package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import sttp.model.StatusCode

final class HealthHandler(sup: PoolSupervisor, dbHealthy: () => Boolean = () => true):
  def health: IO[Either[StatusCode, HealthResponse]] = IO.delay {
    if dbHealthy() then
      val pools = sup.list()
      Right(HealthResponse("ok", pools.size, pools.map(_.size).sum))
    else Left(StatusCode.ServiceUnavailable)
  }
