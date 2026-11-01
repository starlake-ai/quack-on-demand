package ai.starlake.quack.edge.quack

import ai.starlake.quack.QuackNativeConfig
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.{Chunk, Stream}
import org.http4s.{Method, Request}
import org.http4s.ember.core.EmberException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.ref.WeakReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration.*

/** `QuackBodyRead` on a body that arrives one byte per chunk, the worst case for its bookkeeping:
  * what it admits must be exactly what was sent, and a limit must hold to the byte, however the
  * body is cut into reads. Also on reads that are views of larger arrays, which the read must not
  * keep alive.
  */
class QuackBodyReadSpec extends AnyFlatSpec with Matchers:

  private val LiveSession = "live-session"

  private def cfg(
      maxBody: Long = 1 << 20,
      maxConnect: Long = 65536,
      connectTimeout: Long = 5,
      bodyTimeout: Long = 10
  ): QuackNativeConfig =
    QuackNativeConfig(
      enabled = true,
      host = "127.0.0.1",
      port = 0,
      tlsEnabled = false,
      tlsCertChain = "",
      tlsPrivateKey = "",
      maxHeartbeatTimeoutSec = 3600,
      maxBodyBytes = maxBody,
      maxConnectBodyBytes = maxConnect,
      connectBodyTimeoutSec = connectTimeout,
      bodyTimeoutSec = bodyTimeout
    )

  /** A POST that announces no length and whose body arrives one byte per chunk. */
  private def trickled(bytes: Array[Byte]): Request[IO] =
    Request[IO](Method.POST, body = Stream.emits(bytes).covary[IO].chunkLimit(1).unchunks)

  /** A POST that announces no length and whose reads are `parts`, each one chunk. */
  private def cut(parts: Array[Byte]*): Request[IO] =
    Request[IO](
      Method.POST,
      body = Stream.emits(parts.map(Chunk.array(_))).covary[IO].flatMap(Stream.chunk)
    )

  /** A POST that announces no length, whose body is one read and then goes quiet. */
  private def oneRead(bytes: Array[Byte]): Request[IO] =
    Request[IO](Method.POST, body = Stream.chunk(Chunk.array(bytes)) ++ Stream.never[IO])

  /** A POST that announces no length, whose body sends `first` and then fails with `failure`. */
  private def failingAfter(first: Array[Byte], failure: Throwable): Request[IO] =
    Request[IO](
      Method.POST,
      body = Stream.chunk(Chunk.array(first)) ++ Stream.raiseError[IO](failure)
    )

  private def read(req: Request[IO], c: QuackNativeConfig): QuackBodyRead.Outcome =
    QuackBodyRead(req, c, _ == LiveSession).unsafeRunSync()

  private def read(bytes: Array[Byte], c: QuackNativeConfig): QuackBodyRead.Outcome =
    read(trickled(bytes), c)

  /** A body that sends `prefix`, then `views` single bytes, each a view of an array of `arraySize`
    * bytes of its own (as Ember frames the chunks of a chunked body), and then goes quiet. Nothing
    * but the view keeps an array alive: `arrays` holds a weak reference to each, and `sent` opens
    * once every view has been handed over.
    */
  private final class ViewedBody(prefix: Array[Byte], views: Int, arraySize: Int):
    val arrays = new Array[WeakReference[Array[Byte]]](views)
    val sent   = new CountDownLatch(1)

    def request: Request[IO] =
      val viewed = Stream.range(0, views).evalMap { i =>
        IO {
          val big = new Array[Byte](arraySize)
          arrays(i) = new WeakReference(big)
          // Chunk.array would turn a one byte slice into a copy.
          Chunk.ArraySlice(big, 7, 1)
        }
      }
      Request[IO](
        Method.POST,
        body = Stream.chunk(Chunk.array(prefix)) ++ viewed.flatMap(Stream.chunk) ++
          Stream.exec(IO(sent.countDown())) ++ Stream.never[IO]
      )

  /** Reads `body`, which stays open, and waits for the GC to clear what it can: how many of the
    * arrays were cleared, and whether the read had ended. A read that keeps the arrays its views
    * point into clears none of them.
    */
  private def clearedWhileOpen(body: ViewedBody): (Int, Boolean) =
    val c                 = cfg(connectTimeout = 120, bodyTimeout = 240)
    val (outcome, cancel) =
      QuackBodyRead(body.request, c, _ == LiveSession).unsafeToFutureCancelable()
    try
      body.sent.await(10, TimeUnit.SECONDS) shouldBe true
      val until   = System.nanoTime() + 5.seconds.toNanos
      def cleared = body.arrays.count(_.get == null)
      while cleared < body.arrays.length - 1 && System.nanoTime() < until do
        System.gc()
        Thread.sleep(50)
      (cleared, outcome.isCompleted)
    finally Await.ready(cancel(), 10.seconds)

  private val connectMessage: Array[Byte] =
    QuackWire.encodeConnectionRequest(
      QuackWire.ConnectionRequest("t=1", "v2.0.0", "osx_arm64", 3L, 3L, "c", 0L),
      1L
    )

  private val sessionHeaderLength: Int = QuackWire.encodeDisconnect(LiveSession).length

  /** A message for the live session: its header, then `extra` bytes that all differ from their
    * neighbours, so a byte lost, repeated or moved shows.
    */
  private def sessionMessage(extra: Int): Array[Byte] =
    QuackWire.encodeDisconnect(LiveSession) ++ Array.tabulate[Byte](extra)(i => (i * 31 + 7).toByte)

  /** An outcome without its bytes, so a failure does not print a whole body. */
  private def describe(outcome: QuackBodyRead.Outcome): String =
    outcome match
      case QuackBodyRead.Outcome.Admitted(bytes)     => s"Admitted(${bytes.length} bytes)"
      case QuackBodyRead.Outcome.NotAdmitted(prefix) => s"NotAdmitted(${prefix.length} bytes)"
      case QuackBodyRead.Outcome.TooLarge(limit)     => s"TooLarge($limit)"
      case QuackBodyRead.Outcome.TimedOut            => "TimedOut"

  private def admitted(outcome: QuackBodyRead.Outcome): Array[Byte] =
    outcome match
      case QuackBodyRead.Outcome.Admitted(bytes) => bytes
      case other => fail(s"expected Admitted, got ${describe(other)}")

  private def notAdmitted(outcome: QuackBodyRead.Outcome): Array[Byte] =
    outcome match
      case QuackBodyRead.Outcome.NotAdmitted(prefix) => prefix
      case other => fail(s"expected NotAdmitted, got ${describe(other)}")

  /** Equal bytes, with a failure that names the first difference rather than printing the arrays.
    */
  private def shouldBeSame(actual: Array[Byte], expected: Array[Byte]): Unit =
    actual.length shouldBe expected.length
    java.util.Arrays.mismatch(actual, expected) shouldBe -1

  "the test body" should "arrive one byte per chunk" in:
    val sizes = trickled(connectMessage).body.chunks.map(_.size).compile.toList.unsafeRunSync()
    sizes shouldBe List.fill(connectMessage.length)(1)

  "QuackBodyRead" should "admit a CONNECTION_REQUEST with exactly the bytes sent" in:
    shouldBeSame(admitted(read(connectMessage, cfg())), connectMessage)

  it should "admit a live session's message larger than a small read with exactly the bytes sent" in:
    val msg = sessionMessage(2 * QuackBodyRead.SmallReadBytes + 123)
    msg.length should be > QuackBodyRead.SmallReadBytes
    shouldBeSame(admitted(read(msg, cfg())), msg)

  it should "keep every byte, in order, on both sides of the merge boundary" in:
    val s = QuackBodyRead.SmallReadBytes
    // Wide enough to hold the exact boundary, whatever follows the header's last field.
    (Seq(0, 1, 3 * s) ++ ((s - 4) to (s + 2))).foreach { extra =>
      withClue(s"a message with $extra bytes after its header: ") {
        val msg = sessionMessage(extra)
        shouldBeSame(admitted(read(msg, cfg())), msg)
      }
    }

  it should "refuse a CONNECTION_REQUEST one byte over its limit, with no announced length" in:
    val limit = connectMessage.length.toLong
    shouldBeSame(admitted(read(connectMessage, cfg(maxConnect = limit))), connectMessage)
    describe(read(connectMessage :+ 0.toByte, cfg(maxConnect = limit))) shouldBe s"TooLarge($limit)"

  it should "refuse a live session's message one byte over its limit, with no announced length" in:
    // Past a small read and off its multiples, so the byte that crosses the limit arrives while the
    // reads before it have not been merged yet.
    val limit   = (QuackBodyRead.SmallReadBytes + 905).toLong
    val atLimit = sessionMessage(limit.toInt - sessionHeaderLength)
    val over    = sessionMessage(limit.toInt - sessionHeaderLength + 1)
    atLimit.length.toLong shouldBe limit
    shouldBeSame(admitted(read(atLimit, cfg(maxBody = limit))), atLimit)
    describe(read(over, cfg(maxBody = limit))) shouldBe s"TooLarge($limit)"

  it should "hold a body of unannounced length to its limit, however its reads are cut" in:
    val c = cfg(maxConnect = 128)
    // A connect within the limit, as one read and as two.
    shouldBeSame(admitted(read(cut(connectMessage), c)), connectMessage)
    shouldBeSame(
      admitted(read(cut(connectMessage.take(3), connectMessage.drop(3)), c)),
      connectMessage
    )
    // The header is within the limit; the read after it takes the message past it.
    describe(read(cut(connectMessage, new Array[Byte](1024)), c)) shouldBe "TooLarge(128)"

  it should "refuse at once a message over its limit that arrives in one read, with no announced length" in:
    // The body stays open after that read, so only the limit check on the bytes already held can
    // answer before the deadline, which would end the read as TimedOut instead.
    val c = cfg(maxBody = 512, maxConnect = 128, connectTimeout = 2, bodyTimeout = 4)
    describe(read(oneRead(connectMessage ++ new Array[Byte](1024)), c)) shouldBe "TooLarge(128)"
    describe(read(oneRead(sessionMessage(1024)), c)) shouldBe "TooLarge(512)"

  it should "not keep the arrays that reads of a message body are views of" in:
    // 200 single bytes, each a view of 256 KiB: held, they would pin 50 MiB.
    val body = new ViewedBody(QuackWire.encodeDisconnect(LiveSession), 200, 256 * 1024)
    val (cleared, finished) = clearedWhileOpen(body)
    finished shouldBe false
    withClue(s"$cleared of ${body.arrays.length} arrays were cleared: ") {
      cleared should be >= body.arrays.length - 1
    }

  it should "not keep the arrays that reads of a header are views of" in:
    // Field 2 (connection id) announcing 1 MiB: the header never completes, and stays a peek.
    val unfinished          = Array[Byte](2, 0, 0x80.toByte, 0x80.toByte, 0x40)
    val body                = new ViewedBody(unfinished, 200, 256 * 1024)
    val (cleared, finished) = clearedWhileOpen(body)
    finished shouldBe false
    withClue(s"$cleared of ${body.arrays.length} arrays were cleared: ") {
      cleared should be >= body.arrays.length - 1
    }

  it should "stop at the peek limit on a header that never completes, keeping the bytes read" in:
    // Field 2 (connection id) announcing 1 MiB, of which only a little over the limit ever comes.
    val unfinished = Array[Byte](2, 0, 0x80.toByte, 0x80.toByte, 0x40) ++
      Array.fill[Byte](QuackBodyRead.PeekMaxBytes + 1000)(97)
    shouldBeSame(
      notAdmitted(read(unfinished, cfg())),
      unfinished.take(QuackBodyRead.PeekMaxBytes)
    )

  it should "answer a body that ends inside its header from the bytes sent" in:
    val header = QuackWire.encodeDisconnect(LiveSession)
    Seq(0, 1, 5).foreach { n =>
      withClue(s"a body of the first $n bytes of a header: ") {
        shouldBeSame(notAdmitted(read(header.take(n), cfg())), header.take(n))
      }
    }

  it should "answer Ember's idle timeout on a stalled body as a timeout" in:
    // Ember fails a body that waited its idle timeout (60 s) for the client with this exception,
    // before a longer deadline of ours is up.
    val idle = EmberException.ReadTimeout(60.seconds)
    // Inside the header, and inside a live session's body.
    describe(read(failingAfter(sessionMessage(0).take(3), idle), cfg())) shouldBe "TimedOut"
    describe(read(failingAfter(sessionMessage(8), idle), cfg())) shouldBe "TimedOut"
    // Any other failure of the body stays a failure.
    an[EmberException.ReachedEndOfStream] should be thrownBy
      read(failingAfter(sessionMessage(8), EmberException.ReachedEndOfStream()), cfg())

  "QuackNativeConfig" should "refuse a body timeout below its connect timeout" in:
    val e = the[IllegalArgumentException] thrownBy cfg(connectTimeout = 10, bodyTimeout = 5)
    e.getMessage should include("QOD_QUACK_CONNECT_BODY_TIMEOUT_SEC")
    e.getMessage should include("QOD_QUACK_BODY_TIMEOUT_SEC")

  it should "accept equal timeouts, and refuse a body timeout over a day" in:
    noException should be thrownBy cfg(connectTimeout = 5, bodyTimeout = 5)
    noException should be thrownBy cfg(connectTimeout = 5, bodyTimeout = 86400)
    an[IllegalArgumentException] should be thrownBy cfg(connectTimeout = 5, bodyTimeout = 86401)
