package ai.starlake.quack.edge.rest

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/** The heap the REST data edge's `/rows` pages may hold at once. A page is encoded into one buffer
  * that lives until the response is written ([[RestResultEncoder]]), up to twice
  * `QOD_REST_MAX_RESPONSE_BYTES` while the buffer doubles, and an out-of-memory error is fatal to
  * the manager, for every tenant. So one PAT with a valid token could otherwise take the heap with
  * concurrent large pages, bounded only by the connection count.
  *
  * A data statement first reserves a page's worst case ([[reserve]], waiting at most the request's
  * own statement bound, else `503 edge_busy`), keeps only what its body holds once it is encoded
  * ([[settle]]), and gives that back when the response has been written or has failed ([[release]],
  * from [[RestEdgeServer]]). A reservation whose release never comes (a connection that broke
  * before its body was pulled) is given back after `holdAtMost`. Counted in KiB.
  */
final class RestPageBudget private (
    val totalKiB: Long,
    val reserveKiB: Long,
    holdAtMost: FiniteDuration,
    permits: Option[Semaphore[IO]]
):

  private val held = mutable.HashMap.empty[String, Long]

  /** Whether request `rid` holds part of the budget. */
  def holds(rid: String): Boolean = held.synchronized(held.contains(rid))

  /** Reserves a page's worst case for request `rid`, waiting at most `wait`; false when none came
    * free in time.
    */
  def reserve(rid: String, wait: FiniteDuration): IO[Boolean] = permits match
    case None    => IO.pure(true)
    case Some(s) =>
      s.acquireN(reserveKiB).timeout(wait).attempt.flatMap {
        case Left(_)   => IO.pure(false)
        case Right(()) =>
          IO(held.synchronized(held.update(rid, reserveKiB))) *>
            (IO.sleep(holdAtMost) *> release(rid)).start.as(true)
      }

  /** Keeps what a body of `bytes` bytes holds of request `rid`'s reservation, and gives the rest
    * back.
    */
  def settle(rid: String, bytes: Long): IO[Unit] =
    val keep = math.max(1L, (bytes + 1023L) / 1024L)
    giveBack(rid, held => math.min(held, keep))

  /** Gives back all request `rid` holds. Idempotent. */
  def release(rid: String): IO[Unit] = giveBack(rid, _ => 0L)

  private def giveBack(rid: String, keep: Long => Long): IO[Unit] = permits match
    case None    => IO.unit
    case Some(s) =>
      IO {
        held.synchronized {
          held.get(rid) match
            case None    => 0L
            case Some(h) =>
              val now = keep(h)
              if now <= 0L then held.remove(rid) else held.update(rid, now)
              h - math.max(now, 0L)
        }
      }.flatMap(n => if n > 0L then s.releaseN(n) else IO.unit)

object RestPageBudget:

  /** The edge's pages get one part in this many of the heap. */
  val HeapParts: Long = 5L

  /** No budget: every reservation succeeds at once (specs that drive the handlers alone). */
  val Unbounded: RestPageBudget =
    new RestPageBudget(Long.MaxValue, 0L, FiniteDuration(0, "s"), None)

  /** One part in [[HeapParts]] of `maxHeapBytes`, each page reserving twice `maxResponseBytes` (a
    * budget smaller than that still serves one page at a time).
    */
  def forHeap(
      maxResponseBytes: Long,
      maxHeapBytes: Long,
      holdAtMost: FiniteDuration
  ): RestPageBudget =
    val total   = math.max(1L, maxHeapBytes / HeapParts / 1024L)
    val reserve = math.min(total, math.max(1L, (2L * maxResponseBytes + 1023L) / 1024L))
    new RestPageBudget(total, reserve, holdAtMost, Some(Semaphore[IO](total).unsafeRunSync()))
