package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.{QuackResponse, TestArrow}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.policy.ProtectedWriteGuard
import org.apache.arrow.flight.{FlightCallHeaders, FlightRuntimeException, HeaderCallOption}
import org.apache.arrow.flight.sql.FlightSqlClient
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}

import java.util.Base64
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

/** The manager's graceful shutdown first makes the FlightSQL edge refuse new calls and lets the
  * running ones finish (#221). It used to call `stop()` there, which cancels every call still
  * running after 3 s, before the drain began: a statement running when the manager got SIGTERM
  * failed whatever the drain timeout said.
  */
class FlightEdgeDrainSpec extends AnyFlatSpec with Matchers with Eventually:

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds))

  private def credentials: HeaderCallOption =
    val secret = s"${SecurityFixtures.AliceUsername}:${SecurityFixtures.AlicePassword}"
    val hdrs   = new FlightCallHeaders()
    hdrs.insert("tenant", SecurityFixtures.TenantName)
    hdrs.insert("pool", SecurityFixtures.PoolName)
    hdrs.insert(
      "authorization",
      s"Basic ${Base64.getEncoder.encodeToString(secret.getBytes("UTF-8"))}"
    )
    new HeaderCallOption(hdrs)

  "refuseNewCalls" should "let a running statement finish and stream its rows, and refuse new calls" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    // The statement holds on the node, in its DoGet, until released; its schema probe does not.
    val node: String => QuackResponse = sql =>
      if sql.contains("drain_marker") && !sql.contains("_qod_probe") then
        entered.countDown()
        release.await(30, TimeUnit.SECONDS)
      TestArrow.okResponse()
    val h = FlightEdgeHarness.boot(
      SecurityFixtures.freshStore().store,
      wiring = FlightEdgeHarness.RouterWiring(
        nodeQuery = node,
        spawnNodes = true,
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = false),
        protectedWriteGuard = ProtectedWriteGuard.disabled
      )
    )
    given ExecutionContext = ExecutionContext.global
    try
      val client = h.newClient()
      try
        val info   = new FlightSqlClient(client).execute("SELECT 1 AS drain_marker", credentials)
        val ticket = info.getEndpoints.get(0).getTicket
        val rows   = Future {
          val stream = client.getStream(ticket, credentials)
          try
            var n = 0L
            while stream.next() do n += stream.getRoot.getRowCount
            n
          finally stream.close()
        }
        entered.await(30, TimeUnit.SECONDS) shouldBe true

        h.refuseNewCalls()
        h.callsInProgress() should be > 0
        val other = h.newClient()
        try
          intercept[FlightRuntimeException] {
            new FlightSqlClient(other).execute("SELECT 2", credentials)
          }
        finally other.close()

        release.countDown()
        Await.result(rows, 30.seconds) shouldBe 1L
        eventually(h.callsInProgress() shouldBe 0)
      finally
        release.countDown()
        client.close()
    finally h.shutdown()
  }
