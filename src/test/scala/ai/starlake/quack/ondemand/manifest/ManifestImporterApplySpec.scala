// src/test/scala/ai/starlake/quack/ondemand/manifest/ManifestImporterApplySpec.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{Tenant, TenantDb, TenantDbKind}
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
    s.upsertUserWithHash(tenant = None, username = "admin", passwordHash = pre, role = "admin")
    s

  "ManifestImporter.apply" should "bcrypt plaintext passwords" in {
    val s = new InMemoryControlPlaneStore()
    val m = base.copy(users =
      List(
        ManifestUser(tenant = None, username = "admin", password = Some("hunter2"), role = "admin")
      )
    )
    ManifestImporter.apply(m, s) shouldBe Right(())
    val stored = s.getPasswordHash(None, "admin").get
    BCrypt.verifyer().verify("hunter2".toCharArray, stored).verified shouldBe true
  }

  it should "preserve an already-bcrypt password verbatim" in {
    val s   = new InMemoryControlPlaneStore()
    val pre = BCrypt.withDefaults().hashToString(12, "hunter2".toCharArray)
    val m   = base.copy(users =
      List(ManifestUser(tenant = None, username = "admin", password = Some(pre), role = "admin"))
    )
    ManifestImporter.apply(m, s) shouldBe Right(())
    s.getPasswordHash(None, "admin").get shouldBe pre
  }

  it should "reuse the existing password hash when the user has no password field" in {
    val s   = storeWithAdmin
    val pre = s.getPasswordHash(None, "admin").get
    val m   = base.copy(users =
      List(ManifestUser(tenant = None, username = "admin", password = None, role = "admin"))
    )
    ManifestImporter.apply(m, s) shouldBe Right(())
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
          role = "user"
        )
      )
    )
    ManifestImporter.apply(m, s) shouldBe Right(())

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
      List(ManifestUser(tenant = None, username = "newbie", password = None, role = "user"))
    )
    val err = ManifestImporter.apply(m, s).left.toOption.get
    err.exists(_.contains("newbie")) shouldBe true
  }

  it should "leave tenants absent from the YAML untouched" in {
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "t-untouched", displayName = "untouched"))
    ManifestImporter.apply(base, s) shouldBe Right(())
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
    ManifestImporter.apply(m, s) shouldBe Right(())

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
    val res = ManifestImporter.apply(m, s)
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
    val res = ManifestImporter.apply(m, s)
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
    val res = ManifestImporter.apply(m, s)
    res.isLeft shouldBe true
    res.swap.getOrElse(Nil).mkString("\n") should include("azure_account_key")
    // The offending row was NOT persisted.
    s.listTenantDbs("acme") shouldBe empty
  }
