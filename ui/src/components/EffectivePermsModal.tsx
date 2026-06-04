import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { EffectivePermissionsResponse } from '../api/types';

/** Per-user effective-permissions drilldown. Loads /api/user/{id}/effective
  * and renders the closure as four small tables (roles, groups, table
  * permissions, pool grants). The user row at the top is informational
  * so the admin can confirm they're looking at the right principal.
  *
  * Fixed-position overlay -- there's no Modal component in the app yet,
  * so we render directly into the page. Click the backdrop or the close
  * button to dismiss. */
export default function EffectivePermsModal({
  userId,
  onClose,
}: {
  userId: string;
  onClose: () => void;
}) {
  const [data, setData]   = useState<EffectivePermissionsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.effectivePermissions(userId)
      .then(setData)
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }, [userId]);

  return (
    <div
      className="modal-backdrop"
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
        display: 'flex', alignItems: 'flex-start', justifyContent: 'center', zIndex: 100,
        paddingTop: '4rem',
      }}
    >
      <div
        className="modal card"
        onClick={ev => ev.stopPropagation()}
        style={{ width: '90%', maxWidth: 760, maxHeight: '80vh', overflow: 'auto' }}
      >
        <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
          <div className="card-title" style={{ margin: 0 }}>Effective permissions</div>
          <button onClick={onClose}>Close</button>
        </div>
        {error && <div className="login-err">Error: {error}</div>}
        {!data ? (
          <div className="loading">Loading…</div>
        ) : (
          <>
            <p className="subtle">
              Showing the closure of direct + group-inherited grants for
              <code> {data.user.username}</code>
              {data.user.tenant ? <> in tenant <code>{data.user.tenant}</code></>
                                : <> (<em>superuser</em> -- bypasses every gate)</>}.
            </p>

            <h4>Roles ({data.roles.length})</h4>
            {data.roles.length === 0 ? <div className="empty">(none)</div> : (
              <table>
                <thead><tr><th>Name</th><th>Description</th></tr></thead>
                <tbody>{data.roles.map(r => (
                  <tr key={r.id}>
                    <td><code>{r.name}</code></td>
                    <td className="subtle">{r.description ?? ''}</td>
                  </tr>
                ))}</tbody>
              </table>
            )}

            <h4>Groups ({data.groups.length})</h4>
            {data.groups.length === 0 ? <div className="empty">(none)</div> : (
              <table>
                <thead><tr><th>Name</th><th>Description</th></tr></thead>
                <tbody>{data.groups.map(g => (
                  <tr key={g.id}>
                    <td><code>{g.name}</code></td>
                    <td className="subtle">{g.description ?? ''}</td>
                  </tr>
                ))}</tbody>
              </table>
            )}

            <h4>Table permissions ({data.tablePerms.length})</h4>
            {data.tablePerms.length === 0 ? <div className="empty">(none)</div> : (
              <table>
                <thead><tr>
                  <th>Catalog</th><th>Schema</th><th>Table</th><th>Verb</th>
                </tr></thead>
                <tbody>{data.tablePerms.map(p => (
                  <tr key={p.id}>
                    <td><code>{p.catalogName}</code></td>
                    <td><code>{p.schemaName}</code></td>
                    <td><code>{p.tableName}</code></td>
                    <td><code>{p.verb}</code></td>
                  </tr>
                ))}</tbody>
              </table>
            )}

            <h4>Pool grants ({data.pools.length})</h4>
            {data.pools.length === 0 ? <div className="empty">(none)</div> : (
              <table>
                <thead><tr>
                  <th>Tenant</th><th>Pool</th><th>Via</th>
                </tr></thead>
                <tbody>{data.pools.map(p => (
                  <tr key={p.id}>
                    <td><code>{p.tenantId}</code></td>
                    <td><code>{p.poolId ?? '*'}</code></td>
                    <td className="subtle">
                      {p.userId ? 'direct' : p.groupId ? `via group ${p.groupId}` : '-'}
                    </td>
                  </tr>
                ))}</tbody>
              </table>
            )}
          </>
        )}
      </div>
    </div>
  );
}
