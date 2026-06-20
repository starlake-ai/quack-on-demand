import type {
  CreatePoolRequest,
  ScalePoolRequest,
  StopPoolRequest,
  SetMaxConcurrentRequest,
  SetPoolDisabledRequest,
  SetTenantAuthRequest,
  SetTenantDisabledRequest,
  PoolResponse,
  HealthResponse,
  TenantRequest,
  TenantResponse,
  TenantListResponse,
  TenantOpRequest,
  TenantDbRequest,
  TenantDbResponse,
  TenantDbListResponse,
  TenantDbOpRequest,
  ClientConfigResponse,
  ConfigListResponse,
  ManifestImportSummary,
  AuthModeResponse,
  LoginRequest,
  LoginResponse,
  WhoamiResponse,
  StatementHistoryResponse,
  CatalogSchemaEntry,
  CatalogTableEntry,
  CatalogTableDetailResponse,
  // RBAC
  UserResponse,
  UserCreateRequest,
  UserUpdateRequest,
  UserDeleteRequest,
  UserListResponse,
  RoleResponse,
  RoleCreateRequest,
  RoleDeleteRequest,
  RoleListResponse,
  RolePermissionResponse,
  RolePermissionGrantRequest,
  RolePermissionRevokeRequest,
  RolePermissionListResponse,
  ColumnPolicyDto,
  CreateColumnPolicyRequest,
  UpdateColumnPolicyRequest,
  DeleteColumnPolicyRequest,
  ColumnPolicyListResponse,
  RowPolicyDto,
  CreateRowPolicyRequest,
  UpdateRowPolicyRequest,
  DeleteRowPolicyRequest,
  RowPolicyListResponse,
  GroupResponse,
  GroupCreateRequest,
  GroupDeleteRequest,
  GroupListResponse,
  UserRoleMembershipRequest,
  UserGroupMembershipRequest,
  GroupRoleMembershipRequest,
  PoolPermissionResponse,
  PoolPermissionGrantRequest,
  PoolPermissionRevokeRequest,
  PoolPermissionListResponse,
  EffectivePermissionsResponse,
  // Federation
  FederatedSourceCreateRequest,
  FederatedSourceResponse,
  FederatedSourceListResponse,
  FederatedSecretUpsertRequest,
  FederatedSecretResponse,
  FederatedSecretListResponse,
} from './types';

const BASE = '/api';

// Auth transport: the manager sets an HttpOnly qod_session cookie on
// /api/auth/login that the browser auto-attaches on every same-origin
// request. JavaScript can NOT read or write that cookie -- /api/auth/whoami
// is the canonical "am I logged in?" check, and /api/auth/logout is the only
// way to clear the cookie. The legacy localStorage token path is gone; CLI
// clients still go through X-API-Key separately.
export const session = {
  /** Kept as a stub so any straggler callers compile; the real session is
   * the HttpOnly cookie which JS cannot read. */
  get: (): string | null => null,
  set: (_: string) => { /* no-op; cookie is server-set */ },
  clear: () => { /* no-op; cookie is server-cleared via /api/auth/logout */ },
};

/** fetch options shared by every authed call. `credentials: 'same-origin'`
 * is the default but spelling it out documents intent. */
const FETCH_OPTS: RequestInit = { credentials: 'same-origin' };

/** API error with a normalized message + HTTP status so callers can branch
 * on 401 (login-page redirect) vs. other failures. */
export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function handle<T>(r: Response): Promise<T> {
  if (!r.ok) {
    const body = await r.text();
    let msg = body;
    try {
      const j = JSON.parse(body);
      msg = j.message ?? j.error ?? body;
    } catch { /* not JSON */ }
    throw new ApiError(r.status, msg || r.statusText);
  }
  return r.headers.get('content-length') === '0'
    ? (undefined as T)
    : await r.json();
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const r = await fetch(`${BASE}${path}`, {
    ...FETCH_OPTS,
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return handle<T>(r);
}

async function get<T>(path: string): Promise<T> {
  const r = await fetch(`${BASE}${path}`, FETCH_OPTS);
  return handle<T>(r);
}

export const api = {
  // Auth
  login:   (req: LoginRequest) => post<LoginResponse>('/auth/login', req),
  logout:  () => post<void>('/auth/logout'),
  whoami:  () => get<WhoamiResponse>('/auth/whoami'),
  // Per-tenant login mode (unauthenticated). Drives the password-form vs. SSO-redirect branch.
  authMode: (tenant?: string) =>
    get<AuthModeResponse>('/auth/mode' + (tenant ? `?tenant=${encodeURIComponent(tenant)}` : '')),

  // Health + client config
  health:        () => fetch('/health').then(r => r.json() as Promise<HealthResponse>),
  clientConfig:  () => get<ClientConfigResponse>('/config/client'),
  serverConfig:  () => get<ConfigListResponse>('/config/server'),
  exportManifest: async (): Promise<string> => {
    const r = await fetch(`${BASE}/manifest/export`, FETCH_OPTS);
    if (!r.ok) throw new ApiError(r.status, await r.text());
    return await r.text();
  },
  importManifest: async (yaml: string): Promise<ManifestImportSummary> => {
    const r = await fetch(`${BASE}/manifest/import`, {
      ...FETCH_OPTS,
      method: 'POST',
      headers: { 'Content-Type': 'application/yaml' },
      body: yaml,
    });
    return handle<ManifestImportSummary>(r);
  },

  // Pools + nodes
  listPools:   () => get<{ pools: PoolResponse[] }>('/pool/list'),
  poolStatus:  (t: string, td: string, p: string) =>
    get<PoolResponse>(
      `/pool/${encodeURIComponent(t)}/${encodeURIComponent(td)}/${encodeURIComponent(p)}/status`
    ),
  createPool:  (req: CreatePoolRequest) => post<PoolResponse>('/pool/create', req),
  scalePool:   (req: ScalePoolRequest) => post<PoolResponse>('/pool/scale', req),
  stopPool:    (req: StopPoolRequest) => post<void>('/pool/stop', req),
  setMaxConcurrent: (req: SetMaxConcurrentRequest) => post<void>('/node/setMaxConcurrent', req),
  setPoolDisabled:  (req: SetPoolDisabledRequest)  => post<PoolResponse>('/pool/setDisabled', req),

  // Tenants
  listTenants:      () => get<TenantListResponse>('/tenant/list'),
  createTenant:     (req: TenantRequest)            => post<TenantResponse>('/tenant/create', req),
  deleteTenant:     (req: TenantOpRequest)          => post<void>('/tenant/delete', req),
  setTenantDisabled:(req: SetTenantDisabledRequest) => post<TenantResponse>('/tenant/setDisabled', req),
  setTenantAuth:    (req: SetTenantAuthRequest)     => post<TenantResponse>('/tenant/setAuth',     req),

  // Tenant databases
  listTenantDbs:  (tenant: string)       =>
    get<TenantDbListResponse>(`/database/list?tenant=${encodeURIComponent(tenant)}`),
  createTenantDb: (req: TenantDbRequest) => post<TenantDbResponse>('/database/create', req),
  deleteTenantDb: (req: TenantDbOpRequest) => post<void>('/database/delete', req),

  // ----- Federation -----
  listFederatedSources: (tenant: string, tenantDb: string) =>
    get<FederatedSourceListResponse>(
      `/tenants/${encodeURIComponent(tenant)}/tenant-dbs/${encodeURIComponent(tenantDb)}/federated-sources`
    ),

  createFederatedSource: (tenant: string, tenantDb: string, req: FederatedSourceCreateRequest) =>
    post<FederatedSourceResponse>(
      `/tenants/${encodeURIComponent(tenant)}/tenant-dbs/${encodeURIComponent(tenantDb)}/federated-sources`,
      req
    ),

  deleteFederatedSource: async (tenant: string, tenantDb: string, alias: string): Promise<void> => {
    const r = await fetch(
      `${BASE}/tenants/${encodeURIComponent(tenant)}/tenant-dbs/${encodeURIComponent(tenantDb)}/federated-sources/${encodeURIComponent(alias)}`,
      { ...FETCH_OPTS, method: 'DELETE' }
    );
    return handle<void>(r);
  },

  listFederatedSecrets: (tenant: string, tenantDb: string, alias: string) =>
    get<FederatedSecretListResponse>(
      `/tenants/${encodeURIComponent(tenant)}/tenant-dbs/${encodeURIComponent(tenantDb)}/federated-sources/${encodeURIComponent(alias)}/secrets`
    ),

  upsertFederatedSecret: async (
    tenant: string,
    tenantDb: string,
    alias: string,
    req: FederatedSecretUpsertRequest
  ): Promise<FederatedSecretResponse> => {
    const r = await fetch(
      `${BASE}/tenants/${encodeURIComponent(tenant)}/tenant-dbs/${encodeURIComponent(tenantDb)}/federated-sources/${encodeURIComponent(alias)}/secrets`,
      {
        ...FETCH_OPTS,
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
      }
    );
    return handle<FederatedSecretResponse>(r);
  },

  deleteFederatedSecret: async (
    tenant: string,
    tenantDb: string,
    alias: string,
    name: string
  ): Promise<void> => {
    const r = await fetch(
      `${BASE}/tenants/${encodeURIComponent(tenant)}/tenant-dbs/${encodeURIComponent(tenantDb)}/federated-sources/${encodeURIComponent(alias)}/secrets/${encodeURIComponent(name)}`,
      { ...FETCH_OPTS, method: 'DELETE' }
    );
    return handle<void>(r);
  },

  // ----- RBAC: users -----
  listUsers: (tenant?: string) => {
    const q = tenant ? `?tenant=${encodeURIComponent(tenant)}` : '';
    return get<UserListResponse>(`/user/list${q}`);
  },
  createUser:           (req: UserCreateRequest)  => post<UserResponse>('/user/create', req),
  updateUser:           (req: UserUpdateRequest)  => post<UserResponse>('/user/update', req),
  deleteUser:           (req: UserDeleteRequest)  => post<void>('/user/delete', req),
  effectivePermissions: (id: string) =>
    get<EffectivePermissionsResponse>(`/user/${encodeURIComponent(id)}/effective`),

  // ----- RBAC: roles -----
  listRoles:  (tenant: string) =>
    get<RoleListResponse>(`/role/list?tenant=${encodeURIComponent(tenant)}`),
  createRole: (req: RoleCreateRequest) => post<RoleResponse>('/role/create', req),
  deleteRole: (req: RoleDeleteRequest) => post<void>('/role/delete', req),
  listRolePermissions:  (roleId: string) =>
    get<RolePermissionListResponse>(`/role/permission/list?roleId=${encodeURIComponent(roleId)}`),
  grantRolePermission:  (req: RolePermissionGrantRequest)  =>
    post<RolePermissionResponse>('/role/permission/grant', req),
  revokeRolePermission: (req: RolePermissionRevokeRequest) =>
    post<void>('/role/permission/revoke', req),

  // ----- RBAC: column-level policies -----
  createColumnPolicy: (req: CreateColumnPolicyRequest) =>
    post<ColumnPolicyDto>('/role/column-policy/create', req),
  updateColumnPolicy: (req: UpdateColumnPolicyRequest) =>
    post<void>('/role/column-policy/update', req),
  deleteColumnPolicy: (req: DeleteColumnPolicyRequest) =>
    post<void>('/role/column-policy/delete', req),
  listColumnPolicies: (roleId: string) =>
    get<ColumnPolicyListResponse>(`/role/column-policy/list?roleId=${encodeURIComponent(roleId)}`),

  createRowPolicy: (req: CreateRowPolicyRequest) =>
    post<RowPolicyDto>('/role/row-policy/create', req),
  updateRowPolicy: (req: UpdateRowPolicyRequest) =>
    post<void>('/role/row-policy/update', req),
  deleteRowPolicy: (req: DeleteRowPolicyRequest) =>
    post<void>('/role/row-policy/delete', req),
  listRowPolicies: (roleId: string) =>
    get<RowPolicyListResponse>(`/role/row-policy/list?roleId=${encodeURIComponent(roleId)}`),

  // ----- RBAC: groups -----
  listGroups:  (tenant: string) =>
    get<GroupListResponse>(`/group/list?tenant=${encodeURIComponent(tenant)}`),
  createGroup: (req: GroupCreateRequest) => post<GroupResponse>('/group/create', req),
  deleteGroup: (req: GroupDeleteRequest) => post<void>('/group/delete', req),

  // ----- RBAC: memberships -----
  addUserRole:    (req: UserRoleMembershipRequest)  => post<void>('/membership/user-role/add',    req),
  removeUserRole: (req: UserRoleMembershipRequest)  => post<void>('/membership/user-role/remove', req),
  addUserGroup:   (req: UserGroupMembershipRequest) => post<void>('/membership/user-group/add',    req),
  removeUserGroup:(req: UserGroupMembershipRequest) => post<void>('/membership/user-group/remove', req),
  addGroupRole:   (req: GroupRoleMembershipRequest) => post<void>('/membership/group-role/add',    req),
  removeGroupRole:(req: GroupRoleMembershipRequest) => post<void>('/membership/group-role/remove', req),
  listGroupRoles: (groupId: string) =>
    get<RoleListResponse>(`/membership/group-role/list?groupId=${encodeURIComponent(groupId)}`),

  // ----- RBAC: pool permissions -----
  listPoolPermissions: (filters: { tenant?: string; userId?: string; groupId?: string } = {}) => {
    const qs = new URLSearchParams();
    if (filters.tenant)  qs.set('tenant',  filters.tenant);
    if (filters.userId)  qs.set('userId',  filters.userId);
    if (filters.groupId) qs.set('groupId', filters.groupId);
    const q = qs.toString() ? `?${qs.toString()}` : '';
    return get<PoolPermissionListResponse>(`/pool/permission/list${q}`);
  },
  grantPoolPermission:  (req: PoolPermissionGrantRequest)  =>
    post<PoolPermissionResponse>('/pool/permission/grant', req),
  revokePoolPermission: (req: PoolPermissionRevokeRequest) =>
    post<void>('/pool/permission/revoke', req),

  // Recent statement history (newest first)
  statementHistory: (limit = 50) =>
    get<StatementHistoryResponse>(`/node/statements?limit=${limit}`),

  // Catalog browser
  listCatalogSchemas: (tenant: string, tenantDb: string) =>
    get<CatalogSchemaEntry[]>(
      `/catalog/tenant/${encodeURIComponent(tenant)}/database/${encodeURIComponent(tenantDb)}/schemas`
    ),
  listCatalogTables: (tenant: string, tenantDb: string, schema: string) =>
    get<CatalogTableEntry[]>(
      `/catalog/tenant/${encodeURIComponent(tenant)}/database/${encodeURIComponent(tenantDb)}` +
        `/schemas/${encodeURIComponent(schema)}/tables`
    ),
  getCatalogTable: (tenant: string, tenantDb: string, schema: string, table: string) =>
    get<CatalogTableDetailResponse>(
      `/catalog/tenant/${encodeURIComponent(tenant)}/database/${encodeURIComponent(tenantDb)}` +
        `/schemas/${encodeURIComponent(schema)}/tables/${encodeURIComponent(table)}`
    ),
};
