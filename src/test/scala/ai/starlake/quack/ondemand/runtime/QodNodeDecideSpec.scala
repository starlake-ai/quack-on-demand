package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{
  AuthzDecider,
  DecidePurpose,
  DecideRoute,
  DryRunResults,
  MintedHandles,
  NodeKeys,
  NodePrincipal,
  ReadOnlyCatalogs
}
import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.edge.FlightSqlRouter
import ai.starlake.quack.edge.SessionRegistry
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolState
import ai.starlake.quack.observability.metrics.{AuthzInstruments, MetricsBindings, OpaInstruments}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacRole, RbacUser, RolePermission}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.arrow.memory.RootAllocator
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** A real qod-node decided by the manager's own decision route and pipeline ([[DecideRoute]] over
  * an [[AuthzDecider]] wired like BootFactories.authzDecider: the pool's attached catalog from
  * [[AuthzDecider.attachedCatalog]]). Tenant users holding the built-in `qod_all_tables` (`*.*.*
  * ALL`) read a table of a kind=memory database (catalog `memory`) and of a duckdb-file database
  * attached under a custom catalogAlias, each on its own pool (the Task 6 review's I1). Cancelled
  * without a qod-node build. It also pins the node's authorization counters as the health probe
  * reads and exports them (decision 26).
  */
class QodNodeDecideSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val allocator          = new RootAllocator()
  private val client             = new QuackHttpClient(allocator)
  private var dir: Path          = null
  private var live: LiveQodNode  = null
  private var server: HttpServer = null

  private val MemoryPool = PoolKey("acme", "acme_mem", "bi")
  private val FilePool   = PoolKey("acme", "acme_file", "bi")
  private val Alias      = "custom_alias"

  /** Each pool as the supervisor holds it: its kind and effective metastore. A kind=memory database
    * has an empty metastore, which the supervisor's effective metastore names `memory`
    * (PoolSupervisor.effectiveMetastoreFor); the duckdb-file one is attached under a custom alias.
    */
  private val pools = Map(
    MemoryPool -> PoolState(
      MemoryPool,
      Nil,
      RoleDistribution(0, 0, 1),
      Map("dbName" -> "memory"),
      Map.empty,
      kindWire = TenantDbKind.InMemory.wireValue
    ),
    FilePool -> PoolState(
      FilePool,
      Nil,
      RoleDistribution(0, 0, 1),
      Map("dbName" -> "acme_file", "schemaName" -> "main", "catalogAlias" -> Alias),
      Map.empty,
      kindWire = TenantDbKind.DuckDbFile.wireValue
    )
  )

  /** The router's USE prelude for `pool` (FlightSqlRouter.wrapWithDefaultSchema), then `sql`. */
  private def routed(pool: PoolKey, sql: String): String =
    FlightSqlRouter.usePath(pools(pool)).fold(sql)(path => s"USE $path; $sql")

  private val secret         = NodeKeys.randomSecret()
  private val minted         = new MintedHandles()
  private var keys: NodeKeys = null

  private val decider = new AuthzDecider(
    tenantOf = t => Option.when(t == "acme")(Tenant("acme")),
    tenantCatalogs = t => if t == "acme" then Set("acme_mem", "acme_file") else Set.empty,
    poolCatalog = key =>
      pools.get(key).flatMap(st => AuthzDecider.attachedCatalog(st.kindWire, st.metastore)),
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

  /** A tenant user of acme holding `role`'s one permission. */
  private def user(id: String, role: String, permission: RolePermission): EffectiveSet =
    EffectiveSet(
      RbacUser(id, Some("acme"), id, "user"),
      List(RbacRole(permission.roleId, "acme", role)),
      Nil,
      List(permission),
      Nil
    )

  private val AllTables =
    user("u-mia", "qod_all_tables", RolePermission("p-all", "r-all", "*", "*", "*", "ALL"))
  private val OtherDb =
    user("u-ned", "elsewhere", RolePermission("p-else", "r-else", "acme_other", "*", "*", "ALL"))

  override def beforeAll(): Unit =
    if QodNodeTestBinary.path.nonEmpty then
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      keys = new NodeKeys(
        secret,
        s"http://127.0.0.1:${server.getAddress.getPort}",
        minted = Some(minted),
        paths = key => pools.get(key).flatMap(FlightSqlRouter.usePath).getOrElse(""),
        readOnlyCatalogs = ReadOnlyCatalogs.none
      )
      val app = new DecideRoute(
        keys,
        minted,
        decider,
        new DryRunResults(),
        key => pools.get(key).flatMap(FlightSqlRouter.usePath).getOrElse(""),
        AuthzInstruments.noop
      ).routes.orNotFound
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
      dir = Files.createTempDirectory("qod-node-decide")
      val file = dir.toRealPath().resolve("acme_file.duckdb").toString
      live = LiveQodNode.start(
        dir,
        s"""CREATE TABLE memory.main.t AS SELECT 1 AS v;
           |ATTACH '$file' AS $Alias;
           |CREATE TABLE $Alias.main.t AS SELECT 2 AS v;
           |""".stripMargin,
        keys = keys
      )

  override def afterAll(): Unit =
    try
      if live != null then live.stop()
      if server != null then server.stop(0)
      allocator.close()
    finally
      if dir != null then
        val tree = Files.walk(dir)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        catch
          case e: Exception =>
            System.err.println(s"[QodNodeDecideSpec] afterAll: could not fully delete $dir: $e")
        finally tree.close()

  private def node: LiveQodNode =
    assume(live != null, "no qod-node binary (QOD_NODE_BIN or a local build)")
    live

  /** `sql` as `who` on `pool`'s node (this one node serves both pools' handles), as the router
    * sends it: its USE prelude, then the statement.
    */
  private def as(who: EffectiveSet, pool: PoolKey, sql: String): Either[String, List[String]] =
    val principal = NodePrincipal.of(Some(who), pool, DecidePurpose.Live)
    val onPool    = node.node.copy(poolKey = pool)
    LiveQodNode.lines(
      client
        .query(
          node.endpoint,
          node.token,
          routed(pool, sql),
          None,
          () => keys.prelude(onPool, principal)
        )
        .unsafeRunSync()
    )

  "the router" should "send the USE prelude a real pool of each kind gets" in {
    routed(MemoryPool, "SELECT 1") shouldBe "USE memory.main; SELECT 1"
    routed(FilePool, "SELECT 1") shouldBe s"USE $Alias.main; SELECT 1"
  }

  "a tenant user with qod_all_tables" should "read a table of a kind=memory database" in {
    as(AllTables, MemoryPool, "SELECT v FROM t") shouldBe Right(List("1"))
  }

  it should "read a table of a duckdb-file database attached under a custom catalogAlias" in {
    as(AllTables, FilePool, "SELECT v FROM t") shouldBe Right(List("2"))
  }

  it should "not read another pool's catalog: a wildcard catalog grant covers its own pool's" in {
    as(AllTables, MemoryPool, s"SELECT v FROM $Alias.main.t") shouldBe
      Left(s"Permission Error: QoD: user:u-mia lacks grants on $Alias.main.t:read")
  }

  "a tenant user without a grant on the database" should "be refused by the decision pipeline" in {
    as(OtherDb, MemoryPool, "SELECT v FROM t") shouldBe
      Left("Permission Error: QoD: user:u-ned lacks grants on memory.main.t:read")
  }

  /** `sql` as `principal` on the memory pool's node, without the router's USE prelude. */
  private def raw(principal: NodePrincipal, sql: String): Either[String, List[String]] =
    val onPool = node.node.copy(poolKey = MemoryPool)
    LiveQodNode.lines(
      client
        .query(node.endpoint, node.token, sql, None, () => keys.prelude(onPool, principal))
        .unsafeRunSync()
    )

  private val Root = NodePrincipal("u-root", "root", None, superuser = true, system = false)

  "qod_authz_stats" should "be read by the system principal only" in {
    raw(NodePrincipal.System, NodeAuthzStats.sql) match
      case Right(List(row)) => row.split(',').length shouldBe NodeAuthzStats.Counters.size
      case other            => fail(s"the system principal's read: $other")
    // A tenant user: the node's parse screen refuses a superuser-class table function first, with
    // its own text.
    raw(NodePrincipal.of(Some(AllTables), MemoryPool, DecidePurpose.Live), NodeAuthzStats.sql) match
      case Left(m) =>
        m should include("QoD: table function qod_authz_stats is not available to non-superusers")
      case Right(r) => fail(s"a tenant user read the counters: $r")
    // A superuser passes the screen and every class: the function itself refuses.
    raw(Root, NodeAuthzStats.sql) match
      case Left(m)  => m should include("QoD: function qod_authz_stats is superuser-only")
      case Right(r) => fail(s"a superuser read the counters: $r")
  }

  "the health probe" should "export plan cache hits after two identical user statements" in {
    as(AllTables, MemoryPool, "SELECT v FROM t") shouldBe Right(List("1"))
    as(AllTables, MemoryPool, "SELECT v FROM t") shouldBe Right(List("1"))
    // As Main's health loop: the probe (statement, then canary), then on a healthy verdict the
    // counters' read, then the export on scrape.
    val adapter = new QuackHttpAdapter(client, new NodeLoadTracker, keys)
    adapter.probe(node.node).unsafeRunSync() shouldBe ProbeVerdict.Ok
    val reading = adapter.authzStats(node.node).unsafeRunSync()
    reading.map(_.planCacheHits).getOrElse(0L) should be > 0L
    reading.map(_.decideCalls).getOrElse(0L) should be > 0L
    val stats = new NodeAuthzStatsTracker
    reading.foreach(stats.update(node.node.nodeId, _))
    val registry = new SimpleMeterRegistry
    val onPool   = node.node.copy(poolKey = MemoryPool)
    new MetricsBindings(
      registry,
      new NodeLoadTracker,
      new SessionRegistry,
      () =>
        List(PoolState(MemoryPool, List(onPool), RoleDistribution(0, 0, 1), Map.empty, Map.empty)),
      new EngineStatsTracker,
      stats
    ).refresh()
    registry
      .find("qod_node_authz_plan_cache_hits_total")
      .tags("tenant", "acme", "pool", "bi", "node", onPool.nodeId)
      .functionCounter()
      .count() should be > 0.0
  }
