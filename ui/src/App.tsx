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
  const { username, role, logout, authEnabled } = useAuth();
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
        {role === 'admin' && (
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
          <Route path="/config"                                    element={<Config />} />
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
