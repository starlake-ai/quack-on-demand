package ai.starlake.quack.ondemand.api

import Dtos.given
import EndpointSchemas.given
import Endpoints.authToken
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Pool lifecycle + tuning surface, split out of [[Endpoints]] to stay below the JVM's 64KB
  * `<clinit>` ceiling (see [[RbacEndpoints]] for the full rationale). Registered in
  * [[EndpointModules.all]].
  */
object PoolEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val createPool: PublicEndpoint[
    (CreatePoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "create")
      .in(jsonBody[CreatePoolRequest])
      .in(authToken)
      .out(jsonBody[PoolResponse])

  val scalePool: PublicEndpoint[
    (ScalePoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "scale")
      .in(jsonBody[ScalePoolRequest])
      .in(authToken)
      .out(jsonBody[PoolResponse])

  val stopPool: PublicEndpoint[
    (StopPoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("pool" / "stop")
      .in(jsonBody[StopPoolRequest])
      .in(authToken)

  val deletePool: PublicEndpoint[
    (DeletePoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("pool" / "delete")
      .in(jsonBody[DeletePoolRequest])
      .in(authToken)

  val listPools: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    PoolListResponse,
    Any
  ] =
    base.get
      .in("pool" / "list")
      .in(authToken)
      .out(jsonBody[PoolListResponse])

  val poolStatus: PublicEndpoint[
    (String, String, String),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.get
      .in(
        "pool" / path[String]("tenant") / path[String]("tenantDb") / path[String]("pool") / "status"
      )
      .out(jsonBody[PoolResponse])

  val setPoolDisabled: PublicEndpoint[
    (SetPoolDisabledRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "setDisabled")
      .in(jsonBody[SetPoolDisabledRequest])
      .in(authToken)
      .out(jsonBody[PoolResponse])

  val setPoolResources: PublicEndpoint[
    (SetPoolResourcesRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "setResources")
      .in(jsonBody[SetPoolResourcesRequest])
      .in(authToken)
      .out(jsonBody[PoolResponse])

  val setPoolTemplate: PublicEndpoint[
    (SetPoolTemplateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "setPodTemplate")
      .in(jsonBody[SetPoolTemplateRequest])
      .in(authToken)
      .out(jsonBody[PoolResponse])
