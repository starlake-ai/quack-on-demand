package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{
  AuthzDecider,
  DecideRoute,
  MintedHandles,
  NodeKeys,
  NodePrincipal
}
import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.edge.FlightSqlRouter
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolState
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacRole, RbacUser, RolePermission}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
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
  * without a qod-node build.
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
        paths = key => pools.get(key).flatMap(FlightSqlRouter.usePath).getOrElse("")
      )
      val app = new DecideRoute(keys, minted, decider).routes.orNotFound
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
    val principal = NodePrincipal.of(Some(who), pool)
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
