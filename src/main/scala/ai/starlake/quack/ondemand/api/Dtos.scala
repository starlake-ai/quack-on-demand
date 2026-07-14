package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{NodePlacement, NodeToleration, PoolCohort, RoleDistribution}
import io.circe.{Codec, Decoder, Encoder, HCursor, Json, JsonObject}
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._

final case class NodeTolerationDto(
    key: String,
    operator: String = "Equal",
    value: Option[String] = None,
    effect: Option[String] = None
)
final case class NodePlacementDto(
    nodeSelector: Map[String, String] = Map.empty,
    tolerations: List[NodeTolerationDto] = Nil
)
final case class PoolCohortDto(
    placement: NodePlacementDto = NodePlacementDto(),
    distribution: RoleDistribution
)

object PoolCohortDto:
  def fromModel(c: PoolCohort): PoolCohortDto =
    PoolCohortDto(
      placement = NodePlacementDto(
        nodeSelector = c.placement.nodeSelector,
        tolerations =
          c.placement.tolerations.map(t => NodeTolerationDto(t.key, t.operator, t.value, t.effect))
      ),
      distribution = c.distribution
    )
  def toModel(d: PoolCohortDto): PoolCohort =
    PoolCohort(
      placement = NodePlacement(
        nodeSelector = d.placement.nodeSelector,
        tolerations =
          d.placement.tolerations.map(t => NodeToleration(t.key, t.operator, t.value, t.effect))
      ),
      distribution = d.distribution
    )

final case class CreatePoolRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    size: Int,
    roleDistribution: RoleDistribution,
    idleTimeoutSec: Int = -1,
    maxConcurrentPerNode: Int = 0,
    // Optional placement plan. When empty, the supervisor schedules
    // every node with no constraint. When non-empty, the per-cohort
    // RoleDistributions must sum to `roleDistribution` and the total
    // count must equal `size`. Ignored in non-K8s mode.
    cohorts: List[PoolCohortDto] = Nil,
    // When true, the pool is persisted with `disabled = true` so the
    // FlightSQL edge rejects fresh handshakes until enabled. Nodes are
    // still spawned (the pool is "warm but not serving"); this matches
    // the semantics of `setPoolDisabled` applied after the fact.
    disabled: Boolean = false,
    /** Free-form per-pool SQL prepended to the resolved federation blob and shipped to
      * spawn-quack-node.sh via the node's `$extraSetupSql` env var. PRAGMAs / SET / INSTALL / LOAD
      * live here ("SET memory_limit='8GB'", "INSTALL httpfs", etc.); ATTACH aliases live on
      * federation sources. Empty by default. Editing on a running pool takes effect on the NEXT
      * node spawn (scale-up, crash-recovery, manual restart); running nodes keep their old setup.
      */
    initSql: Option[String] = None,
    cpu: String = "",
    memory: String = "",
    podTemplateYaml: String = ""
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
    draining: Boolean = false,
    // Operator quarantine, mirrored from NodeLoad for the UI badge and action toggle.
    quarantined: Boolean = false,
    // DuckDB engine internals scraped by the HealthProbe (EngineStats): buffer-manager
    // memory, temp storage, and live spill files. None until the first successful scrape.
    duckdbMemoryBytes: Option[Long] = None,
    duckdbTempStorageBytes: Option[Long] = None,
    duckdbSpillFiles: Option[Long] = None,
    duckdbSpillBytes: Option[Long] = None
)

final case class PoolResponse(
    tenant: String,
    tenantDb: String,
    pool: String,
    nodes: List[NodeInfo],
    status: String,
    metastore: Map[String, String] =
      Map.empty, // effective metastore inherited from the tenant-db, password redacted
    disabled: Boolean = false, // when true, the edge rejects fresh handshakes targeting this pool
    id: String = "", // qodstate_pool.id; needed by RBAC pool-grant UI to map (tenant, pool) -> id
    cohorts: List[PoolCohortDto] = Nil, // persisted placement plan, empty when none was supplied
    initSql: String = "",               // operator-authored init SQL; empty when none was supplied
    cpu: String = "",                   // Kubernetes cpu request=limit; empty when unset
    memory: String = ""                 // Kubernetes memory request=limit; empty when unset
)

final case class SetPoolResourcesRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    cpu: String,
    memory: String
)

final case class SetPoolTemplateRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    podTemplateYaml: String
)

final case class ScalePoolRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    targetSize: Int,
    roleDistribution: RoleDistribution,
    force: Boolean = false
)

final case class StopPoolRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    force: Boolean = false
)

final case class DeletePoolRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    force: Boolean = false
)

final case class PoolListResponse(pools: List[PoolResponse])
final case class HealthResponse(status: String, poolsCount: Int, nodesCount: Int)

final case class ClientConfigResponse(
    flightSqlHost: String, // may be "0.0.0.0" - UI should substitute window.location.hostname
    flightSqlPort: Int,
    flightSqlTls: Boolean,
    // True iff any basic / bearer auth provider is configured. When false,
    // the UI skips the login screen entirely (there's no credential
    // backend to validate against; `/api/auth/login` would 503).
    authEnabled: Boolean = true,
    // True iff the runtime backend is Kubernetes (i.e. supports
    // nodeSelector / tolerations placement). The UI hides the per-pool
    // cohort/placement controls when false.
    placementSupported: Boolean = false,
    // "db" or "oidc". When "oidc" the UI renders no password form and instead
    // redirects to /api/auth/oidc/start.
    identitySource: String = "db",
    // Best-effort label for the SSO provider (the issuer host, e.g.
    // "accounts.google.com"); empty in db mode. Cosmetic only (UI copy).
    ssoProviderName: String = "",
    // True iff telemetry.store != none; the UI uses this to show or hide
    // the audit-log page.
    telemetryEnabled: Boolean = true
)

/** One row of the admin UI Config page: a single scalar from `application.conf` with its env-var
  * override and a short description. `value` is masked (`"(set)"` / `"(unset)"`) when `sensitive`
  * is true.
  */
final case class ConfigEntryView(
    path: String,
    envVar: String,
    description: String,
    value: String,
    sensitive: Boolean,
    isSet: Boolean
)

final case class ConfigListResponse(entries: List[ConfigEntryView])

final case class ManifestImportSummary(
    tenants: Int,
    tenantDbs: Int,
    pools: Int,
    roles: Int,
    groups: Int,
    users: Int
)

final case class SetMaxConcurrentRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    nodeId: String,
    max: Int
)
final case class NodeOpRequest(tenant: String, tenantDb: String, pool: String, nodeId: String)

final case class ActiveStatementInfo(
    id: String,
    user: String,
    tenant: String,
    pool: String,
    nodeId: String,
    sql: String,
    startedAt: String, // ISO-8601 UTC
    elapsedMs: Long
)
final case class ActiveStatementsResponse(statements: List[ActiveStatementInfo])
final case class KillStatementRequest(id: String)
final case class KillStatementResponse(status: String) // accepted | already-completed

final case class ErrorResponse(error: String, message: String)

final case class TenantRequest(
    // Slug key (required): a lowercase identifier that IS the tenant's one
    // key, used in URLs, sessions, scope checks, and FKs. e.g. "acme".
    id: String,
    // Free-form human label (caps / spaces allowed). Defaults to `id` when
    // empty. e.g. "Acme Corporation".
    displayName: String = "",
    // Auth provider for every user in this tenant. One of
    // {db, keycloak, google, azure, aws}; defaults to `db` so existing
    // wire callers and the bootstrap path keep working.
    authProvider: String = "db",
    // Provider-specific config. Empty for `db`; for OIDC providers
    // expect `issuer` (full URL) plus one of `realm` / `hd` /
    // `tenantId` / `userPoolId`.
    authConfig: Map[String, String] = Map.empty
)
final case class TenantResponse(
    // The slug key. Also exposed as `id` (identical) for callers that key on
    // either field; both hold the slug.
    name: String,
    id: String,
    // Free-form human label. May differ from `id` (e.g. "Acme Corporation").
    displayName: String,
    // Pool natural keys under this tenant, formatted as `tenantDb/pool`
    // (the tenant prefix is implicit). Storage configuration lives on
    // `qodstate_tenant_db` rows, not here.
    pools: List[String],
    disabled: Boolean = false,
    authProvider: String = "db",
    authConfig: Map[String, String] = Map.empty
)
final case class TenantListResponse(tenants: List[TenantResponse])
final case class TenantOpRequest(name: String)

final case class SetTenantDisabledRequest(name: String, disabled: Boolean)
final case class SetTenantAuthRequest(
    name: String,
    authProvider: String,
    authConfig: Map[String, String] = Map.empty
)
final case class SetPoolDisabledRequest(
    tenant: String,
    tenantDb: String,
    pool: String,
    disabled: Boolean
)

// ----- Tenant databases (qodstate_tenant_db) -----------------------------
// One row per `(tenant, name)` -- the name being composed
// `${tenant}_${suffix}` and used verbatim as the actual Postgres
// database name on the shared server.
final case class TenantDbRequest(
    tenant: String,
    // Suffix typed by the user; the supervisor composes the full
    // database name as `${tenant}_${suffix}` (idempotent if the caller
    // already passed the full form).
    name: String,
    kind: String = "ducklake", // wire value: ducklake | duckdb-file | memory
    metastore: Map[String, String] = Map.empty,
    dataPath: String = "",
    objectStore: Map[String, String] = Map.empty,
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    initSql: String = ""
)
final case class TenantDbResponse(
    id: String,
    tenant: String,
    name: String,
    kind: String, // wire value
    metastore: Map[String, String],
    dataPath: String,
    objectStore: Map[String, String] = Map.empty,
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    disabled: Boolean = false,
    federatedSourceCount: Int = 0,
    initSql: String = "",
    // Resolved data path (stored value, or default base + db name when stored
    // is empty), so the UI can show the real location instead of a dash.
    effectiveDataPath: String = "",
    // Tables across all schemas via the DuckLake catalog reader; None for
    // non-DuckLake kinds or when the catalog is unreachable.
    tableCount: Option[Int] = None
)
final case class TenantDbListResponse(tenantDbs: List[TenantDbResponse])
final case class TenantDbOpRequest(tenant: String, name: String)

final case class UpdateTenantDbRequest(
    tenant: String,
    name: String,
    metastore: Option[Map[String, String]] = None,
    objectStore: Option[Map[String, String]] = None,
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    initSql: Option[String] = None
)
final case class FailedRestart(nodeId: String, message: String)
final case class UpdateTenantDbResponse(
    db: TenantDbResponse,
    restartedNodes: List[String] = Nil,
    failedRestarts: List[FailedRestart] = Nil
)

// ----- Federation -----

final case class FederatedSourceCreateRequest(
    alias: String,
    setupSql: String,
    description: Option[String] = None,
    disabled: Boolean = false
)

final case class FederatedSourceResponse(
    id: String,
    tenantDbId: String,
    alias: String,
    setupSql: String,
    description: Option[String] = None,
    disabled: Boolean = false
)

final case class FederatedSourceListResponse(sources: List[FederatedSourceResponse])

final case class FederatedSecretUpsertRequest(
    name: String,
    value: Option[String] = None,      // postgres-resolver backed
    externalRef: Option[String] = None // external-resolver backed
)

final case class FederatedSecretResponse(
    id: String,
    federatedSourceId: String,
    name: String,
    value: Option[String], // ALWAYS redacted in response: "***REDACTED***" if stored as non-null
    externalRef: Option[String]
)

final case class FederatedSecretListResponse(secrets: List[FederatedSecretResponse])

// ----- UI login -----
/** `tenant` is the optional tenant id (e.g. `t-02d0e86e`). Leave it `None`/blank for a system-admin
  * login (`qodstate_user.tenant IS NULL`). Tenant-scoped users (`qodstate_user.tenant = ?`) must
  * send their tenant id.
  */
final case class LoginRequest(
    username: String,
    password: String,
    tenant: Option[String] = None
)

/** Login response. Carries the session token + the authority bits the UI needs to decide what to
  * render. `role` is deliberately NOT here: every minted session is admin by construction (the
  * login handler 403s anything else), so the field was a tautology of the gate. UIs that want to
  * show the user's authoritative role read [[WhoamiResponse.role]] (sourced from the auth backend's
  * profile) instead.
  */
final case class LoginResponse(
    token: String,
    username: String,
    tenant: Option[String] = None,
    superuser: Boolean = false,
    manageableTenants: List[String] = Nil
)
final case class WhoamiResponse(
    username: String,
    role: String,
    tenant: Option[String] = None,
    superuser: Boolean = false,
    manageableTenants: List[String] = Nil
)

/** Resolved admin-UI login mode for a scope, returned by the unauthenticated `GET /api/auth/mode`.
  * The SPA reads this per the tenant in the URL to decide whether to render the password form
  * (`db`) or redirect to `/api/auth/oidc/start` (`oidc`).
  */
final case class AuthModeResponse(
    // "db" -> render the password form; "oidc" -> redirect to /api/auth/oidc/start.
    mode: String,
    // Cosmetic provider label (issuer host for a tenant, manager-wide provider for system).
    ssoProviderName: String = ""
)

// ----- Recent statement history -----
final case class StatementHistoryEntry(
    ts: String, // ISO-8601 UTC
    user: String,
    tenant: String,
    pool: String,
    nodeId: String,
    sql: String,
    durationMs: Long,
    status: String, // ok | denied | transient | permanent | no-node | no-pool | pin-lost
    error: Option[String],
    /** Time the FlightSQL Prepare-time LIMIT-0 probe spent on the node, when this Execute belongs
      * to a prepared-statement round. The UI renders it as subtext under the Execute duration ("57
      * ms / prep 28 ms"). Absent for one-shot statements and for SkipExecute Prepare paths (DML /
      * DDL / transaction control).
      */
    prepareDurationMs: Option[Long] = None
)
final case class StatementHistoryResponse(statements: List[StatementHistoryEntry])

// ----- History: trends and statement search ------------------------------

final case class TrendBucketEntry(
    bucketStart: String,
    tenant: String,
    pool: String,
    username: String,
    stmtCount: Long,
    errorCount: Long,
    deniedCount: Long,
    engineMsSum: Long,
    p50Ms: Option[Double],
    p95Ms: Option[Double],
    p99Ms: Option[Double]
)
final case class TrendsResponse(buckets: List[TrendBucketEntry])

final case class StatementHistoryRowEntry(
    id: String,
    ts: String,
    username: String,
    tenant: String,
    pool: String,
    nodeId: String,
    sql: String,
    durationMs: Long,
    prepareMs: Option[Long],
    status: String,
    error: Option[String]
)
final case class StatementSearchResponse(
    statements: List[StatementHistoryRowEntry],
    nextBefore: Option[String]
)

// ----- Usage and accounting ------------------------------------------------

final case class UsageDayEntry(day: String, statements: Long, errors: Long, engineMs: Long)
final case class UsageGroupEntry(
    tenant: String,
    pool: Option[String],
    username: Option[String],
    statements: Long,
    errors: Long,
    denied: Long,
    engineMs: Long,
    days: List[UsageDayEntry]
)
final case class UsageResponse(
    from: String,
    to: String,
    groupBy: String,
    dataStart: Option[String],
    groups: List[UsageGroupEntry]
)

// ----- Audit log ---------------------------------------------------------

final case class AuditEventEntry(
    id: String,
    ts: String,
    family: String,
    actor: String,
    actorRealm: String,
    tenant: Option[String],
    action: String,
    target: Option[String],
    outcome: String,
    origin: String,
    detail: Map[String, String]
)
final case class AuditListResponse(events: List[AuditEventEntry], nextBefore: Option[String])
final case class AuditActionsResponse(actions: List[String])

// ----- RBAC: users -------------------------------------------------------

final case class UserCreateRequest(
    tenant: Option[String], // None = superuser
    username: String,
    password: String,
    role: String = "user"
)
final case class UserUpdateRequest(
    id: String,
    tenant: Option[String] = None,   // None = leave unchanged
    password: Option[String] = None, // None = no rotation
    role: Option[String] = None
)
final case class UserDeleteRequest(id: String)
final case class UserResponse(
    id: String,
    tenant: Option[String],
    username: String,
    role: String,
    enabled: Boolean = true,
    roles: List[String] = Nil,     // role NAMES (not ids), tenant-scoped
    groups: List[String] = Nil,    // group NAMES
    poolGrants: List[String] = Nil // human label "tenant/pool" or "tenant/*"
)
final case class UserListResponse(users: List[UserResponse])

/** GET /user/{id}/effective response. Permits the UI to show what a given user can actually do
  * without spelunking through roles/groups.
  */
final case class EffectivePermissionsResponse(
    user: UserResponse,
    roles: List[RoleResponse],
    groups: List[GroupResponse],
    pools: List[PoolPermissionResponse],
    tablePerms: List[RolePermissionResponse]
)

// ----- RBAC: roles -------------------------------------------------------

final case class RoleCreateRequest(
    tenant: String,
    name: String,
    description: Option[String] = None
)
final case class RoleDeleteRequest(id: String)
final case class RoleResponse(
    id: String,
    tenantId: String,
    name: String,
    description: Option[String],
    createdAt: String
)
final case class RoleListResponse(roles: List[RoleResponse])

final case class RolePermissionGrantRequest(
    roleId: String,
    catalog: String = "*",
    schema: String = "*",
    table: String = "*",
    verb: String // SELECT | INSERT | UPDATE | DELETE | ALL
)
final case class RolePermissionRevokeRequest(id: String)
final case class RolePermissionResponse(
    id: String,
    roleId: String,
    catalogName: String,
    schemaName: String,
    tableName: String,
    verb: String,
    grantedAt: String
)
final case class RolePermissionListResponse(permissions: List[RolePermissionResponse])

// ----- RBAC: groups ------------------------------------------------------

final case class GroupCreateRequest(
    tenant: String,
    name: String,
    description: Option[String] = None
)
final case class GroupDeleteRequest(id: String)
final case class GroupResponse(
    id: String,
    tenantId: String,
    name: String,
    description: Option[String]
)
final case class GroupListResponse(groups: List[GroupResponse])

// ----- RBAC: memberships -------------------------------------------------

final case class UserRoleMembershipRequest(userId: String, roleId: String)
final case class UserGroupMembershipRequest(userId: String, groupId: String)
final case class GroupRoleMembershipRequest(groupId: String, roleId: String)

// ----- RBAC: column policies ---------------------------------------------

final case class ColumnPolicyDto(
    id: String,
    roleId: String,
    catalogName: String,
    schemaName: String,
    tableName: String,
    columnName: String,
    action: String,
    transformSql: Option[String]
)
final case class CreateColumnPolicyRequest(
    roleId: String,
    catalogName: String,
    schemaName: String,
    tableName: String,
    columnName: String,
    action: String,
    transformSql: Option[String] = None
)
final case class UpdateColumnPolicyRequest(
    id: String,
    action: String,
    transformSql: Option[String] = None
)
final case class DeleteColumnPolicyRequest(id: String)
final case class ColumnPolicyListResponse(policies: List[ColumnPolicyDto])

// ----- RBAC: row policies ------------------------------------------------

final case class RowPolicyDto(
    id: String,
    roleId: String,
    catalogName: String,
    schemaName: String,
    tableName: String,
    predicateSql: String
)
final case class CreateRowPolicyRequest(
    roleId: String,
    catalogName: String,
    schemaName: String,
    tableName: String,
    predicateSql: String
)
final case class UpdateRowPolicyRequest(
    id: String,
    predicateSql: String
)
final case class DeleteRowPolicyRequest(id: String)
final case class RowPolicyListResponse(policies: List[RowPolicyDto])

// ----- RBAC: pool permissions --------------------------------------------

final case class PoolPermissionGrantRequest(
    tenant: String,
    poolId: Option[String] = None, // None = all pools in tenant
    userId: Option[String] = None,
    groupId: Option[String] = None // exactly one of userId / groupId must be set
)
final case class PoolPermissionRevokeRequest(id: String)
final case class PoolPermissionResponse(
    id: String,
    tenantId: String,
    poolId: Option[String],
    userId: Option[String],
    groupId: Option[String],
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
    rowCount: Long,        // best-effort; -1 when DuckLake stats are missing
    dataFileCount: Int,    // count of __ducklake_data_file rows
    folder: Option[String] // parent dir of the table's parquet files, derived
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
    path: String, // absolute file path or s3:// URL
    sizeBytes: Long,
    rowCount: Long,
    snapshotId: Long
)

final case class CatalogTableDetailResponse(
    table: CatalogTableEntry,
    columns: List[CatalogColumnEntry],
    dataFiles: List[CatalogDataFileEntry],
    resolvedSnapshot: Option[Long] = None,
    resolvedAt: Option[java.time.Instant] = None
)

final case class CatalogTableRef(
    schema: String,
    name: String
)

final case class CatalogSnapshotEntry(
    snapshotId: Long,
    committedAt: String, // ISO-8601, from ducklake_snapshot.snapshot_time
    schemaVersion: Long,
    changes: String, // raw ducklake_snapshot_changes.changes_made ('' when absent)
    rowsAdded: Long,
    filesAdded: Int,
    filesRemoved: Int,
    affectedTables: List[CatalogTableRef],
    author: Option[String] = None,       // ducklake_snapshot_changes.author, P1 stamping
    commitMessage: Option[String] = None // ducklake_snapshot_changes.commit_message
)

// ----- Per-table history / audit timeline (EPIC Spec 01) -----

final case class CatalogHistoryTableRef(
    schema: String,
    name: String,
    tableId: Long // stable DuckLake table_id; history identity survives renames
)

final case class CatalogHistoryCommit(
    snapshotId: Long,
    committedAt: String,           // ISO-8601, from ducklake_snapshot.snapshot_time
    operation: String,             // one of HistoryOperation.Values
    author: Option[String],        // null on pre-stamping snapshots
    commitMessage: Option[String], // null when the writer set none
    schemaChanged: Boolean,
    schemaVersion: Long,
    rowsAdded: Long,
    rowsRemoved: Long,
    filesAdded: Int,
    filesRemoved: Int
)

final case class CatalogHistoryResponse(
    table: CatalogHistoryTableRef,
    commits: List[CatalogHistoryCommit], // snapshotId DESC
    hasMore: Boolean
)

// ----- Snapshot tags (EPIC P2 / Spec 06) -----

/** Wire representation of a snapshot tag. The `isProtected` Scala field maps to `"protected"` on
  * the wire (hand-rolled codec avoids the Scala keyword collision).
  */
final case class CatalogTagEntry(
    name: String,
    snapshotId: Long,
    isProtected: Boolean,
    createdBy: Option[String],
    createdAt: Option[java.time.Instant],
    exists: Boolean
)

final case class TagCreateRequest(
    tenant: String,
    tenantDb: String,
    name: String,
    snapshotId: Long,
    isProtected: Boolean = false
)

final case class TagDeleteRequest(tenant: String, tenantDb: String, name: String)

final case class TagProtectRequest(
    tenant: String,
    tenantDb: String,
    name: String,
    isProtected: Boolean
)

// ----- Managed maintenance (EPIC Spec 09) -----

final case class MaintenancePolicyUpsertRequest(
    tenant: String,
    tenantDb: String,
    scopeKind: String, // "tenantdb" | "schema" | "table"
    scopeSchema: Option[String] = None,
    scopeTable: Option[String] = None,
    enabled: Option[Boolean] = None,
    retentionDays: Option[Int] = None,
    compactionEnabled: Option[Boolean] = None,
    targetFileSize: Option[String] = None,
    smallFileMinCount: Option[Int] = None,
    rewriteDeleteThreshold: Option[Double] = None,
    cleanupGraceDays: Option[Int] = None,
    orphanMinAgeDays: Option[Int] = None,
    cron: Option[String] = None
)

final case class MaintenancePolicyDeleteRequest(id: String)

final case class MaintenancePolicyEntry(
    id: String,
    tenant: String,
    tenantDb: String,
    scopeKind: String,
    scopeSchema: Option[String],
    scopeTable: Option[String],
    enabled: Option[Boolean],
    retentionDays: Option[Int],
    compactionEnabled: Option[Boolean],
    targetFileSize: Option[String],
    smallFileMinCount: Option[Int],
    rewriteDeleteThreshold: Option[Double],
    cleanupGraceDays: Option[Int],
    orphanMinAgeDays: Option[Int],
    cron: Option[String],
    updatedAt: Option[java.time.Instant]
)

final case class MaintenanceEffectiveEntry(
    enabled: Boolean,
    retentionDays: Int,
    compactionEnabled: Boolean,
    targetFileSize: String,
    smallFileMinCount: Int,
    rewriteDeleteThreshold: Double,
    cleanupGraceDays: Int,
    orphanMinAgeDays: Int,
    cron: String
)

final case class MaintenancePolicyListResponse(
    rows: List[MaintenancePolicyEntry],
    effective: MaintenanceEffectiveEntry
)

final case class MaintenanceRunEntry(
    id: Long,
    tenant: String,
    tenantDb: String,
    scope: String,
    trigger: String,
    operations: Option[String],
    status: String,
    queuedAt: java.time.Instant,
    startedAt: Option[java.time.Instant],
    finishedAt: Option[java.time.Instant],
    heartbeatAt: Option[java.time.Instant],
    nodeId: Option[String],
    snapshotsExpired: Int,
    snapshotsSkippedPinned: Int,
    filesMerged: Int,
    filesRewritten: Int,
    filesCleaned: Int,
    orphansDeleted: Int,
    bytesReclaimed: Long,
    error: Option[String]
)

final case class MaintenanceRunRequest(
    tenant: String,
    tenantDb: String,
    scope: Option[String] = None,
    operations: Option[String] = None
)

final case class MaintenanceRunResponse(id: Long)

// ----- Catalog data preview (Spec 00 time-travel viewer) -----
final case class PreviewColumn(name: String, dataType: String)

final case class PreviewResponse(
    columns: List[PreviewColumn],
    rows: List[List[Json]],
    snapshotId: Option[Long],
    truncated: Boolean
)

// ----- Catalog data diff (Spec 02) -----
final case class DataDiffSummary(inserted: Long, deleted: Long, updated: Long)

/** One rendered diff entry. `changeType` is `insert | delete | update`, or a bare `update_preimage`
  * / `update_postimage` passthrough when a pair could not be matched. `row` is set for
  * insert/delete/bare entries; `before`/`after` for paired updates.
  */
final case class DataDiffEntry(
    changeType: String,
    snapshotId: Long,
    row: Option[List[Json]] = None,
    before: Option[List[Json]] = None,
    after: Option[List[Json]] = None
)

/** Row-level data diff between two DuckLake snapshots of one table (Spec 02). `summary` is computed
  * over the FULL range by a separate aggregate, so its counts stay exact when `rows` paginates;
  * `nextCursor` is the opaque `<snapshotId>:<rowid>` keyset cursor of the last rendered entry,
  * absent on the final page.
  */
final case class DataDiffResponse(
    schema: String,
    table: String,
    from: Long,
    to: Long,
    summary: DataDiffSummary,
    columns: List[PreviewColumn],
    rows: List[DataDiffEntry],
    nextCursor: Option[String],
    truncated: Boolean
)

// ----- Undrop (Spec 03) -----
final case class RecoverableTableEntry(
    schema: String,
    table: String,
    droppedAtSnapshot: Long,
    lastLiveSnapshot: Long,
    droppedAt: Option[String],
    recoverable: Boolean
)

final case class RecoverableListResponse(tables: List[RecoverableTableEntry])

final case class UndropRequest(
    tenant: String,
    tenantDb: String,
    schema: String,
    table: String,
    asName: Option[String] = None,
    fromSnapshot: Option[Long] = None
)

final case class UndropResponse(
    schema: String,
    table: String,
    restoredAs: String,
    fromSnapshot: Long
)

/** One column whose declared type differs between the `from` and `to` snapshots of a two-snapshot
  * schema diff (Spec 00 Task 6). Only columns present at BOTH ends are considered here; an added or
  * removed column shows up in `SchemaDiffResponse.added` / `.removed` instead.
  */
final case class SchemaDiffColumnType(column: String, fromType: String, toType: String)

/** One column whose nullability differs between the `from` and `to` snapshots. Same "both ends
  * present" caveat as [[SchemaDiffColumnType]].
  */
final case class SchemaDiffNullability(column: String, fromNullable: Boolean, toNullable: Boolean)

/** Column-level schema diff between two DuckLake snapshots of one table (Spec 00 time-travel
  * viewer). `from` / `to` are the resolved snapshot ids (after the request's `from`/`to` selectors
  * -- a snapshot id or a tag name -- are each resolved via [[SnapshotSelector]]).
  */
final case class SchemaDiffResponse(
    from: Long,
    to: Long,
    added: List[CatalogColumnEntry],
    removed: List[CatalogColumnEntry],
    typeChanged: List[SchemaDiffColumnType],
    nullabilityChanged: List[SchemaDiffNullability]
)

object Dtos:
  given Codec[RoleDistribution] = deriveCodec
  // Hand-rolled codecs so optional fields with case-class defaults survive
  // a round-trip through the wire (circe 0.14 deriveCodec is strict).
  given Codec[NodeTolerationDto] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        key      <- c.get[String]("key")
        operator <- c.getOrElse[String]("operator")("Equal")
        value    <- c.getOrElse[Option[String]]("value")(None)
        effect   <- c.getOrElse[Option[String]]("effect")(None)
      yield NodeTolerationDto(key, operator, value, effect)
    },
    Encoder.instance { t =>
      Json.fromJsonObject(
        JsonObject(
          "key"      -> t.key.asJson,
          "operator" -> t.operator.asJson,
          "value"    -> t.value.asJson,
          "effect"   -> t.effect.asJson
        )
      )
    }
  )
  given Codec[NodePlacementDto] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        nodeSelector <- c.getOrElse[Map[String, String]]("nodeSelector")(Map.empty)
        tolerations  <- c.getOrElse[List[NodeTolerationDto]]("tolerations")(Nil)
      yield NodePlacementDto(nodeSelector, tolerations)
    },
    Encoder.instance { p =>
      Json.fromJsonObject(
        JsonObject(
          "nodeSelector" -> p.nodeSelector.asJson,
          "tolerations"  -> p.tolerations.asJson
        )
      )
    }
  )
  given Codec[PoolCohortDto] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        placement    <- c.getOrElse[NodePlacementDto]("placement")(NodePlacementDto())
        distribution <- c.get[RoleDistribution]("distribution")
      yield PoolCohortDto(placement, distribution)
    },
    Encoder.instance { p =>
      Json.fromJsonObject(
        JsonObject(
          "placement"    -> p.placement.asJson,
          "distribution" -> p.distribution.asJson
        )
      )
    }
  )
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
        cohorts              <- c.getOrElse[List[PoolCohortDto]]("cohorts")(Nil)
        disabled             <- c.getOrElse[Boolean]("disabled")(false)
        cpu                  <- c.getOrElse[String]("cpu")("")
        memory               <- c.getOrElse[String]("memory")("")
        podTemplateYaml      <- c.getOrElse[String]("podTemplateYaml")("")
      yield CreatePoolRequest(
        tenant,
        tenantDb,
        pool,
        size,
        roleDistribution,
        idleTimeoutSec,
        maxConcurrentPerNode,
        cohorts,
        disabled,
        None,
        cpu,
        memory,
        podTemplateYaml
      )
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "tenant"               -> r.tenant.asJson,
          "tenantDb"             -> r.tenantDb.asJson,
          "pool"                 -> r.pool.asJson,
          "size"                 -> r.size.asJson,
          "roleDistribution"     -> r.roleDistribution.asJson,
          "idleTimeoutSec"       -> r.idleTimeoutSec.asJson,
          "maxConcurrentPerNode" -> r.maxConcurrentPerNode.asJson,
          "cohorts"              -> r.cohorts.asJson,
          "disabled"             -> r.disabled.asJson,
          "cpu"                  -> r.cpu.asJson,
          "memory"               -> r.memory.asJson,
          "podTemplateYaml"      -> r.podTemplateYaml.asJson
        )
      )
    }
  )
  given Codec[ScalePoolRequest] = Codec.from(
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
      Json.fromJsonObject(
        JsonObject(
          "tenant"           -> r.tenant.asJson,
          "tenantDb"         -> r.tenantDb.asJson,
          "pool"             -> r.pool.asJson,
          "targetSize"       -> r.targetSize.asJson,
          "roleDistribution" -> r.roleDistribution.asJson,
          "force"            -> r.force.asJson
        )
      )
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
      Json.fromJsonObject(
        JsonObject(
          "tenant"   -> r.tenant.asJson,
          "tenantDb" -> r.tenantDb.asJson,
          "pool"     -> r.pool.asJson,
          "force"    -> r.force.asJson
        )
      )
    }
  )
  given Codec[DeletePoolRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenant   <- c.get[String]("tenant")
        tenantDb <- c.get[String]("tenantDb")
        pool     <- c.get[String]("pool")
        force    <- c.getOrElse[Boolean]("force")(false)
      yield DeletePoolRequest(tenant, tenantDb, pool, force)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "tenant"   -> r.tenant.asJson,
          "tenantDb" -> r.tenantDb.asJson,
          "pool"     -> r.pool.asJson,
          "force"    -> r.force.asJson
        )
      )
    }
  )
  // Custom codec for NodeInfo so all metric fields default to zero / true
  // when absent in JSON; lets clients and UI poll responses share one shape.
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
        quarantined   <- c.getOrElse[Boolean]("quarantined")(false)
        duckMem       <- c.getOrElse[Option[Long]]("duckdbMemoryBytes")(None)
        duckTemp      <- c.getOrElse[Option[Long]]("duckdbTempStorageBytes")(None)
        duckSpillN    <- c.getOrElse[Option[Long]]("duckdbSpillFiles")(None)
        duckSpillB    <- c.getOrElse[Option[Long]]("duckdbSpillBytes")(None)
      yield NodeInfo(
        nodeId,
        role,
        host,
        port,
        maxConcurrent,
        inFlight,
        totalServed,
        avgDurationMs,
        p50Ms,
        p95Ms,
        p99Ms,
        healthy,
        draining,
        quarantined,
        duckMem,
        duckTemp,
        duckSpillN,
        duckSpillB
      )
    },
    Encoder.instance { n =>
      Json.fromJsonObject(
        JsonObject(
          "nodeId"                 -> n.nodeId.asJson,
          "role"                   -> n.role.asJson,
          "host"                   -> n.host.asJson,
          "port"                   -> n.port.asJson,
          "maxConcurrent"          -> n.maxConcurrent.asJson,
          "inFlight"               -> n.inFlight.asJson,
          "totalServed"            -> n.totalServed.asJson,
          "avgDurationMs"          -> n.avgDurationMs.asJson,
          "p50Ms"                  -> n.p50Ms.asJson,
          "p95Ms"                  -> n.p95Ms.asJson,
          "p99Ms"                  -> n.p99Ms.asJson,
          "healthy"                -> n.healthy.asJson,
          "draining"               -> n.draining.asJson,
          "quarantined"            -> n.quarantined.asJson,
          "duckdbMemoryBytes"      -> n.duckdbMemoryBytes.asJson,
          "duckdbTempStorageBytes" -> n.duckdbTempStorageBytes.asJson,
          "duckdbSpillFiles"       -> n.duckdbSpillFiles.asJson,
          "duckdbSpillBytes"       -> n.duckdbSpillBytes.asJson
        )
      )
    }
  )
  given Codec[PoolResponse]            = deriveCodec
  given Codec[SetPoolResourcesRequest] = deriveCodec
  given Codec[SetPoolTemplateRequest]  = deriveCodec
  given Codec[PoolListResponse]        = deriveCodec
  given Codec[HealthResponse]          = deriveCodec
  given Codec[SetMaxConcurrentRequest] = deriveCodec
  given Codec[NodeOpRequest]           = deriveCodec
  given Codec[ErrorResponse]           = deriveCodec
  // TenantRequest: hand-rolled so the optional auth fields default
  // correctly when absent from the wire JSON.
  given Codec[TenantRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        id           <- c.get[String]("id")
        displayName  <- c.getOrElse[String]("displayName")("")
        authProvider <- c.getOrElse[String]("authProvider")("db")
        authConfig   <- c.getOrElse[Map[String, String]]("authConfig")(Map.empty)
      yield TenantRequest(id, displayName, authProvider, authConfig)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "id"           -> r.id.asJson,
          "displayName"  -> r.displayName.asJson,
          "authProvider" -> r.authProvider.asJson,
          "authConfig"   -> r.authConfig.asJson
        )
      )
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
      Json.fromJsonObject(
        JsonObject(
          "name"         -> r.name.asJson,
          "authProvider" -> r.authProvider.asJson,
          "authConfig"   -> r.authConfig.asJson
        )
      )
    }
  )
  given Codec[SetPoolDisabledRequest] = deriveCodec
  given Codec[ClientConfigResponse]   = deriveCodec
  given Codec[ConfigEntryView]        = deriveCodec
  given Codec[ConfigListResponse]     = deriveCodec
  given Codec[ManifestImportSummary]  = deriveCodec

  // Hand-rolled decoder so omitted optional fields fall back to the
  // case-class defaults (deriveCodec ignores Scala defaults and treats
  // every field as required). Without this, POST {"tenant":"x","name":"y","kind":"memory"}
  // is rejected with "Missing required field at 'metastore'/'dataPath'/'objectStore'".
  given Codec[TenantDbRequest] = io.circe.Codec.from(
    io.circe.Decoder.instance { c =>
      for
        tenant          <- c.get[String]("tenant")
        name            <- c.get[String]("name")
        kind            <- c.getOrElse[String]("kind")("ducklake")
        metastore       <- c.getOrElse[Map[String, String]]("metastore")(Map.empty)
        dataPath        <- c.getOrElse[String]("dataPath")("")
        objectStore     <- c.getOrElse[Map[String, String]]("objectStore")(Map.empty)
        defaultDatabase <- c.getOrElse[Option[String]]("defaultDatabase")(None)
        defaultSchema   <- c.getOrElse[Option[String]]("defaultSchema")(None)
        initSql         <- c.getOrElse[String]("initSql")("")
      yield TenantDbRequest(
        tenant,
        name,
        kind,
        metastore,
        dataPath,
        objectStore,
        defaultDatabase,
        defaultSchema,
        initSql
      )
    },
    io.circe.generic.semiauto.deriveEncoder[TenantDbRequest]
  )
  given Codec[TenantDbResponse]       = deriveCodec
  given Codec[TenantDbListResponse]   = deriveCodec
  given Codec[TenantDbOpRequest]      = deriveCodec
  given Codec[UpdateTenantDbRequest]  = deriveCodec
  given Codec[FailedRestart]          = deriveCodec
  given Codec[UpdateTenantDbResponse] = deriveCodec

  given Codec[LoginRequest] = deriveCodec
  // Hand-rolled so new optional fields fall back to defaults when absent on the wire,
  // and so old clients (no superuser/manageableTenants) keep parsing.
  given Codec[LoginResponse] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        token             <- c.get[String]("token")
        username          <- c.get[String]("username")
        tenant            <- c.getOrElse[Option[String]]("tenant")(None)
        superuser         <- c.getOrElse[Boolean]("superuser")(false)
        manageableTenants <- c.getOrElse[List[String]]("manageableTenants")(Nil)
      yield LoginResponse(token, username, tenant, superuser, manageableTenants)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "token"             -> r.token.asJson,
          "username"          -> r.username.asJson,
          "tenant"            -> r.tenant.asJson,
          "superuser"         -> r.superuser.asJson,
          "manageableTenants" -> r.manageableTenants.asJson
        )
      )
    }
  )
  given Codec[WhoamiResponse] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        username          <- c.get[String]("username")
        role              <- c.get[String]("role")
        tenant            <- c.getOrElse[Option[String]]("tenant")(None)
        superuser         <- c.getOrElse[Boolean]("superuser")(false)
        manageableTenants <- c.getOrElse[List[String]]("manageableTenants")(Nil)
      yield WhoamiResponse(username, role, tenant, superuser, manageableTenants)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "username"          -> r.username.asJson,
          "role"              -> r.role.asJson,
          "tenant"            -> r.tenant.asJson,
          "superuser"         -> r.superuser.asJson,
          "manageableTenants" -> r.manageableTenants.asJson
        )
      )
    }
  )
  given Codec[AuthModeResponse] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        mode            <- c.get[String]("mode")
        ssoProviderName <- c.getOrElse[String]("ssoProviderName")("")
      yield AuthModeResponse(mode, ssoProviderName)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "mode"            -> r.mode.asJson,
          "ssoProviderName" -> r.ssoProviderName.asJson
        )
      )
    }
  )

  given Codec[StatementHistoryEntry]    = deriveCodec
  given Codec[StatementHistoryResponse] = deriveCodec

  // History: trends and statement search
  given Codec[TrendBucketEntry]         = deriveCodec
  given Codec[TrendsResponse]           = deriveCodec
  given Codec[StatementHistoryRowEntry] = deriveCodec
  given Codec[StatementSearchResponse]  = deriveCodec

  // Usage and accounting
  given Codec[UsageDayEntry]   = deriveCodec
  given Codec[UsageGroupEntry] = deriveCodec
  given Codec[UsageResponse]   = deriveCodec

  // Audit log
  given Codec[AuditEventEntry]      = deriveCodec
  given Codec[AuditListResponse]    = deriveCodec
  given Codec[AuditActionsResponse] = deriveCodec

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
      Json.fromJsonObject(
        JsonObject(
          "tenant"   -> r.tenant.asJson,
          "username" -> r.username.asJson,
          "password" -> r.password.asJson,
          "role"     -> r.role.asJson
        )
      )
    }
  )
  given Codec[UserUpdateRequest] = deriveCodec
  given Codec[UserDeleteRequest] = deriveCodec
  given Codec[UserResponse]      = deriveCodec
  given Codec[UserListResponse]  = deriveCodec

  // RBAC: roles
  given Codec[RoleCreateRequest]          = deriveCodec
  given Codec[RoleDeleteRequest]          = deriveCodec
  given Codec[RoleResponse]               = deriveCodec
  given Codec[RoleListResponse]           = deriveCodec
  given Codec[RolePermissionGrantRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        roleId  <- c.get[String]("roleId")
        catalog <- c.getOrElse[String]("catalog")("*")
        schema  <- c.getOrElse[String]("schema")("*")
        table   <- c.getOrElse[String]("table")("*")
        verb    <- c.get[String]("verb")
      yield RolePermissionGrantRequest(roleId, catalog, schema, table, verb)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "roleId"  -> r.roleId.asJson,
          "catalog" -> r.catalog.asJson,
          "schema"  -> r.schema.asJson,
          "table"   -> r.table.asJson,
          "verb"    -> r.verb.asJson
        )
      )
    }
  )
  given Codec[RolePermissionRevokeRequest] = deriveCodec
  given Codec[RolePermissionResponse]      = deriveCodec
  given Codec[RolePermissionListResponse]  = deriveCodec

  // RBAC: groups
  given Codec[GroupCreateRequest] = deriveCodec
  given Codec[GroupDeleteRequest] = deriveCodec
  given Codec[GroupResponse]      = deriveCodec
  given Codec[GroupListResponse]  = deriveCodec

  given Codec[UserRoleMembershipRequest]  = deriveCodec
  given Codec[UserGroupMembershipRequest] = deriveCodec
  given Codec[GroupRoleMembershipRequest] = deriveCodec

  // RBAC: column policies
  given Codec[ColumnPolicyDto]           = deriveCodec
  given Codec[CreateColumnPolicyRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        roleId       <- c.get[String]("roleId")
        catalogName  <- c.getOrElse[String]("catalogName")("*")
        schemaName   <- c.getOrElse[String]("schemaName")("*")
        tableName    <- c.getOrElse[String]("tableName")("*")
        columnName   <- c.get[String]("columnName")
        action       <- c.get[String]("action")
        transformSql <- c.getOrElse[Option[String]]("transformSql")(None)
      yield CreateColumnPolicyRequest(
        roleId,
        catalogName,
        schemaName,
        tableName,
        columnName,
        action,
        transformSql
      )
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "roleId"       -> r.roleId.asJson,
          "catalogName"  -> r.catalogName.asJson,
          "schemaName"   -> r.schemaName.asJson,
          "tableName"    -> r.tableName.asJson,
          "columnName"   -> r.columnName.asJson,
          "action"       -> r.action.asJson,
          "transformSql" -> r.transformSql.asJson
        )
      )
    }
  )
  given Codec[UpdateColumnPolicyRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        id           <- c.get[String]("id")
        action       <- c.get[String]("action")
        transformSql <- c.getOrElse[Option[String]]("transformSql")(None)
      yield UpdateColumnPolicyRequest(id, action, transformSql)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "id"           -> r.id.asJson,
          "action"       -> r.action.asJson,
          "transformSql" -> r.transformSql.asJson
        )
      )
    }
  )
  given Codec[DeleteColumnPolicyRequest] = deriveCodec
  given Codec[ColumnPolicyListResponse]  = deriveCodec

  // RBAC: row policies
  given Codec[RowPolicyDto]           = deriveCodec
  given Codec[CreateRowPolicyRequest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        roleId       <- c.get[String]("roleId")
        catalogName  <- c.getOrElse[String]("catalogName")("*")
        schemaName   <- c.getOrElse[String]("schemaName")("*")
        tableName    <- c.getOrElse[String]("tableName")("*")
        predicateSql <- c.get[String]("predicateSql")
      yield CreateRowPolicyRequest(roleId, catalogName, schemaName, tableName, predicateSql)
    },
    Encoder.instance { r =>
      Json.fromJsonObject(
        JsonObject(
          "roleId"       -> r.roleId.asJson,
          "catalogName"  -> r.catalogName.asJson,
          "schemaName"   -> r.schemaName.asJson,
          "tableName"    -> r.tableName.asJson,
          "predicateSql" -> r.predicateSql.asJson
        )
      )
    }
  )
  given Codec[UpdateRowPolicyRequest] = deriveCodec
  given Codec[DeleteRowPolicyRequest] = deriveCodec
  given Codec[RowPolicyListResponse]  = deriveCodec

  // RBAC: pool permissions
  given Codec[PoolPermissionGrantRequest]  = deriveCodec
  given Codec[PoolPermissionRevokeRequest] = deriveCodec
  given Codec[PoolPermissionResponse]      = deriveCodec
  given Codec[PoolPermissionListResponse]  = deriveCodec

  given Codec[EffectivePermissionsResponse] = deriveCodec

  // Catalog browser
  given Codec[CatalogSchemaEntry]         = deriveCodec
  given Codec[CatalogTableEntry]          = deriveCodec
  given Codec[CatalogColumnEntry]         = deriveCodec
  given Codec[CatalogDataFileEntry]       = deriveCodec
  given Codec[CatalogTableDetailResponse] = deriveCodec
  given Codec[CatalogTableRef]            = deriveCodec
  given Codec[CatalogSnapshotEntry]       = deriveCodec
  given Codec[CatalogHistoryTableRef]     = deriveCodec
  given Codec[CatalogHistoryCommit]       = deriveCodec
  given Codec[CatalogHistoryResponse]     = deriveCodec

  // Federation
  // Hand-rolled decoder so omitted optional fields fall back to the
  // case-class defaults (deriveCodec ignores Scala defaults and treats
  // `disabled` as required). Without this, POST {"alias":"x","setupSql":"..."}
  // gets rejected with "Missing required field at 'disabled'".
  given Codec[FederatedSourceCreateRequest] = io.circe.Codec.from(
    io.circe.Decoder.instance { c =>
      for
        alias       <- c.get[String]("alias")
        setupSql    <- c.get[String]("setupSql")
        description <- c.getOrElse[Option[String]]("description")(None)
        disabled    <- c.getOrElse[Boolean]("disabled")(false)
      yield FederatedSourceCreateRequest(alias, setupSql, description, disabled)
    },
    io.circe.generic.semiauto.deriveEncoder[FederatedSourceCreateRequest]
  )
  given Codec[FederatedSourceResponse]      = deriveCodec
  given Codec[FederatedSourceListResponse]  = deriveCodec
  given Codec[FederatedSecretUpsertRequest] = deriveCodec
  given Codec[FederatedSecretResponse]      = deriveCodec
  given Codec[FederatedSecretListResponse]  = deriveCodec

  // Active statement management
  given Codec[ActiveStatementInfo]      = deriveCodec
  given Codec[ActiveStatementsResponse] = deriveCodec
  given Codec[KillStatementRequest]     = deriveCodec
  given Codec[KillStatementResponse]    = deriveCodec

  // Snapshot tags: hand-rolled codecs because the wire field is "protected" (a Scala keyword).
  given Codec[CatalogTagEntry] = Codec.from(
    Decoder.instance { c =>
      for
        name        <- c.get[String]("name")
        snapshotId  <- c.get[Long]("snapshotId")
        isProtected <- c.getOrElse[Boolean]("protected")(false)
        createdBy   <- c.get[Option[String]]("createdBy")
        createdAt   <- c.get[Option[java.time.Instant]]("createdAt")
        exists      <- c.getOrElse[Boolean]("exists")(true)
      yield CatalogTagEntry(name, snapshotId, isProtected, createdBy, createdAt, exists)
    },
    Encoder.instance { t =>
      Json.obj(
        "name"       -> Json.fromString(t.name),
        "snapshotId" -> Json.fromLong(t.snapshotId),
        "protected"  -> Json.fromBoolean(t.isProtected),
        "createdBy"  -> t.createdBy.fold(Json.Null)(Json.fromString),
        "createdAt"  -> t.createdAt.fold(Json.Null)(i => Json.fromString(i.toString)),
        "exists"     -> Json.fromBoolean(t.exists)
      )
    }
  )
  given Codec[TagCreateRequest] = Codec.from(
    Decoder.instance { c =>
      for
        tenant      <- c.get[String]("tenant")
        tenantDb    <- c.get[String]("tenantDb")
        name        <- c.get[String]("name")
        snapshotId  <- c.get[Long]("snapshotId")
        isProtected <- c.getOrElse[Boolean]("protected")(false)
      yield TagCreateRequest(tenant, tenantDb, name, snapshotId, isProtected)
    },
    Encoder.instance { r =>
      Json.obj(
        "tenant"     -> Json.fromString(r.tenant),
        "tenantDb"   -> Json.fromString(r.tenantDb),
        "name"       -> Json.fromString(r.name),
        "snapshotId" -> Json.fromLong(r.snapshotId),
        "protected"  -> Json.fromBoolean(r.isProtected)
      )
    }
  )
  given Codec[TagDeleteRequest]  = deriveCodec
  given Codec[TagProtectRequest] = Codec.from(
    Decoder.instance { c =>
      for
        tenant      <- c.get[String]("tenant")
        tenantDb    <- c.get[String]("tenantDb")
        name        <- c.get[String]("name")
        isProtected <- c.get[Boolean]("protected")
      yield TagProtectRequest(tenant, tenantDb, name, isProtected)
    },
    Encoder.instance { r =>
      Json.obj(
        "tenant"    -> Json.fromString(r.tenant),
        "tenantDb"  -> Json.fromString(r.tenantDb),
        "name"      -> Json.fromString(r.name),
        "protected" -> Json.fromBoolean(r.isProtected)
      )
    }
  )

  // Managed maintenance (EPIC Spec 09). Hand-rolled decoder for the upsert request so every
  // optional field defaults to None when absent from the wire JSON (deriveCodec ignores Scala 3
  // defaults and treats every field as required).
  given Codec[MaintenancePolicyUpsertRequest] = Codec.from(
    Decoder.instance { c =>
      for
        tenant                 <- c.get[String]("tenant")
        tenantDb               <- c.get[String]("tenantDb")
        scopeKind              <- c.get[String]("scopeKind")
        scopeSchema            <- c.getOrElse[Option[String]]("scopeSchema")(None)
        scopeTable             <- c.getOrElse[Option[String]]("scopeTable")(None)
        enabled                <- c.getOrElse[Option[Boolean]]("enabled")(None)
        retentionDays          <- c.getOrElse[Option[Int]]("retentionDays")(None)
        compactionEnabled      <- c.getOrElse[Option[Boolean]]("compactionEnabled")(None)
        targetFileSize         <- c.getOrElse[Option[String]]("targetFileSize")(None)
        smallFileMinCount      <- c.getOrElse[Option[Int]]("smallFileMinCount")(None)
        rewriteDeleteThreshold <- c.getOrElse[Option[Double]]("rewriteDeleteThreshold")(None)
        cleanupGraceDays       <- c.getOrElse[Option[Int]]("cleanupGraceDays")(None)
        orphanMinAgeDays       <- c.getOrElse[Option[Int]]("orphanMinAgeDays")(None)
        cron                   <- c.getOrElse[Option[String]]("cron")(None)
      yield MaintenancePolicyUpsertRequest(
        tenant,
        tenantDb,
        scopeKind,
        scopeSchema,
        scopeTable,
        enabled,
        retentionDays,
        compactionEnabled,
        targetFileSize,
        smallFileMinCount,
        rewriteDeleteThreshold,
        cleanupGraceDays,
        orphanMinAgeDays,
        cron
      )
    },
    Encoder.instance { r =>
      Json.obj(
        "tenant"                 -> r.tenant.asJson,
        "tenantDb"               -> r.tenantDb.asJson,
        "scopeKind"              -> r.scopeKind.asJson,
        "scopeSchema"            -> r.scopeSchema.asJson,
        "scopeTable"             -> r.scopeTable.asJson,
        "enabled"                -> r.enabled.asJson,
        "retentionDays"          -> r.retentionDays.asJson,
        "compactionEnabled"      -> r.compactionEnabled.asJson,
        "targetFileSize"         -> r.targetFileSize.asJson,
        "smallFileMinCount"      -> r.smallFileMinCount.asJson,
        "rewriteDeleteThreshold" -> r.rewriteDeleteThreshold.asJson,
        "cleanupGraceDays"       -> r.cleanupGraceDays.asJson,
        "orphanMinAgeDays"       -> r.orphanMinAgeDays.asJson,
        "cron"                   -> r.cron.asJson
      )
    }
  )
  given Codec[MaintenancePolicyDeleteRequest] = deriveCodec
  given Codec[MaintenancePolicyEntry]         = deriveCodec
  given Codec[MaintenanceEffectiveEntry]      = deriveCodec
  given Codec[MaintenancePolicyListResponse]  = deriveCodec
  given Codec[MaintenanceRunEntry]            = deriveCodec
  // Hand-rolled so scope/operations default to None when absent from the wire JSON.
  given Codec[MaintenanceRunRequest] = Codec.from(
    Decoder.instance { c =>
      for
        tenant     <- c.get[String]("tenant")
        tenantDb   <- c.get[String]("tenantDb")
        scope      <- c.getOrElse[Option[String]]("scope")(None)
        operations <- c.getOrElse[Option[String]]("operations")(None)
      yield MaintenanceRunRequest(tenant, tenantDb, scope, operations)
    },
    Encoder.instance { r =>
      Json.obj(
        "tenant"     -> r.tenant.asJson,
        "tenantDb"   -> r.tenantDb.asJson,
        "scope"      -> r.scope.asJson,
        "operations" -> r.operations.asJson
      )
    }
  )
  given Codec[MaintenanceRunResponse] = deriveCodec

  // Catalog data preview
  given Codec[PreviewColumn]           = deriveCodec
  given Codec[PreviewResponse]         = deriveCodec
  given Codec[DataDiffSummary]         = deriveCodec
  given Codec[DataDiffEntry]           = deriveCodec
  given Codec[DataDiffResponse]        = deriveCodec
  given Codec[RecoverableTableEntry]   = deriveCodec
  given Codec[RecoverableListResponse] = deriveCodec
  given Codec[UndropRequest]           = deriveCodec
  given Codec[UndropResponse]          = deriveCodec

  // Schema diff (Task 6)
  given Codec[SchemaDiffColumnType]  = deriveCodec
  given Codec[SchemaDiffNullability] = deriveCodec
  given Codec[SchemaDiffResponse]    = deriveCodec
