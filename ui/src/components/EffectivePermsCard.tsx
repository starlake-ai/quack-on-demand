import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { EffectivePermissionsResponse } from '../api/types';

/** Inline per-user effective-permissions drilldown rendered as a card
  * beneath the user's row in the Users table. Loads
  * `/api/user/{id}/effective` and shows the closure as four small
  * tables (roles, groups, table permissions, pool grants). Toggled
  * open/closed by clicking the username in the parent table. */
export default function EffectivePermsCard({
  userId,
}: {
  userId: string;
}) {
  const [data, setData]   = useState<EffectivePermissionsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setData(null);
    setError(null);
    api.effectivePermissions(userId)
      .then(setData)
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }, [userId]);

  return (
    <div className="effective-perms-card">
      {error && <div className="login-err">Error: {error}</div>}
      {!data && !error && <div className="loading">Loading…</div>}
      {data && (
        <>
          <p className="subtle" style={{ marginTop: 0 }}>
            Closure of direct + group-inherited grants for
            <code> {data.user.username}</code>
            {data.user.tenant ? <> in tenant <code>{data.user.tenant}</code></>
                              : <> (<em>superuser</em> — bypasses every gate)</>}.
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
  );
}
