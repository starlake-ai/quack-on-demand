package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import sttp.model.StatusCode

final class HealthHandler(sup: PoolSupervisor, dbHealthy: () => Boolean = () => true):

  private def snapshot: HealthResponse =
    val pools = sup.list()
    HealthResponse("ok", pools.size, pools.map(_.size).sum)

  /** Liveness probe: always 200 while the JVM is alive. No Postgres gate. */
  def health: IO[Either[StatusCode, HealthResponse]] = IO.delay(Right(snapshot))

  /** Readiness probe: 503 until Postgres is reachable. */
  def ready: IO[Either[StatusCode, HealthResponse]] = IO.delay {
    if dbHealthy() then Right(snapshot)
    else Left(StatusCode.ServiceUnavailable)
  }
