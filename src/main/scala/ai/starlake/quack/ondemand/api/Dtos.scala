package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.RoleDistribution
import io.circe.{Codec, Decoder, Encoder, HCursor, Json, JsonObject}
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._

final case class CreatePoolRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    size: Int,
    roleDistribution: RoleDistribution,
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
    tenantDb: String,
    pool: String,
    nodes: List[NodeInfo],
    status: String,
    metastore: Map[String, String] = Map.empty,  // effective metastore inherited from the tenant-db, password redacted
    disabled: Boolean = false                    // when true, the edge rejects fresh handshakes targeting this pool
)

final case class ScalePoolRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    targetSize: Int,
    roleDistribution: RoleDistribution,
    force: Boolean = false
)

final case class StopPoolRequest(tenant: String, tenantDb: String, pool: String, force: Boolean = false)

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

/** One row of the admin UI Config page: a single scalar from
  * `application.conf` with its env-var override and a short
  * description. `value` is masked (`"(set)"` / `"(unset)"`) when
  * `sensitive` is true. */
final case class ConfigEntryView(
    path: String,
    envVar: String,
    description: String,
    value: String,
    sensitive: Boolean,
    isSet: Boolean
)

final case class ConfigListResponse(entries: List[ConfigEntryView])

final case class SetRoleRequest(tenant: String, tenantDb: String, pool: String, nodeId: String, role: String)
final case class SetMaxConcurrentRequest(tenant: String, tenantDb: String, pool: String, nodeId: String, max: Int)
final case class NodeOpRequest(tenant: String, tenantDb: String, pool: String, nodeId: String)

final case class ErrorResponse(error: String, message: String)

final case class TenantRequest(
    name:         String,
    // Auth provider for every user in this tenant. One of
    // {db, keycloak, google, azure, aws}; defaults to `db` so existing
    // wire callers and the bootstrap path keep working.
    authProvider: String              = "db",
    // Provider-specific config. Empty for `db`; for OIDC providers
    // expect `issuer` (full URL) plus one of `realm` / `hd` /
    // `tenantId` / `userPoolId`.
    authConfig:   Map[String, String] = Map.empty
)
final case class TenantResponse(
    name: String,
    // Pool natural keys under this tenant, formatted as `tenantDb/pool`
    // (the tenant prefix is implicit). Storage configuration lives on
    // `qodstate_tenant_db` rows, not here.
    pools:        List[String],
    disabled:     Boolean             = false,
    authProvider: String              = "db",
    authConfig:   Map[String, String] = Map.empty
)
final case class TenantListResponse(tenants: List[TenantResponse])
final case class TenantOpRequest(name: String)

final case class SetTenantDisabledRequest(name: String, disabled: Boolean)
final case class SetTenantAuthRequest(
    name:         String,
    authProvider: String,
    authConfig:   Map[String, String] = Map.empty
)
final case class SetPoolDisabledRequest(tenant: String, tenantDb: String, pool: String, disabled: Boolean)

// ----- Tenant databases (qodstate_tenant_db) -----------------------------
// One row per `(tenant, name)` -- the name being composed
// `${tenant}_${suffix}` and used verbatim as the actual Postgres
// database name on the shared server.
final case class TenantDbRequest(
    tenant:      String,
    // Suffix typed by the user; the supervisor composes the full
    // database name as `${tenant}_${suffix}` (idempotent if the caller
    // already passed the full form).
    name:        String,
    metastore:   Map[String, String] = Map.empty,
    dataPath:    String              = "",
    objectStore: Map[String, String] = Map.empty
)
final case class TenantDbResponse(
    id:          String,
    tenant:      String,
    name:        String,
    metastore:   Map[String, String],
    dataPath:    String,
    objectStore: Map[String, String] = Map.empty,
    disabled:    Boolean             = false
)
final case class TenantDbListResponse(tenantDbs: List[TenantDbResponse])
final case class TenantDbOpRequest(tenant: String, name: String)

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

// ----- RBAC: users -------------------------------------------------------

final case class UserCreateRequest(
    tenant:   Option[String],         // None = superuser
    username: String,
    password: String,
    role:     String = "user"
)
final case class UserUpdateRequest(
    id:       String,
    tenant:   Option[String] = None,  // None = leave unchanged
    password: Option[String] = None,  // None = no rotation
    role:     Option[String] = None
)
final case class UserDeleteRequest(id: String)
final case class UserResponse(
    id:       String,
    tenant:   Option[String],
    username: String,
    role:     String,
    enabled:  Boolean = true,
    roles:    List[String] = Nil,     // role NAMES (not ids), tenant-scoped
    groups:   List[String] = Nil,     // group NAMES
    poolGrants: List[String] = Nil    // human label "tenant/pool" or "tenant/*"
)
final case class UserListResponse(users: List[UserResponse])

/** GET /user/{id}/effective response. Permits the UI to show what a
  * given user can actually do without spelunking through roles/groups. */
final case class EffectivePermissionsResponse(
    user:       UserResponse,
    roles:      List[RoleResponse],
    groups:     List[GroupResponse],
    pools:      List[PoolPermissionResponse],
    tablePerms: List[RolePermissionResponse]
)

// ----- RBAC: roles -------------------------------------------------------

final case class RoleCreateRequest(
    tenant:      String,
    name:        String,
    description: Option[String] = None
)
final case class RoleDeleteRequest(id: String)
final case class RoleResponse(
    id:          String,
    tenantId:    String,
    name:        String,
    description: Option[String],
    createdAt:   String
)
final case class RoleListResponse(roles: List[RoleResponse])

final case class RolePermissionGrantRequest(
    roleId:  String,
    catalog: String = "*",
    schema:  String = "*",
    table:   String = "*",
    verb:    String                   // SELECT | INSERT | UPDATE | DELETE | ALL
)
final case class RolePermissionRevokeRequest(id: String)
final case class RolePermissionResponse(
    id:          String,
    roleId:      String,
    catalogName: String,
    schemaName:  String,
    tableName:   String,
    verb:        String,
    grantedAt:   String
)
final case class RolePermissionListResponse(permissions: List[RolePermissionResponse])

// ----- RBAC: groups ------------------------------------------------------

final case class GroupCreateRequest(
    tenant:      String,
    name:        String,
    description: Option[String] = None
)
final case class GroupDeleteRequest(id: String)
final case class GroupResponse(
    id:          String,
    tenantId:    String,
    name:        String,
    description: Option[String]
)
final case class GroupListResponse(groups: List[GroupResponse])

// ----- RBAC: memberships -------------------------------------------------

final case class UserRoleMembershipRequest (userId: String, roleId:  String)
final case class UserGroupMembershipRequest(userId: String, groupId: String)
final case class GroupRoleMembershipRequest(groupId: String, roleId: String)

// ----- RBAC: pool permissions --------------------------------------------

final case class PoolPermissionGrantRequest(
    tenant:  String,
    poolId:  Option[String] = None,   // None = all pools in tenant
    userId:  Option[String] = None,
    groupId: Option[String] = None    // exactly one of userId / groupId must be set
)
final case class PoolPermissionRevokeRequest(id: String)
final case class PoolPermissionResponse(
    id:        String,
    tenantId:  String,
    poolId:    Option[String],
    userId:    Option[String],
    groupId:   Option[String],
    grantedAt: String
)
final case class PoolPermissionListResponse(permissions: List[PoolPermissionResponse])

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
        tenantDb             <- c.get[String]("tenantDb")
        pool                 <- c.get[String]("pool")
        size                 <- c.get[Int]("size")
        roleDistribution     <- c.get[RoleDistribution]("roleDistribution")
        idleTimeoutSec       <- c.getOrElse[Int]("idleTimeoutSec")(-1)
        maxConcurrentPerNode <- c.getOrElse[Int]("maxConcurrentPerNode")(0)
      yield CreatePoolRequest(tenant, tenantDb, pool, size, roleDistribution, idleTimeoutSec, maxConcurrentPerNode)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "tenant"               -> r.tenant.asJson,
        "tenantDb"             -> r.tenantDb.asJson,
        "pool"                 -> r.pool.asJson,
        "size"                 -> r.size.asJson,
        "roleDistribution"     -> r.roleDistribution.asJson,
        "idleTimeoutSec"       -> r.idleTimeoutSec.asJson,
        "maxConcurrentPerNode" -> r.maxConcurrentPerNode.asJson
      ))
    }
  )
  given Codec[ScalePoolRequest]  = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenant           <- c.get[String]("tenant")
        tenantDb         <- c.get[String]("tenantDb")
        pool             <- c.get[String]("pool")
        targetSize       <- c.get[Int]("targetSize")
        roleDistribution <- c.get[RoleDistribution]("roleDistribution")
        force            <- c.getOrElse[Boolean]("force")(false)
      yield ScalePoolRequest(tenant, tenantDb, pool, targetSize, roleDistribution, force)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "tenant"           -> r.tenant.asJson,
        "tenantDb"         -> r.tenantDb.asJson,
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
        tenant   <- c.get[String]("tenant")
        tenantDb <- c.get[String]("tenantDb")
        pool     <- c.get[String]("pool")
        force    <- c.getOrElse[Boolean]("force")(false)
      yield StopPoolRequest(tenant, tenantDb, pool, force)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "tenant"   -> r.tenant.asJson,
        "tenantDb" -> r.tenantDb.asJson,
        "pool"     -> r.pool.asJson,
        "force"    -> r.force.asJson
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
  // TenantRequest: hand-rolled so the optional auth fields default
  // correctly when absent from the wire JSON.
  given Codec[TenantRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        name         <- c.get[String]("name")
        authProvider <- c.getOrElse[String]("authProvider")("db")
        authConfig   <- c.getOrElse[Map[String, String]]("authConfig")(Map.empty)
      yield TenantRequest(name, authProvider, authConfig)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "name"         -> r.name.asJson,
        "authProvider" -> r.authProvider.asJson,
        "authConfig"   -> r.authConfig.asJson
      ))
    }
  )
  given Codec[TenantResponse]           = deriveCodec
  given Codec[TenantListResponse]       = deriveCodec
  given Codec[TenantOpRequest]          = deriveCodec
  given Codec[SetTenantDisabledRequest] = deriveCodec
  // Hand-rolled so authConfig defaults to empty when absent from the wire JSON.
  given Codec[SetTenantAuthRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        name         <- c.get[String]("name")
        authProvider <- c.get[String]("authProvider")
        authConfig   <- c.getOrElse[Map[String, String]]("authConfig")(Map.empty)
      yield SetTenantAuthRequest(name, authProvider, authConfig)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "name"         -> r.name.asJson,
        "authProvider" -> r.authProvider.asJson,
        "authConfig"   -> r.authConfig.asJson
      ))
    }
  )
  given Codec[SetPoolDisabledRequest]   = deriveCodec
  given Codec[ClientConfigResponse] = deriveCodec
  given Codec[ConfigEntryView]      = deriveCodec
  given Codec[ConfigListResponse]   = deriveCodec

  given Codec[TenantDbRequest]      = deriveCodec
  given Codec[TenantDbResponse]     = deriveCodec
  given Codec[TenantDbListResponse] = deriveCodec
  given Codec[TenantDbOpRequest]    = deriveCodec

  given Codec[LoginRequest]         = deriveCodec
  given Codec[LoginResponse]        = deriveCodec
  given Codec[WhoamiResponse]       = deriveCodec
  given Codec[StatementHistoryEntry]    = deriveCodec
  given Codec[StatementHistoryResponse] = deriveCodec

  // RBAC: users
  // Custom decoders so Option[String] fields keep their case-class defaults
  // when absent from the wire JSON (circe deriveCodec doesn't honor those).
  given Codec[UserCreateRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenant   <- c.getOrElse[Option[String]]("tenant")(None)
        username <- c.get[String]("username")
        password <- c.get[String]("password")
        role     <- c.getOrElse[String]("role")("user")
      yield UserCreateRequest(tenant, username, password, role)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "tenant"   -> r.tenant.asJson,
        "username" -> r.username.asJson,
        "password" -> r.password.asJson,
        "role"     -> r.role.asJson
      ))
    }
  )
  given Codec[UserUpdateRequest]            = deriveCodec
  given Codec[UserDeleteRequest]            = deriveCodec
  given Codec[UserResponse]                 = deriveCodec
  given Codec[UserListResponse]             = deriveCodec

  // RBAC: roles
  given Codec[RoleCreateRequest]            = deriveCodec
  given Codec[RoleDeleteRequest]            = deriveCodec
  given Codec[RoleResponse]                 = deriveCodec
  given Codec[RoleListResponse]             = deriveCodec
  given Codec[RolePermissionGrantRequest]   = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        roleId  <- c.get[String]("roleId")
        catalog <- c.getOrElse[String]("catalog")("*")
        schema  <- c.getOrElse[String]("schema") ("*")
        table   <- c.getOrElse[String]("table")  ("*")
        verb    <- c.get[String]("verb")
      yield RolePermissionGrantRequest(roleId, catalog, schema, table, verb)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(JsonObject(
        "roleId"  -> r.roleId.asJson,
        "catalog" -> r.catalog.asJson,
        "schema"  -> r.schema.asJson,
        "table"   -> r.table.asJson,
        "verb"    -> r.verb.asJson
      ))
    }
  )
  given Codec[RolePermissionRevokeRequest]  = deriveCodec
  given Codec[RolePermissionResponse]       = deriveCodec
  given Codec[RolePermissionListResponse]   = deriveCodec

  // RBAC: groups
  given Codec[GroupCreateRequest]           = deriveCodec
  given Codec[GroupDeleteRequest]           = deriveCodec
  given Codec[GroupResponse]                = deriveCodec
  given Codec[GroupListResponse]            = deriveCodec

  given Codec[UserRoleMembershipRequest]    = deriveCodec
  given Codec[UserGroupMembershipRequest]   = deriveCodec
  given Codec[GroupRoleMembershipRequest]   = deriveCodec

  // RBAC: pool permissions
  given Codec[PoolPermissionGrantRequest]   = deriveCodec
  given Codec[PoolPermissionRevokeRequest]  = deriveCodec
  given Codec[PoolPermissionResponse]       = deriveCodec
  given Codec[PoolPermissionListResponse]   = deriveCodec

  given Codec[EffectivePermissionsResponse] = deriveCodec

  // Catalog browser
  given Codec[CatalogSchemaEntry]         = deriveCodec
  given Codec[CatalogTableEntry]          = deriveCodec
  given Codec[CatalogColumnEntry]         = deriveCodec
  given Codec[CatalogDataFileEntry]       = deriveCodec
  given Codec[CatalogTableDetailResponse] = deriveCodec