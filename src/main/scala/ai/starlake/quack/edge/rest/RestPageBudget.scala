package ai.starlake.quack.edge.rest

import cats.effect.{FiberIO, IO}
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/** The heap the REST data edge's `/rows` pages may hold at once. A page is encoded into one buffer
  * that lives until the response is written ([[RestResultEncoder]]), up to twice
  * `QOD_DATA_EDGE_MAX_RESPONSE_BYTES` while the buffer doubles, and an out-of-memory error is fatal
  * to the manager, for every tenant. So one PAT with a valid token could otherwise take the heap
  * with concurrent large pages, bounded only by the connection count.
  *
  * A data statement first reserves a page's worst case ([[reserve]], waiting at most the request's
  * own statement bound, else `503 edge_busy`), keeps only what its body holds once it is encoded
  * ([[settle]]), and gives that back when the response has been written or has failed ([[release]],
  * from [[RestEdgeRoutes]]). Counted in KiB.
  *
  * Nothing gives a reservation back on a clock while its page is being encoded: the buffer is still
  * growing, and the encoding is bounded by the statement's own deadline instead. Once the body
  * exists, a backstop gives back what the request still holds after `unpulledHold`, for a body the
  * server never pulls (a connection that broke before its response was written), whose finalizer
  * then never runs. It is stopped by the normal release, and as soon as the body's writing begins
  * ([[pulled]]): from then on the body's own finalizer gives the page back, however slowly the
  * client reads it.
  */
final class RestPageBudget private (
    val totalKiB: Long,
    val reserveKiB: Long,
    unpulledHold: FiniteDuration,
    permits: Option[Semaphore[IO]]
):

  /** What one request holds, and the backstop armed once its body exists. */
  private final case class Held(kiB: Long, backstop: Option[FiberIO[Unit]])

  private val held = mutable.HashMap.empty[String, Held]

  /** Whether request `rid` holds part of the budget. */
  def holds(rid: String): Boolean = held.synchronized(held.contains(rid))

  /** Reserves a page's worst case for request `rid`, waiting at most `wait`; false when none came
    * free in time.
    *
    * The permits are taken and recorded in one uncancelable step, the wait alone cancelable: a
    * cancel lands either before they were taken (the semaphore then takes nothing) or after they
    * were recorded, where [[release]] finds them. A wait that gives up as the permits arrive (the
    * timeout's race) has them recorded too, and gives them back here.
    */
  def reserve(rid: String, wait: FiniteDuration): IO[Boolean] = permits match
    case None    => IO.pure(true)
    case Some(s) =>
      val take = IO.uncancelable { poll =>
        poll(s.acquireN(reserveKiB)) *>
          IO(held.synchronized(held.update(rid, Held(reserveKiB, None))))
      }
      IO.uncancelable { poll =>
        poll(take.timeout(wait)).attempt.flatMap {
          case Right(()) => IO.pure(true)
          case Left(_)   => release(rid).as(false)
        }
      }

  /** Keeps what a body of `bytes` bytes holds of request `rid`'s reservation, gives the rest back,
    * and arms the backstop for a body that is never pulled. After [[release]], a no-op.
    */
  def settle(rid: String, bytes: Long): IO[Unit] = permits match
    case None    => IO.unit
    case Some(s) =>
      val keep = math.max(1L, (bytes + 1023L) / 1024L)
      IO.uncancelable { _ =>
        (IO.sleep(unpulledHold) *> giveBack(rid)).void.start.flatMap { backstop =>
          IO {
            held.synchronized {
              held.get(rid) match
                // Released already: nothing to keep, and no backstop to arm.
                case None    => (0L, Some(backstop))
                case Some(h) =>
                  val now = math.min(h.kiB, keep)
                  held.update(rid, Held(now, Some(backstop)))
                  (h.kiB - now, h.backstop)
            }
          }.flatMap { (freed, stale) =>
            (if freed > 0L then s.releaseN(freed) else IO.unit) *> stale.fold(IO.unit)(_.cancel)
          }
        }
      }

  /** Request `rid`'s body has begun to be written, under a finalizer that releases it: the backstop
    * is no longer needed and is stopped.
    */
  def pulled(rid: String): IO[Unit] =
    IO.uncancelable { _ =>
      IO {
        held.synchronized {
          held.get(rid) match
            case None    => None
            case Some(h) =>
              held.update(rid, h.copy(backstop = None))
              h.backstop
        }
      }.flatMap(_.fold(IO.unit)(_.cancel))
    }

  /** Gives back all request `rid` holds, and stops its backstop. Idempotent. */
  def release(rid: String): IO[Unit] = giveBack(rid).flatMap(_.fold(IO.unit)(_.cancel))

  /** Gives back all request `rid` holds and returns its backstop, without stopping it: the
    * backstop's own release must not cancel itself.
    */
  private def giveBack(rid: String): IO[Option[FiberIO[Unit]]] = permits match
    case None    => IO.pure(None)
    case Some(s) =>
      IO.uncancelable { _ =>
        IO(held.synchronized(held.remove(rid))).flatMap {
          case None    => IO.pure(None)
          case Some(h) => s.releaseN(h.kiB).as(h.backstop)
        }
      }

object RestPageBudget:

  /** The edge's pages get one part in this many of the heap. */
  val HeapParts: Long = 5L

  /** No budget: every reservation succeeds at once (specs that drive the handlers alone). */
  val Unbounded: RestPageBudget =
    new RestPageBudget(Long.MaxValue, 0L, FiniteDuration(0, "s"), None)

  /** One part in [[HeapParts]] of `maxHeapBytes`, each page reserving twice `maxResponseBytes` (a
    * budget smaller than that still serves one page at a time). A body never pulled gives its share
    * back `unpulledHold` after it was settled.
    */
  def forHeap(
      maxResponseBytes: Long,
      maxHeapBytes: Long,
      unpulledHold: FiniteDuration
  ): RestPageBudget =
    val total   = math.max(1L, maxHeapBytes / HeapParts / 1024L)
    val reserve = math.min(total, math.max(1L, (2L * maxResponseBytes + 1023L) / 1024L))
    new RestPageBudget(total, reserve, unpulledHold, Some(Semaphore[IO](total).unsafeRunSync()))
