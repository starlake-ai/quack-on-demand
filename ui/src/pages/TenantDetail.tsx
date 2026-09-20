import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantDbResponse, TenantResponse } from '../api/types';
import AuthProviderSection from '../components/AuthProviderSection';
import DatabaseSection from '../components/DatabaseSection';
import MaintenancePanel from '../components/MaintenancePanel';
import BranchPanel from '../components/BranchPanel';
import PoolSection from '../components/PoolSection';
import Breadcrumb from '../components/Breadcrumb';
import Tabs from '../components/Tabs';

/** Shared tab body for the per-database panels (maintenance, branches): pick one of the
  * tenant's ducklake databases and render `panel` for it. Both features apply only to ducklake
  * catalogs, so other kinds are not listed; branch catalogs themselves are hidden too, since they
  * are addressed through their parent. */
function DuckLakeDbSection({ tenant, title, emptyHint, panel }: {
  tenant: string;
  title: string;
  emptyHint: string;
  panel: (tenantDb: string) => ReactNode;
}) {
  const [dbs, setDbs] = useState<TenantDbResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setDbs(null);
    setError(null);
    setSelected(null);
    api.listTenantDbs(tenant)
      .then(r => {
        if (cancelled) return;
        const lakes = r.tenantDbs.filter(d =>
          (d.kind ?? 'ducklake') === 'ducklake' && !/__br_[0-9a-f]{8}$/.test(d.name));
        setDbs(lakes);
        setSelected(lakes[0]?.name ?? null);
      })
      .catch(e => { if (!cancelled) setError(String(e)); });
    return () => { cancelled = true; };
  }, [tenant]);

  if (error) return <div className="login-err">Error: {error}</div>;
  if (!dbs) return <div className="loading">Loading databases...</div>;
  if (dbs.length === 0) {
    return (
      <div className="card">
        <div className="card-title">{title}</div>
        <p className="subtle">No ducklake databases in this tenant. {emptyHint}</p>
      </div>
    );
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>{title}</div>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="subtle">Database</span>
          <select value={selected ?? ''} onChange={ev => setSelected(ev.target.value)}>
            {dbs.map(d => <option key={d.id} value={d.name}>{d.name}</option>)}
          </select>
        </label>
      </div>
      {selected && panel(selected)}
    </div>
  );
}

function MaintenanceSection({ tenant }: { tenant: string }) {
  return (
    <DuckLakeDbSection
      tenant={tenant}
      title="Maintenance"
      emptyHint="Managed maintenance applies only to ducklake catalogs."
      panel={db => <MaintenancePanel tenant={tenant} tenantDb={db} />}
    />
  );
}

/** Branches tab (Epic 1): writable zero-copy clones an agent works on, reviewed and merged here. */
function BranchesSection({ tenant }: { tenant: string }) {
  return (
    <DuckLakeDbSection
      tenant={tenant}
      title="Branches"
      emptyHint="Branching applies only to ducklake catalogs."
      panel={db => <BranchPanel tenant={tenant} tenantDb={db} />}
    />
  );
}

export default function TenantDetail() {
  const { tenant } = useParams<{ tenant: string }>();
  const [data, setData]   = useState<TenantResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  function refresh() {
    if (!tenant) return;
    api.listTenants()
      .then(r => {
        const t = r.tenants.find(x => x.name === tenant);
        if (!t) setError(`tenant '${tenant}' not found`);
        else setData(t);
      })
      .catch(e => setError(String(e)));
  }

  useEffect(() => { refresh(); /* eslint-disable-next-line */ }, [tenant]);

  if (error) return <div className="login-err">Error: {error}</div>;
  if (!data)  return <div className="loading">Loading…</div>;

  const tEnc = encodeURIComponent(data.name);

  return (
    <>
      <Breadcrumb
        items={[
          { label: 'Tenants', to: '/tenants' },
          { label: data.name },
        ]}
      />
      <div className="row" style={{ justifyContent: 'space-between', marginBottom: '1rem' }}>
        <div>
          <h1 style={{ marginBottom: '0.25rem' }}>{data.name}</h1>
          <p className="subtle" style={{ margin: 0 }}>Tenant ID: <code>{data.id}</code></p>
        </div>
        <Link to="/tenants">
          <button type="button" className="link-button">← Back to tenants</button>
        </Link>
      </div>

      <div className="row" style={{ gap: 12, marginBottom: '1rem', flexWrap: 'wrap' }}>
        <Link to={`/nodes?tenant=${tEnc}`}>Live nodes for this tenant</Link>
        <span style={{ color: '#bbb' }}>·</span>
        <Link to={`/nodes?tenant=${tEnc}#statements`}>Recent statements</Link>
      </div>

      <Tabs
        tabs={[
          { id: 'databases',     label: 'Databases',     body: <DatabaseSection tenant={data.name} /> },
          { id: 'pools',         label: 'Pools',         body: <PoolSection tenant={data.name} /> },
          { id: 'maintenance',   label: 'Maintenance',   body: <MaintenanceSection tenant={data.name} /> },
          { id: 'branches',      label: 'Branches',      body: <BranchesSection tenant={data.name} /> },
          { id: 'auth-provider', label: 'Auth provider', body: <AuthProviderSection tenantName={data.name} /> },
        ]}
      />
    </>
  );
}
