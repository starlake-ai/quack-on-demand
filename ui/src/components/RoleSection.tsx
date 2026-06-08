import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { RolePermissionResponse, RoleResponse } from '../api/types';
import { DeleteIcon, EditIcon } from './Icons';

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

  // Per-role verb counts shown in the list (SELECT / INSERT / UPDATE /
  // DELETE / ALL). One /role/permission/list call per role on reload;
  // mutated optimistically in the edit modal so the list stays in sync
  // without a second round-trip.
  const [verbCounts, setVerbCounts] = useState<Record<string, Record<string, number>>>({});

  const [newName, setNewName]   = useState('');
  const [newDesc, setNewDesc]   = useState('');

  // Inline grant form state.
  const [grantCatalog, setGrantCatalog] = useState('*');
  const [grantSchema,  setGrantSchema]  = useState('*');
  const [grantTable,   setGrantTable]   = useState('*');
  const [grantVerb,    setGrantVerb]    = useState(VERBS[0]);

  function reloadRoles() {
    setError(null);
    if (!tenant) { setRoles([]); setSelected(null); setVerbCounts({}); return; }
    api.listRoles(tenant)
      .then(async r => {
        setRoles(r.roles);
        // Per-role verb counts: one /role/permission/list call per role
        // in parallel. Cheap for the typical role fan-out in admin UI.
        const entries = await Promise.all(r.roles.map(role =>
          api.listRolePermissions(role.id)
            .then(x => {
              const c: Record<string, number> = {};
              for (const v of VERBS) c[v] = 0;
              for (const p of x.permissions) c[p.verb] = (c[p.verb] ?? 0) + 1;
              return [role.id, c] as const;
            })
            .catch(() => [role.id, Object.fromEntries(VERBS.map(v => [v, 0]))] as const),
        ));
        setVerbCounts(Object.fromEntries(entries));
      })
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
      bumpVerbCount(selected.id, grantVerb, +1);
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
      bumpVerbCount(selected.id, p.verb, -1);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  function bumpVerbCount(roleId: string, verb: string, delta: number) {
    setVerbCounts(c => {
      const row = c[roleId] ?? Object.fromEntries(VERBS.map(v => [v, 0]));
      return { ...c, [roleId]: { ...row, [verb]: Math.max(0, (row[verb] ?? 0) + delta) } };
    });
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
          <button type="button" className="link-button" onClick={() => setAdding(true)}>+ New role</button>
        )}
      </div>
      {error && <div className="login-err">Error: {error}</div>}

      {roles.length === 0 ? (
        <div className="empty">(no roles in this tenant)</div>
      ) : (
        <table>
          <thead><tr>
            <th>Name</th>
            {VERBS.map(v => <th key={v}>{v}</th>)}
            <th className="actions"></th>
          </tr></thead>
          <tbody>{roles.map(r => (
            <tr key={r.id}>
              <td><code>{r.name}</code></td>
              {VERBS.map(v => (
                <td key={v}>{verbCounts[r.id]?.[v] ?? '-'}</td>
              ))}
              <td className="actions">
                <button className="icon-btn" title="Edit" aria-label="Edit" onClick={() => setSelected(r)}><EditIcon /></button>
                {' '}
                <button className="icon-btn danger" title="Delete" aria-label="Delete" onClick={() => handleDelete(r)}><DeleteIcon /></button>
              </td>
            </tr>
          ))}</tbody>
        </table>
      )}

      {selected && (
        <div
          className="modal-backdrop"
          onClick={() => setSelected(null)}
          style={{
            position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
            display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
            zIndex: 100, paddingTop: '4rem',
          }}
        >
          <div
            className="modal card"
            onClick={ev => ev.stopPropagation()}
            style={{
              width: '90%', maxWidth: 720,
              height: '80vh', maxHeight: 'calc(100vh - 4rem)',
              display: 'flex', flexDirection: 'column',
            }}
          >
            <div className="card-title">Edit role <code>{selected.name}</code></div>
            <div style={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
              <form onSubmit={handleGrant} className="row" style={{ gap: 8, marginBottom: 8, alignItems: 'center' }}>
                <input style={{ flex: 1, minWidth: 80 }} value={grantCatalog} onChange={ev => setGrantCatalog(ev.target.value)} placeholder="Catalog" />
                <input style={{ flex: 1, minWidth: 80 }} value={grantSchema}  onChange={ev => setGrantSchema(ev.target.value)}  placeholder="Schema" />
                <input style={{ flex: 1, minWidth: 80 }} value={grantTable}   onChange={ev => setGrantTable(ev.target.value)}   placeholder="Table" />
                <select style={{ flex: '0 0 7rem' }} value={grantVerb} onChange={ev => setGrantVerb(ev.target.value)}>
                  {VERBS.map(v => <option key={v} value={v}>{v}</option>)}
                </select>
                <button type="submit" style={{ whiteSpace: 'nowrap' }}>+ Grant</button>
              </form>
              {perms.length === 0 ? (
                <div className="empty">(no permissions yet)</div>
              ) : (
                <table>
                  <thead><tr>
                    <th>Catalog</th><th>Schema</th><th>Table</th><th>Verb</th><th className="actions"></th>
                  </tr></thead>
                  <tbody>
                    {perms.map(p => (
                      <tr key={p.id}>
                        <td><code>{p.catalogName}</code></td>
                        <td><code>{p.schemaName}</code></td>
                        <td><code>{p.tableName}</code></td>
                        <td><code>{p.verb}</code></td>
                        <td className="actions">
                          <button className="icon-btn danger" title="Revoke" aria-label="Revoke" onClick={() => handleRevoke(p)}><DeleteIcon /></button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
            <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
              <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={() => setSelected(null)}>Close</button>
            </div>
          </div>
        </div>
      )}

      {adding && (
        <div
          className="modal-backdrop"
          onClick={() => { setAdding(false); setError(null); }}
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
            <div className="card-title">New role</div>
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
          </div>
        </div>
      )}
    </div>
  );
}
