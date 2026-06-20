import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { PoolResponse, TenantResponse, UserResponse } from '../api/types';
import EffectivePermsCard from './EffectivePermsCard';
import { DeleteIcon, EditIcon } from './Icons';

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
  superusersOnly = false,
}: {
  tenant:  string | null;
  tenants: TenantResponse[];
  /** When true, the rendered table is filtered client-side to users
    * whose `tenant` field is null (superusers). The backend has no
    * dedicated endpoint for "superusers only" via listUsers, so the
    * caller passes `tenant = null` and we narrow on the client. */
  superusersOnly?: boolean;
}) {
  const [rows, setRows]       = useState<UserResponse[]>([]);
  const [error, setError]     = useState<string | null>(null);
  const [adding, setAdding]   = useState(false);
  // Which user's permissions card is currently expanded under their row.
  // Toggled by clicking the username; only one card is open at a time.
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // Pool catalog used to turn the "tenant/poolId" grant tokens emitted
  // by the backend into clickable Links to /pool/:tenant/:tenantDb/:pool,
  // and to feed the EffectivePermsCard for the Databases / Quack nodes
  // sections. Fetched once per mount; reload on tenant filter change so
  // a freshly-created pool shows up without a hard refresh.
  const [pools, setPools] = useState<PoolResponse[]>([]);
  useEffect(() => {
    api.listPools()
      .then(r => setPools(r.pools))
      .catch(() => setPools([]));
  }, []);

  // Index by the surrogate id the backend embeds in poolGrants tokens.
  const poolById = useMemo(
    () => new Map(pools.map(p => [p.id, p])),
    [pools],
  );
  // Fallback index by (tenant displayName, pool name) -- some installs
  // may emit "tenant/poolName" rather than "tenant/poolId"; we accept
  // either form so the column stays useful through any backend change.
  const poolByTenantAndName = useMemo(() => {
    const m = new Map<string, PoolResponse>();
    pools.forEach(p => m.set(`${p.tenant}/${p.pool}`, p));
    return m;
  }, [pools]);

  /** Render one "tenant/poolToken" grant string as a Link. The token
    * is either a surrogate `p-xxx`, a pool display name, or `*` for
    * the "every pool in tenant" wildcard. Falls back to plain text
    * when the catalog hasn't loaded yet or the pool isn't found. */
  function renderGrant(token: string) {
    const slash = token.indexOf('/');
    if (slash < 0) return <code>{token}</code>;
    const tenantName = token.slice(0, slash);
    const poolTok    = token.slice(slash + 1);
    if (poolTok === '*') {
      return (
        <Link to={`/tenant/${tenantName}`} title="every pool in this tenant">
          <code>{tenantName}/*</code>
        </Link>
      );
    }
    const p =
      poolById.get(poolTok)
      ?? poolByTenantAndName.get(`${tenantName}/${poolTok}`);
    if (!p) return <code>{token}</code>;
    return (
      <Link to={`/pool/${p.tenant}/${p.tenantDb}/${p.pool}`}>
        <code>{p.tenant}/{p.pool}</code>
      </Link>
    );
  }

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
  const [editingId, setEditingId]       = useState<string | null>(null);
  const [editPassword, setEditPassword] = useState('');
  const [editIsAdmin, setEditIsAdmin]   = useState(false);

  // Resolve the selected new-tenant to its provider so the form can
  // adapt. Superuser always uses the db backend (no IdP routing).
  const newTenantRow = tenants.find(t => t.name === newTenant);
  const newTenantIsDb = newTenant === SUPERUSER || newTenantRow?.authProvider === 'db';

  // Same lookup but for the rows currently displayed.
  const tenantProviderOf = (tenantName: string | null): string =>
    tenantName ? (tenants.find(t => t.name === tenantName)?.authProvider ?? 'db') : 'db';

  // When the page filter is pinned to a single tenant backed by an
  // external IdP, surface a banner so the operator knows accounts are
  // authoritatively owned elsewhere and the local rows are just
  // pre-provisioned shells for role/group/grant attachment.
  const filterProvider = tenant ? tenantProviderOf(tenant) : null;
  const externalProvider = filterProvider && filterProvider !== 'db' ? filterProvider : null;

  function reload() {
    setError(null);
    api.listUsers(tenant ?? undefined)
      .then(r => setRows(superusersOnly ? r.users.filter(u => u.tenant == null) : r.users))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => {
    setNewTenant(tenant ?? SUPERUSER);
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant, superusersOnly]);

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
      await api.updateUser({
        id,
        password: editPassword || null,
        role:     editIsAdmin ? 'admin' : 'user',
      });
      setEditingId(null); setEditPassword(''); setEditIsAdmin(false); setEditIsAdmin(false);
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
          <button type="button" className="link-button" onClick={() => setAdding(true)}>{createLabel}</button>
        )}
      </div>
      {error && <div className="login-err">Error: {error}</div>}
      {externalProvider && (
        <div className="external-provider-notice">
          Users for tenant <code>{tenant}</code> are managed by the external
          {' '}<code>{externalProvider}</code> identity provider. Accounts
          authenticate against the IdP - the rows below are local
          pre-provisioned shells used only to attach roles, groups, and
          pool grants. Password edits are disabled.
        </div>
      )}
      {rows.length === 0 ? (
        <div className="empty">(no users)</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Username</th>
              <th>Tenant</th>
              <th>Admin</th>
              <th>Roles</th>
              <th>Groups</th>
              <th>Pool grants</th>
              <th>Enabled</th>
              <th className="actions"></th>
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
                  <td>
                    <input
                      type="checkbox"
                      checked={u.role === 'admin'}
                      disabled
                      title="Admin status is edited in the Edit modal"
                    />
                  </td>
                  <td>{u.roles.length === 0 ? <span className="subtle">-</span> : u.roles.join(', ')}</td>
                  <td>{u.groups.length === 0 ? <span className="subtle">-</span> : u.groups.join(', ')}</td>
                  <td>
                    {u.poolGrants.length === 0
                      ? <span className="subtle">-</span>
                      : u.poolGrants.map((g, i) => (
                          <span key={g}>
                            {i > 0 && ', '}
                            {renderGrant(g)}
                          </span>
                        ))}
                  </td>
                  <td>{u.enabled ? '✓' : '✗'}</td>
                  <td className="actions">
                    {allowEdit ? (
                      <button
                        className="icon-btn"
                        title="Edit"
                        aria-label="Edit"
                        onClick={() => { setEditingId(u.id); setEditPassword(''); setEditIsAdmin(u.role === 'admin'); }}
                      ><EditIcon /></button>
                    ) : (
                      <button
                        className="icon-btn"
                        disabled
                        aria-label="Edit"
                        title={`Password is managed by the tenant's ${rowProvider} provider`}
                      ><EditIcon /></button>
                    )}
                    {' '}
                    <button
                      className="icon-btn danger"
                      title="Delete"
                      aria-label="Delete"
                      onClick={() => handleDelete(u)}
                    ><DeleteIcon /></button>
                  </td>
                </tr>
              );
              const perms = isExpanded ? [(
                <tr key={u.id + '-perms'} className="expanded-row">
                  <td colSpan={8}>
                    <EffectivePermsCard userId={u.id} tenants={tenants} pools={pools} />
                  </td>
                </tr>
              )] : [];
              return [row, ...perms];
            })}
          </tbody>
        </table>
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
            <div className="card-title">{createLabel}</div>
            {!newTenantIsDb && (
              <p className="subtle" style={{ marginTop: 0 }}>
                <code>{newTenantRow?.authProvider}</code> tenant: the IdP owns
                authentication. This form creates a local <code>qodstate_user</code>
                row so role / group / pool grants can be attached now; the
                same row is reused on the user's first sign-in.
              </p>
            )}
            <form onSubmit={handleCreate}>
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
                      {t.name} - {t.authProvider}
                    </option>
                  ))}
                </select>
              </label>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={newRole === 'admin'}
                  onChange={ev => setNewRole(ev.target.checked ? 'admin' : 'user')}
                />
                {' '}Admin User
              </label>
              <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
                <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={() => { setAdding(false); setError(null); }}>Cancel</button>
                <button type="submit" style={{ minWidth: '7rem' }}>{newTenantIsDb ? 'Create' : 'Pre-provision'}</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {editingId && (() => {
        const u = rows.find(r => r.id === editingId);
        if (!u) return null;
        return (
          <div
            className="modal-backdrop"
            onClick={() => { setEditingId(null); setEditPassword(''); setEditIsAdmin(false); }}
            style={{
              position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
              display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
              zIndex: 100, paddingTop: '4rem',
            }}
          >
            <div
              className="modal card"
              onClick={ev => ev.stopPropagation()}
              style={{ width: '90%', maxWidth: 480 }}
            >
              <div className="card-title">
                Edit user <code>{u.username}</code>
                {u.tenant ? <> in <code>{u.tenant}</code></> : <> (superuser)</>}
              </div>
              <form onSubmit={ev => { ev.preventDefault(); void handleUpdate(u.id); }}>
                <label>
                  New password
                  <input
                    type="password"
                    value={editPassword}
                    onChange={ev => setEditPassword(ev.target.value)}
                    placeholder="(leave blank to leave unchanged)"
                  />
                </label>
                <label className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={editIsAdmin}
                    onChange={ev => setEditIsAdmin(ev.target.checked)}
                  />
                  {' '}Admin User
                </label>
                <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
                  <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={() => { setEditingId(null); setEditPassword(''); setEditIsAdmin(false); }}>Cancel</button>
                  <button type="submit" style={{ minWidth: '7rem' }}>Save</button>
                </div>
              </form>
            </div>
          </div>
        );
      })()}

    </div>
  );
}
