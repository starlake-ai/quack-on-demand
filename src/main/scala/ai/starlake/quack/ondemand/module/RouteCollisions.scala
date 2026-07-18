package ai.starlake.quack.ondemand.module

import cats.effect.IO
import sttp.tapir.server.ServerEndpoint

/** Duplicate (method, path template) detection across core plus module endpoints. Tapir routes
  * first-match; a module silently shadowing a core route (or vice versa) must fail boot instead.
  */
object RouteCollisions:
  def check(all: List[ServerEndpoint[Any, IO]]): List[String] =
    all
      .groupBy(se =>
        (
          se.endpoint.method.map(_.method).getOrElse("ANY"),
          se.endpoint.showPathTemplate()
        )
      )
      .collect { case ((m, p), es) if es.sizeIs > 1 => s"$m $p (${es.size} definitions)" }
      .toList
      .sorted
