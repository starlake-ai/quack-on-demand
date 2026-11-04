package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.api.{
  ErrorResponse,
  TenantDbHandlers,
  TenantDbRequest,
  TenantHandlers,
  UpdateTenantDbRequest
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.NodeMetastore
import ai.starlake.quack.ondemand.runtime.testkit.{LogCapture, StubQuackBackend}
import ai.starlake.quack.ondemand.state.{DbAdmin, InMemoryControlPlaneStore}
import ai.starlake.quack.ondemand.telemetry.AuditActor
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import scala.collection.mutable.ListBuffer

/** The rules on what a database row may reach, at each path that writes one: the supervisor's own
  * functions (what a module calls directly), `database/create` and `database/update` for a tenant
  * admin and for a superuser, and the manager's own `qod serve` provisioning. No Postgres: the
  * admin and the initializer are recording doubles. A real stack runs these in
  * `TenantIsolationE2ESpec`.
  */
class TenantIsolationRulesSpec extends AnyFlatSpec with Matchers:

  private val DefaultPassword = "default-login-sentinel"
  private val OwnPassword     = "own-login-sentinel"

  private val Defaults = Map(
    "pgHost"     -> "pg.internal",
    "pgPort"     -> "5432",
    "pgUser"     -> "qod",
    "pgPassword" -> DefaultPassword,
    "dbName"     -> "qod",
    "schemaName" -> "main",
    "dataPath"   -> "/data/ducklake"
  )

  private val SupervisorLogger = classOf[PoolSupervisor].getName

  private val tenantAdmin: SessionScope.Resolver =
    _ => SessionScope(superuser = false, manageableTenants = Set("acme"))
  private val superuser: SessionScope.Resolver = _ => SessionScope.Superuser

  private final class RecordingAdmin(var holds: Either[String, Boolean] = Right(false))
      extends DbAdmin:
    val created                                            = ListBuffer.empty[String]
    val dropped                                            = ListBuffer.empty[String]
    def createDatabase(name: String): Either[String, Unit] =
      created += name
      Right(())
    def dropDatabase(name: String): Either[String, Unit] =
      dropped += name
      Right(())
    def holdsAnything(name: String): Either[String, Boolean] = holds

  private final class Fixture(
      holds: Either[String, Boolean] = Right(false),
      init: (Map[String, String], Boolean, Boolean) => Unit = (_, _, _) => ()
  ):
    val store = new InMemoryControlPlaneStore()
    val admin = new RecordingAdmin(holds)
    val inits = ListBuffer.empty[(Map[String, String], Boolean)]
    val sup   = new PoolSupervisor(
      new StubQuackBackend(),
      new NodeLoadTracker,
      store,
      defaultMetastore = Defaults,
      dbAdmin = admin,
      duckLakeInitializer = (m, encrypted, createMissing) =>
        inits += ((m, createMissing))
        init(m, encrypted, createMissing)
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val handlers = new TenantDbHandlers(sup, requireEncryption = false)
    val tenants  = new TenantHandlers(sup, onAuthChanged = _ => ())

    /** A direct supervisor call, as a module makes one. */
    def create(
        suffix: String,
        metastore: Map[String, String] = Map.empty,
        adopt: Boolean = false,
        kind: TenantDbKind = TenantDbKind.DuckLake,
        dataPath: String = "/data/x"
    ): Either[SupervisorError, TenantDb] =
      sup
        .createTenantDb(
          "acme",
          suffix,
          kind,
          metastore,
          if kind == TenantDbKind.InMemory then "" else dataPath,
          adoptExistingDatabase = adopt
        )
        .unsafeRunSync()

    def rest(
        scope: SessionScope.Resolver,
        suffix: String,
        metastore: Map[String, String] = Map.empty
    ): Either[(StatusCode, ErrorResponse), String] =
      handlers
        .createTenantDb(
          TenantDbRequest(
            tenant = "acme",
            name = suffix,
            kind = "ducklake",
            metastore = metastore,
            dataPath = "/data/x"
          ),
          None
        )(scope)
        .unsafeRunSync()
        .map(_.name)

    def serve(
        suffix: String,
        metastore: Map[String, String] = Map.empty
    ): Either[(StatusCode, ErrorResponse), String] =
      handlers
        .createTenantDbAs(
          TenantDbRequest(
            tenant = "acme",
            name = suffix,
            kind = "ducklake",
            metastore = metastore,
            dataPath = "/data/x"
          ),
          SessionScope.Superuser,
          AuditActor.System
        )
        .unsafeRunSync()
        .map(_.name)

    def update(
        scope: SessionScope.Resolver,
        name: String,
        metastore: Map[String, String]
    ): Either[(StatusCode, ErrorResponse), String] =
      handlers
        .update(UpdateTenantDbRequest("acme", name, metastore = Some(metastore)), None)(scope)
        .unsafeRunSync()
        .map(_.db.name)

    def patch(
        name: String,
        metastore: Map[String, String],
        writerTrusted: Boolean = false
    ): Either[SupervisorError, TenantDb] =
      sup
        .updateTenantDb(
          "acme",
          name,
          TenantDbPatch(metastore = Some(metastore), writerTrusted = writerTrusted)
        )
        .unsafeRunSync()
        .map(_.td)

    def rows: List[String] = store.snapshot().tenantDbs.map(_.name).sorted

  private val OwnLogin =
    Map("pgHost" -> "pg.other", "pgUser" -> "me", "pgPassword" -> OwnPassword)

  private def codeOf(out: Either[(StatusCode, ErrorResponse), ?]): (StatusCode, String) =
    val (status, err) = out.left.toOption.getOrElse(fail(s"expected a refusal, got $out"))
    (status, err.error)

  // ---------- never adopt a database that is not ours ----------

  "A create that may not adopt" should "refuse a database holding anything, touching nothing" in {
    val f   = new Fixture(holds = Right(true))
    val out = f.create("lake")
    out.left.toOption.get shouldBe a[SupervisorError.DatabaseNameTaken]
    out.left.toOption.get.message shouldBe
      "database name 'acme_lake' is taken on this server: choose another name"
    f.admin.created shouldBe empty
    f.inits shouldBe empty
    f.rows shouldBe empty
  }

  it should "answer a tenant admin 409 database_name_taken on REST" in {
    val f = new Fixture(holds = Right(true))
    codeOf(f.rest(tenantAdmin, "lake")) shouldBe (StatusCode.Conflict, "database_name_taken")
    f.admin.created shouldBe empty
    f.rows shouldBe empty
  }

  it should "create what does not exist, and reuse what is empty" in {
    val f = new Fixture(holds = Right(false))
    f.rest(tenantAdmin, "lake") shouldBe Right("acme_lake")
    f.admin.created.toList shouldBe List("acme_lake")
    f.rows shouldBe List("acme_lake")
    f.inits.map(_._2).toList shouldBe List(true)
  }

  it should "refuse a name another tenant's row holds, even over an empty database" in {
    val f = new Fixture(holds = Right(false))
    // A tenant stored before new names lost their underscores.
    f.store.upsertTenant(Tenant("acme_corp"))
    f.sup.restore()
    f.sup
      .createTenantDb(
        "acme_corp",
        "sales",
        TenantDbKind.DuckLake,
        Map.empty,
        "/data/held",
        adoptExistingDatabase = false
      )
      .unsafeRunSync()
      .isRight shouldBe true
    f.admin.created.clear()
    f.create("corp_sales").left.toOption.get shouldBe a[SupervisorError.DatabaseNameTaken]
    f.admin.created shouldBe empty
  }

  it should "be a 500 when the database cannot be read, writing nothing" in {
    val f   = new Fixture(holds = Left("connection refused"))
    val out = f.rest(tenantAdmin, "lake")
    out.left.toOption.get._1 shouldBe StatusCode.InternalServerError
    out.left.toOption.get._2.error shouldBe "internal"
    f.admin.created shouldBe empty
    f.rows shouldBe empty
  }

  "A create that may adopt" should "keep a database holding anything, stated by the caller" in {
    val f = new Fixture(holds = Right(true))
    f.create("lake", adopt = true).map(_.name) shouldBe Right("acme_lake")
    f.admin.created.toList shouldBe List("acme_lake")
  }

  it should "be a superuser's on REST" in {
    val f = new Fixture(holds = Right(true))
    f.rest(superuser, "lake") shouldBe Right("acme_lake")
    f.rows shouldBe List("acme_lake")
  }

  it should "be the manager's in qod serve's provisioning" in {
    val f = new Fixture(holds = Right(true))
    f.serve("lake") shouldBe Right("acme_lake")
    f.rows shouldBe List("acme_lake")
  }

  // ---------- create and drop act only on the server the row uses ----------

  "A row on another server" should "create no database, and init without creating one" in {
    val f = new Fixture(holds = Right(true))
    f.create("lake", OwnLogin).map(_.name) shouldBe Right("acme_lake")
    f.admin.created shouldBe empty
    f.inits.map(_._2).toList shouldBe List(false)
    f.rows shouldBe List("acme_lake")
  }

  it should "not read the default server's database of that name either" in {
    val f = new Fixture(holds = Left("must not be asked"))
    f.create("lake", OwnLogin).isRight shouldBe true
  }

  it should "be refused when its catalog database is missing, writing nothing" in {
    val f = new Fixture(init =
      (_, _, _) => throw DuckLakeInitializer.CatalogDatabaseMissingException("create it there")
    )
    f.create("lake", OwnLogin).left.toOption.get shouldBe a[SupervisorError.CatalogDatabaseMissing]
    f.rows shouldBe empty
    codeOf(f.rest(tenantAdmin, "lake", OwnLogin)) shouldBe
      (StatusCode.BadRequest, "catalog_database_missing")
    f.rows shouldBe empty
  }

  it should "still be written when its server is only unreachable, for a pool to retry" in {
    val f = new Fixture(init = (_, _, _) => throw new RuntimeException("connection refused"))
    f.create("lake", OwnLogin).isRight shouldBe true
    f.rows shouldBe List("acme_lake")
  }

  it should "drop nothing on delete, and say so where the default log level shows it" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    val (out, logs) = LogCapture.logged(SupervisorLogger) {
      f.sup.deleteTenantDb("acme", "acme_lake").unsafeRunSync()
    }
    out shouldBe Right(())
    f.admin.dropped shouldBe empty
    f.rows shouldBe empty
    logs.exists(e =>
      e.getLevel == Level.ERROR && e.getFormattedMessage.contains("acme_lake") &&
        e.getFormattedMessage.contains("own server")
    ) shouldBe true
  }

  it should "drop nothing when its tenant is deleted, saying so" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    val (out, logs) = LogCapture.logged(SupervisorLogger) {
      f.sup.deleteTenant("acme").unsafeRunSync()
    }
    out shouldBe Right(())
    f.admin.dropped shouldBe empty
    logs.exists(e =>
      e.getLevel == Level.ERROR && e.getFormattedMessage.contains("acme_lake")
    ) shouldBe
      true
  }

  "A row on QoD's own server" should "drop its database on delete and on a tenant delete" in {
    val f = new Fixture
    f.create("a").isRight shouldBe true
    f.create("b", Map("pgUser" -> "me", "pgPassword" -> OwnPassword)).isRight shouldBe true
    f.sup.deleteTenantDb("acme", "acme_a").unsafeRunSync() shouldBe Right(())
    f.admin.dropped.toList shouldBe List("acme_a")
    f.sup.deleteTenant("acme").unsafeRunSync() shouldBe Right(())
    f.admin.dropped.toList shouldBe List("acme_a", "acme_b")
  }

  it should "init creating a missing database" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.inits.map(_._2).toList shouldBe List(true)
    // The boot pass states the same per row.
    val other = f.create("byo", OwnLogin)
    other.isRight shouldBe true
    f.inits.clear()
    f.sup.ensureDuckLakeInitialized().unsafeRunSync()
    f.inits.map(i => (i._1("dbName"), i._2)).toSet shouldBe
      Set(("acme_lake", true), ("acme_byo", false))
  }

  "A row named after the control-plane database" should "never drop it" in {
    val f = new Fixture
    f.store.upsertTenantDb(
      TenantDb(
        id = "td-cp",
        tenantId = "acme",
        name = "qod",
        kind = TenantDbKind.DuckLake,
        metastore = Map.empty,
        dataPath = "/data/qod"
      )
    )
    f.sup.restore()
    f.sup.deleteTenantDb("acme", "qod").unsafeRunSync() shouldBe Right(())
    f.admin.dropped shouldBe empty
  }

  "A row with no catalog database" should "drop nothing, on either delete" in {
    val f = new Fixture
    f.create("mem", kind = TenantDbKind.InMemory).isRight shouldBe true
    f.sup.deleteTenantDb("acme", "acme_mem").unsafeRunSync() shouldBe Right(())
    f.create("mem2", kind = TenantDbKind.InMemory).isRight shouldBe true
    f.create(
      "file",
      Map("dbName" -> "acme_file", "schemaName" -> "main"),
      kind = TenantDbKind.DuckDbFile
    ).isRight shouldBe true
    f.sup.deleteTenant("acme").unsafeRunSync() shouldBe Right(())
    f.admin.dropped shouldBe empty
  }

  "The createDatabase stamp" should "ride only a DuckLake row of QoD's own server" in {
    val f                                                       = new Fixture
    def row(kind: TenantDbKind, metastore: Map[String, String]) =
      TenantDb("td", "acme", "acme_x", kind, metastore, "/data/x")
    def stamped(td: TenantDb) =
      f.sup.effectiveMetastoreForTest(td).get(TenantDb.CreateDatabaseKey)
    stamped(row(TenantDbKind.DuckLake, Map.empty)) shouldBe Some("true")
    stamped(row(TenantDbKind.DuckLake, OwnLogin)) shouldBe None
    stamped(
      row(TenantDbKind.DuckDbFile, Map("dbName" -> "x", "schemaName" -> "main"))
    ) shouldBe None
    stamped(row(TenantDbKind.InMemory, Map.empty)) shouldBe None
    // A stored key decides nothing.
    stamped(
      row(TenantDbKind.DuckLake, OwnLogin + (TenantDb.CreateDatabaseKey -> "true"))
    ) shouldBe None
  }

  // ---------- dbName cannot be repointed ----------

  "database/update" should "refuse a tenant admin's change of dbName, changing nothing" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    codeOf(f.update(tenantAdmin, "acme_lake", Map("dbName" -> "globex_secret"))) shouldBe
      (StatusCode.BadRequest, "db_name_fixed")
    f.sup.effectiveMetastoreFor("acme", "acme_lake")("dbName") shouldBe "acme_lake"
  }

  it should "refuse it on a direct supervisor call that does not state a trusted writer" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.patch("acme_lake", Map("dbName" -> "globex_secret")).left.toOption.get shouldBe
      a[SupervisorError.DbNameFixed]
    f.store.snapshot().tenantDbs.head.metastore("dbName") shouldBe "acme_lake"
  }

  it should "take it from a superuser" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.update(superuser, "acme_lake", Map("dbName" -> "acme_elsewhere")) shouldBe Right("acme_lake")
    f.sup.effectiveMetastoreFor("acme", "acme_lake")("dbName") shouldBe "acme_elsewhere"
  }

  it should "see no change in the name it holds, or in one left out" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.update(tenantAdmin, "acme_lake", Map("dbName" -> "acme_lake")) shouldBe Right("acme_lake")
    f.update(tenantAdmin, "acme_lake", Map("schemaName" -> "main")) shouldBe Right("acme_lake")
  }

  it should "leave a duckdb-file database's dbName free: it names no Postgres database" in {
    val f = new Fixture
    f.create(
      "file",
      Map("dbName" -> "acme_file", "schemaName" -> "main"),
      kind = TenantDbKind.DuckDbFile
    ).isRight shouldBe true
    f.update(tenantAdmin, "acme_file", Map("dbName" -> "renamed", "schemaName" -> "main")) shouldBe
      Right("acme_file")
  }

  it should "let a tenant admin change catalogAlias and schemaName, which name things inside" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.update(
      tenantAdmin,
      "acme_lake",
      Map("catalogAlias" -> "lake2", "schemaName" -> "s2")
    ) shouldBe
      Right("acme_lake")
    val m = f.sup.effectiveMetastoreFor("acme", "acme_lake")
    m("dbName") shouldBe "acme_lake"
    m("catalogAlias") shouldBe "lake2"
  }

  "A branch row" should "register its own dbName, and update like any row" in {
    val f      = new Fixture
    val parent = f.create("lake").toOption.get
    val branch = TenantDb(
      id = "td-br",
      tenantId = "acme",
      name = "acme_lake__br_ab12cd34",
      kind = TenantDbKind.DuckLake,
      metastore = parent.metastore
        .updated("dbName", "acme_lake__br_ab12cd34")
        .updated(TenantDb.CatalogAliasKey, "acme_lake"),
      dataPath = "/data/x__br_ab12cd34",
      branchOf = Some(parent.id)
    )
    f.sup.registerBranchTenantDb(branch).isRight shouldBe true
    val m = f.sup.effectiveMetastoreFor("acme", branch.name)
    m("dbName") shouldBe branch.name
    m("catalogAlias") shouldBe "acme_lake"
    f.update(
      tenantAdmin,
      branch.name,
      Map("dbName" -> branch.name, "catalogAlias" -> "acme_lake")
    ) shouldBe Right(branch.name)
  }

  "The policy rewriter's column lookup" should "resolve a catalog in the asking tenant only" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.sup.findTenantDbByCatalogName("acme", "ACME_LAKE").map(_.name) shouldBe Some("acme_lake")
    f.sup.findTenantDbByCatalogName("globex", "acme_lake") shouldBe None
  }

  it should "cache per tenant, so no tenant is answered from another's read" in {
    val asked   = ListBuffer.empty[String]
    val columns = new ai.starlake.quack.edge.cls.DuckLakeColumnCatalog((tenant, _, _, _) =>
      cats.effect.IO {
        asked += tenant
        List(s"col_of_$tenant")
      }
    )
    columns.columnsOf("a", "c", "s", "t").unsafeRunSync() shouldBe List("col_of_a")
    columns.columnsOf("b", "c", "s", "t").unsafeRunSync() shouldBe List("col_of_b")
    columns.columnsOf("a", "c", "s", "t").unsafeRunSync() shouldBe List("col_of_a")
    asked.toList shouldBe List("a", "b")
  }

  // ---------- QoD's login goes only to QoD's server ----------

  "A create on another server" should "be refused without its own login, on a direct call" in {
    val f = new Fixture
    List(
      Map("pgHost" -> "pg.other"),
      Map("pgPort" -> "6000"),
      Map("pgHost" -> "pg.other", "pgUser"     -> "me"),
      Map("pgHost" -> "pg.other", "pgPassword" -> OwnPassword)
    ).foreach { m =>
      withClue(m.keySet.toString) {
        f.create("lake", m).left.toOption.get shouldBe a[SupervisorError.OwnLoginRequired]
      }
    }
    f.inits shouldBe empty
    f.admin.created shouldBe empty
    f.rows shouldBe empty
  }

  it should "answer own_login_required on REST, to a superuser too, and on qod serve's path" in {
    val f = new Fixture
    val m = Map("pgHost" -> "pg.other")
    codeOf(f.rest(tenantAdmin, "lake", m)) shouldBe (StatusCode.BadRequest, "own_login_required")
    codeOf(f.rest(superuser, "lake", m)) shouldBe (StatusCode.BadRequest, "own_login_required")
    codeOf(f.serve("lake", m)) shouldBe (StatusCode.BadRequest, "own_login_required")
    f.inits shouldBe empty
    f.rows shouldBe empty
  }

  it should "send the row's own login to the initializer, never the default's" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    val (sent, _) = f.inits.head
    sent("pgHost") shouldBe "pg.other"
    sent("pgUser") shouldBe "me"
    sent("pgPassword") shouldBe OwnPassword
    sent.values.toSet should not contain DefaultPassword
  }

  it should "leave a duckdb-file row alone: it connects to no server" in {
    val f = new Fixture
    f.create(
      "file",
      Map("dbName" -> "acme_file", "schemaName" -> "main", "pgHost" -> "pg.other"),
      kind = TenantDbKind.DuckDbFile
    ).isRight shouldBe true
  }

  "A create on QoD's own server" should "fill in QoD's login for the initializer" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    val (sent, _) = f.inits.head
    sent("pgHost") shouldBe "pg.internal"
    sent("pgUser") shouldBe "qod"
    sent("pgPassword") shouldBe DefaultPassword
    // The host spelled otherwise, the same server.
    f.create("spelled", Map("pgHost" -> "PG.INTERNAL.", "pgPort" -> "05432")).isRight shouldBe true
    f.inits.last._1("pgPassword") shouldBe DefaultPassword
    f.admin.created.toList shouldBe List("acme_lake", "acme_spelled")
  }

  "effectiveMetastoreFor" should "copy no login onto another server, whoever wrote the row" in {
    val f = new Fixture
    // As a manifest import, or an old release, writes one: straight to the store.
    f.store.upsertTenantDb(
      TenantDb(
        id = "td-old",
        tenantId = "acme",
        name = "acme_old",
        kind = TenantDbKind.DuckLake,
        metastore = Map("pgHost" -> "pg.other", "pgPort" -> "6000"),
        dataPath = "/data/old"
      )
    )
    f.sup.restore()
    val effective = f.sup.effectiveMetastoreFor("acme", "acme_old")
    effective.contains("pgUser") shouldBe false
    effective.contains("pgPassword") shouldBe false
    effective.values.toSet should not contain DefaultPassword
    // What every launcher takes from it, and the boot pass.
    NodeMetastore.secrets(effective) shouldBe empty
    NodeMetastore.env(effective).contains("pgUser") shouldBe false
    f.inits.clear()
    f.sup.ensureDuckLakeInitialized().unsafeRunSync()
    f.inits.head._1.values.toSet should not contain DefaultPassword
    f.inits.head._2 shouldBe false
  }

  it should "copy no login onto a row whose user reads as more connection settings" in {
    val f = new Fixture
    f.store.upsertTenantDb(
      TenantDb(
        id = "td-odd",
        tenantId = "acme",
        name = "acme_odd",
        kind = TenantDbKind.DuckLake,
        metastore = Map("pgUser" -> "qod host=pg.other"),
        dataPath = "/data/odd"
      )
    )
    f.sup.restore()
    f.sup.effectiveMetastoreFor("acme", "acme_odd").contains("pgPassword") shouldBe false
    // Nor may a writer store one again.
    f.create("odd2", Map("pgUser" -> "qod host=pg.other")).isLeft shouldBe true
    codeOf(f.rest(tenantAdmin, "odd3", Map("pgUser" -> "qod host=pg.other"))) shouldBe
      (StatusCode.BadRequest, "invalid_contract")
    f.inits.map(_._1("dbName")).toList should not contain "acme_odd3"
  }

  it should "refuse whitespace in every value around the connection, on each write" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.inits.clear()
    val values = List(
      "pgHost"     -> "pg.internal port=1",
      "pgPort"     -> "5432 host=pg.other",
      "pgUser"     -> "qod host=pg.other",
      "dbName"     -> "x host=pg.other",
      "schemaName" -> "main host=pg.other"
    )
    values.zipWithIndex.foreach { case ((k, v), i) =>
      withClue(k) {
        // A create replaces the dbName it is sent with the composed name.
        if k != "dbName" then
          codeOf(f.rest(tenantAdmin, s"odd$i", Map(k -> v))) shouldBe
            (StatusCode.BadRequest, "invalid_contract")
        codeOf(f.update(tenantAdmin, "acme_lake", Map(k -> v))) shouldBe
          (StatusCode.BadRequest, "invalid")
      }
    }
    f.inits shouldBe empty
    f.rows shouldBe List("acme_lake")
    f.store.snapshot().tenantDbs.head.metastore shouldBe Map("dbName" -> "acme_lake")
  }

  it should "keep QoD's login on its own server" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    val effective = f.sup.effectiveMetastoreFor("acme", "acme_lake")
    effective("pgUser") shouldBe "qod"
    effective("pgPassword") shouldBe DefaultPassword
  }

  "An update" should "be refused a move to another server with no login of its own" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.patch("acme_lake", Map("pgHost" -> "pg.other")).left.toOption.get shouldBe
      a[SupervisorError.OwnLoginRequired]
    codeOf(f.update(tenantAdmin, "acme_lake", Map("pgPort" -> "6000"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    codeOf(f.update(superuser, "acme_lake", Map("pgHost" -> "pg.other"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    f.store.snapshot().tenantDbs.head.metastore.contains("pgHost") shouldBe false
  }

  it should "judge the row's result: a stored password does not follow the row" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    val moved = Map("pgHost" -> "pg.third", "pgUser" -> "me")
    f.patch("acme_lake", moved).left.toOption.get shouldBe a[SupervisorError.OwnLoginRequired]
    codeOf(f.update(tenantAdmin, "acme_lake", moved)) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    codeOf(f.update(superuser, "acme_lake", moved)) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    // Back to QoD's own server, the password left out.
    codeOf(f.update(tenantAdmin, "acme_lake", Map("schemaName" -> "main"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    f.store.snapshot().tenantDbs.head.metastore("pgHost") shouldBe "pg.other"
    f.store.snapshot().tenantDbs.head.metastore("pgPassword") shouldBe OwnPassword
  }

  it should "take the password sent again" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    f.update(
      tenantAdmin,
      "acme_lake",
      Map("pgHost" -> "pg.third", "pgUser" -> "me", "pgPassword" -> "again")
    ) shouldBe Right("acme_lake")
    val m = f.store.snapshot().tenantDbs.head.metastore
    m("pgHost") shouldBe "pg.third"
    m("pgPassword") shouldBe "again"
  }

  it should "keep a stored password when the server stays, spelled otherwise or left as it is" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    f.update(tenantAdmin, "acme_lake", Map("pgHost" -> "PG.Other", "pgUser" -> "me")) shouldBe
      Right("acme_lake")
    f.store.snapshot().tenantDbs.head.metastore("pgPassword") shouldBe OwnPassword
    f.update(
      tenantAdmin,
      "acme_lake",
      Map("pgHost" -> "PG.Other", "pgUser" -> "you")
    ) shouldBe Right("acme_lake")
    f.store.snapshot().tenantDbs.head.metastore("pgPassword") shouldBe OwnPassword
  }

  it should "ask nothing of QoD's own server when a row moves between two others" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    f.admin.holds = Left("must not be asked")
    f.update(
      tenantAdmin,
      "acme_lake",
      Map("pgHost" -> "pg.third", "pgUser" -> "me", "pgPassword" -> "again")
    ) shouldBe Right("acme_lake")
    f.admin.created shouldBe empty
  }

  it should "go back to QoD's server with the password removed, QoD's login filling in" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    f.update(tenantAdmin, "acme_lake", Map("pgPassword" -> "")) shouldBe Right("acme_lake")
    f.store.snapshot().tenantDbs.head.metastore.contains("pgPassword") shouldBe false
    f.sup.effectiveMetastoreFor("acme", "acme_lake")("pgPassword") shouldBe DefaultPassword
  }

  it should "meet the rule of a create when it moves onto QoD's own server" in {
    val taken = new Fixture
    taken.create("lake", OwnLogin).isRight shouldBe true
    taken.admin.holds = Right(true)
    codeOf(taken.update(tenantAdmin, "acme_lake", Map("pgPassword" -> ""))) shouldBe
      (StatusCode.Conflict, "database_name_taken")
    taken.admin.created shouldBe empty
    taken.store.snapshot().tenantDbs.head.metastore("pgHost") shouldBe "pg.other"
    taken.store.snapshot().tenantDbs.head.metastore("pgPassword") shouldBe OwnPassword
    // On a direct call that does not state a trusted writer, too.
    taken.patch("acme_lake", Map("pgPassword" -> "")).left.toOption.get shouldBe
      a[SupervisorError.DatabaseNameTaken]
    // A superuser may adopt it.
    taken.update(superuser, "acme_lake", Map("pgPassword" -> "")) shouldBe Right("acme_lake")
    taken.admin.created.toList shouldBe List("acme_lake")
    // A database nothing holds is created for the row, which a delete then drops.
    val free = new Fixture
    free.create("lake", OwnLogin).isRight shouldBe true
    free.update(tenantAdmin, "acme_lake", Map("pgPassword" -> "")) shouldBe Right("acme_lake")
    free.admin.created.toList shouldBe List("acme_lake")
    free.sup.deleteTenantDb("acme", "acme_lake").unsafeRunSync() shouldBe Right(())
    free.admin.dropped.toList shouldBe List("acme_lake")
  }

  it should "not drop a required key to the default's login on another server" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    codeOf(f.update(tenantAdmin, "acme_lake", Map("pgHost" -> "pg.other"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    codeOf(
      f.update(
        tenantAdmin,
        "acme_lake",
        Map("pgHost" -> "pg.other", "pgUser" -> "me", "pgPassword" -> "")
      )
    ) shouldBe (StatusCode.BadRequest, "own_login_required")
    f.store.snapshot().tenantDbs.head.metastore("pgPassword") shouldBe OwnPassword
  }

  it should "leave a row that changes no metastore alone, an old row without a login included" in {
    val f = new Fixture
    f.store.upsertTenantDb(
      TenantDb(
        id = "td-old",
        tenantId = "acme",
        name = "acme_old",
        kind = TenantDbKind.DuckLake,
        metastore = Map("pgHost" -> "pg.other"),
        dataPath = "/data/old"
      )
    )
    f.sup.restore()
    f.handlers
      .update(UpdateTenantDbRequest("acme", "acme_old", initSql = Some("")), None)(tenantAdmin)
      .unsafeRunSync()
      .isRight shouldBe true
  }

  it should "stay refused for a superuser who wants the default password elsewhere: they type it" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.update(
      superuser,
      "acme_lake",
      Map("pgHost" -> "pg.other", "pgUser" -> "qod", "pgPassword" -> "typed-by-hand")
    ) shouldBe Right("acme_lake")
    f.store.snapshot().tenantDbs.head.metastore("pgPassword") shouldBe "typed-by-hand"
  }

  // ---------- the tenant-name rule, at the handler's path ----------

  "tenant/create" should "keep answering a bad name 400 once the supervisor refuses it" in {
    val f   = new Fixture
    val out = f.tenants
      .createTenantAs(
        ai.starlake.quack.ondemand.api.TenantRequest(id = "a_b", displayName = "x"),
        SessionScope.Superuser,
        AuditActor.System
      )
      .unsafeRunSync()
    codeOf(out) shouldBe (StatusCode.BadRequest, "invalid_id")
  }
