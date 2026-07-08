package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Snapshot-tag REST surface (EPIC P2 / Spec 06), split out of [[Endpoints]] for the same reason as
  * [[RbacEndpoints]]: a Scala 3 object turns each `val` into a static-field initializer and the
  * combined `<clinit>` must stay below the JVM's 64KB method ceiling, which [[Endpoints]] already
  * saturates.
  *
  * Unlike the catalog browser GETs, every tag endpoint carries the session input
  * ([[Endpoints.authToken]]): the handlers enforce [[TenantScopeCheck]] per request. Do not mirror
  * the ungated PublicEndpoint drift of the browser surface.
  */
object TagEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val listTagsEndpoint: PublicEndpoint[
    (String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    List[CatalogTagEntry],
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") / "tags"
      )
      .in(Endpoints.authToken)
      .out(jsonBody[List[CatalogTagEntry]])
      .description(
        "List snapshot tags of the (tenant, tenantDb); `exists` flags whether the tagged snapshot is still in the catalog."
      )

  val createTagEndpoint: PublicEndpoint[
    (TagCreateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    CatalogTagEntry,
    Any
  ] =
    base.post
      .in("catalog" / "tag" / "create")
      .in(jsonBody[TagCreateRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[CatalogTagEntry])
      .description(
        "Create a snapshot tag. 404 if the snapshot does not exist, 409 on duplicate name, 400 on invalid name."
      )

  val deleteTagEndpoint: PublicEndpoint[
    (TagDeleteRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("catalog" / "tag" / "delete")
      .in(jsonBody[TagDeleteRequest])
      .in(Endpoints.authToken)
      .description("Delete a snapshot tag by name.")

  val protectTagEndpoint: PublicEndpoint[
    (TagProtectRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    CatalogTagEntry,
    Any
  ] =
    base.post
      .in("catalog" / "tag" / "protect")
      .in(jsonBody[TagProtectRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[CatalogTagEntry])
      .description("Toggle the retention hold (protected flag) of a snapshot tag.")
