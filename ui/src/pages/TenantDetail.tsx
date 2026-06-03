import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantResponse } from '../api/types';
import AclSection from '../components/AclSection';
import IdentitySection from '../components/IdentitySection';
import Breadcrumb from '../components/Breadcrumb';

export default function TenantDetail() {
  const { tenant } = useParams<{ tenant: string }>();
  const nav = useNavigate();
  const [data, setData]   = useState<TenantResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!tenant) return;
    api.listTenants()
      .then(r => {
        const t = r.tenants.find(x => x.name === tenant);
        if (!t) setError(`tenant '${tenant}' not found`);
        else setData(t);
      })
      .catch(e => setError(String(e)));
  }, [tenant]);

  async function handleDelete() {
    if (!tenant || !data) return;
    if (data.pools.length > 0) {
      alert(`Cannot delete - tenant has ${data.pools.length} active pool(s). Stop them first.`);
      return;
    }
    if (!confirm(`Delete tenant '${tenant}'?`)) return;
    try {
      await api.deleteTenant({ name: tenant });
      nav('/tenants');
    } catch (e) { setError(String(e)); }
  }

  if (error) return <div className="login-err">Error: {error}</div>;
  if (!data)  return <div className="loading">Loading…</div>;

  const eff = data.effectiveMetastore;
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
        <h1>{data.name}</h1>
        <div className="row">
          <Link to={`/tenant/${data.name}/create-pool`}><button>+ New pool</button></Link>
          <button className="danger" onClick={handleDelete}>Delete tenant</button>
        </div>
      </div>

      <div className="row" style={{ gap: 12, marginBottom: '1rem', flexWrap: 'wrap' }}>
        <Link to={`/catalog?tenant=${tEnc}`}>Browse catalog</Link>
        <span style={{ color: '#bbb' }}>·</span>
        <Link to={`/nodes?tenant=${tEnc}`}>Live nodes for this tenant</Link>
        <span style={{ color: '#bbb' }}>·</span>
        <Link to={`/nodes?tenant=${tEnc}#statements`}>Recent statements</Link>
      </div>

      <div className="card">
        <div className="card-title">Storage (effective)</div>
        <p className="subtle">
          Global defaults from <code>application.conf</code> overlaid with this tenant's overrides.
          New pools inherit these values unless they override at create time.
        </p>
        {Object.keys(eff).length === 0 ? (
          <div className="empty">(no effective metastore configured)</div>
        ) : (
          <table>
            <tbody>
              {eff.dataPath   && <tr><th>Data path</th><td><code>{eff.dataPath}</code></td></tr>}
              {eff.dbName     && <tr><th>Catalog DB</th><td><code>{eff.dbName}</code></td></tr>}
              {eff.schemaName && <tr><th>Schema</th><td><code>{eff.schemaName}</code></td></tr>}
              {eff.pgHost     && <tr><th>Postgres</th><td><code>{eff.pgUser || '?'}@{eff.pgHost}:{eff.pgPort || '5432'}</code></td></tr>}
              {Object.entries(eff)
                .filter(([k]) => !['dataPath', 'dbName', 'schemaName', 'pgHost', 'pgPort', 'pgUser', 'pgPassword'].includes(k))
                .map(([k, v]) => <tr key={k}><th>{k}</th><td><code>{v}</code></td></tr>)}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <div className="card-title">Tenant overrides</div>
        {Object.keys(data.metastore).length === 0 ? (
          <div className="empty">(none - this tenant inherits all storage settings from global defaults)</div>
        ) : (
          <table>
            <tbody>
              {Object.entries(data.metastore)
                .filter(([k]) => k !== 'pgPassword')
                .map(([k, v]) => <tr key={k}><th>{k}</th><td><code>{v}</code></td></tr>)}
              {data.metastore.pgPassword && <tr><th>pgPassword</th><td><code>••••••••</code></td></tr>}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <div className="card-title">Pools</div>
        {data.pools.length === 0 ? (
          <div className="empty">No pools yet. <Link to={`/tenant/${data.name}/create-pool`}>Create one</Link>.</div>
        ) : (
          <ul>
            {data.pools.map(p => (
              <li key={p}><Link to={`/pool/${data.name}/${p}`}>{p}</Link></li>
            ))}
          </ul>
        )}
      </div>

      <IdentitySection tenantId={data.name} />
      <AclSection tenant={data.name} />
    </>
  );
}
