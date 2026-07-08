package ai.starlake.quack.ondemand.api

import Dtos.given
import EndpointSchemas.given
import Endpoints.authToken
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Node operations + statement surface (quarantine/restart, active statements, kill, recent
  * history), split out of [[Endpoints]] to stay below the JVM's 64KB `<clinit>` ceiling (see
  * [[RbacEndpoints]] for the full rationale). Registered in [[EndpointModules.all]].
  */
object NodeEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val setMaxConcurrent: PublicEndpoint[
    (SetMaxConcurrentRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "setMaxConcurrent")
      .in(jsonBody[SetMaxConcurrentRequest])
      .in(authToken)

  val quarantineNode: PublicEndpoint[
    (NodeOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "quarantine")
      .in(jsonBody[NodeOpRequest])
      .in(authToken)

  val unquarantineNode: PublicEndpoint[
    (NodeOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "unquarantine")
      .in(jsonBody[NodeOpRequest])
      .in(authToken)

  val restartNode: PublicEndpoint[
    (NodeOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "restart")
      .in(jsonBody[NodeOpRequest])
      .in(authToken)

  // Recent statement history (newest first), bounded by `limit` (default 50).
  // Tenant-scoped: the handler clamps the response to rows whose tenant is in
  // the calling session's `manageableTenants` (superuser / static-key / open
  // mode return the unfiltered window).
  val statementHistory: PublicEndpoint[
    (Option[Int], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    StatementHistoryResponse,
    Any
  ] =
    base.get
      .in("node" / "statements")
      .in(query[Option[Int]]("limit"))
      .in(authToken)
      .out(jsonBody[StatementHistoryResponse])

  val activeStatements: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    ActiveStatementsResponse,
    Any
  ] =
    base.get
      .in("node" / "active-statements")
      .in(authToken)
      .out(jsonBody[ActiveStatementsResponse])

  val killStatement: PublicEndpoint[
    (KillStatementRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    KillStatementResponse,
    Any
  ] =
    base.post
      .in("statement" / "kill")
      .in(jsonBody[KillStatementRequest])
      .in(authToken)
      .out(jsonBody[KillStatementResponse])
