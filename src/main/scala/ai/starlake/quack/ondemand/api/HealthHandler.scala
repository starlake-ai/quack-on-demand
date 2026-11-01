package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import sttp.model.StatusCode

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

final class HealthHandler(sup: PoolSupervisor, dbHealthy: () => Boolean = () => true):

  private def snapshot: HealthResponse =
    val pools = sup.list()
    HealthResponse("ok", pools.size, pools.map(_.size).sum)

  /** Liveness probe: always 200 while the JVM is alive. No Postgres gate. */
  def health: IO[Either[StatusCode, HealthResponse]] = IO.delay(Right(snapshot))

  /** Readiness probe: 503 until Postgres is reachable. Anyone can call it, and `dbHealthy` can wait
    * on the database, so it runs off the compute threads.
    */
  def ready: IO[Either[StatusCode, HealthResponse]] = IO.blocking {
    if dbHealthy() then Right(snapshot)
    else Left(StatusCode.ServiceUnavailable)
  }

/** Whether the database answered lately, for the readiness probe. Anyone can call that probe, and a
  * stalled database makes a ping wait for seconds. So one ping runs at a time, at most once per
  * `ttlNanos`, and every other caller gets the last answer at once (false until there is one).
  */
final class CachedDbHealth(
    ping: () => Boolean,
    ttlNanos: Long = 5000000000L,
    nanoTime: () => Long = () => System.nanoTime()
):
  private val last    = new AtomicReference[Option[(Long, Boolean)]](None)
  private val pinging = new AtomicBoolean(false)

  def healthy(): Boolean =
    val known = last.get()
    val now   = nanoTime()
    if known.exists((at, _) => now - at < ttlNanos) || !pinging.compareAndSet(false, true) then
      known.exists((_, answer) => answer)
    else
      try
        val answer = ping()
        last.set(Some((now, answer)))
        answer
      finally pinging.set(false)
