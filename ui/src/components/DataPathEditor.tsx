import { useEffect } from 'react';

// Object-store backends supported in the picker. The labels are the
// human strings shown in the dropdown; the keys are the values
// persisted on the tenant-db row's `objectStore` map and used by the
// Quack node to attach the right cloud filesystem.
export type StoreType = 'none' | 's3' | 'r2' | 'azure' | 'gcs';

interface KeySpec {
  name:        string;
  label:       string;
  placeholder?: string;
  secret?:     boolean;
}

interface StoreSpec {
  label:    string;
  keys:     KeySpec[];
  presets?: Record<string, string>;
}

const STORE_SPECS: Record<StoreType, StoreSpec> = {
  none: {
    label: 'Local filesystem',
    keys:  []
  },
  s3: {
    label: 'AWS S3',
    keys: [
      { name: 's3_region',            label: 'Region',     placeholder: 'us-east-1' },
      { name: 's3_access_key_id',     label: 'Access key id' },
      { name: 's3_secret_access_key', label: 'Secret access key', secret: true },
      { name: 's3_endpoint',          label: 'Endpoint',   placeholder: '(optional, leave blank for AWS)' }
    ]
  },
  r2: {
    label: 'Cloudflare R2',
    keys: [
      { name: 's3_endpoint',          label: 'Endpoint',   placeholder: 'https://<account>.r2.cloudflarestorage.com' },
      { name: 's3_access_key_id',     label: 'Access key id' },
      { name: 's3_secret_access_key', label: 'Secret access key', secret: true }
    ],
    presets: { s3_region: 'auto', s3_url_style: 'path' }
  },
  azure: {
    label: 'Azure Blob',
    keys: [
      { name: 'azure_account',       label: 'Account' },
      { name: 'azure_account_key',   label: 'Account key', secret: true }
    ]
  },
  gcs: {
    label: 'Google Cloud Storage',
    keys: [
      { name: 'gcs_hmac_key_id',     label: 'HMAC key id' },
      { name: 'gcs_hmac_secret',     label: 'HMAC secret', secret: true }
    ]
  }
};

const SCHEME_TO_TYPE: Record<string, StoreType> = {
  's3':    's3',
  'r2':    'r2',
  'azure': 'azure',
  'gs':    'gcs',
};

function detectBackend(dataPath: string): StoreType {
  const m = /^([a-zA-Z0-9]+):\/\//.exec(dataPath);
  if (!m) return 'none';
  return SCHEME_TO_TYPE[m[1].toLowerCase()] ?? 'none';
}

/** Parse a textarea of `key=value` lines into a flat record. Empty
  * lines + lines starting with `#` are skipped. `forbid` keys are
  * silently dropped (used to prevent the textarea from clobbering the
  * structured inputs). */
export function parseExtras(raw: string, forbid: Set<string>): Record<string, string> {
  const out: Record<string, string> = {};
  raw.split(/\r?\n/).forEach(line => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) return;
    const eq = trimmed.indexOf('=');
    if (eq < 0) return;
    const k = trimmed.slice(0, eq).trim();
    const v = trimmed.slice(eq + 1).trim();
    if (!k || forbid.has(k)) return;
    out[k] = v;
  });
  return out;
}

export function buildObjectStore(
  storeType:   StoreType,
  storeKeys:   Record<string, string>,
  storeExtras: string,
): Record<string, string> {
  if (storeType === 'none') return {};
  const spec   = STORE_SPECS[storeType];
  const forbid = new Set(spec.keys.map(k => k.name));
  const extras = parseExtras(storeExtras, forbid);
  const out: Record<string, string> = { ...(spec.presets ?? {}) };
  for (const k of spec.keys) {
    const v = storeKeys[k.name];
    if (v && v.trim()) out[k.name] = v.trim();
  }
  Object.assign(out, extras);
  return out;
}

interface Props {
  dataPath:    string;
  onDataPathChange: (v: string) => void;
  storeType:   StoreType;
  onStoreTypeChange: (t: StoreType) => void;
  storeKeys:   Record<string, string>;
  onStoreKeysChange: (m: Record<string, string>) => void;
  storeExtras: string;
  onStoreExtrasChange: (s: string) => void;
}

/** Backend-first storage editor. The leftmost control is the
  * **Backend** dropdown; changing it (or pasting an `s3://`/`r2://`/...
  * URL into the path) reveals the credential fields for that backend.
  * Empty inputs are not sent. */
export default function DataPathEditor(p: Props) {
  // Auto-flip the backend dropdown when the user pastes a URL whose
  // scheme is known. Skips the override when the dropdown was already
  // moved deliberately (storeType !== 'none' and detected scheme = none).
  useEffect(() => {
    const detected = detectBackend(p.dataPath);
    if (detected !== 'none' && detected !== p.storeType) {
      p.onStoreTypeChange(detected);
    }
  }, [p.dataPath]);

  const spec = STORE_SPECS[p.storeType];

  return (
    <fieldset>
      <legend>Data path</legend>
      <p className="subtle" style={{ marginTop: 0 }}>
        Where the parquet files for this database live. Use a local filesystem path
        (<code>/data/prod</code>), an <code>s3://</code> URL, <code>gs://</code>,
        <code> azure://</code>, etc. Pick the backend on the left; the URL scheme
        also auto-selects it.
      </p>
      <div className="row" style={{ gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <label style={{ display: 'flex', flexDirection: 'column' }}>
          <span>Backend</span>
          <select
            value={p.storeType}
            onChange={ev => p.onStoreTypeChange(ev.target.value as StoreType)}
          >
            {(Object.keys(STORE_SPECS) as StoreType[]).map(t => (
              <option key={t} value={t}>{STORE_SPECS[t].label}</option>
            ))}
          </select>
        </label>
        <label style={{ flex: '1 1 320px', display: 'flex', flexDirection: 'column' }}>
          <span>Path</span>
          <input
            value={p.dataPath}
            onChange={ev => p.onDataPathChange(ev.target.value)}
            placeholder="/data/prod or s3://bucket/path"
            style={{ width: '100%' }}
          />
        </label>
      </div>

      {spec.keys.length > 0 && (
        <div style={{ marginTop: '0.75rem' }}>
          <p className="subtle" style={{ margin: 0 }}>
            <strong>{spec.label}</strong> credentials. Empty fields are not sent.
          </p>
          <div className="row" style={{ gap: 12, flexWrap: 'wrap', marginTop: '0.5rem' }}>
            {spec.keys.map(k => (
              <label key={k.name} style={{ display: 'flex', flexDirection: 'column' }}>
                <span>{k.label} <span className="subtle"><code>({k.name})</code></span></span>
                <input
                  type={k.secret ? 'password' : 'text'}
                  value={p.storeKeys[k.name] ?? ''}
                  onChange={ev => p.onStoreKeysChange({ ...p.storeKeys, [k.name]: ev.target.value })}
                  placeholder={k.placeholder}
                  style={{ minWidth: 240 }}
                />
              </label>
            ))}
          </div>
        </div>
      )}

      {spec.presets && Object.keys(spec.presets).length > 0 && (
        <p className="subtle" style={{ marginTop: '0.5rem' }}>
          Presets applied automatically:&nbsp;
          {Object.entries(spec.presets).map(([k, v], i) => (
            <span key={k}>
              {i > 0 ? ', ' : ''}<code>{k}={v}</code>
            </span>
          ))}
        </p>
      )}

      <details style={{ marginTop: '0.5rem' }}>
        <summary style={{ cursor: 'pointer', color: '#666' }}>Advanced extras</summary>
        <p className="subtle" style={{ marginBottom: 4 }}>
          Extra <code>key=value</code> pairs, one per line, for backend options not listed above.
        </p>
        <textarea
          value={p.storeExtras}
          onChange={ev => p.onStoreExtrasChange(ev.target.value)}
          rows={6}
          placeholder={"# example:\n# s3_url_compatibility_mode=true"}
          style={{ width: '100%', fontFamily: 'monospace' }}
        />
      </details>
    </fieldset>
  );
}
