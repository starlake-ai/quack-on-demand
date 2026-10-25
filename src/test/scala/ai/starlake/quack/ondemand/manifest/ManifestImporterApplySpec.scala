// src/test/scala/ai/starlake/quack/ondemand/manifest/ManifestImporterApplySpec.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{PoolKey, Role, RunningNode, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import at.favre.lib.crypto.bcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManifestImporterApplySpec extends AnyFlatSpec with Matchers:

  private def base = ConfigManifest(
    apiVersion = ConfigManifest.ApiVersion,
    kind = ConfigManifest.Kind,
    exportedAt = Instant.EPOCH,
    exportedFrom = ExportedFrom("0.2.0", "test")
  )

  /** Seed an admin superuser with a known bcrypt-hashed password so the snapshot fallback path has
    * something to read.
    */
  private def storeWithAdmin: InMemoryControlPlaneStore =
    val s   = new InMemoryControlPlaneStore()
    val pre = BCrypt.withDefaults().hashToString(12, "old-secret".toCharArray)
    s.upsertUserWithHash(tenant = None, username = "admin", passwordHash = pre, kind = "admin")
    s

  "ManifestImporter.apply" should "bcrypt plaintext passwords" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(users =
      List(
        ManifestUser(tenant = None, username = "admin", password = Some("hunter2"), kind = "admin")
      )
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    val stored = s.getPasswordHash(None, "admin").get
    BCrypt.verifyer().verify("hunter2".toCharArray, stored).verified shouldBe true
  }

  it should "preserve an already-bcrypt password verbatim" in {
    val s   = new InMemoryControlPlaneStore()
    val pre = BCrypt.withDefaults().hashToString(12, "hunter2".toCharArray)
    val m   = base.copy(users =
      List(ManifestUser(tenant = None, username = "admin", password = Some(pre), kind = "admin"))
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.getPasswordHash(None, "admin").get shouldBe pre
  }

  it should "reuse the existing password hash when the user has no password field" in {
    val s   = storeWithAdmin
    val pre = s.getPasswordHash(None, "admin").get
    val m   = base.copy(users =
      List(ManifestUser(tenant = None, username = "admin", password = None, kind = "admin"))
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.getPasswordHash(None, "admin").get shouldBe pre
  }

  it should "store a tenant-scoped user under the tenant id (the slug key)" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(
      tenants = List(ManifestTenant(name = "acme")),
      users = List(
        ManifestUser(
          tenant = Some("acme"),
          username = "alice",
          password = Some("pw"),
          kind = "user"
        )
      )
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())

    // The tenant id IS the slug "acme" (no separate surrogate). qodstate_user.tenant
    // holds that id, which is what listUsers / findUserForLogin and `?tenant=acme`
    // query against.
    val tenantId = s.listTenants().find(_.displayName == "acme").map(_.id).get
    tenantId shouldBe "acme"
    s.findUser(Some("acme"), "alice") should not be empty
    s.listUsers(Some("acme")).map(_.username) shouldBe List("alice")
  }

  it should "reject a new user without a password field and no prior credential" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(users =
      List(ManifestUser(tenant = None, username = "newbie", password = None, kind = "user"))
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(_.contains("newbie")) shouldBe true
  }

  it should "reject an email-format username whose manifest email differs" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(users =
      List(
        ManifestUser(
          tenant = None,
          username = "alice@example.com",
          password = Some("hunter2"),
          kind = "user",
          email = Some("someone-else@example.com")
        )
      )
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(_.contains("alice@example.com")) shouldBe true
    s.findUser(None, "alice@example.com") shouldBe empty
  }

  it should "import an email-format username with a matching or null email and derive email = username" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(users =
      List(
        ManifestUser(
          tenant = None,
          username = "bob@example.com",
          password = Some("hunter2"),
          kind = "user",
          email = None
        ),
        ManifestUser(
          tenant = None,
          username = "carol@example.com",
          password = Some("hunter2"),
          kind = "user",
          email = Some("carol@example.com")
        )
      )
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.findUser(None, "bob@example.com").get.email shouldBe Some("bob@example.com")
    s.findUser(None, "carol@example.com").get.email shouldBe Some("carol@example.com")
  }

  it should "leave tenants absent from the YAML untouched" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "t-untouched", displayName = "untouched"))
    ManifestImporter.apply(base, s, requireEncryption = false) shouldBe Right(())
    s.listTenants().map(_.displayName) should contain("untouched")
  }

  it should "drop tenant-db registry rows under a tenant with tenantDbs: [] but never call dropDatabase" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "t-tpch", displayName = "tpch"))
    s.upsertTenantDb(
      TenantDb(
        id = "td-1",
        tenantId = "t-tpch",
        name = "tpch_tpch1",
        kind = TenantDbKind.DuckLake,
        metastore = Map.empty,
        dataPath = "/tmp/data"
      )
    )

    val m = base.copy(tenants = List(ManifestTenant(name = "tpch", tenantDbs = Nil)))
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())

    // Registry row gone. (We never call `dbAdmin.dropDatabase` -- the importer
    // has no DbAdmin handle at all, so there is nothing to assert beyond the
    // registry deletion.)
    s.listTenantDbs("t-tpch") shouldBe empty
  }

  it should "reject a tenant-db carrying an injected pg connection param via TenantDb.validate" in {
    val s   = new InMemoryControlPlaneStore()
    val mtd = ManifestTenantDb(
      name = "acme_bi",
      kind = "ducklake",
      metastore = Map(
        "pgHost"     -> "localhost",
        "pgPort"     -> "5432",
        "pgUser"     -> "u",
        "pgPassword" -> "p'; ATTACH 'evil' AS e; --",
        "dbName"     -> "acme_bi",
        "schemaName" -> "main"
      ),
      dataPath = "/tmp/d"
    )
    val m   = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    val res = ManifestImporter.apply(m, s, requireEncryption = false)
    res.isLeft shouldBe true
    res.swap.getOrElse(Nil).mkString("\n") should include("pgPassword")
    // The offending row was NOT persisted.
    s.listTenantDbs("acme") shouldBe empty
  }

  it should "reject a tenant-db carrying an injected dataPath via TenantDb.validate" in {
    val s   = new InMemoryControlPlaneStore()
    val mtd = ManifestTenantDb(
      name = "acme_bi",
      kind = "ducklake",
      metastore = Map(
        "pgHost"     -> "localhost",
        "pgPort"     -> "5432",
        "pgUser"     -> "u",
        "pgPassword" -> "p",
        "dbName"     -> "acme_bi",
        "schemaName" -> "main"
      ),
      dataPath = "/tmp/d'); ATTACH 'evil' AS e --"
    )
    val m   = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    val res = ManifestImporter.apply(m, s, requireEncryption = false)
    res.isLeft shouldBe true
    res.swap.getOrElse(Nil).mkString("\n") should include("dataPath")
  }

  it should "reject a tenant-db carrying a semicolon in an objectStore value via TenantDb.validateSafety" in {
    val s   = new InMemoryControlPlaneStore()
    val mtd = ManifestTenantDb(
      name = "acme_bi",
      kind = "ducklake",
      metastore = Map(
        "pgHost"     -> "localhost",
        "pgPort"     -> "5432",
        "pgUser"     -> "u",
        "pgPassword" -> "p",
        "dbName"     -> "acme_bi",
        "schemaName" -> "main"
      ),
      dataPath = "/tmp/d",
      objectStore = Map("azure_account_key" -> "key;BlobEndpoint=https://evil")
    )
    val m   = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    val res = ManifestImporter.apply(m, s, requireEncryption = false)
    res.isLeft shouldBe true
    res.swap.getOrElse(Nil).mkString("\n") should include("azure_account_key")
    // The offending row was NOT persisted.
    s.listTenantDbs("acme") shouldBe empty
  }

  it should "round-trip the encrypted flag onto the upserted tenant-db row" in {
    val s   = new InMemoryControlPlaneStore()
    val mtd = ManifestTenantDb(
      name = "acme_secure",
      kind = "duckdb-file",
      metastore = Map("dbName" -> "acme_secure", "schemaName" -> "main"),
      dataPath = "/tmp/d",
      encrypted = true
    )
    val m = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.listTenantDbs("acme").find(_.name == "acme_secure").get.encrypted shouldBe true
  }

  it should "carry an existing row's encryptionKey forward when the manifest omits it" in {
    // Mirrors what a real re-import sees: ManifestExporter redacts encryptionKey out of the
    // metastore map, so a manifest built from a live encrypted duckdb-file database never
    // carries the key. Re-applying that manifest onto the SAME row (matched by name) must not
    // wipe the key the running node is relying on to open the file.
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    s.upsertTenantDb(
      TenantDb(
        id = "td-secure",
        tenantId = "acme",
        name = "acme_secure",
        kind = TenantDbKind.DuckDbFile,
        metastore = Map(
          "dbName"        -> "acme_secure",
          "schemaName"    -> "main",
          "encryptionKey" -> "live-secret-key"
        ),
        dataPath = "/tmp/d",
        encrypted = true
      )
    )
    val mtd = ManifestTenantDb(
      name = "acme_secure",
      kind = "duckdb-file",
      metastore = Map("dbName" -> "acme_secure", "schemaName" -> "main"), // no encryptionKey
      dataPath = "/tmp/d",
      encrypted = true
    )
    val m = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.listTenantDbs("acme")
      .find(_.name == "acme_secure")
      .get
      .metastore
      .get("encryptionKey") shouldBe Some("live-secret-key")
  }

  it should "mint an encryptionKey for a new encrypted duckdb-file row that supplies none" in {
    // The REST/MCP path mints a key at PoolSupervisor.createDatabase when the caller declares
    // encrypted=true and supplies no key. A manifest row expressing the identical intent (fresh
    // row, encrypted=true, no key anywhere) bypasses PoolSupervisor entirely, so the importer
    // must mint one itself -- otherwise the spawn script ATTACHes with an empty encryption key
    // (scripts/spawn-quack-node.sh), silently downgrading a declared-encrypted database to
    // unencrypted.
    val s   = new InMemoryControlPlaneStore()
    val mtd = ManifestTenantDb(
      name = "acme_fresh",
      kind = "duckdb-file",
      metastore = Map("dbName" -> "acme_fresh", "schemaName" -> "main"), // no encryptionKey
      dataPath = "/tmp/d",
      encrypted = true
    )
    val m = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    val mintedKey = s
      .listTenantDbs("acme")
      .find(_.name == "acme_fresh")
      .get
      .metastore
      .get("encryptionKey")
    mintedKey shouldBe Symbol("nonEmpty")
    // `Some("")` would also satisfy `nonEmpty` above (a non-empty Option holding an empty
    // string) -- assert on the minted string itself so an empty mint cannot pass this test.
    mintedKey.get should not be empty
    mintedKey.get.length should be >= 16
  }

  it should "not let an empty-string encryptionKey in the manifest overwrite a live key" in {
    // `contains` alone treats a present-but-empty "" the same as a real key ("incoming wins"),
    // which would silently wipe a live database's key and leave it unopenable. The manifest must
    // treat an empty string the same as "omitted" and carry the existing key forward.
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    s.upsertTenantDb(
      TenantDb(
        id = "td-secure",
        tenantId = "acme",
        name = "acme_secure",
        kind = TenantDbKind.DuckDbFile,
        metastore = Map(
          "dbName"        -> "acme_secure",
          "schemaName"    -> "main",
          "encryptionKey" -> "live-secret-key"
        ),
        dataPath = "/tmp/d",
        encrypted = true
      )
    )
    val mtd = ManifestTenantDb(
      name = "acme_secure",
      kind = "duckdb-file",
      metastore = Map("dbName" -> "acme_secure", "schemaName" -> "main", "encryptionKey" -> ""),
      dataPath = "/tmp/d",
      encrypted = true
    )
    val m = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.listTenantDbs("acme")
      .find(_.name == "acme_secure")
      .get
      .metastore
      .get("encryptionKey") shouldBe Some("live-secret-key")
  }

  it should "refuse turning encryption off on an existing encrypted row, with a clear message" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    s.upsertTenantDb(
      TenantDb(
        id = "td-secure",
        tenantId = "acme",
        name = "acme_secure",
        kind = TenantDbKind.DuckDbFile,
        metastore = Map(
          "dbName"        -> "acme_secure",
          "schemaName"    -> "main",
          "encryptionKey" -> "live-secret-key"
        ),
        dataPath = "/tmp/d",
        encrypted = true
      )
    )
    val mtd = ManifestTenantDb(
      name = "acme_secure",
      kind = "duckdb-file",
      metastore = Map("dbName" -> "acme_secure", "schemaName" -> "main"),
      dataPath = "/tmp/d",
      encrypted = false
    )
    val m   = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    val res = ManifestImporter.apply(m, s, requireEncryption = false)
    res.isLeft shouldBe true
    res.swap.getOrElse(Nil).mkString("\n") should include(
      "encryption cannot be turned off for an existing database"
    )
    // The live row's key must survive the refused apply untouched.
    s.listTenantDbs("acme")
      .find(_.name == "acme_secure")
      .get
      .metastore
      .get("encryptionKey") shouldBe Some("live-secret-key")
  }

  it should "refuse turning encryption on for an existing unencrypted row, with a clear message" in {
    // Turning encryption ON in place is exactly as impossible as turning it off: DuckDB cannot
    // encrypt an already-written, unencrypted file, so admitting this would mint a key and store
    // encrypted=true against a row whose data on disk is still plaintext -- the next node spawn
    // would ATTACH an unencrypted file with an ENCRYPTION_KEY clause.
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    s.upsertTenantDb(
      TenantDb(
        id = "td-plain",
        tenantId = "acme",
        name = "acme_plain",
        kind = TenantDbKind.DuckDbFile,
        metastore = Map("dbName" -> "acme_plain", "schemaName" -> "main"),
        dataPath = "/tmp/d",
        encrypted = false
      )
    )
    val mtd = ManifestTenantDb(
      name = "acme_plain",
      kind = "duckdb-file",
      metastore = Map("dbName" -> "acme_plain", "schemaName" -> "main"),
      dataPath = "/tmp/d",
      encrypted = true
    )
    val m   = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    val res = ManifestImporter.apply(m, s, requireEncryption = false)
    res.isLeft shouldBe true
    res.swap.getOrElse(Nil).mkString("\n") should include(
      "encryption cannot be turned on for an existing database"
    )
    // The live row must survive the refused apply untouched: still unencrypted, no key minted.
    val row = s.listTenantDbs("acme").find(_.name == "acme_plain").get
    row.encrypted shouldBe false
    row.metastore.get("encryptionKey") shouldBe empty
  }

  // ---------- QOD_REQUIRE_ENCRYPTION is a deployment policy, not a REST-only one ----------

  /** The importer upserts tenant-db rows directly instead of going through
    * `PoolSupervisor.createTenantDb`, so the knob `TenantDbHandlers` enforces on database/create
    * has to be enforced here too. Otherwise a manifest apply is an open side door and the
    * documented "no plaintext database can exist in the deployment" guarantee is false.
    */
  it should "refuse to create an unencrypted tenant-db when encryption is required" in {
    val s   = new InMemoryControlPlaneStore()
    val mtd = ManifestTenantDb(
      name = "acme_plain",
      kind = "duckdb-file",
      metastore = Map("dbName" -> "acme_plain", "schemaName" -> "main"),
      dataPath = "/tmp/d",
      encrypted = false
    )
    val m   = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    val res = ManifestImporter.apply(m, s, requireEncryption = true)
    res.isLeft shouldBe true
    res.swap.getOrElse(Nil).mkString("\n") should include("QOD_REQUIRE_ENCRYPTION")
    // Refused means NOT created: a plaintext row must not survive the failed apply.
    s.listTenantDbs("acme").find(_.name == "acme_plain") shouldBe empty
  }

  it should "refuse to create a kind=memory tenant-db when encryption is required" in {
    // Same wording as the REST refusal: memory cannot satisfy the policy at all, rather than
    // being told to pass encrypted=true (which the model would then reject on its own).
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          tenantDbs = List(ManifestTenantDb(name = "acme_mem", kind = "memory"))
        )
      )
    )
    val res = ManifestImporter.apply(m, s, requireEncryption = true)
    res.isLeft shouldBe true
    res.swap.getOrElse(Nil).mkString("\n") should include("kind=memory cannot satisfy it")
  }

  it should "still create an encrypted tenant-db when encryption is required" in {
    val s   = new InMemoryControlPlaneStore()
    val mtd = ManifestTenantDb(
      name = "acme_safe",
      kind = "duckdb-file",
      metastore = Map("dbName" -> "acme_safe", "schemaName" -> "main"),
      dataPath = "/tmp/d",
      encrypted = true
    )
    val m = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    ManifestImporter.apply(m, s, requireEncryption = true) shouldBe Right(())
    s.listTenantDbs("acme").find(_.name == "acme_safe").get.encrypted shouldBe true
  }

  it should "keep applying an existing unencrypted row when encryption is required" in {
    // The knob gates CREATES only, exactly as documented: turning it on must not make an
    // operator's own manifest unreplayable against the databases they already run.
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    s.upsertTenantDb(
      TenantDb(
        id = "td-plain",
        tenantId = "acme",
        name = "acme_plain",
        kind = TenantDbKind.DuckDbFile,
        metastore = Map("dbName" -> "acme_plain", "schemaName" -> "main"),
        dataPath = "/tmp/d",
        encrypted = false
      )
    )
    val mtd = ManifestTenantDb(
      name = "acme_plain",
      kind = "duckdb-file",
      metastore = Map("dbName" -> "acme_plain", "schemaName" -> "s2"),
      dataPath = "/tmp/d",
      encrypted = false
    )
    val m = base.copy(tenants = List(ManifestTenant(name = "acme", tenantDbs = List(mtd))))
    ManifestImporter.apply(m, s, requireEncryption = true) shouldBe Right(())
    s.listTenantDbs("acme").find(_.name == "acme_plain").get.metastore("schemaName") shouldBe "s2"
  }

  it should "sweep node rows when an import drops a pool" in {
    val s = new InMemoryControlPlaneStore()
    // Seed: tenant + tenant-db + pool + one node row, as if a manager had run.
    val m1 = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          tenantDbs = List(ManifestTenantDb(name = "acme_default", kind = "memory")),
          pools = List(
            ManifestPool(
              name = "sales",
              tenantDb = "acme_default",
              roleDistribution = ManifestRoleDistribution(0, 0, 1)
            )
          )
        )
      )
    )
    ManifestImporter.apply(m1, s, requireEncryption = false) shouldBe Right(())
    val db  = s.snapshot().tenantDbs.find(_.name == "acme_default").get
    val pid = s.listPools(db.id).head.id
    s.upsertNode(
      RunningNode(
        "quack-acme-acme-default-sales-1",
        PoolKey("acme", "acme_default", "sales"),
        Role.Dual,
        "127.0.0.1",
        21900,
        "tok",
        None,
        None,
        Instant.EPOCH,
        maxConcurrent = 4
      ),
      pid
    )
    // Re-import WITHOUT the pool: rows must be swept, not stranded or FK-blocked.
    val m2 = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          tenantDbs = List(ManifestTenantDb(name = "acme_default", kind = "memory"))
        )
      )
    )
    ManifestImporter.apply(m2, s, requireEncryption = false) shouldBe Right(())
    s.listNodes(pid) shouldBe Nil
    s.snapshot().pools shouldBe Nil
  }

  it should "delete a dropped tenant-db's pools (and their node rows) before the tenant-db row" in {
    val s  = new InMemoryControlPlaneStore()
    val m1 = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          tenantDbs = List(ManifestTenantDb(name = "acme_default", kind = "memory")),
          pools = List(
            ManifestPool(
              name = "sales",
              tenantDb = "acme_default",
              roleDistribution = ManifestRoleDistribution(0, 0, 1)
            )
          )
        )
      )
    )
    ManifestImporter.apply(m1, s, requireEncryption = false) shouldBe Right(())
    val db  = s.snapshot().tenantDbs.find(_.name == "acme_default").get
    val pid = s.listPools(db.id).head.id
    s.upsertNode(
      RunningNode(
        "quack-acme-acme-default-sales-1",
        PoolKey("acme", "acme_default", "sales"),
        Role.Dual,
        "127.0.0.1",
        21900,
        "tok",
        None,
        None,
        Instant.EPOCH,
        maxConcurrent = 4
      ),
      pid
    )
    // Re-import with the tenant but WITHOUT the tenant-db: today this hits the
    // tenant-db FK because pools do NOT cascade.
    val m2 = base.copy(tenants = List(ManifestTenant(name = "acme")))
    ManifestImporter.apply(m2, s, requireEncryption = false) shouldBe Right(())
    s.snapshot().tenantDbs shouldBe Nil
    s.snapshot().pools shouldBe Nil
  }

  "ManifestImporter.apply acl" should "keep the existing opaToken when the manifest carries the redacted marker" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(
      Tenant(
        id = "acme",
        displayName = "acme",
        acl = ai.starlake.quack.model
          .TenantAcl(Some("opa"), Some("http://opa"), None, Some("real-token"))
      )
    )
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          acl = Some(
            ManifestTenantAcl(
              mode = Some("opa"),
              opaUrl = Some("http://opa"),
              opaToken = Some(ai.starlake.quack.model.FederatedSecret.RedactedMarker)
            )
          )
        )
      )
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.listTenants().find(_.id == "acme").get.acl.opaToken shouldBe Some("real-token")
  }

  it should "switch an existing tenant's acl to the manifest's" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          acl = Some(ManifestTenantAcl(mode = Some("opa"), opaUrl = Some("http://opa")))
        )
      )
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.listTenants().find(_.id == "acme").get.acl shouldBe
      ai.starlake.quack.model.TenantAcl(Some("opa"), Some("http://opa"))
  }

  it should "refuse to carry a redacted opaToken over to a changed opaUrl" in {
    val s           = new InMemoryControlPlaneStore()
    val existingAcl =
      ai.starlake.quack.model.TenantAcl(Some("opa"), Some("http://opa"), None, Some("real-token"))
    s.upsertTenant(Tenant(id = "acme", displayName = "acme", acl = existingAcl))
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          acl = Some(
            ManifestTenantAcl(
              mode = Some("opa"),
              opaUrl = Some("http://elsewhere"),
              opaToken = Some(ai.starlake.quack.model.FederatedSecret.RedactedMarker)
            )
          )
        )
      )
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("opaToken") && e.contains("opaUrl")) shouldBe
      true
    // Caught before any write: the stored token never followed the new URL.
    s.listTenants().find(_.id == "acme").get.acl shouldBe existingAcl
  }

  it should "error when a new tenant's manifest carries a redacted opaToken with nothing to reuse" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          acl = Some(
            ManifestTenantAcl(
              mode = Some("opa"),
              opaToken = Some(ai.starlake.quack.model.FederatedSecret.RedactedMarker)
            )
          )
        )
      )
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("opaToken")) shouldBe true
    // Caught before any write: the tenant was never created.
    s.listTenants().find(_.id == "acme") shouldBe None
  }

  it should "error when an existing tenant with no stored token gets a manifest with a redacted opaToken" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          acl = Some(
            ManifestTenantAcl(
              mode = Some("opa"),
              opaToken = Some(ai.starlake.quack.model.FederatedSecret.RedactedMarker)
            )
          )
        )
      )
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("opaToken")) shouldBe true
    // Caught before any write: the stored acl stays at its (default) prior value.
    s.listTenants().find(_.id == "acme").get.acl shouldBe ai.starlake.quack.model.TenantAcl()
  }

  it should "write nothing when one tenant's acl is invalid even though another tenant's pool is valid" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          tenantDbs = List(ManifestTenantDb(name = "acme_default", kind = "memory")),
          pools = List(
            ManifestPool(
              name = "sales",
              tenantDb = "acme_default",
              roleDistribution = ManifestRoleDistribution(0, 0, 1)
            )
          )
        ),
        ManifestTenant(
          name = "globex",
          acl = Some(ManifestTenantAcl(mode = Some("bogus")))
        )
      )
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("globex") && e.contains("mode")) shouldBe true
    // Nothing from tenant "acme" was written either: the whole import fails before any write.
    s.listTenants() shouldBe empty
    s.snapshot().tenantDbs shouldBe empty
    s.snapshot().pools shouldBe empty
  }

  it should "normalize an empty acl mode to the manager default" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(ManifestTenant(name = "acme", acl = Some(ManifestTenantAcl(mode = Some("")))))
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.listTenants().find(_.id == "acme").get.acl.mode shouldBe None
  }

  it should "trim a padded opaUrl" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          acl = Some(ManifestTenantAcl(mode = Some("opa"), opaUrl = Some("  http://opa  ")))
        )
      )
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.listTenants().find(_.id == "acme").get.acl.opaUrl shouldBe Some("http://opa")
  }

  it should "write a plain opaToken value verbatim" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          acl = Some(ManifestTenantAcl(mode = Some("opa"), opaToken = Some("fresh-token")))
        )
      )
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.listTenants().find(_.id == "acme").get.acl.opaToken shouldBe Some("fresh-token")
  }

  it should "leave an existing tenant's acl untouched when the manifest's acl is absent" in {
    val s           = new InMemoryControlPlaneStore()
    val existingAcl =
      ai.starlake.quack.model.TenantAcl(Some("opa"), Some("http://opa"), None, Some("real-token"))
    s.upsertTenant(Tenant(id = "acme", displayName = "acme", acl = existingAcl))
    val m = base.copy(tenants = List(ManifestTenant(name = "acme", acl = None)))
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    s.listTenants().find(_.id == "acme").get.acl shouldBe existingAcl
  }

  it should "reject an invalid acl mode" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(ManifestTenant(name = "acme", acl = Some(ManifestTenantAcl(mode = Some("bogus")))))
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("mode")) shouldBe true
  }

  it should "reject an invalid opaUrl" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(ManifestTenant(name = "acme", acl = Some(ManifestTenantAcl(opaUrl = Some("not a url")))))
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("opaUrl")) shouldBe true
  }

  it should "reject an opaUrl with credentials without echoing them" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          acl = Some(ManifestTenantAcl(opaUrl = Some("http://u:s3cr3t@opa:8181")))
        )
      )
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("opaUrl")) shouldBe true
    err.exists(_.contains("s3cr3t")) shouldBe false
  }

  it should "reject an invalid opaPolicyPath" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          acl = Some(ManifestTenantAcl(opaPolicyPath = Some("bad path!")))
        )
      )
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("opaPolicyPath")) shouldBe true
  }

  // ------------------------------------------------------------------
  // Tenant client secret (Tenant.authClientSecret): the opaToken rules, bound to issuerUrl.
  // ------------------------------------------------------------------

  private val Marker      = ai.starlake.quack.model.FederatedSecret.RedactedMarker
  private val Issuer      = "https://idp.example/realms/acme"
  private val OtherIssuer = "https://other-idp.example/realms/acme"

  /** Tenant `acme` signing in to the admin console through `issuer`, holding `secret`. */
  private def ssoTenant(secret: Option[String], issuer: String = Issuer): Tenant =
    Tenant(
      id = "acme",
      displayName = "acme",
      authProvider = "keycloak",
      authConfig = Map("issuerUrl" -> issuer, "clientId" -> "qod-admin"),
      authClientSecret = secret
    )

  /** The same tenant in manifest shape, carrying `clientSecret` as given. */
  private def ssoManifestTenant(
      clientSecret: Option[String],
      issuer: String = Issuer
  ): ManifestTenant =
    ManifestTenant(
      name = "acme",
      authProvider = "keycloak",
      authConfig = Map("issuerUrl" -> issuer, "clientId" -> "qod-admin"),
      clientSecret = clientSecret
    )

  private def storedAcme(s: InMemoryControlPlaneStore): Tenant =
    s.listTenants().find(_.id == "acme").get

  "ManifestImporter.apply clientSecret" should "keep the stored client secret for the redaction marker and the same issuerUrl" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(ssoTenant(Some("cs-real")))
    val m = base.copy(tenants = List(ssoManifestTenant(Some(Marker))))
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    storedAcme(s).authClientSecret shouldBe Some("cs-real")
  }

  it should "refuse the redaction marker with a changed issuerUrl, leaving the tenant unchanged" in {
    val s      = new InMemoryControlPlaneStore()
    val before = ssoTenant(Some("cs-real"))
    s.upsertTenant(before)
    val m   = base.copy(tenants = List(ssoManifestTenant(Some(Marker), issuer = OtherIssuer)))
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e =>
      e.contains("acme") && e.contains("clientSecret") && e.contains("issuerUrl")
    ) shouldBe true
    // Caught before any write: the stored secret never followed the new issuer.
    storedAcme(s) shouldBe before
  }

  it should "refuse the redaction marker for a new tenant, creating nothing" in {
    val s   = new InMemoryControlPlaneStore()
    val m   = base.copy(tenants = List(ssoManifestTenant(Some(Marker))))
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("clientSecret")) shouldBe true
    s.listTenants().find(_.id == "acme") shouldBe None
  }

  it should "refuse the redaction marker for a tenant with no stored client secret, leaving it unchanged" in {
    val s      = new InMemoryControlPlaneStore()
    val before = ssoTenant(None)
    s.upsertTenant(before)
    val m   = base.copy(tenants = List(ssoManifestTenant(Some(Marker))))
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("clientSecret")) shouldBe true
    storedAcme(s) shouldBe before
  }

  it should "store a plain client secret value as given for a new tenant" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants = List(ssoManifestTenant(Some("cs-fresh"))))
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    storedAcme(s).authClientSecret shouldBe Some("cs-fresh")
  }

  it should "replace the stored client secret with a plain value, a changed issuerUrl included" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(ssoTenant(Some("cs-old")))
    val m = base.copy(tenants = List(ssoManifestTenant(Some("cs-new"), issuer = OtherIssuer)))
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    storedAcme(s).authClientSecret shouldBe Some("cs-new")
    storedAcme(s).authConfig.get("issuerUrl") shouldBe Some(OtherIssuer)
  }

  it should "store no client secret when the manifest omits it or sends an empty value" in
    List(None, Some("")).foreach { incoming =>
      val s = new InMemoryControlPlaneStore()
      s.upsertTenant(ssoTenant(Some("cs-real")))
      val m = base.copy(tenants = List(ssoManifestTenant(incoming)))
      ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
      storedAcme(s).authClientSecret shouldBe None
    }

  it should "refuse a whitespace-only client secret, leaving the tenant unchanged" in {
    val s      = new InMemoryControlPlaneStore()
    val before = ssoTenant(Some("cs-real"))
    s.upsertTenant(before)
    val m   = base.copy(tenants = List(ssoManifestTenant(Some("   "))))
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("clientSecret")) shouldBe true
    storedAcme(s) shouldBe before
  }

  it should "refuse an authConfig carrying a clientSecret key without echoing its value" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          authProvider = "keycloak",
          authConfig =
            Map("issuerUrl" -> Issuer, "clientId" -> "qod-admin", "clientSecret" -> "cs-in-map")
        )
      )
    )
    val err = ManifestImporter.apply(m, s, requireEncryption = false).left.toOption.get
    err.exists(e => e.contains("acme") && e.contains("clientSecret")) shouldBe true
    err.exists(_.contains("cs-in-map")) shouldBe false
    s.listTenants().find(_.id == "acme") shouldBe None
  }

  it should "refuse a client secret, plain or redacted, together with a clientSecretRef, leaving the tenant unchanged" in
    // The stored value makes the marker case one that would otherwise be kept (same issuerUrl):
    // the one-source rule refuses it first.
    List(Some("cs-new"), Some(Marker)).foreach { incoming =>
      val s      = new InMemoryControlPlaneStore()
      val before = ssoTenant(Some("cs-real"))
      s.upsertTenant(before)
      val withRef = ssoManifestTenant(incoming).copy(authConfig =
        Map("issuerUrl" -> Issuer, "clientId" -> "qod-admin", "clientSecretRef" -> "env:ACME_CS")
      )
      val err = ManifestImporter
        .apply(base.copy(tenants = List(withRef)), s, requireEncryption = false)
        .left
        .toOption
        .get
      err.exists(e =>
        e.contains("acme") && e.contains("a client secret or a clientSecretRef, not both")
      ) shouldBe true
      // Caught before any write: the stored row keeps its one source.
      storedAcme(s) shouldBe before
    }

  it should "import a clientSecretRef as written, a changed issuerUrl included" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(
      ssoTenant(None).copy(authConfig =
        Map(
          "issuerUrl"       -> Issuer,
          "clientId"        -> "qod-admin",
          "clientSecretRef" -> "env:ACME_CS_OLD"
        )
      )
    )
    val m = base.copy(tenants =
      List(
        ManifestTenant(
          name = "acme",
          authProvider = "keycloak",
          authConfig = Map(
            "issuerUrl"       -> OtherIssuer,
            "clientId"        -> "qod-admin",
            "clientSecretRef" -> "env:ACME_CS_NEW"
          )
        )
      )
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    storedAcme(s).authConfig.get("clientSecretRef") shouldBe Some("env:ACME_CS_NEW")
  }
