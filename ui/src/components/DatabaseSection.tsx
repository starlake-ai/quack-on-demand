import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { TenantDbKind, TenantDbResponse, UpdateTenantDbRequest } from '../api/types';
import CatalogBrowser from './CatalogBrowser';
import CatalogSnapshotsPanel from './CatalogSnapshotsPanel';
import DataPathEditor, {
  buildObjectStore, parseExtras as parseStoreExtras,
  type StoreType,
} from './DataPathEditor';
import FederationSection from './FederationSection';

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
  // Bumped when the inline browser commits a catalog write (undrop), so the
  // snapshots panel below it refetches and shows the new recovery snapshot.
  const [catalogGen, setCatalogGen] = useState(0);

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
  const [initSql, setInitSql]               = useState('');

  const [editingDb, setEditingDb]             = useState<TenantDbResponse | null>(null);
  const [editDefaultDb, setEditDefaultDb]     = useState('');
  const [editDefaultSchema, setEditDefaultSchema] = useState('');
  const [editInitSql, setEditInitSql]         = useState('');
  const [editMetastore, setEditMetastore]     = useState('');
  const [editObjectStore, setEditObjectStore] = useState('');
  const [metastoreDirty, setMetastoreDirty]   = useState(false);
  const [objectStoreDirty, setObjectStoreDirty] = useState(false);

  const reload = () =>
    api.listTenantDbs(tenant)
      .then(r => setDbs(r.tenantDbs))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));

  useEffect(() => {
    setEditingDb(null);
    setEditDefaultDb('');
    setEditDefaultSchema('');
    setEditInitSql('');
    setEditMetastore('');
    setEditObjectStore('');
    setMetastoreDirty(false);
    setObjectStoreDirty(false);
    void reload();
  }, [tenant]);

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
    setInitSql('');
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
      if (initSql.trim())         reqBody.initSql         = initSql.trim();
      await api.createTenantDb(reqBody);
      resetForm();
      setAdding(false);
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(dbName: string): Promise<boolean> {
    if (!confirm(`Delete database '${dbName}' from tenant '${tenant}'?\n\n` +
                 `This drops the Postgres database '${dbName}' and any DuckLake catalog in it.`)) return false;
    setError(null);
    try {
      await api.deleteTenantDb({ tenant, name: dbName });
      await reload();
      return true;
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
      return false;
    }
  }

  function openEdit(d: TenantDbResponse) {
    setEditingDb(d);
    setEditDefaultDb(d.defaultDatabase ?? '');
    setEditDefaultSchema(d.defaultSchema ?? '');
    setEditInitSql(d.initSql ?? '');
    setEditMetastore(Object.entries(d.metastore).map(([k, v]) => `${k}=${v}`).join('\n'));
    setEditObjectStore(Object.entries(d.objectStore).map(([k, v]) => `${k}=${v}`).join('\n'));
    setMetastoreDirty(false);
    setObjectStoreDirty(false);
  }

  function nodeAffectingDirty(): boolean {
    if (editingDb == null) return false;
    return metastoreDirty || objectStoreDirty || editInitSql.trim() !== (editingDb.initSql ?? '');
  }

  async function handleUpdate(e: React.FormEvent) {
    e.preventDefault();
    if (editingDb == null) return;
    if (nodeAffectingDirty() && !window.confirm(
      `Save changes to "${editingDb.name}"?\n\n` +
      `All nodes of this database restart immediately; statements running on them will fail. ` +
      `Nodes respawn with the same ids.`)) return;
    setError(null);
    try {
      const req: UpdateTenantDbRequest = { tenant, name: editingDb.name };
      if (editDefaultDb.trim() !== (editingDb.defaultDatabase ?? '')) req.defaultDatabase = editDefaultDb.trim();
      if (editDefaultSchema.trim() !== (editingDb.defaultSchema ?? '')) req.defaultSchema = editDefaultSchema.trim();
      if (editInitSql.trim() !== (editingDb.initSql ?? '')) req.initSql = editInitSql.trim();
      if (metastoreDirty) req.metastore = parseStoreExtras(editMetastore, new Set());
      if (objectStoreDirty) req.objectStore = parseStoreExtras(editObjectStore, new Set());
      const out = await api.updateTenantDb(req);
      if (out.failedRestarts.length > 0) {
        setError(`${out.failedRestarts.length} node restart(s) failed: ` +
          out.failedRestarts.map(f => `${f.nodeId}: ${f.message}`).join('; '));
      }
      setEditingDb(null);
      await reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err));
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
        <CatalogBrowser
          tenant={tenant}
          tenantDb={browsing}
          onCatalogMutated={() => setCatalogGen(g => g + 1)}
        />
        <CatalogSnapshotsPanel tenant={tenant} tenantDb={browsing} refreshToken={catalogGen} />
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
              <th>Tables</th>
              <th>Federation</th>
            </tr>
          </thead>
          <tbody>
            {dbs.map(d => (
              <tr key={d.id}>
                <td>
                  <a
                    href="#"
                    onClick={ev => { ev.preventDefault(); openEdit(d); }}
                    title="Edit this database"
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
                <td>
                  <code>{d.dataPath || d.effectiveDataPath || '-'}</code>
                  {!d.dataPath && d.effectiveDataPath && (
                    <span className="subtle" style={{ marginLeft: 4 }}>(inherited)</span>
                  )}
                </td>
                <td>
                  {d.tableCount == null ? (
                    <span>-</span>
                  ) : (
                    <a href="#" onClick={ev => { ev.preventDefault(); setBrowsing(d.name); }}>
                      {d.tableCount}
                    </a>
                  )}
                </td>
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
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {editingDb != null && (
        <form style={{ marginTop: '0.75rem' }} onSubmit={handleUpdate}>
          <fieldset>
            <legend>Edit <code>{editingDb.name}</code></legend>
            <p className="subtle">
              kind <code>{editingDb.kind}</code>, data path{' '}
              <code>{editingDb.dataPath || editingDb.effectiveDataPath || '-'}</code>
              {!editingDb.dataPath && editingDb.effectiveDataPath ? ' (inherited from default)' : ''}.
              Name, kind, and data path are immutable: changing them is a data migration, not an edit.
            </p>
            <label>Default database
              <input value={editDefaultDb} onChange={ev => setEditDefaultDb(ev.target.value)} />
            </label>
            <label>Default schema
              <input value={editDefaultSchema} onChange={ev => setEditDefaultSchema(ev.target.value)} />
            </label>
            <label>Init SQL
              <p className="subtle" style={{ marginBottom: 4 }}>
                Runs at node boot before the quack extension loads and before the pool's own init SQL.
                Engine defaults only, never credentials: secrets belong in federation sources, not here.
              </p>
              <textarea value={editInitSql} onChange={ev => setEditInitSql(ev.target.value)} rows={5} />
            </label>
            <label>Metastore (key=value per line)
              <p className="subtle" style={{ marginBottom: 4 }}>
                pgPassword is hidden and kept unless you set it: add pgPassword=newvalue to rotate.
                Removing a key this database's kind requires is rejected. Editing this section
                restarts the database's nodes.
              </p>
              <textarea value={editMetastore} rows={4}
                onChange={ev => { setEditMetastore(ev.target.value); setMetastoreDirty(true); }} />
            </label>
            <label>Object store (key=value per line)
              <textarea value={editObjectStore} rows={4}
                onChange={ev => { setEditObjectStore(ev.target.value); setObjectStoreDirty(true); }} />
            </label>
            <div className="row" style={{ gap: 8, marginTop: '0.75rem', justifyContent: 'space-between' }}>
              <button type="button" className="danger"
                onClick={() => void (async () => { const ok = await handleDelete(editingDb.name); if (ok) setEditingDb(null); })()}>
                Delete database
              </button>
              <div className="row" style={{ gap: 8 }}>
                <button type="button" className="cancel-button" onClick={() => setEditingDb(null)}>Cancel</button>
                <button type="submit">{nodeAffectingDirty() ? 'Save and restart nodes' : 'Save'}</button>
              </div>
            </div>
          </fieldset>
        </form>
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

          <fieldset style={{ marginTop: '0.5rem' }}>
            <legend>Init SQL (optional)</legend>
            <p className="subtle" style={{ marginBottom: 4 }}>
              Engine defaults for every node of this database, run at boot before the
              pool's own init SQL: <code>SET temp_directory = '...'</code>,{' '}
              <code>SET memory_limit = '8GB'</code>, <code>INSTALL httpfs; LOAD httpfs;</code>.
              Edits take effect on the next node spawn (scale, crash respawn, or restart).
              Engine defaults only, never credentials: secrets belong in federation sources, not here.
            </p>
            <textarea
              value={initSql}
              onChange={ev => setInitSql(ev.target.value)}
              rows={6}
              placeholder={"SET memory_limit = '8GB';\nSET temp_directory = '/fast-disk/tmp';"}
            />
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
