import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { UserResponse } from '../api/types';
import EffectivePermsModal from './EffectivePermsModal';

/** Users tab on the /users page. Renders the user table for the selected
  * tenant (or every user when `tenant === null`), with inline create +
  * per-row edit / delete / effective drilldown actions. */
export default function UserSection({ tenant }: { tenant: string | null }) {
  const [rows, setRows]       = useState<UserResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);
  const [adding, setAdding]   = useState(false);
  const [effectiveFor, setEffectiveFor] = useState<string | null>(null);

  // New-user form state. `tenant` defaults to the selected tenant, but
  // the admin can leave it blank to create a superuser when on the
  // "(all)" filter.
  const [newUsername, setNewUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newRole,     setNewRole]     = useState('user');
  const [newTenant,   setNewTenant]   = useState<string | null>(tenant ?? null);

  // Per-row edit (password rotation only). Edit form opens inline below
  // the table row.
  const [editingId, setEditingId]     = useState<string | null>(null);
  const [editPassword, setEditPassword] = useState('');

  function reload() {
    setError(null);
    api.listUsers(tenant ?? undefined)
      .then(r => setRows(r.users))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => {
    setNewTenant(tenant ?? null);
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);

  async function handleCreate(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    try {
      await api.createUser({
        tenant:   newTenant && newTenant.length > 0 ? newTenant : null,
        username: newUsername.trim(),
        password: newPassword,
        role:     newRole.trim() || 'user',
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

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Users</div>
        {!adding && (
          <button onClick={() => setAdding(true)}>+ New user</button>
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
              const row = (
                <tr key={u.id}>
                  <td><code>{u.username}</code></td>
                  <td>{u.tenant ? <code>{u.tenant}</code> : <em>(superuser)</em>}</td>
                  <td>{u.roles.length === 0 ? <span className="subtle">-</span> : u.roles.join(', ')}</td>
                  <td>{u.groups.length === 0 ? <span className="subtle">-</span> : u.groups.join(', ')}</td>
                  <td>{u.poolGrants.length === 0 ? <span className="subtle">-</span> : u.poolGrants.join(', ')}</td>
                  <td>{u.enabled ? '✓' : '✗'}</td>
                  <td>
                    <button onClick={() => setEffectiveFor(u.id)}>Effective…</button>
                    {' '}
                    <button onClick={() => { setEditingId(u.id); setEditPassword(''); }}>Edit</button>
                    {' '}
                    <button className="danger" onClick={() => handleDelete(u)}>Delete</button>
                  </td>
                </tr>
              );
              if (editingId !== u.id) return [row];
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
              return [row, edit];
            })}
          </tbody>
        </table>
      )}

      {adding && (
        <form
          onSubmit={handleCreate}
          style={{ marginTop: '0.75rem' }}
        >
          <fieldset>
            <legend>New user</legend>
            <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
              <label>
                Username
                <input value={newUsername} onChange={ev => setNewUsername(ev.target.value)} required />
              </label>
              <label>
                Password
                <input type="password" value={newPassword} onChange={ev => setNewPassword(ev.target.value)} required />
              </label>
              <label>
                Tenant
                <input
                  value={newTenant ?? ''}
                  onChange={ev => setNewTenant(ev.target.value || null)}
                  placeholder="(blank = superuser)"
                />
              </label>
              <label>
                Role label
                <input
                  value={newRole}
                  onChange={ev => setNewRole(ev.target.value)}
                  placeholder="user"
                />
              </label>
            </div>
            <div className="row" style={{ gap: 8, marginTop: '0.5rem' }}>
              <button type="submit">Create</button>
              <button type="button" onClick={() => { setAdding(false); setError(null); }}>Cancel</button>
            </div>
          </fieldset>
        </form>
      )}

      {effectiveFor && (
        <EffectivePermsModal
          userId={effectiveFor}
          onClose={() => setEffectiveFor(null)}
        />
      )}
    </div>
  );
}
