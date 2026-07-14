package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Undrop REST surface (Spec 03), its own object for the same `<clinit>` 64KB reason as the other
  * endpoint modules. Both endpoints carry the session input; the handlers enforce
  * [[TenantScopeCheck]] per request (the POST carries tenant in the body like [[TagEndpoints]]'
  * mutations).
  */
object UndropEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val recoverableEndpoint: PublicEndpoint[
    (String, String, Option[Int], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    RecoverableListResponse,
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") / "recoverable"
      )
      .in(query[Option[Int]]("limit"))
      .in(Endpoints.authToken)
      .out(jsonBody[RecoverableListResponse])
      .description(
        "Recently dropped tables of a DuckLake tenant-db that are still recoverable (their " +
          "last-live snapshot has not been expired), newest drop first."
      )

  val undropEndpoint: PublicEndpoint[
    (UndropRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    UndropResponse,
    Any
  ] =
    base.post
      .in("catalog" / "undrop")
      .in(jsonBody[UndropRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[UndropResponse])
      .description(
        "Recreate a dropped table at its last-live snapshot (or an explicit fromSnapshot) via " +
          "CREATE TABLE AS a time-travel read, optionally under a new name (asName). 409 " +
          "name_conflict when the target name is live; 410 snapshot_expired once expiry has " +
          "reaped the source snapshot."
      )
