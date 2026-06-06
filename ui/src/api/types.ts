export type Role = 'READONLY' | 'WRITEONLY' | 'DUAL';

export interface RoleDistribution {
  writeonly: number;
  readonly: number;
  dual: number;
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
}

export interface PoolResponse {
  tenant: string;
  tenantDb: string;
  pool: string;
  nodes: NodeInfo[];
  status: string;
  // Effective metastore for this pool (inherited from the tenant-db).
  // pgPassword is redacted.
  metastore: Record<string, string>;
  disabled: boolean;
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

export interface SetMaxConcurrentRequest {
  tenant: string;
  tenantDb: string;
  pool: string;
  nodeId: string;
  max: number;
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
  tenantClaim: string;
  // When false, no auth providers are configured server-side and the UI
  // skips the login screen entirely. The REST API may still require an
  // X-API-Key - that's a separate gate.
  authEnabled: boolean;
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
  name: string;
  authProvider?: AuthProvider;
  authConfig?: Record<string, string>;
}

export interface TenantResponse {
  name: string;
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
export interface TenantDbRequest {
  tenant: string;
  name: string; // suffix; server composes `${tenant}_${name}`
  metastore?: Record<string, string>;
  dataPath?: string;
  objectStore?: Record<string, string>;
}

export interface TenantDbResponse {
  id: string;
  tenant: string;
  name: string;
  metastore: Record<string, string>;
  dataPath: string;
  objectStore: Record<string, string>;
  disabled: boolean;
}

export interface TenantDbListResponse {
  tenantDbs: TenantDbResponse[];
}

export interface TenantDbOpRequest {
  tenant: string;
  name: string;
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
export interface LoginRequest  { username: string; password: string; }
export interface LoginResponse { token: string; username: string; role: string; }
export interface WhoamiResponse { username: string; role: string; }

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
}