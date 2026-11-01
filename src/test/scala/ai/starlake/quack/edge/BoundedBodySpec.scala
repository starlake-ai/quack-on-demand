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

import java.nio.charset.{Charset, StandardCharsets}
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

  private def nested(levels: Int): String = "[" * levels + "]" * levels

  private def objects(levels: Int): String = "{\"a\":" * levels + "1" + "}" * levels

  private def reading(
      text: String,
      contentType: Option[String] = None,
      charset: Charset = StandardCharsets.UTF_8
  ): BoundedBody.Outcome =
    val base = request(Stream.chunk(Chunk.array(text.getBytes(charset))))
    val req  =
      contentType.fold(base)(t => base.putHeaders(Header.Raw(CIString("Content-Type"), t)))
    run(req, max = 1L << 20)

  private def shouldBeRead(outcome: BoundedBody.Outcome): Unit =
    outcome match
      case BoundedBody.Outcome.Read(_) => succeed
      case other                       => fail(s"expected Read, got $other")

  "read" should "hand back JSON nested to the limit" in:
    shouldBeRead(reading(nested(BoundedBody.MaxNesting)))
    shouldBeRead(reading(objects(BoundedBody.MaxNesting)))

  it should "refuse JSON nested past the limit" in:
    val past = BoundedBody.MaxNesting + 1
    reading(nested(past)) shouldBe BoundedBody.Outcome.TooDeep
    reading(objects(past)) shouldBe BoundedBody.Outcome.TooDeep
    reading("{\"a\":" + nested(past - 1) + "}") shouldBe BoundedBody.Outcome.TooDeep
    reading("[" * past) shouldBe BoundedBody.Outcome.TooDeep
    reading(nested(32000)) shouldBe BoundedBody.Outcome.TooDeep

  it should "count only the levels open at once" in:
    shouldBeRead(reading("[" + "[[]]," * 1000 + "[]]"))
    // A closer with nothing open banks nothing.
    shouldBeRead(reading("]" * 1000 + nested(BoundedBody.MaxNesting)))
    reading("]" * 1000 + nested(BoundedBody.MaxNesting + 1)) shouldBe BoundedBody.Outcome.TooDeep

  it should "not count brackets inside a JSON string" in:
    shouldBeRead(reading("\"" + "[" * 1000 + "\""))
    // An escaped quote does not end the string.
    shouldBeRead(reading("\"\\\"" + "[" * 1000 + "\""))
    // An escaped backslash does not escape the quote after it, so this string ends there.
    reading("\"\\\\\"" + nested(BoundedBody.MaxNesting + 1)) shouldBe BoundedBody.Outcome.TooDeep

  it should "read a declared charset both as a route decoding it and as one reading UTF-8" in:
    assume(Charset.isSupported("IBM037"), "the JDK's extended charsets are required")
    val past     = nested(BoundedBody.MaxNesting + 1)
    val ibm037   = Charset.forName("IBM037")
    val declared = Some("application/json; charset=IBM037")
    // This body holds no '[' byte: only its decoded text nests.
    past.getBytes(ibm037).count(_ == '['.toByte) shouldBe 0
    reading(past, declared, ibm037) shouldBe BoundedBody.Outcome.TooDeep
    reading(past, Some("application/json; charset=UTF-16"), StandardCharsets.UTF_16) shouldBe
      BoundedBody.Outcome.TooDeep
    // This one nests only for a route that reads UTF-8 whatever the header says.
    reading(past, declared) shouldBe BoundedBody.Outcome.TooDeep
    shouldBeRead(reading(nested(8), declared, ibm037))
