import { useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type {
  GroupResponse, PoolPermissionResponse, PoolResponse, RoleResponse, UserResponse,
} from '../api/types';
import Tabs from './Tabs';
import { DeleteIcon, EditIcon } from './Icons';
import { Modal } from './Modal';

/** Groups tab on the /users page. Single-pane list of groups (one row per
  * group, with member/role/pool-grant counts) plus a per-row Edit button
  * that opens a 3-tab modal (Users / Roles / Pool grants) for that group.
  * Counts are computed client-side from the tenant-scoped user list,
  * per-group role membership probes, and a single tenant-wide pool
  * permission fetch bucketed by groupId. */
export default function GroupSection({ tenant }: { tenant: string | null }) {
  const [groups, setGroups]   = useState<GroupResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);
  const [adding, setAdding]   = useState(false);

  // The group currently being edited; non-null opens the edit modal.
  const [selected, setSelected] = useState<GroupResponse | null>(null);

  // Tenant context shared by the list (for counts) and the edit modal.
  const [users, setUsers]             = useState<UserResponse[]>([]);
  const [roles, setRoles]             = useState<RoleResponse[]>([]);
  const [groupRoles, setGroupRoles]   = useState<RoleResponse[]>([]);
  const [poolPerms, setPoolPerms]     = useState<PoolPermissionResponse[]>([]);
  const [tenantPools, setTenantPools] = useState<PoolResponse[]>([]);

  // Per-group counts shown in the list. roleCounts: N parallel calls to
  // /membership/group-role/list. poolCounts: one tenant-wide
  // /pool/permission/list bucketed by groupId. userCounts are derived
  // inline from the `users` list (each user already reports its groups
  // by name).
  const [roleCounts, setRoleCounts] = useState<Record<string, number>>({});
  const [poolCounts, setPoolCounts] = useState<Record<string, number>>({});

  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const [addUserId, setAddUserId]     = useState('');
  const [addRoleId, setAddRoleId]     = useState('');
  const [grantPoolId, setGrantPoolId] = useState<string>(''); // '' = wildcard

  function reloadGroups() {
    setError(null);
    if (!tenant) { setGroups([]); setSelected(null); setRoleCounts({}); setPoolCounts({}); return; }
    api.listGroups(tenant)
      .then(async r => {
        setGroups(r.groups);
        // Pool-grant counts: one tenant-wide call bucketed by groupId.
        api.listPoolPermissions({ tenant })
          .then(pp => {
            const counts: Record<string, number> = {};
            for (const p of pp.permissions) {
              if (p.groupId) counts[p.groupId] = (counts[p.groupId] ?? 0) + 1;
            }
            setPoolCounts(counts);
          })
          .catch(() => setPoolCounts({}));
        // Role counts: one call per group. Acceptable for the typical
        // group fan-out (handful per tenant in the admin UI).
        const entries = await Promise.all(r.groups.map(g =>
          api.listGroupRoles(g.id)
            .then(x => [g.id, x.roles.length] as const)
            .catch(() => [g.id, 0] as const),
        ));
        setRoleCounts(Object.fromEntries(entries));
      })
      .catch(e => setError(errorMessage(e)));
  }

  function reloadTenantContext() {
    if (!tenant) return;
    api.listUsers(tenant).then(r => setUsers(r.users)).catch(() => {});
    api.listRoles(tenant).then(r => setRoles(r.roles)).catch(() => {});
    api.listPools()
      .then(r => setTenantPools(r.pools.filter(p => p.tenant === tenant)))
      .catch(() => setTenantPools([]));
  }

  function reloadGroupPools(g: GroupResponse) {
    api.listPoolPermissions({ groupId: g.id })
      .then(r => setPoolPerms(r.permissions))
      .catch(e => setError(errorMessage(e)));
  }

  function reloadGroupRoles(g: GroupResponse) {
    api.listGroupRoles(g.id)
      .then(r => setGroupRoles(r.roles))
      .catch(e => setError(errorMessage(e)));
  }

  useEffect(() => {
    // Empty the previous tenant's data before loading the new scope, so stale
    // rows + detail don't linger during the fetch. Only on tenant change --
    // mutation-triggered reloadGroups() calls keep the open detail pane.
    setGroups([]);
    setSelected(null);
    setRoleCounts({});
    setPoolCounts({});
    reloadGroups();
    reloadTenantContext();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);

  useEffect(() => {
    if (!selected) { setPoolPerms([]); setGroupRoles([]); return; }
    reloadGroupPools(selected);
    reloadGroupRoles(selected);
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
      setError(errorMessage(e));
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
      setError(errorMessage(e));
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
      setError(errorMessage(e));
    }
  }

  async function handleRemoveUser(userId: string) {
    if (!selected) return;
    setError(null);
    try {
      await api.removeUserGroup({ userId, groupId: selected.id });
      reloadTenantContext();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function handleAddRole(ev: React.FormEvent) {
    ev.preventDefault();
    if (!selected || !addRoleId) return;
    setError(null);
    try {
      await api.addGroupRole({ groupId: selected.id, roleId: addRoleId });
      setAddRoleId('');
      reloadGroupRoles(selected);
      setRoleCounts(c => ({ ...c, [selected.id]: (c[selected.id] ?? 0) + 1 }));
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function handleRemoveRole(roleId: string) {
    if (!selected) return;
    setError(null);
    try {
      await api.removeGroupRole({ groupId: selected.id, roleId });
      reloadGroupRoles(selected);
      setRoleCounts(c => ({ ...c, [selected.id]: Math.max(0, (c[selected.id] ?? 1) - 1) }));
    } catch (e) {
      setError(errorMessage(e));
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
      setPoolCounts(c => ({ ...c, [selected.id]: (c[selected.id] ?? 0) + 1 }));
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function handleRevokePool(p: PoolPermissionResponse) {
    if (!selected) return;
    if (!confirm(`Revoke pool grant ${p.poolId ?? '*'}?`)) return;
    setError(null);
    try {
      await api.revokePoolPermission({ id: p.id });
      reloadGroupPools(selected);
      setPoolCounts(c => ({ ...c, [selected.id]: Math.max(0, (c[selected.id] ?? 1) - 1) }));
    } catch (e) {
      setError(errorMessage(e));
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

  // Per-list-row user count: derived from already-fetched users. Pool +
  // role counts use the pre-computed maps from reloadGroups.
  const userCount = (g: GroupResponse) =>
    users.filter(u => u.groups.includes(g.name)).length;

  // Selected-group derivations (modal only).
  const members = selected
    ? users.filter(u => u.groups.includes(selected.name))
    : [];
  const nonMembers = selected
    ? users.filter(u => !u.groups.includes(selected.name))
    : [];

  const usersTab = !selected ? null : (
    <>
      <form onSubmit={handleAddUser} className="row" style={{ gap: 8, marginBottom: 8, alignItems: 'center' }}>
        <select style={{ flex: 1 }} value={addUserId} onChange={ev => setAddUserId(ev.target.value)}>
          <option value="">(pick a user)</option>
          {nonMembers.map(u => <option key={u.id} value={u.id}>{u.username}</option>)}
        </select>
        <button type="submit" disabled={!addUserId} style={{ whiteSpace: 'nowrap' }}>+ Add member</button>
      </form>
      {members.length === 0 ? <div className="empty">(no members)</div> : (
        <table>
          <thead><tr><th>Username</th><th className="actions"></th></tr></thead>
          <tbody>{members.map(u => (
            <tr key={u.id}>
              <td><code>{u.username}</code></td>
              <td className="actions"><button className="icon-btn danger" title="Remove" aria-label="Remove" onClick={() => handleRemoveUser(u.id)}><DeleteIcon /></button></td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </>
  );

  const rolesTab = !selected ? null : (
    <>
      <form onSubmit={handleAddRole} className="row" style={{ gap: 8, marginBottom: 8, alignItems: 'center' }}>
        <select style={{ flex: 1 }} value={addRoleId} onChange={ev => setAddRoleId(ev.target.value)}>
          <option value="">(pick a role)</option>
          {roles
            .filter(r => !groupRoles.some(gr => gr.id === r.id))
            .map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
        </select>
        <button type="submit" disabled={!addRoleId} style={{ whiteSpace: 'nowrap' }}>+ Add role</button>
      </form>
      {groupRoles.length === 0 ? <div className="empty">(no role memberships)</div> : (
        <table>
          <thead><tr><th>Role</th><th className="actions"></th></tr></thead>
          <tbody>{groupRoles.map(r => (
            <tr key={r.id}>
              <td><code>{r.name}</code></td>
              <td className="actions"><button className="icon-btn danger" title="Remove" aria-label="Remove" onClick={() => handleRemoveRole(r.id)}><DeleteIcon /></button></td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </>
  );

  const poolsTab = !selected ? null : (
    <>
      <form onSubmit={handleGrantPool} className="row" style={{ gap: 8, marginBottom: 8, alignItems: 'center' }}>
        <select
          style={{ flex: 1 }}
          value={grantPoolId}
          onChange={ev => setGrantPoolId(ev.target.value)}
        >
          <option value="">(every pool in tenant)</option>
          {tenantPools.map(p => (
            <option key={p.id} value={p.id}>{p.pool}</option>
          ))}
        </select>
        <button type="submit" style={{ whiteSpace: 'nowrap' }}>+ Grant pool</button>
      </form>
      {poolPerms.length === 0 ? <div className="empty">(no pool grants)</div> : (
        <table>
          <thead><tr><th>Pool</th><th className="actions"></th></tr></thead>
          <tbody>
            {poolPerms.map(p => {
              const name = p.poolId
                ? (tenantPools.find(tp => tp.id === p.poolId)?.pool ?? p.poolId)
                : '*';
              return (
                <tr key={p.id}>
                  <td><code>{name}</code></td>
                  <td className="actions"><button className="icon-btn danger" title="Revoke" aria-label="Revoke" onClick={() => handleRevokePool(p)}><DeleteIcon /></button></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </>
  );

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Groups</div>
        {!adding && (
          <button type="button" className="link-button" onClick={() => setAdding(true)}>+ New group</button>
        )}
      </div>
      {error && <div className="login-err">Error: {error}</div>}

      {groups.length === 0 ? (
        <div className="empty">(no groups)</div>
      ) : (
        <table>
          <thead><tr>
            <th>Name</th>
            <th>Users</th>
            <th>Roles</th>
            <th>Pool grants</th>
            <th className="actions"></th>
          </tr></thead>
          <tbody>{groups.map(g => (
            <tr key={g.id}>
              <td><code>{g.name}</code></td>
              <td>{userCount(g)}</td>
              <td>{roleCounts[g.id] ?? '-'}</td>
              <td>{poolCounts[g.id] ?? 0}</td>
              <td className="actions">
                <button className="icon-btn" title="Edit" aria-label="Edit" onClick={() => setSelected(g)}><EditIcon /></button>
                {' '}
                <button className="icon-btn danger" title="Delete" aria-label="Delete" onClick={() => handleDelete(g)}><DeleteIcon /></button>
              </td>
            </tr>
          ))}</tbody>
        </table>
      )}

      {adding && (
        <Modal maxWidth={560} onClose={() => { setAdding(false); setError(null); }}>
            <div className="card-title">New group</div>
            <form onSubmit={handleCreate}>
              <label>
                Name
                <input value={newName} onChange={ev => setNewName(ev.target.value)} required />
              </label>
              <label>
                Description
                <input value={newDesc} onChange={ev => setNewDesc(ev.target.value)} />
              </label>
              <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
                <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={() => { setAdding(false); setError(null); }}>Cancel</button>
                <button type="submit" style={{ minWidth: '7rem' }}>Create</button>
              </div>
            </form>
        </Modal>
      )}

      {selected && (
        <Modal maxWidth={720} height="80vh" onClose={() => setSelected(null)}>
            <div className="card-title">Edit group <code>{selected.name}</code></div>
            <div style={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
              <Tabs
                tabs={[
                  { id: 'users', label: 'Users',       body: usersTab },
                  { id: 'roles', label: 'Roles',       body: rolesTab },
                  { id: 'pools', label: 'Pool grants', body: poolsTab },
                ]}
              />
            </div>
            <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
              <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={() => setSelected(null)}>Close</button>
            </div>
        </Modal>
      )}
    </div>
  );
}
