package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.BoundedBody
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import com.typesafe.scalalogging.Logger
import fs2.Stream
import org.http4s.{Header, Method, Request, Response, Status}
import org.http4s.ember.core.EmberException
import org.http4s.headers.`Content-Length`
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.duration.*

class NodeCallsSpec extends AnyFlatSpec with Matchers:

  private val Mac = "0" * 64

  /** A body that fails the call if anything pulls it. */
  private val Untouchable: Stream[IO, Byte] =
    Stream.raiseError[IO](new IllegalStateException("the body was read"))

  private def request(
      node: Option[String],
      signature: Option[String],
      body: Stream[IO, Byte] = Stream.emits("{}".getBytes.toList)
  ): Request[IO] =
    val headers = node.map(n => Header.Raw(CIString("X-QoD-Node"), n)).toList ++
      signature.map(s => Header.Raw(CIString("X-QoD-Signature"), s)).toList
    headers.foldLeft(Request[IO](Method.POST, uri"/x").withBodyStream(body))((r, h) =>
      r.putHeaders(h)
    )

  private def response(
      req: Request[IO],
      answered: AtomicInteger = new AtomicInteger(),
      bound: Int = 16
  ): Response[IO] =
    NodeCalls
      .serve(req, bound)((_, why) => (Status.Unauthorized, why)) { (_, _, _) =>
        answered.incrementAndGet()
        (Status.Ok, "ok")
      }
      .unsafeRunSync()

  private def serve(req: Request[IO], answered: AtomicInteger = new AtomicInteger()) =
    val resp = response(req, answered)
    (resp.status, resp.as[String].unsafeRunSync())

  private def closesConnection(resp: Response[IO]): Boolean =
    resp.headers
      .get(CIString("Connection"))
      .exists(_.exists(_.value.equalsIgnoreCase("close")))

  private def nested(levels: Int): Stream[IO, Byte] =
    Stream.emits(("[" * levels + "]" * levels).getBytes.toList)

  "NodeCalls.serve" should "refuse a call missing a header unread, closing the connection" in {
    val answered = new AtomicInteger()
    // The body stays unread, so the connection must not be kept: its unread bytes would be taken
    // for the next request.
    def refusal(req: Request[IO]): (Status, String, Boolean) =
      val resp = response(req, answered)
      (resp.status, resp.as[String].unsafeRunSync(), closesConnection(resp))
    refusal(request(None, Some(Mac), Untouchable)) shouldBe
      (Status.Unauthorized, NodeCalls.MissingHeaders, true)
    refusal(request(Some("quack-a-1"), None, Untouchable)) shouldBe
      (Status.Unauthorized, NodeCalls.MissingHeaders, true)
    refusal(request(Some("bad node"), Some(Mac), Untouchable)) shouldBe
      (Status.Unauthorized, NodeCalls.MissingHeaders, true)
    refusal(request(Some("quack-a-1"), Some(Mac.toUpperCase + "x"), Untouchable)) shouldBe
      (Status.Unauthorized, NodeCalls.MissingHeaders, true)
    answered.get() shouldBe 0
  }

  it should "refuse a declared length over the bound before the body streams" in {
    val req = request(Some("quack-a-1"), Some(Mac), Untouchable).putHeaders(`Content-Length`(17L))
    serve(req) shouldBe (Status.PayloadTooLarge, NodeCalls.BodyTooLarge)
  }

  it should "refuse an undeclared body over the bound, and answer one within it" in {
    serve(
      request(Some("quack-a-1"), Some(Mac), Stream.emits(Array.fill(17)('x'.toByte).toList))
    ) shouldBe
      (Status.PayloadTooLarge, NodeCalls.BodyTooLarge)
    serve(request(Some("quack-a-1"), Some(Mac))) shouldBe (Status.Ok, "ok")
  }

  it should "close the connection after refusing a body it left unread" in {
    val declared =
      request(Some("quack-a-1"), Some(Mac), Untouchable).putHeaders(`Content-Length`(17L))
    val over =
      request(Some("quack-a-1"), Some(Mac), Stream.emits(Array.fill(17)('x'.toByte).toList))
    for req <- List(declared, over) do
      val resp = response(req)
      resp.status shouldBe Status.PayloadTooLarge
      closesConnection(resp) shouldBe true
    // An answer to a body read in full keeps the connection as it was.
    closesConnection(response(request(Some("quack-a-1"), Some(Mac)))) shouldBe false
  }

  it should "refuse chunked framing before pulling the body" in {
    val answered = new AtomicInteger()
    val chunked  = request(Some("quack-a-1"), Some(Mac), Untouchable)
      .putHeaders(Header.Raw(CIString("Transfer-Encoding"), "chunked"))
    val resp = response(chunked, answered)
    (resp.status, resp.as[String].unsafeRunSync()) shouldBe
      (Status.LengthRequired, NodeCalls.ChunkedRefused)
    closesConnection(resp) shouldBe true
    // Without its headers a chunked call is refused as any other, unread.
    val headerless = request(None, Some(Mac), Untouchable)
      .putHeaders(Header.Raw(CIString("Transfer-Encoding"), "chunked"))
    serve(headerless, answered) shouldBe (Status.Unauthorized, NodeCalls.MissingHeaders)
    answered.get() shouldBe 0
  }

  it should "answer a body that stops arriving 408, closing the connection" in {
    val answered = new AtomicInteger()
    val stalled  = Stream.emits("{".getBytes.toList) ++
      Stream.raiseError[IO](EmberException.ReadTimeout(60.seconds))
    val resp = response(request(Some("quack-a-1"), Some(Mac), stalled), answered)
    (resp.status, resp.as[String].unsafeRunSync()) shouldBe
      (Status.RequestTimeout, NodeCalls.BodyTooSlow)
    closesConnection(resp) shouldBe true
    answered.get() shouldBe 0
  }

  it should "refuse JSON nested too deep without calling the route, and answer one at the limit" in {
    val answered = new AtomicInteger()
    val deep     = response(
      request(Some("quack-a-1"), Some(Mac), nested(BoundedBody.MaxNesting + 1)),
      answered,
      bound = 4096
    )
    (deep.status, deep.as[String].unsafeRunSync()) shouldBe
      (Status.BadRequest, NodeCalls.BodyTooDeep)
    closesConnection(deep) shouldBe true
    answered.get() shouldBe 0
    val limit = response(
      request(Some("quack-a-1"), Some(Mac), nested(BoundedBody.MaxNesting)),
      answered,
      bound = 4096
    )
    limit.status shouldBe Status.Ok
    answered.get() shouldBe 1
  }

  "the node-id bound" should "admit the longest id an earlier release composed, and today's" in {
    val tenant = "t" * 61
    // The composed tenant-db name is at most 63 characters.
    s"${tenant}_d".length shouldBe 63
    // A node that started under such an id keeps it (a respawn reuses the stored id), so the bound
    // must go on admitting it though `PoolSupervisor.nodeId` now stops at 63 characters.
    val earlier = s"quack-$tenant-$tenant-d-${"p" * 63}-${Int.MaxValue}"
    earlier.length shouldBe 206
    val today = PoolSupervisor.nodeId(PoolKey(tenant, s"${tenant}_d", "p" * 63), Int.MaxValue)
    List(earlier, today).foreach { longest =>
      longest.length should be <= NodeCalls.MaxNodeIdLength
      NodeCalls.NodeIdText.matches(longest) shouldBe true
    }
    NodeCalls.NodeIdText.matches("q" * NodeCalls.MaxNodeIdLength) shouldBe true
    NodeCalls.NodeIdText.matches("q" * (NodeCalls.MaxNodeIdLength + 1)) shouldBe false
  }

  "RefusalLog" should "warn on a node's first refusal and debug its repeats, within a cap" in {
    val name        = "ai.starlake.quack.edge.authz.NodeCallsSpec.refusals"
    val clock       = new AtomicLong(0L)
    val (_, events) = LogCapture.logged(name) {
      val log = new NodeCalls.RefusalLog(Logger(name), () => clock.get())
      log.refused("node-a", "a 1")
      log.refused("node-a", "a 2")
      log.refused("node-b", "b 1")
      (1 to NodeCalls.RefusalLog.MaxWarnsPerMinute).foreach(i => log.refused(s"n-$i", s"n $i"))
      clock.addAndGet(60_000L)
      log.refused("node-c", "c 1")
    }
    val levels = events.map(e => e.getFormattedMessage -> e.getLevel).toMap
    levels("a 1") shouldBe Level.WARN
    levels("a 2") shouldBe Level.DEBUG
    levels("b 1") shouldBe Level.WARN
    // node-a and node-b used two of the minute's warnings, so the last two flood ids are debug.
    val flood = (1 to NodeCalls.RefusalLog.MaxWarnsPerMinute).map(i => levels(s"n $i"))
    flood.count(_ == Level.WARN) shouldBe NodeCalls.RefusalLog.MaxWarnsPerMinute - 2
    flood.takeRight(2).toSet shouldBe Set(Level.DEBUG)
    levels("c 1") shouldBe Level.WARN
  }
