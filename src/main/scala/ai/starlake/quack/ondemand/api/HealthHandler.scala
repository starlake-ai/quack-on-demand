package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO

final class HealthHandler(sup: PoolSupervisor):
  def health: IO[Either[Unit, HealthResponse]] = IO.delay {
    val pools = sup.list()
    Right(HealthResponse("ok", pools.size, pools.map(_.size).sum))
  }
