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
      init: (Map[String, String], Boolean, Boolean) => Unit = (_, _, _) => (),
      defaults: Map[String, String] = Defaults
  ):
    val store = new InMemoryControlPlaneStore()
    val admin = new RecordingAdmin(holds)
    val inits = ListBuffer.empty[(Map[String, String], Boolean)]
    val sup   = new PoolSupervisor(
      new StubQuackBackend(),
      new NodeLoadTracker,
      store,
      defaultMetastore = defaults,
      dbAdmin = admin,
      duckLakeInitializer = (m, encrypted, createMissing) =>
        inits += ((m, createMissing))
        init(m, encrypted, createMissing)
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val handlers = new TenantDbHandlers(sup, requireEncryption = false)
    val tenants  = new TenantHandlers(sup, onAuthChanged = _ => ())

    /** A direct supervisor call stating a trusted writer, as the manager makes one. */
    def create(
        suffix: String,
        metastore: Map[String, String] = Map.empty,
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
          writerTrusted = true
        )
        .unsafeRunSync()

    /** A direct supervisor call stating no trusted writer, as a module makes one. */
    def untrusted(
        suffix: String,
        metastore: Map[String, String] = Map.empty,
        dataPath: String = "",
        objectStore: Map[String, String] = Map.empty,
        kind: TenantDbKind = TenantDbKind.DuckLake
    ): Either[SupervisorError, TenantDb] =
      sup
        .createTenantDb(
          "acme",
          suffix,
          kind,
          metastore,
          dataPath,
          objectStore,
          writerTrusted = false
        )
        .unsafeRunSync()

    def rest(
        scope: SessionScope.Resolver,
        suffix: String,
        metastore: Map[String, String] = Map.empty,
        dataPath: String = "",
        objectStore: Map[String, String] = Map.empty,
        kind: String = "ducklake"
    ): Either[(StatusCode, ErrorResponse), String] =
      handlers
        .createTenantDb(
          TenantDbRequest(
            tenant = "acme",
            name = suffix,
            kind = kind,
            metastore = metastore,
            dataPath = dataPath,
            objectStore = objectStore
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

    def updateStore(
        scope: SessionScope.Resolver,
        name: String,
        objectStore: Map[String, String]
    ): Either[(StatusCode, ErrorResponse), String] =
      handlers
        .update(UpdateTenantDbRequest("acme", name, objectStore = Some(objectStore)), None)(scope)
        .unsafeRunSync()
        .map(_.db.name)

    def rows: List[String] = store.snapshot().tenantDbs.map(_.name).sorted

    def row(name: String): TenantDb = store.snapshot().tenantDbs.find(_.name == name).get

  private val OwnLogin =
    Map("pgHost" -> "pg.other", "pgUser" -> "me", "pgPassword" -> OwnPassword)

  private def codeOf(out: Either[(StatusCode, ErrorResponse), ?]): (StatusCode, String) =
    val (status, err) = out.left.toOption.getOrElse(fail(s"expected a refusal, got $out"))
    (status, err.error)

  // ---------- never adopt a database that is not ours ----------

  "A create that may not adopt" should "refuse a database holding anything, touching nothing" in {
    val f   = new Fixture(holds = Right(true))
    val out = f.untrusted("lake")
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
        writerTrusted = true
      )
      .unsafeRunSync()
      .isRight shouldBe true
    f.admin.created.clear()
    f.untrusted("corp_sales").left.toOption.get shouldBe a[SupervisorError.DatabaseNameTaken]
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
    f.create("lake").map(_.name) shouldBe Right("acme_lake")
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
    codeOf(f.rest(superuser, "lake", OwnLogin)) shouldBe
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
      (StatusCode.Forbidden, "superuser_required")
    f.sup.effectiveMetastoreFor("acme", "acme_lake")("dbName") shouldBe "acme_lake"
  }

  it should "refuse it on a direct supervisor call that does not state a trusted writer" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    f.patch("acme_lake", Map("dbName" -> "globex_secret")).left.toOption.get shouldBe
      a[SupervisorError.SuperuserChoice]
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

  it should "answer own_login_required to a superuser on REST, and on qod serve's path" in {
    val f = new Fixture
    val m = Map("pgHost" -> "pg.other")
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
    codeOf(f.rest(superuser, "odd3", Map("pgUser" -> "qod host=pg.other"))) shouldBe
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
          codeOf(f.rest(superuser, s"odd$i", Map(k -> v))) shouldBe
            (StatusCode.BadRequest, "invalid_contract")
        codeOf(f.update(superuser, "acme_lake", Map(k -> v))) shouldBe
          (StatusCode.BadRequest, "invalid")
      }
    }
    codeOf(f.update(tenantAdmin, "acme_lake", Map("schemaName" -> "main host=pg.other"))) shouldBe
      (StatusCode.BadRequest, "invalid")
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
    f.patch("acme_lake", Map("pgHost" -> "pg.other"), writerTrusted = true)
      .left
      .toOption
      .get shouldBe
      a[SupervisorError.OwnLoginRequired]
    codeOf(f.update(superuser, "acme_lake", Map("pgPort" -> "6000"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    codeOf(f.update(superuser, "acme_lake", Map("pgHost" -> "pg.other"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    f.store.snapshot().tenantDbs.head.metastore.contains("pgHost") shouldBe false
  }

  it should "judge the row's result: a stored password does not follow the row" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    val moved = Map("pgHost" -> "pg.third", "pgUser" -> "me")
    f.patch("acme_lake", moved, writerTrusted = true).left.toOption.get shouldBe
      a[SupervisorError.OwnLoginRequired]
    codeOf(f.update(superuser, "acme_lake", moved)) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    // Back to QoD's own server, the password left out.
    codeOf(f.update(superuser, "acme_lake", Map("schemaName" -> "main"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    f.store.snapshot().tenantDbs.head.metastore("pgHost") shouldBe "pg.other"
    f.store.snapshot().tenantDbs.head.metastore("pgPassword") shouldBe OwnPassword
  }

  it should "take the password sent again" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    f.update(
      superuser,
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
    f.update(superuser, "acme_lake", Map("pgHost" -> "PG.Other", "pgUser" -> "me")) shouldBe
      Right("acme_lake")
    f.store.snapshot().tenantDbs.head.metastore("pgPassword") shouldBe OwnPassword
    f.update(
      superuser,
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
      superuser,
      "acme_lake",
      Map("pgHost" -> "pg.third", "pgUser" -> "me", "pgPassword" -> "again")
    ) shouldBe Right("acme_lake")
    f.admin.created shouldBe empty
  }

  it should "go back to QoD's server with the password removed, QoD's login filling in" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    f.update(superuser, "acme_lake", Map("pgPassword" -> "")) shouldBe Right("acme_lake")
    f.store.snapshot().tenantDbs.head.metastore.contains("pgPassword") shouldBe false
    f.sup.effectiveMetastoreFor("acme", "acme_lake")("pgPassword") shouldBe DefaultPassword
  }

  it should "meet the rule of a create when it moves onto QoD's own server" in {
    val taken = new Fixture
    taken.create("lake", OwnLogin).isRight shouldBe true
    taken.admin.holds = Right(true)
    codeOf(taken.update(tenantAdmin, "acme_lake", Map("pgPassword" -> ""))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    taken.admin.created shouldBe empty
    taken.store.snapshot().tenantDbs.head.metastore("pgHost") shouldBe "pg.other"
    taken.store.snapshot().tenantDbs.head.metastore("pgPassword") shouldBe OwnPassword
    // On a direct call that does not state a trusted writer, too.
    taken.patch("acme_lake", Map("pgPassword" -> "")).left.toOption.get shouldBe
      a[SupervisorError.SuperuserChoice]
    // A superuser may adopt it.
    taken.update(superuser, "acme_lake", Map("pgPassword" -> "")) shouldBe Right("acme_lake")
    taken.admin.created.toList shouldBe List("acme_lake")
    // A database nothing holds is created for the row, which a delete then drops.
    val free = new Fixture
    free.create("lake", OwnLogin).isRight shouldBe true
    free.update(superuser, "acme_lake", Map("pgPassword" -> "")) shouldBe Right("acme_lake")
    free.admin.created.toList shouldBe List("acme_lake")
    free.sup.deleteTenantDb("acme", "acme_lake").unsafeRunSync() shouldBe Right(())
    free.admin.dropped.toList shouldBe List("acme_lake")
  }

  it should "not drop a required key to the default's login on another server" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    codeOf(f.update(superuser, "acme_lake", Map("pgHost" -> "pg.other"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    codeOf(
      f.update(
        superuser,
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

  // ---------- where a database's data lies ----------

  private val OwnKeys = Map("s3_access_key_id" -> "AK", "s3_secret_access_key" -> "SK")
  private val Remote  = "s3://own-bucket/lake/"

  "A create that names no path" should "get a place beside the default, stored on the row" in {
    val f = new Fixture
    f.rest(tenantAdmin, "lake") shouldBe Right("acme_lake")
    f.row("acme_lake").dataPath shouldBe "/data/acme_lake"
    f.inits.head._1("dataPath") shouldBe "/data/acme_lake"
    f.sup.effectiveMetastoreFor("acme", "acme_lake")("dataPath") shouldBe "/data/acme_lake"
  }

  it should "get it for a superuser and a direct caller too" in {
    val f = new Fixture
    f.rest(superuser, "su") shouldBe Right("acme_su")
    f.row("acme_su").dataPath shouldBe "/data/acme_su"
    f.create("direct", dataPath = "").map(_.dataPath) shouldBe Right("/data/acme_direct")
  }

  it should "follow a remote default, under its bucket" in {
    val f = new Fixture(defaults = Defaults.updated("dataPath", "s3://qod-bucket/lakes/root/"))
    f.create("lake", dataPath = "").map(_.dataPath) shouldBe Right(
      "s3://qod-bucket/lakes/acme_lake"
    )
  }

  it should "be refused 400 with no default to place it by, or only a bucket's root" in
    List(
      Defaults - "dataPath",
      Defaults.updated("dataPath", " "),
      Defaults.updated("dataPath", "s3://b/")
    )
      .foreach { defaults =>
        val f             = new Fixture(defaults = defaults)
        val (status, err) = f.rest(tenantAdmin, "lake").left.toOption.get
        status shouldBe StatusCode.BadRequest
        err.message should include("managed storage")
        err.message should include("superuser")
        f.rows shouldBe empty
        f.admin.created shouldBe empty
      }

  it should "be refused 409 data_path_taken over a row's folder, by whole segments, naming no one" in {
    val f = new Fixture
    f.create("under", dataPath = "/data/acme_lake/below").isRight shouldBe true
    val out = f.rest(tenantAdmin, "lake")
    codeOf(out) shouldBe (StatusCode.Conflict, "data_path_taken")
    out.left.toOption.get._2.message should not include "under"
    f.admin.created.toList shouldBe List("acme_under")
    f.rows shouldBe List("acme_under")

    val above = new Fixture
    above.create("up", dataPath = "/data").isRight shouldBe true
    codeOf(above.rest(tenantAdmin, "lake")) shouldBe (StatusCode.Conflict, "data_path_taken")

    // A folder whose name only begins the same is another folder.
    val beside = new Fixture
    beside.create("near", dataPath = "/data/acme_lake2").isRight shouldBe true
    beside.rest(tenantAdmin, "lake") shouldBe Right("acme_lake")
  }

  it should "leave a metastore's dataPath to its own refusal, and the other kinds alone" in {
    val f = new Fixture
    codeOf(f.rest(superuser, "su", metastore = Map("dataPath" -> "/data/su"))) shouldBe
      (StatusCode.BadRequest, "invalid_contract")
    f.create("mem", kind = TenantDbKind.InMemory).map(_.dataPath) shouldBe Right("")
    codeOf(f.rest(tenantAdmin, "file", kind = "duckdb-file")) shouldBe
      (StatusCode.BadRequest, "invalid_contract")
  }

  "A tenant admin's create" should "name no path but a remote one with own keys" in {
    val f     = new Fixture
    val none  = Map.empty[String, String]
    val cases = List[(String, String, Map[String, String], Map[String, String], String)](
      ("a local path", "/data/own", none, none, "ducklake"),
      ("remote, no keys", Remote, none, none, "ducklake"),
      ("remote, half the keys", Remote, none, Map("s3_access_key_id" -> "AK"), "ducklake"),
      (
        "remote, blank secret",
        Remote,
        none,
        OwnKeys.updated("s3_secret_access_key", " "),
        "ducklake"
      ),
      ("remote, another scheme's keys", "gs://b/x/", none, OwnKeys, "ducklake"),
      ("a local path with keys", "/data/own", none, OwnKeys, "ducklake"),
      ("an unknown scheme with keys", "gcs://b/x/", none, OwnKeys, "ducklake"),
      ("an upper-case scheme", "S3://b/x/", none, OwnKeys, "ducklake"),
      ("the metastore's", "", Map("dataPath" -> "/data/own"), none, "ducklake"),
      (
        "a local file",
        "/data/own.duckdb",
        Map("dbName" -> "x", "schemaName" -> "main"),
        none,
        "duckdb-file"
      ),
      ("a memory scope", "/data/own", none, none, "memory")
    )
    cases.zipWithIndex.foreach { case ((what, path, meta, store, kind), i) =>
      withClue(what) {
        val out = f.rest(tenantAdmin, s"x$i", meta, path, store, kind)
        codeOf(out) shouldBe (StatusCode.Forbidden, "superuser_required")
        out.left.toOption.get._2.message should include("leave dataPath out")
      }
    }
    f.rows shouldBe empty
    f.inits shouldBe empty
    f.admin.created shouldBe empty
  }

  it should "take a remote path with own keys, complete for the scheme" in {
    val f = new Fixture
    f.rest(tenantAdmin, "s3", dataPath = Remote, objectStore = OwnKeys) shouldBe Right("acme_s3")
    f.row("acme_s3").dataPath shouldBe Remote
    f.rest(
      tenantAdmin,
      "gs",
      dataPath = "gs://b/x/",
      objectStore = Map("gcs_hmac_key_id" -> "k", "gcs_hmac_secret" -> "s")
    ) shouldBe Right("acme_gs")
    f.rest(
      tenantAdmin,
      "az",
      dataPath = "az://c/x/",
      objectStore = Map("azure_account" -> "a", "azure_account_key" -> "k")
    ) shouldBe Right("acme_az")
  }

  it should "leave a superuser, qod serve's provisioning and a direct caller to name any" in {
    val f = new Fixture
    f.rest(superuser, "su", dataPath = "/data/su") shouldBe Right("acme_su")
    f.rest(superuser, "su3", dataPath = Remote) shouldBe Right("acme_su3")
    f.serve("sv") shouldBe Right("acme_sv")
    f.row("acme_sv").dataPath shouldBe "/data/x"
    f.create("direct", dataPath = "/data/direct").map(_.dataPath) shouldBe Right("/data/direct")
  }

  "An update" should "move no metastore dataPath for a tenant admin, or a caller stating nothing" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    codeOf(f.update(tenantAdmin, "acme_lake", Map("dataPath" -> "/data/other"))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    f.patch("acme_lake", Map("dataPath" -> "/data/other")).left.toOption.get shouldBe
      a[SupervisorError.SuperuserChoice]
    f.row("acme_lake").metastore.contains("dataPath") shouldBe false
    f.update(superuser, "acme_lake", Map("dataPath" -> "/data/other")) shouldBe Right("acme_lake")
    // Sent back as it is, it is no change; left out of a row that holds it, it is one.
    f.update(tenantAdmin, "acme_lake", Map("dataPath" -> "/data/other")) shouldBe Right("acme_lake")
    codeOf(f.update(tenantAdmin, "acme_lake", Map("schemaName" -> "main"))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
  }

  it should "keep a remote path's own keys complete, for a tenant admin" in {
    val f = new Fixture
    f.rest(tenantAdmin, "s3", dataPath = Remote, objectStore = OwnKeys) shouldBe Right("acme_s3")
    List(
      Map.empty[String, String],
      Map("s3_access_key_id" -> "AK", "s3_secret_access_key" -> ""),
      Map("s3_access_key_id" -> " "),
      Map("s3_region"        -> "eu-west-1")
    ).foreach { store =>
      withClue(store.keySet.toString) {
        codeOf(f.updateStore(tenantAdmin, "acme_s3", store)) shouldBe
          (StatusCode.Forbidden, "superuser_required")
      }
    }
    f.row("acme_s3").objectStore shouldBe OwnKeys
    // Changed and complete, with the stored secret carried.
    f.updateStore(tenantAdmin, "acme_s3", Map("s3_access_key_id" -> "AK2")) shouldBe Right(
      "acme_s3"
    )
    f.row("acme_s3").objectStore("s3_secret_access_key") shouldBe "SK"
    // A superuser may.
    f.updateStore(superuser, "acme_s3", Map("s3_secret_access_key" -> "")) shouldBe Right("acme_s3")
  }

  it should "leave every other update alone, on a row with no keys or no path" in {
    val f = new Fixture
    f.store.upsertTenantDb(
      TenantDb(
        "td-m",
        "acme",
        "acme_m",
        TenantDbKind.DuckLake,
        Map.empty,
        "s3://named-by-a-manifest/x/"
      )
    )
    f.store.upsertTenantDb(TenantDb("td-b", "acme", "acme_b", TenantDbKind.DuckLake, Map.empty, ""))
    f.sup.restore()
    List("acme_m", "acme_b").foreach { name =>
      f.handlers
        .update(UpdateTenantDbRequest("acme", name, initSql = Some("SET threads = 2;")), None)(
          tenantAdmin
        )
        .unsafeRunSync()
        .isRight shouldBe true
      f.update(tenantAdmin, name, Map("schemaName" -> "main")) shouldBe Right(name)
    }
    // No own keys to weaken: adding some, even half, is the row's own business.
    f.updateStore(tenantAdmin, "acme_m", Map("s3_region" -> "eu-west-1")) shouldBe Right("acme_m")
    f.row("acme_m").dataPath shouldBe "s3://named-by-a-manifest/x/"
  }

  // ---------- only a superuser names a Postgres server ----------

  private val ServerValues = List(
    "pgHost"     -> "pg.other",
    "pgPort"     -> "6000",
    "pgUser"     -> "me",
    "pgPassword" -> OwnPassword,
    "dbName"     -> "elsewhere"
  )

  "A tenant admin's create" should "add no key of a Postgres server, nothing written" in {
    val f = new Fixture
    ServerValues.foreach { (k, v) =>
      withClue(k) {
        val out = f.rest(tenantAdmin, "lake", Map(k -> v))
        codeOf(out) shouldBe (StatusCode.Forbidden, "superuser_required")
        out.left.toOption.get._2.message should include("a superuser sets")
        out.left.toOption.get._2.message should include(k)
      }
    }
    codeOf(f.rest(tenantAdmin, "lake", ServerValues.toMap)) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    // An empty value is a value: it would replace the default's.
    codeOf(f.rest(tenantAdmin, "lake", Map("pgHost" -> ""))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    codeOf(f.rest(tenantAdmin, "lake", Map("pgPassword" -> ""))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    f.rows shouldBe empty
    f.inits shouldBe empty
    f.admin.created shouldBe empty
  }

  it should "refuse them on every kind, but for a duckdb-file's dbName, which names its catalog" in {
    val f = new Fixture
    ServerValues.foreach { (k, v) =>
      withClue(k) {
        codeOf(f.rest(tenantAdmin, "mem", Map(k -> v), kind = "memory")) shouldBe
          (StatusCode.Forbidden, "superuser_required")
      }
    }
    val file = Map("dbName" -> "cat", "schemaName" -> "main")
    ServerValues.filterNot(_._1 == "dbName").foreach { (k, v) =>
      withClue(k) {
        codeOf(
          f.rest(tenantAdmin, "file", file + (k -> v), Remote, OwnKeys, "duckdb-file")
        ) shouldBe (StatusCode.Forbidden, "superuser_required")
      }
    }
    f.rows shouldBe empty
    f.rest(tenantAdmin, "file", file, Remote, OwnKeys, "duckdb-file") shouldBe Right("acme_file")
    f.row("acme_file").metastore("dbName") shouldBe "cat"
  }

  it should "leave schemaName and catalogAlias free, which name things inside its catalog" in {
    val f = new Fixture
    f.rest(tenantAdmin, "lake", Map("schemaName" -> "s2", "catalogAlias" -> "lake2")) shouldBe
      Right("acme_lake")
  }

  "A tenant admin's update" should "add no key of a Postgres server to a row" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    val before = f.row("acme_lake")
    ServerValues.foreach { (k, v) =>
      withClue(k) {
        val out = f.update(tenantAdmin, "acme_lake", Map("dbName" -> "acme_lake") ++ Map(k -> v))
        codeOf(out) shouldBe (StatusCode.Forbidden, "superuser_required")
        out.left.toOption.get._2.message should include(k)
        // A direct call stating no trusted writer gets the same.
        f.patch("acme_lake", Map("dbName" -> "acme_lake") ++ Map(k -> v)).left.toOption.get shouldBe
          a[SupervisorError.SuperuserChoice]
      }
    }
    f.row("acme_lake") shouldBe before
    f.admin.created.toList shouldBe List("acme_lake")
  }

  it should "change or remove no key of a row on another server, a removal being a change" in {
    val f = new Fixture
    f.create("lake", OwnLogin + ("pgPort" -> "6000")).isRight shouldBe true
    val before                                                = f.row("acme_lake")
    val shown                                                 = before.metastore - "pgPassword"
    def refused(what: String, map: Map[String, String]): Unit =
      withClue(what) {
        codeOf(f.update(tenantAdmin, "acme_lake", map)) shouldBe
          (StatusCode.Forbidden, "superuser_required")
        f.patch("acme_lake", map).left.toOption.get shouldBe a[SupervisorError.SuperuserChoice]
      }
    refused("pgHost", shown + ("pgHost"                   -> "pg.third"))
    refused("pgHost spelled otherwise", shown + ("pgHost" -> "PG.OTHER"))
    refused("pgPort", shown + ("pgPort"                   -> "6001"))
    refused("pgUser", shown + ("pgUser"                   -> "you"))
    refused("pgPassword", shown + ("pgPassword"           -> "new"))
    refused("dbName", shown + ("dbName"                   -> "elsewhere"))
    refused("no pgHost", shown - "pgHost")
    refused("no pgPort", shown - "pgPort")
    refused("no pgUser", shown - "pgUser")
    refused("no pgPassword", shown + ("pgPassword" -> ""))
    refused("nothing", Map("schemaName" -> "main"))
    f.row("acme_lake") shouldBe before
  }

  it should "not aim a row's own dbName at QoD's server by a drop of its host" in {
    val f = new Fixture
    f.store.upsertTenantDb(
      TenantDb(
        id = "td-own",
        tenantId = "acme",
        name = "acme_own",
        kind = TenantDbKind.DuckLake,
        metastore = OwnLogin + ("dbName" -> "another_tenants_catalog"),
        dataPath = "/data/own"
      )
    )
    f.sup.restore()
    val named = Map("dbName" -> "another_tenants_catalog")
    codeOf(f.update(tenantAdmin, "acme_own", named)) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    codeOf(f.update(tenantAdmin, "acme_own", named + ("pgPassword" -> ""))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    // Its own dbName left out would come back to the row's name: a change, unlike the row's name.
    codeOf(
      f.update(tenantAdmin, "acme_own", OwnLogin - "pgPassword")
    ) shouldBe (StatusCode.Forbidden, "superuser_required")
    f.row("acme_own").metastore shouldBe (OwnLogin + ("dbName" -> "another_tenants_catalog"))
    f.admin.created shouldBe empty
    f.sup.effectiveMetastoreFor("acme", "acme_own")("pgHost") shouldBe "pg.other"
  }

  it should "answer no guess of a stored password: one sent is a change, whatever it says" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    val shown = f.row("acme_lake").metastore - "pgPassword"
    codeOf(f.update(tenantAdmin, "acme_lake", shown + ("pgPassword" -> OwnPassword))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    codeOf(f.update(tenantAdmin, "acme_lake", shown + ("pgPassword" -> "a wrong guess"))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    // Sent empty to a row holding none, it changes nothing and says nothing.
    val plain = new Fixture
    plain.create("lake").isRight shouldBe true
    plain.update(tenantAdmin, "acme_lake", Map("pgPassword" -> "")) shouldBe Right("acme_lake")
  }

  it should "read a stored password in any spelling, as the launchers do" in {
    val f      = new Fixture
    val stored = Map("pgHost" -> "pg.other", "pgUser" -> "me", "PgPassword" -> OwnPassword)
    f.store.upsertTenantDb(
      TenantDb("td-sp", "acme", "acme_sp", TenantDbKind.DuckLake, stored, "/data/sp")
    )
    f.sup.restore()
    f.update(tenantAdmin, "acme_sp", Map("pgHost" -> "pg.other", "pgUser" -> "me")) shouldBe
      Right("acme_sp")
    f.row("acme_sp").metastore("PgPassword") shouldBe OwnPassword
    // A superuser's change of user keeps it, and that spelling is a login of its own.
    f.update(superuser, "acme_sp", Map("pgHost" -> "pg.other", "pgUser" -> "you")) shouldBe
      Right("acme_sp")
    f.update(superuser, "acme_sp", Map("pgHost" -> "pg.other", "pgUser" -> "me")) shouldBe
      Right("acme_sp")
    codeOf(
      f.update(
        tenantAdmin,
        "acme_sp",
        Map("pgHost" -> "pg.other", "pgUser" -> "me", "pgPassword" -> "")
      )
    ) shouldBe (StatusCode.Forbidden, "superuser_required")
    f.row("acme_sp").metastore("PgPassword") shouldBe OwnPassword
  }

  it should "stay allowed when these five stay as stored, on every row" in {
    val f = new Fixture
    f.create("byo", OwnLogin + ("pgPort" -> "6000")).isRight shouldBe true
    val shown = f.row("acme_byo").metastore - "pgPassword"
    f.update(tenantAdmin, "acme_byo", shown + ("schemaName" -> "s2")) shouldBe Right("acme_byo")
    f.row("acme_byo").metastore("pgPassword") shouldBe OwnPassword
    f.row("acme_byo").metastore("schemaName") shouldBe "s2"
    f.handlers
      .update(UpdateTenantDbRequest("acme", "acme_byo", initSql = Some("SET threads = 2;")), None)(
        tenantAdmin
      )
      .unsafeRunSync()
      .isRight shouldBe true
    f.updateStore(tenantAdmin, "acme_byo", Map("s3_region" -> "eu-west-1")) shouldBe Right(
      "acme_byo"
    )
    // A row's own name, sent or left out, is no change: nothing else can read it otherwise.
    f.update(tenantAdmin, "acme_byo", shown - "dbName") shouldBe Right("acme_byo")
    f.update(tenantAdmin, "acme_byo", shown) shouldBe Right("acme_byo")
    // A duckdb-file's dbName names its catalog.
    f.create(
      "file",
      Map("dbName" -> "cat", "schemaName" -> "main"),
      kind = TenantDbKind.DuckDbFile
    ).isRight shouldBe true
    f.update(tenantAdmin, "acme_file", Map("dbName" -> "cat2", "schemaName" -> "main")) shouldBe
      Right("acme_file")
    codeOf(
      f.update(
        tenantAdmin,
        "acme_file",
        Map("dbName" -> "cat2", "schemaName" -> "main", "pgHost" -> "pg.other")
      )
    ) shouldBe (StatusCode.Forbidden, "superuser_required")
  }

  it should "stay allowed on an old row with no login, which the update leaves as it is" in {
    val f = new Fixture
    f.store.upsertTenantDb(
      TenantDb(
        "td-old",
        "acme",
        "acme_old",
        TenantDbKind.DuckLake,
        Map("pgHost" -> "pg.other", "pgPort" -> "6000"),
        "/data/old"
      )
    )
    f.sup.restore()
    val same = Map("pgHost" -> "pg.other", "pgPort" -> "6000")
    f.update(tenantAdmin, "acme_old", same + ("schemaName" -> "s2")) shouldBe Right("acme_old")
    f.row("acme_old").metastore("schemaName") shouldBe "s2"
    // A superuser's too; and a change of its server meets the login rule.
    f.update(superuser, "acme_old", same + ("schemaName" -> "s3")) shouldBe Right("acme_old")
    codeOf(f.update(superuser, "acme_old", same + ("pgPort" -> "6001"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    codeOf(f.update(superuser, "acme_old", same + ("pgUser" -> "me"))) shouldBe
      (StatusCode.BadRequest, "own_login_required")
    f.update(
      superuser,
      "acme_old",
      same + ("pgUser" -> "me") + ("pgPassword" -> OwnPassword)
    ) shouldBe
      Right("acme_old")
    // Nothing of QoD's filled in meanwhile.
    f.inits shouldBe empty
  }

  it should "come before every other refusal: a tenant admin learns nothing else of the row" in {
    val f = new Fixture
    f.create("lake").isRight shouldBe true
    codeOf(f.update(tenantAdmin, "acme_lake", Map("pgUser" -> "qod host=pg.other"))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    codeOf(f.update(tenantAdmin, "acme_lake", Map("pgHost" -> "pg.other"))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
  }

  "Exempt from the server rule" should "be a superuser, stating it by being one" in {
    val f = new Fixture
    f.rest(superuser, "byo", OwnLogin) shouldBe Right("acme_byo")
    f.update(superuser, "acme_byo", OwnLogin + ("pgUser" -> "you")) shouldBe Right("acme_byo")
    f.row("acme_byo").metastore("pgUser") shouldBe "you"
  }

  it should "be qod serve's provisioning, whose scope states it" in {
    val f = new Fixture
    f.serve("sv", OwnLogin) shouldBe Right("acme_sv")
  }

  it should "be a direct call stating a trusted writer, and no patch by default" in {
    val f = new Fixture
    f.create("lake", OwnLogin).isRight shouldBe true
    f.patch("acme_lake", OwnLogin + ("pgUser" -> "you"), writerTrusted = true).isRight shouldBe true
    TenantDbPatch().writerTrusted shouldBe false
    f.patch("acme_lake", OwnLogin + ("pgUser" -> "she")).left.toOption.get shouldBe
      a[SupervisorError.SuperuserChoice]
  }

  private val QoDsOwnKeys = Map(
    "pgHost"     -> "pg.internal",
    "pgPort"     -> "5432",
    "pgUser"     -> "qod",
    "pgPassword" -> DefaultPassword,
    "schemaName" -> "main"
  )

  "A module's direct create" should "state who writes it: untrusted, it names no server or path" in {
    val f                                                             = new Fixture
    def refused(what: String, out: Either[SupervisorError, TenantDb]) =
      withClue(what)(out.left.toOption.get shouldBe a[SupervisorError.SuperuserChoice])
    refused("QoD's keys", f.untrusted("a", QoDsOwnKeys))
    TenantDb.ServerKeys.foreach(k => refused(k, f.untrusted("a", Map(k -> "x"))))
    refused("a path", f.untrusted("a", dataPath = "/data/shared"))
    refused("a remote path", f.untrusted("a", dataPath = "s3://b/x/"))
    f.rows shouldBe empty
    f.inits shouldBe empty
    f.admin.created shouldBe empty
  }

  it should "work with those, stating a trusted writer" in {
    val f = new Fixture
    f.sup
      .createTenantDb(
        "acme",
        "named",
        TenantDbKind.DuckLake,
        QoDsOwnKeys,
        "/data/shared",
        writerTrusted = true
      )
      .unsafeRunSync()
      .map(_.dataPath) shouldBe Right("/data/shared")
    f.row("acme_named").metastore("pgPassword") shouldBe DefaultPassword
  }

  // As qod-hosted's signup should call it: a schema only, and the row holds no login of QoD's.
  it should "be placed untrusted, with no path and no server keys, its row holding no login" in {
    val f      = new Fixture
    val placed = f.untrusted("default", Map("schemaName" -> "main")).toOption.get
    placed.dataPath shouldBe "/data/acme_default"
    placed.metastore shouldBe Map("schemaName" -> "main", "dbName" -> "acme_default")
    f.admin.created.toList shouldBe List("acme_default")
    f.inits.head._2 shouldBe true
    // The login still fills in where it is read.
    f.inits.head._1("pgPassword") shouldBe DefaultPassword
    f.sup.effectiveMetastoreFor("acme", "acme_default")("pgPassword") shouldBe DefaultPassword
  }

  "A tenant admin's refused create" should "keep its answer, ahead of initSql's check" in {
    val f       = new Fixture
    val asked   = new java.util.concurrent.atomic.AtomicInteger(0)
    val checker = new ai.starlake.quack.ondemand.initsql.InitSqlChecker:
      def check(sql: String) =
        asked.incrementAndGet()
        cats.effect.IO.pure(
          ai.starlake.quack.ondemand.initsql.InitSqlVerdict.Checked(
            1,
            List(ai.starlake.quack.ondemand.initsql.InitSqlRefusal(1, "refused"))
          )
        )
    val handlers = new TenantDbHandlers(f.sup, requireEncryption = false, initSqlChecker = checker)
    val out      = handlers
      .createTenantDb(
        TenantDbRequest(
          tenant = "acme",
          name = "lake",
          kind = "ducklake",
          metastore = Map("pgHost" -> "pg.other"),
          initSql = "SET threads = 2;"
        ),
        None
      )(tenantAdmin)
      .unsafeRunSync()
    codeOf(out) shouldBe (StatusCode.Forbidden, "superuser_required")
    asked.get shouldBe 0
    f.rows shouldBe empty
  }

  "A trusted module's row holding QoD's own keys" should "stay as stored for its tenant admin" in {
    val f = new Fixture
    f.sup
      .createTenantDb(
        "acme",
        "default",
        TenantDbKind.DuckLake,
        QoDsOwnKeys,
        "",
        writerTrusted = true
      )
      .unsafeRunSync()
      .isRight shouldBe true
    // Shown everything but the password, they may send that back.
    val shown = f.row("acme_default").metastore - "pgPassword"
    f.update(tenantAdmin, "acme_default", shown + ("schemaName" -> "s2")) shouldBe
      Right("acme_default")
    f.row("acme_default").metastore("pgPassword") shouldBe DefaultPassword
    codeOf(f.update(tenantAdmin, "acme_default", Map("schemaName" -> "s2"))) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    codeOf(
      f.update(tenantAdmin, "acme_default", shown + ("pgPassword" -> DefaultPassword))
    ) shouldBe
      (StatusCode.Forbidden, "superuser_required")
  }

  "metastore-defaults" should "tell a superuser QoD's server, and anyone else nothing" in {
    val f = new Fixture
    f.handlers.metastoreDefaults(None)(superuser).unsafeRunSync().map(_.pgHost) shouldBe
      Right("pg.internal")
    codeOf(f.handlers.metastoreDefaults(None)(tenantAdmin).unsafeRunSync()) shouldBe
      (StatusCode.Forbidden, "superuser_required")
    codeOf(f.handlers.metastoreDefaults(None)(_ => SessionScope.NoAccess).unsafeRunSync()) shouldBe
      (StatusCode.Forbidden, "superuser_required")
  }

  // ---------- a named remote path ----------

  /** Keys complete for `path`'s scheme. */
  private def keysFor(path: String): Map[String, String] =
    if path.startsWith("gs://") then Map("gcs_hmac_key_id" -> "k", "gcs_hmac_secret" -> "s")
    else if path.startsWith("az://") || path.startsWith("azure://") || path.startsWith("abfss://")
    then Map("azure_account" -> "a", "azure_account_key" -> "k")
    else OwnKeys

  private def named(f: Fixture, path: String, suffix: String = "mine") =
    f.rest(tenantAdmin, suffix, dataPath = path, objectStore = keysFor(path))

  "A tenant admin's named remote path" should
    "be refused 409 at, under or above a row's folder, in any spelling, naming no one" in {
      val f = new Fixture
      f.create("held", dataPath = "s3://shared/lakes/held/").isRight shouldBe true
      List(
        "s3://shared/lakes/held/",
        "s3://shared/lakes/held",
        "s3://shared//lakes//held/",
        "s3://SHARED/lakes/held/",
        "s3a://shared/lakes/held/",
        "r2://shared/lakes/held/",
        "gs://shared/lakes/held/",
        "az://shared/lakes/held/",
        "azure://shared/lakes/held/",
        "abfss://shared@acct.dfs.core.windows.net/lakes/held/",
        "s3://shared/lakes/held/below/",
        "s3://shared/lakes/"
      ).foreach { path =>
        withClue(path) {
          val out = named(f, path)
          codeOf(out) shouldBe (StatusCode.Conflict, "data_path_taken")
          out.left.toOption.get._2.message should not include "held"
          out.left.toOption.get._2.message should not include "shared"
        }
      }
      f.rows shouldBe List("acme_held")
      f.inits.size shouldBe 1
      f.admin.created.toList shouldBe List("acme_held")
    }

  it should "be free beside a folder, even one whose name begins the same" in {
    val f = new Fixture
    f.create("held", dataPath = "s3://shared/lakes/held/").isRight shouldBe true
    named(f, "s3://shared/lakes/held2/") shouldBe Right("acme_mine")
    named(f, "s3://other/lakes/held/", "mine2") shouldBe Right("acme_mine2")
  }

  it should "overlap no default data path" in {
    val f = new Fixture(defaults = Defaults.updated("dataPath", "s3://shared/lakes/root/"))
    codeOf(named(f, "s3://shared/lakes/root/x/")) shouldBe (StatusCode.Conflict, "data_path_taken")
    named(f, "s3://shared/lakes/mine/") shouldBe Right("acme_mine")
  }

  it should "overlap no managed prefix within retention" in {
    val f   = new Fixture
    val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    f.store.insertManagedPrefix(
      "td-gone",
      "globex",
      "globex_old",
      "s3://managed/globex_old-1/",
      now
    )
    f.store.markManagedPrefixDeleted("td-gone", now, now.plusSeconds(3600))
    codeOf(named(f, "s3://managed/globex_old-1/mine/")) shouldBe
      (StatusCode.Conflict, "data_path_taken")
    codeOf(named(f, "s3://managed/")) shouldBe (StatusCode.Forbidden, "superuser_required")
    f.rows shouldBe empty
  }

  it should "ask the set the location rules deny, and fail closed when it cannot be read" in {
    val f = new Fixture
    f.sup.dataFolderDenySet = () => Right(List("s3://remembered/old/"))
    codeOf(named(f, "s3://remembered/old/x/")) shouldBe (StatusCode.Conflict, "data_path_taken")
    f.sup.dataFolderDenySet = () => Left("the database data folders could not be read")
    val out = named(f, "s3://elsewhere/x/")
    codeOf(out) shouldBe (StatusCode.InternalServerError, "internal")
    out.left.toOption.get._2.message should include("could not be read")
    f.rows shouldBe empty
  }

  it should "be refused 403 at a bucket's root, in every spelling of a remote scheme" in {
    val f = new Fixture
    List(
      "s3://b",
      "s3://b/",
      "s3://b//",
      "s3a://b",
      "s3a://b/",
      "r2://b",
      "r2://b/",
      "gs://b",
      "gs://b/",
      "az://c",
      "az://c/",
      "azure://c",
      "azure://c/",
      "abfss://c@acct.dfs.core.windows.net",
      "abfss://c@acct.dfs.core.windows.net/"
    ).foreach { path =>
      withClue(path)(codeOf(named(f, path)) shouldBe (StatusCode.Forbidden, "superuser_required"))
    }
    f.rows shouldBe empty
    f.inits shouldBe empty
  }

  it should "be refused 403 when a segment is one a node's reader could read otherwise" in {
    val f = new Fixture
    List(
      "s3://b/./",
      "s3://b/x/..",
      "s3://b/x/../y/",
      "s3://b/*/",
      "s3://b/x?y/",
      "s3://b/[xy]/",
      "s3://b/%2e%2e/",
      "s3://*/x/"
    ).foreach { path =>
      withClue(path)(codeOf(named(f, path)) shouldBe (StatusCode.Forbidden, "superuser_required"))
    }
    f.rows shouldBe empty
  }

  it should "meet a direct call stating no trusted writer the same" in {
    val f = new Fixture
    f.create("held", dataPath = "s3://shared/lakes/held/").isRight shouldBe true
    f.untrusted("a", dataPath = "s3://shared/lakes/held/x/", objectStore = OwnKeys)
      .left
      .toOption
      .get shouldBe a[SupervisorError.DataPathTaken]
    f.untrusted("a", dataPath = "s3://shared/", objectStore = OwnKeys).left.toOption.get shouldBe
      a[SupervisorError.SuperuserChoice]
  }

  it should "not be judged for a superuser, qod serve's provisioning or a direct trusted call" in {
    val f = new Fixture
    f.create("held", dataPath = "s3://shared/lakes/held/").isRight shouldBe true
    f.rest(superuser, "su", dataPath = "s3://shared/lakes/held/x/") shouldBe Right("acme_su")
    f.rest(superuser, "root", dataPath = "s3://shared/") shouldBe Right("acme_root")
    f.handlers
      .createTenantDbAs(
        TenantDbRequest(
          tenant = "acme",
          name = "sv",
          kind = "ducklake",
          dataPath = "s3://shared/lakes/held/y/"
        ),
        SessionScope.Superuser,
        AuditActor.System
      )
      .unsafeRunSync()
      .isRight shouldBe true
    f.create("direct", dataPath = "s3://shared/lakes/").map(_.name) shouldBe Right("acme_direct")
  }

  it should "not reach a path QoD derives, which recreating a database of that name may reuse" in {
    val f = new Fixture
    // The deny set remembers a dropped database; a name placed again is the same database's.
    f.sup.dataFolderDenySet = () => Right(List("/data/acme_lake/"))
    f.rest(tenantAdmin, "lake") shouldBe Right("acme_lake")
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
