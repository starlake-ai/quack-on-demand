import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { PROVIDER_FIELDS, PROVIDER_LABELS } from '../api/authProviders';
import type { AuthProvider } from '../api/types';

export default function CreateTenant() {
  const nav = useNavigate();
  const [name, setName]         = useState('');
  const [provider, setProvider] = useState<AuthProvider>('db');
  const [config, setConfig]     = useState<Record<string, string>>({});
  const [err, setErr]           = useState<string | null>(null);

  function setConfigField(key: string, value: string) {
    setConfig(prev => ({ ...prev, [key]: value }));
  }

  function pickProvider(p: AuthProvider) {
    setProvider(p);
    setConfig({});
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    try {
      await api.createTenant({ name, authProvider: provider, authConfig: config });
      nav(`/tenant/${name}`);
    } catch (e) { setErr(String(e)); }
  }

  const fields = PROVIDER_FIELDS[provider];
  const formReady = name.length > 0 &&
    fields.every(f => (config[f.key] ?? '').trim().length > 0);

  return (
    <form onSubmit={submit}>
      <h2>Create tenant</h2>
      <p style={{ color: '#666', marginTop: 0 }}>
        A tenant is a logical owner. Storage details (metastore, data path,
        object store) are configured per-database, after the tenant is
        created — pick "Add database" on the tenant page.
      </p>
      {err && <p style={{ color: 'red' }}>{err}</p>}

      <fieldset>
        <legend>Identity</legend>
        <label>Name<br/>
          <input value={name} onChange={e => setName(e.target.value)} required />
        </label>
      </fieldset>

      <fieldset style={{ marginTop: '0.75rem' }}>
        <legend>Authentication</legend>
        <p className="subtle" style={{ marginTop: 0 }}>
          Every user in this tenant authenticates through the chosen provider.
          Pick <code>db</code> to manage users with username/password here, or
          an OIDC provider to delegate to an external identity provider.
        </p>
        <label>Provider<br/>
          <select value={provider} onChange={ev => pickProvider(ev.target.value as AuthProvider)}>
            {(Object.keys(PROVIDER_LABELS) as AuthProvider[]).map(k => (
              <option key={k} value={k}>{PROVIDER_LABELS[k]}</option>
            ))}
          </select>
        </label>
        {fields.length === 0 ? (
          <p className="subtle" style={{ marginTop: '0.5rem' }}>
            <code>db</code> needs no extra config — the username on the user
            record IS the identity.
          </p>
        ) : (
          <div className="row" style={{ gap: 12, flexWrap: 'wrap', marginTop: '0.5rem' }}>
            {fields.map(f => (
              <label key={f.key} style={{ flex: '1 1 280px' }}>
                {f.label}<br/>
                <input
                  value={config[f.key] ?? ''}
                  onChange={ev => setConfigField(f.key, ev.target.value)}
                  placeholder={f.placeholder}
                  style={{ width: '100%' }}
                  required
                />
              </label>
            ))}
          </div>
        )}
      </fieldset>

      <div className="row" style={{ gap: 8, marginTop: '0.75rem' }}>
        <button type="submit" disabled={!formReady}>Create</button>
        <button type="button" className="cancel-button" onClick={() => nav(-1)}>
          Cancel
        </button>
      </div>
    </form>
  );
}
