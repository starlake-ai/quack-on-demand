import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { api } from '../api/client';
import { parseScope } from '../nav/scope';

interface AuthState {
  username: string | null;
  role: string | null;
  tenant: string | null;
  superuser: boolean;
  manageableTenants: string[];
  loading: boolean;
  // "db" = password form login; "oidc" = IdP redirect flow.
  identitySource: 'db' | 'oidc';
  // Human-readable IdP label, e.g. "Keycloak" or "Google". Empty string when db mode.
  ssoProviderName: string;
  // Mirrors ClientConfigResponse.telemetryEnabled; gates the History/Usage/Audit surfaces.
  telemetryEnabled: boolean;
  // Mirrors ClientConfigResponse.starlakeUrl; null/undefined = integration off
  // and the UI hides the "Workbench" nav entry. Set = base URL to redirect to.
  starlakeUrl: string | null;
  // Mirrors ClientConfigResponse.fleetEnabled; gates the Fleet servers nav entry.
  fleetEnabled: boolean;
  login: (username: string, password: string, tenant?: string) => Promise<void>;
  logout: () => Promise<void>;
  // Redirect the browser to the OIDC start endpoint (oidc mode only).
  ssoLogin: (tenant?: string) => void;
}

const AuthContext = createContext<AuthState | null>(null);

// The tenant to use for login-mode resolution and SSO start: `?tenant=` wins; otherwise the
// tenant of a scoped path (/ui/t/<tenant>/...), so a session-expiry reload or a direct hit on a
// scoped page resolves (and, for SSO, starts) that tenant's own identity provider rather than
// the system one.
function loginTenantFromUrl(): string | undefined {
  const fromQuery = new URLSearchParams(window.location.search).get('tenant');
  if (fromQuery) return fromQuery;
  const pathScope = parseScope(window.location.pathname.replace(/^\/ui/, ''))?.scope;
  return pathScope?.kind === 'tenant' ? pathScope.tenant : undefined;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [username, setUsername] = useState<string | null>(null);
  const [role, setRole]         = useState<string | null>(null);
  const [tenant, setTenant]     = useState<string | null>(null);
  const [superuser, setSuperuser]                   = useState<boolean>(false);
  const [manageableTenants, setManageableTenants]   = useState<string[]>([]);
  const [loading, setLoading]   = useState(true);
  const [identitySource, setIdentitySource] = useState<'db' | 'oidc'>('db');
  const [ssoProviderName, setSsoProviderName] = useState<string>('');
  const [telemetryEnabled, setTelemetryEnabled] = useState(false);
  const [starlakeUrl, setStarlakeUrl] = useState<string | null>(null);
  const [fleetEnabled, setFleetEnabled] = useState(false);

  // On mount:
  //   1. Read the client config (open endpoint, no token).
  //   2. Resolve the per-tenant login mode from the URL's ?tenant= via
  //      /api/auth/mode (db -> password form, oidc -> SSO redirect). The bare
  //      /ui/ (no tenant) resolves to the manager-wide system mode.
  //   3. Resume any existing session via /api/auth/whoami; on 401 fall through
  //      to whichever login screen the resolved mode dictates.
  // There is no anonymous mode: a manager nobody can sign in to refuses to boot.
  useEffect(() => {
    (async () => {
      let cfg;
      try {
        cfg = await api.clientConfig();
      } catch {
        // Network failure or 5xx - fall back to the login screen so the
        // user gets a clear error rather than a silent skip.
        setLoading(false);
        return;
      }
      setSsoProviderName(cfg.ssoProviderName ?? '');
      setTelemetryEnabled(cfg.telemetryEnabled !== false);
      setStarlakeUrl(cfg.starlakeUrl ?? null);
      setFleetEnabled(cfg.fleetEnabled === true);
      // Resolve the login mode for the tenant in the URL BEFORE deciding what
      // to render, so the gate does not flash the password form then swap to
      // the SSO redirect (or vice versa).
      // ?tenant= wins; otherwise the tenant in a scoped path (/ui/t/<tenant>/...), so a
      // session-expiry reload on a scoped page keeps that tenant's login mode.
      const urlTenant = loginTenantFromUrl();
      try {
        const m = await api.authMode(urlTenant);
        setIdentitySource(m.mode);
        if (m.ssoProviderName) setSsoProviderName(m.ssoProviderName);
      } catch {
        setIdentitySource('db'); // safe fallback: show the password form
      }
      // No client-side session token to consult: the qod_session cookie is
      // HttpOnly. Always ask whoami -- the browser auto-attaches the cookie if
      // there is one. 401 = no live session, fall through to the login screen.
      try {
        const w = await api.whoami();
        setUsername(w.username);
        setRole(w.role);
        setTenant(w.tenant ?? null);
        setSuperuser(w.superuser ?? false);
        setManageableTenants(w.manageableTenants ?? []);
      } catch {
        /* no live session; render login */
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  async function login(u: string, p: string, t?: string) {
    // Server sets the HttpOnly qod_session cookie on a successful response;
    // the JS-visible `token` field in the body is for CLI callers, not us.
    const r = await api.login({ username: u, password: p, tenant: t?.trim() || undefined });
    setUsername(r.username);
    setTenant(r.tenant ?? null);
    setSuperuser(r.superuser ?? false);
    setManageableTenants(r.manageableTenants ?? []);
    // /login returns the coarse `admin` gate (false for a tenant-scoped
    // regular user) but not the descriptive role label, and its `tenant` field
    // is always null. Re-seed the whole identity from /whoami so the
    // in-memory state matches what a page reload would produce - Profile's
    // change-password call sends `tenant`, and a null there resolves to the
    // superuser row and 401s a tenant-scoped user. Fall back to the coarse
    // gate if whoami is unreachable for some reason.
    try {
      const w = await api.whoami();
      setRole(w.role);
      setTenant(w.tenant ?? null);
      setSuperuser(w.superuser ?? false);
      setManageableTenants(w.manageableTenants ?? []);
    } catch {
      setRole(r.admin === false ? 'user' : 'admin');
    }
  }

  function ssoLogin(tenant?: string) {
    // An explicit tenant argument wins; otherwise fall back to the same URL-derived tenant used
    // for login-mode resolution, so reloading a scoped tenant page starts that tenant's own IdP
    // instead of the system one.
    const t = tenant ?? loginTenantFromUrl();
    window.location.href = '/api/auth/oidc/start' + (t ? `?tenant=${encodeURIComponent(t)}` : '');
  }

  function ssoLogout() {
    window.location.href = '/api/auth/oidc/logout';
  }

  async function logout() {
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
    <AuthContext.Provider value={{ username, role, tenant, superuser, manageableTenants, loading, identitySource, ssoProviderName, telemetryEnabled, starlakeUrl, fleetEnabled, login, logout, ssoLogin }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
