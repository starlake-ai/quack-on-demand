package ai.starlake.quack.boot

import ai.starlake.quack.{FlightConfig, Main, ManagerConfig, QuackNativeConfig}
import ai.starlake.quack.edge.config.{AuthenticationConfig, OpaConfig}
import ai.starlake.quack.observability.metrics.MetricsConfig
import ai.starlake.quack.observability.metrics.MetricsConfigCodec.given
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.{ExitCode, FiberIO, IO}
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Paths
import java.sql.DriverManager
import java.time.Duration
import scala.concurrent.duration.*
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

/** Sessions survive a restart with no QOD_SESSION_JWT_SECRET: a real manager booted in-process with
  * an empty session secret stores one in its control plane, a login session minted by that boot is
  * admitted by the next boot against the same control plane, and the stored row is the only one.
  *
  * Manager on 20956 (REST) / 31356 (FlightSQL, plain gRPC), native front door off, no pool (node
  * range 23230-23239 stays unused), against a throwaway control-plane database; never the default
  * ports. Cancelled (not failed) when the test Postgres (SL_TEST_PG_*) is unreachable. Assertions
  * compare as booleans or status codes: a failure never prints a secret or a session.
  */
class SessionSecretRestartSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:
  import Main.given

  private val RestPort   = 20956
  private val FlightPort = 31356
  private val Ceiling    = 90.seconds

  private val DbPrefix = "qod_secretrestart"
  private val dbName   = s"${DbPrefix}_test_${System.nanoTime().toHexString}"

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script and kill-quack-nodes.sh")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else None

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

  private var manager: FiberIO[Either[Throwable, ExitCode]]              = null
  @volatile private var bootOutcome: Option[Either[Throwable, ExitCode]] = None
  // The seeded admin's session, minted by the first boot.
  private var session: String = ""

  private def await[A](what: String)(probe: => Option[A]): A =
    val deadline        = System.nanoTime() + Ceiling.toNanos
    var last: Option[A] = None
    var lastErr: String = ""
    while last.isEmpty && System.nanoTime() < deadline do
      bootOutcome.foreach(o => fail(s"manager exited while waiting for $what: $o"))
      last =
        try probe
        catch
          case t: Throwable =>
            lastErr = s" (last error: ${t.getClass.getSimpleName})"
            None
      if last.isEmpty then Thread.sleep(500)
    last.getOrElse(fail(s"timed out after $Ceiling waiting for $what$lastErr"))

  private def status(path: String, token: String): Int =
    http
      .send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:$RestPort$path"))
          .timeout(Duration.ofSeconds(20))
          .header("X-API-Key", token)
          .GET()
          .build(),
        HttpResponse.BodyHandlers.discarding()
      )
      .statusCode()

  private def login(): String =
    val r = http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$RestPort/api/auth/login"))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("""{"username":"admin","password":"admin"}"""))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    r.statusCode() shouldBe 200
    parse(r.body()).toOption
      .flatMap(_.hcursor.get[String]("token").toOption)
      .getOrElse(fail("no token field in the login response"))

  /** Rows in `qodstate_boot_secret`, and whether the `session_jwt` one holds a value. */
  private def storedRows(): (Int, Boolean) =
    val c = DriverManager.getConnection(
      TestPostgres.dbUrl(dbName),
      TestPostgres.pgUser,
      TestPostgres.pgPass
    )
    try
      val rs    = c.createStatement().executeQuery("SELECT name, value FROM qodstate_boot_secret")
      var n     = 0
      var found = false
      while rs.next() do
        n += 1
        if rs.getString(1) == "session_jwt" && Option(rs.getString(2)).exists(_.nonEmpty) then
          found = true
      (n, found)
    finally c.close()

  private def boot(): Unit =
    bootOutcome = None
    val script  = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath
    val overlay = ConfigSource.string(
      s"""
         |quack-on-demand {
         |  host = "127.0.0.1"
         |  port = $RestPort
         |  runtimeType = "local"
         |  minPort = 23230
         |  maxPort = 23239
         |  spawnScript = "$script"
         |  reconcileIntervalSec = 2
         |  healthCheckIntervalSec = 2
         |  ha.enabled = false
         |  embeddedPostgres.enabled = false
         |  admin { username = "admin", password = "admin" }
         |  auth.management.sessionJwtSecret = ""
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
         |}
         |quack-native { enabled = false, port = 29496 }
         |""".stripMargin
    )
    val source = overlay
      .withFallback(ConfigSource.defaultApplication)
      .withFallback(ConfigSource.defaultReference)
    val authCfg = source.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig]
    authCfg.database.jdbcUrl should endWith(s"/$dbName")
    val opa = OpaConfig
      .validate(source.at("quack-flightsql.opa").loadOrThrow[OpaConfig])
      .fold(err => throw new IllegalArgumentException(err), identity)
    manager = Main
      .bootManager(
        source.at("quack-on-demand").loadOrThrow[ManagerConfig],
        source.at("quack-flightsql").loadOrThrow[FlightConfig],
        authCfg,
        source.at("quack-on-demand.metrics").loadOrThrow[MetricsConfig],
        opa,
        quackCfg = Some(source.at("quack-native").loadOrThrow[QuackNativeConfig])
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
        HttpResponse.BodyHandlers.discarding()
      )
      Option.when(r.statusCode() == 200)(())
    }

  private def stop(): Unit =
    Option(manager).foreach(_.cancel.timeout(60.seconds).unsafeRunSync())
    manager = null

  override def beforeAll(): Unit =
    if missing.isEmpty then TestPostgres.dropStrayTestDatabases(DbPrefix)

  override def afterAll(): Unit =
    Try(stop())
    Try(
      Process(
        Seq("bash", "scripts/kill-quack-nodes.sh", "--port", RestPort.toString),
        None,
        "FORCE_AFTER" -> "5"
      ).!(ProcessLogger(_ => ()))
    )
    if missing.isEmpty then Try(TestPostgres.dropDatabase(dbName))

  "a manager with no session secret" should "store one in its control plane at boot" in {
    missing.foreach(reason => cancel(s"session secret restart skipped: $reason"))
    boot()
    session = login()
    status("/api/auth/whoami", session) shouldBe 200
    storedRows() shouldBe (1, true)
  }

  it should "admit a session minted before a restart after it" in {
    missing.foreach(reason => cancel(s"session secret restart skipped: $reason"))
    if session.isEmpty then cancel("skipped: the first boot did not mint a session")
    stop()
    boot()
    status("/api/auth/whoami", session) shouldBe 200
    status("/api/pool/list", session) shouldBe 200
    // The restarted manager still checks the signature: a forged one is refused.
    status("/api/auth/whoami", session.dropRight(4) + "AAAA") shouldBe 401
    storedRows() shouldBe (1, true)
  }
