package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, Tenant, TenantDb, TenantDbKind}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.sys.process._
import scala.util.Try

class FederatedSourceStoreSpec extends AnyFlatSpec with Matchers with OptionValues:

  ai.starlake.quack.ondemand.state.testkit.TestPostgres.dropStrayTestDatabases("qodfs")

  private val pgHost = sys.env.getOrElse("SL_TEST_PG_HOST", "localhost")
  private val pgPort = sys.env.getOrElse("SL_TEST_PG_PORT", "5432").toInt
  private val pgUser = sys.env.getOrElse("SL_TEST_PG_USER", "postgres")
  private val pgPass = sys.env.getOrElse("SL_TEST_PG_PASSWORD", "azizam")

  Class.forName("org.postgresql.Driver")

  private def adminUrl: String          = s"jdbc:postgresql://$pgHost:$pgPort/postgres"
  private def dbUrl(db: String): String = s"jdbc:postgresql://$pgHost:$pgPort/$db"

  private def pgReachable: Boolean =
    Try {
      val c = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try c.isValid(2)
      finally c.close()
    }.getOrElse(false)

  private def psql(targetDb: String, sql: String): Unit =
    val rc = Process(
      Seq("psql", "-h", pgHost, "-p", pgPort.toString, "-U", pgUser, "-d", targetDb, "-tAc", sql),
      None,
      "PGPASSWORD" -> pgPass
    ).!
    assert(rc == 0, s"psql ($sql) exit=$rc")

  // Helper to seed a tenant + tenant-db so the federation FK can resolve.
  private def seedTd(cp: PostgresControlPlaneStore): String =
    cp.upsertTenant(Tenant(id = "t-1", displayName = "t1", disabled = false))
    cp.upsertTenantDb(
      TenantDb(
        id = "td-1",
        tenantId = "t-1",
        name = "td1",
        kind = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath = ""
      )
    )
    "td-1"

  private def withStores(test: (FederatedSourceStore, PostgresControlPlaneStore) => Unit): Unit =
    if !pgReachable then
      cancel(
        s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
      )
    val dbName = s"qodfs_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      new LiquibaseRunner(dbUrl(dbName), pgUser, pgPass).run()
      val cp = new PostgresControlPlaneStore(dbUrl(dbName), pgUser, pgPass)
      val fs = new FederatedSourceStore(dbUrl(dbName), pgUser, pgPass)
      test(fs, cp)
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  "FederatedSourceStore" should "round-trip a source with a value-backed secret" in withStores {
    (fs, cp) =>
      val tdId = seedTd(cp)
      val src  = FederatedSource(
        id = "src-1",
        tenantDbId = tdId,
        alias = "fedpg",
        setupSql = "INSTALL postgres;"
      )
      val sec = FederatedSecret(
        id = "sec-1",
        federatedSourceId = "src-1",
        name = "PWD",
        value = Some("hunter2"),
        externalRef = None
      )
      fs.upsertSource(src)
      fs.upsertSecret(sec)
      val read = fs.getSource(tdId, "fedpg").value
      read.alias shouldBe "fedpg"
      fs.listSecrets(read.id).map(_.name) should contain only "PWD"
  }

  it should "reject duplicate alias within the same tenant-db" in withStores { (fs, cp) =>
    val tdId = seedTd(cp)
    fs.upsertSource(FederatedSource("src-A", tdId, "dup", "..."))
    val ex = intercept[Throwable] {
      fs.upsertSource(FederatedSource("src-B", tdId, "dup", "..."))
    }
    ex.getMessage should (include("unique") or include("duplicate") or include(
      "uq_fedsrc_tenant_db_alias"
    ))
  }

  it should "cascade-delete secrets when source is deleted" in withStores { (fs, cp) =>
    val tdId = seedTd(cp)
    fs.upsertSource(FederatedSource("src-X", tdId, "tmp", "..."))
    fs.upsertSecret(FederatedSecret("sec-X", "src-X", "K", Some("v"), None))
    fs.deleteSource("src-X")
    fs.listSecrets("src-X") shouldBe empty
  }

  it should "list all enabled sources for a tenant-db in deterministic order" in withStores {
    (fs, cp) =>
      val tdId = seedTd(cp)
      fs.upsertSource(FederatedSource("src-B", tdId, "b", "..."))
      fs.upsertSource(FederatedSource("src-A", tdId, "a", "..."))
      fs.upsertSource(FederatedSource("src-D", tdId, "d", "...", disabled = true))
      fs.listEnabledSources(tdId).map(_.alias) shouldBe List("a", "b")
  }

  it should "accept and round-trip an external-ref-backed secret" in withStores { (fs, cp) =>
    val tdId = seedTd(cp)
    fs.upsertSource(FederatedSource("src-V", tdId, "fedv", "..."))
    fs.upsertSecret(FederatedSecret("sec-V", "src-V", "PWD", None, Some("vault:secret/data/x#k")))
    val sec = fs.getSecret("src-V", "PWD").value
    sec.value shouldBe None
    sec.externalRef shouldBe Some("vault:secret/data/x#k")
  }

  it should "return the tenant-db ids with at least one enabled federated source" in withStores {
    (fs, cp) =>
      val tdId = seedTd(cp)
      fs.upsertSource(FederatedSource("src-E", tdId, "e", "...", disabled = false))
      fs.upsertSource(FederatedSource("src-D", tdId, "d", "...", disabled = true))
      fs.tenantDbIdsWithSources() shouldBe Set(tdId)
  }

  it should "exclude a tenant-db whose only sources are all disabled" in withStores { (fs, cp) =>
    val tdId = seedTd(cp)
    fs.upsertSource(FederatedSource("src-F", tdId, "f", "...", disabled = true))
    fs.tenantDbIdsWithSources() shouldBe empty
  }

  it should "reject a secret with both value and externalRef (rejected at construction)" in
    intercept[IllegalArgumentException] {
      FederatedSecret("bad", "src", "PWD", Some("v"), Some("e:r"))
    }

  it should "round-trip an iceberg_rest source with config and readOnly" in withStores { (fs, cp) =>
    val tdId = seedTd(cp)
    val src  = FederatedSource(
      id = "fs-ice",
      tenantDbId = tdId,
      alias = "sales_lake",
      setupSql = "",
      sourceType = ai.starlake.quack.model.FederatedSourceType.IcebergRest,
      config = Some("""{"warehouse":"sales","authType":"oauth2"}"""),
      readOnly = true
    )
    fs.upsertSource(src)
    val got = fs.getSource(tdId, "sales_lake").value
    got.sourceType shouldBe ai.starlake.quack.model.FederatedSourceType.IcebergRest
    got.config shouldBe Some("""{"warehouse":"sales","authType":"oauth2"}""")
    got.readOnly shouldBe true
    got.setupSql shouldBe ""
  }

  it should "default an existing-style sql source to sql, no config, writable" in withStores {
    (fs, cp) =>
      val tdId = seedTd(cp)
      fs.upsertSource(
        FederatedSource(
          id = "fs-sql",
          tenantDbId = tdId,
          alias = "pg_src",
          setupSql = "ATTACH '' AS {{alias}} (TYPE postgres);"
        )
      )
      val got = fs.getSource(tdId, "pg_src").value
      got.sourceType shouldBe ai.starlake.quack.model.FederatedSourceType.Sql
      got.config shouldBe None
      got.readOnly shouldBe false
  }

  it should "update the new columns on a second upsert of the same id" in withStores { (fs, cp) =>
    val tdId = seedTd(cp)
    val base = FederatedSource(
      id = "fs-flip",
      tenantDbId = tdId,
      alias = "lake",
      sourceType = ai.starlake.quack.model.FederatedSourceType.IcebergRest,
      config = Some("""{"warehouse":"a"}"""),
      readOnly = true
    )
    fs.upsertSource(base)
    fs.upsertSource(base.copy(config = Some("""{"warehouse":"b"}"""), readOnly = false))
    val got = fs.getSource(tdId, "lake").value
    got.config shouldBe Some("""{"warehouse":"b"}""")
    got.readOnly shouldBe false
  }

  "FederatedSource.validate" should "require setupSql for a sql source" in {
    FederatedSource(id = "a", tenantDbId = "t", alias = "x").validate
      .exists(_.contains("setupSql")) shouldBe true
  }

  it should "require config for an iceberg_rest source" in {
    FederatedSource(
      id = "a",
      tenantDbId = "t",
      alias = "x",
      sourceType = ai.starlake.quack.model.FederatedSourceType.IcebergRest
    ).validate.exists(_.contains("config")) shouldBe true
  }

  it should "refuse a source carrying both setupSql and config" in {
    FederatedSource(
      id = "a",
      tenantDbId = "t",
      alias = "x",
      setupSql = "ATTACH ...",
      config = Some("{}")
    ).validate.exists(_.contains("exactly one")) shouldBe true
  }

  it should "accept a well-formed sql source" in {
    FederatedSource(
      id = "a",
      tenantDbId = "t",
      alias = "x",
      setupSql = "ATTACH ..."
    ).validate shouldBe empty
  }
