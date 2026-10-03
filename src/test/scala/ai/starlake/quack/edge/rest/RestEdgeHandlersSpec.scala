package ai.starlake.quack.edge.rest

import ai.starlake.quack.{FlightConfig, RestEdgeConfig}
import ai.starlake.quack.Main.given
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.{NodeLoadTracker, TestArrow}
import ai.starlake.quack.model.{
  PoolKey,
  RoleDistribution,
  SqlLiterals,
  StatementKind,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{CatalogPreviewHandlers, ErrorResponse, ExecCaller}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.route.StatementClassifier
import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cats.effect.IO
import cats.effect.unsafe.{IORuntime, Scheduler}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.arrow.vector.VectorUnloader
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource
import sttp.model.{Header, StatusCode}

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The REST edge over the executor seam, without the wire.
  *
  * The executor is a recording stub in the shape of `McpDataToolsSpec.capturingExecutor`: it keeps
  * every `(caller, poolKey, sql)` it is handed and answers canned Arrow data built with
  * [[TestArrow]], keyed on the statement's shape (probe, listing, data) and its three-part name.
  * The supervisor is the in-memory one the MCP specs use; the PAT resolver is a map. The edge's two
  * executors (recording, and the unrecording one for the probe) share the stub; each call notes
  * which one it came through.
  *
  * The HTTP-layer abuse controls are covered here too, over the same seam: the failed-auth throttle
  * (the resolver counts its lookups) and the per-user in-flight cap (latched stubs).
  */
class RestEdgeHandlersSpec extends AnyFlatSpec with Matchers:

  // ---- principals -------------------------------------------------------------------------------

  private val Token      = "qod_pat_alice"
  private val SuperToken = "qod_pat_root"
  private val OtherToken = "qod_pat_bob"
  // A second PAT of alice's (same owner as Token), and a PAT of another acme user.
  private val Token2     = "qod_pat_alice2"
  private val CarolToken = "qod_pat_carol"

  private def principal(
      tenant: Option[String],
      r: TokenRestriction,
      patId: String,
      userId: Option[String] = None
  ) =
    PatPrincipal(
      user = RbacUser(userId.getOrElse(s"u-$patId"), tenant, "alice", "user"),
      patId = patId,
      scope = SessionScope(superuser = tenant.isEmpty, manageableTenants = Set.empty),
      isAdmin = tenant.isEmpty,
      restriction = r
    )

  // ---- control plane ----------------------------------------------------------------------------

  private lazy val sup: PoolSupervisor =
    val s = new PoolSupervisor(
      StubQuackBackend.noop(),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore(),
      duckLakeInitializer = (_, _, _) => ()
    )
    def ok[A](io: IO[Either[?, A]]): A =
      io.unsafeRunSync().fold(e => throw new IllegalStateException(e.toString), identity)
    List("acme", "globex").foreach(t => ok(s.createTenant(Tenant(t))))
    val lakeMeta = Map(
      "pgHost"     -> "localhost",
      "pgPort"     -> "5432",
      "pgUser"     -> "postgres",
      "pgPassword" -> "unused",
      "schemaName" -> "main"
    )
    val lake = ok(
      s.createTenantDb(
        "acme",
        "lake",
        TenantDbKind.DuckLake,
        lakeMeta,
        "/tmp/qod-rest-h/lake",
        writerTrusted = true
      )
    )
    ok(
      s.createTenantDb(
        "acme",
        "file",
        TenantDbKind.DuckDbFile,
        Map("dbName" -> "acme_file", "schemaName" -> "main"),
        "/tmp/qod-rest-h/file",
        writerTrusted = true
      )
    )
    ok(s.createTenantDb("acme", "mem", TenantDbKind.InMemory, Map.empty, "", writerTrusted = true))
    ok(
      s.createTenantDb("acme", "vault", TenantDbKind.InMemory, Map.empty, "", writerTrusted = true)
    )
    ok(s.createTenantDb("acme", "cold", TenantDbKind.InMemory, Map.empty, "", writerTrusted = true))
    s.registerBranchTenantDb(
      TenantDb(
        id = "td-branch",
        tenantId = lake.tenantId,
        name = "acme_lake__br_0123abcd",
        kind = TenantDbKind.DuckLake,
        metastore = lakeMeta + ("catalogAlias" -> "acme_lake"),
        dataPath = "/tmp/qod-rest-h/lake__br_0123abcd",
        branchOf = Some(lake.id)
      )
    ).fold(e => throw new IllegalStateException(e.toString), identity)
    List(
      PoolKey("acme", "acme_lake", "sales"),
      PoolKey("acme", "acme_lake", "batch"),
      PoolKey("acme", "acme_file", "fpool"),
      PoolKey("acme", "acme_mem", "mpool"),
      PoolKey("acme", "acme_vault", "vpool"),
      PoolKey("acme", "acme_cold", "cpool")
    ).foreach(k => s.createPool(k, RoleDistribution(0, 0, 1)).unsafeRunSync())
    // A branch's pool carries a manager-made name, which the naming rule of user pools refuses.
    s.createPool(
      PoolKey("acme", "acme_lake__br_0123abcd", "__br_0123abcd"),
      RoleDistribution(0, 0, 1),
      internalName = true
    ).unsafeRunSync()
    // Hibernated: no node, the state a request finds before the router's resume.
    ok(s.suspendPool(ColdKey, "idle"))
    s

  private val ColdKey = PoolKey("acme", "acme_cold", "cpool")

  /** DuckLake snapshots: 2..7 exist, the tag `v1` points at 4, any timestamp resolves to 5;
    * `v_customer` is a view.
    */
  private val reader: DuckLakeCatalogReader = new DuckLakeCatalogReader(null):
    override def maxSnapshotId(): Option[Long]                           = Some(7L)
    override def snapshotExists(id: Long): Boolean                       = id >= 2 && id <= 7
    override def snapshotAtOrBefore(ts: java.time.Instant): Option[Long] = Some(5L)
    override def viewEverNamed(schema: String, name: String): Boolean    = name == "v_customer"

  private val tags: (String, String, String) => Option[Long] =
    (_, _, tag) => Option.when(tag == "v1")(4L)

  // ---- executor stub ----------------------------------------------------------------------------

  /** One executor call; `recorded` says it came through the recording executor. */
  final case class Call(caller: ExecCaller, poolKey: PoolKey, sql: String, recorded: Boolean)

  private type Responder = String => IO[Either[RouterFailure, QueryResult]]

  private val NameRe = "\"([^\"]+)\"\\.\"([^\"]+)\"\\.\"([^\"]+)\"".r

  /** The probe's columns per table name. `ghost` is missing, `hidden` is ungranted. */
  private val tableColumns: Map[String, String] = Map(
    "customer"   -> "1::INTEGER AS c_id, 'a' AS c_email, 'X' AS c_region",
    "v_customer" -> "1::INTEGER AS c_id, 'a' AS c_email",
    "fmt_t"      -> "1::INTEGER AS id, 'x' AS format",
    "pool_t"     -> "1::INTEGER AS id, 'x' AS pool",
    "tag_t"      -> "1::INTEGER AS id, 'x' AS \"asOfTag\"",
    "nested_t"   -> "1::INTEGER AS id, [1, 2] AS xs",
    "limit_t"    -> "1::INTEGER AS id, 5 AS \"limit\""
  )

  private def result(
      sql: String,
      closes: AtomicInteger,
      node: String = "n-data"
  ): IO[Either[RouterFailure, QueryResult]] =
    IO(Right(QueryResult(TestArrow.readerFor(sql), () => closes.incrementAndGet(): Unit, node, 1L)))

  /** Canned answers: probe -> the table's schema from node `n-probe`, listings -> fixed rows, data
    * -> `dataRows` rows of the customer shape from node `n-data`.
    */
  private def defaultResponder(closes: AtomicInteger, dataRows: Int): Responder = sql =>
    val name = NameRe.findFirstMatchIn(sql).map(_.group(3))
    if sql.contains("information_schema.schemata") then
      result("SELECT * FROM (VALUES ('main'), ('sales')) t(schema_name)", closes)
    else if sql.startsWith("SELECT table_type FROM information_schema.tables") then
      // The table detail's type lookup, matched on the name.
      val kind =
        if sql.contains("lower('v_customer')") then "'VIEW'"
        else if sql.contains("lower('customer')") then "'BASE TABLE'"
        else ""
      result(
        if kind.isEmpty then "SELECT 'x' AS table_type WHERE false"
        else s"SELECT $kind AS table_type",
        closes
      )
    else if sql.contains("information_schema.tables") then
      if sql.contains("'empty'") then
        result("SELECT 'x' AS table_name, 'BASE TABLE' AS table_type WHERE false", closes)
      else
        result(
          "SELECT * FROM (VALUES ('customer', 'BASE TABLE'), ('v_customer', 'VIEW')) " +
            "t(table_name, table_type)",
          closes
        )
    else if sql.startsWith("SELECT * FROM") && sql.endsWith("LIMIT 0") then
      name match
        case Some("hidden") =>
          IO.pure(Left(RouterFailure.AccessDenied("access denied: acme_lake.main.hidden")))
        case Some(n) if tableColumns.contains(n) =>
          result(s"SELECT ${tableColumns(n)} WHERE false", closes, node = "n-probe")
        case _ =>
          IO.pure(Left(RouterFailure.NotFound("permanent failure: Table does not exist")))
    else
      result(
        s"SELECT range::INTEGER AS c_id, 'e' || range AS c_email, 'X' AS c_region " +
          s"FROM range($dataRows)",
        closes
      )

  final class Fixture(
      val handlers: RestEdgeHandlers,
      val calls: ListBuffer[Call],
      val limiter: UserLimiter,
      val lookups: AtomicInteger
  ):
    val closes = new AtomicInteger()

    /** DuckLake catalog lookups (snapshot and view resolution) made so far. */
    val catalogs = new AtomicInteger()

  private val baseCfg = RestEdgeConfig(
    enabled = true,
    host = "127.0.0.1",
    port = 0,
    tlsEnabled = false,
    tlsCertChain = "",
    tlsPrivateKey = "",
    defaultLimit = 1000,
    maxRows = 100000,
    maxResponseBytes = 64L * 1024 * 1024,
    stmtTimeoutSec = 60,
    maxConnections = 16,
    maxHeaderBytes = 16384,
    headerReceiveTimeoutSec = 10,
    idleTimeoutSec = 60
  )

  private def fixture(
      restriction: TokenRestriction = TokenRestriction.Unrestricted,
      cfg: RestEdgeConfig = baseCfg,
      dataRows: Int = 3,
      respond: Option[AtomicInteger => Responder] = None,
      stmtTimeout: Option[FiniteDuration] = None,
      throttle: Option[AuthThrottle] = None,
      limiter: Option[UserLimiter] = None,
      oidc: Map[(String, String), RestPrincipal] = Map.empty
  ): Fixture =
    val calls                           = ListBuffer.empty[Call]
    val pats: Map[String, PatPrincipal] = Map(
      Token      -> principal(Some("acme"), restriction, "pat-1", Some("u-alice")),
      Token2     -> principal(Some("acme"), restriction, "pat-2", Some("u-alice")),
      CarolToken -> principal(
        Some("acme"),
        TokenRestriction.Unrestricted,
        "pat-3",
        Some("u-carol")
      ),
      SuperToken -> principal(None, TokenRestriction.Unrestricted, "pat-root"),
      OtherToken -> principal(Some("globex"), TokenRestriction.Unrestricted, "pat-bob")
    )
    val lookups     = new AtomicInteger()
    val lim         = limiter.getOrElse(UserLimiter(cfg))
    var fx: Fixture = null
    def executor(recorded: Boolean): CatalogPreviewHandlers.PreviewExecutor =
      (caller, key, sql) =>
        IO(calls.synchronized(calls += Call(caller, key, sql, recorded))) *>
          respond.fold(defaultResponder(fx.closes, dataRows))(_(fx.closes))(sql)
    fx = new Fixture(
      new RestEdgeHandlers(
        cfg,
        sup,
        t => { lookups.incrementAndGet(); pats.get(t) },
        executor(recorded = true),
        executor(recorded = false),
        (_, _) => { fx.catalogs.incrementAndGet(); reader },
        tags,
        stmtTimeout = stmtTimeout,
        throttle = throttle,
        limiter = Some(lim),
        resolveOidc = (t, jwt) => oidc.get((t, jwt))
      ),
      calls,
      lim,
      lookups
    )
    fx

  // ---- request helpers --------------------------------------------------------------------------

  private def req(
      query: String = "",
      auth: List[String] = List(s"Bearer $Token"),
      accept: Option[String] = None,
      client: String = ClientAddress.Unknown
  ) = RestRequest(auth, accept, query, "rid-0001", client)

  private type Out = Either[(StatusCode, List[Header], ErrorResponse), RestOk]

  private def rows(fx: Fixture, table: String, query: String = "", db: String = "acme_lake") =
    fx.handlers.rows("acme", db, "main", table, req(query)).unsafeRunSync()

  private def okOf(out: Out): RestOk = out.fold(e => fail(s"expected 200, got $e"), identity)

  private def errOf(out: Out): (StatusCode, List[Header], ErrorResponse) =
    out.fold(identity, ok => fail(s"expected an error, got ${ok.body}"))

  private def header(hs: List[Header], name: String): Option[String] =
    hs.find(_.name.equalsIgnoreCase(name)).map(_.value)

  private def json(body: String): Json = parse(body).fold(e => fail(e.toString), identity)

  private def dataSql(fx: Fixture): String = fx.calls.last.sql

  // ---- happy path -------------------------------------------------------------------------------

  "the rows endpoint" should "serve JSON built by one probe and one data statement" in {
    val fx  = fixture()
    val out = okOf(rows(fx, "customer"))
    json(out.body).asArray.map(_.size) shouldBe Some(3)
    header(out.headers, "Content-Type") shouldBe Some("application/json")
    fx.calls.size shouldBe 2
    fx.calls.head.sql should endWith("LIMIT 0")
    fx.calls.last.sql should startWith("SELECT \"c_id\", \"c_email\", \"c_region\" FROM")
  }

  it should "hand the executor a rest caller for the PAT, never an unrestricted one" in {
    val r  = TokenRestriction.Unrestricted.copy(maxRows = Some(500))
    val fx = fixture(restriction = r)
    okOf(rows(fx, "customer"))
    fx.calls.foreach { c =>
      c.caller.source shouldBe "rest-data"
      c.caller.edge shouldBe "rest-data"
      c.caller.patId shouldBe Some("pat-1")
      c.caller.connectionId shouldBe "rest-pat-1"
      c.caller.identity shouldBe "alice"
      c.caller.restriction shouldBe r
      c.caller.restriction should not be TokenRestriction.Unrestricted
    }
  }

  it should "send the probe and the data statement with the same AT id, echoed in X-QoD-Snapshot" in {
    val fx  = fixture()
    val out = okOf(rows(fx, "customer"))
    fx.calls.map(_.sql).foreach(_ should include("AT (VERSION => 7)"))
    header(out.headers, "X-QoD-Snapshot") shouldBe Some("7")
    val pinned = fixture()
    val out2   = okOf(rows(pinned, "customer", "asOf=3"))
    pinned.calls.map(_.sql).foreach(_ should include("AT (VERSION => 3)"))
    header(out2.headers, "X-QoD-Snapshot") shouldBe Some("3")
    val tagged = fixture()
    header(okOf(rows(tagged, "customer", "asOfTag=v1")).headers, "X-QoD-Snapshot") shouldBe
      Some("4")
  }

  it should "pin the data statement to the node that answered the probe" in {
    val fx = fixture()
    okOf(rows(fx, "customer"))
    fx.calls.head.caller.preferredNode shouldBe None
    fx.calls.last.caller.preferredNode shouldBe Some("n-probe")
  }

  it should "mark responses private per Authorization, cacheable only when asOf pins them" in {
    val current = okOf(rows(fixture(), "customer"))
    header(current.headers, "Cache-Control") shouldBe Some("private, no-cache")
    header(current.headers, "Vary") shouldBe Some("Authorization, Accept")
    List("asOf=3", "asOfTag=v1").foreach { q =>
      header(okOf(rows(fixture(), "customer", q)).headers, "Cache-Control") shouldBe
        Some("private, max-age=300")
    }
    // A timestamp resolves to whatever snapshot is current at that instant: not immutable.
    header(
      okOf(rows(fixture(), "customer", "asOfTs=2026-01-01T00:00:00Z")).headers,
      "Cache-Control"
    ) shouldBe Some("private, no-cache")
  }

  it should "vary every answer on Accept as well as Authorization, since the format is negotiated" in {
    val fx  = fixture()
    val all = List(
      fx.handlers.schemas("acme", "acme_lake", req("format=csv")),
      fx.handlers.tables("acme", "acme_lake", "main", req()),
      fx.handlers.table("acme", "acme_lake", "main", "customer", req()),
      fx.handlers.rows("acme", "acme_lake", "main", "customer", req(accept = Some("text/csv")))
    ).map(r => okOf(r.unsafeRunSync()))
    all.map(ok => header(ok.headers, "Vary")) shouldBe List.fill(4)(Some("Authorization, Accept"))
  }

  it should "serve CSV when asked by format or by Accept" in {
    val byParam = okOf(rows(fixture(), "customer", "format=csv"))
    header(byParam.headers, "Content-Type") shouldBe Some("text/csv; charset=utf-8")
    byParam.body should startWith("c_id,c_email,c_region\r\n")
    val fx       = fixture()
    val byAccept =
      fx.handlers.rows("acme", "acme_lake", "main", "customer", req(accept = Some("text/csv")))
    okOf(byAccept.unsafeRunSync()).body should startWith("c_id,")
  }

  it should "run the schema probe unrecorded and the data statement recorded" in {
    val fx = fixture()
    okOf(rows(fx, "customer"))
    fx.calls.map(c => (c.sql.endsWith("LIMIT 0"), c.recorded)) shouldBe
      List((true, false), (false, true))
    val detail = fixture()
    okOf(detail.handlers.table("acme", "acme_lake", "main", "customer", req()).unsafeRunSync())
    detail.calls.map(c => (c.sql.endsWith("LIMIT 0"), c.recorded)) shouldBe
      List((true, false), (false, true))
  }

  it should "cut a page at the byte cap at a row boundary, flagging it truncated" in {
    // Each row of the stub's data is {"c_id":n,"c_email":"en","c_region":"X"}.
    val cfg = baseCfg.copy(maxResponseBytes = 100)
    val fx  = fixture(cfg = cfg, dataRows = 10)
    val out = okOf(rows(fx, "customer", "limit=5&order=c_id"))
    out.bytes.length should be <= 100
    out.bytes.length shouldBe out.body.getBytes("UTF-8").length
    val got = json(out.body).asArray.map(_.size).getOrElse(0)
    got should (be > 0 and be < 5)
    header(out.headers, "X-QoD-Truncated") shouldBe Some("true")
    header(out.headers, "Content-Range") shouldBe Some(s"0-${got - 1}/*")
    fx.closes.get shouldBe 2
  }

  it should "close every result exactly once" in {
    val fx = fixture()
    okOf(rows(fx, "customer"))
    fx.closes.get shouldBe 2
  }

  "the listings" should "list schemas as name objects, in JSON and CSV" in {
    val fx  = fixture()
    val out = okOf(fx.handlers.schemas("acme", "acme_lake", req()).unsafeRunSync())
    json(out.body) shouldBe Json.arr(
      Json.obj("name" -> Json.fromString("main")),
      Json.obj("name" -> Json.fromString("sales"))
    )
    fx.calls.head.sql should include("'acme_lake'")
    fx.calls.foreach(_.caller.source shouldBe "rest-data")
    fx.calls.foreach(_.recorded shouldBe true)
    val csv = okOf(fx.handlers.schemas("acme", "acme_lake", req("format=csv")).unsafeRunSync())
    csv.body shouldBe "name\r\nmain\r\nsales\r\n"
    fx.closes.get shouldBe 2
  }

  it should "list tables and views with their type" in {
    val out =
      okOf(fixture().handlers.tables("acme", "acme_lake", "main", req()).unsafeRunSync())
    json(out.body) shouldBe Json.arr(
      Json.obj("name" -> Json.fromString("customer"), "type"   -> Json.fromString("table")),
      Json.obj("name" -> Json.fromString("v_customer"), "type" -> Json.fromString("view"))
    )
  }

  it should "describe one table with the columns the probe returned" in {
    val fx  = fixture()
    val out =
      okOf(fx.handlers.table("acme", "acme_lake", "main", "customer", req()).unsafeRunSync())
    json(out.body) shouldBe Json.obj(
      "name"    -> Json.fromString("customer"),
      "type"    -> Json.fromString("table"),
      "columns" -> Json.arr(
        Json.obj("name" -> Json.fromString("c_id"), "type"     -> Json.fromString("INTEGER")),
        Json.obj("name" -> Json.fromString("c_email"), "type"  -> Json.fromString("VARCHAR")),
        Json.obj("name" -> Json.fromString("c_region"), "type" -> Json.fromString("VARCHAR"))
      )
    )
    header(out.headers, "X-QoD-Snapshot") shouldBe Some("7")
    fx.calls.head.sql should endWith("LIMIT 0")
  }

  it should "refuse the rows vocabulary on the listing and detail endpoints" in {
    val fx = fixture()
    List("limit=5", "c_id=eq.1", "select=c_id").foreach { q =>
      errOf(
        fx.handlers.table("acme", "acme_lake", "main", "customer", req(q)).unsafeRunSync()
      )._3.error shouldBe "invalid_parameter"
      errOf(fx.handlers.schemas("acme", "acme_lake", req(q)).unsafeRunSync())._3.error shouldBe
        "invalid_parameter"
    }
    fx.calls shouldBe empty
  }

  // ---- tools axis -------------------------------------------------------------------------------

  "the tools axis" should "admit no restriction and a list naming rest, and refuse any other" in {
    def withTools(t: Option[Set[String]]) =
      fixture(TokenRestriction.Unrestricted.copy(tools = t))
    rows(withTools(None), "customer").isRight shouldBe true
    rows(withTools(Some(Set("rest"))), "customer").isRight shouldBe true
    val refused = withTools(Some(Set("run_sql")))
    val e       = errOf(rows(refused, "customer"))
    (e._1, e._3.error) shouldBe (StatusCode.Forbidden, "forbidden")
    refused.calls shouldBe empty
  }

  it should "admit a branchOnly token, whose every statement classifies as a SELECT" in {
    val fx = fixture(TokenRestriction.Unrestricted.copy(branchOnly = true))
    okOf(rows(fx, "customer", "c_region=eq.X&order=c_id.desc&limit=2"))
    fx.calls.toList.map(c => StatementClassifier.classify(c.sql)).distinct shouldBe
      List(StatementKind.Select)
  }

  // ---- credentials ------------------------------------------------------------------------------

  "the credentials" should "answer a superuser PAT byte-identically to a garbage token" in {
    val fx                          = fixture()
    def with401(auth: List[String]) =
      errOf(
        fx.handlers.rows("acme", "acme_lake", "main", "customer", req(auth = auth)).unsafeRunSync()
      )
    val superuser = with401(List(s"Bearer $SuperToken"))
    superuser shouldBe with401(List("Bearer qod_pat_garbage"))
    superuser._1 shouldBe StatusCode.Unauthorized
    List(
      Nil,
      List("Basic YWxpY2U6c2VjcmV0"),
      List(s"Bearer $Token", s"Bearer $Token"),
      List("Bearer eyJhbGciOiJIUzI1NiJ9.e30.sig")
    ).foreach(a => with401(a) shouldBe superuser)
    fx.calls shouldBe empty
  }

  it should "ignore ?access_token= entirely" in {
    val fx = fixture()
    val e  = errOf(
      fx.handlers
        .rows("acme", "acme_lake", "main", "customer", req(s"access_token=$Token", auth = Nil))
        .unsafeRunSync()
    )
    e._1 shouldBe StatusCode.Unauthorized
  }

  // ---- tenant -----------------------------------------------------------------------------------

  "the tenant binding" should "answer another tenant's path and an unknown tenant with one 403" in {
    val fx      = fixture()
    val other   = errOf(fx.handlers.rows("globex", "globex_x", "main", "t", req()).unsafeRunSync())
    val unknown = errOf(fx.handlers.rows("nobody", "nobody_x", "main", "t", req()).unsafeRunSync())
    other shouldBe unknown
    (other._1, other._3.error) shouldBe (StatusCode.Forbidden, "forbidden")
    other._3.message shouldBe "your token is scoped to tenant 'acme'"
    val bob = errOf(
      fx.handlers
        .rows("acme", "acme_lake", "main", "customer", req(auth = List(s"Bearer $OtherToken")))
        .unsafeRunSync()
    )
    bob._1 shouldBe StatusCode.Forbidden
    fx.calls shouldBe empty
  }

  // ---- identical 404s ---------------------------------------------------------------------------

  "a missing object and an ungranted one" should "get identical 404s after the same calls" in {
    val missing = fixture()
    val hidden  = fixture()
    val a       = errOf(rows(missing, "ghost"))
    val b       = errOf(rows(hidden, "hidden"))
    a shouldBe b
    (a._1, a._3.error) shouldBe (StatusCode.NotFound, "not_found")
    missing.calls.size shouldBe hidden.calls.size
    missing.calls.size shouldBe 1
    val d1 =
      errOf(missing.handlers.table("acme", "acme_lake", "main", "ghost", req()).unsafeRunSync())
    val d2 =
      errOf(hidden.handlers.table("acme", "acme_lake", "main", "hidden", req()).unsafeRunSync())
    d1 shouldBe d2
    d1 shouldBe a
  }

  it should "cover a missing database, one outside the databases axis and a branch" in {
    val scoped = fixture(TokenRestriction.Unrestricted.copy(databases = Some(Set("acme_lake"))))
    val out    = List(
      rows(scoped, "customer", db = "acme_nope"),
      rows(scoped, "customer", db = "acme_vault"),
      rows(fixture(), "customer", db = "acme_lake__br_0123abcd"),
      rows(fixture(), "customer", db = "acme_nope")
    ).map(errOf)
    out.distinct.size shouldBe 1
    out.head._1 shouldBe StatusCode.NotFound
    scoped.calls shouldBe empty
  }

  it should "cover a schema with nothing visible in it" in {
    val fx = fixture()
    val e  = errOf(fx.handlers.tables("acme", "acme_lake", "empty", req()).unsafeRunSync())
    e shouldBe errOf(rows(fixture(), "ghost"))
  }

  "a missing tag" should "get the plain 404, never echoing the tag" in {
    val e = errOf(rows(fixture(), "customer", "asOfTag=secret_release_tag"))
    (e._1, e._3.error) shouldBe (StatusCode.NotFound, "not_found")
    e._3.message should not include "secret_release_tag"
    e shouldBe errOf(rows(fixture(), "ghost"))
  }

  // ---- path segments ----------------------------------------------------------------------------

  "a schema or table segment that names nothing" should "404 before the executor sees anything" in {
    val fx      = fixture()
    val nothing = List("", "a\u0000b", "a\nb", "x" * 1025)
    val system  = List("information_schema", "pg_catalog", "PG_CATALOG")
    (nothing ++ system).foreach { seg =>
      withClue(seg) {
        errOf(
          fx.handlers.rows("acme", "acme_lake", seg, "customer", req()).unsafeRunSync()
        )._1 shouldBe
          StatusCode.NotFound
        errOf(fx.handlers.tables("acme", "acme_lake", seg, req()).unsafeRunSync())._1 shouldBe
          StatusCode.NotFound
      }
    }
    // The system names are never valid SCHEMA segments; as a table name they are ordinary.
    nothing.foreach { seg =>
      withClue(seg) {
        errOf(fx.handlers.rows("acme", "acme_lake", "main", seg, req()).unsafeRunSync())._1 shouldBe
          StatusCode.NotFound
      }
    }
    // The database segment keeps QoD's own name rule.
    List("..", "INFORMATION_SCHEMA", "order-items").foreach(db =>
      errOf(fx.handlers.schemas("acme", db, req()).unsafeRunSync())._1 shouldBe StatusCode.NotFound
    )
    fx.calls shouldBe empty
  }

  "a schema or table name outside QoD's own name rule" should "reach the executor quoted, so every listed object is readable" in
    List("order-items", "2024_sales", "a\"b", "a b", "ümlaut", "x" * 64).foreach { name =>
      withClue(name) {
        val fx = fixture()
        fx.handlers.rows("acme", "acme_lake", "main", name, req()).unsafeRunSync(): Unit
        fx.handlers.tables("acme", "acme_lake", name, req()).unsafeRunSync(): Unit
        val quoted = SqlLiterals.duckdbIdent(name)
        fx.calls.map(_.sql).count(_.contains(quoted)) shouldBe 1
        fx.calls.map(_.sql).count(_.contains(SqlLiterals.duckdbLiteral(name))) shouldBe 1
      }
    }

  // ---- limits -----------------------------------------------------------------------------------

  "the row cap" should "be the smallest of the server cap, the token's cap and limit" in {
    val cfg = baseCfg.copy(maxRows = 100, defaultLimit = 50)
    val fx  = fixture(TokenRestriction.Unrestricted.copy(maxRows = Some(20)), cfg, dataRows = 21)
    val out = okOf(rows(fx, "customer", "limit=50"))
    dataSql(fx) should endWith("LIMIT 21 OFFSET 0")
    json(out.body).asArray.map(_.size) shouldBe Some(20)
    header(out.headers, "X-QoD-Truncated") shouldBe Some("true")
    header(out.headers, "Content-Range") shouldBe Some("0-19/*")
  }

  it should "not flag truncation when the client's own limit cut the page" in {
    val fx  = fixture(dataRows = 11)
    val out = okOf(rows(fx, "customer", "limit=10&order=c_id&offset=5"))
    dataSql(fx) should endWith("LIMIT 11 OFFSET 5")
    header(out.headers, "X-QoD-Truncated") shouldBe None
    header(out.headers, "Content-Range") shouldBe Some("5-14/*")
  }

  it should "flag truncation when the default limit cut the page" in {
    val fx  = fixture(cfg = baseCfg.copy(defaultLimit = 5), dataRows = 6)
    val out = okOf(rows(fx, "customer"))
    dataSql(fx) should endWith("LIMIT 6 OFFSET 0")
    header(out.headers, "X-QoD-Truncated") shouldBe Some("true")
  }

  it should "answer an empty page with Content-Range */*" in {
    val out = okOf(rows(fixture(dataRows = 0), "customer"))
    out.body shouldBe "[]"
    header(out.headers, "Content-Range") shouldBe Some("*/*")
  }

  it should "cap the listings too, flagging a cut listing" in {
    val fx  = fixture(cfg = baseCfg.copy(maxRows = 1, defaultLimit = 1))
    val out = okOf(fx.handlers.schemas("acme", "acme_lake", req()).unsafeRunSync())
    json(out.body).asArray.map(_.size) shouldBe Some(1)
    header(out.headers, "X-QoD-Truncated") shouldBe Some("true")
  }

  "a page past the first" should "require an order" in {
    val fx = fixture()
    errOf(rows(fx, "customer", "offset=10"))._3.error shouldBe "order_required"
    fx.calls shouldBe empty
  }

  "time travel on a database that is not DuckLake" should "get invalid_kind and no AT" in
    List("acme_file", "acme_mem").foreach { db =>
      val fx = fixture()
      errOf(rows(fx, "customer", "asOf=3", db = db))._3.error shouldBe "invalid_kind"
      fx.calls shouldBe empty
      val plain = fixture()
      val out   = okOf(rows(plain, "customer", db = db))
      plain.calls.map(_.sql).foreach(_ should not include "AT (")
      header(out.headers, "X-QoD-Snapshot") shouldBe None
    }

  it should "qualify memory tables with the built-in memory catalog" in {
    val fx = fixture()
    okOf(rows(fx, "customer", db = "acme_mem"))
    fx.calls.head.sql shouldBe "SELECT * FROM \"memory\".\"main\".\"customer\" LIMIT 0"
  }

  "the pool parameter" should "pick a named pool of the database and refuse any other" in {
    val fx = fixture()
    okOf(rows(fx, "customer", "pool=batch"))
    fx.calls.map(_.poolKey.pool).distinct shouldBe List("batch")
    errOf(rows(fx, "customer", "pool=fpool"))._1 shouldBe StatusCode.NotFound
    errOf(rows(fx, "customer", "pool=nope"))._1 shouldBe StatusCode.NotFound
  }

  it should "refuse a pool outside the token's pools axis with acl_denied" in {
    val fx = fixture(TokenRestriction.Unrestricted.copy(pools = Some(Set("batch"))))
    val e  = errOf(rows(fx, "customer", "pool=sales"))
    (e._1, e._3.error) shouldBe (StatusCode.Forbidden, "acl_denied")
    fx.calls shouldBe empty
  }

  // ---- reserved parameters that look like filters -----------------------------------------------

  "a filter-shaped reserved parameter" should "be reserved_column before any statement runs" in {
    for
      (table, q) <- List(
        "fmt_t"    -> "format=eq.csv",
        "pool_t"   -> "pool=eq.x",
        "limit_t"  -> "limit=eq.5",
        "customer" -> "format=eq.csv",
        "customer" -> "LIMIT=eq.5",
        "customer" -> "asoftag=not.eq.v"
      )
      db <- List("acme_lake", "acme_file")
    do
      val fx = fixture()
      withClue(s"$db $table $q") {
        val e = errOf(rows(fx, table, q, db = db))
        (e._1, e._3.error) shouldBe (StatusCode.BadRequest, "reserved_column")
        fx.calls shouldBe empty
      }
  }

  it should "leave an order that is not filter-shaped an ordinary order" in {
    val fx = fixture()
    okOf(rows(fx, "customer", "order=c_id.desc"))
    dataSql(fx) should include("ORDER BY \"c_id\" DESC")
  }

  // ---- timeouts and resuming pools --------------------------------------------------------------

  "a statement past the timeout" should "get 504 and have its late result closed exactly once" in {
    val release                          = new CountDownLatch(1)
    val closes                           = new AtomicInteger()
    val slow: AtomicInteger => Responder = _ =>
      sql =>
        IO.blocking(release.await(10, TimeUnit.SECONDS)) *>
          IO(
            Right(
              QueryResult(TestArrow.oneRowReader(), () => closes.incrementAndGet(): Unit, "n", 1L)
            )
          )
    val fx = fixture(respond = Some(slow), stmtTimeout = Some(200.millis))
    val e  = errOf(rows(fx, "customer"))
    (e._1, e._3.error) shouldBe (StatusCode.GatewayTimeout, "statement_timeout")
    closes.get shouldBe 0
    release.countDown()
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while closes.get == 0 && System.nanoTime() < deadline do Thread.sleep(20)
    Thread.sleep(200)
    closes.get shouldBe 1
  }

  it should "take the token's stmtTimeoutMs when it is the smaller bound" in {
    val slow: AtomicInteger => Responder =
      _ => _ => IO.sleep(5.seconds) *> IO.pure(Left(RouterFailure.Internal("never")))
    val fx = fixture(
      TokenRestriction.Unrestricted.copy(stmtTimeoutMs = Some(150)),
      respond = Some(slow)
    )
    val start = System.nanoTime()
    errOf(rows(fx, "customer"))._1 shouldBe StatusCode.GatewayTimeout
    (System.nanoTime() - start).nanos should be < 3.seconds
  }

  "a resuming pool" should "get 503 pool_resuming with Retry-After" in {
    val resuming: AtomicInteger => Responder =
      _ => _ => IO.pure(Left(RouterFailure.Unavailable("pool is resuming, retry shortly")))
    val e = errOf(rows(fixture(respond = Some(resuming)), "customer"))
    (e._1, e._3.error) shouldBe (StatusCode.ServiceUnavailable, "pool_resuming")
    header(e._2, "Retry-After") shouldBe Some("5")
  }

  "an unavailable pool" should "get 503 pool_unavailable with Retry-After too" in {
    val gone: AtomicInteger => Responder =
      _ => _ => IO.pure(Left(RouterFailure.Unavailable("no node with role READONLY or DUAL")))
    val e = errOf(rows(fixture(respond = Some(gone)), "customer"))
    (e._1, e._3.error) shouldBe (StatusCode.ServiceUnavailable, "pool_unavailable")
    header(e._2, "Retry-After") shouldBe Some("5")
  }

  "a pool that was cold when the request arrived" should "turn the edge's own timeout into 503 pool_resuming" in {
    val stuck: AtomicInteger => Responder =
      _ => _ => IO.sleep(5.seconds) *> IO.pure(Left(RouterFailure.Internal("never")))
    val fx = fixture(respond = Some(stuck), stmtTimeout = Some(150.millis))
    val e  = errOf(rows(fx, "customer", "pool=cpool", db = "acme_cold"))
    (e._1, e._3.error) shouldBe (StatusCode.ServiceUnavailable, "pool_resuming")
    header(e._2, "Retry-After") shouldBe Some("5")
    // The same wait on a warm pool is a statement timeout.
    errOf(
      rows(fixture(respond = Some(stuck), stmtTimeout = Some(150.millis)), "customer")
    )._1 shouldBe
      StatusCode.GatewayTimeout
  }

  /** A runtime whose clock runs `factor` times faster: every sleep and every timeout fires after
    * `delay / factor`, so two waits race as they would in real time, only sooner.
    */
  private def compressedRuntime(factor: Long): IORuntime =
    val clock = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "rest-spec-compressed-clock"); t.setDaemon(true); t
    }
    val scheduler = new Scheduler:
      def sleep(delay: FiniteDuration, task: Runnable): Runnable =
        val f = clock.schedule(task, math.max(1L, delay.toNanos / factor), TimeUnit.NANOSECONDS)
        () => { f.cancel(false); () }
      def nowMillis(): Long      = System.currentTimeMillis()
      def monotonicNanos(): Long = System.nanoTime()
    IORuntime.builder().setScheduler(scheduler, () => clock.shutdown()).build()

  "the shipped defaults" should "let a cold pool's resume end inside the edge's wait" in {
    val restDefaults = ConfigSource.default.at("quack-rest").loadOrThrow[RestEdgeConfig]
    val hold         =
      ConfigSource.default.at("quack-flightsql").loadOrThrow[FlightConfig].resumeHoldTimeoutSec
    RestEdgeConfig.validate(restDefaults.copy(enabled = true), Nil, hold) shouldBe Right(())
    val cfg     = restDefaults.copy(enabled = true, host = "127.0.0.1", port = 0)
    val runtime = compressedRuntime(100)
    try
      // The router's hold expires on a pool that never wakes: its retryable 503 must reach the
      // client rather than lose the race to the edge's own wait.
      val neverWakes: AtomicInteger => Responder = _ =>
        _ =>
          IO.sleep(hold.seconds) *>
            IO.pure(Left(RouterFailure.Unavailable("pool is resuming, retry shortly")))
      val t0 = System.nanoTime()
      val e  = errOf(
        fixture(cfg = cfg, respond = Some(neverWakes)).handlers
          .rows("acme", "acme_cold", "main", "customer", req("pool=cpool"))
          .unsafeRunSync()(using runtime)
      )
      (e._1, e._3.error) shouldBe (StatusCode.ServiceUnavailable, "pool_resuming")
      header(e._2, "Retry-After") shouldBe Some("5")
      (System.nanoTime() - t0).nanos should be < 20.seconds
      // The pool wakes one second before the hold ends and the probe then takes ten seconds:
      // past the old 60 s default, inside the shipped one, so the request is served.
      val first                                = new AtomicBoolean(true)
      val lateWake: AtomicInteger => Responder = closes =>
        sql =>
          val wake =
            if first.getAndSet(false) then IO.sleep((hold - 1).seconds + 10.seconds) else IO.unit
          wake *> defaultResponder(closes, 2)(sql)
      val ok = okOf(
        fixture(cfg = cfg, respond = Some(lateWake)).handlers
          .rows("acme", "acme_cold", "main", "customer", req("pool=cpool"))
          .unsafeRunSync()(using runtime)
      )
      json(ok.body).asArray.map(_.size) shouldBe Some(2)
    finally runtime.shutdown()
  }

  // ---- views ------------------------------------------------------------------------------------

  "a view" should "be read at the current state with no snapshot pin, header or caching" in {
    val fx  = fixture()
    val out = okOf(rows(fx, "v_customer"))
    fx.calls.map(_.sql).foreach(_ should not include "AT (")
    header(out.headers, "X-QoD-Snapshot") shouldBe None
    header(out.headers, "Cache-Control") shouldBe Some("private, no-cache")
    val detail = fixture()
    val d      =
      okOf(detail.handlers.table("acme", "acme_lake", "main", "v_customer", req()).unsafeRunSync())
    detail.calls.map(_.sql).foreach(_ should not include "AT (")
    header(d.headers, "X-QoD-Snapshot") shouldBe None
  }

  it should "be typed a view by the table detail, from a lookup on its name, not a capped listing" in {
    val fx = fixture(TokenRestriction.Unrestricted.copy(maxRows = Some(1)))
    val d  =
      okOf(fx.handlers.table("acme", "acme_lake", "main", "v_customer", req()).unsafeRunSync())
    json(d.body).hcursor.get[String]("type") shouldBe Right("view")
    fx.calls.map(_.sql).filter(_.contains("information_schema.tables")) shouldBe List(
      RestSql.tableType("acme_lake", "main", "v_customer")
    )
  }

  it should "answer asOf, asOfTag and asOfTs with invalid_selector after the probe" in {
    for q <- List("asOf=3", "asOfTag=v1", "asOfTs=2026-01-01T00:00:00Z") do
      withClue(q) {
        val fx = fixture()
        val e  = errOf(rows(fx, "v_customer", q))
        (e._1, e._3.error) shouldBe (StatusCode.BadRequest, "invalid_selector")
        e._3.message shouldBe "time travel is not available on views"
        fx.calls.map(_.sql.endsWith("LIMIT 0")) shouldBe List(true)
        fx.calls.head.sql should not include "AT ("
        val detail = fixture()
        errOf(
          detail.handlers.table("acme", "acme_lake", "main", "v_customer", req(q)).unsafeRunSync()
        )._3.error shouldBe "invalid_selector"
      }
  }

  it should "leave tables their full time travel" in {
    val fx = fixture()
    header(okOf(rows(fx, "customer", "asOf=3")).headers, "X-QoD-Snapshot") shouldBe Some("3")
  }

  // ---- logs -------------------------------------------------------------------------------------

  private def capturingLogs[A](body: => A): (A, List[String]) =
    val (a, events) = capturingLevels(body)
    (a, events.map(_._2))

  /** Every line logged while `body` runs, with its level. */
  private def capturingLevels[A](body: => A): (A, List[(Level, String)]) =
    val logger   = LoggerFactory.getLogger(classOf[RestEdgeHandlers]).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    val before = logger.getLevel
    logger.setLevel(Level.ALL)
    logger.addAppender(appender)
    try
      val a = body
      (
        a,
        appender.list.asScala.toList.map(e =>
          (e.getLevel, e.getFormattedMessage + String.valueOf(e.getThrowableProxy))
        )
      )
    finally
      logger.detachAppender(appender)
      logger.setLevel(before)

  "the logs and bodies" should "never carry the token or a parameter value" in {
    val fx            = fixture()
    val (outs, lines) = capturingLogs {
      List(
        rows(fx, "customer", "c_email=bogus.secret@x.io"),
        rows(fx, "customer", "c_email=eq.secret@x.io&limit=secret"),
        rows(fx, "customer", "c_email=eq.secret@x.io")
      )
    }
    lines should not be empty
    val bodies = outs.map(_.fold(_._3.message, _.body))
    (lines ++ bodies.take(2)).foreach { l =>
      l should not include "secret"
      l should not include Token
    }
  }

  it should "sanitise hostile parameter names" in {
    val fx           = fixture()
    val (out, lines) = capturingLogs(rows(fx, "customer", "%01evil%0Aname=eq.1"))
    errOf(out)._3.message should include("?evil?name")
    lines.foreach(_ should not include "\n")
    lines.mkString should include("?evil?name")
  }

  // ---- upstream errors --------------------------------------------------------------------------

  "an upstream failure" should "get 502 with the request id and never the node's text" in {
    val secret = "password=hunter2 at node 10.0.0.7"
    List[AtomicInteger => Responder](
      _ => _ => IO.pure(Left(RouterFailure.Internal(secret))),
      _ => _ => IO.raiseError(new RuntimeException(secret))
    ).foreach { r =>
      val e = errOf(rows(fixture(respond = Some(r)), "customer"))
      (e._1, e._3.error) shouldBe (StatusCode.BadGateway, "upstream_error")
      e._3.message should include("rid-0001")
      e._3.message should not include "hunter2"
    }
  }

  it should "keep node and router text out of every line above DEBUG" in {
    val secret                               = "password=hunter2 at node 10.0.0.7"
    val denyData: AtomicInteger => Responder = closes =>
      sql =>
        if sql.endsWith("LIMIT 0") then defaultResponder(closes, 1)(sql)
        else IO.pure(Left(RouterFailure.AccessDenied(s"access denied: $secret")))
    val responders = List[AtomicInteger => Responder](
      _ => _ => IO.pure(Left(RouterFailure.Internal(secret))),
      _ => _ => IO.raiseError(new RuntimeException(secret)),
      _ => _ => IO.pure(Left(RouterFailure.Unavailable(secret))),
      denyData
    )
    responders.foreach { r =>
      val (_, lines)     = capturingLevels(rows(fixture(respond = Some(r)), "customer"))
      val (debug, above) = lines.partition(_._1 == Level.DEBUG)
      above.map(_._2).foreach(_ should not include "hunter2")
      above.map(_._2).exists(_.contains("rid-0001")) shouldBe true
      debug.map(_._2).exists(_.contains("hunter2")) shouldBe true
    }
  }

  it should "turn a denial of the data statement into acl_denied without its reason" in {
    val deny: AtomicInteger => Responder = closes =>
      sql =>
        if sql.endsWith("LIMIT 0") then defaultResponder(closes, 1)(sql)
        else IO.pure(Left(RouterFailure.AccessDenied("access denied: acme_lake.main.salary")))
    val e = errOf(rows(fixture(respond = Some(deny)), "customer"))
    (e._1, e._3.error) shouldBe (StatusCode.Forbidden, "acl_denied")
    e._3.message should not include "salary"
  }

  // ---- node refusals of the data statement -----------------------------------------------------

  "a data statement the node refuses as invalid" should "be a 400 invalid_filter when it carries filters, which the probe could not type" in {
    // A UUID column arrives as VARCHAR: a malformed value fails only at the node's cast.
    val invalid: AtomicInteger => Responder = closes =>
      sql =>
        if sql.endsWith("LIMIT 0") then defaultResponder(closes, 1)(sql)
        else IO.pure(Left(RouterFailure.BadRequest("Conversion Error: Could not convert string")))
    val e = errOf(rows(fixture(respond = Some(invalid)), "customer", "c_email=eq.not-a-uuid"))
    (e._1, e._3.error) shouldBe (StatusCode.BadRequest, "invalid_filter")
    e._3.message should not include "not-a-uuid"
    // With no filter, the node's refusal is not the client's doing.
    errOf(rows(fixture(respond = Some(invalid)), "customer"))._1 shouldBe StatusCode.BadGateway
  }

  // ---- the failed-auth throttle ----------------------------------------------------------------

  private def rowsAs(fx: Fixture, token: String, client: String, table: String = "customer") =
    fx.handlers
      .rows("acme", "acme_lake", "main", table, req(auth = List(s"Bearer $token"), client = client))
      .unsafeRunSync()

  "the failed-auth throttle" should "answer 429 to a blocked client, valid token included, before any PAT lookup" in {
    val fx = fixture()
    (1 to 20).foreach { i =>
      withClue(s"failure $i")(
        errOf(rowsAs(fx, "qod_pat_garbage", "198.51.100.9"))._1 shouldBe StatusCode.Unauthorized
      )
    }
    // The 21st failure is one too many: it is answered 429 and blocks the client.
    val blocked = errOf(rowsAs(fx, "qod_pat_garbage", "198.51.100.9"))
    (blocked._1, blocked._3.error) shouldBe (StatusCode.TooManyRequests, "too_many_auth_failures")
    header(blocked._2, "Retry-After") shouldBe Some("300")
    val lookupsBefore = fx.lookups.get
    val valid         = errOf(rowsAs(fx, Token, "198.51.100.9"))
    (valid._1, valid._3.error) shouldBe (StatusCode.TooManyRequests, "too_many_auth_failures")
    header(valid._2, "Retry-After") shouldBe Some("300")
    fx.lookups.get shouldBe lookupsBefore
    fx.calls shouldBe empty
    // Another client is untouched.
    okOf(rowsAs(fx, Token, "198.51.100.10"))
  }

  it should "count the tenant and tools 403s as authentication failures" in {
    val tools = fixture(TokenRestriction.Unrestricted.copy(tools = Some(Set("run_sql"))))
    (1 to 20).foreach(_ => errOf(rowsAs(tools, Token, "c1"))._3.error shouldBe "forbidden")
    errOf(rowsAs(tools, Token, "c1"))._3.error shouldBe "too_many_auth_failures"
    val tenant = fixture()
    (1 to 20).foreach(_ => errOf(rowsAs(tenant, OtherToken, "c1"))._3.error shouldBe "forbidden")
    errOf(rowsAs(tenant, OtherToken, "c1"))._3.error shouldBe "too_many_auth_failures"
  }

  it should "never throttle an authenticated principal's acl_denied, 404 or 400 floods" in {
    val fx = fixture()
    (1 to 30).foreach { _ =>
      errOf(rowsAs(fx, Token, "c1", table = "ghost"))._3.error shouldBe "not_found"
      errOf(
        fx.handlers
          .rows("acme", "acme_lake", "main", "customer", req("c_id=bogus.1", client = "c1"))
          .unsafeRunSync()
      )._3.error shouldBe "invalid_filter"
      errOf(
        fx.handlers
          .rows("acme", "acme_lake", "main", "customer", req("pool=nope", client = "c1"))
          .unsafeRunSync()
      )._3.error shouldBe "not_found"
    }
    okOf(rowsAs(fx, Token, "c1"))
    val acl      = fixture(TokenRestriction.Unrestricted.copy(pools = Some(Set("batch"))))
    def denied() =
      acl.handlers
        .rows("acme", "acme_lake", "main", "customer", req("pool=sales", client = "c1"))
        .unsafeRunSync()
    (1 to 30).foreach(_ => errOf(denied())._3.error shouldBe "acl_denied")
    okOf(rowsAs(acl, Token, "c1"))
  }

  it should "answer every other client's failing credential 401, however many clients fail" in {
    val t  = new AuthThrottle(20, 60, 300, maxEntries = 100000, clock = () => 0L)
    val fx = fixture(throttle = Some(t))
    // A run spread over many keys blocks none of them, and never turns into 429 for the rest.
    val statuses =
      (1 to 200).map(i => errOf(rowsAs(fx, s"qod_pat_guess$i", s"10.0.${i / 250}.${i % 250}"))._1)
    statuses.distinct shouldBe Vector(StatusCode.Unauthorized)
    okOf(rowsAs(fx, Token, "c4"))
  }

  // ---- the per-user in-flight cap --------------------------------------------------------------

  /** A responder whose every call waits on `gate`; `arrived` counts the calls that reached it. */
  private def gated(gate: CountDownLatch, arrived: AtomicInteger): AtomicInteger => Responder =
    closes =>
      sql =>
        IO(arrived.incrementAndGet()) *> IO.blocking(gate.await(10, TimeUnit.SECONDS)) *>
          defaultResponder(closes, 1)(sql)

  private def awaitCount(n: AtomicInteger, target: Int): Unit =
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while n.get < target && System.nanoTime() < deadline do Thread.sleep(5)
    n.get shouldBe target

  private def awaitDrained(lim: UserLimiter): Unit =
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while lim.inFlightTotal > 0 && System.nanoTime() < deadline do Thread.sleep(5)
    Thread.sleep(100)
    lim.inFlightTotal shouldBe 0

  /** Freed exactly once: the owner's cap of one slot is still exactly one slot. */
  private def assertOneSlotLeft(lim: UserLimiter): Unit =
    lim.tryAcquire(("acme", "u-alice")) should not be empty
    lim.tryAcquire(("acme", "u-alice")) shouldBe None

  "the per-user cap" should "admit maxConcurrentPerUser parallel requests and answer the next 429" in {
    val gate    = new CountDownLatch(1)
    val arrived = new AtomicInteger()
    val fx      = fixture(respond = Some(gated(gate, arrived)))
    val fibers  = (1 to 4).map(_ => IO(rows(fx, "customer")).start.unsafeRunSync())
    awaitCount(arrived, 4)
    val fifth = errOf(rows(fx, "customer"))
    (fifth._1, fifth._3.error) shouldBe (StatusCode.TooManyRequests, "too_many_requests")
    header(fifth._2, "Retry-After") shouldBe Some("1")
    arrived.get shouldBe 4 // the refused request never reached the executor
    gate.countDown()
    fibers.map(_.joinWithNever.unsafeRunSync()).foreach(okOf)
    fx.limiter.inFlightTotal shouldBe 0
  }

  it should "share one budget across every PAT of the same owner, not across owners" in {
    val gate    = new CountDownLatch(1)
    val arrived = new AtomicInteger()
    val fx      = fixture(
      respond = Some(gated(gate, arrived)),
      limiter = Some(new UserLimiter(perUser = 1, total = 64))
    )
    val first = IO(rowsAs(fx, Token, "c1")).start.unsafeRunSync()
    awaitCount(arrived, 1)
    errOf(rowsAs(fx, Token2, "c1"))._3.error shouldBe "too_many_requests"
    val carol = IO(rowsAs(fx, CarolToken, "c1")).start.unsafeRunSync()
    awaitCount(arrived, 2)
    gate.countDown()
    okOf(first.joinWithNever.unsafeRunSync())
    okOf(carol.joinWithNever.unsafeRunSync())
  }

  it should "apply the edge-wide cap across owners" in {
    val gate    = new CountDownLatch(1)
    val arrived = new AtomicInteger()
    val fx      = fixture(
      respond = Some(gated(gate, arrived)),
      limiter = Some(new UserLimiter(perUser = 4, total = 1))
    )
    val first = IO(rowsAs(fx, Token, "c1")).start.unsafeRunSync()
    awaitCount(arrived, 1)
    errOf(rowsAs(fx, CarolToken, "c2"))._3.error shouldBe "too_many_requests"
    gate.countDown()
    okOf(first.joinWithNever.unsafeRunSync())
  }

  it should "refuse a request over the cap before any catalog lookup" in {
    val gate    = new CountDownLatch(1)
    val arrived = new AtomicInteger()
    val fx      = fixture(
      respond = Some(gated(gate, arrived)),
      limiter = Some(new UserLimiter(perUser = 1, total = 64))
    )
    val first = IO(rows(fx, "customer")).start.unsafeRunSync()
    awaitCount(arrived, 1)
    val before = fx.catalogs.get
    errOf(rows(fx, "customer"))._3.error shouldBe "too_many_requests"
    val detail = fx.handlers.table("acme", "acme_lake", "main", "customer", req()).unsafeRunSync()
    errOf(detail)._3.error shouldBe "too_many_requests"
    fx.catalogs.get shouldBe before
    gate.countDown()
    okOf(first.joinWithNever.unsafeRunSync())
  }

  it should "hold the slot past a 504 until the node call completes, then free it exactly once" in {
    val gate    = new CountDownLatch(1)
    val arrived = new AtomicInteger()
    val lim     = new UserLimiter(perUser = 1, total = 64)
    val fx      = fixture(
      respond = Some(gated(gate, arrived)),
      stmtTimeout = Some(200.millis),
      limiter = Some(lim)
    )
    errOf(rows(fx, "customer"))._1 shouldBe StatusCode.GatewayTimeout
    lim.inFlight(("acme", "u-alice")) shouldBe 1
    errOf(rows(fx, "customer"))._3.error shouldBe "too_many_requests"
    gate.countDown()
    awaitDrained(lim)
    fx.closes.get shouldBe 1 // the late probe result was closed, and only once
    assertOneSlotLeft(lim)
  }

  it should "free the slot exactly once when the client disconnects while the node call runs" in {
    val gate    = new CountDownLatch(1)
    val arrived = new AtomicInteger()
    val lim     = new UserLimiter(perUser = 1, total = 64)
    val fx      = fixture(respond = Some(gated(gate, arrived)), limiter = Some(lim))
    val fiber   =
      fx.handlers.rows("acme", "acme_lake", "main", "customer", req()).start.unsafeRunSync()
    awaitCount(arrived, 1)
    fiber.cancel.unsafeRunSync()
    lim.inFlightTotal shouldBe 1 // the node is still working
    gate.countDown()
    awaitDrained(lim)
    fx.closes.get shouldBe 1
    assertOneSlotLeft(lim)
  }

  it should "free the slot exactly once when the client disconnects mid-read" in {
    val reading                            = new CountDownLatch(1)
    val gate                               = new CountDownLatch(1)
    val lim                                = new UserLimiter(perUser = 1, total = 64)
    val stream: AtomicInteger => Responder = closes =>
      sql =>
        if sql.endsWith("LIMIT 0") then defaultResponder(closes, 1)(sql)
        else
          IO {
            val inner  = TestArrow.readerFor("SELECT range::INTEGER AS c_id FROM range(3)")
            val reader = new GatedReader(inner, reading, gate)
            Right(
              QueryResult(
                reader,
                () => { closes.incrementAndGet(); reader.close() },
                "n-data",
                1L
              )
            )
          }
    val fx    = fixture(respond = Some(stream), limiter = Some(lim))
    val fiber =
      fx.handlers.rows("acme", "acme_lake", "main", "customer", req()).start.unsafeRunSync()
    reading.await(5, TimeUnit.SECONDS) shouldBe true
    // The disconnect: cancelling waits for the blocking read, then the finalizer runs.
    val cancel = fiber.cancel.start.unsafeRunSync()
    Thread.sleep(50)
    lim.inFlightTotal shouldBe 1
    gate.countDown()
    cancel.joinWithNever.unsafeRunSync()
    awaitDrained(lim)
    fx.closes.get shouldBe 2 // probe and data, each exactly once
    assertOneSlotLeft(lim)
  }

  /** An Arrow reader that signals `reading` and then waits on `gate` before each batch of `inner`:
    * a client disconnecting while the edge reads the result.
    */
  private final class GatedReader(inner: ArrowReader, reading: CountDownLatch, gate: CountDownLatch)
      extends ArrowReader(TestArrow.sharedAllocator):
    override def loadNextBatch(): Boolean =
      reading.countDown()
      gate.await(10, TimeUnit.SECONDS)
      val more = inner.loadNextBatch()
      if more then
        val batch = new VectorUnloader(inner.getVectorSchemaRoot).getRecordBatch
        try loadRecordBatch(batch)
        finally batch.close()
      more
    override def bytesRead(): Long                 = inner.bytesRead()
    override protected def closeReadSource(): Unit = inner.close()
    override protected def readSchema(): Schema    = inner.getVectorSchemaRoot.getSchema

  // ---- OIDC bearer -----------------------------------------------------------------------------

  private val Jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJkYXZlIn0.c2ln"

  private val dave = RestPrincipal(
    RbacUser("u-dave", Some("acme"), "dave", "user"),
    TokenRestriction.Unrestricted,
    patId = None,
    jwtRoles = Set("analyst"),
    jwtGroups = Set("finance", "analyst"),
    jwtClaims = Map("sub" -> "dave", "exp" -> "1")
  )

  "an OIDC bearer" should "run through the executor as its user, with the token's roles, groups and claims" in {
    val fx  = fixture(oidc = Map(("acme", Jwt) -> dave))
    val out = fx.handlers
      .rows("acme", "acme_lake", "main", "customer", req(auth = List(s"Bearer $Jwt")))
      .unsafeRunSync()
    okOf(out)
    fx.lookups.get shouldBe 0 // the PAT store is never asked for a JWT
    fx.calls.map(_.caller).distinct.foreach { c =>
      c.identity shouldBe "dave"
      c.connectionId shouldBe "rest-oidc-u-dave"
      c.patId shouldBe None
      (c.source, c.edge) shouldBe ("rest-data", "rest-data")
      c.jwtRoles shouldBe Set("analyst")
      c.jwtGroups shouldBe Set("finance", "analyst")
      c.jwtClaims shouldBe Map("sub" -> "dave", "exp" -> "1")
      c.superuserAdmissible shouldBe false
      c.system shouldBe false
    }
    // Its probe runs unrecorded and its data statement recorded, as for a PAT.
    fx.calls.map(c => (c.sql.endsWith("LIMIT 0"), c.recorded)) shouldBe
      List((true, false), (false, true))
  }

  it should "answer 401 when the path tenant's resolver does not verify it" in {
    // Verified for acme only: the same token on globex's path is not.
    val fx = fixture(oidc = Map(("acme", Jwt) -> dave))
    val e  = errOf(
      fx.handlers
        .rows("globex", "globex_lake", "main", "customer", req(auth = List(s"Bearer $Jwt")))
        .unsafeRunSync()
    )
    (e._1, e._3.error) shouldBe (StatusCode.Unauthorized, "unauthorized")
    fx.calls shouldBe empty
  }

  it should "count its failures against the client like any other credential" in {
    val fx = fixture()
    (1 to 20).foreach(_ => errOf(rowsAs(fx, Jwt, "c9"))._1 shouldBe StatusCode.Unauthorized)
    errOf(rowsAs(fx, Jwt, "c9"))._3.error shouldBe "too_many_auth_failures"
  }

  it should "take its in-flight slot as its own user" in {
    val gate    = new CountDownLatch(1)
    val arrived = new AtomicInteger()
    val lim     = new UserLimiter(perUser = 1, total = 64)
    val fx      = fixture(
      respond = Some(gated(gate, arrived)),
      limiter = Some(lim),
      oidc = Map(("acme", Jwt) -> dave)
    )
    val first = IO(rowsAs(fx, Jwt, "c1")).start.unsafeRunSync()
    awaitCount(arrived, 1)
    lim.inFlight(("acme", "u-dave")) shouldBe 1
    errOf(rowsAs(fx, Jwt, "c1"))._3.error shouldBe "too_many_requests"
    // Another user's PAT is not dave's budget.
    val alice = IO(rowsAs(fx, Token, "c1")).start.unsafeRunSync()
    awaitCount(arrived, 2)
    gate.countDown()
    okOf(first.joinWithNever.unsafeRunSync())
    okOf(alice.joinWithNever.unsafeRunSync())
  }

  // ---- Arrow IPC, streamed ---------------------------------------------------------------------

  private def streamedOf(ok: RestOk): RestBody.Streamed = ok.content match
    case s: RestBody.Streamed => s
    case other                => fail(s"expected a streamed body, got $other")

  private def drain(s: RestBody.Streamed): Array[Byte] =
    s.bytes.compile.to(Array).unsafeRunSync()

  private def arrowIds(bytes: Array[Byte]): Vector[Int] =
    val r = new org.apache.arrow.vector.ipc.ArrowStreamReader(
      new java.io.ByteArrayInputStream(bytes),
      TestArrow.sharedAllocator
    )
    try
      val out = Vector.newBuilder[Int]
      while r.loadNextBatch() do
        val v =
          r.getVectorSchemaRoot.getVector("c_id").asInstanceOf[org.apache.arrow.vector.IntVector]
        (0 until r.getVectorSchemaRoot.getRowCount).foreach(i => out += v.get(i))
      out.result()
    finally r.close()

  "format=arrow" should "stream the rows as Arrow IPC, the paging headers as trailers" in {
    val lim = new UserLimiter(perUser = 4, total = 64)
    val fx  = fixture(dataRows = 3, limiter = Some(lim))
    val ok  = okOf(rows(fx, "customer", "format=arrow"))
    header(ok.headers, "Content-Type") shouldBe Some("application/vnd.apache.arrow.stream")
    header(ok.headers, "Trailer") shouldBe Some("Content-Range, X-QoD-Truncated")
    header(ok.headers, "X-QoD-Snapshot") shouldBe Some("7")
    header(ok.headers, "Content-Range") shouldBe None
    // Up front, for the clients and proxies that drop trailers: the default limit here.
    header(ok.headers, "X-QoD-Limit") shouldBe Some("1000")
    val s = streamedOf(ok)
    // The data result stays open, and its slot share held, until the body has been read.
    fx.closes.get shouldBe 1
    lim.inFlightTotal shouldBe 1
    arrowIds(drain(s)) shouldBe Vector(0, 1, 2)
    fx.closes.get shouldBe 2
    lim.inFlightTotal shouldBe 0
    s.trailers.unsafeRunSync().map(h => h.name -> h.value) shouldBe List("Content-Range" -> "0-2/*")
  }

  it should "be negotiated from Accept on /rows, and refused on the documents" in {
    val fx     = fixture()
    val arrow  = Some("application/vnd.apache.arrow.stream")
    val viaAcc = fx.handlers
      .rows("acme", "acme_lake", "main", "customer", req(accept = arrow))
      .unsafeRunSync()
    header(okOf(viaAcc).headers, "Content-Type") shouldBe arrow
    drain(streamedOf(okOf(viaAcc)))
    val listing = fx.handlers.schemas("acme", "acme_lake", req(accept = arrow)).unsafeRunSync()
    (errOf(listing)._1, errOf(listing)._3.error) shouldBe (
      StatusCode.NotAcceptable,
      "unsupported_format"
    )
    val detail = fx.handlers
      .table("acme", "acme_lake", "main", "customer", req("format=arrow"))
      .unsafeRunSync()
    errOf(detail)._3.error shouldBe "unsupported_format"
  }

  it should "cut at the server cap by slicing and say so in a trailer" in {
    val fx  = fixture(cfg = baseCfg.copy(maxRows = 20, defaultLimit = 20), dataRows = 50)
    val cut = okOf(rows(fx, "customer", "format=arrow&limit=30"))
    // The effective limit, min(limit, maxRows): a page holding that many rows may continue.
    header(cut.headers, "X-QoD-Limit") shouldBe Some("20")
    val s = streamedOf(cut)
    arrowIds(drain(s)) shouldBe (0 until 20).toVector
    s.trailers.unsafeRunSync().map(h => h.name -> h.value) shouldBe
      List("Content-Range" -> "0-19/*", "X-QoD-Truncated" -> "true")
    // The client's own limit is not a truncation.
    val ownOk = okOf(rows(fx, "customer", "format=arrow&limit=5"))
    header(ownOk.headers, "X-QoD-Limit") shouldBe Some("5")
    val own = streamedOf(ownOk)
    arrowIds(drain(own)).size shouldBe 5
    own.trailers.unsafeRunSync().map(_.name) shouldBe List("Content-Range")
  }

  it should "abort the stream at the byte cap with rows left, never end it as a whole page" in {
    // 3000 rows arrive in batches of 1024; a 1 KiB cap is crossed by the first one.
    val lim = new UserLimiter(perUser = 4, total = 64)
    val fx  = fixture(
      cfg = baseCfg.copy(maxResponseBytes = 1024),
      dataRows = 3000,
      limiter = Some(lim)
    )
    val s    = streamedOf(okOf(rows(fx, "customer", "format=arrow&limit=3000&order=c_id")))
    val sent = ListBuffer.empty[Byte]
    val out  = s.bytes.chunks.evalMap(c => IO(sent ++= c.toList)).compile.drain.attempt
    out.unsafeRunSync().left.toOption.get shouldBe a[CappedArrowReader.ByteCapReached]
    // The first batch went out; the connection then drops instead of ending the body.
    arrowIds(sent.toArray) shouldBe (0 until 1024).toVector
    fx.closes.get shouldBe 2
    lim.inFlightTotal shouldBe 0
  }

  it should "end cleanly when the page fits under the byte cap" in {
    val fx = fixture(cfg = baseCfg.copy(maxResponseBytes = 1024), dataRows = 1000)
    val s  = streamedOf(okOf(rows(fx, "customer", "format=arrow&limit=1000&order=c_id")))
    arrowIds(drain(s)) shouldBe (0 until 1000).toVector
    s.trailers.unsafeRunSync().map(_.name) shouldBe List("Content-Range")
  }

  it should "abort a body still running at maxStreamSec and release its result and slot" in {
    val lim = new UserLimiter(perUser = 4, total = 64)
    val fx  = fixture(
      cfg = baseCfg.copy(maxStreamSec = 1),
      dataRows = 3000,
      limiter = Some(lim)
    )
    val s = streamedOf(okOf(rows(fx, "customer", "format=arrow&limit=3000")))
    // A slow reader: 600 ms per chunk, so the body cannot finish within the second.
    val slow = s.bytes.chunks.evalMap(_ => IO.sleep(600.millis)).compile.drain.attempt
    slow.unsafeRunSync().left.toOption.get shouldBe a[java.util.concurrent.TimeoutException]
    fx.closes.get shouldBe 2
    lim.inFlightTotal shouldBe 0
  }

  it should "read a view at the current state, unpinned and uncached, and refuse asOf on it" in {
    val fx = fixture()
    val ok = okOf(rows(fx, "v_customer", "format=arrow"))
    drain(streamedOf(ok))
    fx.calls.map(_.sql).foreach(_ should not include "AT (")
    header(ok.headers, "X-QoD-Snapshot") shouldBe None
    header(ok.headers, "Cache-Control") shouldBe Some("private, no-cache")
    val e = errOf(rows(fixture(), "v_customer", "format=arrow&asOf=3"))
    (e._1, e._3.error) shouldBe (StatusCode.BadRequest, "invalid_selector")
  }

  it should "answer a resuming or cold pool with the same 503s and Retry-After as JSON" in {
    val resuming: AtomicInteger => Responder =
      _ => _ => IO.pure(Left(RouterFailure.Unavailable("pool is resuming, retry shortly")))
    val e = errOf(rows(fixture(respond = Some(resuming)), "customer", "format=arrow"))
    (e._1, e._3.error, header(e._2, "Retry-After")) shouldBe
      (StatusCode.ServiceUnavailable, "pool_resuming", Some("5"))
    val stuck: AtomicInteger => Responder =
      _ => _ => IO.sleep(5.seconds) *> IO.pure(Left(RouterFailure.Internal("never")))
    val fx   = fixture(respond = Some(stuck), stmtTimeout = Some(150.millis))
    val cold = errOf(rows(fx, "customer", "pool=cpool&format=arrow", db = "acme_cold"))
    (cold._1, cold._3.error, header(cold._2, "Retry-After")) shouldBe
      (StatusCode.ServiceUnavailable, "pool_resuming", Some("5"))
  }

  it should "answer an ordinary 502 when the first batch fails, and close the result" in {
    val fx = fixture(respond = Some(failing(afterBatches = 0)))
    val e  = errOf(rows(fx, "customer", "format=arrow"))
    (e._1, e._3.error) shouldBe (StatusCode.BadGateway, "upstream_error")
    fx.closes.get shouldBe 2
  }

  it should "fail the stream after the first byte, and still close the result once" in {
    val lim = new UserLimiter(perUser = 4, total = 64)
    val fx  = fixture(respond = Some(failing(afterBatches = 1)), limiter = Some(lim))
    // A limit past the first batch, so the stream reads the failing second one.
    val s   = streamedOf(okOf(rows(fx, "customer", "format=arrow&limit=2500")))
    val out = s.bytes.compile.drain.attempt.unsafeRunSync()
    out.isLeft shouldBe true
    fx.closes.get shouldBe 2
    lim.inFlightTotal shouldBe 0
  }

  it should "release a body that is never read at maxStreamSec" in {
    val lim = new UserLimiter(perUser = 4, total = 64)
    val fx  = fixture(cfg = baseCfg.copy(maxStreamSec = 1), limiter = Some(lim))
    val s   = streamedOf(okOf(rows(fx, "customer", "format=arrow")))
    awaitDrained(lim)
    fx.closes.get shouldBe 2
    // Too late to read: the stream fails rather than touch a released result.
    s.bytes.compile.drain.attempt.unsafeRunSync().isLeft shouldBe true
    fx.closes.get shouldBe 2
  }

  it should "keep a body that is not started yet for 30 s, however short the token's timeout" in {
    val lim = new UserLimiter(perUser = 4, total = 64)
    val fx  = fixture(limiter = Some(lim), stmtTimeout = Some(500.millis))
    drain(streamedOf(okOf(rows(fx, "customer", "format=arrow")))) // warm
    val s = streamedOf(okOf(rows(fx, "customer", "format=arrow")))
    // Ember may take a moment to write the head; a short statement timeout is no reason to drop it.
    Thread.sleep(1200)
    lim.inFlightTotal shouldBe 1
    fx.closes.get shouldBe 3
    arrowIds(drain(s)) shouldBe Vector(0, 1, 2)
    fx.closes.get shouldBe 4
    lim.inFlightTotal shouldBe 0
  }

  /** A `/rows` request in `format` cancelled (the client went away) while the encoder pulls the
    * first batch of the data result, which reads through a [[GatedReader]]. Returns once the
    * cancellation has completed.
    */
  private def cancelledDuringFirstBatch(format: String): (UserLimiter, Fixture) =
    val reading                            = new CountDownLatch(1)
    val gate                               = new CountDownLatch(1)
    val lim                                = new UserLimiter(perUser = 1, total = 64)
    val stream: AtomicInteger => Responder = closes =>
      sql =>
        if sql.endsWith("LIMIT 0") then defaultResponder(closes, 1)(sql)
        else
          IO {
            val inner  = TestArrow.readerFor("SELECT range::INTEGER AS c_id FROM range(3)")
            val reader = new GatedReader(inner, reading, gate)
            Right(
              QueryResult(reader, () => { closes.incrementAndGet(); reader.close() }, "n-data", 1L)
            )
          }
    val fx    = fixture(respond = Some(stream), limiter = Some(lim))
    val fiber = fx.handlers
      .rows("acme", "acme_lake", "main", "customer", req(s"format=$format"))
      .start
      .unsafeRunSync()
    reading.await(5, TimeUnit.SECONDS) shouldBe true
    val cancel = fiber.cancel.start.unsafeRunSync()
    Thread.sleep(50)
    gate.countDown()
    cancel.joinWithNever.unsafeRunSync()
    (lim, fx)

  it should "release the result and the slot when cancelled while it reads the first batch" in {
    val (lim, fx) = cancelledDuringFirstBatch("arrow")
    awaitDrained(lim)
    fx.closes.get shouldBe 2 // probe and data, each exactly once
    assertOneSlotLeft(lim)
  }

  /** Data answers 3000 rows in batches of 1024 whose batch `afterBatches + 1` fails. */
  private def failing(afterBatches: Int): AtomicInteger => Responder = closes =>
    sql =>
      if sql.endsWith("LIMIT 0") then defaultResponder(closes, 1)(sql)
      else
        IO {
          val inner  = TestArrow.readerFor("SELECT range::INTEGER AS c_id FROM range(3000)")
          val reader = new FailingReader(inner, afterBatches)
          Right(
            QueryResult(reader, () => { closes.incrementAndGet(); reader.close() }, "n-data", 1L)
          )
        }

  private final class FailingReader(inner: ArrowReader, afterBatches: Int)
      extends ArrowReader(TestArrow.sharedAllocator):
    private var served                    = 0
    override def loadNextBatch(): Boolean =
      if served >= afterBatches then throw new java.io.IOException("node connection reset")
      served += 1
      val more = inner.loadNextBatch()
      if more then
        val batch = new VectorUnloader(inner.getVectorSchemaRoot).getRecordBatch
        try loadRecordBatch(batch)
        finally batch.close()
      more
    override def bytesRead(): Long                 = inner.bytesRead()
    override protected def closeReadSource(): Unit = inner.close()
    override protected def readSchema(): Schema    = inner.getVectorSchemaRoot.getSchema

  // ---- a stalled node --------------------------------------------------------------------------

  /** What ends a stalled node read early: closing the result (a blocking socket read, deaf to
    * interrupts), or interrupting the reading thread (the JDK HTTP client).
    */
  private enum Unblock:
    case OnClose, OnInterrupt

  /** Data: 3000 rows in batches of 1024, every batch from `stallFrom` on blocking `stallMs` first,
    * as a node that stops sending.
    */
  private def stalledRows(stallFrom: Int, stallMs: Long, unblock: Unblock) =
    (closes: AtomicInteger) =>
      (sql: String) =>
        if sql.endsWith("LIMIT 0") then defaultResponder(closes, 1)(sql)
        else
          IO {
            val inner  = TestArrow.readerFor("SELECT range::INTEGER AS c_id FROM range(3000)")
            val reader = new StalledReader(inner, stallFrom, stallMs, unblock)
            Right(
              QueryResult(reader, () => { closes.incrementAndGet(); reader.close() }, "n-data", 1L)
            )
          }

  private final class StalledReader(inner: ArrowReader, stallFrom: Int, stallMs: Long, by: Unblock)
      extends ArrowReader(TestArrow.sharedAllocator):
    private val closed                    = new CountDownLatch(1)
    @volatile private var served          = 0
    override def loadNextBatch(): Boolean =
      if served >= stallFrom then stall()
      served += 1
      val more = inner.loadNextBatch()
      if more then
        val batch = new VectorUnloader(inner.getVectorSchemaRoot).getRecordBatch
        try loadRecordBatch(batch)
        finally batch.close()
      more
    private def stall(): Unit = by match
      case Unblock.OnInterrupt => Thread.sleep(stallMs)
      case Unblock.OnClose     =>
        val until = System.nanoTime() + stallMs * 1000000L
        var done  = false
        while !done do
          try done = closed.await(until - System.nanoTime(), TimeUnit.NANOSECONDS)
          catch case _: InterruptedException => () // deaf to interrupts, like a socket read
          done = done || System.nanoTime() >= until
        if closed.getCount == 0 then throw new java.io.IOException("node connection closed")
    override def bytesRead(): Long                 = inner.bytesRead()
    override protected def closeReadSource(): Unit =
      closed.countDown()
      inner.close()
    override protected def readSchema(): Schema = inner.getVectorSchemaRoot.getSchema

  private def millisSince(t0: Long): Long = (System.nanoTime() - t0) / 1000000

  private def awaitClosed(fx: Fixture, n: Int): Unit =
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while fx.closes.get < n && System.nanoTime() < deadline do Thread.sleep(5)
    fx.closes.get shouldBe n

  "a stalled node" should "not hold an Arrow stream past maxStreamSec, whatever ends its read" in
    Unblock.values.foreach { unblock =>
      val lim = new UserLimiter(perUser = 4, total = 64)
      val fx  = fixture(
        cfg = baseCfg.copy(maxStreamSec = 1),
        respond = Some(stalledRows(stallFrom = 1, stallMs = 8000, unblock)),
        limiter = Some(lim)
      )
      val t0  = System.nanoTime()
      val s   = streamedOf(okOf(rows(fx, "customer", "format=arrow&limit=3000")))
      val out = s.bytes.compile.drain.attempt.unsafeRunSync()
      val ms  = millisSince(t0)
      info(s"$unblock: the stream ended after $ms ms")
      out.left.toOption.get shouldBe a[java.util.concurrent.TimeoutException]
      ms should be < 2500L
      fx.closes.get shouldBe 2
      lim.inFlightTotal shouldBe 0
    }

  it should "answer 504 at maxStreamSec when the first Arrow batch never comes" in
    Unblock.values.foreach { unblock =>
      val lim = new UserLimiter(perUser = 4, total = 64)
      val fx  = fixture(
        cfg = baseCfg.copy(maxStreamSec = 1),
        respond = Some(stalledRows(stallFrom = 0, stallMs = 8000, unblock)),
        limiter = Some(lim)
      )
      val t0 = System.nanoTime()
      val e  = errOf(rows(fx, "customer", "format=arrow&limit=3000"))
      val ms = millisSince(t0)
      info(s"$unblock: answered after $ms ms")
      (e._1, e._3.error) shouldBe (StatusCode.GatewayTimeout, "statement_timeout")
      ms should be < 2500L
      awaitClosed(fx, 2)
      awaitDrained(lim)
    }

  it should "free a request cancelled while its encoder opens, within about a second" in {
    // Arrow waits for its first batch.
    val formats = List("arrow" -> 0)
    for (format, stallFrom) <- formats; unblock <- Unblock.values do
      val lim = new UserLimiter(perUser = 4, total = 64)
      val fx  = fixture(
        respond = Some(stalledRows(stallFrom, stallMs = 8000, unblock)),
        limiter = Some(lim)
      )
      val fiber = fx.handlers
        .rows("acme", "acme_lake", "main", "customer", req(s"format=$format&limit=3000"))
        .start
        .unsafeRunSync()
      Thread.sleep(700)
      val t0 = System.nanoTime()
      fiber.cancel.unsafeRunSync()
      val ms = millisSince(t0)
      info(s"$format, $unblock: the cancel returned after $ms ms")
      ms should be < 2000L
      awaitClosed(fx, 2)
      awaitDrained(lim)
  }
