package ai.starlake.quack.ondemand.api

import Dtos.given
import Endpoints.authToken
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Tenant + tenant-database CRUD surface, split out of [[Endpoints]] to stay below the JVM's 64KB
  * `<clinit>` ceiling (see [[RbacEndpoints]] for the full rationale). Registered in
  * [[EndpointModules.all]].
  */
object TenantEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val createTenant: PublicEndpoint[
    (TenantRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantResponse,
    Any
  ] =
    base.post
      .in("tenant" / "create")
      .in(jsonBody[TenantRequest])
      .in(authToken)
      .out(jsonBody[TenantResponse])

  val listTenants: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    TenantListResponse,
    Any
  ] =
    base.get
      .in("tenant" / "list")
      .in(authToken)
      .out(jsonBody[TenantListResponse])

  val deleteTenant: PublicEndpoint[
    (TenantOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("tenant" / "delete")
      .in(jsonBody[TenantOpRequest])
      .in(authToken)

  val setTenantDisabled: PublicEndpoint[
    (SetTenantDisabledRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantResponse,
    Any
  ] =
    base.post
      .in("tenant" / "setDisabled")
      .in(jsonBody[SetTenantDisabledRequest])
      .in(authToken)
      .out(jsonBody[TenantResponse])

  val setTenantAuth: PublicEndpoint[
    (SetTenantAuthRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantResponse,
    Any
  ] =
    base.post
      .in("tenant" / "setAuth")
      .in(jsonBody[SetTenantAuthRequest])
      .in(authToken)
      .out(jsonBody[TenantResponse])

  // ----- Tenant databases -----
  val createTenantDb: PublicEndpoint[
    (TenantDbRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantDbResponse,
    Any
  ] =
    base.post
      .in("database" / "create")
      .in(jsonBody[TenantDbRequest])
      .in(authToken)
      .out(jsonBody[TenantDbResponse])

  val listTenantDbs: PublicEndpoint[
    (String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantDbListResponse,
    Any
  ] =
    base.get
      .in("database" / "list")
      .in(query[String]("tenant"))
      .in(authToken)
      .out(jsonBody[TenantDbListResponse])

  val deleteTenantDb: PublicEndpoint[
    (TenantDbOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("database" / "delete")
      .in(jsonBody[TenantDbOpRequest])
      .in(authToken)

  val updateTenantDb: PublicEndpoint[
    (UpdateTenantDbRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    UpdateTenantDbResponse,
    Any
  ] =
    base.post
      .in("database" / "update")
      .in(jsonBody[UpdateTenantDbRequest])
      .in(authToken)
      .out(jsonBody[UpdateTenantDbResponse])
