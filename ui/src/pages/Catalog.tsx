import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantDbResponse } from '../api/types';
import CatalogBrowser from '../components/CatalogBrowser';
import IcebergCatalogBrowser from '../components/IcebergCatalogBrowser';
import CatalogSnapshotsPanel from '../components/CatalogSnapshotsPanel';
import { useTenantScope } from '../nav/TenantScope';

export default function Catalog() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tenant = useTenantScope().scopedTenant ?? '';
  const [tenantDb, setTenantDbState] = useState<string>(searchParams.get('tenantDb') ?? '');
  const [tenantDbs, setTenantDbs]   = useState<TenantDbResponse[]>([]);
  const [error, setError]           = useState<string | null>(null);
  // Bumped when the browser panel commits a catalog write (undrop), so the
  // snapshots panel below refetches and shows the new recovery snapshot.
  const [catalogGen, setCatalogGen] = useState(0);

  const kind = tenantDbs.find(d => d.name === tenantDb)?.kind ?? 'ducklake';

  function pickTenantDb(td: string) {
    setTenantDbState(td);
    setSearchParams({ tenantDb: td });
  }

  useEffect(() => {
    if (!tenant) return;
    setError(null);
    api.listTenantDbs(tenant)
      .then(r => {
        setTenantDbs(r.tenantDbs);
        setTenantDbState(curr =>
          r.tenantDbs.some(d => d.name === curr) ? curr : r.tenantDbs[0]?.name ?? '');
      })
      .catch(e => setError(String(e)));
  }, [tenant]);

  return (
    <div>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2>Catalog</h2>
        <div style={{ display: 'flex', gap: 12 }}>
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
        {tenant && tenantDb && (
          <>
            <CatalogBrowser
              tenant={tenant}
              tenantDb={tenantDb}
              kind={kind}
              onCatalogMutated={() => setCatalogGen(g => g + 1)}
            />
            <IcebergCatalogBrowser tenant={tenant} tenantDb={tenantDb} />
            {kind === 'ducklake' && (
              <CatalogSnapshotsPanel tenant={tenant} tenantDb={tenantDb} refreshToken={catalogGen} />
            )}
          </>
        )}
      </div>
    </div>
  );
}
