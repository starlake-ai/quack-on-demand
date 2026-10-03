package ai.starlake.quack.edge.rest

import cats.effect.{Fiber, IO, Poll}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.*

/** The blocking reads of one streamed body (the node result's batches, a Parquet pipe), made
  * forcible: a deadline or a client cancel ends the WAIT for a read at once, and makes the read
  * itself return instead of waiting for the node.
  *
  * Cancelling an `IO.blocking` read waits for it to return, which a stalled node may never let it
  * do. Here each read runs on its own interruptible fiber and the caller waits on that fiber's join
  * under `poll`. When the wait is cancelled or fails (a deadline), [[abort]] runs `unblock`
  * (closing what the read is blocked on: the node result, through its usual close, which is also
  * how an admin kill ends a statement) and interrupts the reading thread (which ends a JDK HTTP
  * client read and a sleep), without waiting for either.
  *
  * Whoever releases what the reads use waits for the last one first ([[settled]]), so nothing is
  * closed under a read still running. A read that neither the close nor the interrupt can end (a
  * node read inside a native library) still holds that release back until the node answers.
  *
  * One read at a time, as a stream pulls them.
  */
final class ForcedReads(unblock: IO[Unit]):

  private val last    = new AtomicReference[Option[Fiber[IO, Throwable, ?]]](None)
  private val aborted = new AtomicBoolean(false)

  /** `read`, waited for under `poll`, which must be the `poll` of the `IO.uncancelable` this runs
    * in: the read's fiber is then always recorded before a cancel can be observed.
    */
  def apply[A](poll: Poll[IO])(read: => A): IO[A] =
    IO.interruptible(read).start.flatMap { f =>
      IO(last.set(Some(f))) *> poll(f.joinWithNever).onError(_ => abort).onCancel(abort)
    }

  /** Makes the outstanding read return: `unblock`, then an interrupt, not waited for. Once. */
  def abort: IO[Unit] =
    IO(aborted.compareAndSet(false, true)).ifM(
      unblock.attempt *> IO.defer(last.get.fold(IO.unit)(_.cancel.start.void)),
      IO.unit
    )

  /** Waits for the last read to have returned, however it ends. */
  def settled: IO[Unit] = IO.defer(last.get.fold(IO.unit)(_.join.void))

object ForcedReads:

  /** `poll`, with every wait it lets through bounded by what is left until `deadline` (on the
    * monotonic clock): past it the wait fails with a `TimeoutException`.
    */
  def within(poll: Poll[IO], deadline: FiniteDuration): Poll[IO] =
    new Poll[IO]:
      def apply[A](fa: IO[A]): IO[A] =
        IO.monotonic.flatMap(now => poll(fa.timeout((deadline - now).max(Duration.Zero))))
