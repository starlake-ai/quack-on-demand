import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type {
  EffectivePermissionsResponse,
  PoolPermissionResponse,
  PoolResponse,
  TenantResponse,
} from '../api/types';

/** Inline per-user effective-permissions drilldown rendered as a card
  * beneath the user's row in the Users table. Loads
  * `/api/user/{id}/effective` and shows the closure as several small
  * tables (roles, groups, table permissions, pool grants, plus the
  * derived databases and quack nodes the user can reach). Toggled
  * open/closed by clicking the username in the parent table.
  *
  * `tenants` and `pools` come from the parent so the card can resolve
  * surrogate ids in the grant rows to human-friendly labels and Links
  * to the corresponding detail pages. Both are optional: if absent the
  * card falls back to raw ids and renders without links (keeps the
  * component drop-in for callers that don't have the catalogs handy). */
export default function EffectivePermsCard({
  userId,
  tenants = [],
  pools = [],
}: {
  userId: string;
  tenants?: TenantResponse[];
  pools?: PoolResponse[];
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

  // Resolution helpers. The grant rows carry surrogate ids
  // (`p-...`/`t-...`); the catalogs let us recover the display names
  // and the (tenantDb, poolName) tuple needed to deep-link.
  const tenantById = useMemo(
    () => new Map(tenants.map(t => [t.id, t])),
    [tenants],
  );
  const poolById = useMemo(
    () => new Map(pools.map(p => [p.id, p])),
    [pools],
  );
  const poolsByTenantName = useMemo(() => {
    const m = new Map<string, PoolResponse[]>();
    pools.forEach(p => {
      const arr = m.get(p.tenant);
      if (arr) arr.push(p); else m.set(p.tenant, [p]);
    });
    return m;
  }, [pools]);

  /** All pools the user's grants admit. A wildcard grant
    * (`poolId === null`) expands to every pool in that tenant. */
  function expandedPools(grants: PoolPermissionResponse[]): PoolResponse[] {
    const out: PoolResponse[] = [];
    const seen = new Set<string>();
    grants.forEach(g => {
      const tn = tenantById.get(g.tenantId)?.name;
      if (g.poolId) {
        const p = poolById.get(g.poolId);
        if (p && !seen.has(p.id)) { out.push(p); seen.add(p.id); }
      } else if (tn) {
        (poolsByTenantName.get(tn) ?? []).forEach(p => {
          if (!seen.has(p.id)) { out.push(p); seen.add(p.id); }
        });
      }
    });
    return out;
  }

  const reachablePools = data ? expandedPools(data.pools) : [];

  // Databases the user reaches: one row per (tenant, tenantDb) pair,
  // with the list of pools that contribute to the access.
  const databases = useMemo(() => {
    const acc = new Map<string, { tenant: string; tenantDb: string; pools: string[] }>();
    reachablePools.forEach(p => {
      const k = `${p.tenant}/${p.tenantDb}`;
      const row = acc.get(k);
      if (row) row.pools.push(p.pool);
      else acc.set(k, { tenant: p.tenant, tenantDb: p.tenantDb, pools: [p.pool] });
    });
    return [...acc.values()].sort((a, b) =>
      a.tenant.localeCompare(b.tenant) || a.tenantDb.localeCompare(b.tenantDb),
    );
  }, [reachablePools]);

  // Quack nodes the user reaches. We deduplicate by nodeId; the same
  // node serving two granted pools is still one node, but we list every
  // (pool, role, host:port) tuple separately so the operator can spot
  // role drift across cohorts.
  const reachableNodes = useMemo(() => {
    type Row = {
      key: string;
      nodeId: string;
      tenant: string;
      tenantDb: string;
      pool: string;
      role: string;
      host: string;
      port: number;
      healthy: boolean;
      draining: boolean;
    };
    const rows: Row[] = [];
    const seen = new Set<string>();
    reachablePools.forEach(p => {
      p.nodes.forEach(n => {
        const key = `${n.nodeId}@${p.id}`;
        if (seen.has(key)) return;
        seen.add(key);
        rows.push({
          key,
          nodeId: n.nodeId,
          tenant: p.tenant,
          tenantDb: p.tenantDb,
          pool: p.pool,
          role: n.role,
          host: n.host,
          port: n.port,
          healthy: n.healthy,
          draining: n.draining,
        });
      });
    });
    return rows.sort((a, b) =>
      a.tenant.localeCompare(b.tenant) ||
      a.tenantDb.localeCompare(b.tenantDb) ||
      a.pool.localeCompare(b.pool) ||
      a.nodeId.localeCompare(b.nodeId),
    );
  }, [reachablePools]);

  // Render a tenant cell as a Link when we can resolve the name.
  function tenantCell(tenantId: string) {
    const t = tenantById.get(tenantId);
    if (!t) return <code>{tenantId}</code>;
    return (
      <Link to={`/tenant/${t.name}`}><code>{t.name}</code></Link>
    );
  }

  // Render a pool cell as a Link when we can resolve the pool. The
  // wildcard form (`poolId === null`) collapses to a `*` glyph; we
  // link it to the tenant detail page so the operator can jump to the
  // pool list it covers.
  function poolCell(g: PoolPermissionResponse) {
    if (!g.poolId) {
      const tn = tenantById.get(g.tenantId)?.name;
      const star = <code>*</code>;
      return tn
        ? <Link to={`/tenant/${tn}`} title="every pool in this tenant">{star}</Link>
        : star;
    }
    const p = poolById.get(g.poolId);
    if (!p) return <code>{g.poolId}</code>;
    return (
      <Link to={`/pool/${p.tenant}/${p.tenantDb}/${p.pool}`}>
        <code>{p.pool}</code>
      </Link>
    );
  }

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
                  <td>{tenantCell(p.tenantId)}</td>
                  <td>{poolCell(p)}</td>
                  <td className="subtle">
                    {p.userId ? 'direct' : p.groupId ? `via group ${p.groupId}` : '-'}
                  </td>
                </tr>
              ))}</tbody>
            </table>
          )}

          <h4>Databases ({databases.length})</h4>
          {databases.length === 0 ? <div className="empty">(none)</div> : (
            <table>
              <thead><tr>
                <th>Tenant</th><th>Database</th><th>Pools</th>
              </tr></thead>
              <tbody>{databases.map(d => (
                <tr key={`${d.tenant}/${d.tenantDb}`}>
                  <td><Link to={`/tenant/${d.tenant}`}><code>{d.tenant}</code></Link></td>
                  <td><code>{d.tenantDb}</code></td>
                  <td>
                    {d.pools.map((pn, i) => (
                      <span key={pn}>
                        {i > 0 && ', '}
                        <Link to={`/pool/${d.tenant}/${d.tenantDb}/${pn}`}><code>{pn}</code></Link>
                      </span>
                    ))}
                  </td>
                </tr>
              ))}</tbody>
            </table>
          )}

          <h4>Quack nodes ({reachableNodes.length})</h4>
          {reachableNodes.length === 0 ? <div className="empty">(none)</div> : (
            <table>
              <thead><tr>
                <th>Pool</th><th>Node</th><th>Role</th><th>Endpoint</th><th>Status</th>
              </tr></thead>
              <tbody>{reachableNodes.map(n => (
                <tr key={n.key}>
                  <td>
                    <Link to={`/pool/${n.tenant}/${n.tenantDb}/${n.pool}`}>
                      <code>{n.tenant}/{n.pool}</code>
                    </Link>
                  </td>
                  <td><code>{n.nodeId}</code></td>
                  <td><code>{n.role}</code></td>
                  <td className="subtle">{n.host}:{n.port}</td>
                  <td className="subtle">
                    {n.healthy ? 'healthy' : 'unhealthy'}
                    {n.draining ? ' / draining' : ''}
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