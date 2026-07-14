export type Role = 'READONLY' | 'WRITEONLY' | 'DUAL';

export interface RoleDistribution {
  writeonly: number;
  readonly: number;
  dual: number;
}

export interface NodeToleration {
  key: string;
  operator?: string;     // "Equal" (default) | "Exists"
  value?: string;
  effect?: string;       // "NoSchedule" | "PreferNoSchedule" | "NoExecute"
}

export interface NodePlacement {
  nodeSelector?: Record<string, string>;
  tolerations?: NodeToleration[];
}

export interface PoolCohort {
  placement?: NodePlacement;
  distribution: RoleDistribution;
}

export interface NodeInfo {
  nodeId: string;
  role: string;
  host: string;
  port: number;
  maxConcurrent: number; // 0 = unlimited
  // Live metrics. inFlight = currently executing statements; totalServed =
  // lifetime counter since manager start; avgDurationMs = EWMA latency;
  // p50/p95/p99 = sorted percentiles over a rolling 256-sample window.
  inFlight: number;
  totalServed: number;
  avgDurationMs: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  healthy: boolean;
  draining: boolean;
  quarantined: boolean;
  // DuckDB engine internals scraped by the manager's health probe.
  // Absent until the first successful scrape of the node.
  duckdbMemoryBytes?: number | null;
  duckdbTempStorageBytes?: number | null;
  duckdbSpillFiles?: number | null;
  duckdbSpillBytes?: number | null;
}

export interface PoolResponse {
  id: string;
  tenant: string;
  tenantDb: string;
  pool: string;
  nodes: NodeInfo[];
  status: string;
  // Effective metastore for this pool (inherited from the tenant-db).
  // pgPassword is redacted.
  metastore: Record<string, string>;
  disabled: boolean;
  // Persisted placement plan. Empty array (the default) means no
  // placement constraint - all nodes scheduled wherever the runtime puts
  // them. Only meaningful on the Kubernetes backend.
  cohorts?: PoolCohort[];
  // Kubernetes pod resource limits (CPU and memory). Empty string means
  // no explicit limit is set for that dimension.
  cpu: string;
  memory: string;
}

export interface SetPoolDisabledRequest {
  tenant: string;
  tenantDb: string;
  pool: string;
  disabled: boolean;
}

export interface CreatePoolRequest {
  tenant: string;
  tenantDb: string;
  pool: string;
  size: number;
  roleDistribution: RoleDistribution;
  maxConcurrentPerNode?: number; // default 0 = unlimited
  // Optional placement plan. Must sum back to roleDistribution / size when
  // present. The manager ignores cohorts when the runtime backend is not
  // Kubernetes (see ClientConfigResponse.placementSupported).
  cohorts?: PoolCohort[];
  // When true the pool is persisted disabled; the FlightSQL edge rejects
  // fresh handshakes until it's enabled. Nodes still spawn.
  disabled?: boolean;
  // Kubernetes pod resource limits. Empty string or omitted = no limit.
  cpu?: string;
  memory?: string;
  podTemplateYaml?: string;
}

export interface ScalePoolRequest {
  tenant: string;
  tenantDb: string;
  pool: string;
  targetSize: number;
  roleDistribution: RoleDistribution;
  force?: boolean;
}

export interface StopPoolRequest {
  tenant: string;
  tenantDb: string;
  pool: string;
  force?: boolean;
}

export interface DeletePoolRequest {
  tenant: string;
  tenantDb: string;
  pool: string;
  force?: boolean;
}

export interface SetPoolResourcesRequest {
  tenant: string;
  tenantDb: string;
  pool: string;
  cpu: string;
  memory: string;
}

export interface SetMaxConcurrentRequest {
  tenant: string;
  tenantDb: string;
  pool: string;
  nodeId: string;
  max: number;
}

export interface NodeOpRequest {
  tenant: string;
  tenantDb: string;
  pool: string;
  nodeId: string;
}

export interface HealthResponse {
  status: string;
  poolsCount: number;
  nodesCount: number;
}

export interface ClientConfigResponse {
  flightSqlHost: string;     // "0.0.0.0" / "" -> UI substitutes window.location.hostname
  flightSqlPort: number;
  flightSqlTls: boolean;
  // When false, no auth providers are configured server-side and the UI
  // skips the login screen entirely. The REST API may still require an
  // X-API-Key - that's a separate gate.
  authEnabled: boolean;
  // True iff the runtime backend supports node placement (Kubernetes).
  // The UI hides cohort/placement controls when false.
  placementSupported?: boolean;
  /** True when a telemetry store is configured (telemetry.store != none). The UI hides
   *  the Audit page when false. */
  telemetryEnabled?: boolean;
  // "db" (default) = password form; "oidc" = redirect to the IdP via /api/auth/oidc/start.
  identitySource?: 'db' | 'oidc';
  // Human-readable IdP label shown in the SSO redirect card (e.g. "Keycloak", "Google").
  ssoProviderName?: string;
}

/** One row of the Config page. `value` is masked ("(set)" / "(unset)")
 * when `sensitive` is true. */
export interface ConfigEntryView {
  path: string;
  envVar: string;
  description: string;
  value: string;
  sensitive: boolean;
  isSet: boolean;
}

export interface ConfigListResponse {
  entries: ConfigEntryView[];
}

export interface AuditEventEntry {
  id: string;
  ts: string;
  family: string;
  actor: string;
  actorRealm: string;
  tenant: string | null;
  action: string;
  target: string | null;
  outcome: string;
  origin: string;
  detail: Record<string, string>;
}

export interface AuditListResponse {
  events: AuditEventEntry[];
  nextBefore: string | null;
}

export interface AuditActionsResponse {
  actions: string[];
}

export interface ManifestImportSummary {
  tenants:   number;
  tenantDbs: number;
  pools:     number;
  roles:     number;
  groups:    number;
  users:     number;
}

export type AuthProvider = 'db' | 'keycloak' | 'google' | 'azure' | 'aws';

export interface TenantRequest {
  // Slug key (required), e.g. "acme". The one tenant key in URLs/sessions/FKs.
  id: string;
  // Free-form human label, e.g. "Acme Corporation".
  displayName: string;
  authProvider?: AuthProvider;
  authConfig?: Record<string, string>;
}

export interface TenantResponse {
  id: string;
  // Equal to id (kept for callers that key on `name`); both hold the slug.
  name: string;
  // Free-form human label; may differ from id.
  displayName: string;
  pools: string[];
  disabled: boolean;
  authProvider: AuthProvider;
  authConfig: Record<string, string>;
}

export interface SetTenantDisabledRequest {
  name: string;
  disabled: boolean;
}

export interface SetTenantAuthRequest {
  name: string;
  authProvider: AuthProvider;
  authConfig: Record<string, string>;
}

export interface TenantListResponse {
  tenants: TenantResponse[];
}

export interface TenantOpRequest {
  name: string;
}

// ----- Tenant databases -----
export type TenantDbKind = 'ducklake' | 'duckdb-file' | 'memory';

export interface TenantDbRequest {
  tenant: string;
  name: string; // suffix; server composes `${tenant}_${name}`
  kind?: TenantDbKind;          // defaults server-side to "ducklake"
  metastore?: Record<string, string>;
  dataPath?: string;
  objectStore?: Record<string, string>;
  defaultDatabase?: string;
  defaultSchema?: string;
  initSql?: string;
}

export interface TenantDbResponse {
  id: string;
  tenant: string;
  name: string;
  kind: TenantDbKind;           // always present in the response
  metastore: Record<string, string>;
  dataPath: string;
  objectStore: Record<string, string>;
  defaultDatabase?: string;
  defaultSchema?: string;
  disabled: boolean;
  /** Number of registered federated sources on this tenant-db.
    * 0 in file-storage mode (no federation tables). */
  federatedSourceCount?: number;
  initSql: string;
  /** Data path resolved through the default-metastore chain.
    * Present even when the tenant-db has no explicit dataPath. */
  effectiveDataPath: string;
  /** Total table count in the DuckLake catalog; null when unavailable
    * (memory kind, duckdb-file without a live pool, etc.). */
  tableCount: number | null;
}

export interface TenantDbListResponse {
  tenantDbs: TenantDbResponse[];
}

export interface TenantDbOpRequest {
  tenant: string;
  name: string;
}

export interface UpdateTenantDbRequest {
  tenant: string;
  name: string;
  metastore?: Record<string, string>;
  objectStore?: Record<string, string>;
  defaultDatabase?: string;
  defaultSchema?: string;
  initSql?: string;
}
export interface FailedRestart { nodeId: string; message: string; }
export interface UpdateTenantDbResponse {
  db: TenantDbResponse;
  restartedNodes: string[];
  failedRestarts: FailedRestart[];
}

// ----- RBAC: users -----
export interface UserResponse {
  id: string;
  tenant: string | null;            // null = superuser (manager UI + every FlightSQL tenant)
  username: string;
  role: string;                     // free-text JWT-claim label, NOT an RBAC role id
  enabled: boolean;
  roles:  string[];                 // effective role NAMES
  groups: string[];                 // effective group NAMES
  poolGrants: string[];             // human "tenant/pool" or "tenant/*" labels
}

export interface UserCreateRequest {
  tenant: string | null;            // null = superuser
  username: string;
  password: string;
  role?: string;
}

export interface UserUpdateRequest {
  id: string;
  tenant?: string | null;
  password?: string | null;
  role?: string | null;
}

export interface UserDeleteRequest { id: string; }
export interface UserListResponse  { users: UserResponse[]; }

// ----- RBAC: roles -----
export interface RoleResponse {
  id:          string;
  tenantId:    string;
  name:        string;
  description: string | null;
  createdAt:   string;
}

export interface RoleCreateRequest {
  tenant:      string;
  name:        string;
  description?: string | null;
}
export interface RoleDeleteRequest { id: string; }
export interface RoleListResponse { roles: RoleResponse[]; }

// ----- RBAC: role permissions -----
export interface RolePermissionResponse {
  id:          string;
  roleId:      string;
  catalogName: string;              // '*' = wildcard
  schemaName:  string;
  tableName:   string;
  verb:        string;              // SELECT | INSERT | UPDATE | DELETE | ALL
  grantedAt:   string;
}

export interface RolePermissionGrantRequest {
  roleId:  string;
  catalog?: string;
  schema?:  string;
  table?:   string;
  verb:    string;
}
export interface RolePermissionRevokeRequest { id: string; }
export interface RolePermissionListResponse { permissions: RolePermissionResponse[]; }

// ----- RBAC: column-level policies -----
export interface ColumnPolicyDto {
  id:           string;
  roleId:       string;
  catalogName:  string;
  schemaName:   string;
  tableName:    string;
  columnName:   string;
  action:       string;                    // 'deny' | 'mask'
  transformSql: string | null;
}

export interface CreateColumnPolicyRequest {
  roleId:       string;
  catalogName:  string;
  schemaName:   string;
  tableName:    string;
  columnName:   string;
  action:       string;                    // 'deny' | 'mask'
  transformSql?: string | null;
}

export interface UpdateColumnPolicyRequest {
  id:           string;
  action:       string;                    // 'deny' | 'mask'
  transformSql?: string | null;
}

export interface DeleteColumnPolicyRequest { id: string; }
export interface ColumnPolicyListResponse { policies: ColumnPolicyDto[]; }

// ----- RBAC: row-level policies -----
export interface RowPolicyDto {
  id:           string;
  roleId:       string;
  catalogName:  string;
  schemaName:   string;
  tableName:    string;
  predicateSql: string;
}

export interface CreateRowPolicyRequest {
  roleId:       string;
  catalogName:  string;
  schemaName:   string;
  tableName:    string;
  predicateSql: string;
}

export interface UpdateRowPolicyRequest {
  id:           string;
  predicateSql: string;
}

export interface DeleteRowPolicyRequest { id: string; }
export interface RowPolicyListResponse { policies: RowPolicyDto[]; }

// ----- RBAC: groups -----
export interface GroupResponse {
  id:          string;
  tenantId:    string;
  name:        string;
  description: string | null;
}

export interface GroupCreateRequest {
  tenant:      string;
  name:        string;
  description?: string | null;
}
export interface GroupDeleteRequest { id: string; }
export interface GroupListResponse { groups: GroupResponse[]; }

// ----- RBAC: memberships -----
export interface UserRoleMembershipRequest  { userId:  string; roleId:  string; }
export interface UserGroupMembershipRequest { userId:  string; groupId: string; }
export interface GroupRoleMembershipRequest { groupId: string; roleId:  string; }

// ----- RBAC: pool permissions -----
export interface PoolPermissionResponse {
  id:        string;
  tenantId:  string;
  poolId:    string | null;         // null = every pool in tenant
  userId:    string | null;
  groupId:   string | null;
  grantedAt: string;
}

export interface PoolPermissionGrantRequest {
  tenant:  string;
  poolId?: string | null;
  userId?: string | null;
  groupId?: string | null;
}
export interface PoolPermissionRevokeRequest { id: string; }
export interface PoolPermissionListResponse { permissions: PoolPermissionResponse[]; }

// ----- RBAC: effective permissions -----
export interface EffectivePermissionsResponse {
  user:       UserResponse;
  roles:      RoleResponse[];
  groups:     GroupResponse[];
  pools:      PoolPermissionResponse[];
  tablePerms: RolePermissionResponse[];
}

// ----- Auth -----
// Per-tenant admin-UI login mode resolved by GET /api/auth/mode?tenant=.
// "db" -> render the password form; "oidc" -> redirect to /api/auth/oidc/start.
export interface AuthModeResponse {
  mode: 'db' | 'oidc';
  ssoProviderName?: string;
}

export interface LoginRequest  { username: string; password: string; tenant?: string }
export interface LoginResponse {
  token: string;
  username: string;
  // `role` deliberately omitted: every minted session is admin by construction
  // (the server gates anything else with 403 admin_required), so the field was
  // a tautology. The descriptive role shown in the UI is sourced from /whoami.
  tenant?: string | null;
  superuser?: boolean;
  manageableTenants?: string[];
}
export interface WhoamiResponse {
  username: string;
  role: string;
  tenant?: string | null;
  superuser?: boolean;
  manageableTenants?: string[];
}

// ----- Recent statement history -----
export interface StatementHistoryEntry {
  ts: string;                  // ISO-8601 UTC
  user: string;
  tenant: string;
  pool: string;
  nodeId: string;
  sql: string;
  durationMs: number;
  status: string;              // ok | denied | transient | permanent | no-node | no-pool | pin-lost
  error: string | null;
  // Wall-clock ms the FlightSQL Prepare-time LIMIT-0 probe spent on the node, when this Execute
  // belongs to a prepared-statement round. Rendered as subtext under the Execute duration.
  prepareDurationMs?: number | null;
}
export interface StatementHistoryResponse {
  statements: StatementHistoryEntry[];
}

// ----- Catalog browser -----
export interface CatalogSchemaEntry {
  name: string;
  tableCount: number;
}

export interface CatalogTableEntry {
  schema: string;
  name: string;
  rowCount: number;        // -1 when DuckLake stats are unavailable
  dataFileCount: number;
  folder: string | null;   // table data-folder (parent dir of its parquet files);
                           // null when the table has no committed data files yet
}

export interface CatalogColumnEntry {
  ordinal: number;
  name: string;
  typeName: string;
  nullable: boolean;
  isPrimaryKey: boolean;
}

export interface CatalogDataFileEntry {
  path: string;            // absolute file path or s3:// URL
  sizeBytes: number;
  rowCount: number;
  snapshotId: number;
}

export interface CatalogTableDetailResponse {
  table: CatalogTableEntry;
  columns: CatalogColumnEntry[];
  dataFiles: CatalogDataFileEntry[];
  resolvedSnapshot?: number | null;
  resolvedAt?: string | null;
}

export interface CatalogTableRef {
  schema: string;
  name: string;
}

export interface CatalogSnapshotEntry {
  snapshotId: number;
  committedAt: string;     // ISO-8601
  schemaVersion: number;
  changes: string;         // raw DuckLake changes_made string
  rowsAdded: number;
  filesAdded: number;
  filesRemoved: number;
  affectedTables: CatalogTableRef[];
  author: string | null;        // ducklake_snapshot_changes.author, P1 stamping
  commitMessage: string | null; // ducklake_snapshot_changes.commit_message
}

// ----- Per-table history / audit timeline (EPIC Spec 01) -----

export interface CatalogHistoryTableRef {
  schema: string;
  name: string;
  tableId: number;
}

export interface CatalogHistoryCommit {
  snapshotId: number;
  committedAt: string; // ISO-8601
  operation: string;   // create|insert|delete|update|alter|drop|maintenance|unknown
  author: string | null;        // null on pre-stamping snapshots
  commitMessage: string | null;
  schemaChanged: boolean;
  schemaVersion: number;
  rowsAdded: number;
  rowsRemoved: number;
  filesAdded: number;
  filesRemoved: number;
}

export interface CatalogHistoryResponse {
  table: CatalogHistoryTableRef;
  commits: CatalogHistoryCommit[]; // snapshotId DESC
  hasMore: boolean;
}

export interface CatalogTagEntry {
  name: string;
  snapshotId: number;
  protected: boolean;
  createdBy: string | null;
  createdAt: string | null; // ISO-8601
  exists: boolean;          // false = dangling (snapshot expired/vacuumed)
}

// ----- Catalog data preview + schema diff (Spec 00 time-travel viewer) -----

export interface PreviewColumn {
  name: string;
  dataType: string;
}

export interface PreviewResponse {
  columns: PreviewColumn[];
  rows: unknown[][];
  snapshotId: number | null;
  truncated: boolean;
}

export interface SchemaDiffColumnType {
  column: string;
  fromType: string;
  toType: string;
}

export interface SchemaDiffNullability {
  column: string;
  fromNullable: boolean;
  toNullable: boolean;
}

export interface SchemaDiffResponse {
  from: number;
  to: number;
  added: CatalogColumnEntry[];
  removed: CatalogColumnEntry[];
  typeChanged: SchemaDiffColumnType[];
  nullabilityChanged: SchemaDiffNullability[];
}

// ----- Catalog data diff (Spec 02) -----

export interface DataDiffSummary {
  inserted: number;
  deleted: number;
  updated: number;
}

export interface DataDiffEntry {
  changeType: string;            // insert | delete | update | raw update_* passthrough
  snapshotId: number;
  row?: unknown[] | null;        // insert/delete/bare entries
  before?: unknown[] | null;     // paired updates
  after?: unknown[] | null;
}

export interface DataDiffResponse {
  schema: string;
  table: string;
  from: number;
  to: number;
  summary: DataDiffSummary;
  columns: PreviewColumn[];
  rows: DataDiffEntry[];
  nextCursor?: string | null;
  truncated: boolean;
}

// ----- Undrop (Spec 03) -----

export interface RecoverableTableEntry {
  schema: string;
  table: string;
  droppedAtSnapshot: number;
  lastLiveSnapshot: number;
  droppedAt?: string | null;   // ISO-8601; absent once the drop snapshot itself expired
  recoverable: boolean;
}

export interface RecoverableListResponse {
  tables: RecoverableTableEntry[];
}

export interface UndropRequest {
  tenant: string;
  tenantDb: string;
  schema: string;
  table: string;
  asName?: string;
  fromSnapshot?: number;
}

export interface UndropResponse {
  schema: string;
  table: string;
  restoredAs: string;
  fromSnapshot: number;
}

// ----- Managed maintenance (EPIC Spec 09) -----
// Mirrors the Scala DTOs in ondemand/api/Dtos.scala; field names must match
// the circe codecs exactly. Absent optionals serialize as null on responses.

export interface MaintenancePolicyUpsertRequest {
  tenant: string;
  tenantDb: string;
  scopeKind: string;            // "tenantdb" | "schema" | "table"
  scopeSchema?: string;
  scopeTable?: string;
  enabled?: boolean;
  retentionDays?: number;
  compactionEnabled?: boolean;
  targetFileSize?: string;
  smallFileMinCount?: number;
  rewriteDeleteThreshold?: number;
  cleanupGraceDays?: number;
  orphanMinAgeDays?: number;
  cron?: string;
}

export interface MaintenancePolicyEntry {
  id: string;
  tenant: string;
  tenantDb: string;
  scopeKind: string;            // "tenantdb" | "schema" | "table"
  scopeSchema: string | null;
  scopeTable: string | null;
  enabled: boolean | null;
  retentionDays: number | null;
  compactionEnabled: boolean | null;
  targetFileSize: string | null;
  smallFileMinCount: number | null;
  rewriteDeleteThreshold: number | null;
  cleanupGraceDays: number | null;
  orphanMinAgeDays: number | null;
  cron: string | null;
  updatedAt: string | null;     // ISO-8601
}

export interface MaintenanceEffectiveEntry {
  enabled: boolean;
  retentionDays: number;
  compactionEnabled: boolean;
  targetFileSize: string;
  smallFileMinCount: number;
  rewriteDeleteThreshold: number;
  cleanupGraceDays: number;
  orphanMinAgeDays: number;
  cron: string;
}

export interface MaintenancePolicyListResponse {
  rows: MaintenancePolicyEntry[];
  effective: MaintenanceEffectiveEntry;
}

export interface MaintenanceRunEntry {
  id: number;                   // bigserial, keyset cursor for `before`
  tenant: string;
  tenantDb: string;
  scope: string;                // "tenantdb" | "table:<schema>.<table>"
  trigger: string;              // "cadence" | "threshold" | "manual"
  operations: string | null;    // csv subset for manual runs; null = full chain
  status: string;               // "queued" | "running" | "succeeded" | "failed" | "partial"
  queuedAt: string;             // ISO-8601
  startedAt: string | null;
  finishedAt: string | null;
  heartbeatAt: string | null;
  nodeId: string | null;
  snapshotsExpired: number;
  snapshotsSkippedPinned: number;
  filesMerged: number;
  filesRewritten: number;
  filesCleaned: number;
  orphansDeleted: number;
  bytesReclaimed: number;
  error: string | null;
}

export interface MaintenanceRunRequest {
  tenant: string;
  tenantDb: string;
  scope?: string;               // "tenantdb" (default) | "table:<schema>.<table>"
  operations?: string;          // csv subset of flush,expire,merge,rewrite,cleanup,orphans
}

export interface MaintenanceRunResponse {
  id: number;
}

// ----- Federation -----

export interface FederatedSourceCreateRequest {
  alias: string;
  setupSql: string;
  description?: string;
  disabled?: boolean;
}

export interface FederatedSourceResponse {
  id: string;
  tenantDbId: string;
  alias: string;
  setupSql: string;
  description?: string;
  disabled: boolean;
}

export interface FederatedSourceListResponse {
  sources: FederatedSourceResponse[];
}

export interface FederatedSecretUpsertRequest {
  name: string;
  value?: string;
  externalRef?: string;
}

export interface FederatedSecretResponse {
  id: string;
  federatedSourceId: string;
  name: string;
  value?: string;        // server returns "***REDACTED***" when a value exists
  externalRef?: string;
}

export interface FederatedSecretListResponse {
  secrets: FederatedSecretResponse[];
}

export interface FederationImportSummary {
  sources: number;
  secrets: number;
}

// ----- Active statements + kill -----
export interface ActiveStatementInfo {
  id: string;
  user: string;
  tenant: string;
  pool: string;
  nodeId: string;
  sql: string;
  startedAt: string; // ISO-8601 UTC
  elapsedMs: number;
}
export interface ActiveStatementsResponse {
  statements: ActiveStatementInfo[];
}
export interface KillStatementRequest { id: string; }
export interface KillStatementResponse { status: string; } // accepted | already-completed

// ----- History trends -----
export interface TrendBucketEntry {
  bucketStart: string;   // ISO-8601 UTC
  tenant: string;
  pool: string;
  username: string;
  stmtCount: number;
  errorCount: number;
  deniedCount: number;
  engineMsSum: number;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
}

export interface TrendsResponse {
  buckets: TrendBucketEntry[];
}

// ----- Persisted statement search -----
export interface StatementHistoryRowEntry {
  id: string;
  ts: string;
  username: string;
  tenant: string;
  pool: string;
  nodeId: string;
  sql: string;
  durationMs: number;
  prepareMs: number | null;
  status: string;
  error: string | null;
}

export interface StatementSearchResponse {
  statements: StatementHistoryRowEntry[];
  nextBefore: string | null;
}

// ----- Usage and accounting -------------------------------------------------

export interface UsageDayEntry {
  day: string; // ISO-8601 UTC day-bucket start
  statements: number;
  errors: number;
  engineMs: number;
}

export interface UsageGroupEntry {
  tenant: string;
  pool: string | null;     // set only for groupBy=pool
  username: string | null; // set only for groupBy=user
  statements: number;
  errors: number;
  denied: number;
  engineMs: number;
  days: UsageDayEntry[];
}

export interface UsageResponse {
  from: string;
  to: string;
  groupBy: string;
  dataStart: string | null;
  groups: UsageGroupEntry[]; // sorted by engineMs descending
}
