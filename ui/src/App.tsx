import { useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Login from './pages/Login';
import ResetPassword from './pages/ResetPassword';

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
        <button onClick={() => onRetry()}>Try again</button>
      </div>
    </div>
  );
}
import Audit from './pages/Audit';
import History from './pages/History';
import Usage from './pages/Usage';
import TenantList from './pages/TenantList';
import PoolDetail from './pages/PoolDetail';
import Nodes from './pages/Nodes';
import Servers from './pages/Servers';
import Catalog from './pages/Catalog';
import CatalogTableDetail from './pages/CatalogTableDetail';
import IcebergTableDetail from './pages/IcebergTableDetail';
import UsersPage, { GroupsPage, RolesPage } from './pages/Users';
import {
  AccessControlPage, AuthProviderPage, BranchesPage, DatabasesPage, MaintenancePage, PoolsPage,
} from './pages/TenantPages';
import { ScopedLayout, usePrincipal } from './nav/TenantScope';
import { TenantsProvider } from './nav/TenantsContext';
import { defaultScope, parseScope, scopePrefix, scopeRedirect, type Scope } from './nav/scope';
import SidebarLayout from './nav/SidebarLayout';
import TenantSwitcher from './nav/TenantSwitcher';
import { NAV, visibleNav } from './nav/navModel';
import { legacyRedirect } from './nav/legacy';
import Config from './pages/Config';
import Profile from './pages/Profile';

// Regular (non-admin) session: the server only lets this token reach
// /api/auth/{whoami,logout} and /api/profile/{usage,statements}, so the
// sidebar is reduced to that self-service surface (no tenant switcher). No
// admin routes are even mounted; every other path lands on the profile.
function ProfileShell() {
  const { starlakeUrl, telemetryEnabled, fleetEnabled } = useAuth();
  const items = visibleNav(NAV, {
    superuser: false, admin: false, telemetry: telemetryEnabled, fleet: fleetEnabled,
    starlake: !!starlakeUrl, scope: { kind: 'all' },
  });
  return (
    <SidebarLayout items={items} switcher={() => null}>
      <Routes>
        <Route path="/settings/profile" element={<Profile />} />
        <Route path="*" element={<Navigate to="/settings/profile" replace />} />
      </Routes>
    </SidebarLayout>
  );
}

/** Routes shared by `/t/:tenant` and `/all`; ScopedLayout decides what a scope may show. */
function scopedRoutes() {
  return (
    <>
      <Route index element={<Navigate to="dashboard" replace />} />
      <Route path="dashboard" element={<Nodes />} />
      <Route path="databases" element={<DatabasesPage />} />
      <Route path="catalog" element={<Catalog />} />
      <Route path="catalog/:tenantDb/:schema/:table" element={<CatalogTableDetail />} />
      <Route path="catalog/:tenantDb/iceberg/:alias/:schema/:table" element={<IcebergTableDetail />} />
      <Route path="pools" element={<PoolsPage />} />
      <Route path="pools/:tenantDb/:pool" element={<PoolDetail />} />
      <Route path="maintenance" element={<MaintenancePage />} />
      <Route path="branches" element={<BranchesPage />} />
      <Route path="auth-provider" element={<AuthProviderPage />} />
      <Route path="access-control" element={<AccessControlPage />} />
      <Route path="users" element={<UsersPage />} />
      <Route path="groups" element={<GroupsPage />} />
      <Route path="roles" element={<RolesPage />} />
      <Route path="audit/control-plane" element={<Audit />} />
      <Route path="audit/statements" element={<History />} />
      <Route path="audit/usage" element={<Usage />} />
      <Route path="*" element={<Navigate to="dashboard" replace />} />
    </>
  );
}

/** Catch-all: an old bookmark goes to its new home, anything else to the default dashboard. */
function LegacyRedirect() {
  const location = useLocation();
  const def = defaultScope(usePrincipal());
  const to = legacyRedirect(location.pathname, location.search, def) ?? `${scopePrefix(def)}/dashboard`;
  return <Navigate to={to} replace />;
}

function Shell() {
  const { role, telemetryEnabled, fleetEnabled, starlakeUrl } = useAuth();
  // Config (resolved application.conf + manifest export/import) is a
  // cross-tenant view of the entire deployment, so it's superuser-only.
  // `tenant === null` flags the session as system-scoped; tenant-bound
  // admins (when that path lands) are silently dropped from the nav --
  // the matching backend endpoints also 403 them so URL deep-links don't
  // leak.
  const principal = usePrincipal();
  const isSuperuser = principal.superuser;
  const location = useLocation();
  // The sidebar's links follow the scope in the URL; on unscoped pages (settings, tenants,
  // servers) they keep pointing at the last scope the user was in.
  const parsed = parseScope(location.pathname);
  const urlScope = parsed && !scopeRedirect(parsed.scope, principal) ? parsed.scope : null;
  const [lastScope, setLastScope] = useState<Scope>(() => defaultScope(principal));
  useEffect(() => { if (urlScope) setLastScope(urlScope); }, [location.pathname]); // eslint-disable-line react-hooks/exhaustive-deps
  const scope = urlScope ?? lastScope;
  const items = visibleNav(NAV, {
    superuser: isSuperuser,
    admin: role?.toLowerCase() === 'admin',
    telemetry: telemetryEnabled,
    fleet: fleetEnabled,
    starlake: !!starlakeUrl,
    scope,
  });
  return (
    <SidebarLayout
      items={items}
      switcher={(collapsed, expand) => <TenantSwitcher scope={scope} collapsed={collapsed} onExpand={expand} />}
    >
      <Routes>
        <Route path="/t/:tenant" element={<ScopedLayout />}>{scopedRoutes()}</Route>
        <Route path="/all" element={<ScopedLayout all />}>{scopedRoutes()}</Route>
        <Route path="/tenants" element={<TenantList />} />
        {isSuperuser && <Route path="/servers" element={<Servers />} />}
        {isSuperuser && <Route path="/settings/config" element={<Config />} />}
        <Route path="/settings/profile" element={<Profile />} />
        <Route path="*" element={<LegacyRedirect />} />
      </Routes>
    </SidebarLayout>
  );
}

function SsoRedirect({ ssoLogin }: { ssoLogin: () => void }) {
  // Side effect in an effect (not the render body) so it does not double-fire
  // under React StrictMode.
  useEffect(() => { ssoLogin(); }, []);
  return <div className="loading">Redirecting to sign-in…</div>;
}

function AuthGate() {
  const location = useLocation();
  const { username, role, loading, identitySource, ssoLogin } = useAuth();
  // Password-reset landing page: reached straight from the emailed link and
  // must render immediately, before (and regardless of) any session/SSO
  // resolution - same public, pre-session contract as the
  // /api/auth/reset-password endpoint it calls. Short-circuit ahead of every
  // other gate below, including the loading spinner.
  if (location.pathname === '/reset-password') return <ResetPassword />;
  if (loading) return <div className="loading">Loading session…</div>;
  // Right after login() sets `username` there's a brief async gap before its
  // follow-up whoami() call resolves `role` - without this guard that window
  // would render ProfileShell (role still null) before flipping to Shell.
  if (username && role == null) return <div className="loading">Loading session…</div>;
  // Case-insensitive to match the server's equalsIgnoreCase("admin") gate --
  // a qodstate role of "Admin" is admin server-side and must get the full shell.
  if (username) {
    return role?.toLowerCase() === 'admin'
      ? <TenantsProvider><Shell /></TenantsProvider>
      : <ProfileShell />;
  }
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
