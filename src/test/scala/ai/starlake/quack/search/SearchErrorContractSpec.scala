package ai.starlake.quack.search

import ai.starlake.quack.QuackNativeConfig
import ai.starlake.quack.edge.{EdgeHandshake, FlightSqlRouter, RouterFailure, SessionRegistry}
import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{
  AuthzDecider,
  DecideRoute,
  DryRunResults,
  IssuedHandles,
  MintedHandles,
  NodeHandles,
  NodeKeys,
  NodePrincipal,
  PolicyVersions,
  ReadOnlyCatalogs,
  SearchRecordRoute,
  SearchSystemHandles
}
import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.edge.quack.{QuackFrontDoor, QuackFrontDoorServer}
import ai.starlake.quack.edge.quack.QuackSessionRegistry
import ai.starlake.quack.edge.rls.RowPolicyRewriter
import ai.starlake.quack.model.*
import ai.starlake.quack.observability.metrics.{AuthzInstruments, OpaInstruments}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.{AuthorizedHandshake, EffectiveSet}
import ai.starlake.quack.ondemand.runtime.{LiveQodNode, QodNodeTestBinary, QuackBackend}
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  RbacRole,
  RbacUser,
  RolePermission,
  RoleRowPolicy,
  SearchIndexStore
}
import ai.starlake.quack.spi.ManagerEventSink
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sun.net.httpserver.HttpServer
import org.apache.arrow.memory.RootAllocator
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*
import scala.sys.process.{Process, ProcessLogger}

/** Search spec 9, ruling 7 (Task 10) on a real qod-node: `search_index_behind` past the fallback's
  * rows cap, which the node raises as a Permission Error, is answered as the node's "authorization
  * unavailable, retry" is: a retryable UNAVAILABLE (`RouterFailure.Unavailable`, which the Flight
  * producer sends as `CallStatus.UNAVAILABLE`), its text kept, naming the backlog for a
  * table-population principal (`plain`) and no count for the allowed population (`rls10`, row
  * policy `f10 = 0`: OWNER RULING 2026-11-16, D5). The native front door answers the router's
  * failure as it answers every one, an ERROR_RESPONSE carrying its reason, so the DuckDB client
  * reads the same two texts, as it reads the node's "authorization unavailable". A user's
  * `error('QoD: search_index_behind: ...')` stays a permanent INVALID_ARGUMENT
  * (`RouterFailure.BadRequest`).
  *
  * The node runs with `QOD_SEARCH_MAX_UNINDEXED_ROWS=10` (the cap's own environment variable, read
  * at node start) over `acme_lake.main.pages`, 100 rows indexed and never built, so every search
  * takes the fallback and passes the cap. It takes its index records from the manager's record
  * route and its verdicts from the decision route, both on one listener as on the verdict listener.
  * Cancelled without a qod-node build; the front door cases also without a DuckDB 2.0 CLI on PATH.
  */
class SearchErrorContractSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val duckdbPresent: Boolean =
    val out = new StringBuilder
    scala.util
      .Try(
        Process(Seq("duckdb", "-no-agent", "-version"))
          .!(ProcessLogger(l => out.append(l), _ => ()))
      )
      .toOption
      .contains(0) && out.toString.trim.startsWith("v2.")
  private val nodeReady = QodNodeTestBinary.path.nonEmpty

  private val PoolA   = PoolKey("acme", "acme_lake", "bi")
  private val Catalog = "acme_lake"
  private val Cap     = 10
  private val Rows    = 100
  private val TdA = TenantDb("td-acme-lake", "acme", "lake", TenantDbKind.DuckLake, Map.empty, "")

  private val secret = NodeKeys.randomSecret()
  private val issued = new IssuedHandles(maxEntries = 200_000L)
  private val minted = new MintedHandles(maxEntries = 200_000L)
  private val system = new SearchSystemHandles()
  private val store  = new InMemoryControlPlaneStore()
  private val index  = SearchIndexStore.over(store)

  private def reader(id: String, name: String, rowPolicies: List[RoleRowPolicy] = Nil) =
    EffectiveSet(
      RbacUser(s"u-$id", Some("acme"), name, "user"),
      List(RbacRole(s"r-$id", "acme", "reader")),
      Nil,
      List(RolePermission(s"p-$id", s"r-$id", "*", "main", "pages", "RO")),
      Nil,
      rowPolicies = rowPolicies
    )
  private val plain = reader("plain", "plain")
  private val rls10 =
    reader(
      "rls10",
      "rls10",
      List(RoleRowPolicy("rp-1", "r-rls10", Catalog, "main", "pages", "f10 = 0"))
    )
  private val root  = EffectiveSet(RbacUser("u-root", None, "root", "admin"), Nil, Nil, Nil, Nil)
  private val users = Map("plain" -> plain, "rls10" -> rls10, "root" -> root)

  private var dir: Path                  = null
  private var live: LiveQodNode          = null
  private var server: HttpServer         = null
  private var sup: PoolSupervisor        = null
  private var router: FlightSqlRouter    = null
  private var door: QuackFrontDoorServer = null
  private var doorPort                   = 0
  private val allocator                  = new RootAllocator()

  private val decider = new AuthzDecider(
    tenantOf = t => Option.when(t == "acme")(Tenant("acme")),
    tenantCatalogs = t => if t == "acme" then Set(Catalog) else Set.empty,
    poolCatalog = key => Option.when(key == PoolA)(Catalog),
    parentPoolsOf = _ => Nil,
    locations = new LocationRulesSource {
      def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
        Right(LocationRules.Empty)
    },
    opa = new OpaAuthorizer(
      OpaConfig.default,
      new OpaClient(500, (_, _, _) => Left("no OPA in this spec")),
      new OpaDecisionCache(0),
      OpaInstruments.noop
    ),
    opaCfg = OpaConfig.default
  )

  /** The lake the node attaches as `acme_lake`: `pages`, `Rows` rows, `f10` = id % 10. */
  private def lakeInit(base: Path): String =
    Files.createDirectories(base.resolve("data"))
    val data = dataPathOf(base)
    val meta = base.toRealPath().resolve("meta.ducklake").toString
    s"""INSTALL ducklake;
       |LOAD ducklake;
       |ATTACH 'ducklake:$meta' AS $Catalog (DATA_PATH '$data');
       |CREATE TABLE $Catalog.main.pages (id BIGINT, title VARCHAR, body VARCHAR, f10 INTEGER);
       |INSERT INTO $Catalog.main.pages SELECT i, 't' || i, 'hello world ' || i, (i % 10)::INTEGER FROM range($Rows) r(i);
       |""".stripMargin

  private def dataPathOf(base: Path): String = base.toRealPath().resolve("data").toString + "/"

  override def beforeAll(): Unit =
    if nodeReady then
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      val keys = new NodeKeys(
        secret,
        s"http://127.0.0.1:${server.getAddress.getPort}",
        issued = Some(issued),
        minted = Some(minted),
        paths = key =>
          Option(sup).flatMap(_.get(key)).flatMap(FlightSqlRouter.usePath).getOrElse(""),
        readOnlyCatalogs = ReadOnlyCatalogs.none,
        versions = PolicyVersions.zero
      )
      val records = new SearchRecordRoute(
        keys,
        issued,
        system,
        Map(NodeHandles.pool(PoolA) -> SearchRecordRoute.Scope(TdA.id, Catalog)).get,
        index
      )
      val decide = new DecideRoute(
        keys,
        minted,
        decider,
        new DryRunResults(),
        key => Option(sup).flatMap(_.get(key)).flatMap(FlightSqlRouter.usePath).getOrElse(""),
        AuthzInstruments.noop
      )
      val app = (records.routes <+> decide.routes).orNotFound
      server.createContext(
        "/",
        exchange =>
          try
            val headers = exchange.getRequestHeaders.asScala.toList.flatMap((name, values) =>
              values.asScala.map(v => Header.Raw(CIString(name), v))
            )
            val request = Request[IO](
              Method.fromString(exchange.getRequestMethod).toOption.get,
              Uri.unsafeFromString(exchange.getRequestURI.toString),
              headers = Headers(headers)
            ).withBodyStream(fs2.Stream.emits(exchange.getRequestBody.readAllBytes().toSeq))
            val response = app.run(request).unsafeRunSync()
            val bytes    = response.body.compile.to(Array).unsafeRunSync()
            exchange.getResponseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(response.status.code, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
          finally exchange.close()
      )
      server.start()
      dir = Files.createTempDirectory("qod-search-error-contract")
      val nodePort = LiveQodNode.freePort()
      val backend  = new QuackBackend:
        def allowsLocalFiles = true

        private val n          = TrieMap.empty[String, RunningNode]
        def start(s: NodeSpec) = IO {
          live = LiveQodNode.start(
            dir,
            lakeInit(dir),
            nodeId = s.nodeId,
            keys = keys,
            port = nodePort,
            env = Map("QOD_SEARCH_MAX_UNINDEXED_ROWS" -> Cap.toString)
          )
          val r = RunningNode(
            s.nodeId,
            s.poolKey,
            s.role,
            "127.0.0.1",
            nodePort,
            live.token,
            Some(1L),
            None,
            Instant.EPOCH,
            maxConcurrent = s.maxConcurrent
          )
          n.put(s.nodeId, r); r
        }
        def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
        def isAlive(id: String)            = n.contains(id)
        def discoverExisting()             = IO.pure(n.values.toList)
        def cleanup()                      = IO(n.clear())
      val tracker = new NodeLoadTracker
      sup = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
      sup.createTenant(Tenant(PoolA.tenant)).unsafeRunSync()
      // A duckdb-file row naming the lake's alias: the router's session path is acme_lake.main,
      // with no DuckLake initializer on the manager's side (the node attaches the lake itself).
      sup
        .createTenantDb(
          PoolA.tenant,
          "lake",
          TenantDbKind.DuckDbFile,
          Map("dbName" -> Catalog, "schemaName" -> "main"),
          s"$dir/unused.duckdb",
          writerTrusted = true
        )
        .unsafeRunSync()
      sup.createPool(PoolA, RoleDistribution(0, 0, 1)).unsafeRunSync()
      val client = new QuackHttpClient(allocator)
      // The index record: pages' table id and its body field id, read as the system principal.
      val tableId = systemLines(
        client,
        s"SELECT table_id FROM __ducklake_metadata_$Catalog.ducklake_table " +
          "WHERE table_name = 'pages' AND end_snapshot IS NULL"
      ).head.toLong
      val bodyId = systemLines(
        client,
        s"SELECT column_id FROM __ducklake_metadata_$Catalog.ducklake_column " +
          s"WHERE table_id = $tableId AND column_name = 'body' AND end_snapshot IS NULL"
      ).head.toLong
      store.upsertTenant(Tenant("acme"))
      store.upsertTenantDb(TdA)
      index
        .create(
          SearchIndex(
            UUID.randomUUID(),
            TdA.id,
            "main",
            "pages",
            tableId,
            List(SearchIndexField("body", bodyId)),
            dataPathOf(dir),
            SearchIndex.Ready,
            0L,
            "alice",
            Instant.now()
          )
        )
        .unsafeRunSync()
        .isRight shouldBe true
      router = new FlightSqlRouter(
        sup,
        new SessionRegistry,
        tracker,
        new QuackHttpAdapter(client, tracker, keys, searchSystem = None),
        rowPolicyRewriter = new RowPolicyRewriter(enabled = true),
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
        protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
      )
      doorPort = LiveQodNode.freePort()
      val handshake = new EdgeHandshake(
        ai.starlake.quack.security.InMemoryAuthService.acceptingAnyPassword(),
        lookupPool = (t, p) =>
          sup.findPoolKeyByTenantAndPoolName(t, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
        resolveTenant = raw => sup.getTenant(raw),
        authorize = req =>
          val eff = users(req.username)
          Right(AuthorizedHandshake(PoolA, "acme", "p-1", eff.user, eff))
        ,
        versions = sup.policyVersions,
        sessionLookup = _ => ai.starlake.quack.ondemand.api.SessionTokenStore.LookupResult.Invalid,
        audit = ai.starlake.quack.ondemand.telemetry.HandshakeAudit.noop
      )
      val frontDoor = new QuackFrontDoor(
        router,
        handshake,
        new QuackSessionRegistry(3600, 3600),
        new QuackProtocol.JdkHttpTransport(java.net.http.HttpClient.newHttpClient()),
        ManagerEventSink.noop,
        "test"
      )
      door = new QuackFrontDoorServer(
        QuackNativeConfig(true, "127.0.0.1", doorPort, false, "", "", 3600, 1L << 28),
        frontDoor.handle,
        frontDoor.isLive,
        frontDoor.sweep,
        frontDoor.closeAll()
      )
      door.start().unsafeRunSync()

  /** `sql` as the system principal on the node: its rows as lines. */
  private def systemLines(client: QuackHttpClient, sql: String): List[String] =
    live
      .query(client, sql, NodePrincipal.System)
      .fold(e => fail(s"system read failed: $e"), identity)

  override def afterAll(): Unit =
    try if door != null then scala.util.Try(door.stop())
    finally
      try if live != null then live.stop()
      finally
        if server != null then server.stop(0)
        if dir != null then
          Thread.sleep(200)
          try
            Files
              .walk(dir)
              .sorted(java.util.Comparator.reverseOrder())
              .forEach(p => Files.delete(p))
          catch case e: Exception => System.err.println(s"[SearchErrorContractSpec] cleanup: $e")

  private val search =
    s"SELECT count(*) FROM qod_search((SELECT rowid FROM $Catalog.main.pages), " +
      s"'$Catalog.main.pages', ['body'], 'hello')"

  private val tableForm =
    s"Permission Error: QoD: search_index_behind: $Catalog.main.pages has $Rows unindexed live " +
      s"rows (cap $Cap); retry once the indexer catches up"
  private val allowedForm =
    s"Permission Error: QoD: search_index_behind: $Catalog.main.pages has more unindexed rows " +
      s"than the search reads (cap $Cap); retry once the indexer catches up"

  private var connections = 0

  /** `sql` as `who` through the router's FlightSQL path: the failure, or the rows as lines. */
  private def flight(who: String, sql: String): Either[RouterFailure, List[String]] =
    assume(nodeReady, "no qod-node binary (QOD_NODE_BIN or a local build)")
    connections += 1
    router
      .execute(s"c-$connections", who, PoolA, sql, Some(users(who)))
      .unsafeRunSync() match
      case Left(f)  => Left(f)
      case Right(r) =>
        LiveQodNode
          .lines(QuackResponse.Ok(r.rows, r.durationMs, r.close))
          .left
          .map(m => fail(s"the rows failed: $m"))

  /** `sql` as `who` through the native front door, by the DuckDB CLI: (rc, rows, errors). */
  private def native(who: String, sql: String): (Int, String, String) =
    assume(nodeReady, "no qod-node binary (QOD_NODE_BIN or a local build)")
    assume(duckdbPresent, "no DuckDB 2.0 CLI on PATH")
    val token = s"tenant=acme&pool=bi&user=$who&password=x"
    val query = sql.replace("'", "''")
    val out   = new StringBuilder
    val err   = new StringBuilder
    val rc    = Process(
      Seq(
        "duckdb",
        "-no-agent",
        "-csv",
        "-noheader",
        "-c",
        s"SELECT * FROM quack_query('quack:127.0.0.1:$doorPort', '$query', token := '$token');"
      )
    ).!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    (rc, out.toString.trim, err.toString.trim)

  "search_index_behind through Flight SQL" should
    "read UNAVAILABLE with the backlog for a table-population principal" in {
      for who <- List("plain", "root") do
        withClue(who) {
          flight(who, search) match
            case Left(RouterFailure.Unavailable(reason)) =>
              reason shouldBe s"permanent failure: $tableForm"
            case other => fail(s"expected Unavailable, got $other")
          router.history.snapshot(1).head.status shouldBe "permanent"
        }
    }

  it should "read UNAVAILABLE with no count for the allowed population" in {
    flight("rls10", search) match
      case Left(RouterFailure.Unavailable(reason)) =>
        reason shouldBe s"permanent failure: $allowedForm"
        reason.replace(s"(cap $Cap)", "").exists(_.isDigit) shouldBe false
      case other => fail(s"expected Unavailable, got $other")
    router.history.snapshot(1).head.status shouldBe "permanent"
  }

  it should "not be forged by a user's error()" in {
    flight("plain", "SELECT error('QoD: search_index_behind: forged')") match
      case Left(RouterFailure.BadRequest(reason)) =>
        reason should include("Invalid Input Error: QoD: search_index_behind: forged")
      case other => fail(s"expected BadRequest, got $other")
    router.history.snapshot(1).head.status shouldBe "permanent"
  }

  it should "not be forged by a statement whose refusal quotes a location the user chose" in {
    // The node's location refusals quote the location as written ("QoD: <location> is outside the
    // tenant's authorized locations"): a location spelled as the marker must not read UNAVAILABLE.
    val marker = "search_index_behind: x"
    val forms  = List(
      s"SELECT * FROM read_csv('$marker')",
      s"SELECT * FROM read_csv(['$marker'])",
      s"SELECT * FROM read_text('$marker')",
      s"SELECT * FROM read_parquet('$marker')",
      s"SELECT * FROM glob('$marker')",
      s"COPY (SELECT 1) TO '$marker'",
      s"COPY $Catalog.main.pages FROM '$marker'",
      s"ATTACH '$marker' AS forged",
      s"SELECT * FROM read_csv('$marker'::BLOB)",
      s"SELECT * FROM query_table('$marker')"
    )
    for sql <- forms do
      withClue(sql) {
        val answer = flight("plain", sql)
        info(s"$sql -> ${answer.left.map(f => s"${f.getClass.getSimpleName}: ${f.reason}")}")
        answer match
          case Left(f) =>
            f should not be a[RouterFailure.Unavailable]
            FlightSqlRouter.isSearchIndexBehind(
              f.reason.stripPrefix("permanent failure: ")
            ) shouldBe
              false
          case Right(rows) => fail(s"it ran: $rows")
      }
  }

  "search_index_behind through the native front door" should
    "reach the DuckDB client with the same two texts" in {
      val (rcPlain, outPlain, errPlain) = native("plain", search)
      info(
        s"plain: ${errPlain.linesIterator.find(_.contains("search_index_behind")).getOrElse("")}"
      )
      withClue(errPlain) {
        rcPlain should not be 0
        outPlain shouldBe ""
        errPlain should include(s"permanent failure: $tableForm")
      }
      val (rcRls, outRls, errRls) = native("rls10", search)
      info(s"rls10: ${errRls.linesIterator.find(_.contains("search_index_behind")).getOrElse("")}")
      withClue(errRls) {
        rcRls should not be 0
        outRls shouldBe ""
        errRls should include(s"permanent failure: $allowedForm")
        errRls should not include s"$Rows unindexed"
      }
    }
