package ai.starlake.quack.ondemand.runtime

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
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

/** End-to-end smoke of local nodes on qod-node: a real manager booted in-process with
  * `runtimeType = local` resolves its qod-node at boot ([[QodNodeBinary]]: `QOD_NODE_BIN`, which
  * build.sbt points at this checkout's build, else the binary vendored for this platform), a pool
  * of one starts a node through the real spawn script, and `SELECT 42` reaches it through the
  * FlightSQL edge.
  *
  * Manager on 20952 (REST) / 31352 (FlightSQL, plain gRPC), native front door off, nodes on
  * 23210-23219, against a throwaway control-plane database; never the default ports. Teardown stops
  * the manager fiber, then any node of this manager by its marker (kill-quack-nodes.sh --port
  * 20952), then drops the database. Cancelled (not failed) when the test Postgres (SL_TEST_PG_*) is
  * unreachable.
  */
class LocalNodeSmokeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:
  import Main.given

  private val RestPort   = 20952
  private val FlightPort = 31352
  private val ApiKey     = "local-smoke-key"
  private val Tenant     = "smoke"
  private val TenantDb   = "smoke_db"
  private val Pool       = "bi"
  private val Ceiling    = 90.seconds

  private val DbPrefix = "qod_localsmoke"
  private val dbName   = s"${DbPrefix}_test_${System.nanoTime().toHexString}"

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script and kill-quack-nodes.sh")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else None

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

  private var manager: FiberIO[Either[Throwable, ExitCode]]              = null
  @volatile private var bootOutcome: Option[Either[Throwable, ExitCode]] = None
  @volatile private var broken: Option[String]                           = None

  private def step(name: String)(body: => Unit): Unit =
    missing.foreach(reason => cancel(s"local node smoke skipped: $reason"))
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
      println(f"[local-smoke] $name%-40s ${ms}%6d ms")

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

  private def poolNodes(): List[Json] =
    val (code, body) = request("GET", "/api/pool/list", None)
    withClue(s"GET /api/pool/list -> $code $body: ")(code shouldBe 200)
    parse(body)
      .fold(e => fail(e.getMessage), identity)
      .hcursor
      .downField("pools")
      .values
      .getOrElse(Nil)
      .find(j => j.hcursor.get[String]("pool").contains(Pool))
      .flatMap(_.hcursor.downField("nodes").values.map(_.toList))
      .getOrElse(Nil)

  private def bootManager(): Unit =
    val script  = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath
    val overlay = ConfigSource.string(
      s"""
         |quack-on-demand {
         |  host = "127.0.0.1"
         |  port = $RestPort
         |  apiKey = "$ApiKey"
         |  runtimeType = "local"
         |  minPort = 23210
         |  maxPort = 23219
         |  spawnScript = "$script"
         |  reconcileIntervalSec = 2
         |  ha.enabled = false
         |  embeddedPostgres.enabled = false
         |  admin { username = "admin", password = "admin", role = "admin" }
         |  auth.management.sessionJwtSecret = "local-smoke-session-secret-0123456789abcdef"
         |  defaultMetastore {
         |    pgHost = "${TestPostgres.pgHost}"
         |    pgPort = "${TestPostgres.pgPort}"
         |    pgUser = "${TestPostgres.pgUser}"
         |    pgPassword = "${TestPostgres.pgPass}"
         |    dbName = "$dbName"
         |  }
         |}
         |quack-flightsql {
         |  host = "127.0.0.1"
         |  port = $FlightPort
         |  tlsEnabled = false
         |  acl.enabled = false
         |}
         |quack-native { enabled = false, port = 29495 }
         |""".stripMargin
    )
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
    val opa      = ai.starlake.quack.edge.config.OpaConfig
      .validate(
        source.at("quack-flightsql.opa").loadOrThrow[ai.starlake.quack.edge.config.OpaConfig]
      )
      .fold(err => throw new IllegalArgumentException(err), identity)
    authCfg.database.jdbcUrl should endWith(s"/$dbName")
    manager = Main
      .bootManager(
        mgrCfg,
        edgeCfg,
        authCfg,
        aclCfg,
        metrics,
        opa,
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

  override def beforeAll(): Unit =
    if missing.isEmpty then TestPostgres.dropStrayTestDatabases(DbPrefix)

  override def afterAll(): Unit =
    // The manager fiber first, so its reconcile loop cannot respawn a node stopped below; then
    // whatever node of this manager is left, by its marker; then the database. Each stage is
    // guarded so one failure cannot skip the next.
    Try(Option(manager).foreach(_.cancel.timeout(60.seconds).unsafeRunSync()))
    Try(
      Process(
        Seq("bash", "scripts/kill-quack-nodes.sh", "--port", RestPort.toString),
        None,
        "FORCE_AFTER" -> "5"
      ).!(ProcessLogger(_ => ()))
    )
    if missing.isEmpty then Try(TestPostgres.dropDatabase(dbName))

  "local node smoke 1" should "boot a local-runtime manager, resolving its qod-node" in step(
    "1 boot manager"
  ) {
    bootManager()
    QodNodeBinary.default.isRight shouldBe true
  }

  "local node smoke 2" should "start the pool's node as a qod-node process" in step(
    "2 tenant/db/pool, node up"
  ) {
    post("/api/tenant/create", s"""{"id":"$Tenant","displayName":"$Tenant","metastore":{}}""")
    post("/api/database/create", s"""{"tenant":"$Tenant","name":"db","kind":"memory"}""")
    post(
      "/api/pool/create",
      s"""{"tenant":"$Tenant","tenantDb":"$TenantDb","pool":"$Pool","size":1,
         |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
    )
    await("one node")(poolNodes().headOption)
    // This manager's node: its argv names its node directory, which carries the manager's marker
    // (quack-node.<rest port>-<boot id>.XXXXXX). It runs the qod-node the manager resolved.
    val command = await("this manager's qod-node process") {
      ProcessHandle
        .allProcesses()
        .iterator()
        .asScala
        .find(_.info().arguments().toScala.exists(_.exists(_.contains(s"quack-node.$RestPort-"))))
        .flatMap(_.info().command().toScala)
    }
    Paths.get(command).toRealPath() shouldBe QodNodeBinary.default.toOption.get.toRealPath()
  }

  "local node smoke 3" should "route SELECT 42 to the node over FlightSQL" in step(
    "3 FlightSQL SELECT 42"
  ) {
    await("SELECT 42 through the edge", 30.seconds)(Try(selectFortyTwo()).toOption) shouldBe "42"
  }
