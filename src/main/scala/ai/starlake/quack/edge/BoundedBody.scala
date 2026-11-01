package ai.starlake.quack.edge

import cats.effect.IO
import fs2.{Chunk, Stream}
import org.http4s.Request

import scala.concurrent.duration.FiniteDuration

/** Reads a whole request body under a size and a time limit: what REST, MCP and the node routes do
  * before a route sees the body. Eager rather than a limiting stream: every route that reads a body
  * reads it whole anyway, and the refusals are then answered in one place, with no dependence on
  * how Tapir maps an exception raised while it decodes.
  *
  * A refused body stays unread. When any of it has not arrived yet, Ember then closes the
  * connection and does not read the rest (`Parser.Body`: its `drain` stays `None` until the body is
  * consumed), and the caller's answer should say `Connection: close` either way.
  */
object BoundedBody:

  enum Outcome:
    case Read(bytes: Array[Byte])
    case TooLarge
    case TimedOut

  /** The body of `req`, at most `maxBytes`, within `deadline`. A declared length over the limit is
    * refused before any read. The limit itself holds on the stream Ember frames, however the client
    * announced it: Ember and http4s' typed headers read `Content-Length: +32` or
    * `Transfer-Encoding: foo, chunked` differently, so nothing here may decide from the headers
    * that there is no body. A request with no body reads as empty at once.
    */
  def read(req: Request[IO], maxBytes: Long, deadline: FiniteDuration): IO[Outcome] =
    if req.contentLength.exists(_ > maxBytes) then IO.pure(Outcome.TooLarge)
    else
      req.body
        .take(maxBytes + 1)
        .compile
        .to(Array)
        .map(bytes =>
          if bytes.length.toLong > maxBytes then Outcome.TooLarge else Outcome.Read(bytes)
        )
        .timeoutTo(deadline, IO.pure(Outcome.TimedOut))

  /** `req` with `bytes` as its body: what a route reads once the listener read the original. */
  def replay(req: Request[IO], bytes: Array[Byte]): Request[IO] =
    req.withBodyStream(Stream.chunk(Chunk.array(bytes)))
