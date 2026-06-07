import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import { PROVIDER_FIELDS, PROVIDER_LABELS } from '../api/authProviders';
import type { AuthProvider, TenantResponse } from '../api/types';

/** Auth-provider tab on the TenantDetail page. Shows the tenant's
  * current provider + config and lets the admin swap it. Users / roles /
  * groups under the tenant are unaffected by this swap -- it's only a
  * change to HOW the tenant's users authenticate.
  *
  * `db` config is intentionally empty: the username on `qodstate_user`
  * IS the identity, so there's nothing to capture beyond the provider
  * choice. */
export default function AuthProviderSection({ tenantName }: { tenantName: string }) {
  const [tenant, setTenant]   = useState<TenantResponse | null>(null);
  const [editing, setEditing] = useState(false);
  const [error, setError]     = useState<string | null>(null);

  // Edit-form state. Initialized from the current tenant when "Edit" is clicked.
  const [provider, setProvider] = useState<AuthProvider>('db');
  const [config, setConfig]     = useState<Record<string, string>>({});

  function reload() {
    setError(null);
    api.listTenants()
      .then(r => {
        const t = r.tenants.find(x => x.name === tenantName);
        if (!t) setError(`tenant '${tenantName}' not found`);
        else setTenant(t);
      })
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => { reload(); /* eslint-disable-next-line */ }, [tenantName]);

  function openEditor() {
    if (!tenant) return;
    setProvider(tenant.authProvider);
    setConfig({ ...tenant.authConfig });
    setEditing(true);
    setError(null);
  }

  function setConfigField(key: string, value: string) {
    setConfig(prev => ({ ...prev, [key]: value }));
  }

  function pickProvider(p: AuthProvider) {
    setProvider(p);
    setConfig({}); // discard prior provider's fields on switch
  }

  async function save(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    try {
      const updated = await api.setTenantAuth({
        name: tenantName, authProvider: provider, authConfig: config,
      });
      setTenant(updated);
      setEditing(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  if (!tenant) {
    return (
      <div className="card">
        <div className="card-title">Auth provider</div>
        {error ? <div className="login-err">Error: {error}</div> : <div className="loading">Loading…</div>}
      </div>
    );
  }

  const fields = PROVIDER_FIELDS[editing ? provider : tenant.authProvider];
  const activeConfig = editing ? config : tenant.authConfig;
  const formReady = fields.every(f => (config[f.key] ?? '').trim().length > 0);

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Auth provider</div>
        {!editing && (
          <button onClick={openEditor}>Edit</button>
        )}
      </div>
      <p className="subtle">
        Every user in this tenant authenticates through the chosen provider.
        Changing the provider does NOT delete the tenant's users, roles, or
        groups; for OIDC providers, future handshakes will be matched
        against the new `iss` claim instead of the directory.
      </p>
      {error && <div className="login-err">Error: {error}</div>}

      {!editing ? (
        <table>
          <tbody>
            <tr>
              <th style={{ textAlign: 'left', width: 160 }}>Provider</th>
              <td><code>{tenant.authProvider}</code> <span className="subtle">— {PROVIDER_LABELS[tenant.authProvider]}</span></td>
            </tr>
            {fields.length === 0 ? (
              <tr>
                <th style={{ textAlign: 'left' }}>Config</th>
                <td className="subtle">(none — the username on each user record IS the identity)</td>
              </tr>
            ) : (
              fields.map(f => (
                <tr key={f.key}>
                  <th style={{ textAlign: 'left' }}>{f.label}</th>
                  <td><code>{activeConfig[f.key] ?? <span className="subtle">(not set)</span>}</code></td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      ) : (
        <form onSubmit={save}>
          <label>
            Provider<br/>
            <select
              value={provider}
              onChange={ev => pickProvider(ev.target.value as AuthProvider)}
            >
              {(Object.keys(PROVIDER_LABELS) as AuthProvider[]).map(k => (
                <option key={k} value={k}>{PROVIDER_LABELS[k]}</option>
              ))}
            </select>
          </label>
          {fields.length === 0 ? (
            <p className="subtle" style={{ marginTop: '0.5rem' }}>
              <code>db</code> needs no extra config.
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
          <div className="row" style={{ gap: 8, marginTop: '0.75rem' }}>
            <button type="submit" disabled={!formReady}>Save</button>
            <button type="button" className="cancel-button" onClick={() => { setEditing(false); setError(null); }}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  );
}
