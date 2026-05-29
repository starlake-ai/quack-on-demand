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
  pool: string;
  nodes: NodeInfo[];
  status: string;
  metastore: Record<string, string>;   // effective merged metastore; pgPassword redacted
}

export interface CreatePoolRequest {
  tenant: string;
  pool: string;
  size: number;
  roleDistribution: RoleDistribution;
  metastore: Record<string, string>;
  s3?: Record<string, string>;
  maxConcurrentPerNode?: number; // default 0 = unlimited
}

export interface ScalePoolRequest {
  tenant: string;
  pool: string;
  targetSize: number;
  roleDistribution: RoleDistribution;
  force?: boolean;
}

export interface StopPoolRequest {
  tenant: string;
  pool: string;
  force?: boolean;
}

export interface SetMaxConcurrentRequest {
  tenant: string;
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

export interface TenantRequest {
  name: string;
  metastore?: Record<string, string>;
}

export interface TenantResponse {
  name: string;
  metastore: Record<string, string>;             // tenant's own overrides
  pools: string[];
  effectiveMetastore: Record<string, string>;    // global defaults merged with overrides; pgPassword redacted
}

export interface TenantListResponse {
  tenants: TenantResponse[];
}

export interface TenantOpRequest {
  name: string;
}

// ----- ACL grants -----
export interface AclGrant {
  id: number;
  tenantId: string;
  principal: string;                 // e.g. "user:alice", "group:eng", "role:admin"
  catalogName: string | null;        // null = any
  schemaName:  string | null;
  tableName:   string | null;
  permission:  string;               // SELECT | INSERT | UPDATE | DELETE | ALL
  grantedAt:   string;               // ISO-8601
}

export interface AclGrantRequest {
  tenantId: string;
  principal: string;
  catalogName?: string | null;
  schemaName?:  string | null;
  tableName?:   string | null;
  permission:   string;
}

export interface AclGrantListResponse { grants: AclGrant[]; }
export interface AclGrantBulkRequest  { grants: AclGrantRequest[]; }

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