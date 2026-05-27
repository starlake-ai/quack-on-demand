import type {
  CreatePoolRequest,
  ScalePoolRequest,
  StopPoolRequest,
  SetMaxConcurrentRequest,
  PoolResponse,
  HealthResponse,
  TenantRequest,
  TenantResponse,
  TenantListResponse,
  TenantOpRequest,
  ClientConfigResponse,
  AclGrant,
  AclGrantRequest,
  AclGrantListResponse,
  AclGrantBulkRequest,
  LoginRequest,
  LoginResponse,
  WhoamiResponse,
  StatementHistoryResponse,
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

  // Pools + nodes
  listPools:   () => get<{ pools: PoolResponse[] }>('/pool/list'),
  poolStatus:  (t: string, p: string) => get<PoolResponse>(`/pool/${t}/${p}/status`),
  createPool:  (req: CreatePoolRequest) => post<PoolResponse>('/pool/create', req),
  scalePool:   (req: ScalePoolRequest) => post<PoolResponse>('/pool/scale', req),
  stopPool:    (req: StopPoolRequest) => post<void>('/pool/stop', req),
  setMaxConcurrent: (req: SetMaxConcurrentRequest) => post<void>('/node/setMaxConcurrent', req),

  // Tenants
  listTenants:        () => get<TenantListResponse>('/tenant/list'),
  createTenant:       (req: TenantRequest)   => post<TenantResponse>('/tenant/create', req),
  setTenantMetastore: (req: TenantRequest)   => post<TenantResponse>('/tenant/setMetastore', req),
  deleteTenant:       (req: TenantOpRequest) => post<void>('/tenant/delete', req),

  // ACL grants
  listAclGrants:   (tenant?: string) => {
    const q = tenant ? `?tenant=${encodeURIComponent(tenant)}` : '';
    return get<AclGrantListResponse>(`/acl/grant/list${q}`);
  },
  createAclGrant:  (req: AclGrantRequest) => post<AclGrant>('/acl/grant/create', req),
  deleteAclGrant:  (id: number)           => post<void>(`/acl/grant/delete/${id}`),
  uploadAclGrants: (req: AclGrantBulkRequest) => post<AclGrantListResponse>('/acl/grant/upload', req),

  // Recent statement history (newest first)
  statementHistory: (limit = 50) =>
    get<StatementHistoryResponse>(`/node/statements?limit=${limit}`),
};
