import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import type { IdentityResponse } from '../api/types';

// Provider presets. Picking one fills both inputs so the admin only
// has to substitute the variable bits (realm name, domain, ...).
type ProviderKey = 'database' | 'keycloak' | 'google' | 'azure' | 'aws' | 'custom';

interface ProviderPreset {
  label:      string;
  issuer:     string;
  externalId: string;
  hint:       string;
}

const PRESETS: Record<ProviderKey, ProviderPreset> = {
  database: {
    label:      'Database user',
    issuer:     'db',
    externalId: '',
    hint:       "Issuer is the literal 'db'; external id is the username from slkstate_user. One row per allowed user.",
  },
  keycloak: {
    label:      'Keycloak realm',
    issuer:     'https://keycloak.example.com/realms/<realm>',
    externalId: '<realm>',
    hint:       'Issuer = full realm URL (matches the JWT iss). External id = the realm name carried in the configured tenant claim. One row per realm.',
  },
  google: {
    label:      'Google Workspace domain',
    issuer:     'accounts.google.com',
    externalId: '<example.com>',
    hint:       'External id = the workspace domain Google returns in the hd claim. One row per domain.',
  },
  azure: {
    label:      'Azure AD tenant',
    issuer:     'https://login.microsoftonline.com/<tenant-id>/v2.0',
    externalId: '<tenant-id>',
    hint:       'External id = the Azure AD tenant (directory) id. One row per AD tenant.',
  },
  aws: {
    label:      'AWS Cognito user pool',
    issuer:     'https://cognito-idp.<region>.amazonaws.com/<userpool-id>',
    externalId: '<userpool-id>',
    hint:       'External id = the Cognito user-pool id. One row per pool.',
  },
  custom: {
    label:      'Custom (free text)',
    issuer:     '',
    externalId: '',
    hint:       'Use this when none of the providers above match. The issuer/externalId pair must exactly equal what your edge-side TenantResolver receives.',
  },
};

export default function IdentitySection({ tenantId }: { tenantId: string }) {
  const { username } = useAuth();
  const [rows, setRows]   = useState<IdentityResponse[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  // Most useful default for the database-user provider: the currently
  // signed-in admin. Falls back to "" when auth is disabled.
  const defaultDbExternalId = username ?? '';

  const [provider, setProvider]     = useState<ProviderKey>('database');
  const [issuer, setIssuer]         = useState(PRESETS.database.issuer);
  const [externalId, setExternalId] = useState(defaultDbExternalId);

  function pickProvider(p: ProviderKey) {
    setProvider(p);
    setIssuer(PRESETS[p].issuer);
    setExternalId(p === 'database' ? defaultDbExternalId : PRESETS[p].externalId);
  }

  function openForm() {
    pickProvider('database');
    setAdding(true);
  }

  const reload = () =>
    api.listIdentities(tenantId)
      .then(r => setRows(r.identities))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));

  useEffect(() => { void reload(); }, [tenantId]);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api.createIdentity({
        tenantId,
        issuer:     issuer.trim(),
        externalId: externalId.trim(),
      });
      setAdding(false);
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(id: string, label: string) {
    if (!confirm(`Revoke identity '${label}' from this tenant?`)) return;
    setError(null);
    try {
      await api.deleteIdentity({ id });
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  return (
    <div className="card">
      <div className="card-title">Tenant identities</div>
      <p className="subtle">
        Verified-identity mappings consulted at every FlightSQL connect.
        Pick a provider below and fill the variable bits; the manager
        resolves <code>(issuer, externalId)</code> to this tenant.
      </p>
      {error && <div className="login-err">Error: {error}</div>}
      {rows.length === 0 ? (
        <div className="empty">(no identities yet)</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Issuer</th>
              <th>External id</th>
              <th>Created</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.id}>
                <td><code>{r.issuer}</code></td>
                <td><code>{r.externalId}</code></td>
                <td className="subtle">{r.createdAt}</td>
                <td>
                  <button
                    className="danger"
                    onClick={() => handleDelete(r.id, `${r.issuer}/${r.externalId}`)}
                  >Revoke</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {!adding ? (
        <button onClick={openForm} style={{ marginTop: '0.5rem' }}>+ Allow identity</button>
      ) : (
        <form onSubmit={handleCreate} style={{ marginTop: '0.75rem' }}>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <label>
              Provider
              <select
                value={provider}
                onChange={ev => pickProvider(ev.target.value as ProviderKey)}
                style={{ minWidth: 200 }}
              >
                {(Object.entries(PRESETS) as [ProviderKey, ProviderPreset][]).map(([k, p]) => (
                  <option key={k} value={k}>{p.label}</option>
                ))}
              </select>
            </label>
            <label>
              Issuer
              <input
                value={issuer}
                onChange={ev => setIssuer(ev.target.value)}
                placeholder={PRESETS[provider].issuer || 'db or OIDC iss URL'}
                required
                style={{ width: 380 }}
              />
            </label>
            <label>
              External id
              <input
                value={externalId}
                onChange={ev => setExternalId(ev.target.value)}
                placeholder={PRESETS[provider].externalId || 'username, realm, or hd domain'}
                required
                style={{ width: 260 }}
              />
            </label>
          </div>
          <p className="subtle" style={{ marginTop: 6 }}>{PRESETS[provider].hint}</p>
          <div className="row" style={{ gap: 8, marginTop: '0.5rem' }}>
            <button type="submit">Add</button>
            <button type="button" onClick={() => { setAdding(false); setError(null); }}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  );
}
