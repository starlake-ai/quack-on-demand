package ai.starlake.quack.ondemand.api

import Dtos.given
import EndpointSchemas.given
import Endpoints.authToken
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

  // Admin surface: superuser session or static key only (checked in the handler).
  val listServers: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    FleetServerListResponse,
    Any
  ] =
    base.get
      .in("fleet" / "servers")
      .in(authToken)
      .out(jsonBody[FleetServerListResponse])

  val drainServer: PublicEndpoint[
    (FleetServerOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("fleet" / "server" / "drain")
      .in(jsonBody[FleetServerOpRequest])
      .in(authToken)

  val undrainServer: PublicEndpoint[
    (FleetServerOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("fleet" / "server" / "undrain")
      .in(jsonBody[FleetServerOpRequest])
      .in(authToken)

  val removeServer: PublicEndpoint[
    (FleetServerOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("fleet" / "server" / "remove")
      .in(jsonBody[FleetServerOpRequest])
      .in(authToken)
