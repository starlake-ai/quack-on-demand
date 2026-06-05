import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { TenantResponse, UserResponse } from '../api/types';
import EffectivePermsCard from './EffectivePermsCard';

/** Users tab on the /users page. Renders the user table for the
  * selected tenant (or every user when `tenant === null`), with inline
  * create + per-row edit / delete / effective drilldown actions.
  *
  * The create form adapts to the tenant's auth provider:
  *   - `db` tenants: full form (username + password). Submitted user
  *     can authenticate immediately via Basic / DB-backed login.
  *   - OIDC tenants (`keycloak` / `google` / `azure` / `aws`): no
  *     password field. The form becomes a "Pre-provision" affordance --
  *     it creates a `qodstate_user` row so the admin can attach roles
  *     / groups / pool grants before the user's first handshake; the
  *     IdP still owns authentication, and the row will be reused (not
  *     duplicated) when the user actually signs in.
  *
  * `tenants` is passed in by the parent page so the tenant `<select>`
  * stays in sync with the page-level filter without a second fetch. */
export default function UserSection({
  tenant,
  tenants,
}: {
  tenant:  string | null;
  tenants: TenantResponse[];
}) {
  const [rows, setRows]       = useState<UserResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);
  const [adding, setAdding]   = useState(false);
  // Which user's permissions card is currently expanded under their row.
  // Toggled by clicking the username; only one card is open at a time.
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // New-user form state. `newTenant` is the dropdown selection;
  // it defaults to the page-level filter and can be overridden when
  // creating on the "(all)" view. Empty string sentinel = superuser.
  const SUPERUSER = '';
  const [newUsername, setNewUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newRole,     setNewRole]     = useState<'user' | 'admin'>('user');
  const [newTenant,   setNewTenant]   = useState<string>(tenant ?? SUPERUSER);

  // Per-row edit (password rotation only). Edit form opens inline
  // below the table row. Skipped for OIDC-tenant users -- the IdP
  // owns password rotation there.
  const [editingId, setEditingId]     = useState<string | null>(null);
  const [editPassword, setEditPassword] = useState('');

  // Resolve the selected new-tenant to its provider so the form can
  // adapt. Superuser always uses the db backend (no IdP routing).
  const newTenantRow = tenants.find(t => t.name === newTenant);
  const newTenantIsDb = newTenant === SUPERUSER || newTenantRow?.authProvider === 'db';

  // Same lookup but for the rows currently displayed.
  const tenantProviderOf = (tenantName: string | null): string =>
    tenantName ? (tenants.find(t => t.name === tenantName)?.authProvider ?? 'db') : 'db';

  function reload() {
    setError(null);
    api.listUsers(tenant ?? undefined)
      .then(r => setRows(r.users))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => {
    setNewTenant(tenant ?? SUPERUSER);
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);

  async function handleCreate(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    try {
      await api.createUser({
        tenant:   newTenant === SUPERUSER ? null : newTenant,
        username: newUsername.trim(),
        // For OIDC tenants we pre-provision with a random throwaway
        // password -- the server still requires SOMETHING (DB column
        // is NOT NULL) but the user will never authenticate via Basic
        // against it. The IdP is authoritative.
        password: newTenantIsDb ? newPassword : crypto.randomUUID(),
        role:     newRole,
      });
      setAdding(false);
      setNewUsername(''); setNewPassword(''); setNewRole('user');
      reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleUpdate(id: string) {
    setError(null);
    try {
      await api.updateUser({ id, password: editPassword || null });
      setEditingId(null); setEditPassword('');
      reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(u: UserResponse) {
    if (!confirm(`Delete user '${u.username}'${u.tenant ? ` from tenant '${u.tenant}'` : ' (superuser)'}?`)) return;
    setError(null);
    try {
      await api.deleteUser({ id: u.id });
      reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  // Choose the create-button label based on tenant provider. "Add" for
  // db tenants (the typical full-create), "Pre-provision" for OIDC
  // tenants where the user actually comes from the IdP.
  const createLabel = newTenantIsDb ? '+ New user' : '+ Pre-provision user';

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Users</div>
        {!adding && (
          <button onClick={() => setAdding(true)}>{createLabel}</button>
        )}
      </div>
      {error && <div className="login-err">Error: {error}</div>}
      {rows.length === 0 ? (
        <div className="empty">(no users)</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Username</th>
              <th>Tenant</th>
              <th>Roles</th>
              <th>Groups</th>
              <th>Pool grants</th>
              <th>Enabled</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.flatMap(u => {
              const rowProvider = tenantProviderOf(u.tenant);
              const allowEdit = rowProvider === 'db' || u.tenant === null;
              const isExpanded = expandedId === u.id;
              const row = (
                <tr key={u.id}>
                  <td>
                    <button
                      type="button"
                      className="user-name-toggle"
                      aria-expanded={isExpanded}
                      title={isExpanded ? 'Hide effective permissions' : 'Show effective permissions'}
                      onClick={() => setExpandedId(isExpanded ? null : u.id)}
                    >
                      <span className="caret">{isExpanded ? '▾' : '▸'}</span>
                      <code>{u.username}</code>
                    </button>
                  </td>
                  <td>{u.tenant ? <code>{u.tenant}</code> : <em>(superuser)</em>}</td>
                  <td>{u.roles.length === 0 ? <span className="subtle">-</span> : u.roles.join(', ')}</td>
                  <td>{u.groups.length === 0 ? <span className="subtle">-</span> : u.groups.join(', ')}</td>
                  <td>{u.poolGrants.length === 0 ? <span className="subtle">-</span> : u.poolGrants.join(', ')}</td>
                  <td>{u.enabled ? '✓' : '✗'}</td>
                  <td>
                    {allowEdit ? (
                      <button onClick={() => { setEditingId(u.id); setEditPassword(''); }}>Edit</button>
                    ) : (
                      <button
                        disabled
                        title={`Password is managed by the tenant's ${rowProvider} provider`}
                      >Edit</button>
                    )}
                    {' '}
                    <button className="danger" onClick={() => handleDelete(u)}>Delete</button>
                  </td>
                </tr>
              );
              const perms = isExpanded ? [(
                <tr key={u.id + '-perms'} className="expanded-row">
                  <td colSpan={7}>
                    <EffectivePermsCard userId={u.id} />
                  </td>
                </tr>
              )] : [];
              if (editingId !== u.id) return [row, ...perms];
              const edit = (
                <tr key={u.id + '-edit'} className="edit-row">
                  <td colSpan={7}>
                    <form
                      onSubmit={ev => { ev.preventDefault(); void handleUpdate(u.id); }}
                      className="row"
                      style={{ gap: 8, alignItems: 'center' }}
                    >
                      <label>
                        New password
                        <input
                          type="password"
                          value={editPassword}
                          onChange={ev => setEditPassword(ev.target.value)}
                          placeholder="(leave blank to leave unchanged)"
                          style={{ marginLeft: 6, width: 280 }}
                        />
                      </label>
                      <button type="submit">Save</button>
                      <button type="button" onClick={() => { setEditingId(null); setEditPassword(''); }}>Cancel</button>
                    </form>
                  </td>
                </tr>
              );
              return [row, ...perms, edit];
            })}
          </tbody>
        </table>
      )}

      {adding && (
        <form onSubmit={handleCreate} style={{ marginTop: '0.75rem' }}>
          <fieldset>
            <legend>{createLabel}</legend>
            {!newTenantIsDb && (
              <p className="subtle" style={{ marginTop: 0 }}>
                <code>{newTenantRow?.authProvider}</code> tenant: the IdP owns
                authentication. This form creates a local <code>qodstate_user</code>
                row so role / group / pool grants can be attached now; the
                same row is reused on the user's first sign-in.
              </p>
            )}
            <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
              <label>
                Username
                <input value={newUsername} onChange={ev => setNewUsername(ev.target.value)} required />
              </label>
              {newTenantIsDb && (
                <label>
                  Password
                  <input
                    type="password"
                    value={newPassword}
                    onChange={ev => setNewPassword(ev.target.value)}
                    required
                  />
                </label>
              )}
              <label>
                Tenant
                <select value={newTenant} onChange={ev => setNewTenant(ev.target.value)}>
                  <option value={SUPERUSER}>(superuser)</option>
                  {tenants.map(t => (
                    <option key={t.name} value={t.name}>
                      {t.name} — {t.authProvider}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Role label
                <select value={newRole} onChange={ev => setNewRole(ev.target.value as 'user' | 'admin')}>
                  <option value="user">user</option>
                  <option value="admin">admin</option>
                </select>
              </label>
            </div>
            <div className="row" style={{ gap: 8, marginTop: '0.5rem' }}>
              <button type="submit">{newTenantIsDb ? 'Create' : 'Pre-provision'}</button>
              <button type="button" onClick={() => { setAdding(false); setError(null); }}>Cancel</button>
            </div>
          </fieldset>
        </form>
      )}

    </div>
  );
}
