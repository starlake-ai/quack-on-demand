package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Managed-maintenance REST surface (EPIC Spec 09), split out of [[Endpoints]] for the same reason
  * as [[TagEndpoints]]: a Scala 3 object turns each `val` into a static-field initializer and the
  * combined `<clinit>` must stay below the JVM's 64KB method ceiling, which [[Endpoints]] already
  * saturates.
  *
  * Every endpoint carries the session input ([[Endpoints.authToken]]): the handlers enforce
  * [[TenantScopeCheck]] per request. The GET endpoints take `tenant` as a QUERY parameter (not a
  * path segment); [[TenantScopeGuard.extractTenant]]'s query fallback covers it.
  */
object MaintenanceEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val upsertPolicyEndpoint: PublicEndpoint[
    (MaintenancePolicyUpsertRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    MaintenancePolicyEntry,
    Any
  ] =
    base.post
      .in("maintenance" / "policy" / "upsert")
      .in(jsonBody[MaintenancePolicyUpsertRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[MaintenancePolicyEntry])
      .description(
        "Create or replace the maintenance policy row for a scope tuple. 404 unknown " +
          "tenant/tenant-db, 400 invalid_scope/invalid_cron/invalid_value, 400 invalid_kind on a " +
          "non-ducklake tenant-db."
      )

  val deletePolicyEndpoint: PublicEndpoint[
    (MaintenancePolicyDeleteRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("maintenance" / "policy" / "delete")
      .in(jsonBody[MaintenancePolicyDeleteRequest])
      .in(Endpoints.authToken)
      .description("Delete a maintenance policy row by id. 404 unknown id.")

  val listPoliciesEndpoint: PublicEndpoint[
    (String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    MaintenancePolicyListResponse,
    Any
  ] =
    base.get
      .in("maintenance" / "policy")
      .in(query[String]("tenant"))
      .in(query[String]("tenantDb"))
      .in(Endpoints.authToken)
      .out(jsonBody[MaintenancePolicyListResponse])
      .description(
        "List the maintenance policy rows of (tenant, tenantDb) plus the resolved effective " +
          "tenantdb-scope policy."
      )

  val listRunsEndpoint: PublicEndpoint[
    (String, String, Option[Int], Option[Long], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    List[MaintenanceRunEntry],
    Any
  ] =
    base.get
      .in("maintenance" / "runs")
      .in(query[String]("tenant"))
      .in(query[String]("tenantDb"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Long]]("before"))
      .in(Endpoints.authToken)
      .out(jsonBody[List[MaintenanceRunEntry]])
      .description(
        "List maintenance runs of (tenant, tenantDb), newest first; keyset pagination via " +
          "limit + before=runId."
      )

  val triggerRunEndpoint: PublicEndpoint[
    (MaintenanceRunRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    MaintenanceRunResponse,
    Any
  ] =
    base.post
      .in("maintenance" / "run")
      .in(jsonBody[MaintenanceRunRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[MaintenanceRunResponse])
      .description(
        "Enqueue a manual maintenance run for (tenant, tenantDb). 400 invalid_operations on an " +
          "unknown csv entry, 400 invalid_kind on a non-ducklake tenant-db."
      )
