import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { PoolResponse, TenantResponse } from '../api/types';
import AclSection from '../components/AclSection';
import DatabaseSection from '../components/DatabaseSection';
import IdentitySection from '../components/IdentitySection';
import PoolSection from '../components/PoolSection';
import Breadcrumb from '../components/Breadcrumb';
import Tabs from '../components/Tabs';

export default function TenantDetail() {
  const { tenant } = useParams<{ tenant: string }>();
  const nav = useNavigate();
  const [data, setData]   = useState<TenantResponse | null>(null);
  const [pools, setPools] = useState<PoolResponse[]>([]);
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
    api.listPools()
      .then(r => setPools(r.pools.filter(p => p.tenant === tenant)))
      .catch(e => setError(String(e)));
  }

  useEffect(() => { refresh(); /* eslint-disable-next-line */ }, [tenant]);

  async function handleDelete() {
    if (!tenant || !data) return;
    if (pools.length > 0) {
      alert(`Cannot delete - tenant has ${pools.length} active pool(s). Stop them first.`);
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
          <button className="danger" onClick={handleDelete}>Delete tenant</button>
        </div>
      </div>

      <div className="row" style={{ gap: 12, marginBottom: '1rem', flexWrap: 'wrap' }}>
        <Link to={`/nodes?tenant=${tEnc}`}>Live nodes for this tenant</Link>
        <span style={{ color: '#bbb' }}>·</span>
        <Link to={`/nodes?tenant=${tEnc}#statements`}>Recent statements</Link>
      </div>

      <Tabs
        tabs={[
          { id: 'databases',  label: 'Databases',  body: <DatabaseSection tenant={data.name} /> },
          { id: 'pools',      label: 'Pools',      body: <PoolSection tenant={data.name} /> },
          { id: 'identities', label: 'Identities', body: <IdentitySection tenantId={data.name} /> },
          { id: 'acl',        label: 'ACL',        body: <AclSection tenant={data.name} /> },
        ]}
      />
    </>
  );
}
