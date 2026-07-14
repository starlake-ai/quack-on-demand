import { useEffect, useRef, useState } from 'react';
import { api, ApiError } from '../api/client';
import type {
  CatalogSchemaEntry,
  CatalogTableEntry,
  RecoverableTableEntry,
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

  // Recently dropped (Spec 03 undrop). Absent silently on tenant-dbs where the
  // endpoint rejects (non-DuckLake kinds return 400 invalid_kind).
  const [dropped, setDropped] = useState<RecoverableTableEntry[]>([]);
  const [undropTarget, setUndropTarget] = useState<string | null>(null); // "schema.table"
  const [undropAs, setUndropAs] = useState('');
  const [undropError, setUndropError] = useState<string | null>(null);
  const [undropping, setUndropping] = useState(false);
  const droppedSeq = useRef(0);

  function reloadDropped() {
    const seq = ++droppedSeq.current;
    api.listRecoverable(tenant, tenantDb)
      .then(r => { if (seq === droppedSeq.current) setDropped(r.tables); })
      .catch(e => {
        if (seq !== droppedSeq.current) return;
        // Only the expected rejection (400 invalid_kind on non-DuckLake
        // tenant-dbs) empties the section; a transient failure keeps the
        // last known list instead of silently hiding recoverable tables.
        if (e instanceof ApiError && e.status === 400) setDropped([]);
      });
  }

  useEffect(() => {
    if (!tenant || !tenantDb) { setSchemas([]); setDropped([]); return; }
    setError(null);
    setUndropTarget(null);
    api.listCatalogSchemas(tenant, tenantDb)
      .then(setSchemas)
      .catch(e => setError(String(e)));
    reloadDropped();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant, tenantDb]);

  function undrop(entry: RecoverableTableEntry) {
    setUndropping(true);
    setUndropError(null);
    const asName = undropAs.trim();
    api.undropTable({
      tenant,
      tenantDb,
      schema: entry.schema,
      table: entry.table,
      asName: asName && asName !== entry.table ? asName : undefined,
    })
      .then(() => {
        setUndropTarget(null);
        reloadDropped();
        // Refresh the live lists: the restored table appears in its schema.
        api.listCatalogSchemas(tenant, tenantDb).then(setSchemas).catch(() => {});
        if (schema === entry.schema) {
          api.listCatalogTables(tenant, tenantDb, schema).then(setTables).catch(() => {});
        }
      })
      .catch(e => setUndropError(e instanceof ApiError ? e.message : String(e)))
      .finally(() => setUndropping(false));
  }

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

      {dropped.length > 0 && (
        <details style={{ marginTop: '1.5rem' }} open>
          <summary style={{ cursor: 'pointer', color: 'var(--text-mute)' }}>
            Recently dropped ({dropped.length})
          </summary>
          <p className="subtle" style={{ marginBottom: 4 }}>
            Dropped tables stay recoverable until snapshot expiry reaps their last-live
            snapshot; retention is the undo horizon.
          </p>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th align="left">Table</th>
                <th align="left">Dropped at</th>
                <th align="right">Last-live snapshot</th>
                <th align="left"></th>
              </tr>
            </thead>
            <tbody>
              {dropped.map(d => {
                const key = `${d.schema}.${d.table}`;
                const open = undropTarget === key;
                return (
                  <tr key={key} style={{ borderTop: '1px solid #eee' }}>
                    <td><code>{key}</code></td>
                    <td>{d.droppedAt ? new Date(d.droppedAt).toLocaleString() : '--'}</td>
                    <td align="right">{d.lastLiveSnapshot}</td>
                    <td>
                      {!d.recoverable
                        ? <em style={{ color: '#888' }}>no longer recoverable</em>
                        : open
                          ? (
                            <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
                              <input
                                value={undropAs}
                                onChange={e => setUndropAs(e.target.value)}
                                style={{ width: 180 }}
                                title="restore under this name"
                              />
                              <button type="button" disabled={undropping} onClick={() => undrop(d)}>
                                {undropping ? 'Undropping...' : 'Confirm'}
                              </button>
                              <button type="button" onClick={() => setUndropTarget(null)}>
                                Cancel
                              </button>
                            </span>
                          )
                          : (
                            <button
                              type="button"
                              onClick={() => {
                                setUndropTarget(key);
                                setUndropAs(d.table);
                                setUndropError(null);
                              }}
                            >
                              Undrop
                            </button>
                          )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {undropError && <p style={{ color: 'red' }}>Undrop error: {undropError}</p>}
        </details>
      )}
    </>
  );
}
