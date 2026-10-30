package ai.starlake.quack.it.authz

import ai.starlake.quack.edge.adapter.{Connection, QuackProtocol}
import ai.starlake.quack.edge.authz.{NodeKeys, NodePrincipal, ReadOnlyCatalogs}
import ai.starlake.quack.ondemand.runtime.{
  LiveExtensionCache,
  LiveQodNode,
  QodNodeTestBinary,
  StubDecideServer
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.IORuntime
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.util.Try

/** Spike item 8 at the node: what qod-node adds to a statement for its verdict, on a cache hit and
  * on a miss decided by a same-host endpoint (Task 10's [[StubDecideServer]], which signs an allow
  * for whatever it is asked). Measured on a plain node (the memory catalog) and on a node with a
  * DuckLake-on-Postgres catalog attached and every statement under the router's `USE` prelude, the
  * managed pool's shape. The baseline is the system principal on its own connection: it is
  * described like everyone (the local refusals) but skips the parse screen's content rules, the
  * call-name walk (CONTRACT Addendum 4 item 32 B) and the decision. Opt-in: `QOD_AUTHZ_LATENCY=1`.
  */
class AuthzNodeLatencySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private given IORuntime = IORuntime.global

  private val enabled = sys.env.get("QOD_AUTHZ_LATENCY").contains("1")
  // QOD_AUTHZ_LATENCY_ROUNDS shortens a trial run (1 to Latency.MaxRounds); the numbers of record use
  // the default.
  private val Rounds = Latency.rounds(sys.env.get("QOD_AUTHZ_LATENCY_ROUNDS"))
  private val Warmup = math.min(200, Rounds)
  private val User   =
    NodePrincipal("u-lat", "lat", Some("acme"), superuser = false, system = false)
  private val run       = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  private val LakeDb    = s"qod_authz_node_latency_test_$run"
  private val allocator = new RootAllocator()
  private val secret    = NodeKeys.randomSecret()

  /** A plan verdict lives 60 s (the stub's, as the manager's `GrantLifetimeMs`). */
  private val VerdictLifetimeMs = 60000L

  private var stub: StubDecideServer = null
  private var root: Path             = null
  private var plain: LiveQodNode     = null
  private var lake: LiveQodNode      = null
  private var lakeCreated            = false

  /** The table and the view chain every node serves: a view over a view calling a built-in macro
    * (`fdiv`), which the call-name walk reaches through both definitions.
    */
  private def objects(at: String): String =
    s"""${Latency.wideTable(s"${at}w")}
       |CREATE VIEW ${at}v1 AS SELECT * FROM ${at}w;
       |CREATE VIEW ${at}v2 AS SELECT c0, fdiv(c1, 2) AS h FROM ${at}v1;
       |""".stripMargin

  override def beforeAll(): Unit =
    if enabled && QodNodeTestBinary.path.nonEmpty then
      root = Files.createTempDirectory("qod-authz-latency").toRealPath()
      stub = StubDecideServer.start(secret)
      val home = LiveExtensionCache.seed(Files.createDirectories(root.resolve("home")))
      plain = LiveQodNode.start(
        Files.createDirectories(root.resolve("plain")),
        // httpfs as the spawn scripts install it (quack_serve's crypto needs it; autoloaded).
        "INSTALL httpfs;\n" + objects(""),
        nodeId = "quack-latency-plain",
        keys = new NodeKeys(secret, stub.url, readOnlyCatalogs = ReadOnlyCatalogs.none),
        home = Some(home)
      )
      if TestPostgres.reachable then
        TestPostgres.dropStrayTestDatabases("qod_authz_node_latency")
        createDatabase(LakeDb)
        lakeCreated = true
        val pw = TestPostgres.pgPass.replace("'", "''")
        val pg =
          s"host=${TestPostgres.pgHost} port=${TestPostgres.pgPort} dbname=$LakeDb " +
            s"user=${TestPostgres.pgUser}"
        // The spawn script's DuckLake attach (the password on a temporary secret), then the objects.
        lake = LiveQodNode.start(
          Files.createDirectories(root.resolve("lake")),
          s"""INSTALL httpfs;
             |INSTALL ducklake;
             |LOAD ducklake;
             |INSTALL postgres;
             |LOAD postgres;
             |CREATE OR REPLACE TEMPORARY SECRET qod_catalog_pg (TYPE postgres, PASSWORD '$pw');
             |ATTACH 'ducklake:postgres:$pg' AS lake
             |  (DATA_PATH '$root/lake_data/', METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'});
             |DROP SECRET IF EXISTS qod_catalog_pg;
             |USE lake.main;
             |${objects("lake.main.")}""".stripMargin,
          nodeId = "quack-latency-lake",
          keys = new NodeKeys(
            secret,
            stub.url,
            paths = _ => "lake.main",
            readOnlyCatalogs = ReadOnlyCatalogs.none
          ),
          home = Some(home)
        )

  override def afterAll(): Unit =
    try
      Try(if plain != null then plain.stop())
      Try(if lake != null then lake.stop())
      Try(if stub != null then stub.stop())
      if lakeCreated then Try(TestPostgres.dropDatabase(LakeDb))
      allocator.close()
    finally
      if root != null then
        val tree = Files.walk(root)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()

  private def createDatabase(name: String): Unit =
    val c = java.sql.DriverManager
      .getConnection(TestPostgres.adminUrl, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val st = c.createStatement()
      try st.execute(s"""CREATE DATABASE "$name"""")
      finally st.close()
    finally c.close()

  /** `body`, a failure carrying the end of `live`'s log (an init statement that failed is there).
    */
  private def withLog[A](live: LiveQodNode)(body: => A): A =
    try body
    catch
      case e: Exception =>
        throw new AssertionError(s"${e.getMessage}\nnode log:\n${live.log.takeRight(4000)}", e)

  /** One arm's samples (nanoseconds), recorded after the warm-up. */
  private final class Arm(val name: String):
    val samples = new Array[Long](Rounds)

  /** Measures every arm on `live`, interleaved round by round, and returns the targets it missed.
    * `use` prefixes every statement (the router's `USE` prelude on a managed pool); `keys` are the
    * node's, whose handles name the stub by IP, and the same keys naming it `localhost` time the
    * name resolution of the decision call (hand-off T7-M5).
    */
  private def measure(shape: String, live: LiveQodNode, use: String, keys: NodeKeys): List[String] =
    val byName = new NodeKeys(
      secret,
      stub.url.replace("127.0.0.1", "localhost"),
      paths = _ => if use.isEmpty then "" else "lake.main",
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
    val protocol = new QuackProtocol(
      new QuackProtocol.JdkHttpTransport(java.net.http.HttpClient.newHttpClient()),
      allocator
    )
    // One principal per connection (qod_session): the system's, the user's naming the stub by IP,
    // and the user's naming it localhost.
    val system = protocol.open(live.endpoint, live.token).unsafeRunSync()
    val user   = protocol.open(live.endpoint, live.token).unsafeRunSync()
    val named  = protocol.open(live.endpoint, live.token).unsafeRunSync()
    try
      // One statement on a connection: a fresh handle of its principal (minted before the clock
      // starts: the manager's cost, not the node's), then the SQL. Nanoseconds, and the plan
      // decisions the stub was asked meanwhile.
      def timed(conn: Connection, k: NodeKeys, who: NodePrincipal, sql: String): (Long, Long) =
        val prelude = k.prelude(live.node, who)
        val before  = stub.planCalls
        val t0      = System.nanoTime()
        conn.executeDiscard(prelude).unsafeRunSync()
        conn.executeDiscard(sql).unsafeRunSync()
        (System.nanoTime() - t0, stub.planCalls - before)

      val last          = Latency.subsets.size - 1
      val fixed         = use + Latency.counting("w", last)
      val view          = use + "SELECT count(h), count(c0) FROM v2"
      def fresh(n: Int) = use + Latency.counting("w", n)
      // The two miss arms never share a subset: the cache is the node's, whoever names the replica.
      val ByNameOffset = Warmup + Rounds

      val base       = new Arm(s"$shape baseline (system)")
      val hit        = new Arm(s"$shape hit")
      val miss       = new Arm(s"$shape miss (replica by IP)")
      val repeat     = new Arm(s"$shape 2nd send (by IP)")
      val missName   = new Arm(s"$shape miss (replica by localhost)")
      val repeatName = new Arm(s"$shape 2nd send (by localhost)")
      val viewBase   = new Arm(s"$shape view chain baseline (system)")
      val viewHit    = new Arm(s"$shape view chain hit")

      var unasked   = 0  // a miss the stub was not asked for
      var extra     = 0L // decisions beyond one per miss: first sights and expired verdicts
      var systemAsk = 0L // decisions for the system principal (must stay 0)
      def record(arm: Arm, r: Int, at: (Long, Long), isMiss: Boolean): Unit =
        val (t, calls) = at
        if r >= 0 then arm.samples(r) = t
        if isMiss && calls < 1 then unasked += 1
        extra += calls - (if isMiss then 1 else 0)

      val started = System.currentTimeMillis()
      (0 until Warmup + Rounds).foreach { j =>
        val r = j - Warmup
        val s = timed(system, keys, NodePrincipal.System, fixed)
        systemAsk += s._2
        if r >= 0 then base.samples(r) = s._1
        record(hit, r, timed(user, keys, User, fixed), isMiss = false)
        // The quack client sends each query twice: a miss, then a hit on the verdict just cached.
        record(miss, r, timed(user, keys, User, fresh(j)), isMiss = true)
        record(repeat, r, timed(user, keys, User, fresh(j)), isMiss = false)
        record(missName, r, timed(named, byName, User, fresh(ByNameOffset + j)), isMiss = true)
        record(repeatName, r, timed(named, byName, User, fresh(ByNameOffset + j)), isMiss = false)
        val v = timed(system, keys, NodePrincipal.System, view)
        systemAsk += v._2
        if r >= 0 then viewBase.samples(r) = v._1
        record(viewHit, r, timed(user, keys, User, view), isMiss = false)
      }
      val elapsedMs = System.currentTimeMillis() - started

      List(base, hit, miss, repeat, missName, repeatName, viewBase, viewHit)
        .foreach(a => println(Latency.line(a.name, a.samples)))
      println(Latency.added(s"$shape hit", hit.samples, base.samples))
      println(Latency.added(s"$shape 2nd send (IP)", repeat.samples, base.samples))
      println(Latency.added(s"$shape miss (IP)", miss.samples, base.samples))
      println(Latency.added(s"$shape miss (localhost)", missName.samples, base.samples))
      println(Latency.added(s"$shape 2nd send (localhost)", repeatName.samples, base.samples))
      println(Latency.added(s"$shape view chain hit", viewHit.samples, viewBase.samples))
      println(Latency.added(s"$shape localhost over IP", missName.samples, miss.samples))
      // The statements the plan cache holds a verdict for across rounds: the fixed one, the view
      // chain and, on a managed pool, the USE prelude. Each is asked the first time it is seen and
      // again whenever its verdict expires.
      val cached = if use.isEmpty then 2 else 3
      println(
        f"[authz-latency] $shape: ${elapsedMs / 1000.0}%.1f s, ${Rounds + Warmup} rounds, " +
          s"$extra decision(s) beyond one per miss (first sight of the repeated statements, " +
          "then one per expired verdict)"
      )

      val missed = List.newBuilder[String]
      if systemAsk != 0 then missed += s"$shape: the system principal asked $systemAsk decision(s)"
      if unasked != 0 then missed += s"$shape: $unasked miss(es) were not decided by the stub"
      val refreshCeiling = cached * (elapsedMs / VerdictLifetimeMs + 1)
      if extra > refreshCeiling then
        missed += s"$shape: $extra extra decisions, more than $refreshCeiling expiry refreshes"
      def over(label: String, arm: Arm, b: Arm, ceilingMs: Double): Unit =
        val added = Latency.percentile(arm.samples, 99) - Latency.percentile(b.samples, 99)
        if added >= ceilingMs then
          missed += f"$shape $label adds $added%.3f ms at p99 (target under $ceilingMs ms)"
      over("hit", hit, base, 1.0)
      over("2nd send", repeat, base, 1.0)
      over("miss", miss, base, 5.0)
      missed.result()
    finally
      system.close().unsafeRunSync()
      user.close().unsafeRunSync()
      named.close().unsafeRunSync()

  "a verdict on a plain node" should "add under 1 ms on a hit and under 5 ms on a miss, at p99" in {
    assume(enabled, "latency measurement is opt-in: QOD_AUTHZ_LATENCY=1")
    assume(plain != null, "no qod-node binary (QOD_NODE_BIN or a local build)")
    withLog(plain)(measure("plain", plain, "", plain.keys)) shouldBe empty
  }

  "a verdict on a DuckLake-on-Postgres node" should "add under 1 ms on a hit and under 5 ms on " +
    "a miss, at p99" in {
      assume(enabled, "latency measurement is opt-in: QOD_AUTHZ_LATENCY=1")
      assume(lake != null, "needs a qod-node build and the test Postgres")
      withLog(lake)(measure("ducklake", lake, "USE lake.main; ", lake.keys)) shouldBe empty
    }
