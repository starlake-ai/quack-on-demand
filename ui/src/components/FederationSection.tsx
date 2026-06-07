import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type {
  FederatedSourceResponse,
  FederatedSecretResponse,
} from '../api/types';

/** Copy-pasteable Setup SQL templates surfaced as quick-insert buttons above
  * the textarea. Each picks a common DuckDB federation flow. Placeholders
  * `{{alias}}` and `{{secret.NAME}}` are resolved server-side at node spawn. */
const SETUP_SAMPLES: { label: string; hint: string; sql: string }[] = [
  {
    label: 'Postgres (read-only)',
    hint:  'ATTACH an external Postgres database via the postgres extension. Password resolved from secret PG_PWD.',
    sql:
      "INSTALL postgres; LOAD postgres;\n" +
      "CREATE OR REPLACE SECRET {{alias}}_sec (\n" +
      "  TYPE POSTGRES,\n" +
      "  HOST 'pg.example.com',\n" +
      "  PORT 5432,\n" +
      "  DATABASE 'warehouse',\n" +
      "  USER 'svc_qod',\n" +
      "  PASSWORD '{{secret.PG_PWD}}'\n" +
      ");\n" +
      "ATTACH '' AS {{alias}} (TYPE POSTGRES, SECRET {{alias}}_sec, READ_ONLY);\n",
  },
  {
    label: 'MySQL (read-only)',
    hint:  'ATTACH an external MySQL database via the mysql extension. Password resolved from secret MYSQL_PWD.',
    sql:
      "INSTALL mysql; LOAD mysql;\n" +
      "CREATE OR REPLACE SECRET {{alias}}_sec (\n" +
      "  TYPE MYSQL,\n" +
      "  HOST 'mysql.example.com',\n" +
      "  PORT 3306,\n" +
      "  DATABASE 'warehouse',\n" +
      "  USER 'svc_qod',\n" +
      "  PASSWORD '{{secret.MYSQL_PWD}}'\n" +
      ");\n" +
      "ATTACH '' AS {{alias}} (TYPE MYSQL, SECRET {{alias}}_sec, READ_ONLY);\n",
  },
  {
    label: 'SQLite file',
    hint:  'ATTACH a local SQLite file as a read-only catalog.',
    sql:
      "INSTALL sqlite; LOAD sqlite;\n" +
      "ATTACH '/data/example.db' AS {{alias}} (TYPE SQLITE, READ_ONLY);\n",
  },
  {
    label: 'S3 Parquet (views)',
    hint:  'Expose S3 parquet files as views in a memory schema under this alias.',
    sql:
      "INSTALL httpfs; LOAD httpfs;\n" +
      "CREATE OR REPLACE SECRET {{alias}}_sec (\n" +
      "  TYPE S3,\n" +
      "  KEY_ID '{{secret.S3_KEY}}',\n" +
      "  SECRET '{{secret.S3_SECRET}}',\n" +
      "  REGION 'us-east-1'\n" +
      ");\n" +
      "ATTACH ':memory:' AS {{alias}};\n" +
      "CREATE SCHEMA IF NOT EXISTS {{alias}}.lake;\n" +
      "CREATE OR REPLACE VIEW {{alias}}.lake.orders   AS SELECT * FROM read_parquet('s3://my-bucket/orders/*.parquet');\n" +
      "CREATE OR REPLACE VIEW {{alias}}.lake.lineitem AS SELECT * FROM read_parquet('s3://my-bucket/lineitem/*.parquet');\n",
  },
  {
    label: 'Iceberg (REST catalog)',
    hint:  'ATTACH an Iceberg REST catalog (e.g. Polaris, Nessie). Token resolved from secret ICEBERG_TOKEN.',
    sql:
      "INSTALL iceberg; LOAD iceberg;\n" +
      "INSTALL httpfs;  LOAD httpfs;\n" +
      "CREATE OR REPLACE SECRET {{alias}}_sec (\n" +
      "  TYPE ICEBERG,\n" +
      "  TOKEN '{{secret.ICEBERG_TOKEN}}'\n" +
      ");\n" +
      "ATTACH 'https://catalog.example.com/iceberg' AS {{alias}} (\n" +
      "  TYPE ICEBERG,\n" +
      "  SECRET {{alias}}_sec,\n" +
      "  WAREHOUSE 'my_warehouse'\n" +
      ");\n",
  },
  {
    label: 'DuckDB file (read-only)',
    hint:  'ATTACH another DuckDB file as a read-only catalog under this alias.',
    sql:
      "ATTACH '/data/external.duckdb' AS {{alias}} (READ_ONLY);\n",
  },
  {
    label: 'HTTPS Parquet (single file)',
    hint:  'Expose a remote parquet file over HTTPS as a view, no credentials required.',
    sql:
      "INSTALL httpfs; LOAD httpfs;\n" +
      "ATTACH ':memory:' AS {{alias}};\n" +
      "CREATE OR REPLACE VIEW {{alias}}.main.taxi\n" +
      "  AS SELECT * FROM read_parquet(\n" +
      "    'https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_2023-01.parquet'\n" +
      "  );\n",
  },
];

/** Inline labeled-value pair used in the federation-source detail view.
  * Renders a small uppercase label above the value. The `mono` prop
  * switches to the monospace font family for code-like values; `subtle`
  * dims the value when it's metadata (ids) the operator rarely cares
  * about. */
function DetailItem({
  label,
  value,
  mono,
  subtle,
}: {
  label:   string;
  value:   string;
  mono?:   boolean;
  subtle?: boolean;
}) {
  return (
    <div>
      <div style={{
        fontSize: '.7rem',
        color: 'var(--text-mute)',
        textTransform: 'uppercase',
        letterSpacing: '.06em',
      }}>{label}</div>
      <div style={{
        fontFamily: mono ? 'var(--mono)' : undefined,
        color: subtle ? 'var(--text-mute)' : 'var(--text)',
        marginTop: 2,
      }}>{value}</div>
    </div>
  );
}

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
    <div style={{
      padding: '0.5rem 1rem',
      background: 'var(--bg-card)',
      border: '1px solid var(--border)',
      borderRadius: 'var(--radius)',
      color: 'var(--text)',
    }}>
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

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>
          Federation &mdash; <code>{tenantDb}</code>
        </div>
        <div className="row" style={{ gap: 8 }}>
          {!adding && (
            <button type="button" className="link-button" onClick={() => { resetAddForm(); setAdding(true); }}>+ Add federated source</button>
          )}
          <button type="button" className="link-button" onClick={onClose}>&larr; Back to databases</button>
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
              <div className="setup-templates">
                <label className="setup-templates-label" htmlFor="setup-template-picker">
                  Start from a template
                </label>
                <select
                  id="setup-template-picker"
                  className="setup-templates-picker"
                  defaultValue=""
                  onChange={ev => {
                    const idx = ev.target.value;
                    if (idx === '') return;
                    const sample = SETUP_SAMPLES[parseInt(idx, 10)];
                    if (sample) setSetupSql(sample.sql);
                    ev.target.value = '';   // reset so the same pick can fire again
                  }}
                >
                  <option value="">Pick a template...</option>
                  {SETUP_SAMPLES.map((s, i) => (
                    <option key={s.label} value={i} title={s.hint}>{s.label}</option>
                  ))}
                </select>
                <span className="setup-templates-hint">
                  inserts a skeleton into the box below; edit before saving
                </span>
              </div>
              <textarea
                value={setupSql}
                onChange={ev => setSetupSql(ev.target.value)}
                rows={20}
                placeholder={
                  "Pick a template above, or write your own.\n\n" +
                  "Placeholders:\n" +
                  "  {{alias}}        - replaced with this source's alias\n" +
                  "  {{secret.NAME}}  - replaced with the resolved value of secret NAME"
                }
                style={{ fontFamily: 'monospace', width: '100%' }}
                required
              />
            </label>
            <div className="row" style={{ gap: 8, marginTop: '0.5rem' }}>
              <button type="submit">Create</button>
              <button type="button" className="cancel-button" onClick={() => { setAdding(false); resetAddForm(); }}>Cancel</button>
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
                  <td>
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => setExpanded(expanded === s.alias ? null : s.alias)}
                      title="Show connection details"
                    >
                      <code>{s.alias}</code>
                    </button>
                  </td>
                  <td>{s.description ?? <span className="subtle">-</span>}</td>
                  <td>{s.disabled ? 'Yes' : 'No'}</td>
                  <td>
                    <button className="danger" onClick={() => void handleDelete(s.alias)}>Delete</button>
                  </td>
                </tr>
                {expanded === s.alias && (
                  <tr key={`${s.id}-detail`}>
                    <td colSpan={4} style={{ padding: 0, background: 'var(--bg-elev)' }}>
                      <div style={{ padding: '.75rem 1rem' }}>
                        <div className="row" style={{ gap: '1.5rem', flexWrap: 'wrap', marginBottom: '.6rem' }}>
                          <DetailItem label="Alias"       value={s.alias} mono />
                          <DetailItem label="Source ID"   value={s.id} mono subtle />
                          <DetailItem label="Tenant-DB"   value={s.tenantDbId} mono subtle />
                          <DetailItem label="Disabled"    value={s.disabled ? 'Yes' : 'No'} />
                          {s.description && <DetailItem label="Description" value={s.description} />}
                        </div>
                        <div style={{ marginTop: '.4rem' }}>
                          <div style={{
                            fontSize: '.75rem', color: 'var(--text-mute)',
                            textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: '.25rem',
                          }}>
                            Setup SQL
                          </div>
                          <pre style={{
                            margin: 0,
                            padding: '.6rem .8rem',
                            background: 'var(--bg-card)',
                            border: '1px solid var(--border)',
                            borderRadius: 'var(--radius)',
                            fontFamily: 'var(--mono)',
                            fontSize: '.85em',
                            color: 'var(--text)',
                            whiteSpace: 'pre-wrap',
                            overflowX: 'auto',
                          }}>{s.setupSql}</pre>
                          <div style={{ fontSize: '.75em', color: 'var(--text-mute)', marginTop: '.35rem' }}>
                            <code>{'{{alias}}'}</code> and <code>{'{{secret.NAME}}'}</code> placeholders are
                            resolved at node spawn; never logged in resolved form.
                          </div>
                        </div>
                        <div style={{ marginTop: '.9rem' }}>
                          <div style={{
                            fontSize: '.75rem', color: 'var(--text-mute)',
                            textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: '.25rem',
                          }}>
                            Secrets
                          </div>
                          <SecretEditor tenant={tenant} tenantDb={tenantDb} alias={s.alias} />
                        </div>
                      </div>
                    </td>
                  </tr>
                )}
              </>
            ))}
          </tbody>
        </table>
      )}

    </div>
  );
}