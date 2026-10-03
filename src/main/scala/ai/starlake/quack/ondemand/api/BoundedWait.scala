package ai.starlake.quack.ondemand.api

import cats.effect.{Deferred, IO, Ref}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

/** A BOUNDED WAIT over a statement that closes a result arriving too late.
  *
  * `IO.timeoutTo` is the wrong tool for a node call. The call is `IO.blocking`, which cancellation
  * cannot interrupt, so `timeoutTo` sits on it until the node answers, and when the answer does
  * arrive the cancelled fiber drops it without closing its Arrow reader or its kill-registry entry.
  * Here the statement runs on its own fiber that is never cancelled: the caller stops waiting at
  * the limit, and whichever of the two moves second takes ownership of the result, so a late result
  * is closed exactly once and a result delivered in time is the caller's alone to close.
  *
  * This is NOT a cancellation: the node keeps executing the statement to completion, as before.
  * Node-side cancellation is a separate concern this helper does not attempt.
  */
object BoundedWait:

  private val logger = LoggerFactory.getLogger(getClass)

  /** Run `run`, waiting at most `limit` for it. In time, its outcome is returned unchanged (a
    * raised error is re-raised). Past the limit, `Left(onTimeout)` is returned at once and a
    * `Right` that arrives afterwards is handed to `close`. The same applies when the waiting caller
    * is itself cancelled (an outer timeout, a disconnect). A late `Left` or a late error is
    * dropped: it holds nothing to release.
    */
  def closingLate[E, A](
      run: IO[Either[E, A]],
      limit: FiniteDuration,
      onTimeout: => E,
      close: A => Unit
  ): IO[Either[E, A]] =
    IO.uncancelable { poll =>
      for
        state   <- Ref[IO].of[Handoff](Handoff.Waiting)
        outcome <- Deferred[IO, Either[Throwable, Either[E, A]]]
        _       <- run.attempt.flatMap { r =>
          state.modify {
            case Handoff.Waiting => (Handoff.Delivered, outcome.complete(r).void)
            case other           => (other, closeQuietly(r, close))
          }.flatten
        }.start
        // The caller stops waiting. If the worker already delivered, the result is in `outcome`
        // and still the caller's: take it rather than drop it.
        giveUp = state.modify {
          case Handoff.Waiting   => (Handoff.Abandoned, IO.pure(Right(Left(onTimeout))))
          case Handoff.Delivered => (Handoff.Delivered, outcome.get)
          case Handoff.Abandoned => (Handoff.Abandoned, IO.pure(Right(Left(onTimeout))))
        }.flatten
        result <- poll(outcome.get.timeoutTo(limit, giveUp)).onCancel(
          // Cancelled while waiting (an outer timeout, a disconnect): nobody will read the result
          // now, so whoever holds it closes it. A delivered one is closed here, a later one by the
          // worker.
          state.modify {
            case Handoff.Delivered =>
              (Handoff.Delivered, outcome.get.flatMap(closeQuietly(_, close)))
            case _ => (Handoff.Abandoned, IO.unit)
          }.flatten
        )
        value <- IO.fromEither(result)
      yield value
    }

  /** Who owns the statement's result: nobody yet, the waiting caller, or (after the caller gave up)
    * the worker fiber, which then closes it.
    */
  private enum Handoff:
    case Waiting, Delivered, Abandoned

  private def closeQuietly[E, A](r: Either[Throwable, Either[E, A]], close: A => Unit): IO[Unit] =
    r match
      case Right(Right(a)) =>
        IO.blocking(close(a)).handleError(t => logger.warn("closing a late result failed", t))
      case _ => IO.unit
