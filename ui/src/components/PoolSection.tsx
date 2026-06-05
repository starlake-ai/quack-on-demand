import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { PoolResponse, TenantDbResponse } from '../api/types';
import PoolDetailBody from './PoolDetailBody';

/** Pools card for the TenantDetail page. Mirrors DatabaseSection's
  * shape: list pools, plus an inline "+ New pool" form that opens
  * below the table instead of navigating to a dedicated route. */
export default function PoolSection({ tenant }: { tenant: string }) {
  const [pools, setPools]         = useState<PoolResponse[]>([]);
  const [tenantDbs, setTenantDbs] = useState<TenantDbResponse[]>([]);
  const [error, setError]         = useState<string | null>(null);
  const [adding, setAdding]       = useState(false);
  // null = show the list; otherwise the (tenantDb, pool) being browsed
  // inline via <PoolDetailBody>. Clicking "Back" returns to the list
  // without leaving the Pools tab.
  const [browsing, setBrowsing]   = useState<{ tenantDb: string; pool: string } | null>(null);

  // Form state.
  const [tenantDb, setTenantDb]   = useState('');
  const [poolName, setPoolName]   = useState('');
  const [ro, setRo]               = useState(0);
  const [wo, setWo]               = useState(0);
  const [dual, setDual]           = useState(1);
  const [maxConcurrent, setMaxConcurrent] = useState(0); // 0 = unlimited

  const size = ro + wo + dual;

  function reloadPools() {
    return api.listPools()
      .then(r => setPools(r.pools.filter(p => p.tenant === tenant)))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  function reloadTenantDbs() {
    return api.listTenantDbs(tenant)
      .then(r => {
        setTenantDbs(r.tenantDbs);
        // Default the form to the first DB so the select isn't blank.
        if (r.tenantDbs.length > 0 && !tenantDb) setTenantDb(r.tenantDbs[0].name);
      })
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => {
    void reloadPools();
    void reloadTenantDbs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);

  function resetForm() {
    setPoolName('');
    setRo(0);
    setWo(0);
    setDual(1);
    setMaxConcurrent(0);
    setError(null);
    if (tenantDbs.length > 0) setTenantDb(tenantDbs[0].name);
  }

  function openForm() {
    resetForm();
    setAdding(true);
  }

  function cancelForm() {
    setAdding(false);
    resetForm();
  }

  async function togglePool(p: PoolResponse) {
    setError(null);
    const next = !p.disabled;
    // Optimistic so the toggle feels instant.
    setPools(curr => curr.map(x =>
      x.tenantDb === p.tenantDb && x.pool === p.pool ? { ...x, disabled: next } : x
    ));
    try {
      await api.setPoolDisabled({
        tenant, tenantDb: p.tenantDb, pool: p.pool, disabled: next,
      });
    } catch (e) {
      setPools(curr => curr.map(x =>
        x.tenantDb === p.tenantDb && x.pool === p.pool ? { ...x, disabled: !next } : x
      ));
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!tenantDb) { setError('pick a tenant database'); return; }
    try {
      await api.createPool({
        tenant, tenantDb, pool: poolName, size,
        roleDistribution: { writeonly: wo, readonly: ro, dual },
        maxConcurrentPerNode: maxConcurrent,
      });
      const justCreated = { tenantDb, pool: poolName };
      setAdding(false);
      resetForm();
      await reloadPools();
      // Open the newly-created pool inline, same panel.
      setBrowsing(justCreated);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  // Browsing-a-pool mode: render PoolDetailBody (title + 3 tabs) with a
  // back button at the top, scoped to the chosen (tenantDb, pool).
  if (browsing != null) {
    return (
      <div className="card">
        <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
          <div className="card-title" style={{ margin: 0 }}>
            Pool &mdash; <code>{browsing.tenantDb}</code> / <code>{browsing.pool}</code>
          </div>
          <button onClick={() => setBrowsing(null)}>&larr; Back to pools</button>
        </div>
        <PoolDetailBody
          tenant={tenant}
          tenantDb={browsing.tenantDb}
          pool={browsing.pool}
          onStopped={() => { setBrowsing(null); void reloadPools(); }}
          showBack={false}
        />
      </div>
    );
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Pools</div>
        {!adding && (
          <button
            onClick={openForm}
            disabled={tenantDbs.length === 0}
            title={tenantDbs.length === 0 ? 'Create a database first on the Databases tab' : undefined}
          >
            + New pool
          </button>
        )}
      </div>
      {error && <div className="login-err">Error: {error}</div>}
      {pools.length === 0 ? (
        <div className="empty">No pools yet.</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th align="left">Database</th>
              <th align="left">Pool</th>
              <th align="right">Nodes</th>
              <th align="right">Enabled</th>
            </tr>
          </thead>
          <tbody>
            {pools.map(p => (
              <tr
                key={`${p.tenantDb}/${p.pool}`}
                style={{ borderTop: '1px solid #eee', opacity: p.disabled ? 0.55 : 1 }}
              >
                <td><code>{p.tenantDb}</code></td>
                <td>
                  <a
                    href="#"
                    onClick={ev => { ev.preventDefault(); setBrowsing({ tenantDb: p.tenantDb, pool: p.pool }); }}
                    title="Open this pool"
                  >
                    {p.pool}
                  </a>
                  {p.disabled && <span className="subtle"> (disabled)</span>}
                </td>
                <td align="right">{p.nodes.length}</td>
                <td align="right">
                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                    <input
                      type="checkbox"
                      checked={!p.disabled}
                      onChange={() => void togglePool(p)}
                      aria-label={`Toggle pool ${p.pool} enabled`}
                    />
                    <span className="subtle">{p.disabled ? 'off' : 'on'}</span>
                  </label>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {adding && (
        <form onSubmit={handleCreate} style={{ marginTop: '0.75rem' }}>
          <fieldset>
            <legend>Identity</legend>
            <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
              <label style={{ display: 'flex', flexDirection: 'column' }}>
                <span>Database</span>
                <select value={tenantDb} onChange={ev => setTenantDb(ev.target.value)} required>
                  {tenantDbs.map(d => <option key={d.name} value={d.name}>{d.name}</option>)}
                </select>
              </label>
              <label style={{ display: 'flex', flexDirection: 'column' }}>
                <span>Pool name</span>
                <input
                  value={poolName}
                  onChange={ev => setPoolName(ev.target.value)}
                  placeholder="sales"
                  required
                />
              </label>
            </div>
            <p className="subtle" style={{ marginTop: 4 }}>
              The pool inherits the metastore, data path, and object-store
              config from the database. Pool names must be unique within the
              tenant -- the server resolves <code>(tenant, pool)</code> to
              the owning database at handshake time.
            </p>
          </fieldset>

          <fieldset style={{ marginTop: '0.5rem' }}>
            <legend>Role distribution (size = {size})</legend>
            <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
              <label style={{ display: 'flex', flexDirection: 'column' }}>
                <span>WriteOnly</span>
                <input type="number" min={0} value={wo}   onChange={ev => setWo(+ev.target.value)}   style={{ width: 100 }} />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column' }}>
                <span>ReadOnly</span>
                <input type="number" min={0} value={ro}   onChange={ev => setRo(+ev.target.value)}   style={{ width: 100 }} />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column' }}>
                <span>Dual</span>
                <input type="number" min={0} value={dual} onChange={ev => setDual(+ev.target.value)} style={{ width: 100 }} />
              </label>
            </div>
          </fieldset>

          <fieldset style={{ marginTop: '0.5rem' }}>
            <legend>Concurrency</legend>
            <label style={{ display: 'flex', flexDirection: 'column' }}>
              <span>Max concurrent per node</span>
              <div className="row" style={{ gap: 8 }}>
                <input
                  type="number"
                  min={0}
                  value={maxConcurrent}
                  onChange={ev => setMaxConcurrent(+ev.target.value)}
                  style={{ width: 100 }}
                />
                <span className="subtle">
                  {maxConcurrent === 0 ? '(0 = unlimited)' : ''}
                </span>
              </div>
            </label>
          </fieldset>

          <div className="row" style={{ gap: 8, marginTop: '0.75rem' }}>
            <button type="submit" disabled={size === 0 || !tenantDb || !poolName}>
              Create
            </button>
            <button type="button" onClick={cancelForm}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  );
}
