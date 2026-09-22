import { Fragment, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type {
  FederatedSourceResponse,
  FederatedSourceType,
  FederatedSecretResponse,
  IcebergAuthType,
  IcebergEndpointType,
  IcebergRestConfig,
} from '../api/types';
import { DeleteIcon, EditIcon } from './Icons';
import { Modal } from './Modal';

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

/** The single selector the Iceberg form shows for "how do we authenticate".
  *
  * DuckDB refuses `AUTHORIZATION_TYPE` combined with `ENDPOINT_TYPE` (and the
  * manager 400s the pair, see `IcebergRestConfig.validate`), so the two option
  * sets can never both be live. Merging them into one control makes that
  * exclusivity impossible to express rather than merely validated: picking
  * `glue` / `s3_tables` sets `endpointType` and leaves `authType` undefined,
  * and the other four do the converse. */
type IcebergMode = IcebergAuthType | IcebergEndpointType;

const ICEBERG_ENDPOINT_MODES: IcebergEndpointType[] = ['glue', 's3_tables'];

const ICEBERG_MODES: { id: IcebergMode; label: string }[] = [
  { id: 'none',      label: 'none - unauthenticated catalog' },
  { id: 'oauth2',    label: 'oauth2 - client credentials exchange' },
  { id: 'token',     label: 'token - bearer token' },
  { id: 'sigv4',     label: 'sigv4 - AWS request signing' },
  { id: 'glue',      label: 'glue - AWS Glue endpoint' },
  { id: 's3_tables', label: 's3_tables - AWS S3 Tables endpoint' },
];

/** Narrows an `IcebergMode` to the two that are `ENDPOINT_TYPE` values. The
  * type predicate is what lets `buildIcebergConfig` assign `mode` straight
  * into `endpointType` / `authType` without a cast, so a mode added to one
  * union and forgotten in the other is a compile error. */
function isEndpointMode(mode: IcebergMode): mode is IcebergEndpointType {
  return (ICEBERG_ENDPOINT_MODES as IcebergMode[]).includes(mode);
}

/** Every free-text input of the Iceberg form, held as one blob so switching
  * mode does not lose what the operator already typed. What is SENT is
  * decided by `buildIcebergConfig`, not by what is still in here. */
type IcebergFields = {
  uri:             string;
  warehouse:       string;
  clientId:        string;
  clientSecret:    string;
  oauth2ServerUri: string;
  oauth2Scope:     string;
  oauth2GrantType: string;
  token:           string;
};

const EMPTY_ICEBERG_FIELDS: IcebergFields = {
  uri: '', warehouse: '', clientId: '', clientSecret: '',
  oauth2ServerUri: '', oauth2Scope: '', oauth2GrantType: '', token: '',
};

/** The manager requires `clientSecret` and `token` to hold a
  * `{{secret.NAME}}` placeholder and rejects a literal with a 400 at save
  * time (`IcebergRestConfig.placeholderErrors`). Same pattern, anchored on
  * the trimmed value exactly as the server anchors on its own. */
const SECRET_PLACEHOLDER = /^\{\{secret\.[A-Za-z0-9_]+\}\}$/;

function isSecretPlaceholder(raw: string): boolean {
  return SECRET_PLACEHOLDER.test(raw.trim());
}

/** A credential field the operator has filled in with something that is not a
  * placeholder. Blank is NOT an error here: the input's own `required`
  * attribute reports that, and disabling submit for it would leave a dead
  * button with no explanation. */
function credentialError(raw: string): boolean {
  return raw.trim().length > 0 && !isSecretPlaceholder(raw);
}

const CREDENTIAL_HINT =
  'Use {{secret.NAME}} and add the value under Secrets. ' +
  'Literal values are rejected when the source is saved.';

/** Assemble the wire config from the form.
  *
  * Fields the selected mode does not show are omitted entirely rather than
  * sent blank: the manager rejects a credential field set under a mode that
  * takes none, so a client secret typed under `oauth2` and abandoned after a
  * switch to `token` must not survive into the request. */
function buildIcebergConfig(mode: IcebergMode, f: IcebergFields): IcebergRestConfig {
  const set = (s: string) => (s.trim().length > 0 ? s.trim() : undefined);
  const warehouse = f.warehouse.trim();
  if (isEndpointMode(mode)) return { warehouse, endpointType: mode };
  const base: IcebergRestConfig = { warehouse, authType: mode, uri: f.uri.trim() };
  if (mode === 'oauth2')
    return {
      ...base,
      clientId:        set(f.clientId),
      clientSecret:    set(f.clientSecret),
      oauth2ServerUri: set(f.oauth2ServerUri),
      oauth2Scope:     set(f.oauth2Scope),
      oauth2GrantType: set(f.oauth2GrantType),
    };
  if (mode === 'token') return { ...base, token: set(f.token) };
  return base;
}

/** Inverse of `buildIcebergConfig` for edit-mode prefill. A stored config
  * always carries exactly one of the two, so the `none` fallback only ever
  * applies to a create. */
function icebergModeOf(config: IcebergRestConfig | undefined): IcebergMode {
  return config?.endpointType ?? config?.authType ?? 'none';
}

function icebergFieldsOf(config: IcebergRestConfig | undefined): IcebergFields {
  if (!config) return EMPTY_ICEBERG_FIELDS;
  return {
    uri:             config.uri             ?? '',
    warehouse:       config.warehouse       ?? '',
    clientId:        config.clientId        ?? '',
    clientSecret:    config.clientSecret    ?? '',
    oauth2ServerUri: config.oauth2ServerUri ?? '',
    oauth2Scope:     config.oauth2Scope     ?? '',
    oauth2GrantType: config.oauth2GrantType ?? '',
    token:           config.token           ?? '',
  };
}

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

/** Secret backends the UI knows how to format. The wire shape sent to
  * `PUT .../secrets`:
  *   - postgres -> { value: <input> }
  *   - any other store -> { externalRef: "<store>:<input>" }
  * The manager's DispatchingSecretResolver routes by the same prefix at
  * node spawn, so the UI's select drives the route. */
type SecretStore = 'postgres' | 'env' | 'aws-sm' | 'gcp-sm' | 'azure-kv' | 'vault';

type SecretStoreSpec = {
  id:           SecretStore;
  label:        string;
  inputLabel:   string;
  placeholder:  string;
  mono:         boolean;
  /** Resolution path: a short sentence the operator can scan to know
    * where the value comes from. */
  resolution:   string;
  /** Credentials the manager process needs to reach the backend. */
  credentials:  string;
  example?:     string;
  /** True if the manager-side resolver is still a stub (NotImplementedError
   *  at node spawn). UI grays the option and blocks form submission.
   *  Existing data with this store is still rendered / browsable so an
   *  operator can replace it; only saving a new value for it is blocked.
   */
  unimplemented?: boolean;
};

const SECRET_STORES: SecretStoreSpec[] = [
  {
    id:          'postgres',
    label:       'Postgres (inline value)',
    inputLabel:  'Value',
    placeholder: 'hunter2',
    mono:        false,
    resolution:  'Stored as plaintext in qodstate_federated_secret.value. PostgresSecretResolver reads it on every node spawn.',
    credentials: 'None beyond the control-plane Postgres connection the manager already holds (defaultMetastore.{pgHost,pgUser,pgPassword,dbName}).',
  },
  {
    id:          'env',
    label:       'Process env var',
    inputLabel:  'Environment variable name',
    placeholder: 'SL_QOD_SECRET_PG_PWD',
    mono:        true,
    resolution:  'EnvSecretResolver calls System.getenv on the var name. Reads happen in the manager JVM at node spawn, not in the Quack node.',
    credentials: 'None. The var just needs to be exported into the manager process.',
    example:     'env:SL_QOD_SECRET_PG_PWD',
  },
  {
    id:          'aws-sm',
    label:       'AWS Secrets Manager (not implemented)',
    inputLabel:  'ARN or name[#jsonKey]',
    placeholder: 'arn:aws:secretsmanager:us-east-1:123456789012:secret:prod/pg-RaNDom',
    mono:        true,
    resolution:  'AwsSecretsManagerResolver calls GetSecretValue. Optional #jsonKey selects a field from a JSON-shaped secret.',
    credentials: 'AWS SDK default credential chain: env vars (AWS_ACCESS_KEY_ID etc.), ~/.aws/credentials, EC2 instance profile, EKS IRSA, ECS task role. Region picked from federation.aws-sm.region.',
    example:     'aws-sm:prod/warehouse/pg#password',
    unimplemented: true,
  },
  {
    id:          'gcp-sm',
    label:       'GCP Secret Manager (not implemented)',
    inputLabel:  'Resource name',
    placeholder: 'projects/my-project/secrets/prod-warehouse-pg/versions/latest',
    mono:        true,
    resolution:  'GcpSecretsManagerResolver calls google-cloud-secretmanager accessSecretVersion on the resource path.',
    credentials: 'Application Default Credentials: GOOGLE_APPLICATION_CREDENTIALS service-account JSON, GKE Workload Identity, or the GCE metadata server.',
    example:     'gcp-sm:projects/my-project/secrets/prod-pg/versions/latest',
    unimplemented: true,
  },
  {
    id:          'azure-kv',
    label:       'Azure Key Vault (not implemented)',
    inputLabel:  'Secret name',
    placeholder: 'prod-warehouse-pg-password',
    mono:        true,
    resolution:  'AzureSecretsManagerResolver calls SecretClient.getSecret on the configured vault URL.',
    credentials: 'DefaultAzureCredential chain: AZURE_* env vars, AKS workload identity, az CLI login (dev). Vault URL picked from federation.azure-kv.vaultUrl.',
    example:     'azure-kv:prod-warehouse-pg-password',
    unimplemented: true,
  },
  {
    id:          'vault',
    label:       'HashiCorp Vault (not implemented)',
    inputLabel:  'Path[#key]',
    placeholder: 'secret/data/prod/warehouse/pg#password',
    mono:        true,
    resolution:  'VaultSecretResolver reads the KV v2 path and (optionally) selects a single field via #key.',
    credentials: 'Static token read from the env var named by federation.vault.tokenEnv (default VAULT_TOKEN). Vault address from federation.vault.address.',
    example:     'vault:secret/data/prod/warehouse/pg#password',
    unimplemented: true,
  },
];

/** Result of validating the user-typed secret-reference body against
  * the currently-selected store. `ok: true` means the form is safe to
  * submit. On `ok: false` the form blocks submission, shows the
  * message, and (if `suggestion` is set) offers a one-click fix. */
type SecretRefValidation =
  | { ok: true }
  | { ok: false; message: string; suggestion?: string };

/** The full set of store prefixes the manager understands -- used to
  * detect "user pasted a full externalRef including the prefix" across
  * stores, not just the currently-selected one. */
const KNOWN_PREFIXES: SecretStore[] = ['env', 'aws-sm', 'gcp-sm', 'azure-kv', 'vault'];

/** Validate the body the user typed (no prefix; the form adds the
  * `<store>:` prefix automatically). Catches three classes of issue:
  *   1. Empty input.
  *   2. User accidentally typed the store prefix themselves -- offer to
  *      strip it.
  *   3. Body doesn't match the selected store's expected shape. */
function validateSecretRef(store: SecretStore, raw: string): SecretRefValidation {
  const s = raw.trim();

  // 1. Empty
  if (s.length === 0) {
    return { ok: false, message: store === 'postgres'
      ? 'Value is required.'
      : 'A reference is required.' };
  }

  // 2. Prefix detection. Match any known store prefix (not just the
  //    currently-selected one) so we can also warn when the user pasted
  //    e.g. an `aws-sm:` ref while the dropdown is on `vault`.
  for (const p of KNOWN_PREFIXES) {
    if (s.startsWith(`${p}:`)) {
      const after = s.slice(p.length + 1);
      if (p === store) {
        return {
          ok: false,
          message: `Drop the "${p}:" prefix - the form adds it for you.`,
          suggestion: after,
        };
      } else {
        return {
          ok: false,
          message: `That looks like a "${p}:" reference, but the store is set to "${store}". Either change the store or paste the body only.`,
          suggestion: after,
        };
      }
    }
  }

  // 3. Per-store shape.
  switch (store) {
    case 'postgres':
      return { ok: true };

    case 'env':
      return /^[A-Za-z_][A-Za-z0-9_]*$/.test(s)
        ? { ok: true }
        : { ok: false, message: 'Expected a POSIX env var name: [A-Za-z_][A-Za-z0-9_]*' };

    case 'aws-sm': {
      const parts = s.split('#');
      if (parts.length > 2)
        return { ok: false, message: 'At most one #jsonKey suffix is allowed.' };
      const base = parts[0];
      if (base.startsWith('arn:')) {
        return /^arn:aws:secretsmanager:[a-z0-9-]+:\d+:secret:.+/.test(base)
          ? { ok: true }
          : { ok: false, message: 'ARN should match arn:aws:secretsmanager:<region>:<accountId>:secret:<name>.' };
      }
      // bare name path: AWS Secrets Manager names allow A-Za-z0-9 and /_+=.@-
      return /^[A-Za-z0-9/_+=.@-]+$/.test(base)
        ? { ok: true }
        : { ok: false, message: 'Secret name allows A-Za-z0-9 and /_+=.@-' };
    }

    case 'gcp-sm':
      return /^projects\/[^/]+\/secrets\/[^/]+\/versions\/[^/]+$/.test(s)
        ? { ok: true }
        : { ok: false, message: 'Expected projects/<project>/secrets/<name>/versions/<version-or-latest>.' };

    case 'azure-kv':
      return /^[A-Za-z0-9-]{1,127}$/.test(s)
        ? { ok: true }
        : { ok: false, message: 'Azure Key Vault secret names are 1-127 chars, alphanumeric or dashes only.' };

    case 'vault': {
      const parts = s.split('#');
      if (parts.length > 2)
        return { ok: false, message: 'At most one #key suffix is allowed.' };
      const path = parts[0];
      return /^[A-Za-z0-9_\-/]+$/.test(path)
        ? { ok: true }
        : { ok: false, message: 'Vault path expects [A-Za-z0-9_-/], optionally followed by #key.' };
    }
  }
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

  // Modal state. `editingName === null` is create-mode; non-null is
  // edit-mode (Name field locked, prefilled where possible).
  const [modalOpen,   setModalOpen]   = useState(false);
  const [editingName, setEditingName] = useState<string | null>(null);

  // Add/edit-secret form state. `store` drives both the input shape and
  // the payload the form POSTs: postgres -> {value}, anything else ->
  // {externalRef: "<store>:<input>"}.
  const [newName,  setNewName]  = useState('');
  const [store,    setStore]    = useState<SecretStore>('postgres');
  const [newValue, setNewValue] = useState('');
  // `touched` gates inline validation messages: don't shout "required"
  // the instant the form mounts, only after the user has interacted.
  const [touched,  setTouched]  = useState(false);

  function openCreate() {
    setEditingName(null);
    setNewName(''); setStore('postgres'); setNewValue('');
    setTouched(false); setError(null);
    setModalOpen(true);
  }
  function openEdit(s: FederatedSecretResponse) {
    setEditingName(s.name);
    setNewName(s.name);
    if (s.externalRef != null) {
      const match = KNOWN_PREFIXES.find(p => s.externalRef!.startsWith(p + ':'));
      if (match) {
        setStore(match);
        setNewValue(s.externalRef!.slice(match.length + 1));
      } else {
        setStore('env');
        setNewValue(s.externalRef!);
      }
    } else {
      // Postgres-stored secret: value is REDACTED on read; the user must
      // re-enter the value to update it.
      setStore('postgres');
      setNewValue('');
    }
    setTouched(false); setError(null);
    setModalOpen(true);
  }
  function closeModal() { setModalOpen(false); setError(null); }
  const storeSpec  = SECRET_STORES.find(s => s.id === store)!;
  const validation = validateSecretRef(store, newValue);
  // Always show a prefix-mismatch warning (touched or not) so a user
  // pasting in a full externalRef sees the fix immediately. Only gate
  // the generic "required" / shape errors on `touched`.
  const showError  = !validation.ok && (touched || newValue.length > 0);

  const reload = () =>
    api.listFederatedSecrets(tenant, tenantDb, alias)
      .then(r => setSecrets(r.secrets))
      .catch(e => setError(errorMessage(e)));

  useEffect(() => { void reload(); }, [tenant, tenantDb, alias]);

  async function handleAdd(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    setTouched(true);
    // Belt-and-suspenders: the submit button is also disabled on invalid,
    // but block here too in case keyboard submit slipped through.
    if (!validation.ok) return;
    try {
      const trimmedValue = newValue.trim();
      const payload =
        store === 'postgres'
          ? { value: newValue }
          : { externalRef: `${store}:${trimmedValue}` };
      await api.upsertFederatedSecret(tenant, tenantDb, alias, {
        name: newName.trim(),
        ...payload,
      });
      setModalOpen(false);
      await reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function handleDelete(name: string) {
    if (!confirm(`Delete secret '${name}'?`)) return;
    setError(null);
    try {
      await api.deleteFederatedSecret(tenant, tenantDb, alias, name);
      await reload();
    } catch (e) {
      setError(errorMessage(e));
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
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <strong>Secrets for <code>{alias}</code></strong>
        <button type="button" className="link-button" onClick={openCreate}>+ New secret</button>
      </div>
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
              <th className="actions">Actions</th>
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
                <td className="actions">
                  <button className="icon-btn" title="Edit" aria-label={`Edit secret ${s.name}`} onClick={() => openEdit(s)}><EditIcon /></button>
                  {' '}
                  <button className="icon-btn danger" title="Delete" aria-label={`Delete secret ${s.name}`} onClick={() => void handleDelete(s.name)}><DeleteIcon /></button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {modalOpen && (
        <Modal maxWidth={640} onClose={closeModal}>
            <div className="card-title">
              {editingName ? <>Edit secret <code>{editingName}</code></> : 'New secret'}
            </div>
            <form onSubmit={handleAdd}>
              <label>
                Name
                <input
                  value={newName}
                  onChange={ev => setNewName(ev.target.value)}
                  placeholder="MY_SECRET"
                  disabled={editingName != null}
                  required
                />
              </label>
              <label>
                Store
                <select value={store} onChange={ev => setStore(ev.target.value as SecretStore)}>
                  {SECRET_STORES.map(s => (
                    <option
                      key={s.id}
                      value={s.id}
                      disabled={s.unimplemented}
                      title={s.unimplemented
                        ? 'Resolver stub -- the manager would raise NotImplementedError at node spawn. Use postgres (inline value) or env (process env var) instead.'
                        : undefined}
                    >
                      {s.label}
                    </option>
                  ))}
                </select>
              </label>
              {storeSpec.unimplemented && (
                <div style={{ fontSize: '0.85em', color: 'var(--bad)' }}>
                  This resolver is a stub; saving a secret with it will work but the manager
                  raises NotImplementedError at node spawn until the SDK is wired. Use
                  <code> postgres </code> (inline value) or <code> env </code> (process env
                  var) instead.
                </div>
              )}
              <label>
                {storeSpec.inputLabel}
                <input
                  type={store === 'postgres' ? 'password' : 'text'}
                  value={newValue}
                  onChange={ev => setNewValue(ev.target.value)}
                  onBlur={() => setTouched(true)}
                  placeholder={
                    editingName && store === 'postgres'
                      ? '(enter a new value to replace the existing secret)'
                      : storeSpec.placeholder
                  }
                  style={showError ? { borderColor: 'var(--bad)' } : undefined}
                  aria-invalid={showError}
                />
              </label>

              {showError && !validation.ok && (
                <div style={{
                  marginTop: '.4rem',
                  padding: '.4rem .65rem',
                  background: 'rgba(248, 113, 113, 0.08)',
                  border: '1px solid rgba(248, 113, 113, 0.4)',
                  borderRadius: 'var(--radius)',
                  color: 'var(--bad)',
                  fontSize: '.85em',
                  display: 'flex',
                  gap: '.6rem',
                  alignItems: 'center',
                  flexWrap: 'wrap',
                }}>
                  <span>{validation.message}</span>
                  {validation.suggestion !== undefined && (
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => setNewValue(validation.suggestion!)}
                    >
                      Use "{validation.suggestion}"
                    </button>
                  )}
                </div>
              )}

              {/* Inline docs for the currently-selected store: resolution path
                  + credentials + the wire shape of the externalRef. */}
              <div className="secret-store-doc">
                <div className="secret-store-doc-row">
                  <span className="secret-store-doc-label">Resolved by</span>
                  <span>{storeSpec.resolution}</span>
                </div>
                <div className="secret-store-doc-row">
                  <span className="secret-store-doc-label">Credentials</span>
                  <span>{storeSpec.credentials}</span>
                </div>
                {store !== 'postgres' && storeSpec.example && (
                  <div className="secret-store-doc-row">
                    <span className="secret-store-doc-label">externalRef stored as</span>
                    <code>{storeSpec.example}</code>
                  </div>
                )}
                {store === 'postgres' && (
                  <div className="secret-store-doc-row">
                    <span className="secret-store-doc-label">externalRef stored as</span>
                    <span style={{ color: 'var(--text-mute)' }}>(none; the literal value is written to qodstate_federated_secret.value)</span>
                  </div>
                )}
              </div>

              <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
                <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={closeModal}>Cancel</button>
                <button type="submit" style={{ minWidth: '7rem' }} disabled={!validation.ok}>Save</button>
              </div>
            </form>
        </Modal>
      )}
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
  // Edit-mode: alias being edited, or null in create-mode. Alias is the
  // identity key and is locked in edit-mode; description / setupSql are
  // editable. Save uses the same `createFederatedSource` endpoint which
  // is an upsert by alias server-side.
  const [editingAlias, setEditingAlias] = useState<string | null>(null);
  // Alias of the source whose secrets are expanded; null = all collapsed.
  const [expanded, setExpanded] = useState<string | null>(null);

  // Add/edit-source form state.
  const [alias,       setAlias]       = useState('');
  const [setupSql,    setSetupSql]    = useState('');
  const [description, setDescription] = useState('');
  const [sourceType,  setSourceType]  = useState<FederatedSourceType>('sql');
  const [readOnly,    setReadOnly]    = useState(false);
  const [icebergMode, setIcebergMode] = useState<IcebergMode>('none');
  const [iceberg,     setIceberg]     = useState<IcebergFields>(EMPTY_ICEBERG_FIELDS);

  const isIceberg = sourceType === 'iceberg_rest';
  const setIcebergField = (key: keyof IcebergFields, value: string) =>
    setIceberg(prev => ({ ...prev, [key]: value }));

  // Blocks submit only on a credential the manager is certain to reject.
  const credentialsOk =
    !isIceberg ||
    !((icebergMode === 'oauth2' && credentialError(iceberg.clientSecret)) ||
      (icebergMode === 'token'  && credentialError(iceberg.token)));

  const reload = () =>
    api.listFederatedSources(tenant, tenantDb)
      .then(r => setSources(r.sources))
      .catch(e => setError(errorMessage(e)));

  useEffect(() => { void reload(); }, [tenant, tenantDb]);

  function resetAddForm() {
    setAlias('');
    setSetupSql('');
    setDescription('');
    setSourceType('sql');
    setReadOnly(false);
    setIcebergMode('none');
    setIceberg(EMPTY_ICEBERG_FIELDS);
    setError(null);
  }

  /** Picking the type also resets the read-only default: an external catalog
    * QoD does not own starts read-only (the manager's own default for a new
    * `iceberg_rest` row), a `sql` source keeps today's behaviour. */
  function selectSourceType(next: FederatedSourceType) {
    setSourceType(next);
    setReadOnly(next === 'iceberg_rest');
  }

  function openCreate() {
    resetAddForm();
    setEditingAlias(null);
    setAdding(true);
  }

  function openEdit(s: FederatedSourceResponse) {
    setAlias(s.alias);
    setSetupSql(s.setupSql ?? '');
    setDescription(s.description ?? '');
    setSourceType(s.sourceType);
    setReadOnly(s.readOnly);
    setIcebergMode(icebergModeOf(s.config));
    setIceberg(icebergFieldsOf(s.config));
    setError(null);
    setEditingAlias(s.alias);
    setAdding(true);
  }

  function closeForm() {
    setAdding(false);
    setEditingAlias(null);
    resetAddForm();
  }

  async function handleCreate(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    if (!credentialsOk) return;
    try {
      await api.createFederatedSource(tenant, tenantDb, {
        // The manager normalizes every alias through Names.normalizeOrError
        // (lowercase); sending the normalized form is what keeps the value the
        // operator sees in this field identical to the one it is stored under.
        alias:       alias.trim().toLowerCase(),
        description: description.trim() || undefined,
        sourceType,
        readOnly,
        // Exactly one of the two: the manager 400s a row carrying both.
        ...(isIceberg
          ? { config: buildIcebergConfig(icebergMode, iceberg) }
          : { setupSql: setupSql.trim() || undefined }),
      });
      closeForm();
      await reload();
    } catch (e) {
      setError(errorMessage(e));
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
      setError(errorMessage(e));
    }
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>
          Federation: <code>{tenantDb}</code>
        </div>
        <div className="row" style={{ gap: 8 }}>
          {!adding && (
            <button type="button" className="link-button" onClick={openCreate}>+ Add federated source</button>
          )}
          <button type="button" className="link-button" onClick={onClose}>&larr; Back to databases</button>
        </div>
      </div>
      <p className="subtle">
        Federated sources are DuckDB <code>ATTACH</code> / extension-based remote catalogs injected
        at session start via <code>setupSql</code>. Secrets referenced inside that SQL are resolved
        from the secrets table at runtime. An <code>iceberg_rest</code> source skips the hand-written
        SQL: fill in the typed form and the manager renders the <code>ATTACH</code> itself.
      </p>

      {error && <div className="login-err">Error: {error}</div>}

      {adding && (
        <Modal maxWidth={800} height="85vh" onClose={closeForm}>
            <div className="card-title">
              {editingAlias ? <>Edit federated source <code>{editingAlias}</code></> : 'New federated source'}
            </div>
            <form onSubmit={handleCreate} style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
              <div style={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
            {/* The alias is lowercased as it is typed rather than silently on
                save: every alias is stored lowercase, and an operator who typed
                "Sales_Lake" should not have to go looking for "sales_lake". */}
            <label>
              Alias <span style={{ color: 'var(--bad)' }}>*</span>
              <input
                value={alias}
                onChange={ev => setAlias(ev.target.value.toLowerCase())}
                placeholder="my_s3_source"
                pattern="[a-z_][a-z0-9_]*"
                maxLength={63}
                disabled={editingAlias != null}
                required
              />
            </label>
            <div style={{ fontSize: '.75em', color: 'var(--text-mute)', marginTop: '-.35rem' }}>
              Stored lowercase, 1 to 63 chars, letters / digits / underscore, not starting with a
              digit. DuckDB compares catalog aliases case-insensitively.
            </div>
            <label>
              Source type
              <select
                value={sourceType}
                onChange={ev => selectSourceType(ev.target.value as FederatedSourceType)}
                disabled={editingAlias != null}
              >
                <option value="sql">sql - your own ATTACH / setup SQL</option>
                <option value="iceberg_rest">iceberg_rest - external Iceberg REST catalog</option>
              </select>
            </label>
            {editingAlias != null && (
              <div style={{ fontSize: '.75em', color: 'var(--text-mute)', marginTop: '-.35rem' }}>
                The type is fixed for an existing alias: the manager refuses a flip between sql and
                iceberg_rest rather than replacing your setup SQL with a rendered config unseen.
                Delete the source and recreate it to change type.
              </div>
            )}
            <label>
              Description
              <input
                value={description}
                onChange={ev => setDescription(ev.target.value)}
                placeholder="Optional description"
              />
            </label>

            {isIceberg && (
              <>
                <label>
                  Warehouse <span style={{ color: 'var(--bad)' }}>*</span>
                  <input
                    value={iceberg.warehouse}
                    onChange={ev => setIcebergField('warehouse', ev.target.value)}
                    placeholder="my_warehouse"
                    required
                  />
                </label>
                <label>
                  Authentication
                  <select
                    value={icebergMode}
                    onChange={ev => setIcebergMode(ev.target.value as IcebergMode)}
                  >
                    {ICEBERG_MODES.map(m => (
                      <option key={m.id} value={m.id}>{m.label}</option>
                    ))}
                  </select>
                </label>
                {isEndpointMode(icebergMode) ? (
                  <div style={{ fontSize: '.75em', color: 'var(--text-mute)' }}>
                    This endpoint type selects its own signing, so it takes no URI and no
                    credentials: DuckDB refuses ENDPOINT_TYPE combined with AUTHORIZATION_TYPE.
                  </div>
                ) : (
                  <label>
                    URI <span style={{ color: 'var(--bad)' }}>*</span>
                    <input
                      value={iceberg.uri}
                      onChange={ev => setIcebergField('uri', ev.target.value)}
                      placeholder="https://catalog.example.com/iceberg"
                      required
                    />
                  </label>
                )}
                {icebergMode === 'oauth2' && (
                  <>
                    <label>
                      Client ID <span style={{ color: 'var(--bad)' }}>*</span>
                      <input
                        value={iceberg.clientId}
                        onChange={ev => setIcebergField('clientId', ev.target.value)}
                        placeholder="qod-catalog-client"
                        required
                      />
                    </label>
                    <label>
                      Client secret <span style={{ color: 'var(--bad)' }}>*</span>
                      <input
                        value={iceberg.clientSecret}
                        onChange={ev => setIcebergField('clientSecret', ev.target.value)}
                        placeholder="{{secret.ICEBERG_CLIENT_SECRET}}"
                        style={credentialError(iceberg.clientSecret) ? { borderColor: 'var(--bad)' } : undefined}
                        aria-invalid={credentialError(iceberg.clientSecret)}
                        required
                      />
                    </label>
                    {credentialError(iceberg.clientSecret) && (
                      <div style={{ fontSize: '.85em', color: 'var(--bad)' }}>
                        Client secret must be a {'{{secret.NAME}}'} placeholder, not a literal value.
                      </div>
                    )}
                    <label>
                      OAuth2 server URI
                      <input
                        value={iceberg.oauth2ServerUri}
                        onChange={ev => setIcebergField('oauth2ServerUri', ev.target.value)}
                        placeholder="Optional; defaults to <uri>/v1/oauth/tokens"
                      />
                    </label>
                    <label>
                      OAuth2 scope
                      <input
                        value={iceberg.oauth2Scope}
                        onChange={ev => setIcebergField('oauth2Scope', ev.target.value)}
                        placeholder="Optional, e.g. PRINCIPAL_ROLE:ALL"
                      />
                    </label>
                    <label>
                      OAuth2 grant type
                      <input
                        value={iceberg.oauth2GrantType}
                        onChange={ev => setIcebergField('oauth2GrantType', ev.target.value)}
                        placeholder="Optional, e.g. client_credentials"
                      />
                    </label>
                  </>
                )}
                {icebergMode === 'token' && (
                  <>
                    <label>
                      Token <span style={{ color: 'var(--bad)' }}>*</span>
                      <input
                        value={iceberg.token}
                        onChange={ev => setIcebergField('token', ev.target.value)}
                        placeholder="{{secret.ICEBERG_TOKEN}}"
                        style={credentialError(iceberg.token) ? { borderColor: 'var(--bad)' } : undefined}
                        aria-invalid={credentialError(iceberg.token)}
                        required
                      />
                    </label>
                    {credentialError(iceberg.token) && (
                      <div style={{ fontSize: '.85em', color: 'var(--bad)' }}>
                        Token must be a {'{{secret.NAME}}'} placeholder, not a literal value.
                      </div>
                    )}
                  </>
                )}
                {(icebergMode === 'oauth2' || icebergMode === 'token') && (
                  <div style={{ fontSize: '.75em', color: 'var(--text-mute)', marginTop: '.35rem' }}>
                    {CREDENTIAL_HINT}
                    {' '}Expand the source row after saving to add the secret value.
                  </div>
                )}
              </>
            )}

            {!isIceberg && (
            <label>
              Setup SQL <span style={{ color: 'var(--bad)' }}>*</span>
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
                rows={12}
                placeholder={
                  "Pick a template above, or write your own.\n\n" +
                  "Placeholders:\n" +
                  "  {{alias}}        - replaced with this source's alias\n" +
                  "  {{secret.NAME}}  - replaced with the resolved value of secret NAME"
                }
                required
              />
            </label>
            )}

            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={readOnly}
                onChange={ev => setReadOnly(ev.target.checked)}
              />
              {' '}Read-only catalog
            </label>
            <div style={{ fontSize: '.75em', color: 'var(--text-mute)' }}>
              {isIceberg
                ? 'Renders a READ_ONLY ATTACH option and denies writes at the edge. Takes effect on an already-running node only after the pool recycles.'
                : 'Denies writes to this catalog at the edge. The ATTACH text itself is yours, so this screen is the only enforcement for a sql source.'}
            </div>
              </div>
              <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
                <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={closeForm}>Cancel</button>
                <button type="submit" style={{ minWidth: '7rem' }} disabled={!credentialsOk}>{editingAlias ? 'Save' : 'Create'}</button>
              </div>
            </form>
        </Modal>
      )}

      {sources.length === 0 ? (
        <div className="empty">(no federated sources)</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Alias</th>
              <th>Type</th>
              <th>Description</th>
              <th>Disabled</th>
              <th className="actions">Actions</th>
            </tr>
          </thead>
          <tbody>
            {sources.map(s => (
              <Fragment key={s.id}>
                <tr>
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
                  <td>
                    <code>{s.sourceType}</code>
                    {/* Absent for a sql or disabled row, and "attached" is the
                        quiet case: only a state an operator should act on is
                        worth a badge. */}
                    {s.attachStatus != null && s.attachStatus !== 'attached' && (
                      <>
                        {' '}
                        <span
                          className="badge warn"
                          title="Live attach state across this pool's nodes"
                        >{s.attachStatus}</span>
                      </>
                    )}
                  </td>
                  <td>{s.description ?? <span className="subtle">-</span>}</td>
                  <td>{s.disabled ? 'Yes' : 'No'}</td>
                  <td className="actions">
                    <button className="icon-btn" title="Edit" aria-label={`Edit federated source ${s.alias}`} onClick={() => openEdit(s)}><EditIcon /></button>
                    {' '}
                    <button className="icon-btn danger" title="Delete" aria-label={`Delete federated source ${s.alias}`} onClick={() => void handleDelete(s.alias)}><DeleteIcon /></button>
                  </td>
                </tr>
                {expanded === s.alias && (
                  <tr>
                    <td colSpan={5} style={{ padding: 0, background: 'var(--bg-elev)' }}>
                      <div style={{ padding: '.75rem 1rem' }}>
                        <div className="row" style={{ gap: '1.5rem', flexWrap: 'wrap', marginBottom: '.6rem' }}>
                          <DetailItem label="Alias"       value={s.alias} mono />
                          <DetailItem label="Source type" value={s.sourceType} mono />
                          <DetailItem label="Source ID"   value={s.id} mono subtle />
                          <DetailItem label="Tenant-DB"   value={s.tenantDbId} mono subtle />
                          <DetailItem label="Disabled"    value={s.disabled ? 'Yes' : 'No'} />
                          <DetailItem label="Read-only"   value={s.readOnly ? 'Yes' : 'No'} />
                          {s.attachStatus && <DetailItem label="Attach status" value={s.attachStatus} />}
                          {s.description && <DetailItem label="Description" value={s.description} />}
                        </div>
                        {s.config && (
                          <div style={{ marginTop: '.4rem' }}>
                            <div style={{
                              fontSize: '.75rem', color: 'var(--text-mute)',
                              textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: '.25rem',
                            }}>
                              Iceberg REST catalog
                            </div>
                            <div className="row" style={{ gap: '1.5rem', flexWrap: 'wrap' }}>
                              {s.config.warehouse && <DetailItem label="Warehouse" value={s.config.warehouse} mono />}
                              {s.config.uri && <DetailItem label="URI" value={s.config.uri} mono />}
                              {s.config.authType && <DetailItem label="Auth type" value={s.config.authType} mono />}
                              {s.config.endpointType && <DetailItem label="Endpoint type" value={s.config.endpointType} mono />}
                              {s.config.clientId && <DetailItem label="Client ID" value={s.config.clientId} mono />}
                              {/* Credential fields hold a {{secret.NAME}} placeholder, never a
                                  value: the manager rejects a literal, which is why showing
                                  them here is safe and tells the operator which secret is wired. */}
                              {s.config.clientSecret && <DetailItem label="Client secret" value={s.config.clientSecret} mono />}
                              {s.config.token && <DetailItem label="Token" value={s.config.token} mono />}
                              {s.config.oauth2ServerUri && <DetailItem label="OAuth2 server URI" value={s.config.oauth2ServerUri} mono />}
                              {s.config.oauth2Scope && <DetailItem label="OAuth2 scope" value={s.config.oauth2Scope} mono />}
                              {s.config.oauth2GrantType && <DetailItem label="OAuth2 grant type" value={s.config.oauth2GrantType} mono />}
                            </div>
                          </div>
                        )}
                        {s.setupSql && (
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
                        )}
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
              </Fragment>
            ))}
          </tbody>
        </table>
      )}

    </div>
  );
}