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

import java.lang.ref.WeakReference
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

  private def pattern(n: Int): Array[Byte] = Array.tabulate[Byte](n)(i => (i * 7 + 1).toByte)

  it should "hand back every byte of a body cut into reads of one byte, and refuse one more" in:
    def trickled(n: Int): Stream[IO, Byte] =
      Stream.emits(pattern(n).toList).covary[IO].chunkLimit(1).unchunks
    run(request(trickled(16))) match
      case BoundedBody.Outcome.Read(b) => b.toList shouldBe pattern(16).toList
      case other                       => fail(s"expected Read, got $other")
    run(request(trickled(17))) shouldBe BoundedBody.Outcome.TooLarge

  it should "start from nothing each time the same read runs" in:
    val read = BoundedBody.read(request(bytes(8)), 16, 5.seconds)
    for _ <- 1 to 2 do
      read.unsafeRunSync() match
        case BoundedBody.Outcome.Read(b) => b.length shouldBe 8
        case other                       => fail(s"expected Read, got $other")

  it should "keep every byte, in order, across the arrays it holds a body in" in:
    val s = BoundedBody.SegmentBytes
    // Sizes on both sides of the points where one array fills and the next begins, read in one go
    // and cut into reads that start and end inside the arrays.
    val cases = for
      size <- List(1, 511, 512, 513, 1024, 1025, 4096, s - 1, s, s + 1, 2 * s, 3 * s + 5)
      cut  <- List(Int.MaxValue, 1000, 7)
      if cut != 7 || size <= 4096
    yield (size, cut)
    cases.foreach { (size, cut) =>
      withClue(s"a body of $size bytes in reads of at most $cut: ") {
        val body = Stream.emits(pattern(size).toList).covary[IO].chunkLimit(cut).unchunks
        run(request(body), max = 1L << 20) match
          case BoundedBody.Outcome.Read(b) =>
            b.length shouldBe size
            java.util.Arrays.mismatch(b, pattern(size)) shouldBe -1
          case other => fail(s"expected Read, got $other")
      }
    }

  /** A body of `reads` reads, each a view of `viewSize` bytes of an array of 1 MiB of its own, as
    * Ember cuts the chunks of a chunked body out of the socket read they came from. `arrays` holds
    * a weak reference to each array. Before it hands over a read, and again before it ends, the
    * body waits for the collector to clear the array of every read it handed over: a reader that
    * kept a read leaves its array standing, and the body then fails the read, naming the read it
    * waited for, once `patience` is up. So a reader that keeps its reads fails in `patience` rather
    * than hanging, and holds at most two arrays.
    */
  private final class ViewedBody(reads: Int, viewSize: Int, patience: FiniteDuration):
    private val Offset = 7
    private val arrays = new Array[WeakReference[Array[Byte]]](reads)

    private def clearedBefore(read: Int): Boolean = (0 until read).forall(arrays(_).get == null)

    private def awaitCleared(read: Int): IO[Unit] =
      IO.blocking {
        val until = System.nanoTime() + patience.toNanos
        while !clearedBefore(read) && System.nanoTime() < until do
          System.gc()
          Thread.sleep(20)
        if !clearedBefore(read) then
          throw new IllegalStateException(
            s"read $read was due, but an array of the reads before it was still held after $patience"
          )
      }

    val body: Stream[IO, Byte] =
      Stream
        .range(0, reads)
        .evalMap { i =>
          awaitCleared(i) *> IO {
            val big = new Array[Byte](1 << 20)
            java.util.Arrays.fill(big, Offset, Offset + viewSize, (i + 1).toByte)
            arrays(i) = new WeakReference(big)
            // Chunk.array would copy a short slice.
            Chunk.ArraySlice(big, Offset, viewSize)
          }
        }
        .flatMap(Stream.chunk) ++ Stream.exec(awaitCleared(reads))

    /** What the reader must hand back: view `i` is `viewSize` bytes of `i + 1`. */
    val expected: Array[Byte] = Array.tabulate(reads * viewSize)(k => (k / viewSize + 1).toByte)

  it should "keep no read it was handed" in:
    // A view of a large array stands for a chunk of a chunked body, cut from a whole socket read.
    // The stream keeps its next read waiting until every earlier array was seen to be collected.
    // A view shorter than 64 bytes is copied by fs2's collector anyway, so these are not shorter.
    for viewSize <- List(64, 1000) do
      withClue(s"views of $viewSize bytes: ") {
        val viewed = new ViewedBody(reads = 12, viewSize, patience = 5.seconds)
        run(request(viewed.body), max = 1L << 20, deadline = 2.minutes) match
          case BoundedBody.Outcome.Read(b) =>
            b.length shouldBe viewed.expected.length
            java.util.Arrays.mismatch(b, viewed.expected) shouldBe -1
          case other => fail(s"expected Read, got $other")
      }
