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

  // Set by withStores right before it runs the test body, so a test that needs to hit the
  // throwaway database directly (e.g. with the psql helper, for a raw-SQL row) can find it
  // without widening every other test's two-arg callback.
  private var currentDbName: String = ""

  private def withStores(test: (FederatedSourceStore, PostgresControlPlaneStore) => Unit): Unit =
    if !pgReachable then
      cancel(
        s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
      )
    val dbName = s"qodfs_test_${System.nanoTime()}"
    currentDbName = dbName
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      new LiquibaseRunner(dbUrl(dbName), pgUser, pgPass).run()
      val cp = new PostgresControlPlaneStore(dbUrl(dbName), pgUser, pgPass)
      val fs = new FederatedSourceStore(dbUrl(dbName), pgUser, pgPass)
      test(fs, cp)
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  /** DuckDB-style single-quote escaping for a raw psql -c literal - mirrors
    * `ai.starlake.quack.model.SqlLiterals.duckdbLiteral`, kept local so this spec doesn't need a
    * main-source dependency just for one test's raw INSERT.
    */
  private def sqlLit(v: String): String = "'" + v.replace("'", "''") + "'"

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

  // One bootstrap (one CREATE DATABASE + Liquibase run + DROP via withStores) covers all four
  // of: an iceberg_rest round-trip, the nine-column upsert's model defaults for a sql source, a
  // second upsert flipping the new columns, and - the one case in this suite that would catch
  // `DEFAULT true` slipping back into 0038's read_only column - a genuine pre-0038-shaped row
  // inserted via raw SQL naming only the six original columns.
  it should "round-trip iceberg/sql sources, flip columns on re-upsert, and default a raw " +
    "pre-0038 row" in withStores { (fs, cp) =>
      val tdId = seedTd(cp)

      val ice = FederatedSource(
        id = "fs-ice",
        tenantDbId = tdId,
        alias = "sales_lake",
        setupSql = "",
        sourceType = ai.starlake.quack.model.FederatedSourceType.IcebergRest,
        config = Some("""{"warehouse":"sales","authType":"oauth2"}"""),
        readOnly = true
      )
      fs.upsertSource(ice)
      val gotIce = fs.getSource(tdId, "sales_lake").value
      gotIce.sourceType shouldBe ai.starlake.quack.model.FederatedSourceType.IcebergRest
      gotIce.config shouldBe Some("""{"warehouse":"sales","authType":"oauth2"}""")
      gotIce.readOnly shouldBe true
      gotIce.setupSql shouldBe ""

      // Round-trips model defaults through the nine-column upsert: upsertSource binds
      // source_type / config / read_only explicitly, so this does NOT exercise the 0038 column
      // DEFAULTs - see the raw-SQL case below for that.
      fs.upsertSource(
        FederatedSource(
          id = "fs-sql",
          tenantDbId = tdId,
          alias = "pg_src",
          setupSql = "ATTACH '' AS {{alias}} (TYPE postgres);"
        )
      )
      val gotSql = fs.getSource(tdId, "pg_src").value
      gotSql.sourceType shouldBe ai.starlake.quack.model.FederatedSourceType.Sql
      gotSql.config shouldBe None
      gotSql.readOnly shouldBe false

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
      val gotFlip = fs.getSource(tdId, "lake").value
      gotFlip.config shouldBe Some("""{"warehouse":"b"}""")
      gotFlip.readOnly shouldBe false

      // The genuine pre-0038 upgrade-path case: a row inserted naming ONLY the six columns that
      // existed before 0038 (id, tenant_db_id, alias, setup_sql, description, disabled), read
      // back through the normal getSource path. This is the only test that would catch
      // `DEFAULT true` slipping back into 0038's read_only column, which would silently make
      // every existing federation source read-only on upgrade.
      val legacySetupSql = "ATTACH '' AS {{alias}} (TYPE postgres);"
      psql(
        currentDbName,
        "INSERT INTO qodstate_federated_source " +
          "(id, tenant_db_id, alias, setup_sql, description, disabled) VALUES " +
          s"('fs-legacy', '$tdId', 'legacy_src', ${sqlLit(legacySetupSql)}, NULL, false)"
      )
      val gotLegacy = fs.getSource(tdId, "legacy_src").value
      gotLegacy.sourceType shouldBe ai.starlake.quack.model.FederatedSourceType.Sql
      gotLegacy.config shouldBe None
      gotLegacy.readOnly shouldBe false
      gotLegacy.setupSql shouldBe legacySetupSql
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
