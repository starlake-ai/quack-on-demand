import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '../api/client';
import type { CatalogSchemaEntry, FederatedSourceResponse } from '../api/types';

/** Nested schema/table browser for one attached Iceberg alias, shown inline when the alias
  * row is expanded. Mirrors CatalogBrowser's schema-aside / table-main layout at a smaller
  * scale, against the Iceberg-specific list endpoints (table names only, no row counts). */
function IcebergAliasBody({
  tenant,
  tenantDb,
  alias,
}: {
  tenant: string;
  tenantDb: string;
  alias: string;
}) {
  const [schemas, setSchemas] = useState<CatalogSchemaEntry[]>([]);
  const [schema, setSchema] = useState('');
  const [tables, setTables] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  // Sequence guards: a namespace switch (or a re-mount on a different alias)
  // must not let an older, still-in-flight schemas/tables fetch overwrite a
  // newer selection's result.
  const schemasSeq = useRef(0);
  const tablesSeq = useRef(0);

  useEffect(() => {
    const seq = ++schemasSeq.current;
    setError(null);
    setSchema('');
    setSchemas([]);
    api.listIcebergSchemas(tenant, tenantDb, alias)
      .then(r => { if (seq === schemasSeq.current) setSchemas(r); })
      .catch(e => { if (seq === schemasSeq.current) setError(errorMessage(e)); });
  }, [tenant, tenantDb, alias]);

  useEffect(() => {
    const seq = ++tablesSeq.current;
    if (!schema) { setTables([]); return; }
    setError(null);
    api.listIcebergTables(tenant, tenantDb, alias, schema)
      .then(r => { if (seq === tablesSeq.current) setTables(r); })
      .catch(e => { if (seq === tablesSeq.current) setError(errorMessage(e)); });
  }, [tenant, tenantDb, alias, schema]);

  return (
    <div style={{ padding: '.75rem 1rem', background: 'var(--bg-elev)' }}>
      {error && <p style={{ color: 'red' }}>Error: {error}</p>}
      <div style={{ display: 'grid', gridTemplateColumns: '220px 1fr', gap: 24 }}>
        <aside>
          <h4 style={{ marginTop: 0 }}>Namespaces</h4>
          {schemas.length === 0
            ? <em style={{ color: '#888' }}>no namespaces</em>
            : (
              <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
                {schemas.map(s => {
                  const active = s.name === schema;
                  return (
                    <li
                      key={s.name}
                      onClick={() => setSchema(s.name)}
                      className={'tree-item' + (active ? ' selected' : '')}
                      style={{
                        cursor: 'pointer',
                        padding: '4px 8px',
                        borderRadius: 4,
                        fontWeight: active ? 600 : 400,
                      }}
                    >
                      {s.name}
                    </li>
                  );
                })}
              </ul>
            )}
        </aside>
        <main>
          <h4 style={{ marginTop: 0 }}>
            Tables in {schema ? <code>{schema}</code> : <em style={{ color: '#888' }}>pick a namespace</em>}
          </h4>
          {schema && (
            tables.length === 0
              ? <em style={{ color: '#888' }}>no tables</em>
              : (
                <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
                  {tables.map(t => (
                    <li key={t} style={{ padding: '4px 0' }}>
                      <Link
                        to={
                          `/catalog/${encodeURIComponent(tenant)}/${encodeURIComponent(tenantDb)}` +
                          `/iceberg/${encodeURIComponent(alias)}/${encodeURIComponent(schema)}/${encodeURIComponent(t)}`
                        }
                      >
                        <code>{t}</code>
                      </Link>
                    </li>
                  ))}
                </ul>
              )
          )}
        </main>
      </div>
    </div>
  );
}

/** External Iceberg REST catalogs attached to a tenant-db, shown under the DuckLake schema
  * browser on the Catalog page. Sourced from the same federated-sources list FederationSection
  * uses, filtered to attached iceberg_rest rows; renders nothing when there are none, so a
  * tenant-db with no external catalogs sees no change to the Catalog page. A failed fetch also
  * renders nothing (logged to the console) rather than putting an error box on every visit to
  * the Catalog page for a tenant-db that may have no Iceberg sources at all. */
export default function IcebergCatalogBrowser({
  tenant,
  tenantDb,
}: {
  tenant: string;
  tenantDb: string;
}) {
  const [sources, setSources] = useState<FederatedSourceResponse[]>([]);
  const [expandedAlias, setExpandedAlias] = useState<string | null>(null);
  // Sequence guard: switching tenant/tenantDb quickly must not let an older
  // in-flight fetch overwrite the newer selection's (possibly empty) result.
  const sourcesSeq = useRef(0);

  useEffect(() => {
    const seq = ++sourcesSeq.current;
    setExpandedAlias(null);
    setSources([]);
    if (!tenant || !tenantDb) return;
    api.listFederatedSources(tenant, tenantDb)
      .then(r => {
        if (seq !== sourcesSeq.current) return;
        setSources(r.sources.filter(s => s.sourceType === 'iceberg_rest' && !s.disabled));
      })
      .catch(e => {
        if (seq !== sourcesSeq.current) return;
        // eslint-disable-next-line no-console
        console.error('failed to list federated sources for Iceberg browser', errorMessage(e));
      });
  }, [tenant, tenantDb]);

  if (sources.length === 0) return null;

  return (
    <section style={{ marginTop: 24 }}>
      <h3>External Iceberg catalogs</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th align="left">Alias</th>
            <th align="left">Status</th>
          </tr>
        </thead>
        <tbody>
          {sources.flatMap(s => {
            const attached = s.attachStatus === 'attached';
            const isOpen = expandedAlias === s.alias;
            const row = (
              <tr key={s.alias} style={{ borderTop: '1px solid #eee' }}>
                <td>
                  {attached ? (
                    <button
                      type="button"
                      className="user-name-toggle"
                      aria-expanded={isOpen}
                      title={isOpen ? 'Hide namespaces' : 'Show namespaces'}
                      onClick={() => setExpandedAlias(isOpen ? null : s.alias)}
                    >
                      <span className="caret">{isOpen ? '▾' : '▸'}</span>
                      <code>{s.alias}</code>
                    </button>
                  ) : (
                    <code>{s.alias}</code>
                  )}
                </td>
                <td>
                  {attached
                    ? 'attached'
                    : <span className="badge warn">{s.attachStatus ?? 'unknown'}</span>}
                </td>
              </tr>
            );
            if (!isOpen) return [row];
            return [row, (
              <tr key={s.alias + '-body'}>
                <td colSpan={2} style={{ padding: 0 }}>
                  <IcebergAliasBody tenant={tenant} tenantDb={tenantDb} alias={s.alias} />
                </td>
              </tr>
            )];
          })}
        </tbody>
      </table>
    </section>
  );
}
