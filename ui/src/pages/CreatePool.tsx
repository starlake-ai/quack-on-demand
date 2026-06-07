import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantDbResponse } from '../api/types';
import Breadcrumb from '../components/Breadcrumb';

export default function CreatePool() {
  const nav = useNavigate();
  const { tenant } = useParams<{ tenant: string }>();
  const [tenantDbs, setTenantDbs] = useState<TenantDbResponse[]>([]);
  const [tenantDb, setTenantDb]   = useState('');
  const [pool, setPool]   = useState('');
  const [ro, setRo]       = useState(0);
  const [wo, setWo]       = useState(0);
  const [dual, setDual]   = useState(1);
  const [maxConcurrent, setMaxConcurrent] = useState(0); // 0 = unlimited
  const [err, setErr]     = useState<string | null>(null);

  useEffect(() => {
    if (!tenant) return;
    api.listTenantDbs(tenant)
      .then(r => {
        setTenantDbs(r.tenantDbs);
        if (r.tenantDbs.length > 0) setTenantDb(r.tenantDbs[0].name);
      })
      .catch(e => setErr(String(e)));
  }, [tenant]);

  const size = ro + wo + dual;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    if (!tenant)   { setErr('missing tenant in URL'); return; }
    if (!tenantDb) { setErr('pick a tenant database'); return; }
    try {
      await api.createPool({
        tenant, tenantDb, pool, size,
        roleDistribution: { writeonly: wo, readonly: ro, dual },
        maxConcurrentPerNode: maxConcurrent
      });
      nav(`/pool/${encodeURIComponent(tenant)}/${encodeURIComponent(tenantDb)}/${encodeURIComponent(pool)}`);
    } catch (e) { setErr(String(e)); }
  }

  return (
    <form onSubmit={submit}>
      <Breadcrumb
        items={[
          { label: 'Tenants', to: '/tenants' },
          { label: tenant!,   to: `/tenant/${encodeURIComponent(tenant!)}` },
          { label: 'New pool' },
        ]}
      />
      <h2>Create pool in {tenant}</h2>
      {err && <p style={{ color: 'red' }}>{err}</p>}

      {tenantDbs.length === 0 ? (
        <p style={{ color: '#a55' }}>
          This tenant has no databases yet. Add a database on the tenant page first.
        </p>
      ) : (
        <>
          <label>
            Database&nbsp;
            <select value={tenantDb} onChange={e => setTenantDb(e.target.value)}>
              {tenantDbs.map(d => <option key={d.name} value={d.name}>{d.name}</option>)}
            </select>
          </label><br/>
          <p style={{ color: '#888', marginTop: 0 }}>
            The pool inherits the metastore, data path, and object-store config
            from the database. Pools no longer override storage.
          </p>
        </>
      )}

      <label>Pool <input value={pool} onChange={e => setPool(e.target.value)} required /></label><br/>

      <fieldset>
        <legend>Role distribution (size = {size})</legend>
        <label>WriteOnly <input type="number" min={0} value={wo}   onChange={e => setWo(+e.target.value)} /></label>
        <label>ReadOnly  <input type="number" min={0} value={ro}   onChange={e => setRo(+e.target.value)} /></label>
        <label>Dual      <input type="number" min={0} value={dual} onChange={e => setDual(+e.target.value)} /></label>
      </fieldset>

      <fieldset>
        <legend>Concurrency</legend>
        <label>
          Max concurrent per node{' '}
          <input
            type="number"
            min={0}
            value={maxConcurrent}
            onChange={e => setMaxConcurrent(+e.target.value)}
          />
        </label>
        <span style={{ marginLeft: 8, color: '#888' }}>
          {maxConcurrent === 0 ? '(0 = unlimited)' : ''}
        </span>
      </fieldset>

      <div className="row" style={{ gap: 8, marginTop: '0.75rem' }}>
        <button type="submit" disabled={size === 0 || !tenant || !tenantDb || !pool}>
          Create
        </button>
        <button
          type="button"
          className="cancel-button"
          onClick={() => nav(`/tenant/${encodeURIComponent(tenant!)}`)}
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
