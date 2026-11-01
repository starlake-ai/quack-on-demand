package ai.starlake.quack.edge.quack

import ai.starlake.quack.QuackNativeConfig
import ai.starlake.quack.edge.quack.QuackWire.HeaderPeek
import cats.effect.{IO, Ref}
import fs2.Chunk
import org.http4s.Request

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

  private enum State:
    case Peeking(seen: Chunk[Byte])
    case Reading(seen: Chunk[Byte], limit: Long, sessionBound: Boolean)
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
        else State.Reading(seen, max, sessionBound)

  private def step(
      cfg: QuackNativeConfig,
      isLive: String => Boolean,
      declared: Option[Long]
  )(state: State, chunk: Chunk[Byte]): State =
    state match
      case State.Peeking(seen) =>
        val now = seen ++ chunk
        QuackWire.peekHeader(now.toArray) match
          case HeaderPeek.Complete(h) => admit(cfg, isLive, declared, h, now)
          case HeaderPeek.NeedMore if now.size < PeekMaxBytes => State.Peeking(now)
          case _ => State.Done(Outcome.NotAdmitted(now.toArray))
      case State.Reading(seen, limit, sessionBound) =>
        val now = seen ++ chunk
        if now.size.toLong > limit then State.Done(Outcome.TooLarge(limit))
        else State.Reading(now, limit, sessionBound)
      case done => done

  /** The outcome of reading `req`'s body. The clock starts now: `connectBodyTimeoutSec` for the
    * header and a CONNECTION_REQUEST, `bodyTimeoutSec` in all once the header names a live session.
    */
  def apply(req: Request[IO], cfg: QuackNativeConfig, isLive: String => Boolean): IO[Outcome] =
    Ref.of[IO, Boolean](false).flatMap { sessionBound =>
      val read: IO[Outcome] =
        req.body.chunks
          .scan[State](State.Peeking(Chunk.empty))(step(cfg, isLive, req.contentLength))
          .evalTap {
            case State.Reading(_, _, true) => sessionBound.set(true)
            case _                         => IO.unit
          }
          .takeThrough {
            case State.Done(_) => false
            case _             => true
          }
          .compile
          .last
          .map {
            case Some(State.Done(outcome))       => outcome
            case Some(State.Reading(seen, _, _)) => Outcome.Admitted(seen.toArray)
            // The body ended before the header did: the dispatcher answers it as malformed.
            case Some(State.Peeking(seen)) => Outcome.NotAdmitted(seen.toArray)
            case None                      => Outcome.NotAdmitted(Array.emptyByteArray)
          }
      val deadline: IO[Unit] =
        IO.sleep(cfg.connectBodyTimeoutSec.seconds) *> sessionBound.get.flatMap { bound =>
          if bound then
            IO.sleep(math.max(0L, cfg.bodyTimeoutSec - cfg.connectBodyTimeoutSec).seconds)
          else IO.unit
        }
      IO.race(deadline, read).map(_.fold(_ => Outcome.TimedOut, identity))
    }
