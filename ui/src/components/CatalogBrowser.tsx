import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type {
  CatalogSchemaEntry,
  CatalogTableEntry,
} from '../api/types';
import TableSchemaCard from './TableSchemaCard';

/** Schema + table browser for a single (tenant, tenantDb). Pure body
  * panel: no tenant / database selectors, no breadcrumb -- the caller
  * supplies the context. Used by both the standalone `/catalog` page
  * and the inline view inside the Databases tab. */
export default function CatalogBrowser({
  tenant,
  tenantDb,
}: {
  tenant:   string;
  tenantDb: string;
}) {
  const [schemas, setSchemas] = useState<CatalogSchemaEntry[]>([]);
  const [schema, setSchema]   = useState<string>('');
  const [tables, setTables]   = useState<CatalogTableEntry[]>([]);
  const [filter, setFilter]   = useState<string>('');
  const [error, setError]     = useState<string | null>(null);
  // Name of the currently-expanded table whose schema card is shown
  // inline beneath its row. Only one card open at a time. Reset when
  // the parent schema changes.
  const [expandedTable, setExpandedTable] = useState<string | null>(null);

  useEffect(() => {
    if (!tenant || !tenantDb) { setSchemas([]); return; }
    setError(null);
    api.listCatalogSchemas(tenant, tenantDb)
      .then(setSchemas)
      .catch(e => setError(String(e)));
  }, [tenant, tenantDb]);

  useEffect(() => {
    if (!tenant || !tenantDb || !schema) { setTables([]); return; }
    setError(null);
    // Reset the filter when the user picks a different schema - a stale
    // string typed against tpch1 would silently hide every row in main.
    setFilter('');
    setExpandedTable(null);
    api.listCatalogTables(tenant, tenantDb, schema)
      .then(setTables)
      .catch(e => setError(String(e)));
  }, [tenant, tenantDb, schema]);

  const filteredTables = filter
    ? tables.filter(t => t.name.toLowerCase().includes(filter.toLowerCase()))
    : tables;

  return (
    <>
      {error && <p style={{ color: 'red' }}>Error: {error}</p>}
      <div style={{ display: 'grid', gridTemplateColumns: '220px 1fr', gap: '24px' }}>
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
                      onClick={() => setSchema(s.name)}
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
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 16, marginBottom: 8 }}>
            <h3 style={{ margin: 0 }}>
              Tables in {schema ? <code>{schema}</code> : <em style={{ color: '#888' }}>pick a schema</em>}
            </h3>
            {schema && tables.length > 0 && (
              <input
                type="search"
                value={filter}
                onChange={e => setFilter(e.target.value)}
                placeholder="filter by name…"
                style={{
                  padding: '4px 8px',
                  border: '1px solid #ccc',
                  borderRadius: 4,
                  fontSize: '0.9rem',
                  width: 220,
                }}
              />
            )}
          </div>
          {schema && (
            tables.length === 0
              ? <em style={{ color: '#888' }}>no tables</em>
              : filteredTables.length === 0
              ? <em style={{ color: '#888' }}>no tables matching <code>{filter}</code></em>
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
                    {filteredTables.flatMap(t => {
                      const isOpen = expandedTable === t.name;
                      const row = (
                        <tr key={t.name} style={{ borderTop: '1px solid #eee' }}>
                          <td>
                            <button
                              type="button"
                              className="user-name-toggle"
                              aria-expanded={isOpen}
                              title={isOpen ? 'Hide schema' : 'Show schema'}
                              onClick={() => setExpandedTable(isOpen ? null : t.name)}
                            >
                              <span className="caret">{isOpen ? '▾' : '▸'}</span>
                              <code>{t.name}</code>
                            </button>
                          </td>
                          <td align="right">
                            {t.rowCount < 0 ? <em style={{ color: '#888' }}>--</em> : t.rowCount.toLocaleString()}
                          </td>
                          <td align="right">{t.dataFileCount}</td>
                          <td>
                            {t.folder
                              ? <code style={{ fontSize: '0.85em', color: '#444' }}>{t.folder}</code>
                              : <em style={{ color: '#888' }}>--</em>}
                          </td>
                        </tr>
                      );
                      if (!isOpen) return [row];
                      return [row, (
                        <tr key={t.name + '-schema'} className="expanded-row">
                          <td colSpan={4}>
                            <TableSchemaCard
                              tenant={tenant}
                              tenantDb={tenantDb}
                              schema={schema}
                              table={t.name}
                            />
                          </td>
                        </tr>
                      )];
                    })}
                  </tbody>
                </table>
              )
          )}
        </main>
      </div>
    </>
  );
}
