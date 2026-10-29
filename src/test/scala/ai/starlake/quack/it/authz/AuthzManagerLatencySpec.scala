package ai.starlake.quack.it.authz

import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import io.circe.Json
import org.apache.arrow.flight.auth2.{ClientBearerHeaderHandler, ClientIncomingAuthHeaderMiddleware}
import org.apache.arrow.flight.sql.FlightSqlClient
import org.apache.arrow.flight.{
  CallOption,
  FlightCallHeaders,
  FlightClient,
  HeaderCallOption,
  Location
}
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.Try

/** Spike item 8 end to end: a user's statement through the FlightSQL edge of a real manager on this
  * host, on a DuckLake-on-Postgres pool (the managed shape), its verdict cached on the node (warm)
  * or decided by the real pipeline on the real endpoint (cold: a plan the node never saw). The
  * difference is the manager round trip with everything the decision does. Manager on 20955 (REST)
  * / 31355 (FlightSQL) / 29498 (front door), nodes 23240-23249, decisions straight to the REST port
  * (no relay). Opt-in: `QOD_AUTHZ_LATENCY=1`.
  */
class AuthzManagerLatencySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val enabled = sys.env.get("QOD_AUTHZ_LATENCY").contains("1")
  // QOD_AUTHZ_LATENCY_ROUNDS shortens a trial run; the numbers of record use the default.
  private val Rounds =
    sys.env.get("QOD_AUTHZ_LATENCY_ROUNDS").flatMap(_.toIntOption).getOrElse(2000)
  private val Warmup = math.min(200, Rounds)
  private val run    = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  private val Tenant = s"lat_authz_latency_$run"
  private val Pw     = "lat-e2e-pw-1"

  private var stack: AuthzStack = null
  private var root: Path        = null

  private def ready: Boolean =
    enabled && TestPostgres.reachable && QodNodeTestBinary.path.nonEmpty &&
      !scala.util.Properties.isWin

  override def beforeAll(): Unit =
    if ready then
      root = Files.createTempDirectory("qod-authz-lat").toRealPath()
      stack = new AuthzStack(
        "authz_latency",
        20955,
        31355,
        29498,
        20965,
        23240,
        23249,
        aclEnabled = true,
        filesRoot = Files.createDirectories(root.resolve("files")),
        relayed = false,
        stackPrefixes = List("lat_authz_latency_")
      )
      stack.boot()
      def obj(f: (String, Json)*) = Json.obj(f*).noSpaces
      def s(v: String)            = Json.fromString(v)
      stack.post(
        "/api/tenant/create",
        obj("id" -> s(Tenant), "displayName" -> s(Tenant), "metastore" -> Json.obj())
      )
      val db = stack
        .post(
          "/api/database/create",
          obj(
            "tenant"   -> s(Tenant),
            "name"     -> s("lake"),
            "kind"     -> s("ducklake"),
            "dataPath" -> s(s"${root.resolve("lake_data")}/")
          )
        )
        .hcursor
        .get[String]("name")
        .fold(e => throw e, identity)
      stack.catalogDatabases += db
      stack.post(
        "/api/pool/create",
        s"""{"tenant":"$Tenant","tenantDb":"$db","pool":"p","size":1,
           |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
      )
      // The default memberships: ALL on every table of every pool of the tenant.
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Tenant),
          "username" -> s("lat"),
          "password" -> s(Pw),
          "kind"     -> s("user")
        )
      )
      stack.await("the pool serving", 120.seconds) {
        stack.flight("SELECT 1", "admin", "admin", Tenant, "p", superuser = true).toOption
      }
      stack
        .flight(Latency.wideTable("w"), "admin", "admin", Tenant, "p", superuser = true)
        .fold(e => throw new IllegalStateException(s"creating the table: $e"), identity)
      stack.await("the user's first statement", 60.seconds) {
        stack.flight("SELECT count(*) FROM w", "lat", Pw, Tenant, "p").toOption
      }

  override def afterAll(): Unit =
    try if stack != null then stack.stop()
    finally
      if root != null then
        Try {
          val tree = Files.walk(root)
          try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
          finally tree.close()
        }

  "a manager round trip" should "add under 5 ms at p99 on a cache miss" in {
    assume(enabled, "latency measurement is opt-in: QOD_AUTHZ_LATENCY=1")
    assume(stack != null, "needs the test Postgres and a qod-node build")
    val allocator = new RootAllocator()
    // The edge answers a Basic call with `Bearer <peerId>`; replaying it skips the password check
    // (bcrypt) on every later call, which would otherwise dominate both arms.
    val bearer = new ClientIncomingAuthHeaderMiddleware.Factory(new ClientBearerHeaderHandler())
    val raw    = FlightClient
      .builder(allocator, Location.forGrpcInsecure("127.0.0.1", 31355))
      .intercept(bearer)
      .build()
    try
      val hdrs = new FlightCallHeaders()
      hdrs.insert("tenant", Tenant)
      hdrs.insert("pool", "p")
      hdrs.insert(
        "authorization",
        "Basic " + Base64.getEncoder.encodeToString(s"lat:$Pw".getBytes("UTF-8"))
      )
      val fsql                                     = new FlightSqlClient(raw)
      def exec(sql: String, opt: CallOption): Unit =
        val info   = fsql.execute(sql, opt)
        val stream = fsql.getStream(info.getEndpoints.get(0).getTicket, opt)
        try while stream.next() do ()
        finally stream.close()
      exec("SELECT 1", new HeaderCallOption(hdrs))
      val session                  = bearer.getCredentialCallOption
      def timed(sql: String): Long =
        val t0 = System.nanoTime()
        exec(sql, session)
        System.nanoTime() - t0
      // Both arms change the text every round (a projected constant, which no description carries:
      // the gateway parses a new statement either way); only the cold arm reads a column subset the
      // node never saw, so only its plan is new.
      val fixed        = Latency.subsets.size - 1
      def warm(i: Int) = Latency.counting("w", fixed, head = s"$i AS k, ")
      def cold(i: Int) = Latency.counting("w", i, head = s"$i AS k, ")
      val w            = new Array[Long](Rounds)
      val c            = new Array[Long](Rounds)
      (0 until Warmup).foreach { i =>
        timed(warm(i))
        timed(cold(Rounds + i))
      }
      (0 until Rounds).foreach { i =>
        w(i) = timed(warm(Warmup + i))
        c(i) = timed(cold(i))
      }
      println(Latency.line("e2e warm (hit)", w))
      println(Latency.line("e2e cold (miss)", c))
      println(Latency.added("e2e manager round trip", c, w))
      (Latency.percentile(c, 99) - Latency.percentile(w, 99)) should be < 5.0
    finally
      raw.close()
      allocator.close()
  }
