// src/test/scala/ai/starlake/quack/ondemand/manifest/ManifestRoundTripSpec.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  FederatedSecret,
  FederatedSource,
  FederatedSourceType,
  Pool,
  RoleDistribution,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.federation.iceberg.{IcebergAuthType, IcebergRestConfig}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  InMemoryFederatedSourceStore,
  PoolPermission,
  RbacRole,
  RolePermission
}
import at.favre.lib.crypto.bcrypt.BCrypt
import io.circe.syntax.*
import io.circe.yaml.v12.Printer
import io.circe.yaml.v12.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManifestRoundTripSpec extends AnyFlatSpec with Matchers:

  private val Yaml = Printer.builder.withDropNullKeys(true).build()

  private val ExportedAt   = Instant.parse("2026-06-05T12:00:00Z")
  private val AdminVersion = "0.2.0"
  private val Hostname     = "test"

  /** Minimal no-op backend so a PoolSupervisor can be built over an imported store to exercise the
    * authorizeHandshake login gate. restore() only rehydrates caches; no process is spawned.
    * Mirrors the stub in FlightHandshakeSecuritySpec.
    */
  private def stubBackend: QuackBackend = StubQuackBackend.noop()

  private def buildSupervisor(store: InMemoryControlPlaneStore): PoolSupervisor =
    val sup = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup.restore()
    sup

  private val adminHash = BCrypt.withDefaults().hashToString(12, "admin-secret".toCharArray)
  private val aliceHash = BCrypt.withDefaults().hashToString(12, "alice-secret".toCharArray)

  /** Build and populate the source store with a representative mix. Returns the store plus the user
    * ids for admin and alice so tests can look up role edges.
    */
  private def buildSrc(): InMemoryControlPlaneStore =
    val s = new InMemoryControlPlaneStore()

    // Tenant
    s.upsertTenant(Tenant(id = "tpch", displayName = "tpch", authProvider = "db"))

    // TenantDb
    s.upsertTenantDb(
      TenantDb(
        id = "td-1",
        tenantId = "tpch",
        name = "tpch_tpch1",
        kind = TenantDbKind.DuckLake,
        metastore = Map.empty,
        dataPath = "/tmp/data",
        objectStore = Map.empty,
        initSql = "SET memory_limit = '4GB';"
      )
    )

    // Pool
    s.upsertPool(
      Pool(
        id = "p-1",
        tenantId = "tpch",
        tenantDbId = "td-1",
        name = "sales",
        size = 3,
        distribution = RoleDistribution(writeonly = 1, readonly = 1, dual = 1),
        maxConcurrentPerNode = 0,
        disabled = false,
        suspended = true,
        cpu = "2",
        memory = "8Gi",
        podTemplateYaml = "apiVersion: v1\nkind: Pod\nspec:\n  nodeName: n1\n",
        lockdown = Some(true),
        // writeonly + dual = 2 write-capable nodes, so the floor must be >= 2.
        minNodes = Some(2),
        maxNodes = Some(4)
      )
    )

    // Role with one table permission
    s.upsertRole(
      RbacRole(id = "r-1", tenantId = "tpch", name = "reader", description = Some("read-only"))
    )
    s.insertRolePermission(
      RolePermission(
        id = "rp-1",
        roleId = "r-1",
        catalogName = "tpch_tpch1",
        schemaName = "tpch1",
        tableName = "customer",
        verb = "RO"
      )
    )

    // Superuser admin
    s.upsertUserWithHash(
      tenant = None,
      username = "admin",
      passwordHash = adminHash,
      role = "admin"
    )

    // Tenant-scoped alice, bound to the reader role
    val aliceId = s.upsertUserWithHash(
      tenant = Some("tpch"),
      username = "alice",
      passwordHash = aliceHash,
      role = "user"
    )
    s.addUserRole(aliceId, "r-1")

    s

  /** Replace every tenant-db's federated sources in an already-exported manifest. The federation
    * tests below hand-build sources the store cannot produce (an invalid alias, a blank secret),
    * and they all need the same three-deep rewrite.
    */
  private def withFederatedSources(
      m: ConfigManifest,
      sources: List[ManifestFederatedSource]
  ): ConfigManifest =
    m.copy(tenants = m.tenants.map { mt =>
      mt.copy(tenantDbs = mt.tenantDbs.map(_.copy(federatedSources = sources)))
    })

  // ------------------------------------------------------------------
  // Test 1: structural round-trip
  //
  // SnakeYAML's emitter under circe-yaml-v12 isn't deterministic on
  // map-key order between separate `Printer.pretty` calls, so a strict
  // byte-equality assertion is too brittle. The semantic invariant we
  // care about is that an export -> import -> export cycle yields the
  // same `ConfigManifest` value (which is what backup / restore depends
  // on).
  // ------------------------------------------------------------------

  "ManifestRoundTripSpec" should "round-trip a populated store to the same ConfigManifest value" in {
    val src = buildSrc()

    // Step 1-2: first export
    val manifest1 = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)
    val yaml1     = Yaml.pretty(manifest1.asJson)

    // Step 3-4: parse the YAML back
    val parsed = parser.parse(yaml1).flatMap(_.as[ConfigManifest]).fold(throw _, identity)

    // Step 5: supply passwords so the importer has credentials for a fresh store
    val withPasswords = parsed.copy(
      users = parsed.users.map { u =>
        val hash = (u.tenant, u.username) match
          case (None, "admin")         => adminHash
          case (Some("tpch"), "alice") => aliceHash
          case _                       => aliceHash // safe fallback
        u.copy(password = Some(hash))
      }
    )

    // Step 6: import into a fresh store
    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(withPasswords, dst, requireEncryption = false) shouldBe Right(())

    // Verify initSql is preserved after import
    val restored = dst.listTenantDbs("tpch").head
    restored.initSql shouldBe "SET memory_limit = '4GB';"

    // Verify the Kubernetes pool fields are preserved after import. listPools
    // takes a tenant-db id, and the importer minted a fresh surrogate id for
    // the tenant-db, so list via the restored tenant-db's id.
    val restoredPool = dst.listPools(restored.id).find(_.name == "sales").get
    restoredPool.cpu shouldBe "2"
    restoredPool.memory shouldBe "8Gi"
    restoredPool.podTemplateYaml shouldBe "apiVersion: v1\nkind: Pod\nspec:\n  nodeName: n1\n"
    // The per-pool lockdown override ("on") must survive the export -> import
    // cycle: a manifest import must not silently wipe a superuser's override.
    restoredPool.lockdown shouldBe Some(true)
    // Same guarantee for the scale-to-zero flag: an import must not wake a
    // suspended pool back up by resetting it to false.
    restoredPool.suspended shouldBe true
    // The owner-declared demand scale-out band must survive the export ->
    // import cycle too: the importer's apply path threads minNodes/maxNodes
    // into the Pool(...) build, not just the exporter's read side.
    restoredPool.minNodes shouldBe Some(2)
    restoredPool.maxNodes shouldBe Some(4)

    // Step 7: second export from the imported store
    val manifest2 = ManifestExporter.build(dst, ExportedAt, AdminVersion, Hostname)

    // Manifest1 still carries the synthetic passwords we attached above; the
    // exporter never emits passwords, so manifest2's users all have
    // `password = None`. Strip manifest1's password fields before comparing
    // so the round-trip equality is on the structure the store actually owns.
    val manifest1ForCompare =
      manifest1.copy(users = manifest1.users.map(_.copy(password = None)))

    manifest2 shouldBe manifest1ForCompare
  }

  // ------------------------------------------------------------------
  // Test 2: password preservation across a password-less second import
  // ------------------------------------------------------------------

  it should "preserve existing password hashes when a second import carries no password fields" in {
    val src = buildSrc()

    // Export
    val manifest1 = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)
    val yaml1     = Yaml.pretty(manifest1.asJson)

    // Parse
    val parsed = parser.parse(yaml1).flatMap(_.as[ConfigManifest]).fold(throw _, identity)

    // First import: supply passwords so the fresh store gets credentials
    val withPasswords = parsed.copy(
      users = parsed.users.map { u =>
        val hash = (u.tenant, u.username) match
          case (None, "admin")         => adminHash
          case (Some("tpch"), "alice") => aliceHash
          case _                       => aliceHash
        u.copy(password = Some(hash))
      }
    )
    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(withPasswords, dst, requireEncryption = false) shouldBe Right(())

    // Verify passwords landed correctly after the first import
    dst.getPasswordHash(None, "admin").get shouldBe adminHash

    // Second import: use the original parsed manifest which has NO password fields
    ManifestImporter.apply(parsed, dst, requireEncryption = false) shouldBe Right(())

    // Passwords must be unchanged after the password-less second import.
    // The importer normalizes the user's tenant to the surrogate id it minted
    // for "tpch" on import, so look the credential up by that id, not the name.
    val aliceTenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    dst.getPasswordHash(None, "admin").get shouldBe adminHash
    dst.getPasswordHash(Some(aliceTenantId), "alice").get shouldBe aliceHash
  }

  // ------------------------------------------------------------------
  // Test 3: federation round-trip with redact-and-reuse semantics
  //
  // Uses InMemoryFederatedSourceStore so no Postgres is required.
  // Verifies:
  //   - a source + secret survive export -> import (first pass: real value)
  //   - the value is redacted on export ("***REDACTED***")
  //   - re-importing the redacted YAML over the already-seeded store
  //     reuses the existing value correctly (second pass)
  //   - a third export produces the same structure as the first
  // ------------------------------------------------------------------

  it should "round-trip federated sources and reuse redacted secret values" in {
    val cp = buildSrc()

    // Seed one federated source with one value-backed secret in the source store.
    val srcFed = new InMemoryFederatedSourceStore()
    srcFed.upsertSource(
      FederatedSource(
        id = "fs-1",
        tenantDbId = "td-1",
        alias = "pg_ext",
        setupSql = "ATTACH 'dbname=prod' AS pg_ext (TYPE POSTGRES);",
        description = Some("prod postgres"),
        disabled = false
      )
    )
    srcFed.upsertSecret(
      FederatedSecret(
        id = "fsec-1",
        federatedSourceId = "fs-1",
        name = "PG_PASSWORD",
        value = Some("super-secret"),
        externalRef = None
      )
    )

    // Step 1: export from the populated source store.
    val manifest1 = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(srcFed))
    val yaml1     = Yaml.pretty(manifest1.asJson)

    // The raw value must NOT appear in the YAML; the redaction sentinel must.
    yaml1 should not include "super-secret"
    yaml1 should include("***REDACTED***")
    yaml1 should include("pg_ext")

    // Verify the in-memory manifest carries the redacted value.
    val tdb = manifest1.tenants.head.tenantDbs.head
    tdb.federatedSources should have size 1
    tdb.federatedSources.head.alias shouldBe "pg_ext"
    tdb.federatedSources.head.secrets.head.value shouldBe Some("***REDACTED***")

    // Step 2: first import into a fresh control-plane store, supplying the
    // real value explicitly (not redacted) so the importer can store it.
    val manifest1WithReal = manifest1.copy(
      tenants = manifest1.tenants.map { mt =>
        mt.copy(tenantDbs = mt.tenantDbs.map { mtd =>
          mtd.copy(federatedSources = mtd.federatedSources.map { ms =>
            ms.copy(secrets = ms.secrets.map(_.copy(value = Some("super-secret"))))
          })
        })
      },
      users = manifest1.users.map { u =>
        val hash = (u.tenant, u.username) match
          case (None, "admin")         => adminHash
          case (Some("tpch"), "alice") => aliceHash
          case _                       => aliceHash
        u.copy(password = Some(hash))
      }
    )

    val dstCp  = new InMemoryControlPlaneStore()
    val dstFed = new InMemoryFederatedSourceStore()
    ManifestImporter.apply(
      manifest1WithReal,
      dstCp,
      Some(dstFed),
      requireEncryption = false
    ) shouldBe Right(())

    // Verify the real value landed.
    val tdId    = dstCp.listTenants().flatMap(t => dstCp.listTenantDbs(t.id)).head.id
    val srcList = dstFed.listSources(tdId)
    srcList should have size 1
    srcList.head.alias shouldBe "pg_ext"
    dstFed.listSecrets(srcList.head.id).head.value shouldBe Some("super-secret")

    // Step 3: parse yaml1 (redacted) and re-import over the already-seeded dstFed.
    // The importer must reuse the existing value for the "***REDACTED***" sentinel.
    val parsedRedacted = parser.parse(yaml1).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    val parsedWithPasswords = parsedRedacted.copy(
      users = parsedRedacted.users.map { u =>
        val hash = (u.tenant, u.username) match
          case (None, "admin")         => adminHash
          case (Some("tpch"), "alice") => aliceHash
          case _                       => aliceHash
        u.copy(password = Some(hash))
      }
    )
    ManifestImporter.apply(
      parsedWithPasswords,
      dstCp,
      Some(dstFed),
      requireEncryption = false
    ) shouldBe Right(())

    // The secret value must have been reused.
    val srcList2 = dstFed.listSources(tdId)
    dstFed.listSecrets(srcList2.head.id).head.value shouldBe Some("super-secret")

    // Step 4: third export from the destination stores must produce the same
    // federated-source structure as the first export (both redact the value).
    val manifest3 = ManifestExporter.build(dstCp, ExportedAt, AdminVersion, Hostname, Some(dstFed))

    manifest3.tenants.head.tenantDbs.head.federatedSources shouldBe
      manifest1.tenants.head.tenantDbs.head.federatedSources
  }

  // ------------------------------------------------------------------
  // Test 4: a disabled user stays disabled across export -> import
  // ------------------------------------------------------------------

  it should "keep a disabled user disabled across export then import" in {
    val src = buildSrc()
    // alice starts enabled (buildSrc's default); disable her explicitly by
    // re-upserting with the same hash and enabled = false.
    src.upsertUserWithHash(
      tenant = Some("tpch"),
      username = "alice",
      passwordHash = aliceHash,
      role = "user",
      enabled = false
    )

    val manifest1     = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)
    val aliceManifest = manifest1.users.find(_.username == "alice").get
    aliceManifest.enabled shouldBe false

    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(
      manifest1.copy(users = manifest1.users.map { u =>
        if u.username == "alice" then u.copy(password = Some(aliceHash)) else u
      }),
      dst,
      requireEncryption = false
    ) shouldBe Right(())

    val aliceTenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    dst.findUser(Some(aliceTenantId), "alice").get.enabled shouldBe false

    // Close the loop: the imported disabled user must not merely carry the
    // flag, she must be unable to log in. authorizeHandshake is the FlightSQL
    // login gate; it must deny her with a 'disabled' reason before the
    // pool-access check even runs.
    val sup = buildSupervisor(dst)
    val hs  = sup.authorizeHandshake("tpch", "sales", "alice")
    hs.isLeft shouldBe true
    hs.left.toOption.get.toLowerCase should include("disabled")
  }

  // ------------------------------------------------------------------
  // Test 5: a user's bcrypt hash round-trips byte-identical and still
  // authenticates with the SAME original credential
  // ------------------------------------------------------------------

  it should "round-trip a user's bcrypt hash so the same password still authenticates" in {
    val src = buildSrc()

    val manifest1     = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)
    val aliceManifest = manifest1.users.find(_.username == "alice").get

    // The exporter must carry the REAL hash, not a redaction placeholder,
    // and never the plaintext.
    aliceManifest.password shouldBe None
    aliceManifest.passwordHash shouldBe Some(aliceHash)

    // Import into a fresh store WITHOUT supplying any plaintext password --
    // the passwordHash field alone must be enough to carry the credential
    // forward.
    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(manifest1, dst, requireEncryption = false) shouldBe Right(())

    val aliceTenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    val storedHash    = dst.getPasswordHash(Some(aliceTenantId), "alice").get

    // Byte-identical hash post-roundtrip.
    storedHash shouldBe aliceHash

    // And the ORIGINAL plaintext still authenticates against it.
    BCrypt.verifyer().verify("alice-secret".toCharArray, storedHash).verified shouldBe true
  }

  // ------------------------------------------------------------------
  // Test 5b: mustChangePassword round-trips across export -> import, and a
  // manifest without the field imports as false (backward compatible).
  // ------------------------------------------------------------------

  it should "round-trip a flagged mustChangePassword user across export then import" in {
    val src = buildSrc()
    // alice starts with mustChangePassword = false (buildSrc's default);
    // re-upsert with the same hash and mustChangePassword = true.
    src.upsertUserWithHash(
      tenant = Some("tpch"),
      username = "alice",
      passwordHash = aliceHash,
      role = "user",
      mustChangePassword = true
    )
    // Same for the superuser "admin" (tenant = None): the exporter's
    // superuser branch (ManifestExporter.scala's `superusers` block) is a
    // separate code path from the tenant-scoped branch, so it needs its own
    // coverage rather than relying on alice's assertion alone.
    src.upsertUserWithHash(
      tenant = None,
      username = "admin",
      passwordHash = adminHash,
      role = "admin",
      mustChangePassword = true
    )

    val manifest1     = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)
    val aliceManifest = manifest1.users.find(_.username == "alice").get
    aliceManifest.mustChangePassword shouldBe true
    val adminManifest = manifest1.users.find(_.username == "admin").get
    adminManifest.mustChangePassword shouldBe true

    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(manifest1, dst, requireEncryption = false) shouldBe Right(())

    val aliceTenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    dst.findUser(Some(aliceTenantId), "alice").get.mustChangePassword shouldBe true
    dst.findUser(None, "admin").get.mustChangePassword shouldBe true
  }

  it should "import a manifest without the mustChangePassword field as false" in {
    val src       = buildSrc()
    val manifest1 = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)

    // Simulate an older manifest that predates the field: drop it from the
    // YAML entirely and re-parse, rather than relying on the in-memory case
    // class default (which would trivially pass without exercising decoding).
    val yamlStr      = Yaml.pretty(manifest1.asJson)
    val parsedJson   = parser.parse(yamlStr).toOption.get
    val strippedJson = parsedJson.hcursor
      .downField("users")
      .withFocus(_.mapArray(_.map(_.mapObject(_.remove("mustChangePassword")))))
      .top
      .get
    val stripped = strippedJson.as[ConfigManifest].toOption.get

    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(stripped, dst, requireEncryption = false) shouldBe Right(())

    val aliceTenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    dst.findUser(Some(aliceTenantId), "alice").get.mustChangePassword shouldBe false
  }

  // ------------------------------------------------------------------
  // Test 5c: email round-trips across export -> import, and a manifest
  // without the field imports as None (backward compatible).
  // ------------------------------------------------------------------

  it should "round-trip a user's email across export then import" in {
    val src = buildSrc()
    // alice starts with email = None (buildSrc's default); re-upsert with
    // the same hash and an email set.
    src.upsertUserWithHash(
      tenant = Some("tpch"),
      username = "alice",
      passwordHash = aliceHash,
      role = "user",
      email = Some("alice@x.io")
    )
    // Same for the superuser "admin" (tenant = None): the exporter's
    // superuser branch (ManifestExporter.scala's `superusers` block) is a
    // separate code path from the tenant-scoped branch, so it needs its own
    // coverage rather than relying on alice's assertion alone.
    src.upsertUserWithHash(
      tenant = None,
      username = "admin",
      passwordHash = adminHash,
      role = "admin",
      email = Some("admin@x.io")
    )

    val manifest1     = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)
    val aliceManifest = manifest1.users.find(_.username == "alice").get
    aliceManifest.email shouldBe Some("alice@x.io")
    val adminManifest = manifest1.users.find(_.username == "admin").get
    adminManifest.email shouldBe Some("admin@x.io")

    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(manifest1, dst, requireEncryption = false) shouldBe Right(())

    val aliceTenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    dst.findUser(Some(aliceTenantId), "alice").get.email shouldBe Some("alice@x.io")
    dst.findUser(None, "admin").get.email shouldBe Some("admin@x.io")
  }

  it should "import a manifest without the email field as None" in {
    val src = buildSrc()
    src.upsertUserWithHash(
      tenant = Some("tpch"),
      username = "alice",
      passwordHash = aliceHash,
      role = "user",
      email = Some("alice@x.io")
    )
    val manifest1 = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)

    // Simulate an older manifest that predates the field: drop it from the
    // YAML entirely and re-parse, rather than relying on the in-memory case
    // class default (which would trivially pass without exercising decoding).
    val yamlStr      = Yaml.pretty(manifest1.asJson)
    val parsedJson   = parser.parse(yamlStr).toOption.get
    val strippedJson = parsedJson.hcursor
      .downField("users")
      .withFocus(_.mapArray(_.map(_.mapObject(_.remove("email")))))
      .top
      .get
    val stripped = strippedJson.as[ConfigManifest].toOption.get

    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(stripped, dst, requireEncryption = false) shouldBe Right(())

    val aliceTenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    dst.findUser(Some(aliceTenantId), "alice").get.email shouldBe None
  }

  // ------------------------------------------------------------------
  // Test 6: a superuser pool grant to a pool in a DIFFERENT tenant
  // round-trips to the exact same (tenant, pool) target
  // ------------------------------------------------------------------

  it should "round-trip a superuser's cross-tenant pool grant to the same (tenant, pool)" in {
    val src = buildSrc()

    // A second tenant with its own pool, distinct from "tpch"/"sales".
    src.upsertTenant(Tenant(id = "other", displayName = "other", authProvider = "db"))
    src.upsertTenantDb(
      TenantDb(
        id = "td-2",
        tenantId = "other",
        name = "other_db1",
        kind = TenantDbKind.DuckLake,
        metastore = Map.empty,
        dataPath = "/tmp/other",
        objectStore = Map.empty
      )
    )
    src.upsertPool(
      Pool(
        id = "p-2",
        tenantId = "other",
        tenantDbId = "td-2",
        name = "reporting",
        size = 1,
        distribution = RoleDistribution(writeonly = 0, readonly = 1, dual = 0)
      )
    )

    // Grant the superuser "admin" access to the "reporting" pool in the
    // "other" tenant -- a tenant DIFFERENT from admin's (non-existent) home
    // tenant.
    val adminId = src.findUser(None, "admin").get.id
    src.insertPoolPermission(
      PoolPermission(
        id = "pp-cross",
        tenantId = "other",
        poolId = Some("p-2"),
        userId = Some(adminId),
        groupId = None
      )
    )

    val manifest1     = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)
    val adminManifest = manifest1.users.find(_.username == "admin").get

    // The grant must resolve to the real pool name AND the tenant it
    // actually lives in -- not None, and not "tpch" (admin's export-time
    // tenant context, which does not even apply since admin is a
    // superuser).
    adminManifest.poolGrants shouldBe List(
      ManifestPoolGrant(pool = Some("reporting"), tenant = Some("other"))
    )

    // Round-trip: import into a fresh store and export again; the grant
    // must resolve to the identical (tenant, pool) target.
    val withPasswords = manifest1.copy(users = manifest1.users.map { u =>
      u.copy(password = Some(if u.username == "admin" then adminHash else aliceHash))
    })
    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(withPasswords, dst, requireEncryption = false) shouldBe Right(())

    val manifest2 = ManifestExporter.build(dst, ExportedAt, AdminVersion, Hostname)
    manifest2.users.find(_.username == "admin").get.poolGrants shouldBe
      List(ManifestPoolGrant(pool = Some("reporting"), tenant = Some("other")))

    // And the underlying pool-permission row really points at the pool
    // that lives under the "other" tenant-db, not under "tpch".
    val otherTenantId = dst.listTenants().find(_.displayName == "other").map(_.id).get
    val otherDb       = dst.listTenantDbs(otherTenantId).find(_.name == "other_db1").get
    val reportingPool = dst.listPools(otherDb.id).find(_.name == "reporting").get
    val adminId2      = dst.findUser(None, "admin").get.id
    dst.listPoolPermissionsForUser(adminId2).map(_.poolId) shouldBe List(Some(reportingPool.id))
  }

  // ------------------------------------------------------------------
  // Test 7: a pool manifest that OMITS the lockdown field imports as the
  // default "inherit" (Pool.lockdown == None), so a pre-lockdown YAML never
  // acquires a spurious override.
  // ------------------------------------------------------------------

  it should "import a pool that omits lockdown as inherit (no override)" in {
    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: x, hostname: y }
        |tenants:
        |  - name: tpch
        |    tenantDbs:
        |      - name: tpch_tpch1
        |    pools:
        |      - name: sales
        |        tenantDb: tpch_tpch1
        |        roleDistribution: { writeonly: 0, readonly: 0, dual: 1 }
        |""".stripMargin
    val parsed = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    parsed.tenants.head.pools.head.lockdown shouldBe "inherit"

    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(parsed, dst, requireEncryption = false) shouldBe Right(())

    val tenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    val db       = dst.listTenantDbs(tenantId).find(_.name == "tpch_tpch1").get
    dst.listPools(db.id).find(_.name == "sales").get.lockdown shouldBe None
  }

  // ------------------------------------------------------------------
  // Test 8: an invalid lockdown tri-state is rejected by validation rather
  // than silently coerced.
  // ------------------------------------------------------------------

  it should "reject a pool manifest with an invalid lockdown value" in {
    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: x, hostname: y }
        |tenants:
        |  - name: tpch
        |    tenantDbs:
        |      - name: tpch_tpch1
        |    pools:
        |      - name: sales
        |        tenantDb: tpch_tpch1
        |        roleDistribution: { writeonly: 0, readonly: 0, dual: 1 }
        |        lockdown: banana
        |""".stripMargin
    val parsed = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    val dst    = new InMemoryControlPlaneStore()
    val result = ManifestImporter.apply(parsed, dst, requireEncryption = false)
    result.isLeft shouldBe true
    result.left.toOption.get.exists(_.contains("lockdown")) shouldBe true
  }

  // ------------------------------------------------------------------
  // Test 9: a pool manifest that OMITS the suspended field imports as
  // suspended = false, so a pre-suspend YAML never acquires a spurious
  // scale-to-zero flag.
  // ------------------------------------------------------------------

  it should "import a pool that omits suspended as not suspended" in {
    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: x, hostname: y }
        |tenants:
        |  - name: tpch
        |    tenantDbs:
        |      - name: tpch_tpch1
        |    pools:
        |      - name: sales
        |        tenantDb: tpch_tpch1
        |        roleDistribution: { writeonly: 0, readonly: 0, dual: 1 }
        |""".stripMargin
    val parsed = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    parsed.tenants.head.pools.head.suspended shouldBe false

    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(parsed, dst, requireEncryption = false) shouldBe Right(())

    val tenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    val db       = dst.listTenantDbs(tenantId).find(_.name == "tpch_tpch1").get
    dst.listPools(db.id).find(_.name == "sales").get.suspended shouldBe false
  }

  // ------------------------------------------------------------------
  // Test 10: a pool manifest that OMITS the autoscale band imports as
  // fixed-size (Pool.minNodes/maxNodes == None/None), so a pre-autoscale
  // YAML never acquires a spurious band.
  // ------------------------------------------------------------------

  it should "import a pool that omits the autoscale band as fixed-size" in {
    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: x, hostname: y }
        |tenants:
        |  - name: tpch
        |    tenantDbs:
        |      - name: tpch_tpch1
        |    pools:
        |      - name: sales
        |        tenantDb: tpch_tpch1
        |        roleDistribution: { writeonly: 0, readonly: 0, dual: 1 }
        |""".stripMargin
    val parsed = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    parsed.tenants.head.pools.head.minNodes shouldBe None
    parsed.tenants.head.pools.head.maxNodes shouldBe None

    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(parsed, dst, requireEncryption = false) shouldBe Right(())

    val tenantId = dst.listTenants().find(_.displayName == "tpch").map(_.id).get
    val db       = dst.listTenantDbs(tenantId).find(_.name == "tpch_tpch1").get
    val pool     = dst.listPools(db.id).find(_.name == "sales").get
    pool.minNodes shouldBe None
    pool.maxNodes shouldBe None
  }

  // ------------------------------------------------------------------
  // Test 11: a one-sided autoscale band is rejected by validation rather
  // than silently coerced or applied.
  // ------------------------------------------------------------------

  it should "reject a pool manifest with a one-sided autoscale band" in {
    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: x, hostname: y }
        |tenants:
        |  - name: tpch
        |    tenantDbs:
        |      - name: tpch_tpch1
        |    pools:
        |      - name: sales
        |        tenantDb: tpch_tpch1
        |        roleDistribution: { writeonly: 0, readonly: 0, dual: 1 }
        |        minNodes: 1
        |""".stripMargin
    val parsed = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    val dst    = new InMemoryControlPlaneStore()
    val result = ManifestImporter.apply(parsed, dst, requireEncryption = false)
    result.isLeft shouldBe true
    result.left.toOption.get.exists(_.contains("set together")) shouldBe true
  }

  // ------------------------------------------------------------------
  // Test 12: a typed (iceberg_rest) federated source survives
  // export -> YAML -> import with sourceType, config and readOnly intact.
  //
  // readOnly is the load-bearing one: it is what puts READ_ONLY on the
  // ATTACH the blob builder renders, so losing it here would re-attach the
  // catalog WRITABLE at the next node spawn.
  // ------------------------------------------------------------------

  it should "round-trip an iceberg_rest federated source through export, YAML and import" in {
    val cp     = buildSrc()
    val srcFed = new InMemoryFederatedSourceStore()
    val cfg    = IcebergRestConfig(
      uri = "https://catalog.example.com/api/catalog",
      warehouse = "sales",
      authType = Some(IcebergAuthType.OAuth2),
      clientId = Some("{{secret.CID}}"),
      clientSecret = Some("{{secret.CSEC}}")
    ).toJson
    srcFed.upsertSource(
      FederatedSource(
        id = "fs-ice",
        tenantDbId = "td-1",
        alias = "sales_lake",
        sourceType = FederatedSourceType.IcebergRest,
        config = Some(cfg),
        readOnly = true
      )
    )

    val exported = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(srcFed))
    val msrc     = exported.tenants.head.tenantDbs.head.federatedSources.head
    msrc.sourceType shouldBe "iceberg_rest"
    msrc.readOnly shouldBe true
    msrc.config shouldBe Some(cfg)
    msrc.setupSql shouldBe ""

    // Through the actual wire, not only the in-memory value: a codec that dropped one of the
    // three fields would still satisfy the assertions above.
    val yaml     = Yaml.pretty(exported.asJson)
    val reparsed = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)

    val target = new InMemoryFederatedSourceStore()
    ManifestImporter.apply(reparsed, cp, Some(target)) shouldBe Right(())

    val back = target.listSources("td-1")
    back should have size 1
    back.head.alias shouldBe "sales_lake"
    back.head.sourceType shouldBe FederatedSourceType.IcebergRest
    back.head.config shouldBe Some(cfg)
    back.head.readOnly shouldBe true
    back.head.setupSql shouldBe ""
  }

  // ------------------------------------------------------------------
  // Test 13: a pre-Iceberg manifest (no sourceType / config / readOnly)
  // still decodes, as a WRITABLE sql source.
  // ------------------------------------------------------------------

  it should "decode a pre-Iceberg manifest with no sourceType as a writable sql source" in {
    val direct = ManifestFederatedSource(alias = "pg", setupSql = "ATTACH 'x' AS {{alias}};")
    direct.sourceType shouldBe "sql"
    direct.readOnly shouldBe false
    direct.config shouldBe None

    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: x, hostname: y }
        |tenants:
        |  - name: tpch
        |    tenantDbs:
        |      - name: tpch_tpch1
        |        federatedSources:
        |          - alias: pg
        |            setupSql: ATTACH 'x' AS {{alias}};
        |""".stripMargin
    val parsed  = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    val decoded = parsed.tenants.head.tenantDbs.head.federatedSources.head
    decoded.sourceType shouldBe "sql"
    decoded.readOnly shouldBe false
    decoded.config shouldBe None

    val cp  = buildSrc()
    val fed = new InMemoryFederatedSourceStore()
    ManifestImporter.apply(parsed, cp, Some(fed)) shouldBe Right(())
    val stored = fed.listSources("td-1")
    stored should have size 1
    stored.head.sourceType shouldBe FederatedSourceType.Sql
    stored.head.readOnly shouldBe false
  }

  // ------------------------------------------------------------------
  // Test 14: the importer normalizes an imported alias the way REST create
  // does, and refuses one that cannot be normalized rather than writing a
  // name every other path rejects.
  // ------------------------------------------------------------------

  it should "normalize an imported federated alias and reject one that cannot be normalized" in {
    val cp      = buildSrc()
    val fed     = new InMemoryFederatedSourceStore()
    val tooLong = "a" * 64

    val base    = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(fed))
    val withFed = withFederatedSources(
      base,
      List(
        ManifestFederatedSource(alias = "Sales_Lake", setupSql = "ATTACH 'x' AS {{alias}};"),
        ManifestFederatedSource(alias = tooLong, setupSql = "ATTACH 'y' AS {{alias}};")
      )
    )
    val res = ManifestImporter.apply(withFed, cp, Some(fed))

    res.isLeft shouldBe true
    res.left.toOption.get.exists(_.contains(s"invalid alias '$tooLong'")) shouldBe true

    // The valid one landed lowercased; the invalid one was never written.
    fed.listSources("td-1").map(_.alias) shouldBe List("sales_lake")
  }

  // ------------------------------------------------------------------
  // Test 15: a source with NO secrets re-imports onto its own row.
  //
  // The id used to be recovered from a map flat-mapped over each source's
  // SECRETS, so a secret-less source missed the lookup, minted a fresh id,
  // and (on Postgres) violated uq_fedsrc_tenant_db_alias.
  // ------------------------------------------------------------------

  it should "keep a secret-less source on its own row id across a re-import" in {
    val cp  = buildSrc()
    val fed = new InMemoryFederatedSourceStore()
    fed.upsertSource(
      FederatedSource(
        id = "fs-nosec",
        tenantDbId = "td-1",
        alias = "pg_ext",
        setupSql = "ATTACH 'dbname=prod' AS pg_ext (TYPE POSTGRES);"
      )
    )

    val exported = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(fed))
    exported.tenants.head.tenantDbs.head.federatedSources.head.secrets shouldBe Nil

    ManifestImporter.apply(exported, cp, Some(fed)) shouldBe Right(())

    val rows = fed.listSources("td-1")
    rows should have size 1
    rows.head.id shouldBe "fs-nosec"
    rows.head.alias shouldBe "pg_ext"
  }

  // ------------------------------------------------------------------
  // Test 16: a manifest alias that differs from the stored one only in case
  // updates that row in place (id preserved, secret value reusable) instead
  // of delete-and-recreate.
  // ------------------------------------------------------------------

  it should "update a stored source whose alias differs only in case instead of recreating it" in {
    val cp  = buildSrc()
    val fed = new InMemoryFederatedSourceStore()
    // A legacy row, written before aliases were normalized at the REST layer.
    fed.upsertSource(
      FederatedSource(
        id = "fs-legacy",
        tenantDbId = "td-1",
        alias = "Sales",
        setupSql = "ATTACH 'old' AS Sales;"
      )
    )
    fed.upsertSecret(
      FederatedSecret(
        id = "fsec-legacy",
        federatedSourceId = "fs-legacy",
        name = "PG_PASSWORD",
        value = Some("super-secret"),
        externalRef = None
      )
    )

    val base    = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(fed))
    val withFed = withFederatedSources(
      base,
      List(
        ManifestFederatedSource(
          alias = "sales",
          setupSql = "ATTACH 'new' AS {{alias}};",
          secrets = List(
            ManifestFederatedSecret(
              name = "PG_PASSWORD",
              value = Some(FederatedSecret.RedactedMarker)
            )
          )
        )
      )
    )
    ManifestImporter.apply(withFed, cp, Some(fed)) shouldBe Right(())

    val rows = fed.listSources("td-1")
    rows should have size 1
    rows.head.id shouldBe "fs-legacy"
    rows.head.alias shouldBe "sales"
    rows.head.setupSql shouldBe "ATTACH 'new' AS {{alias}};"
    // The redaction sentinel resolved against the row's existing secret, which is only reachable
    // through the recovered row id.
    fed.listSecrets("fs-legacy").map(_.value) shouldBe List(Some("super-secret"))
  }

  // ------------------------------------------------------------------
  // Test 17: FederatedSource.validate runs on every imported row, so the
  // importer cannot write a shape the REST surface refuses.
  // ------------------------------------------------------------------

  it should "refuse an imported source whose shape FederatedSource.validate rejects" in {
    val cp  = buildSrc()
    val fed = new InMemoryFederatedSourceStore()
    // A stored row the rejected manifest source names. Rejecting a source is an error for the
    // operator to fix, not a reason to destroy what is stored, so this row must survive untouched.
    fed.upsertSource(
      FederatedSource(
        id = "fs-ice-old",
        tenantDbId = "td-1",
        alias = "ice",
        setupSql = "ATTACH 'old' AS ice;"
      )
    )
    val base    = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(fed))
    val withFed = withFederatedSources(
      base,
      List(
        ManifestFederatedSource(alias = "ice", sourceType = "iceberg_rest"),
        ManifestFederatedSource(alias = "empty_sql", setupSql = "   ")
      )
    )
    val res = ManifestImporter.apply(withFed, cp, Some(fed))

    res.isLeft shouldBe true
    val msgs = res.left.toOption.get
    msgs.exists(_.contains("config is required for sourceType 'iceberg_rest'")) shouldBe true
    msgs.exists(_.contains("setupSql is required for sourceType 'sql'")) shouldBe true

    val kept = fed.getSource("td-1", "ice").get
    kept.id shouldBe "fs-ice-old"
    kept.setupSql shouldBe "ATTACH 'old' AS ice;"
    kept.sourceType shouldBe FederatedSourceType.Sql
    fed.getSource("td-1", "empty_sql") shouldBe None
  }

  // ------------------------------------------------------------------
  // Test 18: a blank inline secret value is refused on import, the way
  // FederatedSourceHandlers.upsertSecret refuses it. Left to land, it
  // renders `VALUE ''` into the node's piped init script and the catalog
  // silently disappears.
  // ------------------------------------------------------------------

  it should "refuse a blank inline secret value on import" in {
    val cp      = buildSrc()
    val fed     = new InMemoryFederatedSourceStore()
    val base    = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(fed))
    val withFed = withFederatedSources(
      base,
      List(
        ManifestFederatedSource(
          alias = "pg_ext",
          setupSql = "ATTACH 'dbname=prod' AS {{alias}};",
          secrets = List(ManifestFederatedSecret(name = "PG_PASSWORD", value = Some("   ")))
        )
      )
    )
    val res = ManifestImporter.apply(withFed, cp, Some(fed))

    res.isLeft shouldBe true
    res.left.toOption.get.exists(_.contains("has a blank value")) shouldBe true

    // The source itself is a valid shape and lands; only the secret is refused.
    val src = fed.getSource("td-1", "pg_ext").get
    fed.getSecret(src.id, "PG_PASSWORD") shouldBe None
  }

  // ------------------------------------------------------------------
  // Test 19: a stored row whose alias the manifest repeats VERBATIM but
  // which cannot be normalized (a legacy row from before aliases were
  // normalized) is reported and left alone, not deleted.
  // ------------------------------------------------------------------

  it should "keep a stored source whose alias the manifest repeats but cannot normalize" in {
    val cp  = buildSrc()
    val fed = new InMemoryFederatedSourceStore()
    fed.upsertSource(
      FederatedSource(
        id = "fs-legacy-name",
        tenantDbId = "td-1",
        alias = "bad-alias",
        setupSql = "ATTACH 'old' AS \"bad-alias\";"
      )
    )

    val base    = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(fed))
    val withFed = withFederatedSources(
      base,
      List(ManifestFederatedSource(alias = "bad-alias", setupSql = "ATTACH 'new' AS {{alias}};"))
    )
    val res = ManifestImporter.apply(withFed, cp, Some(fed))

    res.isLeft shouldBe true
    res.left.toOption.get.exists(_.contains("invalid alias 'bad-alias'")) shouldBe true

    val rows = fed.listSources("td-1")
    rows should have size 1
    rows.head.id shouldBe "fs-legacy-name"
    rows.head.alias shouldBe "bad-alias"
    rows.head.setupSql shouldBe "ATTACH 'old' AS \"bad-alias\";"
  }

  // ------------------------------------------------------------------
  // Test 20: delete-missing keys on the ROW ID, not on the alias string.
  //
  // A manifest entry whose alias differs from the stored one only in case
  // and whose shape is rejected used to destroy the stored row: the alias
  // string was not in the incoming set, so the row was deleted, and the
  // upsert that would have recreated it never ran.
  // ------------------------------------------------------------------

  it should "not delete a stored source when a case-differing manifest entry is rejected" in {
    val cp  = buildSrc()
    val fed = new InMemoryFederatedSourceStore()
    fed.upsertSource(
      FederatedSource(
        id = "fs-mixed",
        tenantDbId = "td-1",
        alias = "Ice",
        setupSql = "ATTACH 'old' AS Ice;"
      )
    )

    val base    = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(fed))
    val withFed = withFederatedSources(
      base,
      List(ManifestFederatedSource(alias = "ice", sourceType = "iceberg_rest"))
    )
    val res = ManifestImporter.apply(withFed, cp, Some(fed))

    res.isLeft shouldBe true
    val rows = fed.listSources("td-1")
    rows should have size 1
    rows.head.id shouldBe "fs-mixed"
    rows.head.setupSql shouldBe "ATTACH 'old' AS Ice;"
  }

  // ------------------------------------------------------------------
  // Test 21: a duplicated alias is reported AND kept out of the upsert
  // loop.
  //
  // Reporting alone left both entries to be written. `sourceByAlias` is
  // snapshotted before the loop, so with no stored row the second entry
  // never sees the id the first one minted: it mints its own and issues
  // a second write under the same (tenant_db, alias). In this in-memory
  // store that lands as two rows; on Postgres it violates
  // `uq_fedsrc_tenant_db_alias` and throws out of `apply`, so the caller
  // gets an exception instead of the accumulated Left this code builds.
  // The row count is what pins it -- the Left was already returned
  // before the fix.
  // ------------------------------------------------------------------

  it should "write nothing for an alias duplicated in the payload" in {
    val cp      = buildSrc()
    val fed     = new InMemoryFederatedSourceStore()
    val base    = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(fed))
    val withFed = withFederatedSources(
      base,
      List(
        ManifestFederatedSource(alias = "Sales", setupSql = "ATTACH 'a' AS {{alias}};"),
        ManifestFederatedSource(alias = "sales", setupSql = "ATTACH 'b' AS {{alias}};")
      )
    )
    val res = ManifestImporter.apply(withFed, cp, Some(fed))

    res.isLeft shouldBe true
    res.left.toOption.get.exists(_.contains("duplicate alias 'sales' in payload")) shouldBe true

    // Neither entry was written: one alias, two candidate rows, no way to
    // choose, so the operator fixes the manifest rather than the store
    // silently keeping whichever won the race.
    fed.listSources("td-1") shouldBe empty
  }

  // ------------------------------------------------------------------
  // Test 22: two LEGACY stored rows whose aliases differ only in case
  // collapse to exactly one row on import, not to one live row plus one
  // unreachable shadow.
  //
  // This pins the delete sweep against the review's M-2 reading, which
  // held that both ids reach `keepIds` and the shadowed row survives to
  // collide inside `FederationBlobBuilder`. Both `keepIds` arms fold the
  // incoming alias with `Locale.ROOT`, and `Names.normalizeOrError`
  // lowercases too, so under any ASCII-lowercasing default locale the
  // two arms resolve to the SAME map entry and only one id is kept. The
  // survivor is rewritten under the normalized alias and the other row
  // is deleted, which is the cleanup the pre-normalization alias-string
  // code also did.
  // ------------------------------------------------------------------

  it should "collapse two legacy case-colliding stored rows to one row on import" in {
    val cp  = buildSrc()
    val fed = new InMemoryFederatedSourceStore()
    fed.upsertSource(
      FederatedSource(
        id = "fs-upper",
        tenantDbId = "td-1",
        alias = "Sales",
        setupSql = "ATTACH 'upper' AS Sales;"
      )
    )
    fed.upsertSource(
      FederatedSource(
        id = "fs-lower",
        tenantDbId = "td-1",
        alias = "sales",
        setupSql = "ATTACH 'lower' AS sales;"
      )
    )

    val base    = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(fed))
    val withFed = withFederatedSources(
      base,
      List(ManifestFederatedSource(alias = "sales", setupSql = "ATTACH 'new' AS {{alias}};"))
    )
    ManifestImporter.apply(withFed, cp, Some(fed)) shouldBe Right(())

    val rows = fed.listSources("td-1")
    rows should have size 1
    rows.head.alias shouldBe "sales"
    rows.head.setupSql shouldBe "ATTACH 'new' AS {{alias}};"
  }
