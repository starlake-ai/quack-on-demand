import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { TenantDbKind, TenantDbResponse } from '../api/types';
import CatalogBrowser from './CatalogBrowser';
import DataPathEditor, {
  buildObjectStore, parseExtras as parseStoreExtras,
  type StoreType,
} from './DataPathEditor';
import FederationSection from './FederationSection';
import { DeleteIcon } from './Icons';

/** Databases card for the TenantDetail page. Lists tenant databases,
  * lets you add a new one (name + dataPath + structured metastore +
  * structured object-store inputs) or delete an existing one. */
export default function DatabaseSection({ tenant }: { tenant: string }) {
  const prefix = `${tenant}_`;

  const [dbs, setDbs]         = useState<TenantDbResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);
  const [adding, setAdding]   = useState(false);
  // null = show the list; otherwise the name of the database being
  // browsed inline via <CatalogBrowser>. Clicking "Back" returns to
  // the list without leaving the Databases tab.
  const [browsing, setBrowsing] = useState<string | null>(null);
  // null = show the list; otherwise the name of the database whose
  // federation sources are being managed inline via <FederationSection>.
  const [federating, setFederating] = useState<string | null>(null);

  // Form state. The Name input always starts with the tenant prefix
  // and the locked-prefix logic in `onNameChange` keeps it there.
  const [name, setName]               = useState(prefix);
  const [nameError, setNameError]     = useState<string | null>(null);
  const [dataPath, setDataPath]       = useState('');
  const [schemaName, setSchemaName]   = useState('');
  const [extrasText, setExtrasText]   = useState('');

  const [storeType, setStoreType]     = useState<StoreType>('none');
  const [storeKeys, setStoreKeys]     = useState<Record<string, string>>({});
  const [storeExtras, setStoreExtras] = useState('');

  const [kind, setKind]                     = useState<TenantDbKind>('ducklake');
  const [defaultDatabase, setDefaultDatabase] = useState('');
  const [defaultSchema, setDefaultSchema]     = useState('');

  const reload = () =>
    api.listTenantDbs(tenant)
      .then(r => setDbs(r.tenantDbs))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));

  useEffect(() => { void reload(); }, [tenant]);

  function resetForm() {
    setName(prefix);
    setNameError(null);
    setDataPath('');
    setSchemaName('');
    setExtrasText('');
    setStoreType('none');
    setStoreKeys({});
    setStoreExtras('');
    setKind('ducklake');
    setDefaultDatabase('');
    setDefaultSchema('');
    setError(null);
  }

  function openForm() {
    resetForm();
    setAdding(true);
  }

  // Locked-prefix UX: input always shows `${tenant}_` followed by
  // whatever the user typed. Backspacing into the prefix snaps back
  // to just the prefix and surfaces an inline error.
  function onNameChange(next: string) {
    if (!next.startsWith(prefix)) {
      setNameError(`Name must start with '${prefix}' -- the tenant prefix is mandatory.`);
      setName(prefix);
      return;
    }
    const suffix = next.slice(prefix.length);
    if (suffix.length === 0) {
      setNameError("Suffix is required (e.g. 'prod', 'eu', 'tpch1').");
    } else if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(suffix)) {
      setNameError('Suffix must start with a letter and contain only letters, digits and underscore.');
    } else {
      setNameError(null);
    }
    setName(next);
  }

  function collectMetastore(): Record<string, string> {
    const m: Record<string, string> = {};
    if (schemaName.trim()) m.schemaName = schemaName.trim();
    Object.assign(m, parseStoreExtras(extrasText, new Set(['schemaName', 'dataPath'])));
    return m;
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (nameError != null || name === prefix) return;
    try {
      const reqBody: Parameters<typeof api.createTenantDb>[0] = {
        tenant,
        name,  // server is idempotent on already-prefixed input
        kind,
      };
      if (kind === 'ducklake') {
        reqBody.metastore   = collectMetastore();
        reqBody.dataPath    = dataPath.trim();
        reqBody.objectStore = buildObjectStore(storeType, storeKeys, storeExtras);
      } else if (kind === 'duckdb-file') {
        reqBody.metastore = collectMetastore();
        reqBody.dataPath  = dataPath.trim();
      }
      // memory: no metastore / dataPath / objectStore
      if (defaultDatabase.trim()) reqBody.defaultDatabase = defaultDatabase.trim();
      if (defaultSchema.trim())   reqBody.defaultSchema   = defaultSchema.trim();
      await api.createTenantDb(reqBody);
      resetForm();
      setAdding(false);
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(dbName: string) {
    if (!confirm(`Delete database '${dbName}' from tenant '${tenant}'?\n\n` +
                 `This drops the Postgres database '${dbName}' and any DuckLake catalog in it.`)) return;
    setError(null);
    try {
      await api.deleteTenantDb({ tenant, name: dbName });
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  // Browsing-a-database mode: full-width catalog browser scoped to that DB.
  if (browsing != null) {
    return (
      <div className="card">
        <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
          <div className="card-title" style={{ margin: 0 }}>
            Catalog &mdash; <code>{browsing}</code>
          </div>
          <button type="button" className="link-button" onClick={() => setBrowsing(null)}>&larr; Back to databases</button>
        </div>
        <CatalogBrowser tenant={tenant} tenantDb={browsing} />
      </div>
    );
  }

  // Federation mode: show the FederationSection for the selected database.
  if (federating != null) {
    return (
      <FederationSection
        tenant={tenant}
        tenantDb={federating}
        onClose={() => setFederating(null)}
      />
    );
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Databases</div>
        {!adding && (
          <button type="button" className="link-button" onClick={openForm}>+ New database</button>
        )}
      </div>
      <p className="subtle">
        Each database is a separate Postgres database (one DuckLake catalog + data path + object-store
        config). The control plane creates it as <code>{`${tenant}_<suffix>`}</code> on the shared
        Postgres instance. Pools live inside a database; the bootstrap-default for this tenant is
        <code>{` ${tenant}_tpch1`}</code> when this is the seed tenant.
      </p>
      {error && <div className="login-err">Error: {error}</div>}
      {dbs.length === 0 ? (
        <div className="empty">(none)</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Kind</th>
              <th>Schema</th>
              <th>Data path</th>
              <th>Federation</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {dbs.map(d => (
              <tr key={d.id}>
                <td>
                  <a
                    href="#"
                    onClick={ev => { ev.preventDefault(); setBrowsing(d.name); }}
                    title="Browse this database's catalog"
                  >
                    <code>{d.name}</code>
                  </a>
                  {d.disabled && <span className="subtle"> (disabled)</span>}
                </td>
                <td>
                  <span style={{
                    display: 'inline-block',
                    padding: '1px 6px',
                    borderRadius: 3,
                    fontSize: '0.8em',
                    fontWeight: 600,
                    background: d.kind === 'ducklake' ? '#dbeafe'
                              : d.kind === 'duckdb-file' ? '#fef9c3'
                              : '#f3e8ff',
                    color: d.kind === 'ducklake' ? '#1d4ed8'
                         : d.kind === 'duckdb-file' ? '#854d0e'
                         : '#6b21a8',
                  }}>{d.kind ?? 'ducklake'}</span>
                </td>
                <td><code>{d.metastore.schemaName || d.defaultSchema || '-'}</code></td>
                <td><code>{d.dataPath || '-'}</code></td>
                <td>
                  <button type="button" className="link-button" onClick={() => setFederating(d.name)}>
                    Federation
                    {(d.federatedSourceCount ?? 0) > 0 && (
                      <span style={{
                        marginLeft: 6,
                        padding: '0 6px',
                        borderRadius: 9,
                        background: 'var(--accent)',
                        color: 'var(--accent-fg)',
                        fontSize: '0.75em',
                        fontWeight: 600,
                      }}>{d.federatedSourceCount}</span>
                    )}
                  </button>
                </td>
                <td>
                  <div className="row" style={{ gap: 6 }}>
                    <button className="icon-btn danger" title="Delete" aria-label="Delete" onClick={() => handleDelete(d.name)}><DeleteIcon /></button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {adding && (
        <form onSubmit={handleCreate} style={{ marginTop: '0.75rem' }}>
          <fieldset>
            <legend>Identity</legend>
            <p className="subtle" style={{ marginTop: 0 }}>
              Postgres host, port, user, and password are global (shared across every database).
              Only the fields below -- name, schema, data path + its cloud credentials, and any
              advanced metastore keys -- are per-database. The Postgres database itself is
              created automatically as <code>{`${tenant}_<suffix>`}</code>.
            </p>
            <label>
              Name
              <input
                value={name}
                onChange={ev => onNameChange(ev.target.value)}
                placeholder={`${prefix}prod`}
                aria-invalid={nameError != null}
                style={nameError ? { borderColor: 'var(--bad)' } : undefined}
                required
              />
            </label>
            {nameError ? (
              <p style={{ color: 'var(--bad)', fontSize: '0.85em', marginTop: '-0.5rem' }}>{nameError}</p>
            ) : (
              <p className="subtle" style={{ fontSize: '0.85em', marginTop: '-0.5rem' }}>
                The <code>{prefix}</code> prefix is mandatory and locked; only the suffix is editable.
              </p>
            )}
            <label>
              Kind
              <select value={kind} onChange={e => setKind(e.target.value as TenantDbKind)}>
                <option value="ducklake">ducklake</option>
                <option value="duckdb-file">duckdb-file</option>
                <option value="memory">memory</option>
              </select>
            </label>
            <p className="subtle" style={{ fontSize: '0.85em', marginTop: '-0.5rem' }}>
              {kind === 'ducklake'    && 'Postgres-backed DuckLake catalog + object-store data path.'}
              {kind === 'duckdb-file' && 'Local .duckdb file attached as the default catalog. Single-node only.'}
              {kind === 'memory'      && 'No persistent default catalog. Only useful with federated sources.'}
            </p>
            {kind !== 'memory' && (
              <label>
                Schema
                <input value={schemaName} onChange={ev => setSchemaName(ev.target.value)} placeholder="main" />
              </label>
            )}
            {kind !== 'memory' && (
              <details style={{ marginTop: '0.25rem' }}>
                <summary style={{ cursor: 'pointer', color: 'var(--text-mute)' }}>Advanced metastore keys</summary>
                <p className="subtle" style={{ marginBottom: 4 }}>
                  Extra <code>key=value</code> pairs, one per line. Use for non-standard metastore options.
                </p>
                <textarea
                  value={extrasText}
                  onChange={ev => setExtrasText(ev.target.value)}
                  rows={6}
                  placeholder={"# example:\n# applicationName=quack-prod"}
                />
              </details>
            )}
          </fieldset>

          {kind === 'ducklake' && (
            <div style={{ marginTop: '0.5rem' }}>
              <DataPathEditor
                dataPath={dataPath}
                onDataPathChange={setDataPath}
                storeType={storeType}
                onStoreTypeChange={setStoreType}
                storeKeys={storeKeys}
                onStoreKeysChange={setStoreKeys}
                storeExtras={storeExtras}
                onStoreExtrasChange={setStoreExtras}
              />
            </div>
          )}
          {kind === 'duckdb-file' && (
            <fieldset style={{ marginTop: '0.5rem' }}>
              <legend>Data path</legend>
              <p className="subtle" style={{ marginTop: 0 }}>
                Local <code>.duckdb</code> file path attached as the default catalog.
              </p>
              <label>
                Path
                <input
                  value={dataPath}
                  onChange={ev => setDataPath(ev.target.value)}
                  placeholder="/data/mydb.duckdb"
                />
              </label>
            </fieldset>
          )}

          <fieldset style={{ marginTop: '0.5rem' }}>
            <legend>Federated defaults (optional)</legend>
            <label>
              Default catalog (optional)
              <input
                value={defaultDatabase}
                onChange={ev => setDefaultDatabase(ev.target.value)}
                placeholder="my_catalog"
              />
            </label>
            <label>
              Default schema (optional)
              <input
                value={defaultSchema}
                onChange={ev => setDefaultSchema(ev.target.value)}
                placeholder="main"
              />
            </label>
          </fieldset>

          <div className="row" style={{ gap: 8, marginTop: '0.75rem', justifyContent: 'flex-end' }}>
            <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={() => { setAdding(false); resetForm(); }}>Cancel</button>
            <button type="submit" style={{ minWidth: '7rem' }} disabled={nameError != null || name === prefix}>Create</button>
          </div>
        </form>
      )}
    </div>
  );
}
