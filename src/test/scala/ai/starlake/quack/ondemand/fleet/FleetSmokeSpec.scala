package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.{FlightConfig, Main, ManagerConfig, QuackNativeConfig}
import ai.starlake.quack.edge.config.{AclConfig, AuthenticationConfig, NodeLockdownConfig}
import ai.starlake.quack.observability.metrics.MetricsConfig
import ai.starlake.quack.observability.metrics.MetricsConfigCodec.given
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.{ExitCode, FiberIO, IO}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.apache.arrow.flight.sql.FlightSqlClient
import org.apache.arrow.flight.{FlightCallHeaders, FlightClient, HeaderCallOption, Location}
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.Base64
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

/** End-to-end smoke of the fleet backend: a real manager booted in-process with
  * `runtimeType = fleet`, a real `qod agent` (run from the CLI checkout through `uv run`), a real
  * DuckDB node spawned by that agent, and a real FlightSQL statement routed to it.
  *
  * Steps (each one timed and printed):
  *   1. boot the manager on 20950 (REST) / 31350 (FlightSQL, plain gRPC), native front door off,
  *      against a throwaway control-plane database;
  *   2. create tenant acme, a memory database, pool bi of size 1: the pool is pending (no server);
  *   3. start the agent (server s1, node port 23100);
  *   4. the pending slot fills: one node, served by s1, s1 reachable and running;
  *   5. `SELECT 42` through the FlightSQL edge;
  *   6. drain s1: the slot goes pending, s1 keeps no assignment;
  *   7. undrain s1: the node comes back on s1;
  *   8. SIGKILL the agent (the node survives it), restart the agent: it reaps the orphan through
  *      its pidfile and the node comes back on s1, capacity reported;
  *   9. SIGTERM the agent: its node stops; after the reassign window the node row is gone and the
  *      slot is pending again;
  *   10. teardown (afterAll, also on failure): agent and node processes, manager fiber, database.
  *
  * Cancelled (not failed) when `duckdb` or `uv` is not on PATH, when the CLI checkout has no `cli/`
  * directory, or when the test Postgres (SL_TEST_PG_* envs) is unreachable. Never touches the
  * default ports 20900 / 31338 / 9494. Every wait is bounded (60 s ceilings).
  */
class FleetSmokeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:
  import Main.given

  private val RestPort   = 20950
  private val FlightPort = 31350
  private val NodePort   = 23100
  private val ApiKey     = "smoke-key"
  private val JoinToken  = "smoke"
  private val Server     = "s1"
  private val Tenant     = "acme"
  private val TenantDb   = "acme_db"
  private val Pool       = "bi"
  private val Ceiling    = 60.seconds

  private val DbPrefix = "qod_fleetsmoke"
  private val dbName   = s"${DbPrefix}_test_${System.nanoTime().toHexString}"

  private def onPath(bin: String): Boolean =
    Try(Process(Seq("which", bin)).!(ProcessLogger(_ => ()))).getOrElse(1) == 0

  private val cliDir = new java.io.File("cli")

  /** Why the spec cannot run here, or None when every prerequisite is present. */
  private lazy val missing: Option[String] =
    if !onPath("duckdb") then Some("duckdb not on PATH")
    else if !onPath("uv") then Some("uv not on PATH")
    else if !cliDir.isDirectory then Some("no cli/ directory next to the build")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else None

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

  private var stateDir: Path                                             = null
  private var manager: FiberIO[Either[Throwable, ExitCode]]              = null
  @volatile private var bootOutcome: Option[Either[Throwable, ExitCode]] = None
  private var agent: Option[Process]                                     = None
  private var agentUv: Option[ProcessHandle]                             = None
  // Every node pid the agents recorded, so teardown can kill their process groups.
  private val nodePids = scala.collection.mutable.Set.empty[Long]
  // Set by the first failing step: later steps cancel instead of piling up follow-on failures.
  @volatile private var broken: Option[String] = None

  // ---------------------------------------------------------------- steps

  private def step(name: String)(body: => Unit): Unit =
    missing.foreach(reason => cancel(s"fleet smoke skipped: $reason"))
    broken.foreach(b => cancel(s"skipped: earlier step failed ($b)"))
    val t0 = System.nanoTime()
    try body
    catch
      case t: org.scalatest.exceptions.TestCanceledException => throw t
      case t: Throwable                                      =>
        broken = Some(name)
        throw t
    finally
      val ms = (System.nanoTime() - t0) / 1_000_000
      println(f"[fleet-smoke] $name%-40s ${ms}%6d ms")

  /** Polls `probe` until it returns Some, or fails naming `what` after `ceiling`. */
  private def await[A](what: String, ceiling: FiniteDuration = Ceiling)(probe: => Option[A]): A =
    val deadline        = System.nanoTime() + ceiling.toNanos
    var last: Option[A] = None
    var lastErr: String = ""
    while last.isEmpty && System.nanoTime() < deadline do
      bootOutcome.foreach(o => fail(s"manager exited while waiting for $what: $o"))
      last =
        try probe
        catch
          case t: Throwable =>
            lastErr = s" (last error: ${t.getMessage})"
            None
      if last.isEmpty then Thread.sleep(500)
    last.getOrElse(fail(s"timed out after $ceiling waiting for $what$lastErr"))

  // ---------------------------------------------------------------- REST

  private def request(method: String, path: String, body: Option[String]): (Int, String) =
    val b = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$RestPort$path"))
      .timeout(Duration.ofSeconds(20))
      .header("X-API-Key", ApiKey)
      .header("Content-Type", "application/json")
    val req = body match
      case Some(json) => b.method(method, HttpRequest.BodyPublishers.ofString(json)).build()
      case None       => b.method(method, HttpRequest.BodyPublishers.noBody()).build()
    val r = http.send(req, HttpResponse.BodyHandlers.ofString())
    (r.statusCode(), r.body())

  private def post(path: String, json: String): Json =
    val (code, body) = request("POST", path, Some(json))
    withClue(s"POST $path -> $code $body: ")(code should (be >= 200 and be < 300))
    if body.isBlank then Json.Null else parse(body).fold(e => fail(e.getMessage), identity)

  private def get(path: String): Json =
    val (code, body) = request("GET", path, None)
    withClue(s"GET $path -> $code $body: ")(code shouldBe 200)
    parse(body).fold(e => fail(e.getMessage), identity)

  private final case class PoolView(nodes: List[Json], pending: Int, reason: Option[String])

  private def pool(): PoolView =
    val p = get("/api/pool/list").hcursor
      .downField("pools")
      .values
      .getOrElse(Nil)
      .find(j => j.hcursor.get[String]("pool").contains(Pool))
      .getOrElse(fail(s"pool $Pool missing from pool/list"))
    val c = p.hcursor
    PoolView(
      c.downField("nodes").values.map(_.toList).getOrElse(Nil),
      c.get[Int]("pending").getOrElse(0),
      c.get[Option[String]]("pendingReason").toOption.flatten
    )

  private def server(): Option[Json] =
    get("/api/fleet/servers").hcursor
      .downField("servers")
      .values
      .getOrElse(Nil)
      .find(j => j.hcursor.get[String]("name").contains(Server))

  private def str(j: Json, field: String): Option[String] =
    j.hcursor.get[Option[String]](field).toOption.flatten

  // ---------------------------------------------------------------- agent

  private def startAgent(): Unit =
    val cmd = Seq(
      "uv",
      "run",
      "qod",
      "agent",
      "--manager",
      s"http://127.0.0.1:$RestPort",
      "--join-token",
      JoinToken,
      "--name",
      Server,
      "--advertise-host",
      "127.0.0.1",
      "--node-port",
      NodePort.toString,
      "--duckdb-bin",
      Process(Seq("which", "duckdb")).!!.trim,
      "--state-dir",
      stateDir.toString,
      "--insecure"
    )
    // VIRTUAL_ENV from the caller's shell would make uv warn and could point it elsewhere.
    val before = uvChildren()
    val p      = Process(cmd, cliDir, "VIRTUAL_ENV" -> "")
      .run(ProcessLogger(l => println(s"[agent] $l"), l => println(s"[agent] $l")))
    agent = Some(p)
    // The scala Process API exposes no pid: find the new `uv` child of this JVM.
    agentUv = Some(await("the agent's uv launcher", 10.seconds) {
      (uvChildren() -- before).headOption.flatMap(pid => ProcessHandle.of(pid).toScala)
    })
    // And its python child, the agent proper, which is what the signals must reach.
    await("the agent process under uv", 30.seconds)(agentHandles().headOption)

  private def pidfile: Path = stateDir.resolve("node.pid")

  private def recordNodePid(): Option[Long] =
    Try(Files.readString(pidfile).trim.toLong).toOption.map { pid =>
      nodePids += pid
      pid
    }

  private def uvChildren(): Set[Long] =
    ProcessHandle
      .current()
      .children()
      .iterator()
      .asScala
      .filter(h => h.info().command().toScala.exists(_.endsWith("/uv")))
      .map(_.pid())
      .toSet

  /** The `uv` launcher and its descendants (the python agent, and the node while it lives). */
  private def agentTree(): List[ProcessHandle] =
    agentUv.toList.flatMap(h => h :: h.descendants().iterator().asScala.toList)

  /** The agent process itself: the direct child of `uv`. */
  private def agentHandles(): List[ProcessHandle] =
    agentUv.toList.flatMap(_.children().iterator().asScala.toList)

  private def alive(pid: Long): Boolean = ProcessHandle.of(pid).toScala.exists(_.isAlive)

  private def killGroup(pid: Long): Unit =
    Try(Process(Seq("kill", "-KILL", s"-$pid")).!(ProcessLogger(_ => ())))
    Try(Process(Seq("kill", "-KILL", pid.toString)).!(ProcessLogger(_ => ())))

  private def awaitAgentGone(): Unit =
    await("the agent process to exit")(Option.when(agent.forall(!_.isAlive()))(()))
    agent = None
    agentUv = None

  // ---------------------------------------------------------------- manager

  private def bootManager(): Unit =
    val overlay = ConfigSource.string(
      s"""
         |quack-on-demand {
         |  host = "127.0.0.1"
         |  port = $RestPort
         |  apiKey = "$ApiKey"
         |  runtimeType = "fleet"
         |  reconcileIntervalSec = 2
         |  ha.enabled = false
         |  embeddedPostgres.enabled = false
         |  admin { username = "admin", password = "admin", role = "admin" }
         |  auth.management.sessionJwtSecret = "fleet-smoke-session-secret-0123456789abcdef"
         |  defaultMetastore {
         |    pgHost = "${TestPostgres.pgHost}"
         |    pgPort = "${TestPostgres.pgPort}"
         |    pgUser = "${TestPostgres.pgUser}"
         |    pgPassword = "${TestPostgres.pgPass}"
         |    dbName = "$dbName"
         |  }
         |  fleet {
         |    joinToken = "$JoinToken"
         |    heartbeatSec = 1
         |    heartbeatTimeoutSec = 5
         |    reassignAfterSec = 10
         |    startupTimeoutSec = 60
         |    stopTimeoutSec = 10
         |    ephemeral = "fleet"
         |  }
         |}
         |quack-flightsql {
         |  host = "127.0.0.1"
         |  port = $FlightPort
         |  tlsEnabled = false
         |  acl.enabled = false
         |}
         |quack-native { enabled = false, port = 29494 }
         |""".stripMargin
    )
    // Unresolved layers merged first, resolved once: the auth database jdbcUrl is a substitution
    // of defaultMetastore.dbName and must see the throwaway database, not the bundled `qod`.
    val source = overlay
      .withFallback(ConfigSource.defaultApplication)
      .withFallback(ConfigSource.defaultReference)
    val mgrCfg   = source.at("quack-on-demand").loadOrThrow[ManagerConfig]
    val edgeCfg  = source.at("quack-flightsql").loadOrThrow[FlightConfig]
    val quackCfg = source.at("quack-native").loadOrThrow[QuackNativeConfig]
    val authCfg  = source.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig]
    val aclCfg   = source.at("quack-flightsql.acl").loadOrThrow[AclConfig]
    val lockdown = source.at("quack-flightsql.nodeLockdown").loadOrThrow[NodeLockdownConfig]
    val metrics  = source.at("quack-on-demand.metrics").loadOrThrow[MetricsConfig]
    authCfg.database.jdbcUrl should endWith(s"/$dbName")
    mgrCfg.fleet.heartbeatSec shouldBe 1
    quackCfg.enabled shouldBe false
    manager = Main
      .bootManager(
        mgrCfg,
        edgeCfg,
        authCfg,
        aclCfg,
        metrics,
        lockdownCfg = lockdown,
        quackCfg = Some(quackCfg)
      )
      .attempt
      .flatTap(o => IO { bootOutcome = Some(o) })
      .start
      .unsafeRunSync()
    await("manager /health on :" + RestPort) {
      val r = http.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:$RestPort/health"))
          .timeout(Duration.ofSeconds(2))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      Option.when(r.statusCode() == 200)(())
    }

  // ---------------------------------------------------------------- FlightSQL

  private def selectFortyTwo(): String =
    val allocator         = new RootAllocator()
    val raw: FlightClient =
      FlightClient.builder(allocator, Location.forGrpcInsecure("127.0.0.1", FlightPort)).build()
    try
      val hdrs = new FlightCallHeaders()
      hdrs.insert("tenant", Tenant)
      hdrs.insert("pool", Pool)
      hdrs.insert("superuser", "true")
      hdrs.insert(
        "authorization",
        "Basic " + Base64.getEncoder.encodeToString("admin:admin".getBytes("UTF-8"))
      )
      val opt    = new HeaderCallOption(hdrs)
      val fsql   = new FlightSqlClient(raw)
      val info   = fsql.execute("SELECT 42 AS answer", opt)
      val stream = fsql.getStream(info.getEndpoints.get(0).getTicket, opt)
      try
        val values = scala.collection.mutable.ListBuffer.empty[String]
        while stream.next() do
          val root = stream.getRoot
          for i <- 0 until root.getRowCount do
            values += String.valueOf(root.getVector(0).getObject(i))
        values.mkString(",")
      finally stream.close()
    finally
      raw.close()
      allocator.close()

  // ---------------------------------------------------------------- lifecycle

  override def beforeAll(): Unit =
    if missing.isEmpty then
      TestPostgres.dropStrayTestDatabases(DbPrefix)
      stateDir = Files.createTempDirectory("qod-fleet-smoke")

  override def afterAll(): Unit =
    // Agents first (TERM, then KILL whatever is left of the tree), then every node group the
    // pidfiles named, then the manager fiber, then the database. Each stage is guarded so one
    // failure cannot skip the next.
    Try(agentHandles().foreach(_.destroy()))
    Try {
      val deadline = System.nanoTime() + 15.seconds.toNanos
      while agent.exists(_.isAlive()) && System.nanoTime() < deadline do Thread.sleep(200)
    }
    Try(agentTree().foreach(_.destroyForcibly()))
    Try(agent.foreach(_.destroy()))
    Try(recordNodePid())
    nodePids.foreach(pid => Try(killGroup(pid)))
    Try(Option(manager).foreach(_.cancel.timeout(60.seconds).unsafeRunSync()))
    if missing.isEmpty then Try(TestPostgres.dropDatabase(dbName))
    Try(Option(stateDir).foreach { d =>
      Files.walk(d).iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
    })

  // ---------------------------------------------------------------- the smoke

  "fleet smoke 1" should "boot a fleet-mode manager in-process" in step("1 boot manager") {
    bootManager()
  }

  "fleet smoke 2" should "leave a pool pending while no server has joined" in step(
    "2 tenant/db/pool pending"
  ) {
    post("/api/tenant/create", s"""{"id":"$Tenant","displayName":"$Tenant","metastore":{}}""")
    post("/api/database/create", s"""{"tenant":"$Tenant","name":"db","kind":"memory"}""")
    post(
      "/api/pool/create",
      s"""{"tenant":"$Tenant","tenantDb":"$TenantDb","pool":"$Pool","size":1,
         |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
    )
    val p = pool()
    p.nodes shouldBe empty
    p.pending shouldBe 1
    p.reason shouldBe Some("none_free")
  }

  "fleet smoke 3+4" should "fill the slot once the agent joins" in step(
    "3-4 agent joins, slot fills"
  ) {
    startAgent()
    val node = await("one node and no pending slot") {
      val p = pool()
      Option.when(p.nodes.size == 1 && p.pending == 0)(p.nodes.head)
    }
    str(node, "serverName") shouldBe Some(Server)
    await("s1 reachable with its node running") {
      server().filter(s =>
        str(s, "liveness").contains("reachable") && str(s, "nodeState").contains("running")
      )
    }
    recordNodePid() should not be empty
  }

  "fleet smoke 5" should "route SELECT 42 to the fleet node over FlightSQL" in step(
    "5 FlightSQL SELECT 42"
  ) {
    await("SELECT 42 through the edge", 30.seconds)(Try(selectFortyTwo()).toOption) shouldBe "42"
  }

  "fleet smoke 6" should "move the slot to pending when the server is drained" in step("6 drain") {
    post("/api/fleet/server/drain", s"""{"name":"$Server"}""")
    await("pending slot after drain") {
      val p = pool()
      Option.when(p.nodes.isEmpty && p.pending == 1)(())
    }
    await("s1 without assignment") {
      server().filter(s => str(s, "assignedNodeId").isEmpty)
    }
  }

  "fleet smoke 7" should "bring the node back when the server is undrained" in step("7 undrain") {
    post("/api/fleet/server/undrain", s"""{"name":"$Server"}""")
    val node = await("node back after undrain") {
      val p = pool()
      Option.when(p.nodes.size == 1 && p.pending == 0)(p.nodes.head)
    }
    str(node, "serverName") shouldBe Some(Server)
    await("s1 running again") {
      server().filter(s => str(s, "nodeState").contains("running"))
    }
    recordNodePid() should not be empty
  }

  "fleet smoke 8" should "reap the orphan node when a killed agent restarts" in step(
    "8 SIGKILL agent, restart, reap"
  ) {
    val orphan = recordNodePid().getOrElse(fail("no node pidfile before the crash"))
    // SIGKILL the agent and its uv launcher only: the node leads its own session and survives.
    agentHandles().foreach(_.destroyForcibly())
    agentUv.foreach(_.destroyForcibly())
    awaitAgentGone()
    alive(orphan) shouldBe true
    startAgent()
    await("orphan node reaped")(Option.when(!alive(orphan))(()))
    val node = await("node running on s1 after the restart") {
      val p = pool()
      Option.when(p.nodes.size == 1 && p.pending == 0)(p.nodes.head)
    }
    str(node, "serverName") shouldBe Some(Server)
    val s = await("s1 running with capacity reported") {
      server().filter(s =>
        str(s, "nodeState").contains("running") && str(s, "liveness").contains("reachable")
      )
    }
    s.hcursor.get[Option[Int]]("cpus").toOption.flatten should not be empty
    s.hcursor.get[Option[Long]]("memoryBytes").toOption.flatten should not be empty
    val fresh = recordNodePid().getOrElse(fail("no node pidfile after the restart"))
    fresh should not be orphan
  }

  "fleet smoke 9" should "stop the node and free the slot when the agent is stopped" in step(
    "9 SIGTERM agent, reassign window"
  ) {
    val nodePid = recordNodePid().getOrElse(fail("no node pidfile before the stop"))
    agentHandles().foreach(_.destroy()) // SIGTERM: the agent stops its node on the way out
    awaitAgentGone()
    await("node process stopped with the agent")(Option.when(!alive(nodePid))(()))
    await("node row gone and the slot pending after the reassign window") {
      val p = pool()
      Option.when(p.nodes.isEmpty && p.pending == 1)(())
    }
  }
