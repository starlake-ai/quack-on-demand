import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { TenantResponse } from '../api/types';

export default function TenantList() {
  const [tenants, setTenants] = useState<TenantResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);

  function reload() {
    return api.listTenants()
      .then(r => setTenants(r.tenants))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => { void reload(); }, []);

  async function toggle(t: TenantResponse) {
    setError(null);
    const next = !t.disabled;
    // Optimistic update so the toggle feels instant.
    setTenants(curr => curr.map(x => x.name === t.name ? { ...x, disabled: next } : x));
    try {
      await api.setTenantDisabled({ name: t.name, disabled: next });
    } catch (e) {
      // Roll back on failure.
      setTenants(curr => curr.map(x => x.name === t.name ? { ...x, disabled: !next } : x));
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (tenants.length === 0) return (
    <p>No tenants yet. <Link to="/create-tenant">Create one</Link>.</p>
  );

  return (
    <div>
      <header style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h2>Tenants</h2>
        <Link to="/create-tenant">+ New tenant</Link>
      </header>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th align="left">Name</th>
            <th align="right">Pools</th>
            <th align="right">Enabled</th>
          </tr>
        </thead>
        <tbody>
          {tenants.map(t => (
            <tr key={t.name} style={{ borderTop: '1px solid #eee', opacity: t.disabled ? 0.55 : 1 }}>
              <td>
                <Link to={`/tenant/${t.name}`}>{t.name}</Link>
                {t.disabled && <span className="subtle"> (disabled)</span>}
              </td>
              <td align="right">{t.pools.length}</td>
              <td align="right">
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={!t.disabled}
                    onChange={() => void toggle(t)}
                    aria-label={`Toggle tenant ${t.name} enabled`}
                  />
                  <span className="subtle">{t.disabled ? 'off' : 'on'}</span>
                </label>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
