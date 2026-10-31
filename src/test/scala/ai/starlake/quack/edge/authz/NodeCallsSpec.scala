package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import com.typesafe.scalalogging.Logger
import fs2.Stream
import org.http4s.{Header, Method, Request, Status}
import org.http4s.headers.`Content-Length`
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

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

  private def serve(req: Request[IO], answered: AtomicInteger = new AtomicInteger()) =
    val resp = NodeCalls
      .serve(req, 16)((_, why) => (Status.Unauthorized, why)) { (_, _, _) =>
        answered.incrementAndGet()
        (Status.Ok, "ok")
      }
      .unsafeRunSync()
    (resp.status, resp.as[String].unsafeRunSync())

  "NodeCalls.serve" should "refuse a call missing a header without reading its body" in {
    val answered = new AtomicInteger()
    serve(request(None, Some(Mac), Untouchable), answered) shouldBe
      (Status.Unauthorized, NodeCalls.MissingHeaders)
    serve(request(Some("quack-a-1"), None, Untouchable), answered) shouldBe
      (Status.Unauthorized, NodeCalls.MissingHeaders)
    serve(request(Some("bad node"), Some(Mac), Untouchable), answered)._1 shouldBe
      Status.Unauthorized
    serve(
      request(Some("quack-a-1"), Some(Mac.toUpperCase + "x"), Untouchable),
      answered
    )._1 shouldBe
      Status.Unauthorized
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

  "the node-id bound" should "admit the longest id PoolSupervisor composes" in {
    val tenant  = "t" * 61
    val longest = PoolSupervisor.nodeId(
      PoolKey(tenant, s"${tenant}_d", "p" * 63),
      Int.MaxValue
    )
    // The composed tenant-db name is at most 63 characters.
    s"${tenant}_d".length shouldBe 63
    longest.length should be <= NodeCalls.MaxNodeIdLength
    NodeCalls.NodeIdText.matches(longest) shouldBe true
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
