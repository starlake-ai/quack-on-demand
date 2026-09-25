package ai.starlake.quack.ondemand.api

import Dtos.given
import EndpointSchemas.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Fleet runtime surface: the agent heartbeat (machine-to-machine, X-Fleet-Token) and the admin
  * server endpoints (Task 8). Registered in [[EndpointModules.all]].
  */
object FleetEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  /** Public at the api-key guard only when runtimeType=fleet; the handler checks X-Fleet-Token. */
  val heartbeat: PublicEndpoint[
    (FleetHeartbeatRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    FleetHeartbeatResponse,
    Any
  ] =
    base.post
      .in("fleet" / "heartbeat")
      .in(jsonBody[FleetHeartbeatRequest])
      .in(header[Option[String]]("X-Fleet-Token"))
      .out(jsonBody[FleetHeartbeatResponse])
