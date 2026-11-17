package ai.starlake.quack.ondemand.api

import cats.effect.{Deferred, IO, Ref}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

/** A BOUNDED WAIT over a statement that never lets a late result take effect.
  *
  * `IO.timeoutTo` is the wrong tool for a node call. The call is `IO.blocking`, which cancellation
  * cannot interrupt, so `timeoutTo` sits on it until the node answers. Here the statement runs on
  * its own fiber that is never cancelled: the caller stops waiting at the limit, and whichever of
  * the two moves second takes ownership of the result, so a result that arrives after the caller
  * gave up is handed to `abandon` exactly once, and a result delivered in time is the caller's
  * alone to close. The fiber is not cancelled because what runs after the node answers (the
  * router's kill-registry entry, its load and history bookkeeping) is not written to be cancelled
  * midway.
  *
  * `abandon` must not let a result take effect: for a stamped write it closes without the COMMIT
  * (`QueryResults.abandon`). It defaults to `close`, which is right for a result whose close does
  * nothing but release (a read).
  */
object BoundedWait:

  private val logger = LoggerFactory.getLogger(getClass)

  /** Run `run`, waiting at most `limit` for it. In time, its outcome is returned unchanged (a
    * raised error is re-raised). Past the limit, `Left(onTimeout)` is returned at once and a
    * `Right` that arrives afterwards is handed to `abandon` (else `close`). The same applies when
    * the waiting caller is itself cancelled (an outer timeout, a disconnect), a result already
    * delivered but not yet returned included. A late `Left` or a late error is dropped: it holds
    * nothing to release.
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
        _       <- run.attempt.flatMap { r =>
          state.modify {
            case Handoff.Waiting => (Handoff.Delivered, outcome.complete(r).void)
            case other           => (other, closeQuietly(r, drop))
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
          // now, so whoever holds it abandons it. A delivered one is abandoned here, a later one by
          // the worker.
          state.modify {
            case Handoff.Delivered =>
              (Handoff.Delivered, outcome.get.flatMap(closeQuietly(_, drop)))
            case _ => (Handoff.Abandoned, IO.unit)
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
