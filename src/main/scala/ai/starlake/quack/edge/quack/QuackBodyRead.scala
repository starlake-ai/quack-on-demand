package ai.starlake.quack.edge.quack

import ai.starlake.quack.QuackNativeConfig
import ai.starlake.quack.edge.quack.QuackWire.HeaderPeek
import cats.effect.{IO, Ref}
import fs2.Chunk
import org.http4s.Request
import org.http4s.ember.core.EmberException

import scala.concurrent.duration.*

/** How `POST /quack` reads a body: the message header first, then as much as that header earns. A
  * CONNECTION_REQUEST gets `maxConnectBodyBytes`, since no credential has been checked yet. A
  * message naming a live session gets `maxBodyBytes`. Anything else (malformed, an unknown
  * connection id) is not read further: the dispatcher answers it from the bytes already here,
  * because it checks the header before it decodes a body.
  */
private[quack] object QuackBodyRead:

  /** A header that has not completed within this many bytes is malformed: a real one is under 100.
    */
  val PeekMaxBytes: Int = 4096

  enum Outcome:
    case Admitted(bytes: Array[Byte])
    case NotAdmitted(prefix: Array[Byte])
    case TooLarge(limit: Long)
    case TimedOut

  /** Reads are copied into one flat array until this many bytes are waiting. A read may be a view
    * of a much larger socket buffer, and a client sending one byte per packet would otherwise cost
    * about 40 bytes of heap per byte, one chunk object per read, before any credential.
    */
  val SmallReadBytes: Int = 4096

  private enum State:
    case Peeking(seen: Chunk[Byte])
    // `seen` holds merged arrays, `small` the reads not merged yet.
    case Reading(seen: Chunk[Byte], small: Chunk[Byte], limit: Long, sessionBound: Boolean)
    case Done(outcome: Outcome)

  private def admit(
      cfg: QuackNativeConfig,
      isLive: String => Boolean,
      declared: Option[Long],
      header: QuackWire.Header,
      seen: Chunk[Byte]
  ): State =
    val limit: Option[(Long, Boolean)] =
      if header.msgType == QuackWire.Type.ConnectionRequest then
        Some((cfg.maxConnectBodyBytes, false))
      else if isLive(header.connectionId) then Some((cfg.maxBodyBytes, true))
      else None
    limit match
      case None                      => State.Done(Outcome.NotAdmitted(seen.toArray))
      case Some((max, sessionBound)) =>
        if declared.exists(_ > max) || seen.size.toLong > max then State.Done(Outcome.TooLarge(max))
        else State.Reading(seen.compact, Chunk.empty, max, sessionBound)

  private def step(
      cfg: QuackNativeConfig,
      isLive: String => Boolean,
      declared: Option[Long]
  )(state: State, chunk: Chunk[Byte]): State =
    state match
      case State.Peeking(seen) =>
        // Flat, not a queue of reads: the header is parsed again on every read, before any
        // credential.
        val bytes = (seen ++ chunk).toArray
        val now   = Chunk.array(bytes)
        QuackWire.peekHeader(bytes) match
          case HeaderPeek.Complete(h) => admit(cfg, isLive, declared, h, now)
          case HeaderPeek.NeedMore if bytes.length < PeekMaxBytes => State.Peeking(now)
          case _ => State.Done(Outcome.NotAdmitted(bytes))
      case State.Reading(seen, small, limit, sessionBound) =>
        // Copied now, not at the flush: `chunk` may be a view pinning a whole socket buffer.
        val merged = (small ++ chunk).compact
        if seen.size.toLong + merged.size > limit then State.Done(Outcome.TooLarge(limit))
        else if merged.size < SmallReadBytes then State.Reading(seen, merged, limit, sessionBound)
        else State.Reading(seen ++ merged, Chunk.empty, limit, sessionBound)
      case done => done

  /** The outcome of reading `req`'s body. The clock starts now: `connectBodyTimeoutSec` for the
    * header and a CONNECTION_REQUEST, `bodyTimeoutSec` in all once the header names a live session.
    * Ember's idle timeout (`ReadTimeout`: 60 s without a byte from the client) can fail the body
    * before a longer deadline of ours: that is a `TimedOut` too, since an error would reach Ember
    * as a bare 500.
    */
  def apply(req: Request[IO], cfg: QuackNativeConfig, isLive: String => Boolean): IO[Outcome] =
    Ref.of[IO, Boolean](false).flatMap { sessionBound =>
      val read: IO[Outcome] =
        req.body.chunks
          .scan[State](State.Peeking(Chunk.empty))(step(cfg, isLive, req.contentLength))
          .evalTap {
            case State.Reading(_, _, _, true) => sessionBound.set(true)
            case _                            => IO.unit
          }
          .takeThrough {
            case State.Done(_) => false
            case _             => true
          }
          .compile
          .last
          .map {
            case Some(State.Done(outcome))              => outcome
            case Some(State.Reading(seen, small, _, _)) => Outcome.Admitted((seen ++ small).toArray)
            // The body ended before the header did: the dispatcher answers it as malformed.
            case Some(State.Peeking(seen)) => Outcome.NotAdmitted(seen.toArray)
            case None                      => Outcome.NotAdmitted(Array.emptyByteArray)
          }
          .recover { case _: EmberException.ReadTimeout => Outcome.TimedOut }
      val deadline: IO[Unit] =
        IO.sleep(cfg.connectBodyTimeoutSec.seconds) *> sessionBound.get.flatMap { bound =>
          if bound then IO.sleep((cfg.bodyTimeoutSec - cfg.connectBodyTimeoutSec).seconds)
          else IO.unit
        }
      IO.race(deadline, read).map(_.fold(_ => Outcome.TimedOut, identity))
    }
