package ai.starlake.quack.ondemand.api

import Dtos.given
import ai.starlake.quack.ondemand.api.SecurityAuditResponse.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** The security audit's endpoint. Internal: only the console and the qod CLI call it. Deliberately
  * NOT in [[EndpointModules.all]], so it is in no OpenAPI document, no docs and no CLI parity gate
  * (DocEndpointsSpec pins that).
  */
object SecurityAuditEndpoints:
  val audit: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    SecurityAuditResponse,
    Any
  ] =
    endpoint
      .in("api" / "security" / "audit")
      .get
      .in(Endpoints.authToken)
      .errorOut(statusCode.and(jsonBody[ErrorResponse]))
      .out(jsonBody[SecurityAuditResponse])
