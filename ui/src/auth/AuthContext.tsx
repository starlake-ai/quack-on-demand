import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { api, ApiError } from '../api/client';

interface AuthState {
  username: string | null;
  role: string | null;
  tenant: string | null;
  superuser: boolean;
  manageableTenants: string[];
  loading: boolean;
  // True when the server has no auth providers configured (auth.* all
  // disabled). In that case the UI runs without a login screen and the
  // "user" is a synthetic anonymous principal.
  authEnabled: boolean;
  // "db" = password form login; "oidc" = IdP redirect flow.
  identitySource: 'db' | 'oidc';
  // Human-readable IdP label, e.g. "Keycloak" or "Google". Empty string when db mode.
  ssoProviderName: string;
  login: (username: string, password: string, tenant?: string) => Promise<void>;
  logout: () => Promise<void>;
  // Redirect the browser to the OIDC start endpoint (oidc mode only).
  ssoLogin: (tenant?: string) => void;
}

const AuthContext = createContext<AuthState | null>(null);

const ANONYMOUS_USERNAME = 'anonymous';
const ANONYMOUS_ROLE     = 'admin';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [username, setUsername] = useState<string | null>(null);
  const [role, setRole]         = useState<string | null>(null);
  const [tenant, setTenant]     = useState<string | null>(null);
  const [superuser, setSuperuser]                   = useState<boolean>(false);
  const [manageableTenants, setManageableTenants]   = useState<string[]>([]);
  const [loading, setLoading]   = useState(true);
  const [authEnabled, setAuthEnabled] = useState(true);
  const [identitySource, setIdentitySource] = useState<'db' | 'oidc'>('db');
  const [ssoProviderName, setSsoProviderName] = useState<string>('');

  // On mount:
  //   1. Ask the server whether auth is enabled (open endpoint, no token).
  //   2. If disabled → set a synthetic "anonymous" user and skip everything.
  //   3. Otherwise resume any existing session via /api/auth/whoami; on 401
  //      drop the stale token and let the login screen render.
  useEffect(() => {
    api.clientConfig()
      .then(cfg => {
        setIdentitySource(cfg.identitySource ?? 'db');
        setSsoProviderName(cfg.ssoProviderName ?? '');
        if (!cfg.authEnabled) {
          setAuthEnabled(false);
          setUsername(ANONYMOUS_USERNAME);
          setRole(ANONYMOUS_ROLE);
          setTenant(null);
          setSuperuser(true);
          setManageableTenants([]);
          setLoading(false);
          return;
        }
        setAuthEnabled(true);
        // No client-side session token to consult: the qod_session cookie is
        // HttpOnly. Always ask whoami -- the browser auto-attaches the
        // cookie if there is one. 401 = no live session, fall through to
        // the login screen.
        api.whoami()
          .then(w => {
            setUsername(w.username);
            setRole(w.role);
            setTenant(w.tenant ?? null);
            setSuperuser(w.superuser ?? false);
            setManageableTenants(w.manageableTenants ?? []);
          })
          .catch((_: ApiError) => { /* no live session; render login */ })
          .finally(() => setLoading(false));
      })
      .catch(() => {
        // Network failure or 5xx - fall back to the login screen so the
        // user gets a clear error rather than a silent skip.
        setLoading(false);
      });
  }, []);

  async function login(u: string, p: string, t?: string) {
    // Server sets the HttpOnly qod_session cookie on a successful response;
    // the JS-visible `token` field in the body is for CLI callers, not us.
    const r = await api.login({ username: u, password: p, tenant: t?.trim() || undefined });
    setUsername(r.username);
    setTenant(r.tenant ?? null);
    setSuperuser(r.superuser ?? false);
    setManageableTenants(r.manageableTenants ?? []);
    // /login no longer returns `role` (every minted session is admin by
    // construction); fetch the descriptive role from /whoami so the badge
    // reflects what the auth backend recorded.
    try {
      const w = await api.whoami();
      setRole(w.role);
    } catch {
      setRole('admin');
    }
  }

  function ssoLogin(tenant?: string) {
    // Pick up ?tenant= from the current URL when no explicit tenant is given.
    const t = tenant ?? new URLSearchParams(window.location.search).get('tenant') ?? undefined;
    window.location.href = '/api/auth/oidc/start' + (t ? `?tenant=${encodeURIComponent(t)}` : '');
  }

  function ssoLogout() {
    window.location.href = '/api/auth/oidc/logout';
  }

  async function logout() {
    // No-op when auth is disabled - there's no session to revoke.
    if (!authEnabled) return;
    // OIDC mode: redirect to the IdP logout endpoint; the server clears the
    // session cookie there.
    if (identitySource === 'oidc') { ssoLogout(); return; }
    // Password mode: server-side clear the qod_session cookie + denylist the
    // jti for the remaining lifetime. JS can't clear an HttpOnly cookie,
    // so this hop is required.
    try { await api.logout(); } catch { /* best effort */ }
    setUsername(null);
    setRole(null);
    setTenant(null);
    setSuperuser(false);
    setManageableTenants([]);
  }

  return (
    <AuthContext.Provider value={{ username, role, tenant, superuser, manageableTenants, loading, authEnabled, identitySource, ssoProviderName, login, logout, ssoLogin }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}