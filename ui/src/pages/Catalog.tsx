import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type {
  TenantResponse,
  CatalogSchemaEntry,
  CatalogTableEntry,
} from '../api/types';

export default function Catalog() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [tenants, setTenants] = useState<TenantResponse[]>([]);
  const [tenant, setTenantState]   = useState<string>('');
  const [schemas, setSchemas]      = useState<CatalogSchemaEntry[]>([]);
  const [schema, setSchemaState]   = useState<string>('');
  const [tables, setTables]        = useState<CatalogTableEntry[]>([]);
  const [error, setError]          = useState<string | null>(null);

  function pickTenant(t: string) {
    setTenantState(t);
    setSchemaState('');
    setSearchParams({ tenant: t });
  }
  function pickSchema(s: string) {
    setSchemaState(s);
    setSearchParams({ tenant, schema: s });
  }

  useEffect(() => {
    api.listTenants()
      .then(r => {
        setTenants(r.tenants);
        const fromQuery = searchParams.get('tenant');
        const initial = fromQuery ?? r.tenants[0]?.name ?? '';
        if (initial) setTenantState(initial);
        const querySchema = searchParams.get('schema');
        if (querySchema) setSchemaState(querySchema);
      })
      .catch(e => setError(String(e)));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!tenant) return;
    setError(null);
    api.listCatalogSchemas(tenant)
      .then(setSchemas)
      .catch(e => setError(String(e)));
  }, [tenant]);

  useEffect(() => {
    if (!tenant || !schema) { setTables([]); return; }
    setError(null);
    api.listCatalogTables(tenant, schema)
      .then(setTables)
      .catch(e => setError(String(e)));
  }, [tenant, schema]);

  return (
    <div>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2>Catalog</h2>
        <label>
          Tenant&nbsp;
          <select value={tenant} onChange={e => pickTenant(e.target.value)}>
            {tenants.map(t => <option key={t.name} value={t.name}>{t.name}</option>)}
          </select>
        </label>
      </header>

      {error && <p style={{ color: 'red' }}>Error: {error}</p>}

      <div style={{ display: 'grid', gridTemplateColumns: '220px 1fr', gap: '24px', marginTop: '12px' }}>
        <aside>
          <h3 style={{ marginTop: 0 }}>Schemas</h3>
          {schemas.length === 0
            ? <em style={{ color: '#888' }}>no schemas</em>
            : (
              <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
                {schemas.map(s => {
                  const active = s.name === schema;
                  return (
                    <li
                      key={s.name}
                      onClick={() => pickSchema(s.name)}
                      style={{
                        cursor: 'pointer',
                        padding: '4px 8px',
                        borderRadius: 4,
                        background: active ? '#e6f0ff' : 'transparent',
                        fontWeight: active ? 600 : 400,
                      }}
                    >
                      {s.name}
                      <span style={{ color: '#888', marginLeft: 6 }}>({s.tableCount})</span>
                    </li>
                  );
                })}
              </ul>
            )}
        </aside>

        <main>
          <h3 style={{ marginTop: 0 }}>
            Tables in {schema ? <code>{schema}</code> : <em style={{ color: '#888' }}>pick a schema</em>}
          </h3>
          {schema && (
            tables.length === 0
              ? <em style={{ color: '#888' }}>no tables</em>
              : (
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>
                      <th align="left">Name</th>
                      <th align="right">Rows</th>
                      <th align="right">Data files</th>
                      <th align="left">Folder</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tables.map(t => (
                      <tr key={t.name} style={{ borderTop: '1px solid #eee' }}>
                        <td>
                          <Link to={`/catalog/${encodeURIComponent(tenant)}/${encodeURIComponent(schema)}/${encodeURIComponent(t.name)}`}>
                            {t.name}
                          </Link>
                        </td>
                        <td align="right">
                          {t.rowCount < 0 ? <em style={{ color: '#888' }}>—</em> : t.rowCount.toLocaleString()}
                        </td>
                        <td align="right">{t.dataFileCount}</td>
                        <td>
                          {t.folder
                            ? <code style={{ fontSize: '0.85em', color: '#444' }}>{t.folder}</code>
                            : <em style={{ color: '#888' }}>—</em>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )
          )}
        </main>
      </div>
    </div>
  );
}