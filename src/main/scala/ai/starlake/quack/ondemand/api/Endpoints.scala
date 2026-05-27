package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

// Explicit Schemas for the response types touched by the metrics fields.
// tapir's generic auto-derivation chokes on the bigger NodeInfo case class
// in Scala 3, so we materialize them once here and reuse.
private given Schema[NodeInfo]           = Schema.derived
private given Schema[PoolResponse]       = Schema.derived
private given Schema[PoolListResponse]   = Schema.derived

object Endpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val createPool: PublicEndpoint[CreatePoolRequest, (sttp.model.StatusCode, ErrorResponse), PoolResponse, Any] =
    base.post.in("pool" / "create").in(jsonBody[CreatePoolRequest]).out(jsonBody[PoolResponse])

  val scalePool: PublicEndpoint[ScalePoolRequest, (sttp.model.StatusCode, ErrorResponse), PoolResponse, Any] =
    base.post.in("pool" / "scale").in(jsonBody[ScalePoolRequest]).out(jsonBody[PoolResponse])

  val stopPool: PublicEndpoint[StopPoolRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("pool" / "stop").in(jsonBody[StopPoolRequest])

  val listPools: PublicEndpoint[Unit, (sttp.model.StatusCode, ErrorResponse), PoolListResponse, Any] =
    base.get.in("pool" / "list").out(jsonBody[PoolListResponse])

  val poolStatus: PublicEndpoint[(String, String), (sttp.model.StatusCode, ErrorResponse), PoolResponse, Any] =
    base.get.in("pool" / path[String]("tenant") / path[String]("pool") / "status").out(jsonBody[PoolResponse])

  val setRole: PublicEndpoint[SetRoleRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("node" / "setRole").in(jsonBody[SetRoleRequest])

  val setMaxConcurrent: PublicEndpoint[SetMaxConcurrentRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("node" / "setMaxConcurrent").in(jsonBody[SetMaxConcurrentRequest])

  val quarantineNode: PublicEndpoint[NodeOpRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("node" / "quarantine").in(jsonBody[NodeOpRequest])

  val restartNode: PublicEndpoint[NodeOpRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("node" / "restart").in(jsonBody[NodeOpRequest])

  val health: PublicEndpoint[Unit, Unit, HealthResponse, Any] =
    endpoint.get.in("health").out(jsonBody[HealthResponse])

  /** Static client-connection info the UI needs to build JDBC/ODBC/ADBC URLs.
    * Uses bare `endpoint` (no /api prefix, no error envelope) to match `/health`. */
  val clientConfig: PublicEndpoint[Unit, Unit, ClientConfigResponse, Any] =
    endpoint.get.in("api" / "config" / "client").out(jsonBody[ClientConfigResponse])

  val createTenant: PublicEndpoint[TenantRequest, (sttp.model.StatusCode, ErrorResponse), TenantResponse, Any] =
    base.post.in("tenant" / "create").in(jsonBody[TenantRequest]).out(jsonBody[TenantResponse])

  val listTenants: PublicEndpoint[Unit, (sttp.model.StatusCode, ErrorResponse), TenantListResponse, Any] =
    base.get.in("tenant" / "list").out(jsonBody[TenantListResponse])

  val setTenantMetastore: PublicEndpoint[TenantRequest, (sttp.model.StatusCode, ErrorResponse), TenantResponse, Any] =
    base.post.in("tenant" / "setMetastore").in(jsonBody[TenantRequest]).out(jsonBody[TenantResponse])

  val deleteTenant: PublicEndpoint[TenantOpRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("tenant" / "delete").in(jsonBody[TenantOpRequest])

  // ----- ACL grants (Postgres-relational store, slkstate_acl_grant) -----
  val createAclGrant: PublicEndpoint[AclGrantRequest, (sttp.model.StatusCode, ErrorResponse), AclGrantResponse, Any] =
    base.post.in("acl" / "grant" / "create").in(jsonBody[AclGrantRequest]).out(jsonBody[AclGrantResponse])

  val listAclGrants: PublicEndpoint[Option[String], (sttp.model.StatusCode, ErrorResponse), AclGrantListResponse, Any] =
    base.get.in("acl" / "grant" / "list").in(query[Option[String]]("tenant")).out(jsonBody[AclGrantListResponse])

  val deleteAclGrant: PublicEndpoint[Long, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("acl" / "grant" / "delete" / path[Long]("id"))

  val uploadAclGrants: PublicEndpoint[AclGrantBulkRequest, (sttp.model.StatusCode, ErrorResponse), AclGrantListResponse, Any] =
    base.post.in("acl" / "grant" / "upload").in(jsonBody[AclGrantBulkRequest]).out(jsonBody[AclGrantListResponse])

  // ----- UI login -----
  val login: PublicEndpoint[LoginRequest, (sttp.model.StatusCode, ErrorResponse), LoginResponse, Any] =
    base.post.in("auth" / "login").in(jsonBody[LoginRequest]).out(jsonBody[LoginResponse])

  val logout: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("auth" / "logout").in(header[String]("X-API-Key"))

  val whoami: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), WhoamiResponse, Any] =
    base.get.in("auth" / "whoami").in(header[String]("X-API-Key")).out(jsonBody[WhoamiResponse])

  // Recent statement history (newest first), bounded by `limit` (default 50).
  val statementHistory: PublicEndpoint[Option[Int], (sttp.model.StatusCode, ErrorResponse), StatementHistoryResponse, Any] =
    base.get.in("node" / "statements")
        .in(query[Option[Int]]("limit"))
        .out(jsonBody[StatementHistoryResponse])