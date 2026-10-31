import AuthProviderSection from '../components/AuthProviderSection';
import { AccessControlSection } from '../components/AccessControlSection';
import { AuthorizedLocationsSection } from '../components/AuthorizedLocationsSection';
import BranchPanel from '../components/BranchPanel';
import DatabaseSection from '../components/DatabaseSection';
import DuckLakeDbSection from '../components/DuckLakeDbSection';
import MaintenancePanel from '../components/MaintenancePanel';
import PoolSection from '../components/PoolSection';
import { useTenantScope } from '../nav/TenantScope';
import { useTenants } from '../nav/TenantsContext';

/** Tenant section pages. ScopedLayout guarantees a concrete tenant before any of these render. */
function useScopedTenant(): string {
  return useTenantScope().scopedTenant ?? '';
}

function TenantHeader({ title }: { title: string }) {
  const tenant = useScopedTenant();
  const row = useTenants().tenants?.find(t => t.id === tenant);
  return (
    <div style={{ marginBottom: '1rem' }}>
      <h1 style={{ marginBottom: '0.25rem' }}>{title}</h1>
      <p className="subtle" style={{ margin: 0 }}>
        {row?.displayName ?? tenant} <code>{tenant}</code>
      </p>
    </div>
  );
}

export function DatabasesPage() {
  const tenant = useScopedTenant();
  return <><TenantHeader title="Databases" /><DatabaseSection tenant={tenant} /></>;
}

export function PoolsPage() {
  const tenant = useScopedTenant();
  return <><TenantHeader title="Pools" /><PoolSection tenant={tenant} /></>;
}

export function MaintenancePage() {
  const tenant = useScopedTenant();
  return (
    <>
      <TenantHeader title="Maintenance" />
      <DuckLakeDbSection
        tenant={tenant}
        title="Maintenance"
        emptyHint="Managed maintenance applies only to ducklake catalogs."
        panel={db => <MaintenancePanel tenant={tenant} tenantDb={db} />}
      />
    </>
  );
}

/** Branches (Epic 1): writable zero-copy clones an agent works on, reviewed and merged here. */
export function BranchesPage() {
  const tenant = useScopedTenant();
  return (
    <>
      <TenantHeader title="Branches" />
      <DuckLakeDbSection
        tenant={tenant}
        title="Branches"
        emptyHint="Branching applies only to ducklake catalogs."
        panel={db => <BranchPanel tenant={tenant} tenantDb={db} />}
      />
    </>
  );
}

export function AuthProviderPage() {
  const tenant = useScopedTenant();
  // Keyed on the tenant: the editor holds a typed client secret, which must never survive a
  // change of tenant and be saved into another one.
  return (
    <>
      <TenantHeader title="Auth Provider" />
      <AuthProviderSection key={tenant} tenantName={tenant} />
    </>
  );
}

export function AccessControlPage() {
  const tenant = useScopedTenant();
  const { tenants, error, reload } = useTenants();
  const row = tenants?.find(t => t.id === tenant);
  return (
    <>
      <TenantHeader title="Access Control" />
      {row
        ? <AccessControlSection tenant={row} onSaved={() => { void reload(); }} />
        : error
          ? <div className="login-err">Error: {error}</div>
          : <div className="loading">Loading...</div>}
      {/* Keyed on the tenant: a half-typed folder must never survive a change of tenant. */}
      <AuthorizedLocationsSection key={tenant} tenant={tenant} />
    </>
  );
}
