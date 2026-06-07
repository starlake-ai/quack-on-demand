import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type {
  GroupResponse, PoolPermissionResponse, RoleResponse, UserResponse,
} from '../api/types';

/** Groups tab on the /users page. Three-pane:
  *   - left:   groups list (per-tenant) + "+ New group"
  *   - middle: members of the selected group + role memberships
  *   - right:  pool grants attached to the selected group
  *
  * Membership add/remove is intentionally minimalist -- pick a user or
  * role from a dropdown of every user/role in the tenant and submit.
  * Tab-completion in a real UI is left for a later iteration. */
export default function GroupSection({ tenant }: { tenant: string | null }) {
  const [groups, setGroups]   = useState<GroupResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);
  const [adding, setAdding]   = useState(false);

  const [selected, setSelected] = useState<GroupResponse | null>(null);

  // Members + role memberships of the selected group are derived by
  // listing every user/role in the tenant and filtering by the
  // /user/{id}/effective view + role membership lookups. To keep this
  // lightweight we use /user/list (which already reports each user's
  // groups by name) + /role/list and trim client-side.
  const [users, setUsers]     = useState<UserResponse[]>([]);
  const [roles, setRoles]     = useState<RoleResponse[]>([]);
  const [poolPerms, setPoolPerms] = useState<PoolPermissionResponse[]>([]);

  // New-group form
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');

  // Inline membership add controls
  const [addUserId, setAddUserId]     = useState('');
  const [addRoleId, setAddRoleId]     = useState('');
  const [grantPoolId, setGrantPoolId] = useState<string>(''); // '' = wildcard

  function reloadGroups() {
    setError(null);
    if (!tenant) { setGroups([]); setSelected(null); return; }
    api.listGroups(tenant)
      .then(r => setGroups(r.groups))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  function reloadTenantContext() {
    if (!tenant) return;
    api.listUsers(tenant).then(r => setUsers(r.users)).catch(() => {});
    api.listRoles(tenant).then(r => setRoles(r.roles)).catch(() => {});
  }

  function reloadGroupPools(g: GroupResponse) {
    api.listPoolPermissions({ groupId: g.id })
      .then(r => setPoolPerms(r.permissions))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => { reloadGroups(); reloadTenantContext();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);

  useEffect(() => {
    if (!selected) { setPoolPerms([]); return; }
    reloadGroupPools(selected);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected?.id]);

  async function handleCreate(ev: React.FormEvent) {
    ev.preventDefault();
    if (!tenant) return;
    setError(null);
    try {
      const created = await api.createGroup({
        tenant, name: newName.trim(), description: newDesc.trim() || null,
      });
      setAdding(false); setNewName(''); setNewDesc('');
      reloadGroups();
      setSelected(created);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(g: GroupResponse) {
    if (!confirm(`Delete group '${g.name}'?`)) return;
    setError(null);
    try {
      await api.deleteGroup({ id: g.id });
      if (selected?.id === g.id) setSelected(null);
      reloadGroups();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleAddUser(ev: React.FormEvent) {
    ev.preventDefault();
    if (!selected || !addUserId) return;
    setError(null);
    try {
      await api.addUserGroup({ userId: addUserId, groupId: selected.id });
      setAddUserId('');
      reloadTenantContext();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleRemoveUser(userId: string) {
    if (!selected) return;
    setError(null);
    try {
      await api.removeUserGroup({ userId, groupId: selected.id });
      reloadTenantContext();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleAddRole(ev: React.FormEvent) {
    ev.preventDefault();
    if (!selected || !addRoleId) return;
    setError(null);
    try {
      await api.addGroupRole({ groupId: selected.id, roleId: addRoleId });
      setAddRoleId('');
      // role list display refresh happens via /user/list role names;
      // the group->role wiring isn't directly visible to the user pane
      // so we just clear the input.
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleGrantPool(ev: React.FormEvent) {
    ev.preventDefault();
    if (!selected || !tenant) return;
    setError(null);
    try {
      await api.grantPoolPermission({
        tenant,
        poolId:  grantPoolId.trim() || null,
        groupId: selected.id,
      });
      setGrantPoolId('');
      reloadGroupPools(selected);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleRevokePool(p: PoolPermissionResponse) {
    if (!selected) return;
    if (!confirm(`Revoke pool grant ${p.poolId ?? '*'}?`)) return;
    setError(null);
    try {
      await api.revokePoolPermission({ id: p.id });
      reloadGroupPools(selected);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  if (!tenant) {
    return (
      <div className="card">
        <div className="card-title">Groups</div>
        <p className="subtle">Pick a tenant above to manage its groups.</p>
      </div>
    );
  }

  // Members of the selected group: every user whose `groups` column contains
  // the group name. Cheap client-side filter on the already-fetched user list.
  const members = selected
    ? users.filter(u => u.groups.includes(selected.name))
    : [];
  const nonMembers = selected
    ? users.filter(u => !u.groups.includes(selected.name))
    : [];

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Groups</div>
        {!adding && (
          <button onClick={() => setAdding(true)}>+ New group</button>
        )}
      </div>
      {error && <div className="login-err">Error: {error}</div>}

      <div className="row" style={{ alignItems: 'flex-start', gap: 16 }}>
        {/* Left: groups list */}
        <div style={{ flex: '0 0 220px' }}>
          {groups.length === 0 ? (
            <div className="empty">(no groups)</div>
          ) : (
            <table>
              <thead><tr><th>Name</th><th></th></tr></thead>
              <tbody>{groups.map(g => (
                <tr
                  key={g.id}
                  onClick={() => setSelected(g)}
                  style={{
                    cursor: 'pointer',
                    background: selected?.id === g.id ? 'rgba(0,0,0,0.04)' : undefined,
                  }}
                >
                  <td><code>{g.name}</code></td>
                  <td>
                    <button className="danger" onClick={ev => { ev.stopPropagation(); void handleDelete(g); }}>Delete</button>
                  </td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </div>

        {/* Middle: members + role memberships */}
        <div style={{ flex: 1 }}>
          {!selected ? (
            <div className="subtle">(select a group)</div>
          ) : (
            <>
              <h4 style={{ marginTop: 0 }}>Members of <code>{selected.name}</code></h4>
              {members.length === 0 ? <div className="empty">(none)</div> : (
                <table>
                  <thead><tr><th>Username</th><th></th></tr></thead>
                  <tbody>{members.map(u => (
                    <tr key={u.id}>
                      <td><code>{u.username}</code></td>
                      <td><button className="danger" onClick={() => handleRemoveUser(u.id)}>Remove</button></td>
                    </tr>
                  ))}</tbody>
                </table>
              )}
              <form onSubmit={handleAddUser} className="row" style={{ gap: 8, marginTop: 8, alignItems: 'center' }}>
                <select value={addUserId} onChange={ev => setAddUserId(ev.target.value)}>
                  <option value="">(pick a user)</option>
                  {nonMembers.map(u => <option key={u.id} value={u.id}>{u.username}</option>)}
                </select>
                <button type="submit" disabled={!addUserId}>+ Add member</button>
              </form>

              <h4>Role memberships</h4>
              <form onSubmit={handleAddRole} className="row" style={{ gap: 8, alignItems: 'center' }}>
                <select value={addRoleId} onChange={ev => setAddRoleId(ev.target.value)}>
                  <option value="">(pick a role)</option>
                  {roles.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
                <button type="submit" disabled={!addRoleId}>+ Add role</button>
              </form>
              <p className="subtle" style={{ marginTop: 4 }}>
                Use the per-user "Effective…" drilldown to confirm a member inherited the role.
              </p>
            </>
          )}
        </div>

        {/* Right: pool grants */}
        <div style={{ flex: '0 0 280px' }}>
          {!selected ? null : (
            <>
              <h4 style={{ marginTop: 0 }}>Pool grants</h4>
              <form id="group-pool-grant-form" onSubmit={handleGrantPool} />
              <table>
                <thead><tr><th>Pool</th><th></th></tr></thead>
                <tbody>
                  {poolPerms.length === 0 ? (
                    <tr>
                      <td colSpan={2} className="empty" style={{ padding: '.5rem' }}>(none)</td>
                    </tr>
                  ) : (
                    poolPerms.map(p => (
                      <tr key={p.id}>
                        <td><code>{p.poolId ?? '*'}</code></td>
                        <td><button className="danger" onClick={() => handleRevokePool(p)}>Revoke</button></td>
                      </tr>
                    ))
                  )}
                </tbody>
                <tfoot>
                  <tr>
                    <td>
                      <input
                        form="group-pool-grant-form"
                        value={grantPoolId}
                        onChange={ev => setGrantPoolId(ev.target.value)}
                        placeholder="Pool id (blank = every pool)"
                      />
                    </td>
                    <td><button type="submit" form="group-pool-grant-form">Grant</button></td>
                  </tr>
                </tfoot>
              </table>
            </>
          )}
        </div>
      </div>

      {adding && (
        <form onSubmit={handleCreate} style={{ marginTop: '0.75rem' }}>
          <fieldset>
            <legend>New group</legend>
            <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
              <label>
                Name
                <input value={newName} onChange={ev => setNewName(ev.target.value)} required />
              </label>
              <label style={{ flex: 1, minWidth: 240 }}>
                Description
                <input value={newDesc} onChange={ev => setNewDesc(ev.target.value)} />
              </label>
            </div>
            <div className="row" style={{ gap: 8, marginTop: '0.5rem' }}>
              <button type="submit">Create</button>
              <button type="button" className="cancel-button" onClick={() => { setAdding(false); setError(null); }}>Cancel</button>
            </div>
          </fieldset>
        </form>
      )}
    </div>
  );
}
