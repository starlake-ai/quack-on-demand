package ai.starlake.quack.edge

import cats.effect.IO
import fs2.{Chunk, Stream}
import org.http4s.Request
import org.http4s.ember.core.EmberException
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import scala.annotation.tailrec
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

  /** The body of `req`, at most `maxBytes`, within `deadline`. A declared length over the limit is
    * refused before any read. The limit itself holds on the stream Ember frames, however the client
    * announced it: Ember and http4s' typed headers read `Content-Length: +32` or
    * `Transfer-Encoding: foo, chunked` differently, so nothing here may decide from the headers
    * that there is no body. A request with no body reads as empty at once.
    *
    * Each read is copied flat before it is kept: Ember cuts a chunked body's chunks as views of the
    * socket read they came from, so a client padding small chunks with chunk extensions would
    * otherwise pin a whole read for every few bytes the limit counts. Ember's own idle timeout on a
    * stalled body is the deadline passing too, as in `QuackBodyRead`.
    *
    * A body that nests past [[MaxNesting]] is `TooDeep`, whether a route reads it as UTF-8 or
    * decodes it in the charset the request declares.
    */
  def read(req: Request[IO], maxBytes: Long, deadline: FiniteDuration): IO[Outcome] =
    if req.contentLength.exists(_ > maxBytes) then IO.pure(Outcome.TooLarge)
    else
      req.body
        .take(maxBytes + 1)
        // A chunk of a chunked body is a view of the whole socket read it was cut from.
        .chunks
        .map(_.compact)
        .unchunks
        .compile
        .to(Array)
        .map(bytes =>
          if bytes.length.toLong > maxBytes then Outcome.TooLarge
          else if nestsTooDeep(req, bytes) then Outcome.TooDeep
          else Outcome.Read(bytes)
        )
        .recover { case _: EmberException.ReadTimeout => Outcome.TimedOut }
        .timeoutTo(deadline, IO.pure(Outcome.TimedOut))

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
