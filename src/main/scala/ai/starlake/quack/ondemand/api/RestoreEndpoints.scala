package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Restore REST surface (Spec 04), its own object for the same `<clinit>` 64KB reason as the other
  * endpoint modules. The POST carries tenant in the body like [[UndropEndpoints]]; the handler
  * enforces [[TenantScopeCheck]] per request.
  */
object RestoreEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val restoreEndpoint: PublicEndpoint[
    (RestoreRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    RestoreResponse,
    Any
  ] =
    base.post
      .in("catalog" / "restore")
      .in(jsonBody[RestoreRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[RestoreResponse])
      .description(
        "Restore a live table to a prior snapshot as a NEW forward snapshot (non-destructive: " +
          "history is preserved and the pre-restore state stays queryable; the replace assigns " +
          "a new table id, so the per-table history timeline restarts at the restore). " +
          "dryRun returns the change summary that would be undone and performs no write. " +
          "Requires an ALL grant, or DDL plus RO/RW, on the table. 409 concurrent_write when " +
          "expectedCurrentSnapshot no longer matches the table's latest snapshot; 410 " +
          "snapshot_expired; 422 invalid_snapshot when the table does not resolve at the target."
      )
