package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.RoleDistribution
import io.circe.{Codec, Decoder, Encoder, HCursor, Json, JsonObject}
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._

final case class CreatePoolRequest(
    tenant: String,
    pool: String,
    size: Int,
    roleDistribution: RoleDistribution,
    metastore: Map[String, String],
    s3: Map[String, String] = Map.empty,
    idleTimeoutSec: Int = -1,
    maxConcurrentPerNode: Int = 0
)

final case class NodeInfo(
    nodeId: String,
    role: String,
    host: String,
    port: Int,
    maxConcurrent: Int = 0,
    // Live metrics from NodeLoadTracker. inFlight = currently executing,
    // totalServed = lifetime counter since manager start, avgDurationMs =
    // EWMA of completed-statement latency. healthy/draining mirror the
    // tracker for UI status badges. p50/p95/p99 are sampled over a
    // rolling 256-statement window per node - see LatencyRing.
    inFlight: Int = 0,
    totalServed: Long = 0L,
    avgDurationMs: Double = 0.0,
    p50Ms: Double = 0.0,
    p95Ms: Double = 0.0,
    p99Ms: Double = 0.0,
    healthy: Boolean = true,
    draining: Boolean = false
)

final case class PoolResponse(
    tenant: String,
    pool: String,
    nodes: List[NodeInfo],
    status: String,
    metastore: Map[String, String] = Map.empty   // effective merged metastore, password redacted
)

final case class ScalePoolRequest(
    tenant: String,
    pool: String,
    targetSize: Int,
    roleDistribution: RoleDistribution,
    force: Boolean = false
)

final case class StopPoolRequest(tenant: String, pool: String, force: Boolean = false)

final case class PoolListResponse(pools: List[PoolResponse])
final case class HealthResponse(status: String, poolsCount: Int, nodesCount: Int)

final case class ClientConfigResponse(
    flightSqlHost: String,    // may be "0.0.0.0" - UI should substitute window.location.hostname
    flightSqlPort: Int,
    flightSqlTls: Boolean,
    tenantClaim: String,       // JWT claim name the edge uses
    // True iff any basic / bearer auth provider is configured. When false,
    // the UI skips the login screen entirely (there's no credential
    // backend to validate against; `/api/auth/login` would 503).
    authEnabled: Boolean = true
)

final case class SetRoleRequest(tenant: String, pool: String, nodeId: String, role: String)
final case class SetMaxConcurrentRequest(tenant: String, pool: String, nodeId: String, max: Int)
final case class NodeOpRequest(tenant: String, pool: String, nodeId: String)

final case class ErrorResponse(error: String, message: String)

final case class TenantRequest(name: String, metastore: Map[String, String] = Map.empty)
final case class TenantResponse(
    name: String,
    metastore: Map[String, String],          // tenant's own overrides (write-back surface)
    pools: List[String],
    effectiveMetastore: Map[String, String] = Map.empty   // global defaults <- tenant overrides, password-redacted
)
final case class TenantListResponse(tenants: List[TenantResponse])
final case class TenantOpRequest(name: String)

// ----- Tenant identity allowlist -----------------------------------------
// One verified `(issuer, externalId)` pair per row, mapped to a tenant id.
final case class IdentityRequest(tenantId: String, issuer: String, externalId: String)
final case class IdentityResponse(
    id:         String,
    tenantId:   String,
    issuer:     String,
    externalId: String,
    createdAt:  String
)
final case class IdentityListResponse(identities: List[IdentityResponse])
final case class IdentityOpRequest(id: String)

final case class AclGrantRequest(
    tenantId: String,
    principal: String,                  // "user:alice" | "group:eng" | "role:admin"
    catalogName: Option[String] = None,
    schemaName: Option[String]  = None,
    tableName: Option[String]   = None,
    permission: String                  // SELECT | INSERT | UPDATE | DELETE | ALL
)

final case class AclGrantResponse(
    id: Long,
    tenantId: String,
    principal: String,
    catalogName: Option[String],
    schemaName: Option[String],
    tableName: Option[String],
    permission: String,
    grantedAt: String                   // ISO-8601 UTC
)

final case class AclGrantListResponse(grants: List[AclGrantResponse])
final case class AclGrantBulkRequest(grants: List[AclGrantRequest])

// ----- UI login -----
final case class LoginRequest(username: String, password: String)
final case class LoginResponse(token: String, username: String, role: String)
final case class WhoamiResponse(username: String, role: String)

// ----- Recent statement history -----
final case class StatementHistoryEntry(
    ts:         String,        // ISO-8601 UTC
    user:       String,
    tenant:     String,
    pool:       String,
    nodeId:     String,
    sql:        String,
    durationMs: Long,
    status:     String,        // ok | denied | transient | permanent | no-node | no-pool | pin-lost
    error:      Option[String]
)
final case class StatementHistoryResponse(statements: List[StatementHistoryEntry])

// ----- Catalog browser DTOs -----
final case class CatalogSchemaEntry(
    name: String,
    tableCount: Int
)

final case class CatalogTableEntry(
    schema: String,
    name: String,
    rowCount: Long,         // best-effort; -1 when DuckLake stats are missing
    dataFileCount: Int,     // count of __ducklake_data_file rows
    folder: Option[String]  // parent dir of the table's parquet files, derived
                            // from a sample ducklake_data_file.path. None when
                            // no committed data file exists yet.
)

final case class CatalogColumnEntry(
    ordinal: Int,
    name: String,
    typeName: String,
    nullable: Boolean,
    isPrimaryKey: Boolean
)

final case class CatalogDataFileEntry(
    path: String,           // absolute file path or s3:// URL
    sizeBytes: Long,
    rowCount: Long,
    snapshotId: Long
)

final case class CatalogTableDetailResponse(
    table: CatalogTableEntry,
    columns: List[CatalogColumnEntry],
    dataFiles: List[CatalogDataFileEntry]
)

object Dtos:
  given Codec[RoleDistribution]  = deriveCodec
  // Custom codec for CreatePoolRequest so that optional fields with case-class
  // defaults stay optional in the wire JSON (circe 0.14 deriveCodec ignores Scala 3 defaults).
  given Codec[CreatePoolRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenant               <- c.get[String]("tenant")
        pool                 <- c.get[String]("pool")
        size                 <- c.get[Int]("size")
        roleDistribution     <- c.get[RoleDistribution]("roleDistribution")
        metastore            <- c.get[Map[String, String]]("metastore")
        s3                   <- c.getOrElse[Map[String, String]]("s3")(Map.empty)
        idleTimeoutSec       <- c.getOrElse[Int]("idleTimeoutSec")(-1)
        maxConcurrentPerNode <- c.getOrElse[Int]("maxConcurrentPerNode")(0)
      yield CreatePoolRequest(tenant, pool, size, roleDistribution, metastore, s3, idleTimeoutSec, maxConcurrentPerNode)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "tenant"               -> r.tenant.asJson,
        "pool"                 -> r.pool.asJson,
        "size"                 -> r.size.asJson,
        "roleDistribution"     -> r.roleDistribution.asJson,
        "metastore"            -> r.metastore.asJson,
        "s3"                   -> r.s3.asJson,
        "idleTimeoutSec"       -> r.idleTimeoutSec.asJson,
        "maxConcurrentPerNode" -> r.maxConcurrentPerNode.asJson
      ))
    }
  )
  // Same pattern: optional `force` should default to false when absent.
  given Codec[ScalePoolRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenant           <- c.get[String]("tenant")
        pool             <- c.get[String]("pool")
        targetSize       <- c.get[Int]("targetSize")
        roleDistribution <- c.get[RoleDistribution]("roleDistribution")
        force            <- c.getOrElse[Boolean]("force")(false)
      yield ScalePoolRequest(tenant, pool, targetSize, roleDistribution, force)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "tenant"           -> r.tenant.asJson,
        "pool"             -> r.pool.asJson,
        "targetSize"       -> r.targetSize.asJson,
        "roleDistribution" -> r.roleDistribution.asJson,
        "force"            -> r.force.asJson
      ))
    }
  )
  given Codec[StopPoolRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenant <- c.get[String]("tenant")
        pool   <- c.get[String]("pool")
        force  <- c.getOrElse[Boolean]("force")(false)
      yield StopPoolRequest(tenant, pool, force)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "tenant" -> r.tenant.asJson,
        "pool"   -> r.pool.asJson,
        "force"  -> r.force.asJson
      ))
    }
  )
  // Custom codec for NodeInfo so all metric fields default to zero / true
  // when absent in JSON (legacy clients + UI poll responses both go through).
  given Codec[NodeInfo] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        nodeId        <- c.get[String]("nodeId")
        role          <- c.get[String]("role")
        host          <- c.get[String]("host")
        port          <- c.get[Int]("port")
        maxConcurrent <- c.getOrElse[Int]("maxConcurrent")(0)
        inFlight      <- c.getOrElse[Int]("inFlight")(0)
        totalServed   <- c.getOrElse[Long]("totalServed")(0L)
        avgDurationMs <- c.getOrElse[Double]("avgDurationMs")(0.0)
        p50Ms         <- c.getOrElse[Double]("p50Ms")(0.0)
        p95Ms         <- c.getOrElse[Double]("p95Ms")(0.0)
        p99Ms         <- c.getOrElse[Double]("p99Ms")(0.0)
        healthy       <- c.getOrElse[Boolean]("healthy")(true)
        draining      <- c.getOrElse[Boolean]("draining")(false)
      yield NodeInfo(nodeId, role, host, port, maxConcurrent,
                     inFlight, totalServed, avgDurationMs, p50Ms, p95Ms, p99Ms,
                     healthy, draining)
    },
    Encoder.instance { n =>
      Json.fromJsonObject(
        JsonObject(
          "nodeId"        -> n.nodeId.asJson,
          "role"          -> n.role.asJson,
          "host"          -> n.host.asJson,
          "port"          -> n.port.asJson,
          "maxConcurrent" -> n.maxConcurrent.asJson,
          "inFlight"      -> n.inFlight.asJson,
          "totalServed"   -> n.totalServed.asJson,
          "avgDurationMs" -> n.avgDurationMs.asJson,
          "p50Ms"         -> n.p50Ms.asJson,
          "p95Ms"         -> n.p95Ms.asJson,
          "p99Ms"         -> n.p99Ms.asJson,
          "healthy"       -> n.healthy.asJson,
          "draining"      -> n.draining.asJson
        )
      )
    }
  )
  given Codec[PoolResponse] = deriveCodec
  given Codec[PoolListResponse]        = deriveCodec
  given Codec[HealthResponse]          = deriveCodec
  given Codec[SetRoleRequest]          = deriveCodec
  given Codec[SetMaxConcurrentRequest] = deriveCodec
  given Codec[NodeOpRequest]           = deriveCodec
  given Codec[ErrorResponse]           = deriveCodec
  // Hand-rolled so `metastore` defaults to Map.empty when absent - matches the
  // CreatePoolRequest pattern. Lets a client POST `{"name":"acme"}` alone.
  given Codec[TenantRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        name      <- c.get[String]("name")
        metastore <- c.getOrElse[Map[String, String]]("metastore")(Map.empty)
      yield TenantRequest(name, metastore)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "name"      -> r.name.asJson,
        "metastore" -> r.metastore.asJson
      ))
    }
  )
  given Codec[TenantResponse]     = deriveCodec
  given Codec[TenantListResponse] = deriveCodec
  given Codec[TenantOpRequest]    = deriveCodec
  given Codec[ClientConfigResponse] = deriveCodec

  given Codec[IdentityRequest]      = deriveCodec
  given Codec[IdentityResponse]     = deriveCodec
  given Codec[IdentityListResponse] = deriveCodec
  given Codec[IdentityOpRequest]    = deriveCodec

  // ACL grants. Hand-rolled AclGrantRequest decoder so Option fields default
  // to None when absent (circe 0.14 deriveCodec wouldn't honor the case-class
  // defaults on decode).
  given Codec[AclGrantRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenantId    <- c.get[String]("tenantId")
        principal   <- c.get[String]("principal")
        catalogName <- c.getOrElse[Option[String]]("catalogName")(None)
        schemaName  <- c.getOrElse[Option[String]]("schemaName")(None)
        tableName   <- c.getOrElse[Option[String]]("tableName")(None)
        permission  <- c.get[String]("permission")
      yield AclGrantRequest(tenantId, principal, catalogName, schemaName, tableName, permission)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "tenantId"    -> r.tenantId.asJson,
        "principal"   -> r.principal.asJson,
        "catalogName" -> r.catalogName.asJson,
        "schemaName"  -> r.schemaName.asJson,
        "tableName"   -> r.tableName.asJson,
        "permission"  -> r.permission.asJson
      ))
    }
  )
  given Codec[AclGrantResponse]     = deriveCodec
  given Codec[AclGrantListResponse] = deriveCodec
  given Codec[AclGrantBulkRequest]  = deriveCodec
  given Codec[LoginRequest]         = deriveCodec
  given Codec[LoginResponse]        = deriveCodec
  given Codec[WhoamiResponse]       = deriveCodec
  given Codec[StatementHistoryEntry]    = deriveCodec
  given Codec[StatementHistoryResponse] = deriveCodec

  // Catalog browser
  given Codec[CatalogSchemaEntry]         = deriveCodec
  given Codec[CatalogTableEntry]          = deriveCodec
  given Codec[CatalogColumnEntry]         = deriveCodec
  given Codec[CatalogDataFileEntry]       = deriveCodec
  given Codec[CatalogTableDetailResponse] = deriveCodec