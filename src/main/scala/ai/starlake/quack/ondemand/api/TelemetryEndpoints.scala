package ai.starlake.quack.ondemand.api

import Dtos.given
import EndpointSchemas.given
import Endpoints.authToken
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Audit / history / usage read surface, split out of [[Endpoints]] to stay below the JVM's 64KB
  * `<clinit>` ceiling (see [[RbacEndpoints]] for the full rationale). Registered in
  * [[EndpointModules.all]].
  */
object TelemetryEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  // Tenant-scoped audit log with keyset pagination. All query params are optional.
  // `before` is an opaque cursor (last row's id as a string from a prior response).
  // `from`/`to` must be ISO-8601 instants; invalid values return 400 invalid_time.
  val auditList: PublicEndpoint[
    (
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[Int],
        Option[String],
        Option[Boolean],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    AuditListResponse,
    Any
  ] =
    base.get
      .in("audit" / "list")
      .in(query[Option[String]]("family"))
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("actor"))
      .in(query[Option[String]]("action"))
      .in(query[Option[String]]("q"))
      .in(query[Option[String]]("from"))
      .in(query[Option[String]]("to"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[String]]("before"))
      .in(query[Option[Boolean]]("noTenant"))
      .in(authToken)
      .out(jsonBody[AuditListResponse])

  // Exhaustive audit action vocabulary (static registry, no store access). Feeds the
  // Audit page's action select.
  val auditActions: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    AuditActionsResponse,
    Any
  ] =
    base.get
      .in("audit" / "actions")
      .in(authToken)
      .out(jsonBody[AuditActionsResponse])

  // Tenant-scoped rollup trends (hourly or daily aggregates). granularity is required ("hour" |
  // "day"); invalid values return 400 invalid_granularity. from/to must be ISO-8601 instants.
  val historyTrends: PublicEndpoint[
    (
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    TrendsResponse,
    Any
  ] =
    base.get
      .in("history" / "trends")
      .in(query[Option[String]]("granularity"))
      .in(query[Option[String]]("from"))
      .in(query[Option[String]]("to"))
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("pool"))
      .in(authToken)
      .out(jsonBody[TrendsResponse])

  // Tenant-scoped statement search with keyset pagination. `before` is an opaque cursor (last row
  // id as a string from a prior response). `limit` defaults to 50, clamped to [1, 500].
  val historyStatements: PublicEndpoint[
    (
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[Int],
        Option[String],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    StatementSearchResponse,
    Any
  ] =
    base.get
      .in("history" / "statements")
      .in(query[Option[String]]("from"))
      .in(query[Option[String]]("to"))
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("pool"))
      .in(query[Option[String]]("user"))
      .in(query[Option[String]]("status"))
      .in(query[Option[String]]("q"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[String]]("before"))
      .in(authToken)
      .out(jsonBody[StatementSearchResponse])

  // Tenant-scoped usage accounting over daily rollups. Defaults: current calendar month (UTC),
  // groupBy=tenant. groupBy must be tenant | pool | user (400 invalid_group_by otherwise);
  // from/to must be ISO-8601 instants (400 invalid_time).
  val usage: PublicEndpoint[
    (
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    UsageResponse,
    Any
  ] =
    base.get
      .in("usage")
      .in(query[Option[String]]("from"))
      .in(query[Option[String]]("to"))
      .in(query[Option[String]]("groupBy"))
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("pool"))
      .in(authToken)
      .out(jsonBody[UsageResponse])
