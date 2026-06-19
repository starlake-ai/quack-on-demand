import { useEffect } from 'react';
import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Login from './pages/Login';

// ---- SSO error codes returned by /api/auth/oidc/callback via ?error= ----

const SSO_ERROR_COPY: Record<string, { title: string; detail: string }> = {
  not_provisioned: {
    title: 'Account not provisioned',
    detail: 'Your identity was verified but no matching user account exists in Quack on Demand. Contact your administrator.',
  },
  admin_required: {
    title: 'Admin access required',
    detail:
      'This console is restricted to admin users. If you are a tenant admin, sign in via your tenant URL (e.g. /ui/?tenant=YOURTENANT).',
  },
  invalid_state: {
    title: 'Session expired',
    detail: 'The sign-in session timed out or the state parameter was invalid. Please try again.',
  },
  idp_error: {
    title: 'Identity provider error',
    detail: 'The identity provider returned an error. Please try again or contact your administrator.',
  },
  oidc_not_configured: {
    title: 'SSO not configured',
    detail: 'OIDC single sign-on is not configured on this server. Contact your administrator.',
  },
  discovery_failed: {
    title: 'IdP discovery failed',
    detail: 'The server could not reach the identity provider discovery endpoint. Try again later.',
  },
  auth_mode_disabled: {
    title: 'Authentication disabled',
    detail: 'The requested authentication mode is disabled on this server.',
  },
};

function SsoError({ code, onRetry }: { code: string; onRetry: () => void }) {
  const copy = SSO_ERROR_COPY[code] ?? {
    title: 'Sign-in error',
    detail: `An unexpected error occurred (code: ${code}). Please try again.`,
  };
  return (
    <div className="login-shell">
      <div className="login-card">
        <div className="login-brand">
          <img src="/ui/mark-dark.svg" alt="" className="login-logo" />
          <h1>Quack on Demand</h1>
          <p className="login-sub">Admin console</p>
        </div>
        <div className="login-err">{copy.title}</div>
        <p style={{ margin: '0.5rem 0 1rem' }}>{copy.detail}</p>
        <button onClick={onRetry}>Try again</button>
      </div>
    </div>
  );
}
import TenantList from './pages/TenantList';
import TenantDetail from './pages/TenantDetail';
import PoolDetail from './pages/PoolDetail';
import Nodes from './pages/Nodes';
import Catalog from './pages/Catalog';
import CatalogTableDetail from './pages/CatalogTableDetail';
import Users from './pages/Users';
import Config from './pages/Config';

function Shell() {
  const { username, role, tenant, logout, authEnabled } = useAuth();
  // Config (resolved application.conf + manifest export/import) is a
  // cross-tenant view of the entire deployment, so it's superuser-only.
  // `tenant === null` flags the session as system-scoped; tenant-bound
  // admins (when that path lands) are silently dropped from the nav --
  // the matching backend endpoints also 403 them so URL deep-links don't
  // leak. `authEnabled === false` is the no-auth dev mode; treat the
  // synthetic anonymous user as a superuser there.
  const isSuperuser = !authEnabled || tenant === null;
  return (
    <>
      <nav className="app-nav">
        <span className="brand">
          <img src="/ui/mark-dark.svg" alt="" className="brand-mark" />
          Quack on Demand
        </span>
        <NavLink to="/"        end className={({ isActive }) => isActive ? 'active' : ''}>Nodes</NavLink>
        <NavLink to="/tenants"     className={({ isActive }) => isActive ? 'active' : ''}>Tenants</NavLink>
        <NavLink to="/users"       className={({ isActive }) => isActive ? 'active' : ''}>Users</NavLink>
        {role === 'admin' && isSuperuser && (
          <NavLink to="/config"    className={({ isActive }) => isActive ? 'active' : ''}>Config</NavLink>
        )}
        <span className="spacer" />
        {authEnabled ? (
          <>
            <span className="user-pill">
              {username} <span className="role">{role}</span>
            </span>
            <button className="secondary" onClick={() => { void logout(); }}>Sign out</button>
          </>
        ) : (
          <span className="user-pill" title="Server has no auth providers configured">
            anonymous <span className="role">no-auth</span>
          </span>
        )}
      </nav>
      <main>
        <Routes>
          <Route path="/"                                 element={<Nodes />} />
          <Route path="/tenants"                          element={<TenantList />} />
          <Route path="/tenant/:tenant"                   element={<TenantDetail />} />
          <Route path="/pool/:tenant/:tenantDb/:pool"              element={<PoolDetail />} />
          <Route path="/nodes"                                     element={<Nodes />} />
          <Route path="/users"                                     element={<Users />} />
          <Route path="/catalog"                                   element={<Catalog />} />
          <Route path="/catalog/:tenant/:tenantDb/:schema/:table"  element={<CatalogTableDetail />} />
          {isSuperuser && (
            <Route path="/config"                                  element={<Config />} />
          )}
        </Routes>
      </main>
    </>
  );
}

function SsoRedirect({ ssoLogin }: { ssoLogin: () => void }) {
  // Side effect in an effect (not the render body) so it does not double-fire
  // under React StrictMode.
  useEffect(() => { ssoLogin(); }, []);
  return <div className="loading">Redirecting to sign-in…</div>;
}

function AuthGate() {
  const { username, loading, identitySource, ssoLogin } = useAuth();
  if (loading) return <div className="loading">Loading session…</div>;
  if (username) return <Shell />;
  // OIDC mode: redirect to the IdP, or show an error card when the callback
  // returned with ?error=<code>.
  if (identitySource === 'oidc') {
    const err = new URLSearchParams(window.location.search).get('error');
    if (err) return <SsoError code={err} onRetry={ssoLogin} />;
    return <SsoRedirect ssoLogin={ssoLogin} />;
  }
  // db mode: unchanged password form.
  return <Login />;
}

export default function App() {
  return (
    <BrowserRouter basename="/ui">
      <AuthProvider>
        <AuthGate />
      </AuthProvider>
    </BrowserRouter>
  );
}
