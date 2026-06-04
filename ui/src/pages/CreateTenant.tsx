import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { AuthProvider } from '../api/types';

/** Provider-specific config field surfaced in the tenant create form.
  * The map flattens to one `Record<string, string>` and is sent as
  * `authConfig`. `db` has zero fields -- the username on
  * `qodstate_user` IS the identity. */
interface ProviderField {
  key:         string;
  label:       string;
  placeholder: string;
}

const PROVIDER_FIELDS: Record<AuthProvider, ProviderField[]> = {
  db:       [],
  keycloak: [
    { key: 'issuer', label: 'Issuer URL',
      placeholder: 'https://keycloak.example.com/realms/<realm>' },
    { key: 'realm',  label: 'Realm name',     placeholder: 'tpch' },
  ],
  google:   [
    { key: 'issuer', label: 'Issuer URL',     placeholder: 'accounts.google.com' },
    { key: 'hd',     label: 'Workspace domain', placeholder: 'example.com' },
  ],
  azure:    [
    { key: 'issuer',   label: 'Issuer URL',
      placeholder: 'https://login.microsoftonline.com/<tenant-id>/v2.0' },
    { key: 'tenantId', label: 'AD tenant id',  placeholder: '<directory-id>' },
  ],
  aws:      [
    { key: 'issuer',     label: 'Issuer URL',
      placeholder: 'https://cognito-idp.<region>.amazonaws.com/<userpool>' },
    { key: 'userPoolId', label: 'User pool id', placeholder: '<userpool-id>' },
  ],
};

export default function CreateTenant() {
  const nav = useNavigate();
  const [name, setName]             = useState('');
  const [provider, setProvider]     = useState<AuthProvider>('db');
  const [config, setConfig]         = useState<Record<string, string>>({});
  const [err, setErr]               = useState<string | null>(null);

  function setConfigField(key: string, value: string) {
    setConfig(prev => ({ ...prev, [key]: value }));
  }

  function pickProvider(p: AuthProvider) {
    setProvider(p);
    setConfig({}); // discard prior provider's fields on switch
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    try {
      await api.createTenant({
        name,
        authProvider: provider,
        authConfig:   config,
      });
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
        <label>
          Name<br/>
          <input value={name} onChange={e => setName(e.target.value)} required />
        </label>
      </fieldset>

      <fieldset style={{ marginTop: '0.75rem' }}>
        <legend>Authentication</legend>
        <p className="subtle" style={{ marginTop: 0 }}>
          Every user in this tenant authenticates through the chosen provider.
          Pick <code>db</code> to manage users with username/password against
          the manager's directory; pick an OIDC provider to delegate to an
          external identity provider.
        </p>
        <label>
          Provider<br/>
          <select
            value={provider}
            onChange={ev => pickProvider(ev.target.value as AuthProvider)}
          >
            <option value="db">db (username + password, managed here)</option>
            <option value="keycloak">keycloak (OIDC)</option>
            <option value="google">google (OIDC)</option>
            <option value="azure">azure (OIDC)</option>
            <option value="aws">aws (Cognito, OIDC)</option>
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

      <button type="submit" disabled={!formReady} style={{ marginTop: '0.75rem' }}>
        Create
      </button>
    </form>
  );
}