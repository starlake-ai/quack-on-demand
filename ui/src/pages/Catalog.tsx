import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type {
  TenantResponse,
  TenantDbResponse,
} from '../api/types';
import CatalogBrowser from '../components/CatalogBrowser';

export default function Catalog() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [tenants, setTenants]       = useState<TenantResponse[]>([]);
  const [tenant, setTenantState]    = useState<string>('');
  const [tenantDbs, setTenantDbs]   = useState<TenantDbResponse[]>([]);
  const [tenantDb, setTenantDbState] = useState<string>('');
  const [error, setError]           = useState<string | null>(null);

  function pickTenant(t: string) {
    setTenantState(t);
    setTenantDbState('');
    setSearchParams({ tenant: t });
  }
  function pickTenantDb(td: string) {
    setTenantDbState(td);
    setSearchParams({ tenant, tenantDb: td });
  }

  useEffect(() => {
    api.listTenants()
      .then(r => {
        setTenants(r.tenants);
        const fromQuery = searchParams.get('tenant');
        const initial = fromQuery ?? r.tenants[0]?.name ?? '';
        if (initial) setTenantState(initial);
        const queryDb     = searchParams.get('tenantDb');
        if (queryDb) setTenantDbState(queryDb);
      })
      .catch(e => setError(String(e)));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!tenant) return;
    setError(null);
    api.listTenantDbs(tenant)
      .then(r => {
        setTenantDbs(r.tenantDbs);
        if (!tenantDb && r.tenantDbs.length > 0) {
          setTenantDbState(r.tenantDbs[0].name);
        }
      })
      .catch(e => setError(String(e)));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);

  return (
    <div>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2>Catalog</h2>
        <div style={{ display: 'flex', gap: 12 }}>
          <label>
            Tenant&nbsp;
            <select value={tenant} onChange={e => pickTenant(e.target.value)}>
              {tenants.map(t => <option key={t.name} value={t.name}>{t.name}</option>)}
            </select>
          </label>
          <label>
            Database&nbsp;
            <select value={tenantDb} onChange={e => pickTenantDb(e.target.value)} disabled={tenantDbs.length === 0}>
              {tenantDbs.length === 0 && <option value="">(no databases)</option>}
              {tenantDbs.map(d => <option key={d.name} value={d.name}>{d.name}</option>)}
            </select>
          </label>
        </div>
      </header>

      {error && <p style={{ color: 'red' }}>Error: {error}</p>}

      <div style={{ marginTop: '12px' }}>
        {tenant && tenantDb && <CatalogBrowser tenant={tenant} tenantDb={tenantDb} />}
      </div>
    </div>
  );
}
