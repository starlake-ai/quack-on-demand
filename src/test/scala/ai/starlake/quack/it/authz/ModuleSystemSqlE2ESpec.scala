package ai.starlake.quack.it.authz

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.telemetry.AuditActions
import ai.starlake.quack.spi.{ManagerContext, ManagerEvent, ManagerModule}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.server.ServerEndpoint

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.util.Try

/** The module SPI's system SQL capability (`ManagerContext.systemSql`) through Main's own wiring,
  * on a real manager and a real qod-node: a module booted with the stack receives its context, and
  * what it runs goes through the routed executor as `ExecCaller.system`, labelled and audited with
  * the module's name. The world is what a hosted signup provisions: a tenant, its DuckLake
  * database, a pool and a tenant admin (erin, the default memberships).
  *
  * The statements are the ones the node refuses that admin since in-engine authorization (`dbgen`,
  * `INSTALL` / `LOAD`, the `memory` catalog on a DuckLake pool, the node's own state), which is why
  * the hosted TPC-H seeder runs as the module instead of as the enrollee.
  *
  * Cancelled without the test Postgres or a qod-node build. Manager on 20973 (REST) / 31373
  * (FlightSQL) / 29473 (front door), relay 20983, nodes 23270-23274.
  */
class ModuleSystemSqlE2ESpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val ModuleName = "e2e-system-sql"
  private val run        = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  private val Tenant     = s"msql_e2e_$run"
  private val ErinPw     = "erin-msql-pw-1"

  @volatile private var ctx: ManagerContext = null

  /** Keeps the context Main hands it: the capability under test is bound to this module's name. */
  private val module = new ManagerModule:
    def name: String                             = ModuleName
    def changelogPath: Option[String]            = None
    def start(c: ManagerContext): IO[Unit]       = IO { ctx = c }
    def endpoints: List[ServerEndpoint[Any, IO]] = Nil
    def publicPathPrefixes: Set[String]          = Set.empty
    def onEvent(event: ManagerEvent): IO[Unit]   = IO.unit
    def stop: IO[Unit]                           = IO.unit

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script and kill-quack-nodes.sh")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else if QodNodeTestBinary.path.isEmpty then Some("no qod-node build (QOD_NODE_BIN)")
    else None

  private lazy val root: Path = Files.createTempDirectory("qod-module-sql").toRealPath()

  private var stack: AuthzStack = null
  private var lake: String      = "" // the tenant-db, also its catalog alias
  private def poolKey: PoolKey  = PoolKey(Tenant, lake, "default")

  override def beforeAll(): Unit =
    if missing.isEmpty then
      stack = new AuthzStack(
        "modsql",
        20973,
        31373,
        29473,
        20983,
        23270,
        23274,
        filesRoot = root,
        stackPrefixes = List("msql_e2e_"),
        modules = List(module)
      )
      stack.boot()
      stack.post(
        "/api/tenant/create",
        Json
          .obj("id" -> s(Tenant), "displayName" -> s(Tenant), "metastore" -> Json.obj())
          .noSpaces
      )
      val lakeData = Files.createDirectories(root.resolve(Tenant).resolve("lake"))
      lake = stack
        .post(
          "/api/database/create",
          Json
            .obj(
              "tenant"   -> s(Tenant),
              "name"     -> s("lake"),
              "kind"     -> s("ducklake"),
              "dataPath" -> s(s"$lakeData/")
            )
            .noSpaces
        )
        .hcursor
        .get[String]("name")
        .fold(e => throw e, identity)
      stack.catalogDatabases += lake
      stack.post(
        "/api/pool/create",
        s"""{"tenant":"$Tenant","tenantDb":"$lake","pool":"default","size":1,
           |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
      )
      stack.post(
        "/api/user/create",
        Json
          .obj(
            "tenant"   -> s(Tenant),
            "username" -> s("erin"),
            "password" -> s(ErinPw),
            "kind"     -> s("admin")
          )
          .noSpaces
      )
      stack.await("pool default serving", 120.seconds) {
        stack.flight("SELECT 1", "admin", "admin", Tenant, "default", superuser = true).toOption
      }

  override def afterAll(): Unit =
    try if stack != null then stack.stop()
    finally
      Try {
        val tree = Files.walk(root)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }

  private def s(v: String): Json = Json.fromString(v)

  private def ready(): Unit =
    missing.foreach(reason => cancel(s"module system SQL end to end skipped: $reason"))

  private def asErin(sql: String): Either[String, List[String]] =
    stack.flight(sql, "erin", ErinPw, Tenant, "default")

  private def system(sql: String): Either[String, Unit] =
    ctx.systemSql.execute(poolKey, sql).unsafeRunSync()

  private def auditRows(): List[io.circe.ACursor] =
    val q = URLEncoder.encode(AuditActions.ModuleSql, StandardCharsets.UTF_8)
    stack
      .get(s"/api/audit/list?action=$q&tenant=$Tenant&limit=500")
      .hcursor
      .downField("events")
      .values
      .toList
      .flatten
      .map(_.hcursor)

  "a module's system SQL" should "run what the node refuses the tenant's own admin" in {
    ready()
    val probes = List(
      "SELECT count(*) > 0 FROM duckdb_extensions()",
      "CREATE OR REPLACE TABLE memory.main.msql_probe AS SELECT 1 AS x"
    )
    probes.foreach { sql =>
      withClue(s"erin: $sql -> ") {
        asErin(sql) match
          case Left(err) => err should include("QoD: ")
          case Right(r)  => fail(s"expected a refusal, got rows $r")
      }
      withClue(s"system: $sql -> ")(system(sql) shouldBe Right(()))
    }
  }

  it should "seed TPC-H into the tenant's database, as the hosted signup seeder does" in {
    ready()
    asErin("CALL dbgen(sf => 0.01, catalog => 'memory')") match
      case Left(err) => err should include("QoD: ")
      case Right(r)  => fail(s"expected a refusal, got rows $r")
    // The environment, not the capability: the node fetches tpch from the extension repository
    // unless an earlier run left it installed. Checked as the system admin, so a refusal of the
    // module's own INSTALL below still fails this case.
    val installable =
      stack.flight("INSTALL tpch", "admin", "admin", Tenant, "default", superuser = true).isRight
    assume(installable, "the node could not install the tpch extension (no network access?)")
    // The seeder's statement shapes: extension, generation into the node's memory catalog, then
    // an unqualified CTAS that lands in the session's default catalog (the tenant-db).
    List(
      "INSTALL tpch",
      "LOAD tpch",
      "DROP TABLE IF EXISTS memory.main.region",
      "CALL dbgen(sf => 0.01, catalog => 'memory')",
      "CREATE OR REPLACE TABLE region AS SELECT DISTINCT * FROM memory.main.region",
      "DROP TABLE IF EXISTS memory.main.region"
    ).foreach(sql => withClue(s"system: $sql -> ")(system(sql) shouldBe Right(())))
    // The tenant's admin reads the seeded table in its own database.
    asErin(s"SELECT count(*) FROM $lake.main.region") shouldBe Right(List("5"))
  }

  it should "answer a failed statement as its error text" in {
    ready()
    system("SELECT * FROM msql_no_such_table") match
      case Left(err) => err should include("msql_no_such_table")
      case Right(()) => fail("expected the statement to fail")
  }

  it should "audit every call and label its history with the module's name" in {
    ready()
    system("SELECT 42") shouldBe Right(())
    system("SELECT * FROM msql_missing_for_audit").isLeft shouldBe true
    val rows                                          = auditRows()
    def field(c: io.circe.ACursor, f: String): String =
      c.downField(f).as[String].getOrElse("")
    def detail(c: io.circe.ACursor, f: String): String =
      c.downField("detail").downField(f).as[String].getOrElse("")
    rows.foreach { r =>
      field(r, "actor") shouldBe s"module:$ModuleName"
      field(r, "actorRealm") shouldBe "system"
      field(r, "target") shouldBe s"$lake/default"
      detail(r, "module") shouldBe ModuleName
    }
    rows.exists(r => detail(r, "sql") == "SELECT 42" && field(r, "outcome") == "ok") shouldBe
      true
    rows.exists(r =>
      detail(r, "sql") == "SELECT * FROM msql_missing_for_audit" &&
        field(r, "outcome") == "error" && detail(r, "reason").contains("msql_missing_for_audit")
    ) shouldBe true
    // The router's own statement history names the module, never the bare "superuser" label.
    val history = stack
      .get("/api/node/statements?limit=500")
      .hcursor
      .downField("statements")
      .values
      .toList
      .flatten
      .map(_.hcursor)
      .filter(c => field(c, "tenant") == Tenant && field(c, "sql") == "SELECT 42")
    history should not be empty
    history.map(field(_, "user")).distinct shouldBe List(s"module:$ModuleName")
  }
