import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type {
  FederatedSourceResponse,
  FederatedSecretResponse,
} from '../api/types';

/** Secret sub-editor shown when a source row is expanded. */
function SecretEditor({
  tenant,
  tenantDb,
  alias,
}: {
  tenant: string;
  tenantDb: string;
  alias: string;
}) {
  const [secrets, setSecrets] = useState<FederatedSecretResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);

  // Add-secret form state.
  const [newName,        setNewName]        = useState('');
  const [secretMode,     setSecretMode]     = useState<'value' | 'externalRef'>('value');
  const [newValue,       setNewValue]       = useState('');
  const [newExternalRef, setNewExternalRef] = useState('');

  const reload = () =>
    api.listFederatedSecrets(tenant, tenantDb, alias)
      .then(r => setSecrets(r.secrets))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));

  useEffect(() => { void reload(); }, [tenant, tenantDb, alias]);

  async function handleAdd(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    try {
      await api.upsertFederatedSecret(tenant, tenantDb, alias, {
        name: newName.trim(),
        ...(secretMode === 'value'
          ? { value: newValue }
          : { externalRef: newExternalRef.trim() }),
      });
      setNewName('');
      setNewValue('');
      setNewExternalRef('');
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(name: string) {
    if (!confirm(`Delete secret '${name}'?`)) return;
    setError(null);
    try {
      await api.deleteFederatedSecret(tenant, tenantDb, alias, name);
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  return (
    <div style={{ padding: '0.5rem 1rem', background: '#f8f8f8', borderTop: '1px solid #e0e0e0' }}>
      <strong>Secrets for <code>{alias}</code></strong>
      {error && <div className="login-err" style={{ marginTop: 4 }}>Error: {error}</div>}
      {secrets.length === 0 ? (
        <div className="empty" style={{ marginTop: 4 }}>(no secrets)</div>
      ) : (
        <table style={{ marginTop: '0.5rem' }}>
          <thead>
            <tr>
              <th>Name</th>
              <th>Value</th>
              <th>External ref</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {secrets.map(s => (
              <tr key={s.id}>
                <td><code>{s.name}</code></td>
                <td>
                  {s.value != null
                    ? <code>***REDACTED***</code>
                    : <span className="subtle">-</span>}
                </td>
                <td>
                  {s.externalRef != null
                    ? <code>{s.externalRef}</code>
                    : <span className="subtle">-</span>}
                </td>
                <td>
                  <button className="danger" onClick={() => void handleDelete(s.name)}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <form onSubmit={handleAdd} style={{ marginTop: '0.75rem', display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <label style={{ display: 'flex', flexDirection: 'column' }}>
          <span>Name</span>
          <input
            value={newName}
            onChange={ev => setNewName(ev.target.value)}
            placeholder="MY_SECRET"
            style={{ width: 180, fontFamily: 'monospace' }}
            required
          />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column' }}>
          <span>Mode</span>
          <select value={secretMode} onChange={ev => setSecretMode(ev.target.value as 'value' | 'externalRef')}>
            <option value="value">Inline value</option>
            <option value="externalRef">External ref</option>
          </select>
        </label>
        {secretMode === 'value' ? (
          <label style={{ display: 'flex', flexDirection: 'column' }}>
            <span>Value</span>
            <input
              type="password"
              value={newValue}
              onChange={ev => setNewValue(ev.target.value)}
              placeholder="secret value"
              style={{ width: 200 }}
            />
          </label>
        ) : (
          <label style={{ display: 'flex', flexDirection: 'column' }}>
            <span>External ref</span>
            <input
              value={newExternalRef}
              onChange={ev => setNewExternalRef(ev.target.value)}
              placeholder="arn:aws:secretsmanager:..."
              style={{ width: 280, fontFamily: 'monospace' }}
            />
          </label>
        )}
        <button type="submit">Add / update</button>
      </form>
    </div>
  );
}

/** Federation card for one tenant-db. Mounted from DatabaseSection when the
  * user clicks the "Federation" button for a specific database row. */
export default function FederationSection({
  tenant,
  tenantDb,
  onClose,
}: {
  tenant: string;
  tenantDb: string;
  onClose: () => void;
}) {
  const [sources, setSources] = useState<FederatedSourceResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);
  const [adding, setAdding]   = useState(false);
  // Alias of the source whose secrets are expanded; null = all collapsed.
  const [expanded, setExpanded] = useState<string | null>(null);

  // Add-source form state.
  const [alias,       setAlias]       = useState('');
  const [setupSql,    setSetupSql]    = useState('');
  const [description, setDescription] = useState('');

  // YAML import/export state.
  const [yamlText,     setYamlText]     = useState('');
  const [importResult, setImportResult] = useState<{ sources: number; secrets: number } | null>(null);
  const [importError,  setImportError]  = useState<string | null>(null);
  const [showImport,   setShowImport]   = useState(false);

  const reload = () =>
    api.listFederatedSources(tenant, tenantDb)
      .then(r => setSources(r.sources))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));

  useEffect(() => { void reload(); }, [tenant, tenantDb]);

  function resetAddForm() {
    setAlias('');
    setSetupSql('');
    setDescription('');
    setError(null);
  }

  async function handleCreate(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    try {
      await api.createFederatedSource(tenant, tenantDb, {
        alias:       alias.trim(),
        setupSql:    setupSql.trim(),
        description: description.trim() || undefined,
      });
      resetAddForm();
      setAdding(false);
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(a: string) {
    if (!confirm(`Delete federated source '${a}'? This also removes all its secrets.`)) return;
    setError(null);
    try {
      await api.deleteFederatedSource(tenant, tenantDb, a);
      if (expanded === a) setExpanded(null);
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleExport() {
    setError(null);
    try {
      const yaml = await api.exportFederationYaml(tenant, tenantDb);
      // Trigger a browser download.
      const blob = new Blob([yaml], { type: 'text/plain' });
      const url  = URL.createObjectURL(blob);
      const a    = document.createElement('a');
      a.href     = url;
      a.download = `federation-${tenantDb}.yaml`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleImport(ev: React.FormEvent) {
    ev.preventDefault();
    setImportError(null);
    setImportResult(null);
    try {
      const summary = await api.importFederationYaml(tenant, tenantDb, yamlText);
      setImportResult(summary);
      setYamlText('');
      await reload();
    } catch (e) {
      setImportError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleImportFile(ev: React.ChangeEvent<HTMLInputElement>) {
    const file = ev.target.files?.[0];
    if (!file) return;
    const text = await file.text();
    setYamlText(text);
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>
          Federation &mdash; <code>{tenantDb}</code>
        </div>
        <div className="row" style={{ gap: 8 }}>
          {!adding && (
            <button onClick={() => { resetAddForm(); setAdding(true); }}>+ Add federated source</button>
          )}
          <button onClick={onClose}>&larr; Back to databases</button>
        </div>
      </div>
      <p className="subtle">
        Federated sources are DuckDB <code>ATTACH</code> / extension-based remote catalogs injected
        at session start via <code>setupSql</code>. Secrets referenced inside that SQL are resolved
        from the secrets table at runtime.
      </p>

      {error && <div className="login-err">Error: {error}</div>}

      {adding && (
        <form onSubmit={handleCreate} style={{ marginBottom: '0.75rem' }}>
          <fieldset>
            <legend>New federated source</legend>
            <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
              <label style={{ display: 'flex', flexDirection: 'column' }}>
                <span>Alias <span style={{ color: '#c33' }}>*</span></span>
                <input
                  value={alias}
                  onChange={ev => setAlias(ev.target.value)}
                  placeholder="my_s3_source"
                  style={{ width: 200, fontFamily: 'monospace' }}
                  required
                />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', flex: 1, minWidth: 240 }}>
                <span>Description</span>
                <input
                  value={description}
                  onChange={ev => setDescription(ev.target.value)}
                  placeholder="Optional description"
                />
              </label>
            </div>
            <label style={{ display: 'flex', flexDirection: 'column', marginTop: '0.5rem' }}>
              <span>Setup SQL <span style={{ color: '#c33' }}>*</span></span>
              <textarea
                value={setupSql}
                onChange={ev => setSetupSql(ev.target.value)}
                rows={6}
                placeholder={
                  "-- Examples:\n" +
                  "-- ATTACH 'dbname=mydb host=pg.example.com' AS pg_src (TYPE POSTGRES);\n" +
                  "-- ATTACH 's3://my-bucket/data.parquet' AS parquet_src;\n" +
                  "-- INSTALL httpfs; LOAD httpfs; SET s3_region='us-east-1';"
                }
                style={{ fontFamily: 'monospace', width: '100%' }}
                required
              />
            </label>
            <div className="row" style={{ gap: 8, marginTop: '0.5rem' }}>
              <button type="submit">Create</button>
              <button type="button" onClick={() => { setAdding(false); resetAddForm(); }}>Cancel</button>
            </div>
          </fieldset>
        </form>
      )}

      {sources.length === 0 ? (
        <div className="empty">(no federated sources)</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Alias</th>
              <th>Description</th>
              <th>Disabled</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {sources.map(s => (
              <>
                <tr key={s.id}>
                  <td><code>{s.alias}</code></td>
                  <td>{s.description ?? <span className="subtle">-</span>}</td>
                  <td>{s.disabled ? 'Yes' : 'No'}</td>
                  <td>
                    <div className="row" style={{ gap: 6 }}>
                      <button
                        onClick={() => setExpanded(expanded === s.alias ? null : s.alias)}
                      >
                        {expanded === s.alias ? 'Hide secrets' : 'Edit secrets'}
                      </button>
                      <button className="danger" onClick={() => void handleDelete(s.alias)}>Delete</button>
                    </div>
                  </td>
                </tr>
                {expanded === s.alias && (
                  <tr key={`${s.id}-secrets`}>
                    <td colSpan={4} style={{ padding: 0 }}>
                      <SecretEditor tenant={tenant} tenantDb={tenantDb} alias={s.alias} />
                    </td>
                  </tr>
                )}
              </>
            ))}
          </tbody>
        </table>
      )}

      <div className="row" style={{ gap: 8, marginTop: '1rem', flexWrap: 'wrap' }}>
        <button onClick={() => void handleExport()}>Export YAML</button>
        <button onClick={() => { setShowImport(!showImport); setImportResult(null); setImportError(null); }}>
          {showImport ? 'Hide import' : 'Import YAML'}
        </button>
      </div>

      {showImport && (
        <form onSubmit={handleImport} style={{ marginTop: '0.75rem' }}>
          <fieldset>
            <legend>Import YAML</legend>
            <label style={{ display: 'flex', flexDirection: 'column' }}>
              <span>Paste YAML or load a file:</span>
              <input
                type="file"
                accept=".yaml,.yml,.txt"
                onChange={handleImportFile}
                style={{ marginBottom: '0.5rem' }}
              />
              <textarea
                value={yamlText}
                onChange={ev => setYamlText(ev.target.value)}
                rows={10}
                placeholder="sources:&#10;  - alias: my_source&#10;    setupSql: ATTACH ..."
                style={{ fontFamily: 'monospace', width: '100%' }}
                required
              />
            </label>
            {importError && <div className="login-err" style={{ marginTop: 4 }}>Error: {importError}</div>}
            {importResult && (
              <div style={{ marginTop: 4, color: '#166534', background: '#dcfce7', padding: '0.4rem 0.6rem', borderRadius: 4 }}>
                Import successful: {importResult.sources} source(s), {importResult.secrets} secret(s).
              </div>
            )}
            <div className="row" style={{ gap: 8, marginTop: '0.5rem' }}>
              <button type="submit">Import</button>
              <button type="button" onClick={() => { setShowImport(false); setYamlText(''); setImportResult(null); setImportError(null); }}>
                Cancel
              </button>
            </div>
          </fieldset>
        </form>
      )}
    </div>
  );
}