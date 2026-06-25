import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { PoolResponse, TenantDbResponse } from '../api/types';
import PoolDetailBody from './PoolDetailBody';
import { DeleteIcon } from './Icons';
import CohortEditor, {
  CohortDraft,
  cohortDraftToWire,
  cohortsTotal,
  emptyCohort,
  PlacementUnsupportedWarning,
} from './CohortEditor';

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

  // Per-row Scale modal state. `scaling` is the pool currently being
  // resized (null = closed). The role counters mirror the same shape as
  // the Scale modal in <PoolDetailBody>.
  const [scaling, setScaling]   = useState<PoolResponse | null>(null);
  const [scaleRo, setScaleRo]   = useState(0);
  const [scaleWo, setScaleWo]   = useState(0);
  const [scaleDual, setScaleDual] = useState(0);
  const [scaleForce, setScaleForce] = useState(false);
  const [scaleErr, setScaleErr]     = useState<string | null>(null);

  // Form state.
  const [tenantDb, setTenantDb]   = useState('');
  const [poolName, setPoolName]   = useState('');
  const [ro, setRo]               = useState(0);
  const [wo, setWo]               = useState(0);
  const [dual, setDual]           = useState(1);
  const [maxConcurrent, setMaxConcurrent] = useState(0); // 0 = unlimited
  // When true, the pool is persisted with disabled=true so the edge
  // rejects fresh handshakes until the operator enables it. Useful for
  // pre-provisioning a pool before its tenant goes live.
  const [createDisabled, setCreateDisabled] = useState(false);
  // Operator-authored per-pool init SQL prepended to the federation blob
  // at node spawn (PRAGMAs / SET / INSTALL / LOAD). Empty by default.
  const [initSql, setInitSql] = useState('');

  // Placement plan. Always available; on non-K8s backends the cohorts
  // are persisted so a YAML export still survives, but the runtime
  // ignores them. `placementSupported` defaults to true; the effect
  // below corrects it from /api/config/client.
  const [placementSupported, setPlacementSupported] = useState(true);
  const [useCohorts, setUseCohorts] = useState(false);
  const [cohorts, setCohorts]       = useState<CohortDraft[]>([emptyCohort()]);

  const effective = useCohorts ? cohortsTotal(cohorts) : { wo, ro, dual };
  const size = effective.wo + effective.ro + effective.dual;

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
    api.clientConfig()
      .then(cfg => setPlacementSupported(!!cfg.placementSupported))
      .catch(() => setPlacementSupported(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);

  async function handleStop(p: PoolResponse, force: boolean) {
    setError(null);
    const mode = force ? 'FORCE' : 'DRAIN';
    if (!window.confirm(
      `Stop pool "${p.tenantDb}/${p.pool}" (${mode})?\n\n` +
      'The pool scales down to 0 nodes but is NOT deleted; scale it back up later.\n\n' +
      (force
        ? 'Nodes stop immediately; outstanding queries fail.'
        : 'Nodes stop accepting new queries first, then shut down.')
    )) return;
    try {
      await api.stopPool({ tenant, tenantDb: p.tenantDb, pool: p.pool, force });
      await reloadPools();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(p: PoolResponse) {
    setError(null);
    if (!window.confirm(
      `Delete pool "${p.tenantDb}/${p.pool}"?\n\n` +
      'This permanently removes the pool and all its nodes. ' +
      'Running nodes are force-stopped; outstanding queries fail.'
    )) return;
    try {
      await api.deletePool({ tenant, tenantDb: p.tenantDb, pool: p.pool, force: true });
      await reloadPools();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  function openScale(p: PoolResponse) {
    setScaleRo(p.nodes.filter(n => n.role === 'READONLY'  || n.role === 'ReadOnly').length);
    setScaleWo(p.nodes.filter(n => n.role === 'WRITEONLY' || n.role === 'WriteOnly').length);
    setScaleDual(p.nodes.filter(n => n.role === 'DUAL'    || n.role === 'Dual').length);
    setScaleForce(false);
    setScaleErr(null);
    setScaling(p);
  }
  function closeScale() { setScaling(null); setScaleErr(null); }

  async function submitScale(ev: React.FormEvent) {
    ev.preventDefault();
    if (!scaling) return;
    setScaleErr(null);
    const target = scaleRo + scaleWo + scaleDual;
    try {
      await api.scalePool({
        tenant, tenantDb: scaling.tenantDb, pool: scaling.pool,
        targetSize: target,
        roleDistribution: { writeonly: scaleWo, readonly: scaleRo, dual: scaleDual },
        force: scaleForce,
      });
      setScaling(null);
      await reloadPools();
    } catch (e) {
      setScaleErr(String(e));
    }
  }

  function resetForm() {
    setPoolName('');
    setRo(0);
    setWo(0);
    setDual(1);
    setMaxConcurrent(0);
    setUseCohorts(false);
    setCohorts([emptyCohort()]);
    setCreateDisabled(false);
    setInitSql('');
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
    const wireCohorts = useCohorts ? cohorts.map(cohortDraftToWire) : undefined;
    try {
      await api.createPool({
        tenant, tenantDb, pool: poolName, size,
        roleDistribution: {
          writeonly: effective.wo,
          readonly:  effective.ro,
          dual:      effective.dual,
        },
        maxConcurrentPerNode: maxConcurrent,
        ...(wireCohorts ? { cohorts: wireCohorts } : {}),
        ...(createDisabled ? { disabled: true } : {}),
        ...(initSql.trim() ? { initSql: initSql.trim() } : {}),
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
        <PoolDetailBody
          tenant={tenant}
          tenantDb={browsing.tenantDb}
          pool={browsing.pool}
          onStopped={() => { setBrowsing(null); void reloadPools(); }}
          onBack={() => setBrowsing(null)}
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
            type="button"
            className="link-button"
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
              <th className="actions">Actions</th>
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
                <td className="actions">
                  <button
                    type="button"
                    onClick={() => openScale(p)}
                    title="Resize this pool (per-role distribution)."
                  >
                    Scale
                  </button>
                  {' '}
                  <button
                    type="button"
                    onClick={() => void handleStop(p, false)}
                    aria-label={`Drain pool ${p.pool}`}
                    title="Drain: stop accepting new queries, then shut down. Scales to 0 nodes; the pool is kept."
                  >
                    Drain
                  </button>
                  {' '}
                  <button
                    type="button"
                    onClick={() => void handleStop(p, true)}
                    aria-label={`Force-stop pool ${p.pool}`}
                    title="Force: stop immediately; outstanding queries fail. Scales to 0 nodes; the pool is kept."
                  >
                    Force
                  </button>
                  {' '}
                  <button
                    type="button"
                    className="danger"
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
                    onClick={() => void handleDelete(p)}
                    aria-label={`Delete pool ${p.pool}`}
                    title="Delete: permanently remove the pool and all its nodes."
                  >
                    <DeleteIcon /> Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {adding && (
        <div
          className="modal-backdrop"
          onClick={cancelForm}
          style={{
            position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
            display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
            zIndex: 100, paddingTop: '4rem',
            // Outer is the scroll container: when the inner content (with
            // many cohorts) exceeds the viewport the modal scrolls
            // instead of running off the bottom of the screen.
            overflowY: 'auto',
            paddingBottom: '2rem',
          }}
        >
          <div
            className="modal card"
            onClick={ev => ev.stopPropagation()}
            style={{ width: '90%', maxWidth: 560 }}
          >
            <div className="card-title">New pool</div>
            <p className="subtle" style={{ marginTop: 0 }}>
              The pool inherits the metastore, data path, and object-store
              config from the database. Pool names must be unique within the
              tenant -- the server resolves <code>(tenant, pool)</code> to
              the owning database at handshake time.
            </p>
            <form onSubmit={handleCreate}>
              <label>
                Database
                <select value={tenantDb} onChange={ev => setTenantDb(ev.target.value)} required>
                  {tenantDbs.map(d => <option key={d.name} value={d.name}>{d.name}</option>)}
                </select>
              </label>
              <label>
                Pool name
                <input
                  value={poolName}
                  onChange={ev => setPoolName(ev.target.value)}
                  placeholder="sales"
                  required
                />
              </label>
              <fieldset style={{ marginTop: '0.5rem' }}>
                <legend>Node placement</legend>
                <label>
                  <input
                    type="checkbox"
                    checked={useCohorts}
                    onChange={ev => setUseCohorts(ev.target.checked)}
                  />{' '}
                  Pin nodes to Kubernetes node labels (cohorts)
                </label>
                {useCohorts && !placementSupported && <PlacementUnsupportedWarning />}
              </fieldset>
              {!useCohorts ? (
                <fieldset style={{ marginTop: '0.5rem' }}>
                  <legend>Role distribution (size = {size})</legend>
                  <div className="row" style={{ gap: 12, alignItems: 'center' }}>
                    <label>WriteOnly <input type="number" min={0} value={wo}   onChange={ev => setWo(+ev.target.value)}   style={{ width: 72 }} /></label>
                    <label>ReadOnly  <input type="number" min={0} value={ro}   onChange={ev => setRo(+ev.target.value)}   style={{ width: 72 }} /></label>
                    <label>Dual      <input type="number" min={0} value={dual} onChange={ev => setDual(+ev.target.value)} style={{ width: 72 }} /></label>
                  </div>
                </fieldset>
              ) : (
                <CohortEditor cohorts={cohorts} onChange={setCohorts} />
              )}
              <label>
                Max concurrent per node
                <input
                  type="number"
                  min={0}
                  value={maxConcurrent}
                  onChange={ev => setMaxConcurrent(+ev.target.value)}
                />
              </label>
              {maxConcurrent === 0 && (
                <p className="subtle" style={{ fontSize: '0.85em', marginTop: '-0.5rem' }}>(0 = unlimited)</p>
              )}
              <label style={{ display: 'block', marginTop: '.5rem' }}>
                <input
                  type="checkbox"
                  checked={createDisabled}
                  onChange={ev => setCreateDisabled(ev.target.checked)}
                />{' '}
                Create disabled (nodes spawn, but the edge rejects fresh handshakes until enabled)
              </label>
              <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
                <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={cancelForm}>Cancel</button>
                <button type="submit" style={{ minWidth: '7rem' }} disabled={size === 0 || !tenantDb || !poolName}>
                  Create
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {scaling && (
        <div
          className="modal-backdrop"
          onClick={closeScale}
          style={{
            position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
            display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
            zIndex: 100, paddingTop: '4rem',
            overflowY: 'auto',
            paddingBottom: '2rem',
          }}
        >
          <div
            className="modal card"
            onClick={ev => ev.stopPropagation()}
            style={{ width: '90%', maxWidth: 480 }}
          >
            <div className="card-title">Scale {scaling.tenant}/{scaling.tenantDb}/{scaling.pool}</div>
            <p className="subtle" style={{ marginTop: 0 }}>
              Current size: {scaling.nodes.length}. Target: {scaleRo + scaleWo + scaleDual}.
            </p>
            {scaleErr && <p style={{ color: 'var(--bad)' }}>{scaleErr}</p>}
            <form onSubmit={submitScale}>
              <fieldset>
                <legend>Role distribution</legend>
                {/* Keying each input by the pool's natural address forces a
                    remount when the Scale modal opens for a different pool.
                    Without the remount, WebKit's native spinner arrows do
                    not always fire a `input` event on a controlled
                    `<input value={0}>` whose DOM value already matches --
                    re-mounting clears that stale-equality state so spinner
                    clicks register as expected. The keys are stable across
                    re-renders for the same pool, so React preserves focus
                    and caret position. */}
                <div className="row" style={{ gap: 12, alignItems: 'center' }}>
                  <label>WriteOnly <input
                    key={`scale-wo-${scaling.pool}`}
                    type="number" min={0} step={1}
                    value={scaleWo}
                    onChange={e => setScaleWo(Math.max(0, Math.floor(Number(e.target.value) || 0)))}
                    style={{ width: 72 }} /></label>
                  <label>ReadOnly  <input
                    key={`scale-ro-${scaling.pool}`}
                    type="number" min={0} step={1}
                    value={scaleRo}
                    onChange={e => setScaleRo(Math.max(0, Math.floor(Number(e.target.value) || 0)))}
                    style={{ width: 72 }} /></label>
                  <label>Dual      <input
                    key={`scale-dual-${scaling.pool}`}
                    type="number" min={0} step={1}
                    value={scaleDual}
                    onChange={e => setScaleDual(Math.max(0, Math.floor(Number(e.target.value) || 0)))}
                    style={{ width: 72 }} /></label>
                </div>
              </fieldset>
              {scaleRo + scaleWo + scaleDual < scaling.nodes.length && (
                <label style={{ display: 'block', marginTop: '1rem', color: 'var(--bad)' }}>
                  <input type="checkbox" checked={scaleForce} onChange={e => setScaleForce(e.target.checked)} />
                  {' '}Force (skip graceful drain - outstanding queries fail)
                </label>
              )}
              <div className="row" style={{ display: 'flex', gap: '.5rem', marginTop: '1rem', justifyContent: 'flex-end' }}>
                <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={closeScale}>Cancel</button>
                <button type="submit" style={{ minWidth: '7rem' }} disabled={scaleRo + scaleWo + scaleDual === 0}>Apply</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
