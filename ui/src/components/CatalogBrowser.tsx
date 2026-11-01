import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, errorMessage } from '../api/client';
import type {
  CatalogSchemaEntry,
  CatalogTableDetailResponse,
  CatalogTableEntry,
  PreviewResponse,
  RecoverableTableEntry,
  TenantDbKind,
} from '../api/types';
import { catalogTablePath } from '../nav/links';
import PreviewTable from './PreviewTable';
import Tabs from './Tabs';

/** Schema/table explorer for one (tenant, tenantDb): a tree of schemas and
  * their tables on the left, the selected table's columns and a row preview
  * on the right. Pure body panel: the caller supplies the context. Used by
  * both the standalone `/catalog` page and the inline view inside the
  * Databases tab.
  *
  * `kind` hides what only a DuckLake database has: data files, folders, the
  * snapshot-based full view and recently dropped tables. A `duckdb-file` or
  * `memory` catalog is read from a node of the database's pool, so the first
  * read can wake a suspended pool. */
export default function CatalogBrowser({
  tenant,
  tenantDb,
  kind = 'ducklake',
  onCatalogMutated,
}: {
  tenant:   string;
  tenantDb: string;
  kind?:    TenantDbKind;
  /** Fired after this panel commits a catalog write (undrop recovery), so
    * sibling panels showing derived state (the snapshots list: the recovery
    * CTAS mints a new snapshot) can refetch. */
  onCatalogMutated?: () => void;
}) {
  const isDuckLake = kind === 'ducklake';
  const [schemas, setSchemas]   = useState<CatalogSchemaEntry[] | null>(null);
  const [tables, setTables]     = useState<Record<string, CatalogTableEntry[]>>({});
  const [loadingSchemas, setLoadingSchemas] = useState<Set<string>>(new Set());
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<{ schema: string; table: string } | null>(null);
  const [filter, setFilter]     = useState('');
  const [error, setError]       = useState<string | null>(null);
  // Guards against a slow response for a database the user already left.
  const scopeSeq = useRef(0);

  function loadTables(schema: string, seq = scopeSeq.current) {
    setLoadingSchemas(s => new Set(s).add(schema));
    return api.listCatalogTables(tenant, tenantDb, schema)
      .then(ts => { if (seq === scopeSeq.current) setTables(m => ({ ...m, [schema]: ts })); })
      .catch(e => { if (seq === scopeSeq.current) setError(errorMessage(e)); })
      .finally(() => {
        if (seq === scopeSeq.current) {
          setLoadingSchemas(s => { const n = new Set(s); n.delete(schema); return n; });
        }
      });
  }

  useEffect(() => {
    const seq = ++scopeSeq.current;
    setSchemas(null);
    setTables({});
    setExpanded(new Set());
    setSelected(null);
    setFilter('');
    setError(null);
    if (!tenant || !tenantDb) return;
    api.listCatalogSchemas(tenant, tenantDb)
      .then(ss => {
        if (seq !== scopeSeq.current) return;
        setSchemas(ss);
        // Open the first schema that holds tables, so the tree never starts as a wall of carets.
        const first = ss.find(s => s.tableCount > 0);
        if (first) {
          setExpanded(new Set([first.name]));
          loadTables(first.name, seq);
        }
      })
      .catch(e => { if (seq === scopeSeq.current) { setSchemas([]); setError(errorMessage(e)); } });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant, tenantDb]);

  // A filter searches every schema, so fetch the ones not opened yet.
  useEffect(() => {
    if (!filter || !schemas) return;
    schemas
      .filter(s => s.tableCount > 0 && !tables[s.name] && !loadingSchemas.has(s.name))
      .forEach(s => loadTables(s.name));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter, schemas]);

  function toggle(schema: string) {
    const opening = !expanded.has(schema);
    setExpanded(prev => {
      const next = new Set(prev);
      if (opening) next.add(schema);
      else next.delete(schema);
      return next;
    });
    if (opening && !tables[schema] && !loadingSchemas.has(schema)) loadTables(schema);
  }

  const needle = filter.trim().toLowerCase();
  const matches = (t: CatalogTableEntry) => !needle || t.name.toLowerCase().includes(needle);
  const selectedEntry = selected
    ? tables[selected.schema]?.find(t => t.name === selected.table) ?? null
    : null;

  return (
    <>
      {error && <div className="login-err">{error}</div>}
      <div className="catalog-explorer">
        <nav className="catalog-tree" aria-label="Schemas and tables">
          <input
            type="search"
            className="catalog-filter"
            value={filter}
            onChange={e => setFilter(e.target.value)}
            placeholder="Filter tables"
            aria-label="Filter tables by name"
          />
          {schemas == null && <div className="subtle catalog-tree-note">Loading schemas...</div>}
          {schemas?.length === 0 && !error && (
            <div className="subtle catalog-tree-note">This database has no schemas yet.</div>
          )}
          <ul className="catalog-tree-list">
            {schemas?.map(s => {
              const open = needle ? true : expanded.has(s.name);
              const ts = (tables[s.name] ?? []).filter(matches);
              if (needle && tables[s.name] && ts.length === 0) return null;
              return (
                <li key={s.name}>
                  <button
                    type="button"
                    className="catalog-node schema"
                    aria-expanded={open}
                    onClick={() => toggle(s.name)}
                    disabled={!!needle}
                  >
                    <span className="caret" aria-hidden="true">{open ? '▾' : '▸'}</span>
                    <span className="catalog-node-name">{s.name}</span>
                    <span className="catalog-node-count" title={`${s.tableCount} tables`}>{s.tableCount}</span>
                  </button>
                  {open && (
                    <ul>
                      {loadingSchemas.has(s.name) && !tables[s.name] && (
                        <li className="subtle catalog-tree-note">Loading...</li>
                      )}
                      {tables[s.name]?.length === 0 && (
                        <li className="subtle catalog-tree-note">No tables</li>
                      )}
                      {ts.map(t => {
                        const active = selected?.schema === s.name && selected.table === t.name;
                        return (
                          <li key={t.name}>
                            <button
                              type="button"
                              className={'catalog-node table' + (active ? ' selected' : '')}
                              aria-current={active ? 'true' : undefined}
                              onClick={() => setSelected({ schema: s.name, table: t.name })}
                            >
                              <span className="catalog-node-name">{t.name}</span>
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </li>
              );
            })}
          </ul>
        </nav>

        <section className="catalog-detail" aria-live="polite">
          {selected && selectedEntry ? (
            <TableDetail
              key={`${selected.schema}.${selected.table}`}
              tenant={tenant}
              tenantDb={tenantDb}
              entry={selectedEntry}
              isDuckLake={isDuckLake}
            />
          ) : (
            <div className="catalog-detail-empty">
              Pick a table to see its columns and preview its rows.
            </div>
          )}
        </section>
      </div>

      {isDuckLake && (
        <RecentlyDropped
          tenant={tenant}
          tenantDb={tenantDb}
          onRestored={schema => {
            api.listCatalogSchemas(tenant, tenantDb).then(setSchemas).catch(() => {});
            if (tables[schema]) loadTables(schema);
            onCatalogMutated?.();
          }}
        />
      )}
    </>
  );
}

/** The selected table: name, the facts its kind has, and Columns / Preview tabs. */
function TableDetail({
  tenant, tenantDb, entry, isDuckLake,
}: {
  tenant: string;
  tenantDb: string;
  entry: CatalogTableEntry;
  isDuckLake: boolean;
}) {
  const [detail, setDetail] = useState<CatalogTableDetailResponse | null>(null);
  const [error, setError]   = useState<string | null>(null);

  useEffect(() => {
    api.getCatalogTable(tenant, tenantDb, entry.schema, entry.name)
      .then(setDetail)
      .catch(e => setError(errorMessage(e)));
  }, [tenant, tenantDb, entry.schema, entry.name]);

  return (
    <>
      <header className="catalog-detail-head">
        <h3>
          <span className="catalog-detail-schema">{entry.schema}.</span>{entry.name}
        </h3>
        {isDuckLake && (
          <Link to={catalogTablePath(tenant, tenantDb, entry.schema, entry.name)}>
            Snapshots and history
          </Link>
        )}
      </header>
      <dl className="catalog-facts">
        <div>
          <dt>Rows</dt>
          <dd>{entry.rowCount < 0 ? 'unknown' : entry.rowCount.toLocaleString()}</dd>
        </div>
        {detail && (
          <div>
            <dt>Columns</dt>
            <dd>{detail.columns.length}</dd>
          </div>
        )}
        {isDuckLake && (
          <div>
            <dt>Data files</dt>
            <dd>{entry.dataFileCount}</dd>
          </div>
        )}
        {isDuckLake && entry.folder && (
          <div className="catalog-facts-wide">
            <dt>Folder</dt>
            <dd><code>{entry.folder}</code></dd>
          </div>
        )}
      </dl>

      <Tabs
        tabs={[
          {
            id: 'columns',
            label: 'Columns',
            body: error ? <div className="login-err">{error}</div>
              : !detail ? <div className="subtle">Loading columns...</div>
              : detail.columns.length === 0 ? <div className="subtle">This table has no columns.</div>
              : (
                <div className="catalog-scroll">
                <table className="catalog-columns">
                  <thead>
                    <tr>
                      <th className="num">#</th>
                      <th>Name</th>
                      <th>Type</th>
                      <th>Nullable</th>
                      <th>Key</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.columns.map(c => (
                      <tr key={c.ordinal}>
                        <td className="num subtle">{c.ordinal}</td>
                        <td><code>{c.name}</code></td>
                        <td><code className="catalog-type">{c.typeName}</code></td>
                        <td className="subtle">{c.nullable ? 'yes' : 'no'}</td>
                        <td>{c.isPrimaryKey && <span className="badge warn">primary</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                </div>
              ),
          },
          {
            id: 'preview',
            label: 'Preview',
            body: <PreviewPane tenant={tenant} tenantDb={tenantDb} schema={entry.schema} table={entry.name} />,
          },
        ]}
      />
    </>
  );
}

/** First rows of the table, fetched when the Preview tab opens (Tabs remounts its body). */
function PreviewPane({
  tenant, tenantDb, schema, table,
}: { tenant: string; tenantDb: string; schema: string; table: string }) {
  const [preview, setPreview] = useState<PreviewResponse | null>(null);
  const [error, setError]     = useState<string | null>(null);

  useEffect(() => {
    api.previewCatalogTable(tenant, tenantDb, schema, table, undefined, 100)
      .then(setPreview)
      .catch(e => setError(
        e instanceof ApiError && e.status === 403
          ? 'Your roles do not grant SELECT on this table.'
          : errorMessage(e)
      ));
  }, [tenant, tenantDb, schema, table]);

  if (error) return <div className="login-err">{error}</div>;
  if (!preview) return <div className="subtle">Loading rows...</div>;
  return (
    <div className="catalog-preview">
      <PreviewTable columns={preview.columns} rows={preview.rows} truncated={preview.truncated} />
    </div>
  );
}

/** Spec 03 undrop: DuckLake tables dropped but still recoverable from a live snapshot. */
function RecentlyDropped({
  tenant, tenantDb, onRestored,
}: {
  tenant: string;
  tenantDb: string;
  onRestored: (schema: string) => void;
}) {
  const [dropped, setDropped] = useState<RecoverableTableEntry[]>([]);
  const [undropTarget, setUndropTarget] = useState<string | null>(null); // "schema.table"
  const [undropAs, setUndropAs] = useState('');
  const [undropError, setUndropError] = useState<string | null>(null);
  const [undropping, setUndropping] = useState(false);
  // Per "schema.table" group: the last-live snapshot picked in the version select.
  // Absent = newest recoverable drop of that name.
  const [undropFrom, setUndropFrom] = useState<Record<string, number>>({});
  const droppedSeq = useRef(0);

  // A name dropped N times yields N reader rows (newest first). One line per
  // table; the versions feed the last-live snapshot select.
  const droppedGroups = useMemo(() => {
    const byName = new Map<string, RecoverableTableEntry[]>();
    for (const d of dropped) {
      const key = `${d.schema}.${d.table}`;
      const list = byName.get(key) ?? [];
      list.push(d);
      byName.set(key, list);
    }
    return [...byName.entries()].map(([key, versions]) => ({ key, versions }));
  }, [dropped]);

  function reload() {
    const seq = ++droppedSeq.current;
    api.listRecoverable(tenant, tenantDb)
      .then(r => { if (seq === droppedSeq.current) setDropped(r.tables); })
      .catch(e => {
        if (seq !== droppedSeq.current) return;
        // Only the expected rejection (400 invalid_kind) empties the section; a transient
        // failure keeps the last known list instead of silently hiding recoverable tables.
        if (e instanceof ApiError && e.status === 400) setDropped([]);
      });
  }

  useEffect(() => {
    setDropped([]);
    setUndropTarget(null);
    setUndropFrom({});
    if (tenant && tenantDb) reload();
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
      // Pin the picked version: the handler CTAS-reads AT (VERSION => fromSnapshot),
      // so an older drop of a multiply-dropped name recovers that state.
      fromSnapshot: entry.lastLiveSnapshot,
      tableId: entry.tableId,
    })
      .then(() => {
        setUndropTarget(null);
        reload();
        onRestored(entry.schema);
      })
      .catch(e => setUndropError(errorMessage(e)))
      .finally(() => setUndropping(false));
  }

  if (droppedGroups.length === 0) return null;
  return (
    <details className="catalog-dropped" open>
      <summary>Recently dropped ({droppedGroups.length})</summary>
      <p className="subtle">
        Dropped tables stay recoverable until snapshot expiry reaps their last-live
        snapshot. A table dropped more than once keeps one line; pick the version to
        restore in the snapshot select.
      </p>
      <table>
        <thead>
          <tr>
            <th>Table</th>
            <th>Dropped at</th>
            <th className="num">Last-live snapshot</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {droppedGroups.map(g => {
            const open = undropTarget === g.key;
            // Selected version: the user's pick when it still exists after a refetch, else the
            // newest recoverable drop. undefined = every version's last-live snapshot expired.
            const selected =
              g.versions.find(v => v.recoverable && v.lastLiveSnapshot === undropFrom[g.key])
              ?? g.versions.find(v => v.recoverable);
            const shown = selected ?? g.versions[0];
            return (
              <tr key={g.key}>
                <td><code>{g.key}</code></td>
                <td>{shown.droppedAt ? new Date(shown.droppedAt).toLocaleString() : '-'}</td>
                <td className="num">
                  {g.versions.length === 1
                    ? shown.lastLiveSnapshot
                    : (
                      <select
                        value={selected?.lastLiveSnapshot ?? g.versions[0].lastLiveSnapshot}
                        onChange={e =>
                          setUndropFrom(m => ({ ...m, [g.key]: Number(e.target.value) }))
                        }
                        aria-label={`Version of ${g.key} to restore`}
                      >
                        {g.versions.map(v => (
                          <option
                            key={v.droppedAtSnapshot}
                            value={v.lastLiveSnapshot}
                            disabled={!v.recoverable}
                          >
                            {v.lastLiveSnapshot}
                            {v.droppedAt ? `, dropped ${new Date(v.droppedAt).toLocaleString()}` : ''}
                            {v.recoverable ? '' : ' (expired)'}
                          </option>
                        ))}
                      </select>
                    )}
                </td>
                <td>
                  {!selected
                    ? <span className="subtle">No longer recoverable</span>
                    : open
                      ? (
                        <span className="catalog-undrop-form">
                          <input
                            value={undropAs}
                            onChange={e => setUndropAs(e.target.value)}
                            aria-label="Restore under this name"
                          />
                          <button type="button" disabled={undropping} onClick={() => undrop(selected)}>
                            {undropping ? 'Restoring...' : 'Restore'}
                          </button>
                          <button type="button" onClick={() => setUndropTarget(null)}>Cancel</button>
                        </span>
                      )
                      : (
                        <button
                          type="button"
                          onClick={() => {
                            setUndropTarget(g.key);
                            setUndropAs(selected.table);
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
      {undropError && <div className="login-err">{undropError}</div>}
    </details>
  );
}
