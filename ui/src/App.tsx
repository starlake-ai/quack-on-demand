import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Login from './pages/Login';
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

function AuthGate() {
  const { username, loading } = useAuth();
  if (loading) return <div className="loading">Loading session…</div>;
  return username ? <Shell /> : <Login />;
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
