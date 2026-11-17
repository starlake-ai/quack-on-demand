package ai.starlake.quack.search

import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{
  AuthzDecider,
  DecidePurpose,
  DecideRoute,
  DryRunResults,
  IssuedHandles,
  MintedHandles,
  NodeKeys,
  NodePrincipal,
  PolicyVersions,
  ReadOnlyCatalogs,
  SearchRecordRoute,
  SearchSystemHandles
}
import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.model.{
  PoolKey,
  SearchIndex,
  SearchIndexField,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.observability.metrics.{AuthzInstruments, OpaInstruments}
import ai.starlake.quack.ondemand.api.{
  CatalogPreviewHandlers,
  RestCaller,
  SearchHandlers,
  SearchIndexBuildRequest
}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.LiveTable
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.{LiveQodNode, QodNodeTestBinary}
import ai.starlake.quack.ondemand.search.{SearchCatalog, SearchIndexService}
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  RbacRole,
  RbacUser,
  RolePermission,
  SearchIndexStore
}
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
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
import scala.jdk.CollectionConverters.*

/** Task 7 live: real qod-nodes taking their index records from the manager's own record route
  * ([[SearchRecordRoute]]) and verdicts from its decision route, both on one listener as on the
  * verdict listener. Every principal takes its records from the route, the system principal's build
  * included (D4): Task 6's interim build runs through a [[QuackHttpAdapter]] that records its
  * system handles ([[SearchSystemHandles]]), and an adapter that records none gets no record. Each
  * node runs with `QOD_SEARCH_MAX_UNINDEXED_ROWS=0`, so a search answers only from bundles that
  * opened under the record's MAC key. A superuser's search makes no decide call; a tenant user's
  * makes its verdict's only, and its record comes from the route through its own handle (the
  * store's lookups move). A node uses a record only when its bundle root is the catalog's own
  * DuckLake data path. A node of tenant B's pool, whose tenant-db names its catalog `acme_lake`
  * too, gets acme's table in `missing`. Cancelled without a qod-node build.
  */
class QodNodeSearchRecordSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val allocator          = new RootAllocator()
  private val client             = new QuackHttpClient(allocator)
  private var dir: Path          = null
  private var nodeA: LiveQodNode = null
  private var nodeB: LiveQodNode = null
  private var server: HttpServer = null

  private val PoolA   = PoolKey("acme", "lake", "bi")
  private val PoolB   = PoolKey("globex", "lake", "bi")
  private val Catalog = "acme_lake"
  private val TdA = TenantDb("td-acme-lake", "acme", "lake", TenantDbKind.DuckLake, Map.empty, "")

  private val secret         = NodeKeys.randomSecret()
  private val issued         = new IssuedHandles(maxEntries = 200_000L)
  private val minted         = new MintedHandles(maxEntries = 200_000L)
  private val system         = new SearchSystemHandles()
  private var keys: NodeKeys = null
  private val store          = new InMemoryControlPlaneStore()
  private val indexes        = SearchIndexStore.over(store)
  private val PagesIndex     = UUID.randomUUID()

  /** [[indexes]] counting the route's lookups: which statements reached the route for a record. */
  private object counted extends SearchIndexStore:
    val finds                                   = new java.util.concurrent.atomic.AtomicInteger(0)
    def find(tenantDbId: String, tableId: Long) =
      IO(finds.incrementAndGet()) *> indexes.find(tenantDbId, tableId)
    def create(ix: SearchIndex)                = indexes.create(ix)
    def list(tenantDbIds: Set[String])         = indexes.list(tenantDbIds)
    def findByIndexId(indexId: UUID)           = indexes.findByIndexId(indexId)
    def delete(indexId: UUID)                  = indexes.delete(indexId)
    def setState(indexId: UUID, state: String) = indexes.setState(indexId, state)
    def deleteForTenantDb(tenantDbId: String)  = indexes.deleteForTenantDb(tenantDbId)
    def recordSegment(
        indexId: UUID,
        pathKey: String,
        path: String,
        bytes: Long,
        docs: Long,
        buildMs: Long,
        formatVersion: Int
    ) = indexes.recordSegment(indexId, pathKey, path, bytes, docs, buildMs, formatVersion)

  /** Each table of node A's lake: its DuckLake table id and field ids, read from its metadata. */
  private var tables: Map[String, (Long, Map[String, Long])] = Map.empty
  private def pagesId: Long                                  = tables("pages")._1
  private def pagesFields: Map[String, Long]                 = tables("pages")._2

  /** Each node's lake's data path, as attached: the bundle root a record must name. */
  private def dataPathOf(base: Path): String = base.toRealPath().resolve("data").toString + "/"

  private val decider = new AuthzDecider(
    tenantOf = t => Option.when(t == "acme")(Tenant("acme")),
    tenantCatalogs = t => if t == "acme" then Set(Catalog) else Set.empty,
    poolCatalog = key =>
      Option
        .when(key == PoolA)(Map("dbName" -> Catalog))
        .flatMap(AuthzDecider.attachedCatalog(TenantDbKind.DuckLake.wireValue, _)),
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

  private val AllTables = EffectiveSet(
    RbacUser("u-mia", Some("acme"), "u-mia", "user"),
    List(RbacRole("r-all", "acme", "qod_all_tables")),
    Nil,
    List(RolePermission("p-all", "r-all", "*", "*", "*", "ALL")),
    Nil
  )
  private val Root = NodePrincipal("u-root", "root", None, superuser = true, system = false)

  /** The lake each node attaches as `acme_lake`: `pages` (100 rows, even ones say hello), and
    * `notes`, `docs` and `memo` (indexed, never built).
    */
  private def lakeInit(base: Path): String =
    Files.createDirectories(base.resolve("data"))
    val data = dataPathOf(base)
    val meta = base.toRealPath().resolve("meta.ducklake").toString
    s"""INSTALL ducklake;
       |LOAD ducklake;
       |ATTACH 'ducklake:$meta' AS $Catalog (DATA_PATH '$data');
       |CREATE TABLE $Catalog.main.pages (id BIGINT, title VARCHAR, body VARCHAR);
       |INSERT INTO $Catalog.main.pages SELECT i, 't' || i, CASE WHEN i % 2 = 0 THEN 'hello world ' || i ELSE 'goodbye ' || i END FROM range(100) r(i);
       |CREATE TABLE $Catalog.main.notes (id BIGINT, body VARCHAR);
       |INSERT INTO $Catalog.main.notes SELECT i, 'hello note ' || i FROM range(20) r(i);
       |CREATE TABLE $Catalog.main.docs (id BIGINT, body VARCHAR);
       |INSERT INTO $Catalog.main.docs SELECT i, 'hello doc ' || i FROM range(20) r(i);
       |CREATE TABLE $Catalog.main.memo (id BIGINT, body VARCHAR);
       |INSERT INTO $Catalog.main.memo SELECT i, 'hello memo ' || i FROM range(20) r(i);
       |""".stripMargin

  override def beforeAll(): Unit =
    if QodNodeTestBinary.path.nonEmpty then
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      keys = new NodeKeys(
        secret,
        s"http://127.0.0.1:${server.getAddress.getPort}",
        issued = Some(issued),
        minted = Some(minted),
        readOnlyCatalogs = ReadOnlyCatalogs.none,
        versions = PolicyVersions.zero
      )
      val records = new SearchRecordRoute(
        keys,
        issued,
        system,
        Map(
          ai.starlake.quack.edge.authz.NodeHandles.pool(PoolA) ->
            SearchRecordRoute.Scope(TdA.id, Catalog),
          // Tenant B's database is attached under the same alias: free text a tenant sets.
          ai.starlake.quack.edge.authz.NodeHandles.pool(PoolB) ->
            SearchRecordRoute.Scope("td-globex-lake", Catalog)
        ).get,
        counted
      )
      val decide = new DecideRoute(
        keys,
        minted,
        decider,
        new DryRunResults(),
        _ => "",
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
      dir = Files.createTempDirectory("qod-node-search-record")
      val env = Map("QOD_SEARCH_MAX_UNINDEXED_ROWS" -> "0")
      val a   = Files.createDirectories(dir.resolve("a"))
      val b   = Files.createDirectories(dir.resolve("b"))
      nodeA = LiveQodNode.start(
        a,
        lakeInit(a),
        nodeId = "quack-acme-lake-bi-1",
        keys = keys,
        env = env
      )
      nodeB = LiveQodNode.start(
        b,
        lakeInit(b),
        nodeId = "quack-globex-lake-bi-1",
        keys = keys,
        env = env
      )
      readTables()
      store.upsertTenant(Tenant("acme"))
      store.upsertTenantDb(TdA)
      val fields = List(
        SearchIndexField("title", pagesFields("title")),
        SearchIndexField("body", pagesFields("body"))
      )
      def ix(id: UUID, table: String, f: List[SearchIndexField], root: String) = SearchIndex(
        id,
        TdA.id,
        "main",
        table,
        tables(table)._1,
        f,
        root,
        SearchIndex.Ready,
        0L,
        "alice",
        Instant.now()
      )
      def body(table: String) = List(SearchIndexField("body", tables(table)._2("body")))
      List(
        ix(PagesIndex, "pages", fields, dataPathOf(a)),
        ix(UUID.randomUUID(), "notes", body("notes"), dataPathOf(a)),
        ix(UUID.randomUUID(), "docs", body("docs"), dataPathOf(a)),
        // A record whose bundle root is another catalog's data path (tenant B's lake): the node must
        // not use it for acme_lake (security review M1, M2).
        ix(UUID.randomUUID(), "memo", body("memo"), dataPathOf(b))
      ).foreach(i => indexes.create(i).unsafeRunSync().isRight shouldBe true)

  /** Table and field ids of node A's lake, read as the system principal. */
  private def readTables(): Unit =
    def ids(table: String): (Long, Map[String, Long]) =
      val id = systemLines(
        nodeA,
        s"SELECT table_id FROM __ducklake_metadata_$Catalog.ducklake_table " +
          s"WHERE table_name = '$table' AND end_snapshot IS NULL"
      ).head.toLong
      val cols = systemLines(
        nodeA,
        s"SELECT column_name, column_id FROM __ducklake_metadata_$Catalog.ducklake_column " +
          s"WHERE table_id = $id AND end_snapshot IS NULL"
      ).map { line =>
        val Array(n, c) = line.split(',')
        n -> c.toLong
      }.toMap
      (id, cols)
    tables = List("pages", "notes", "docs", "memo").map(t => t -> ids(t)).toMap

  override def afterAll(): Unit =
    try
      if nodeA != null then nodeA.stop()
      if nodeB != null then nodeB.stop()
      if server != null then server.stop(0)
      allocator.close()
    finally
      if dir != null then
        val tree = Files.walk(dir)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        catch
          case e: Exception =>
            System.err.println(s"[QodNodeSearchRecordSpec] afterAll: could not delete $dir: $e")
        finally tree.close()

  private def live(n: LiveQodNode): LiveQodNode =
    assume(n != null, "no qod-node binary (QOD_NODE_BIN or a local build)")
    n

  private def onPool(n: LiveQodNode, pool: PoolKey) = live(n).node.copy(poolKey = pool)

  private def poolOf(n: LiveQodNode): PoolKey = if n eq nodeB then PoolB else PoolA

  /** `sql` as `principal` straight on the node (no adapter: nothing recorded). */
  private def as(n: LiveQodNode, principal: NodePrincipal, sql: String) =
    LiveQodNode.lines(
      client
        .query(
          live(n).endpoint,
          n.token,
          sql,
          None,
          () => keys.prelude(onPool(n, poolOf(n)), principal)
        )
        .unsafeRunSync()
    )

  private def systemLines(n: LiveQodNode, sql: String): List[String] =
    as(n, NodePrincipal.System, sql).fold(e => fail(s"system read failed: $e"), identity)

  /** Built once the keys exist (beforeAll). */
  private lazy val recording = new QuackHttpAdapter(client, new NodeLoadTracker, keys, Some(system))

  /** `sql` as the system principal through `adapter` (the manager's own path to a node). */
  private def viaAdapter(
      adapter: QuackHttpAdapter,
      sql: String,
      searchSystemCall: Boolean
  ): Either[String, List[String]] =
    LiveQodNode.lines(
      adapter
        .send(
          onPool(nodeA, PoolA),
          sql,
          NodePrincipal.System,
          None,
          recordLoad = false,
          searchSystemCall = searchSystemCall
        )
        .unsafeRunSync()
    )

  private def decideCalls(n: LiveQodNode): Long =
    new QuackHttpAdapter(client, new NodeLoadTracker, keys, searchSystem = None)
      .authzStats(onPool(n, poolOf(n)))
      .unsafeRunSync()
      .map(_.decideCalls)
      .getOrElse(fail("the node's counters could not be read"))

  private def search(table: String, query: String = "hello"): String =
    s"SELECT count(*) FROM qod_search((SELECT rowid FROM $Catalog.main.$table), " +
      s"'$Catalog.main.$table', ['body'], '$query')"

  "a system search on an adapter that records no handle" should "get no record" in {
    live(nodeA)
    val unrecorded = new QuackHttpAdapter(client, new NodeLoadTracker, keys, searchSystem = None)
    viaAdapter(unrecorded, search("pages"), searchSystemCall = true) match
      case Left(m)  => m should include("search index record unavailable")
      case Right(r) => fail(s"a system search with an unrecorded handle answered: $r")
    system.size shouldBe 0L
  }

  it should "get no record when its call site did not flag it, whatever its text calls" in {
    // The recording adapter, but no flag: a system statement whose text calls qod_search is not
    // recorded (security review M3), so the route refuses its handle.
    viaAdapter(recording, search("pages"), searchSystemCall = false) match
      case Left(m)  => m should include("search index record unavailable")
      case Right(r) => fail(s"an unflagged system search answered: $r")
    system.size shouldBe 0L
  }

  "Task 6's build" should "build every bundle on records the route serves to its system handles" in {
    live(nodeA)
    val executor: CatalogPreviewHandlers.PreviewExecutor = (caller, key, sql) =>
      caller.system shouldBe true
      caller.searchRecords shouldBe true
      // As the routed executor passes it on (Main: caller.system && caller.searchRecords).
      new QuackHttpAdapter(client, new NodeLoadTracker, keys, Some(system))
        .send(
          onPool(nodeA, key),
          sql,
          NodePrincipal.System,
          None,
          recordLoad = false,
          searchSystemCall = caller.system && caller.searchRecords
        )
        .map {
          case QuackResponse.Ok(rows, ms, close) =>
            Right(QueryResult(rows, close, nodeA.nodeId, ms))
          case QuackResponse.Failed(QuackError.Permanent(m), _) =>
            Left(RouterFailure.BadRequest(m))
          case QuackResponse.Failed(QuackError.Transient(m, _), _) =>
            Left(RouterFailure.Unavailable(m))
        }
    val catalog = new SearchCatalog:
      def liveTableColumns(td: TenantDb, schema: String, table: String) =
        IO.pure(
          Option.when(schema == "main" && table == "pages")(
            LiveTable(
              pagesId,
              "main",
              "pages",
              List(
                ("id", pagesFields("id"), "bigint"),
                ("title", pagesFields("title"), "varchar"),
                ("body", pagesFields("body"), "varchar")
              )
            )
          )
        )
      def liveTableName(td: TenantDb, tableId: Long) =
        IO.pure(Option.when(tableId == pagesId)(("main", "pages")))
    val handlers = new SearchHandlers(
      service = new SearchIndexService(
        tenantDbOf = (t, d) => Option.when(t == "acme" && d == TdA.name)(TdA),
        tenantDbsOf = _ => List(TdA),
        metastoreOf = _ => Map("dataPath" -> dir.resolve("a/data").toString),
        catalog = catalog,
        store = indexes
      ),
      store = indexes,
      poolExists = _ == PoolA,
      aliasOf = _ => Catalog,
      executor = executor,
      audit = new AuditRecorder(new RecordingTelemetryStore, _ => None),
      callerOf = RestCaller(patOf =
        _ =>
          Some(
            PatPrincipal(
              RbacUser("u-root", None, "root", "admin"),
              "pat-1",
              SessionScope.Superuser,
              isAdmin = true,
              TokenRestriction.Unrestricted
            )
          )
      ),
      databaseRefusal = (_, _, _) => None
    )
    val resp = handlers
      .build(SearchIndexBuildRequest("acme", "lake", "pages", "bi"), Some("tok"))(_ =>
        SessionScope.Superuser
      )
      .unsafeRunSync()
      .fold(e => fail(s"the build was refused: $e"), identity)
    resp.files should not be empty
    resp.files.map(_.docs).sum shouldBe 100L
    // One segment row per bundle, each recorded under the index.
    store.listSearchSegments(PagesIndex).size shouldBe resp.files.size
    // Every build statement's handle was recorded for the route, and only the system's.
    system.size should be > 0L
  }

  "a flagged system search through the recording adapter" should "take its record from the route" in {
    // `notes` was never fetched before: its record comes through the recorded system handle.
    val finds = counted.finds.get()
    viaAdapter(recording, search("notes"), searchSystemCall = true) match
      case Left(m)  => m should include("search_index_behind")
      case Right(r) => fail(s"a search with no bundle answered: $r")
    counted.finds.get() shouldBe finds + 1
    viaAdapter(recording, search("pages"), searchSystemCall = true) shouldBe Right(List("10"))
  }

  "a superuser's search" should "answer from the bundles with no decide call" in {
    val before = decideCalls(nodeA)
    as(nodeA, Root, search("pages")) shouldBe Right(List("10"))
    val after = decideCalls(nodeA)
    info(s"decide_calls before $before, after $after")
    after shouldBe before
  }

  it should "find the record of an index never built, and no bundle (the cap is 0)" in {
    val before = decideCalls(nodeA)
    as(nodeA, Root, search("notes")) match
      case Left(m)  => m should include("search_index_behind")
      case Right(r) => fail(s"a search with no bundle answered: $r")
    decideCalls(nodeA) shouldBe before
  }

  "a tenant user's search" should "take its record from the route, its verdict the one decide call" in {
    // `docs` was never fetched before: its record comes through the tenant user's own handle.
    val principal = NodePrincipal.of(Some(AllTables), PoolA, DecidePurpose.Live)
    val before    = decideCalls(nodeA)
    val finds     = counted.finds.get()
    as(nodeA, principal, search("docs")) match
      case Left(m)  => m should include("search_index_behind")
      case Right(r) => fail(s"a search with no bundle answered: $r")
    val after = decideCalls(nodeA)
    info(s"decide_calls before $before, after $after; route lookups ${counted.finds.get() - finds}")
    counted.finds.get() shouldBe finds + 1
    after shouldBe before + 1
  }

  it should "answer from the bundles" in {
    val principal = NodePrincipal.of(Some(AllTables), PoolA, DecidePurpose.Live)
    val before    = decideCalls(nodeA)
    as(nodeA, principal, search("pages")) shouldBe Right(List("10"))
    decideCalls(nodeA) shouldBe before + 1
  }

  "a record whose bundle root is another catalog's data path" should "not be used, nor kept" in {
    val finds = counted.finds.get()
    List(1, 2).foreach { round =>
      as(nodeA, Root, search("memo")) match
        case Left(m)  => withClue(s"round $round")(m should include("search_index_unavailable"))
        case Right(r) => fail(s"a record of another data path was used: $r")
      // Fetched again each time: the node kept nothing for it.
      counted.finds.get() shouldBe finds + round
    }
  }

  "a node of tenant B's pool" should "get acme's table in missing, under the same alias" in {
    as(nodeB, Root, search("pages")) match
      case Left(m)  => m should include("search_index_unavailable")
      case Right(r) => fail(s"tenant B's node searched with tenant A's record: $r")
  }
