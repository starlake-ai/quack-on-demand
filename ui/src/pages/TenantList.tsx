import { FormEvent, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { PROVIDER_FIELDS, PROVIDER_LABELS } from '../api/authProviders';
import type { AuthProvider, TenantResponse } from '../api/types';
import { DeleteIcon } from '../components/Icons';

export default function TenantList() {
  const [tenants, setTenants] = useState<TenantResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);

  // "+ New tenant" modal state.
  const [adding, setAdding]     = useState(false);
  const [newName, setNewName]   = useState('');
  const [newProvider, setNewProvider] = useState<AuthProvider>('db');
  const [newConfig, setNewConfig]     = useState<Record<string, string>>({});
  const [createErr, setCreateErr]     = useState<string | null>(null);

  function reload() {
    return api.listTenants()
      .then(r => setTenants(r.tenants))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => { void reload(); }, []);

  function openCreate() {
    setNewName(''); setNewProvider('db'); setNewConfig({}); setCreateErr(null);
    setAdding(true);
  }
  function closeCreate() { setAdding(false); setCreateErr(null); }

  function pickProvider(p: AuthProvider) {
    setNewProvider(p);
    setNewConfig({});
  }

  async function submitCreate(ev: FormEvent) {
    ev.preventDefault();
    setCreateErr(null);
    try {
      await api.createTenant({ name: newName, authProvider: newProvider, authConfig: newConfig });
      setAdding(false);
      await reload();
    } catch (e) {
      setCreateErr(e instanceof ApiError ? e.message : String(e));
    }
  }

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

  async function handleDelete(t: TenantResponse) {
    setError(null);
    if (!window.confirm(
      `Delete tenant "${t.name}"?\n\n` +
      `This is irreversible. Tenants with active pools cannot be deleted ` +
      `until the pools are stopped.`
    )) return;
    try {
      await api.deleteTenant({ name: t.name });
      await reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  const fields = PROVIDER_FIELDS[newProvider];
  const createReady = newName.length > 0 &&
    fields.every(f => f.optional || (newConfig[f.key] ?? '').trim().length > 0);

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;

  const createModal = adding && (
    <div
      className="modal-backdrop"
      onClick={closeCreate}
      style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
        display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
        zIndex: 100, paddingTop: '4rem',
      }}
    >
      <div
        className="modal card"
        onClick={ev => ev.stopPropagation()}
        style={{ width: '90%', maxWidth: 560 }}
      >
        <div className="card-title">New tenant</div>
        <p className="subtle" style={{ marginTop: 0 }}>
          A tenant is a logical owner. Storage details (metastore, data path,
          object store) are configured per-database, after the tenant is
          created.
        </p>
        {createErr && <p style={{ color: 'var(--bad)' }}>{createErr}</p>}
        <form onSubmit={submitCreate}>
          <label>
            Name
            <input value={newName} onChange={ev => setNewName(ev.target.value)} required />
          </label>
          <label>
            Auth provider
            <select value={newProvider} onChange={ev => pickProvider(ev.target.value as AuthProvider)}>
              {(Object.keys(PROVIDER_LABELS) as AuthProvider[]).map(k => (
                <option key={k} value={k}>{PROVIDER_LABELS[k]}</option>
              ))}
            </select>
          </label>
          {fields.length === 0 ? (
            <p className="subtle" style={{ marginTop: 0 }}>
              <code>db</code> needs no extra config — the username on the user
              record IS the identity.
            </p>
          ) : (
            fields.map(f => (
              <label key={f.key}>
                {f.label}
                <input
                  value={newConfig[f.key] ?? ''}
                  onChange={ev => setNewConfig(prev => ({ ...prev, [f.key]: ev.target.value }))}
                  placeholder={f.placeholder}
                  required={!f.optional}
                />
              </label>
            ))
          )}
          <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
            <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={closeCreate}>Cancel</button>
            <button type="submit" style={{ minWidth: '7rem' }} disabled={!createReady}>Create</button>
          </div>
        </form>
      </div>
    </div>
  );

  if (tenants.length === 0) return (
    <div>
      <p>No tenants yet.{' '}
        <button type="button" className="link-button" onClick={openCreate}>Create one</button>.
      </p>
      {createModal}
    </div>
  );

  return (
    <div>
      <header style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h2>Tenants</h2>
        <button type="button" className="link-button" onClick={openCreate}>+ New tenant</button>
      </header>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th align="left">Name</th>
            <th align="right">Pools</th>
            <th align="right">Enabled</th>
            <th className="actions">Actions</th>
          </tr>
        </thead>
        <tbody>
          {tenants.map(t => (
            <tr key={t.name} style={{ borderTop: '1px solid #eee', opacity: t.disabled ? 0.55 : 1 }}>
              <td>
                <Link to={`/tenant/${t.name}`}>{t.name}</Link>
                {t.disabled && <span className="subtle"> (disabled)</span>}
                <div className="subtle" style={{ fontSize: '0.85em' }}>
                  <code>{t.id}</code>
                </div>
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
              <td className="actions">
                <button
                  type="button"
                  className="icon-btn danger"
                  onClick={() => void handleDelete(t)}
                  aria-label={`Delete tenant ${t.name}`}
                  disabled={t.pools.length > 0}
                  title={t.pools.length > 0
                    ? `Stop the ${t.pools.length} active pool(s) before deleting.`
                    : 'Permanently delete this tenant.'}
                >
                  <DeleteIcon />
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {createModal}
    </div>
  );
}
