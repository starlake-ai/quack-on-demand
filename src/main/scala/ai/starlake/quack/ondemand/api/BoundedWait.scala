package ai.starlake.quack.ondemand.api

import cats.effect.{Deferred, IO, Ref}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

/** A BOUNDED WAIT over a statement that never lets a late result take effect.
  *
  * `IO.timeoutTo` is the wrong tool for a node call. The call is `IO.blocking`, which cancellation
  * cannot interrupt, so `timeoutTo` sits on it until the node answers. Here the statement runs on
  * its own fiber: the caller stops waiting at the limit, and the fiber is then cancelled in the
  * background, so once the node answers, the release path a cancel takes runs (a stamped write's
  * inner reader is closed without its COMMIT, and the write rolls back). A result that escaped that
  * path (it was delivered after the caller gave up) is handed to `abandon`, never read.
  *
  * `abandon` must not let a result take effect: for a stamped write it closes without the COMMIT
  * (`QueryResults.abandon`). It defaults to `close`, which is right for a result whose close does
  * nothing but release (a read).
  */
object BoundedWait:

  private val logger = LoggerFactory.getLogger(getClass)

  /** Run `run`, waiting at most `limit` for it. In time, its outcome is returned unchanged (a
    * raised error is re-raised). Past the limit, `Left(onTimeout)` is returned at once, `run`'s
    * fiber is cancelled in the background, and a `Right` that arrives afterwards anyway is handed to
    * `abandon` (else `close`). The same applies when the waiting caller is itself cancelled (an
    * outer timeout, a disconnect), a result already delivered but not yet returned included. A late
    * `Left` or a late error is dropped: it holds nothing to release.
    *
    * `onDelivered` sees a `Right` delivered in time before it is returned, with no cancellation
    * point in between: a caller that registers it there can close it when it is cancelled at the
    * boundary of the `poll` it waits in, where the returned value itself would be lost.
    */
  def closingLate[E, A](
      run: IO[Either[E, A]],
      limit: FiniteDuration,
      onTimeout: => E,
      close: A => Unit,
      onDelivered: A => IO[Unit] = (_: A) => IO.unit,
      abandon: Option[A => Unit] = None
  ): IO[Either[E, A]] =
    val drop: A => Unit = abandon.getOrElse(close)
    IO.uncancelable { poll =>
      for
        state   <- Ref[IO].of[Handoff](Handoff.Waiting)
        outcome <- Deferred[IO, Either[Throwable, Either[E, A]]]
        // `run` stays cancelable, so a cancel runs its own release path; once it has finished, the
        // hand-off has no cancellation point of its own.
        worker <- IO.uncancelable { inner =>
          inner(run).attempt.flatMap { r =>
            state.modify {
              case Handoff.Waiting => (Handoff.Delivered, outcome.complete(r).void)
              case other           => (other, closeQuietly(r, drop))
            }.flatten
          }
        }.start
        // Not awaited: the worker may sit in an uninterruptible node call until the node answers.
        cancelWorker = worker.cancel.start.void
        // The caller stops waiting. If the worker already delivered, the result is in `outcome`
        // and still the caller's: take it rather than drop it.
        giveUp = state.modify {
          case Handoff.Waiting =>
            (Handoff.Abandoned, cancelWorker.as(Right(Left(onTimeout))))
          case Handoff.Delivered => (Handoff.Delivered, outcome.get)
          case Handoff.Abandoned => (Handoff.Abandoned, IO.pure(Right(Left(onTimeout))))
        }.flatten
        result <- poll(outcome.get.timeoutTo(limit, giveUp)).onCancel(
          // Cancelled while waiting (an outer timeout, a disconnect): nobody will read the result
          // now. A delivered one is abandoned here; otherwise the worker is cancelled, and a result
          // that still escapes is abandoned by the worker.
          state.modify {
            case Handoff.Delivered =>
              (Handoff.Delivered, outcome.get.flatMap(closeQuietly(_, drop)))
            case _ => (Handoff.Abandoned, cancelWorker)
          }.flatten
        )
        value <- IO.fromEither(result)
        _     <- value.fold(_ => IO.unit, onDelivered)
      yield value
    }

  /** Who owns the statement's result: nobody yet, the waiting caller, or (after the caller gave up)
    * the worker fiber, which then abandons it.
    */
  private enum Handoff:
    case Waiting, Delivered, Abandoned

  private def closeQuietly[E, A](r: Either[Throwable, Either[E, A]], drop: A => Unit): IO[Unit] =
    r match
      case Right(Right(a)) =>
        IO.blocking(drop(a)).handleError(t => logger.warn("dropping a late result failed", t))
      case _ => IO.unit
