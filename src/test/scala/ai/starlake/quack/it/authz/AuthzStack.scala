package ai.starlake.quack.it.authz

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
import org.apache.arrow.flight.{
  FlightCallHeaders,
  FlightClient,
  FlightRuntimeException,
  HeaderCallOption,
  Location
}
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.FieldVector
import pureconfig.ConfigSource

import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

/** A real manager booted in-process for the in-engine authorization end-to-end specs: local
  * runtime, the real spawn script and the qod-node build (`QOD_NODE_BIN`), the FlightSQL edge
  * (plain gRPC), the native Quack front door, MCP, and a throwaway control-plane database. With
  * `relayed`, nodes call the decision endpoint through [[relay]] (the handles' replica URL), which
  * a spec stops to play a dead replica; without it they call the REST port directly (the latency
  * spec, Task 14). REST calls ride a login session of the seeded admin (admin / admin).
  */
final class AuthzStack(
    name: String,
    val restPort: Int,
    val flightPort: Int,
    val quackPort: Int,
    relayPort: Int,
    minNodePort: Int,
    maxNodePort: Int,
    aclEnabled: Boolean,
    filesRoot: Path,
    relayed: Boolean = true,
    /** `quack-on-demand.userS3.allowedEndpoints`: the endpoints users' S3 credentials may name. */
    userS3Endpoints: String = "",
    /** What this stack's tenants and source databases are named with, before a run's hex: the
      * databases [[boot]] drops when an earlier run of this stack left them.
      */
    stackPrefixes: List[String] = Nil
):
  import Main.given

  val dbName: String = s"qod_${name}_test_${System.nanoTime().toHexString}"

  /** DuckLake catalog databases the specs created on the test Postgres, dropped by [[stop]]. */
  val catalogDatabases: ListBuffer[String] = ListBuffer.empty

  val relay = new TcpRelay(relayPort, restPort)

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

  private var session: String                                            = ""
  private var manager: FiberIO[Either[Throwable, ExitCode]]              = null
  @volatile private var bootOutcome: Option[Either[Throwable, ExitCode]] = None

  def boot(): Unit =
    TestPostgres.dropStrayTestDatabases(s"qod_$name")
    dropStrayCatalogs()
    if relayed then relay.start()
    val replicaPort = if relayed then relayPort else restPort
    val script      = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath
    val overlay     = ConfigSource.string(
      s"""
         |quack-on-demand {
         |  host = "127.0.0.1"
         |  port = $restPort
         |  runtimeType = "local"
         |  minPort = $minNodePort
         |  maxPort = $maxNodePort
         |  spawnScript = "$script"
         |  reconcileIntervalSec = 2
         |  healthCheckIntervalSec = 2
         |  ha.enabled = false
         |  embeddedPostgres.enabled = false
         |  admin { username = "admin", password = "admin", kind = "admin" }
         |  auth.management.sessionJwtSecret = "authz-e2e-session-secret-0123456789abcdef"
         |  nodeAuthz.internalUrl = "http://127.0.0.1:$replicaPort"
         |  tenantFiles.root = "$filesRoot"
         |  userS3.allowedEndpoints = "$userS3Endpoints"
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
         |  port = $flightPort
         |  tlsEnabled = false
         |  acl.enabled = $aclEnabled
         |}
         |quack-native { enabled = true, host = "127.0.0.1", port = $quackPort }
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
    require(authCfg.database.jdbcUrl.endsWith(s"/$dbName"), "the auth store is not the test's")
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
    await(s"manager /health on :$restPort") {
      val r = http.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:$restPort/health"))
          .timeout(Duration.ofSeconds(2))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      Option.when(r.statusCode() == 200)(())
    }
    session = login(None, "admin", "admin")

  /** The databases of earlier runs of this stack that a killed run left on the test Postgres: the
    * tenant catalogs and the Postgres source database its world names with `stackPrefixes` (each
    * followed by the run's hex and `_` or the end), never anyone else's.
    */
  private def dropStrayCatalogs(): Unit =
    val patterns =
      stackPrefixes.map(p => s"^${java.util.regex.Pattern.quote(p)}[0-9a-f]+(_.*)?$$".r)
    val names = scala.util.Using.resource(
      java.sql.DriverManager
        .getConnection(TestPostgres.adminUrl, TestPostgres.pgUser, TestPostgres.pgPass)
    ) { c =>
      scala.util.Using.resource(c.createStatement()) { st =>
        val rs  = st.executeQuery("SELECT datname FROM pg_database")
        val out = ListBuffer.empty[String]
        while rs.next() do out += rs.getString(1)
        out.toList
      }
    }
    names
      .filter(n => patterns.exists(_.matches(n)))
      .foreach(n => Try(TestPostgres.dropDatabase(n)))

  /** The manager fiber first (its reconcile loop cannot respawn a node stopped below), then this
    * manager's nodes by their marker, then the databases; each stage guarded.
    */
  def stop(): Unit =
    Try(Option(manager).foreach(_.cancel.timeout(60.seconds).unsafeRunSync()))
    Try(
      Process(
        Seq("bash", "scripts/kill-quack-nodes.sh", "--port", restPort.toString),
        None,
        "FORCE_AFTER" -> "5"
      ).!(ProcessLogger(_ => ()))
    )
    Try(relay.stop())
    catalogDatabases.foreach(db => Try(TestPostgres.dropDatabase(db)))
    Try(TestPostgres.dropDatabase(dbName))

  def await[A](what: String, ceiling: FiniteDuration = 90.seconds)(probe: => Option[A]): A =
    val deadline        = System.nanoTime() + ceiling.toNanos
    var last: Option[A] = None
    var lastErr         = ""
    while last.isEmpty && System.nanoTime() < deadline do
      bootOutcome.foreach(o => throw new IllegalStateException(s"manager exited: $o"))
      last =
        try probe
        catch
          case t: Throwable =>
            lastErr = s" (last error: ${t.getMessage})"
            None
      if last.isEmpty then Thread.sleep(500)
    last.getOrElse(throw new IllegalStateException(s"timed out waiting for $what$lastErr"))

  /** A REST call as the seeded admin (or the session `token`): (status, body). */
  def postStatus(path: String, json: String, token: String = session): (Int, String) =
    val r = http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$restPort$path"))
        .timeout(Duration.ofSeconds(30))
        .header("X-API-Key", token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (r.statusCode(), r.body())

  def post(path: String, json: String, token: String = session): Json =
    val (code, body) = postStatus(path, json, token)
    if code < 200 || code >= 300 then throw new IllegalStateException(s"POST $path -> $code $body")
    if body.isBlank then Json.Null else parse(body).fold(e => throw e, identity)

  /** A PUT as the seeded admin (the federated-secret upsert); throws on a non-2xx answer. */
  def put(path: String, json: String): Unit =
    val r = http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$restPort$path"))
        .timeout(Duration.ofSeconds(30))
        .header("X-API-Key", session)
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(json))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    if r.statusCode() < 200 || r.statusCode() >= 300 then
      throw new IllegalStateException(s"PUT $path -> ${r.statusCode()}")

  /** A session of `user` (`tenant` None: the system realm). */
  def login(tenant: Option[String], user: String, password: String): String =
    val body = Json
      .obj(
        "username" -> Json.fromString(user),
        "password" -> Json.fromString(password),
        "tenant"   -> tenant.fold(Json.Null)(Json.fromString)
      )
      .dropNullValues
      .noSpaces
    val r = http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$restPort/api/auth/login"))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    if r.statusCode() != 200 then
      throw new IllegalStateException(s"login of $user -> ${r.statusCode()}")
    parse(r.body()).toOption
      .flatMap(_.hcursor.get[String]("token").toOption)
      .getOrElse(throw new IllegalStateException("no token field in the login response"))

  /** `sql` through the FlightSQL edge as `user`, on a fresh client and handshake: every row as
    * `a,b` (Right), or the error the client got (Left).
    */
  def flight(
      sql: String,
      user: String,
      password: String,
      tenant: String,
      pool: String,
      superuser: Boolean = false
  ): Either[String, List[String]] =
    flightCall(sql, user, password, tenant, pool, superuser).left.map(_.getMessage)

  /** As [[flight]], the failure as the client got it: its Flight status code and description. */
  def flightCall(
      sql: String,
      user: String,
      password: String,
      tenant: String,
      pool: String,
      superuser: Boolean = false
  ): Either[FlightRuntimeException, List[String]] =
    val allocator = new RootAllocator()
    val raw       =
      FlightClient.builder(allocator, Location.forGrpcInsecure("127.0.0.1", flightPort)).build()
    try
      val hdrs = new FlightCallHeaders()
      hdrs.insert("tenant", tenant)
      hdrs.insert("pool", pool)
      if superuser then hdrs.insert("superuser", "true")
      hdrs.insert(
        "authorization",
        "Basic " + Base64.getEncoder.encodeToString(s"$user:$password".getBytes("UTF-8"))
      )
      val opt  = new HeaderCallOption(hdrs)
      val fsql = new FlightSqlClient(raw)
      try
        val info   = fsql.execute(sql, opt)
        val stream = fsql.getStream(info.getEndpoints.get(0).getTicket, opt)
        try
          val out = ListBuffer.empty[String]
          while stream.next() do
            val root = stream.getRoot
            for i <- 0 until root.getRowCount do
              out += root.getFieldVectors.asScala
                .map((v: FieldVector) => String.valueOf(v.getObject(i)))
                .mkString(",")
          Right(out.toList)
        finally stream.close()
      catch case e: FlightRuntimeException => Left(e)
    finally
      raw.close()
      allocator.close()

  /** The front door token of `user` on `pool`. */
  private def doorToken(user: String, password: String, tenant: String, pool: String): String =
    s"tenant=$tenant&pool=$pool&user=$user&password=$password"

  /** `sql` through the native Quack front door, from the real DuckDB 2.0 CLI's `quack_query`: the
    * rows as CSV lines (Right), or the CLI's stderr (Left). Callers check
    * [[AuthzStack.cliPresent]].
    */
  def door(
      sql: String,
      user: String,
      password: String,
      tenant: String,
      pool: String
  ): Either[String, List[String]] =
    val token = doorToken(user, password, tenant, pool)
    cli(
      s"SELECT * FROM quack_query('quack:127.0.0.1:$quackPort', ${AuthzStack.lit(sql)}, " +
        s"token := ${AuthzStack.lit(token)});"
    )

  /** `sql` from the DuckDB 2.0 CLI after it ATTACHed the front door as `q` (TYPE quack): the
    * client's own attach-time catalog statements reach the node first, then `sql`'s scans.
    */
  def attached(
      sql: String,
      user: String,
      password: String,
      tenant: String,
      pool: String
  ): Either[String, List[String]] =
    val token = doorToken(user, password, tenant, pool)
    cli(
      s"ATTACH 'quack:127.0.0.1:$quackPort' AS q (TYPE quack, TOKEN ${AuthzStack.lit(token)});\n$sql"
    )

  /** `script` run by the DuckDB 2.0 CLI from its stdin (so no credential is on its command line).
    */
  private def cli(script: String): Either[String, List[String]] =
    val out = new StringBuilder
    val err = new StringBuilder
    val rc  = (Process(Seq("duckdb", "-no-agent", "-bail", "-csv", "-noheader")) #<
      new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)))
      .!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    if rc == 0 && err.isEmpty then
      Right(out.toString.linesIterator.map(_.trim).filter(_.nonEmpty).toList)
    else Left(err.toString.trim)

  /** `sql` through MCP's `run_sql` with personal access token `pat`: the tool's text (Right), or
    * its error text (Left).
    */
  def mcp(pat: String, sql: String, database: String): Either[String, String] =
    val body = Json
      .obj(
        "jsonrpc" -> Json.fromString("2.0"),
        "id"      -> Json.fromInt(1),
        "method"  -> Json.fromString("tools/call"),
        "params"  -> Json.obj(
          "name"      -> Json.fromString("run_sql"),
          "arguments" -> Json.obj(
            "sql"      -> Json.fromString(sql),
            "database" -> Json.fromString(database)
          )
        )
      )
      .noSpaces
    val r = http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$restPort/mcp"))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer $pat")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    val result = parse(r.body()).toOption.map(_.hcursor.downField("result"))
    val text   = result
      .flatMap(_.downField("content").downN(0).get[String]("text").toOption)
      .getOrElse(r.body())
    if result.flatMap(_.get[Boolean]("isError").toOption).contains(false) then Right(text)
    else Left(text)

object AuthzStack:

  /** A SQL string literal of `s`. */
  def lit(s: String): String = "'" + s.replace("'", "''") + "'"

  /** The client of the native front door cases: a DuckDB 2.0 CLI on PATH (`-no-agent`: under an AI
    * agent the 2.0 CLI switches its output format).
    */
  lazy val cliPresent: Boolean =
    val out = new StringBuilder
    Try(
      Process(Seq("duckdb", "-no-agent", "-version")).!(ProcessLogger(l => out.append(l), _ => ()))
    ).toOption
      .contains(0) && out.toString.trim.startsWith("v2.")

/** A TCP relay from 127.0.0.1:`listenPort` to 127.0.0.1:`targetPort`: the decision replica the
  * nodes see. [[stop]] closes the listener and every relayed connection (a dead replica); [[start]]
  * listens again on the same port. [[decideCalls]] counts the decision requests the nodes sent
  * through it (request lines `POST /api/internal/authz/decide`), so a spec can tell a verdict the
  * node took from its cache from one it asked for.
  */
final class TcpRelay(listenPort: Int, targetPort: Int):

  @volatile private var server: Option[ServerSocket] = None
  private val open                                   = ListBuffer.empty[Socket]
  private val decides                                = new java.util.concurrent.atomic.AtomicLong(0)

  /** Decision requests relayed so far. */
  def decideCalls: Long = decides.get()

  def start(): Unit = synchronized {
    val s = new ServerSocket()
    s.setReuseAddress(true)
    s.bind(new InetSocketAddress("127.0.0.1", listenPort))
    server = Some(s)
    val t = new Thread(() => accept(s), s"tcp-relay-$listenPort")
    t.setDaemon(true)
    t.start()
  }

  def stop(): Unit = synchronized {
    server.foreach(s => Try(s.close()))
    server = None
    open.foreach(c => Try(c.close()))
    open.clear()
  }

  private def accept(s: ServerSocket): Unit =
    val running = new AtomicBoolean(true)
    while running.get() do
      Try(s.accept()).toOption match
        case None         => running.set(false)
        case Some(client) =>
          Try(new Socket("127.0.0.1", targetPort)).toOption match
            case None =>
              Try(client.close())
            case Some(upstream) =>
              synchronized {
                open += client
                open += upstream
              }
              pipe(client.getInputStream, upstream.getOutputStream, client, upstream, count = true)
              pipe(upstream.getInputStream, client.getOutputStream, client, upstream, count = false)

  private def pipe(
      in: InputStream,
      out: OutputStream,
      a: Socket,
      b: Socket,
      count: Boolean
  ): Unit =
    val t = new Thread(() => {
      Try(if count then countingCopy(in, out) else in.transferTo(out))
      Try(a.close())
      Try(b.close())
      ()
    })
    t.setDaemon(true)
    t.start()

  /** Copies `in` to `out`, counting decision request lines, also across reads. */
  private def countingCopy(in: InputStream, out: OutputStream): Unit =
    val needle = TcpRelay.DecideLine
    val buffer = new Array[Byte](8192)
    var tail   = Array.emptyByteArray
    var n      = in.read(buffer)
    while n >= 0 do
      out.write(buffer, 0, n)
      out.flush()
      val seen = tail ++ buffer.take(n)
      var at   = seen.indexOfSlice(needle)
      while at >= 0 do
        decides.incrementAndGet()
        at = seen.indexOfSlice(needle, at + needle.length)
      tail = seen.takeRight(needle.length - 1)
      n = in.read(buffer)

object TcpRelay:
  private val DecideLine: Array[Byte] =
    "POST /api/internal/authz/decide ".getBytes(StandardCharsets.US_ASCII)
