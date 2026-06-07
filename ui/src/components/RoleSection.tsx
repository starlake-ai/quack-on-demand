import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { RolePermissionResponse, RoleResponse } from '../api/types';

const VERBS = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'ALL'];

/** Roles tab on the /users page. Two-pane layout:
  *   - left: roles list + "+ New role"
  *   - right: the selected role's permission rows + inline grant form
  * No role-permission revoke action returns a payload, so each toggle
  * triggers a refresh of the right pane. */
export default function RoleSection({ tenant }: { tenant: string | null }) {
  const [roles, setRoles]   = useState<RoleResponse[]>([]);
  const [error, setError]   = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  // Selected role + its permissions.
  const [selected, setSelected]       = useState<RoleResponse | null>(null);
  const [perms,    setPerms]          = useState<RolePermissionResponse[]>([]);

  const [newName, setNewName]   = useState('');
  const [newDesc, setNewDesc]   = useState('');

  // Inline grant form state.
  const [grantCatalog, setGrantCatalog] = useState('*');
  const [grantSchema,  setGrantSchema]  = useState('*');
  const [grantTable,   setGrantTable]   = useState('*');
  const [grantVerb,    setGrantVerb]    = useState(VERBS[0]);

  function reloadRoles() {
    setError(null);
    if (!tenant) { setRoles([]); setSelected(null); return; }
    api.listRoles(tenant)
      .then(r => setRoles(r.roles))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  function reloadPerms(r: RoleResponse) {
    api.listRolePermissions(r.id)
      .then(x => setPerms(x.permissions))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => {
    reloadRoles();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);

  useEffect(() => {
    if (!selected) { setPerms([]); return; }
    reloadPerms(selected);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected?.id]);

  async function handleCreate(ev: React.FormEvent) {
    ev.preventDefault();
    if (!tenant) return;
    setError(null);
    try {
      const created = await api.createRole({
        tenant,
        name:        newName.trim(),
        description: newDesc.trim() || null,
      });
      setAdding(false);
      setNewName(''); setNewDesc('');
      reloadRoles();
      setSelected(created);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(r: RoleResponse) {
    if (!confirm(`Delete role '${r.name}'?`)) return;
    setError(null);
    try {
      await api.deleteRole({ id: r.id });
      if (selected?.id === r.id) setSelected(null);
      reloadRoles();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleGrant(ev: React.FormEvent) {
    ev.preventDefault();
    if (!selected) return;
    setError(null);
    try {
      await api.grantRolePermission({
        roleId:  selected.id,
        catalog: grantCatalog.trim() || '*',
        schema:  grantSchema.trim()  || '*',
        table:   grantTable.trim()   || '*',
        verb:    grantVerb,
      });
      reloadPerms(selected);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleRevoke(p: RolePermissionResponse) {
    if (!selected) return;
    if (!confirm(`Revoke ${p.verb} on ${p.catalogName}.${p.schemaName}.${p.tableName}?`)) return;
    setError(null);
    try {
      await api.revokeRolePermission({ id: p.id });
      reloadPerms(selected);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  if (!tenant) {
    return (
      <div className="card">
        <div className="card-title">Roles</div>
        <p className="subtle">Pick a tenant above to manage its roles.</p>
      </div>
    );
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Roles</div>
        {!adding && (
          <button onClick={() => setAdding(true)}>+ New role</button>
        )}
      </div>
      {error && <div className="login-err">Error: {error}</div>}

      <div className="row" style={{ alignItems: 'flex-start', gap: 16 }}>
        {/* Left pane: roles list */}
        <div style={{ flex: '0 0 280px' }}>
          {roles.length === 0 ? (
            <div className="empty">(no roles in this tenant)</div>
          ) : (
            <table>
              <thead><tr><th>Name</th><th></th></tr></thead>
              <tbody>{roles.map(r => (
                <tr
                  key={r.id}
                  onClick={() => setSelected(r)}
                  style={{
                    cursor: 'pointer',
                    background: selected?.id === r.id ? 'rgba(0,0,0,0.04)' : undefined,
                  }}
                >
                  <td><code>{r.name}</code></td>
                  <td>
                    <button className="danger" onClick={ev => { ev.stopPropagation(); void handleDelete(r); }}>Delete</button>
                  </td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </div>

        {/* Right pane: selected role's permissions */}
        <div style={{ flex: 1 }}>
          {!selected ? (
            <div className="subtle">(select a role to see its permissions)</div>
          ) : (
            <>
              <h4 style={{ marginTop: 0 }}>
                Permissions for <code>{selected.name}</code>
              </h4>
              {perms.length === 0 ? (
                <div className="empty">(no permissions yet)</div>
              ) : (
                <table>
                  <thead><tr>
                    <th>Catalog</th><th>Schema</th><th>Table</th><th>Verb</th><th></th>
                  </tr></thead>
                  <tbody>{perms.map(p => (
                    <tr key={p.id}>
                      <td><code>{p.catalogName}</code></td>
                      <td><code>{p.schemaName}</code></td>
                      <td><code>{p.tableName}</code></td>
                      <td><code>{p.verb}</code></td>
                      <td>
                        <button className="danger" onClick={() => handleRevoke(p)}>Revoke</button>
                      </td>
                    </tr>
                  ))}</tbody>
                </table>
              )}

              <form onSubmit={handleGrant} className="row" style={{ gap: 8, marginTop: '0.75rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
                <label>
                  Catalog<br/>
                  <input value={grantCatalog} onChange={ev => setGrantCatalog(ev.target.value)} style={{ width: 140 }} />
                </label>
                <label>
                  Schema<br/>
                  <input value={grantSchema} onChange={ev => setGrantSchema(ev.target.value)} style={{ width: 140 }} />
                </label>
                <label>
                  Table<br/>
                  <input value={grantTable} onChange={ev => setGrantTable(ev.target.value)} style={{ width: 140 }} />
                </label>
                <label>
                  Verb<br/>
                  <select value={grantVerb} onChange={ev => setGrantVerb(ev.target.value)}>
                    {VERBS.map(v => <option key={v} value={v}>{v}</option>)}
                  </select>
                </label>
                <button type="submit">Grant</button>
              </form>
            </>
          )}
        </div>
      </div>

      {adding && (
        <form onSubmit={handleCreate} style={{ marginTop: '0.75rem' }}>
          <fieldset>
            <legend>New role</legend>
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
