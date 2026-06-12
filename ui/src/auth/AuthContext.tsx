import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { api, session, ApiError } from '../api/client';

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
  login: (username: string, password: string, tenant?: string) => Promise<void>;
  logout: () => Promise<void>;
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

  // On mount:
  //   1. Ask the server whether auth is enabled (open endpoint, no token).
  //   2. If disabled → set a synthetic "anonymous" user and skip everything.
  //   3. Otherwise resume any existing session via /api/auth/whoami; on 401
  //      drop the stale token and let the login screen render.
  useEffect(() => {
    api.clientConfig()
      .then(cfg => {
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
        const t = session.get();
        if (!t) { setLoading(false); return; }
        api.whoami()
          .then(w => {
            setUsername(w.username);
            setRole(w.role);
            setTenant(w.tenant ?? null);
            setSuperuser(w.superuser ?? false);
            setManageableTenants(w.manageableTenants ?? []);
          })
          .catch((e: ApiError) => { if (e.status === 401) session.clear(); })
          .finally(() => setLoading(false));
      })
      .catch(() => {
        // Network failure or 5xx - fall back to the login screen so the
        // user gets a clear error rather than a silent skip.
        setLoading(false);
      });
  }, []);

  async function login(u: string, p: string, t?: string) {
    const r = await api.login({ username: u, password: p, tenant: t?.trim() || undefined });
    session.set(r.token);
    setUsername(r.username);
    setTenant(r.tenant ?? null);
    setSuperuser(r.superuser ?? false);
    setManageableTenants(r.manageableTenants ?? []);
    // /login no longer returns `role` (every minted session is admin by
    // construction); fetch the descriptive role from /whoami so the badge
    // reflects what the auth backend recorded (defaults to "admin" if the
    // call fails so the nav doesn't render a stale label).
    try {
      const w = await api.whoami();
      setRole(w.role);
    } catch {
      setRole('admin');
    }
  }

  async function logout() {
    // No-op when auth is disabled - there's no session to revoke.
    if (!authEnabled) return;
    try { await api.logout(); } catch { /* best effort */ }
    session.clear();
    setUsername(null);
    setRole(null);
    setTenant(null);
    setSuperuser(false);
    setManageableTenants([]);
  }

  return (
    <AuthContext.Provider value={{ username, role, tenant, superuser, manageableTenants, loading, authEnabled, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}