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
} from './types';

const BASE = '/api';
const TOKEN_KEY = 'quack-on-demand-session';

export const session = {
  get: (): string | null => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

function authHeaders(): Record<string, string> {
  const token = session.get();
  return token ? { 'X-API-Key': token } : {};
}

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
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return handle<T>(r);
}

async function get<T>(path: string): Promise<T> {
  const r = await fetch(`${BASE}${path}`, { headers: authHeaders() });
  return handle<T>(r);
}

export const api = {
  // Auth
  login:   (req: LoginRequest) => post<LoginResponse>('/auth/login', req),
  logout:  () => post<void>('/auth/logout'),
  whoami:  () => get<WhoamiResponse>('/auth/whoami'),

  // Health + client config
  health:        () => fetch('/health').then(r => r.json() as Promise<HealthResponse>),
  clientConfig:  () => get<ClientConfigResponse>('/config/client'),
  serverConfig:  () => get<ConfigListResponse>('/config/server'),
  exportManifest: async (): Promise<string> => {
    const r = await fetch(`${BASE}/manifest/export`, { headers: authHeaders() });
    if (!r.ok) throw new ApiError(r.status, await r.text());
    return await r.text();
  },
  importManifest: async (yaml: string): Promise<ManifestImportSummary> => {
    const r = await fetch(`${BASE}/manifest/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/yaml', ...authHeaders() },
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
