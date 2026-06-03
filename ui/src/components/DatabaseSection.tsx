import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { TenantDbResponse } from '../api/types';
import DataPathEditor, {
  buildObjectStore, parseExtras as parseStoreExtras,
  type StoreType,
} from './DataPathEditor';

/** Databases card for the TenantDetail page. Lists tenant databases,
  * lets you add a new one (name + dataPath + structured metastore +
  * structured object-store inputs) or delete an existing one. */
export default function DatabaseSection({ tenant }: { tenant: string }) {
  const prefix = `${tenant}_`;

  const [dbs, setDbs]     = useState<TenantDbResponse[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

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
      await api.createTenantDb({
        tenant,
        name:        name,  // server is idempotent on already-prefixed input
        dataPath:    dataPath.trim(),
        metastore:   collectMetastore(),
        objectStore: buildObjectStore(storeType, storeKeys, storeExtras),
      });
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

  return (
    <div className="card">
      <div className="card-title">Databases</div>
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
              <th>Schema</th>
              <th>Data path</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {dbs.map(d => (
              <tr key={d.id}>
                <td>
                  <code>{d.name}</code>
                  {d.disabled && <span className="subtle"> (disabled)</span>}
                </td>
                <td><code>{d.metastore.schemaName || '-'}</code></td>
                <td><code>{d.dataPath || '-'}</code></td>
                <td>
                  <button className="danger" onClick={() => handleDelete(d.name)}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {!adding ? (
        <button onClick={openForm} style={{ marginTop: '0.5rem' }}>+ New database</button>
      ) : (
        <form onSubmit={handleCreate} style={{ marginTop: '0.75rem' }}>
          <fieldset>
            <legend>Identity</legend>
            <p className="subtle" style={{ marginTop: 0 }}>
              Postgres host, port, user, and password are global (shared across every database).
              Only the fields below -- name, schema, data path + its cloud credentials, and any
              advanced metastore keys -- are per-database. The Postgres database itself is
              created automatically as <code>{`${tenant}_<suffix>`}</code>.
            </p>
            <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
              <label style={{ display: 'flex', flexDirection: 'column' }}>
                <span>Name</span>
                <input
                  value={name}
                  onChange={ev => onNameChange(ev.target.value)}
                  placeholder={`${prefix}prod`}
                  aria-invalid={nameError != null}
                  style={{
                    width: 280,
                    fontFamily: 'monospace',
                    borderColor: nameError ? '#c33' : undefined
                  }}
                  required
                />
                {nameError ? (
                  <span style={{ color: '#c33', fontSize: '0.85em', marginTop: 2 }}>{nameError}</span>
                ) : (
                  <span className="subtle" style={{ fontSize: '0.85em', marginTop: 2 }}>
                    The <code>{prefix}</code> prefix is mandatory and locked; only the suffix is editable.
                  </span>
                )}
              </label>
              <label style={{ display: 'flex', flexDirection: 'column' }}>
                <span>Schema</span>
                <input value={schemaName} onChange={ev => setSchemaName(ev.target.value)} placeholder="main" />
              </label>
            </div>
            <details style={{ marginTop: '0.5rem' }}>
              <summary style={{ cursor: 'pointer', color: '#666' }}>Advanced metastore keys</summary>
              <p className="subtle" style={{ marginBottom: 4 }}>
                Extra <code>key=value</code> pairs, one per line. Use for non-standard metastore options.
              </p>
              <textarea
                value={extrasText}
                onChange={ev => setExtrasText(ev.target.value)}
                rows={6}
                placeholder={"# example:\n# applicationName=quack-prod"}
                style={{ width: '100%', fontFamily: 'monospace' }}
              />
            </details>
          </fieldset>

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

          <div className="row" style={{ gap: 8, marginTop: '0.75rem' }}>
            <button type="submit" disabled={nameError != null || name === prefix}>Create</button>
            <button type="button" onClick={() => { setAdding(false); resetForm(); }}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  );
}
