import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantResponse } from '../api/types';

export default function TenantList() {
  const [tenants, setTenants] = useState<TenantResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);

  useEffect(() => {
    api.listTenants().then(r => setTenants(r.tenants)).catch(e => setError(String(e)));
  }, []);

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (tenants.length === 0) return (
    <p>No tenants yet. <Link to="/create-tenant">Create one</Link>.</p>
  );

  return (
    <div>
      <header style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h2>Tenants</h2>
        <Link to="/create-tenant">+ New tenant</Link>
      </header>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th align="left">Name</th>
            <th align="left">Pools</th>
            <th align="left">Data path</th>
            <th align="left">Catalog DB</th>
            <th align="left">Schema</th>
            <th align="left">Postgres</th>
            <th align="left">Overrides</th>
          </tr>
        </thead>
        <tbody>
          {tenants.map(t => {
            const eff = t.effectiveMetastore;
            const pg = eff.pgHost
              ? `${eff.pgUser || '?'}@${eff.pgHost}:${eff.pgPort || '5432'}`
              : '';
            const overrideCount = Object.keys(t.metastore).filter(k => k !== 'pgPassword').length;
            return (
              <tr key={t.name} style={{ borderTop: '1px solid #eee' }}>
                <td><Link to={`/tenant/${t.name}`}>{t.name}</Link></td>
                <td>{t.pools.length}</td>
                <td>{eff.dataPath   ? <code>{eff.dataPath}</code>   : <em style={{ color: '#888' }}>-</em>}</td>
                <td>{eff.dbName     ? <code>{eff.dbName}</code>     : <em style={{ color: '#888' }}>-</em>}</td>
                <td>{eff.schemaName ? <code>{eff.schemaName}</code> : <em style={{ color: '#888' }}>-</em>}</td>
                <td>{pg             ? <code>{pg}</code>             : <em style={{ color: '#888' }}>-</em>}</td>
                <td>
                  {overrideCount === 0
                    ? <em style={{ color: '#888' }}>inherits all defaults</em>
                    : `${overrideCount} field${overrideCount === 1 ? '' : 's'} overridden`}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}