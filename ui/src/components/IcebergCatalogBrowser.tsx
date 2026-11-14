import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '../api/client';
import type { CatalogSchemaEntry, FederatedSourceResponse } from '../api/types';
import { icebergTablePath } from '../nav/links';

/** Namespace/table browser for one attached Iceberg alias, shown inline when the alias row is
  * expanded: a namespace dropdown (preselected when there is only one) above that namespace's
  * tables, against the Iceberg-specific list endpoints (table names only, no row counts). */
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
  const [tablesLoading, setTablesLoading] = useState(false);
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
      .then(r => {
        if (seq !== schemasSeq.current) return;
        setSchemas(r);
        // A single namespace needs no choice: select it so its tables show at once.
        if (r.length === 1) setSchema(r[0].name);
      })
      .catch(e => { if (seq === schemasSeq.current) setError(errorMessage(e)); });
  }, [tenant, tenantDb, alias]);

  useEffect(() => {
    const seq = ++tablesSeq.current;
    if (!schema) { setTables([]); setTablesLoading(false); return; }
    setError(null);
    setTables([]);
    setTablesLoading(true);
    api.listIcebergTables(tenant, tenantDb, alias, schema)
      .then(r => { if (seq === tablesSeq.current) setTables(r); })
      .catch(e => { if (seq === tablesSeq.current) setError(errorMessage(e)); })
      .finally(() => { if (seq === tablesSeq.current) setTablesLoading(false); });
  }, [tenant, tenantDb, alias, schema]);

  return (
    <div style={{ padding: '.75rem 1rem', background: 'var(--bg-elev)' }}>
      {error && <p style={{ color: 'red' }}>Error: {error}</p>}
      {schemas.length === 0 ? (
        <em style={{ color: 'var(--color-muted)' }}>no namespaces</em>
      ) : (
        <>
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            Namespace
            <select value={schema} onChange={ev => setSchema(ev.target.value)}>
              {schemas.length > 1 && <option value="">Pick a namespace</option>}
              {schemas.map(s => (
                <option key={s.name} value={s.name}>{s.name}</option>
              ))}
            </select>
          </label>
          {schema && (
            <>
              <h4 style={{ marginTop: 0 }}>Tables in <code>{schema}</code></h4>
              {tablesLoading
                ? <em style={{ color: 'var(--color-muted)' }}>Loading...</em>
                : tables.length === 0
                ? <em style={{ color: 'var(--color-muted)' }}>no tables</em>
                : (
                  <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
                    {tables.map(t => (
                      <li key={t} style={{ padding: '4px 0' }}>
                        <Link
                          to={icebergTablePath(tenant, tenantDb, alias, schema, t)}
                        >
                          <code>{t}</code>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
            </>
          )}
        </>
      )}
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
              <tr key={s.alias} style={{ borderTop: '1px solid var(--color-rule)' }}>
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
