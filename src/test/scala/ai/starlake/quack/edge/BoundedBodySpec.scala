package ai.starlake.quack.edge

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.{Chunk, Stream}
import org.http4s.{Header, Method, Request, Uri}
import org.http4s.ember.core.EmberException
import org.http4s.headers.`Content-Length`
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

class BoundedBodySpec extends AnyFlatSpec with Matchers:

  private def request(body: Stream[IO, Byte], declared: Option[Long] = None): Request[IO] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString("/x")).withBodyStream(body)
    declared.fold(base)(n => base.putHeaders(`Content-Length`.unsafeFromLong(n)))

  private def bytes(n: Int): Stream[IO, Byte] = Stream.chunk(Chunk.array(Array.fill[Byte](n)(1)))

  private def run(req: Request[IO], max: Long = 16, deadline: FiniteDuration = 5.seconds) =
    BoundedBody.read(req, max, deadline).unsafeRunSync()

  "read" should "hand back a body at exactly the limit" in:
    run(request(bytes(16))) match
      case BoundedBody.Outcome.Read(b) => b.length shouldBe 16
      case other                       => fail(s"expected Read, got $other")

  it should "refuse one byte over the limit" in:
    run(request(bytes(17))) shouldBe BoundedBody.Outcome.TooLarge

  it should "refuse a declared length over the limit without reading" in:
    val touched = new AtomicBoolean(false)
    val body    = Stream.eval(IO(touched.set(true))).drain ++ bytes(1)
    run(request(body, declared = Some(17L))) shouldBe BoundedBody.Outcome.TooLarge
    touched.get shouldBe false

  it should "stop reading an endless body at the limit" in:
    run(request(Stream.constant[IO, Byte](1.toByte, 1024))) shouldBe BoundedBody.Outcome.TooLarge

  it should "give up on a body that stops arriving" in:
    val stuck = bytes(1) ++ Stream.eval(IO.never[Byte])
    run(request(stuck), deadline = 100.millis) shouldBe BoundedBody.Outcome.TimedOut

  it should "answer Ember's idle timeout on a stalled body as a timeout" in:
    val idle = EmberException.ReadTimeout(60.seconds)
    run(request(bytes(1) ++ Stream.raiseError[IO](idle))) shouldBe BoundedBody.Outcome.TimedOut

  it should "read an empty body at once" in:
    run(request(Stream.empty)) match
      case BoundedBody.Outcome.Read(b) => b.length shouldBe 0
      case other                       => fail(s"expected Read, got $other")

  private def encoded(value: String, name: String = "Transfer-Encoding"): Request[IO] =
    request(bytes(1)).putHeaders(Header.Raw(CIString(name), value))

  "asksChunked" should "see Transfer-Encoding: chunked, however the header is spelled" in:
    BoundedBody.asksChunked(encoded("chunked")) shouldBe true
    BoundedBody.asksChunked(encoded("CHUNKED", "transfer-encoding")) shouldBe true

  it should "see a list of codings that ends in chunked" in:
    BoundedBody.asksChunked(encoded("foo, chunked")) shouldBe true

  it should "see no such header" in:
    BoundedBody.asksChunked(request(bytes(1))) shouldBe false
    BoundedBody.asksChunked(request(bytes(1), declared = Some(1L))) shouldBe false

  "replay" should "give a route the bytes already read" in:
    val replayed = BoundedBody.replay(request(bytes(1)), Array[Byte](1, 2, 3))
    replayed.body.compile.to(Array).unsafeRunSync().toList shouldBe List[Byte](1, 2, 3)
