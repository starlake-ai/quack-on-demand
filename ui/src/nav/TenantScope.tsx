import { createContext, useContext } from 'react';
import { Link, Navigate, Outlet, useLocation, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import {
  ALL, parseScope, scopePrefix, scopeRedirect, sectionNeedsTenant, sectionRoot, type Principal, type Scope,
} from './scope';
import { useTenants } from './TenantsContext';

interface TenantScopeValue {
  scope: Scope;
  /** The scope's tenant slug, or null under All tenants. */
  scopedTenant: string | null;
}

const Ctx = createContext<TenantScopeValue | null>(null);

export function useTenantScope(): TenantScopeValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useTenantScope outside a scoped route');
  return v;
}

/** `!authEnabled` is the no-auth dev mode: the synthetic anonymous user is a superuser. */
export function usePrincipal(): Principal {
  const { authEnabled, superuser, tenant, manageableTenants } = useAuth();
  // An OIDC admin may hold admin grants on several tenants (whoami's manageableTenants) and may
  // switch among exactly those; a DB-login tenant admin has just their own.
  const allowed = manageableTenants.length > 0 ? [...manageableTenants] : tenant ? [tenant] : [];
  if (tenant && !allowed.includes(tenant)) allowed.unshift(tenant);
  // A tenant sign-in, through the database or the tenant's own SSO, is never superuser, so the
  // case whoami's flag used to cover (a superuser signed in through a tenant URL) is gone.
  // `tenant === null` is a looser test than the flag: a multi-tenant admin session from the
  // system login has no tenant either.
  return {
    superuser: !authEnabled || superuser || tenant === null,
    ownTenant: tenant,
    allowedTenants: allowed,
  };
}

/** Layout route for `/t/:tenant/*` and `/all/*`: resolves the scope, keeps tenant admins inside the
  * tenants they may manage, and stops tenant-only sections under All tenants with a pick-a-tenant card. */
export function ScopedLayout({ all }: { all?: boolean }) {
  const params = useParams<{ tenant: string }>();
  const location = useLocation();
  const principal = usePrincipal();
  const { tenants } = useTenants();
  const scope: Scope = all ? ALL : { kind: 'tenant', tenant: params.tenant ?? '' };
  const section = parseScope(location.pathname)?.section ?? '';

  const redirect = scopeRedirect(scope, principal);
  if (redirect) {
    return <Navigate replace to={`${scopePrefix(redirect)}/${sectionRoot(section)}${location.search}`} />;
  }
  if (scope.kind === 'tenant' && tenants && !tenants.some(t => t.id === scope.tenant)) {
    return (
      <div className="card">
        <div className="card-title">Tenant not found</div>
        <p className="subtle">
          No tenant <code>{scope.tenant}</code>. <Link to="/tenants">Manage tenants</Link>
        </p>
      </div>
    );
  }
  if (scope.kind === 'all' && sectionNeedsTenant(section)) {
    return (
      <div className="card">
        <div className="card-title">Pick a tenant</div>
        <p className="subtle">This page shows one tenant at a time. Choose one in the tenant switcher.</p>
      </div>
    );
  }
  return (
    <Ctx.Provider value={{ scope, scopedTenant: scope.kind === 'tenant' ? scope.tenant : null }}>
      {/* Keyed on the scope: switching tenant must not carry page state (pool filter, selected
        * database, paging cursors) from the previous tenant, so the page remounts per scope. In
        * React 18, state updates from in-flight requests of the unmounted page are dropped, which
        * closes the stale-response race too. */}
      <Outlet key={scopePrefix(scope)} />
    </Ctx.Provider>
  );
}
