package ai.starlake.quack.edge

import cats.effect.IO
import fs2.{Chunk, Stream}
import org.http4s.Request
import org.http4s.ember.core.EmberException
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration

/** Reads a whole request body under a size and a time limit: what REST, MCP and the node routes do
  * before a route sees the body. Eager rather than a limiting stream: every route that reads a body
  * reads it whole anyway, and the refusals are then answered in one place, with no dependence on
  * how Tapir maps an exception raised while it decodes. The same read refuses a body whose JSON
  * nests deeper than [[MaxNesting]], for the same reason: the decode is where it would fail, and
  * nothing of ours can catch it there.
  *
  * A refused body stays unread. When any of it has not arrived yet, Ember then closes the
  * connection and does not read the rest (`Parser.Body`: its `drain` stays `None` until the body is
  * consumed), and the caller's answer should say `Connection: close` either way.
  */
object BoundedBody:

  enum Outcome:
    case Read(bytes: Array[Byte])
    case TooLarge
    case TooDeep
    case TimedOut

  /** How deep a body's JSON may nest. No client needs more, and the check is what stands between a
    * small anonymous body and a halted JVM: circe's failure message prints the value it refused,
    * its printer recurses once per level, and a `StackOverflowError` is fatal to cats-effect (see
    * `ai.starlake.sql.TooDeep`). The decode runs inside Tapir, where nothing of ours can catch it,
    * and 8 KB is enough, so no size limit helps.
    */
  val MaxNesting: Int = 256

  /** Bytes a listener reads from a socket at once. Ember allocates this per connection on the first
    * read and keeps it, whatever the connection sends, and an out-of-memory error is fatal to the
    * manager. So this sets what a flood of connections holds: at Ember's default of 256 KiB, 1024
    * idle connections hold 262 MiB of heap. A large body only takes more reads.
    */
  val ReadBufferBytes: Int = 32 * 1024

  /** The largest array a body is held in while it is read. G1 gives an array of half a region or
    * more (512 KiB with the default heap of a 4 GiB host) whole regions to itself, so one array
    * growing to hold a body of 1 MiB costs 2 MiB of heap while the client stalls (measured), and
    * the node verdict listener takes such bodies from clients with no key. Arrays of this size cost
    * what they hold.
    */
  private[edge] val SegmentBytes: Int = 64 * 1024

  /** The bytes of a body so far. Each read is copied into arrays that start small and grow with the
    * body up to [[SegmentBytes]], so a client sending small packets costs no wrapper per read and a
    * short body costs no more than itself, and nothing of a read is kept: a chunk of a chunked body
    * is a view pinning the whole socket read it was cut from. The one array a route gets is made by
    * [[toByteArray]], once the body is complete.
    */
  private final class Kept:
    private val full: ArrayBuffer[Array[Byte]] = ArrayBuffer.empty
    private var current: Array[Byte]           = Array.emptyByteArray
    private var filled: Int                    = 0
    private var held: Long                     = 0L

    def size: Long = held

    def add(from: Array[Byte], offset: Int, length: Int): Unit =
      @tailrec def go(offset: Int, length: Int): Unit =
        if length > 0 then
          if filled == current.length then
            full += current
            current = new Array[Byte](math.min(SegmentBytes.toLong, math.max(512L, held)).toInt)
            filled = 0
          val n = math.min(length, current.length - filled)
          System.arraycopy(from, offset, current, filled, n)
          filled += n
          held += n
          go(offset + n, length - n)
      go(offset, length)

    def toByteArray: Array[Byte] =
      val out = new Array[Byte](held.toInt)
      val at  = full.foldLeft(0) { (at, segment) =>
        System.arraycopy(segment, 0, out, at, segment.length)
        at + segment.length
      }
      System.arraycopy(current, 0, out, at, filled)
      out

  /** The body of `req`, at most `maxBytes`, within `deadline`. A declared length over the limit is
    * refused before any read. The limit itself holds on the stream Ember frames, however the client
    * announced it: Ember and http4s' typed headers read `Content-Length: +32` or
    * `Transfer-Encoding: foo, chunked` differently, so nothing here may decide from the headers
    * that there is no body. A request with no body reads as empty at once.
    *
    * Each read goes into [[Kept]] as it arrives and nothing of it is kept. A chunk kept per read
    * costs a wrapper per read, about 3 heap bytes per body byte for a client sending small packets,
    * before any credential. And Ember cuts a chunked body's chunks as views of the socket read they
    * came from, so a client padding small chunks with chunk extensions would otherwise pin a whole
    * read for every few bytes the limit counts. Ember's own idle timeout on a stalled body is the
    * deadline passing too, as in `QuackBodyRead`.
    *
    * A body that nests past [[MaxNesting]] is `TooDeep`, whether a route reads it as UTF-8 or
    * decodes it in the charset the request declares.
    */
  def read(req: Request[IO], maxBytes: Long, deadline: FiniteDuration): IO[Outcome] =
    if req.contentLength.exists(_ > maxBytes) then IO.pure(Outcome.TooLarge)
    else
      // A Kept for each run: `fold` takes its starting value once.
      IO.defer {
        req.body
          .take(maxBytes + 1)
          .chunks
          .compile
          .fold(new Kept()) { (kept, read) =>
            val flat = read.toArraySlice
            kept.add(flat.values, flat.offset, flat.size)
            kept
          }
          .map(kept =>
            if kept.size > maxBytes then Outcome.TooLarge
            else
              val bytes = kept.toByteArray
              if nestsTooDeep(req, bytes) then Outcome.TooDeep else Outcome.Read(bytes)
          )
          .recover { case _: EmberException.ReadTimeout => Outcome.TimedOut }
          .timeoutTo(deadline, IO.pure(Outcome.TimedOut))
      }

  /** Whether `bytes` nest past [[MaxNesting]] for any route: as UTF-8, which is what the routes
    * reading bytes see, and in the charset the request declares, which is what Tapir decodes with
    * (an IBM037 body holds no `[` byte at all).
    */
  private def nestsTooDeep(req: Request[IO], bytes: Array[Byte]): Boolean =
    nestsTooDeep(bytes) || req.charset
      .map(_.nioCharset)
      .filter(_ != UTF_8)
      .exists(charset => nestsTooDeep(new String(bytes, charset).getBytes(UTF_8)))

  /** Whether the UTF-8 `text` opens more than [[MaxNesting]] arrays or objects at once. It reads
    * JSON's strings as a parser does, so on any text a parser accepts the two agree, and where they
    * could disagree the parser has already refused it. A UTF-8 multi-byte sequence holds no ASCII
    * byte, so the bytes can be read directly. A closer with nothing open banks nothing.
    */
  private def nestsTooDeep(text: Array[Byte]): Boolean =
    @tailrec def scan(i: Int, depth: Int, inString: Boolean): Boolean =
      if i >= text.length then false
      else
        text(i).toChar match
          case '\\' if inString => scan(i + 2, depth, true)
          case '"'              => scan(i + 1, depth, !inString)
          case _ if inString    => scan(i + 1, depth, true)
          case '[' | '{'        =>
            if depth == MaxNesting then true else scan(i + 1, depth + 1, false)
          case ']' | '}' => scan(i + 1, math.max(depth - 1, 0), false)
          case _         => scan(i + 1, depth, false)
    scan(0, 0, false)

  /** Whether the client asks for chunked framing, read from the raw header so that it covers every
    * spelling Ember decodes. Ember rescans a chunk-size line on every read, up to 40 KiB of it, and
    * that line yields no data byte for a limit to count, so a client trickling one burns about a
    * core per connection until the deadline. So what reads a body before any credential refuses
    * such a request with 411, before pulling the body.
    */
  def asksChunked(req: Request[IO]): Boolean =
    req.headers.get(CIString("Transfer-Encoding")).isDefined

  /** `req` with `bytes` as its body: what a route reads once the listener read the original. */
  def replay(req: Request[IO], bytes: Array[Byte]): Request[IO] =
    req.withBodyStream(Stream.chunk(Chunk.array(bytes)))
