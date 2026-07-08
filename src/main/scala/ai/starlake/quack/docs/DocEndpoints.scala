package ai.starlake.quack.docs

import ai.starlake.quack.ondemand.api.EndpointModules
import sttp.tapir.AnyEndpoint

/** Collects every public Tapir endpoint val from the endpoint-definition objects via reflection, so
  * a newly added endpoint is picked up by the OpenAPI generator with no extra wiring. Only public
  * zero-arg members whose runtime return type is a `sttp.tapir.Endpoint` are included; the private
  * `base` and `fedBase` builders are excluded because `getMethods` returns public members only. The
  * holder objects come from [[EndpointModules.all]] - register new endpoint objects there.
  */
object DocEndpoints:

  private val holders: List[AnyRef] = EndpointModules.all

  /** All documented endpoints, de-duplicated by (method, path), in a stable order. */
  lazy val all: List[AnyEndpoint] =
    holders
      .flatMap(endpointsOf)
      .distinctBy(routeKey)
      .sortBy(routeKey)

  /** Stable "GET /api/foo" style key used for dedup, sorting, and the guard test. */
  def routeKey(e: AnyEndpoint): String =
    val method = e.method.map(_.method).getOrElse("ANY")
    s"$method ${e.showPathTemplate()}"

  private def endpointsOf(holder: AnyRef): List[AnyEndpoint] =
    holder.getClass.getMethods.toList
      .filter(_.getParameterCount == 0)
      .filter(m => classOf[sttp.tapir.Endpoint[?, ?, ?, ?, ?]].isAssignableFrom(m.getReturnType))
      .map { m =>
        try m.invoke(holder).asInstanceOf[AnyEndpoint]
        catch
          case e: java.lang.reflect.InvocationTargetException =>
            throw RuntimeException(
              s"DocEndpoints: failed to read ${holder.getClass.getSimpleName}.${m.getName}",
              e.getCause
            )
      }
