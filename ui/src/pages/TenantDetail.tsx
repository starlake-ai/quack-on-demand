import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantDbResponse, TenantResponse } from '../api/types';
import AuthProviderSection from '../components/AuthProviderSection';
import DatabaseSection from '../components/DatabaseSection';
import MaintenancePanel from '../components/MaintenancePanel';
import PoolSection from '../components/PoolSection';
import Breadcrumb from '../components/Breadcrumb';
import Tabs from '../components/Tabs';

/** Maintenance tab body: pick one of the tenant's ducklake databases and
  * render the per-database MaintenancePanel for it. Managed maintenance
  * only applies to ducklake catalogs, so other kinds are not listed. */
function MaintenanceSection({ tenant }: { tenant: string }) {
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
        const lakes = r.tenantDbs.filter(d => (d.kind ?? 'ducklake') === 'ducklake');
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
        <div className="card-title">Maintenance</div>
        <p className="subtle">
          No ducklake databases in this tenant. Managed maintenance applies only
          to ducklake catalogs.
        </p>
      </div>
    );
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Maintenance</div>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="subtle">Database</span>
          <select value={selected ?? ''} onChange={ev => setSelected(ev.target.value)}>
            {dbs.map(d => <option key={d.id} value={d.name}>{d.name}</option>)}
          </select>
        </label>
      </div>
      {selected && <MaintenancePanel tenant={tenant} tenantDb={selected} />}
    </div>
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
          { id: 'maintenance',   label: 'Maintenance',   body: <MaintenanceSection tenant={data.name} /> },
          { id: 'pools',         label: 'Pools',         body: <PoolSection tenant={data.name} /> },
          { id: 'auth-provider', label: 'Auth provider', body: <AuthProviderSection tenantName={data.name} /> },
        ]}
      />
    </>
  );
}
