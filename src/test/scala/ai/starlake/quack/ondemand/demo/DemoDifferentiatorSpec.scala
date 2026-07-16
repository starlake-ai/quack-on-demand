package ai.starlake.quack.ondemand.demo

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.google.protobuf.{Any => ProtoAny}
import org.apache.arrow.flight.sql.FlightSqlClient
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.flight.{
  FlightCallHeaders,
  FlightClient,
  FlightDescriptor,
  HeaderCallOption,
  Location
}
import org.apache.arrow.memory.RootAllocator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** End-to-end proof of the `qod demo` differentiator: a fully self-contained boot (embedded
  * Postgres, TPC-H seed, RBAC import, real FlightSQL edge + Quack node) actually enforces row-level
  * security (RLS) and column-level security (CLS) for the seeded `analyst` role, and denies access
  * to a table outside a role's grants.
  *
  * This is a slow, real-infra integration spec (embedded Postgres download/boot + `dbgen` + a
  * spawned Quack node process), guarded the same way [[DemoPostgresSpec]] /
  * `TestPostgres.ensureReachable()` guard their live-infra specs: cancel (not fail) rather than
  * false-negative the suite when `duckdb` is unavailable on the host.
  *
  * Reuses the project's real Arrow Flight / FlightSQL wire client (`org.apache.arrow.flight.sql`,
  * already a main dependency -- see [[ai.starlake.quack.security.FlightSqlRealClientSpec]] for the
  * sibling pattern of driving [[ai.starlake.quack.edge.FlightEdgeServer]] over real gRPC with
  * `HeaderCallOption`-carried `tenant` / `pool` / `authorization` headers) rather than hand-rolling
  * a new client. Unlike that harness (which stubs the Quack backend), this spec drives the FULL
  * stack end to end, so [[queryFlight]] fetches and decodes real result rows via
  * [[org.apache.arrow.flight.sql.FlightSqlClient.getStream]] instead of stopping at the handshake.
  */
class DemoDifferentiatorSpec extends AnyFlatSpec with Matchers:

  private val Tenant     = "acme"
  private val Pool       = "bi"
  private val FlightPort = 31338

  private def duckdbAvailable: Boolean =
    scala.util
      .Try(
        scala.sys.process
          .Process(Seq("duckdb", "-version"))
          .!(scala.sys.process.ProcessLogger(_ => ()))
      )
      .getOrElse(1) == 0

  // Final-review Fix 2: none of these are legitimately set outside a demo run, so their absence
  // here doubles as "nothing else already polluted this JVM fork" and lets the assertion after
  // teardown (below) be a plain null-check rather than a snapshot/compare.
  private val DemoMutatedSysProps = List(
    "quack-on-demand.cls.enabled",
    "quack-on-demand.rls.enabled",
    "QOD_BOOTSTRAP_YAML"
  )

  "qod demo" should "apply RLS/CLS for the analyst and deny ungranted tables" in {
    if !duckdbAvailable then cancel("duckdb CLI not on PATH; skipping demo integration test")

    DemoMutatedSysProps.foreach(k => System.getProperty(k) shouldBe null)

    val home  = java.nio.file.Files.createTempDirectory("demo-diff-spec")
    val fiber = DemoRunner.runDemo(List(home.toString)).start.unsafeRunSync()
    try
      // 1. Wait until FlightSQL :31338 answers a handshake-free RPC (GetSqlInfo needs no auth --
      //    see FlightSqlRealClientSpec "serve GetSqlInfo without any Authorization header"). A full
      //    boot here means: embedded PG start, control-plane migrate, RBAC manifest import,
      //    TPC-H dbgen seed, and a spawned Quack node -- budget generously.
      waitForFlightReady(FlightPort, 150.seconds)

      // Final-review Fix 2, mid-lifecycle half of the proof: `setup` has unconditionally run by
      // the time Flight answers (it runs before `bootWithBanner`), so the mutated properties must
      // be live now -- the teardown assertion in `finally` below is only meaningful if we first
      // confirm they were actually set, not just absent because `setup` never ran.
      System.getProperty("quack-on-demand.cls.enabled") shouldBe "true"
      System.getProperty("quack-on-demand.rls.enabled") shouldBe "true"
      System.getProperty("QOD_BOOTSTRAP_YAML") shouldBe "classpath:bootstrap-demo-minimal.yaml"

      // 2. alice (analyst): c_phone masked to '***', only BUILDING rows. The Quack node may still
      //    be warming up after Flight starts accepting connections, so retry transient failures.
      val aliceRows = queryFlightRetrying(
        FlightPort,
        "alice",
        "demo-alice",
        "SELECT c_phone, c_mktsegment FROM acme_tpch.tpch1.customer LIMIT 50",
        90.seconds
      )
      aliceRows should not be empty
      aliceRows.map(_("c_phone")).toSet shouldBe Set("***")           // CLS mask
      aliceRows.map(_("c_mktsegment")).toSet shouldBe Set("BUILDING") // RLS filter

      // 3. acme-admin: full, unmasked, multi-segment data (no analyst policies on this role).
      val adminRows = queryFlightRetrying(
        FlightPort,
        "acme-admin",
        "demo-acme-admin",
        "SELECT c_phone, c_mktsegment FROM acme_tpch.tpch1.customer LIMIT 50",
        60.seconds
      )
      adminRows should not be empty
      adminRows.map(_("c_phone")).toSet should not contain "***"
      adminRows.map(_("c_mktsegment")).toSet.size should be > 1

      // 4. alice querying a table she has no grant on (lineitem): denied. The ACL gate runs before
      //    any node dispatch (see FlightSqlRouter.execute), so this doesn't need node warm-up
      //    retries the way the two SELECTs above do.
      val ex = intercept[Exception] {
        queryFlight(
          FlightPort,
          "alice",
          "demo-alice",
          "SELECT * FROM acme_tpch.tpch1.lineitem LIMIT 1"
        )
      }
      ex.getMessage.toLowerCase should include("denied")
    finally
      // `Fiber#cancel` completes only once the target fiber's finalizers -- including `runDemo`'s
      // `.guarantee(cleanup)`, which restores the mutated system properties (Fix 2) -- have run, so
      // no extra wait/poll is needed before the assertion right below.
      fiber.cancel.unsafeRunSync()
      // Final-review Fix 2, teardown half of the proof: back to unset, exactly the pre-demo state
      // asserted at the top of this test.
      DemoMutatedSysProps.foreach(k => System.getProperty(k) shouldBe null)
      // best-effort dir cleanup; runDemo's guarantee also removes its own home
      DemoHome(home, home, home, home).deleteRecursively()
  }

  // ---------------------------------------------------------------------
  // FlightSQL wire helpers -- built on org.apache.arrow.flight(.sql), the
  // same real client used by FlightSqlRealClientSpec. No hand-rolled
  // protocol: HeaderCallOption carries tenant/pool/authorization exactly as
  // FlightEdgeServer.headerAuth expects, and FlightSqlClient drives the
  // CommandStatementQuery execute + doGet dance.
  // ---------------------------------------------------------------------

  /** TLS client against the demo edge. The demo posture keeps TLS on with a self-signed
    * auto-generated cert (DemoConfig.overlay), so the client must skip server verification --
    * exactly what the banner's `tls_skip_verify` knob tells ADBC users to do.
    */
  private def buildFlightClient(allocator: RootAllocator, port: Int): FlightClient =
    FlightClient
      .builder(allocator, Location.forGrpcTls("127.0.0.1", port))
      .useTls()
      .verifyServer(false)
      .build()

  private def basicCredentials(user: String, password: String): String =
    Base64.getEncoder.encodeToString(s"$user:$password".getBytes("UTF-8"))

  private def headersOpt(user: String, password: String): HeaderCallOption =
    val hdrs = new FlightCallHeaders()
    hdrs.insert("tenant", Tenant)
    hdrs.insert("pool", Pool)
    hdrs.insert("authorization", s"Basic ${basicCredentials(user, password)}")
    new HeaderCallOption(hdrs)

  /** Poll an unauthenticated GetSqlInfo call until the FlightSQL edge answers, or fail after
    * `timeout`. GetSqlInfo is served without any Authorization header (R5 / Power BI ODBC connect
    * probe), so this exercises the real gRPC server without needing RBAC data to be ready yet.
    */
  private def waitForFlightReady(port: Int, timeout: FiniteDuration): Unit =
    val deadline                   = System.nanoTime() + timeout.toNanos
    var lastErr: Option[Throwable] = None
    while System.nanoTime() < deadline do
      val allocator = new RootAllocator()
      val client    = buildFlightClient(allocator, port)
      try
        val cmd = FlightSql.CommandGetSqlInfo
          .newBuilder()
          .addInfo(FlightSql.SqlInfo.FLIGHT_SQL_SERVER_NAME_VALUE)
          .build()
        client.getInfo(FlightDescriptor.command(ProtoAny.pack(cmd).toByteArray))
        return
      catch case e: Throwable => lastErr = Some(e)
      finally
        try client.close()
        catch case _: Throwable => ()
        try allocator.close()
        catch case _: Throwable => ()
      Thread.sleep(1000)
    fail(
      s"FlightSQL never became ready on 127.0.0.1:$port within $timeout" +
        lastErr.map(e => s" (last error: ${e.getMessage})").getOrElse("")
    )

  /** Execute `sql` as `user` over a fresh Flight connection and decode the full result into a list
    * of column-name -> stringified-value rows.
    */
  private def queryFlight(
      port: Int,
      user: String,
      password: String,
      sql: String
  ): List[Map[String, String]] =
    val allocator = new RootAllocator()
    val rawClient = buildFlightClient(allocator, port)
    try
      val fsql     = new FlightSqlClient(rawClient)
      val opt      = headersOpt(user, password)
      val info     = fsql.execute(sql, opt)
      val endpoint = info.getEndpoints.get(0)
      val stream   = fsql.getStream(endpoint.getTicket, opt)
      try
        val fields = stream.getSchema.getFields.asScala.map(_.getName).toList
        val rows   = ListBuffer.empty[Map[String, String]]
        while stream.next() do
          val root = stream.getRoot
          for i <- 0 until root.getRowCount do
            rows += fields
              .map(name =>
                name -> Option(root.getVector(name).getObject(i)).map(_.toString).getOrElse("")
              )
              .toMap
        rows.toList
      finally stream.close()
    finally
      rawClient.close()
      allocator.close()

  /** Like [[queryFlight]] but retries on any failure until `timeout` elapses. Covers the brief
    * startup window where the FlightSQL edge is already accepting connections but the pool's Quack
    * node hasn't finished spawning yet.
    */
  private def queryFlightRetrying(
      port: Int,
      user: String,
      password: String,
      sql: String,
      timeout: FiniteDuration
  ): List[Map[String, String]] =
    val deadline           = System.nanoTime() + timeout.toNanos
    var lastErr: Throwable = null
    while System.nanoTime() < deadline do
      try return queryFlight(port, user, password, sql)
      catch
        case e: Throwable =>
          lastErr = e
          Thread.sleep(2000)
    throw new RuntimeException(s"query never succeeded within $timeout: sql='$sql'", lastErr)
