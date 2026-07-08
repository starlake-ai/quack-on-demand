package ai.starlake.quack.ondemand.api

import Dtos.given
import Endpoints.authToken
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Federated source + secret surface, split out of [[Endpoints]] to stay below the JVM's 64KB
  * `<clinit>` ceiling (see [[RbacEndpoints]] for the full rationale). Registered in
  * [[EndpointModules.all]].
  */
object FederatedSourceEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  private val fedBase =
    base.in(
      "tenants" / path[String]("tenant") / "tenant-dbs" / path[String](
        "tenantDb"
      ) / "federated-sources"
    )

  val createFederatedSource: PublicEndpoint[
    (String, String, FederatedSourceCreateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSourceResponse,
    Any
  ] =
    fedBase.post
      .in(jsonBody[FederatedSourceCreateRequest])
      .in(authToken)
      .out(jsonBody[FederatedSourceResponse])

  val listFederatedSources: PublicEndpoint[
    (String, String),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSourceListResponse,
    Any
  ] =
    fedBase.get.out(jsonBody[FederatedSourceListResponse])

  val getFederatedSource: PublicEndpoint[
    (String, String, String),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSourceResponse,
    Any
  ] =
    fedBase.get.in(path[String]("alias")).out(jsonBody[FederatedSourceResponse])

  val deleteFederatedSource: PublicEndpoint[
    (String, String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    fedBase.delete.in(path[String]("alias")).in(authToken)

  val listFederatedSecrets: PublicEndpoint[
    (String, String, String),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSecretListResponse,
    Any
  ] =
    fedBase.get
      .in(path[String]("alias") / "secrets")
      .out(jsonBody[FederatedSecretListResponse])

  val upsertFederatedSecret: PublicEndpoint[
    (String, String, String, FederatedSecretUpsertRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSecretResponse,
    Any
  ] =
    fedBase.put
      .in(path[String]("alias") / "secrets")
      .in(jsonBody[FederatedSecretUpsertRequest])
      .in(authToken)
      .out(jsonBody[FederatedSecretResponse])

  val deleteFederatedSecret: PublicEndpoint[
    (String, String, String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    fedBase.delete.in(path[String]("alias") / "secrets" / path[String]("name")).in(authToken)
