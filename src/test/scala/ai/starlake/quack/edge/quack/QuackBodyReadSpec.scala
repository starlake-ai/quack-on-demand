package ai.starlake.quack.edge.quack

import ai.starlake.quack.QuackNativeConfig
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import org.http4s.{Method, Request}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `QuackBodyRead` on a body that arrives one byte per chunk, the worst case for its bookkeeping:
  * what it admits must be exactly what was sent, and a limit must hold to the byte, however the
  * body is cut into reads.
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

  private def read(bytes: Array[Byte], c: QuackNativeConfig): QuackBodyRead.Outcome =
    QuackBodyRead(trickled(bytes), c, _ == LiveSession).unsafeRunSync()

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

  "QuackNativeConfig" should "refuse a body timeout below its connect timeout" in:
    val e = the[IllegalArgumentException] thrownBy cfg(connectTimeout = 10, bodyTimeout = 5)
    e.getMessage should include("QOD_QUACK_CONNECT_BODY_TIMEOUT_SEC")
    e.getMessage should include("QOD_QUACK_BODY_TIMEOUT_SEC")

  it should "accept equal timeouts, and refuse a body timeout over a day" in:
    noException should be thrownBy cfg(connectTimeout = 5, bodyTimeout = 5)
    noException should be thrownBy cfg(connectTimeout = 5, bodyTimeout = 86400)
    an[IllegalArgumentException] should be thrownBy cfg(connectTimeout = 5, bodyTimeout = 86401)
