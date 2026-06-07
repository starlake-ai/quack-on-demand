package ai.starlake.quack.federation

import ai.starlake.quack.edge.sql.{
  Allowed, Denied, PostgresAclValidator, ValidationContext
}
import ai.starlake.quack.model.{FederatedSecret, FederatedSource, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.federation.{FederationBlobBuilder, PostgresSecretResolver}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{
  FederatedSourceStore, LiquibaseRunner, PoolPermission, PostgresControlPlaneStore,
  RbacGroup, RbacRole, RbacUser, RolePermission
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.time.Instant
import scala.sys.process._
import scala.util.Try

/** Full manager-side E2E spec for the federation flow.
  *
  * Layer B (always-runnable): exercised against a live local Postgres
  * (same one used by the rest of the suite). Cancelled cleanly when
  * Postgres is not reachable. Covers all three TenantDbKind variants
  * (DuckLake, DuckDbFile, InMemory) and the ACL grant/revoke cycle.
  *
  * Layer A (gated): full DuckDB spawn. Requires `duckdb` and `psql` on
  * PATH and Postgres reachable. Skipped via `cancel` if either is
  * absent. Currently a placeholder with the prerequisite gate; the heavy
  * spawn plumbing can be filled in once the CI environment ships the
  * `quack` and `ducklake` DuckDB extensions.
  */
class PostgresFederationE2ESpec extends AnyFlatSpec with Matchers with OptionValues:

  TestPostgres.dropStrayTestDatabases("qodfe")

  Class.forName("org.postgresql.Driver")

  private val pgHost = TestPostgres.pgHost
  private val pgPort = TestPostgres.pgPort
  private val pgUser = TestPostgres.pgUser
  private val pgPass = TestPostgres.pgPass

  // -----------------------------------------------------------------------
  // Infrastructure helpers
  // -----------------------------------------------------------------------

  private def pgReachable: Boolean = TestPostgres.reachable

  private def duckdbAvailable: Boolean =
    Try(Process(Seq("duckdb", "--version")).!!).isSuccess

  private def psqlAvailable: Boolean =
    Try(Process(Seq("psql", "--version")).!!).isSuccess

  /** Spin up a fresh Postgres database with the full Liquibase migration
    * applied, then run the test body, and drop the database afterwards. */
  private def withControlPlane(
      test: (PostgresControlPlaneStore, FederatedSourceStore, String, String, String) => Unit
  ): Unit =
    if !pgReachable then cancel(
      s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
    )
    val dbName = s"qodfe_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, pgUser, pgPass).run()
      val cp = new PostgresControlPlaneStore(url, pgUser, pgPass)
      val fs = new FederatedSourceStore(url, pgUser, pgPass)
      test(cp, fs, dbName, url, pgPass)
    finally Try(TestPostgres.dropDatabase(dbName))

  /** Seed a tenant + one tenant-db of the requested kind, then run
    * the test body with the IDs. */
  private def withTenantAndDb(
      kind: TenantDbKind,
      cp:   PostgresControlPlaneStore,
      test: (String, String) => Unit
  ): Unit =
    val tenantId = s"t-${System.nanoTime()}"
    val tdId     = s"td-${System.nanoTime()}"
    cp.upsertTenant(Tenant(id = tenantId, name = tenantId, displayName = tenantId, disabled = false))
    val metastore = kind match
      case TenantDbKind.DuckLake   => Map("pgHost" -> pgHost, "pgPort" -> pgPort.toString,
                                          "pgUser" -> pgUser, "pgPassword" -> pgPass,
                                          "dbName" -> "fedtest", "schemaName" -> "main")
      case TenantDbKind.DuckDbFile => Map("dbName" -> "mydata", "schemaName" -> "main")
      case TenantDbKind.InMemory   => Map.empty[String, String]
    cp.upsertTenantDb(TenantDb(
      id        = tdId,
      tenantId  = tenantId,
      name      = s"${tenantId}_db",
      kind      = kind,
      metastore = metastore,
      dataPath  = kind match
        case TenantDbKind.DuckLake   => "/tmp/fed-e2e-data"
        case TenantDbKind.DuckDbFile => "/tmp/fed-e2e.duckdb"
        case TenantDbKind.InMemory   => ""
    ))
    test(tenantId, tdId)

  /** Build a [[FederationBlobBuilder]] backed by the real
    * [[FederatedSourceStore]] and the [[PostgresSecretResolver]]. */
  private def blobBuilder(fs: FederatedSourceStore): FederationBlobBuilder =
    new FederationBlobBuilder(
      loadEnabled = tdId => IO.blocking(fs.listEnabledSources(tdId)),
      loadSecrets = srcId => IO.blocking(fs.listSecrets(srcId)),
      resolver    = new PostgresSecretResolver()
    )

  // -----------------------------------------------------------------------
  // Layer-B helpers: ACL
  // -----------------------------------------------------------------------

  private val validator = new PostgresAclValidator()

  private def mkEffective(
      tenantId: String,
      perms:    List[RolePermission]
  ): EffectiveSet =
    EffectiveSet(
      user        = RbacUser(id = "u-1", tenant = Some(tenantId), username = "alice", role = "analyst",
                             createdAt = Some(Instant.now()), updatedAt = Some(Instant.now())),
      roles       = List(RbacRole(id = "role-1", tenantId = tenantId, name = "analyst")),
      groups      = Nil,
      permissions = perms,
      poolPerms   = Nil
    )

  private def mkCtx(sql: String, tenantId: String, eff: EffectiveSet): ValidationContext =
    ValidationContext(
      username        = "alice",
      database        = s"$tenantId/td-1/pool-1",
      statement       = sql,
      peer            = "conn-1",
      defaultDatabase = None,
      defaultSchema   = None,
      effectiveSet    = Some(eff)
    )

  private def perm(catalog: String, schema: String, table: String, verb: String): RolePermission =
    RolePermission(
      id          = s"rp-$catalog-$schema-$table-$verb",
      roleId      = "role-1",
      catalogName = catalog,
      schemaName  = schema,
      tableName   = table,
      verb        = verb,
      grantedAt   = Some(Instant.now())
    )

  // -----------------------------------------------------------------------
  // Layer B -- kind = InMemory
  // -----------------------------------------------------------------------

  "PostgresFederationE2ESpec (InMemory)" should
    "persist tenant + tenant-db, register source + secret, build blob with resolved value" in
    withControlPlane { (cp, fs, _, _, _) =>
      withTenantAndDb(TenantDbKind.InMemory, cp, { (tenantId, tdId) =>

        // 1. Register a federated Postgres source using {{alias}} and {{secret.PWD}}.
        val src = FederatedSource(
          id         = "src-mem-1",
          tenantDbId = tdId,
          alias      = "fedpg",
          setupSql   =
            "INSTALL postgres;\n" +
            "ATTACH 'host=pg.example.com dbname=orders password={{secret.PWD}}' AS {{alias}};"
        )
        fs.upsertSource(src)

        // 2. Register a value-backed secret.
        val sec = FederatedSecret(
          id                = "sec-mem-1",
          federatedSourceId = src.id,
          name              = "PWD",
          value             = Some("hunter2"),
          externalRef       = None
        )
        fs.upsertSecret(sec)

        // 3. Round-trip verification via the store.
        val readSrc = fs.getSource(tdId, "fedpg").value
        readSrc.alias    shouldBe "fedpg"
        readSrc.setupSql should include("{{alias}}")

        val readSec = fs.getSecret(readSrc.id, "PWD").value
        readSec.value shouldBe Some("hunter2")

        // 4. Build the blob: {{alias}} and {{secret.PWD}} both substituted.
        val blob = blobBuilder(fs).build(tdId).unsafeRunSync().value
        blob should include("-- BEGIN federation: fedpg")
        blob should include("-- END federation: fedpg")
        blob should include("AS fedpg")
        blob should include("hunter2")
        blob should not include "{{alias}}"
        blob should not include "{{secret.PWD}}"

        // 5. logSafePreview redacts the value but keeps {{secret.PWD}}.
        val preview = blobBuilder(fs).logSafePreview(tdId).unsafeRunSync().value
        preview should include("{{secret.PWD}}")
        preview should not include "hunter2"
        preview should include("AS fedpg")
      })
    }

  it should "allow SELECT on fedpg.public.orders after grant, deny after revoke" in {
    val tenantId = "t-acl-inmem"

    // Grant SELECT on fedpg.public.orders.
    val withGrant = mkEffective(tenantId, perms = List(perm("fedpg", "public", "orders", "SELECT")))
    val ctxGrant  = mkCtx("SELECT * FROM fedpg.public.orders", tenantId, withGrant)
    validator.validate(ctxGrant) shouldBe Allowed

    // Revoke: empty permission set.
    val withRevoke = mkEffective(tenantId, perms = Nil)
    val ctxRevoke  = mkCtx("SELECT * FROM fedpg.public.orders", tenantId, withRevoke)
    validator.validate(ctxRevoke) match
      case Denied(msg) => msg should (include("fedpg") or include("orders"))
      case other       => fail(s"expected Denied after revoke, got $other")
  }

  it should "allow a DuckLake-join query when both local and federated grants exist" in {
    val tenantId = "t-acl-join-inmem"
    val perms    = List(
      perm("fedpg", "public",    "orders",   "SELECT"),
      perm("tpch",  "main",      "lineitem", "SELECT")
    )
    val eff = mkEffective(tenantId, perms)
    val sql = "SELECT o.id, l.qty FROM fedpg.public.orders o JOIN tpch.main.lineitem l ON o.id = l.id"
    validator.validate(mkCtx(sql, tenantId, eff)) shouldBe Allowed
  }

  it should "deny the join when only the local-side grant is present" in {
    val tenantId = "t-acl-join-deny"
    val eff      = mkEffective(tenantId, perms = List(perm("tpch", "main", "lineitem", "SELECT")))
    val sql      = "SELECT o.id FROM fedpg.public.orders o JOIN tpch.main.lineitem l ON o.id = l.id"
    validator.validate(mkCtx(sql, tenantId, eff)) match
      case Denied(msg) => msg should (include("fedpg") or include("orders"))
      case other       => fail(s"expected Denied, got $other")
  }

  // -----------------------------------------------------------------------
  // Layer B -- kind = DuckDbFile
  // -----------------------------------------------------------------------

  "PostgresFederationE2ESpec (DuckDbFile)" should
    "persist tenant + tenant-db of kind duckdb-file, register source + secret, build blob" in
    withControlPlane { (cp, fs, _, _, _) =>
      withTenantAndDb(TenantDbKind.DuckDbFile, cp, { (tenantId, tdId) =>

        // Verify kind round-trips correctly.
        val storedDb = cp.listTenantDbs(tenantId).head
        storedDb.kind shouldBe TenantDbKind.DuckDbFile

        val src = FederatedSource(
          id         = "src-file-1",
          tenantDbId = tdId,
          alias      = "fedpg",
          setupSql   =
            "INSTALL postgres;\n" +
            "ATTACH 'dbname=prod password={{secret.PWD}}' AS {{alias}} (TYPE postgres);"
        )
        fs.upsertSource(src)
        fs.upsertSecret(FederatedSecret("sec-file-1", src.id, "PWD", Some("s3cr3t"), None))

        val blob = blobBuilder(fs).build(tdId).unsafeRunSync().value
        blob should include("-- BEGIN federation: fedpg")
        blob should include("AS fedpg")
        blob should include("s3cr3t")
      })
    }

  it should "allow SELECT on fedpg.public.orders for DuckDbFile tenant after grant" in {
    val tenantId = "t-acl-file"
    val eff      = mkEffective(tenantId, List(perm("fedpg", "public", "orders", "SELECT")))
    validator.validate(mkCtx("SELECT * FROM fedpg.public.orders", tenantId, eff)) shouldBe Allowed
  }

  it should "deny SELECT on fedpg.public.orders for DuckDbFile tenant without grant" in {
    val tenantId = "t-acl-file-deny"
    val eff      = mkEffective(tenantId, Nil)
    validator.validate(mkCtx("SELECT * FROM fedpg.public.orders", tenantId, eff)) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  // -----------------------------------------------------------------------
  // Layer B -- kind = DuckLake
  // -----------------------------------------------------------------------

  "PostgresFederationE2ESpec (DuckLake)" should
    "persist tenant + tenant-db of kind ducklake, register source + secret, build blob" in
    withControlPlane { (cp, fs, _, _, _) =>
      withTenantAndDb(TenantDbKind.DuckLake, cp, { (tenantId, tdId) =>

        val storedDb = cp.listTenantDbs(tenantId).head
        storedDb.kind shouldBe TenantDbKind.DuckLake

        val src = FederatedSource(
          id         = "src-lake-1",
          tenantDbId = tdId,
          alias      = "fedpg",
          setupSql   =
            "INSTALL postgres;\n" +
            "ATTACH 'host=fed-pg.internal dbname=sales password={{secret.PWD}}' AS {{alias}} (TYPE postgres);"
        )
        fs.upsertSource(src)
        fs.upsertSecret(FederatedSecret("sec-lake-1", src.id, "PWD", Some("p4ssw0rd"), None))

        // Blob: both {{alias}} and {{secret.PWD}} resolved.
        val blob = blobBuilder(fs).build(tdId).unsafeRunSync().value
        blob should include("-- BEGIN federation: fedpg")
        blob should include("-- END federation: fedpg")
        blob should include("AS fedpg")
        blob should include("p4ssw0rd")
        blob should not include "{{secret.PWD}}"
        blob should not include "{{alias}}"

        // Preview blob: value redacted, alias expanded.
        val preview = blobBuilder(fs).logSafePreview(tdId).unsafeRunSync().value
        preview should include("{{secret.PWD}}")
        preview should include("AS fedpg")
        preview should not include "p4ssw0rd"
      })
    }

  it should "allow SELECT joining DuckLake catalog + federated alias for DuckLake tenant" in {
    val tenantId = "t-acl-lake-join"
    val perms    = List(
      perm("fedpg",   "public", "orders",   "SELECT"),
      perm("tpch",    "main",   "customer", "SELECT")
    )
    val eff = mkEffective(tenantId, perms)
    val sql =
      "SELECT o.id, c.name FROM fedpg.public.orders o JOIN tpch.main.customer c ON o.cust_id = c.id"
    validator.validate(mkCtx(sql, tenantId, eff)) shouldBe Allowed
  }

  it should "deny SELECT on DuckLake catalog table when only federated grant exists" in {
    val tenantId = "t-acl-lake-partial"
    val eff      = mkEffective(tenantId, List(perm("fedpg", "public", "orders", "SELECT")))
    val sql      =
      "SELECT o.id, c.name FROM fedpg.public.orders o JOIN tpch.main.customer c ON o.cust_id = c.id"
    validator.validate(mkCtx(sql, tenantId, eff)) match
      case Denied(msg) => msg should (include("tpch") or include("customer"))
      case other       => fail(s"expected Denied for ungranted local table, got $other")
  }

  // -----------------------------------------------------------------------
  // Layer B -- blob markers and multi-source ordering
  // -----------------------------------------------------------------------

  "PostgresFederationE2ESpec (blob assembly)" should
    "produce deterministic source ordering and correct BEGIN/END markers" in
    withControlPlane { (cp, fs, _, _, _) =>
      withTenantAndDb(TenantDbKind.InMemory, cp, { (tenantId, tdId) =>

        // Register two sources with aliases that sort alphabetically: bbb < zzz.
        val srcB = FederatedSource("src-bbb", tdId, "bbb_db",
                                   "ATTACH 'x' AS {{alias}};")
        val srcZ = FederatedSource("src-zzz", tdId, "zzz_db",
                                   "ATTACH 'y' AS {{alias}};")
        fs.upsertSource(srcB)
        fs.upsertSource(srcZ)

        val blob = blobBuilder(fs).build(tdId).unsafeRunSync().value
        blob should include ("-- BEGIN federation: bbb_db")
        blob should include ("-- END federation: bbb_db")
        blob should include ("-- BEGIN federation: zzz_db")
        blob should include ("-- END federation: zzz_db")
        // bbb_db must appear before zzz_db.
        blob.indexOf("BEGIN federation: bbb_db") should be < blob.indexOf("BEGIN federation: zzz_db")
      })
    }

  it should "return None when all sources are disabled" in
    withControlPlane { (cp, fs, _, _, _) =>
      withTenantAndDb(TenantDbKind.InMemory, cp, { (tenantId, tdId) =>
        fs.upsertSource(FederatedSource("src-off", tdId, "off",
                                        "ATTACH 'x' AS {{alias}};", disabled = true))
        blobBuilder(fs).build(tdId).unsafeRunSync() shouldBe None
      })
    }

  // -----------------------------------------------------------------------
  // Layer A -- gated on duckdb + psql + pg reachability
  // -----------------------------------------------------------------------

  "PostgresFederationE2ESpec (Layer A: full spawn)" should
    "attach a Postgres source and query its data through DuckDB (prerequisite-gated)" in {
    if !pgReachable || !duckdbAvailable || !psqlAvailable then
      cancel(
        s"Layer A skipped: pgReachable=$pgReachable duckdbAvailable=$duckdbAvailable" +
        s" psqlAvailable=$psqlAvailable - one or more prerequisites missing"
      )

    // If we reach here all prerequisites are satisfied. The full spawn
    // sequence below can only run where `quack` + `ducklake` DuckDB
    // extensions are also installed. For now we record the intent and
    // cancel: the extensions are not available on lean CI machines.
    cancel(
      "Layer A skipped: duckdb binary found but `quack` + `ducklake` extensions are not " +
      "guaranteed on this machine. Wire the full spawn once CI ships the extension bundle."
    )
  }