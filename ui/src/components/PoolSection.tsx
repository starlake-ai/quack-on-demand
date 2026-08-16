import { useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { PoolResponse, TenantDbResponse } from '../api/types';
import PoolDetailBody from './PoolDetailBody';
import { CpuLimitSlider, MemLimitSlider } from './LimitSlider';
import { Modal } from './Modal';
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
  // Kubernetes pod resource limits. Checkbox enables; slider sets the value.
  const [cpuEnabled, setCpuEnabled] = useState(false);
  const [cpuSlider, setCpuSlider]   = useState(2);
  const [memEnabled, setMemEnabled] = useState(false);
  const [memSlider, setMemSlider]   = useState(8);

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
      .catch(e => setError(errorMessage(e)));
  }

  function reloadTenantDbs() {
    return api.listTenantDbs(tenant)
      .then(r => {
        setTenantDbs(r.tenantDbs);
        // Default the form to the first DB so the select isn't blank.
        if (r.tenantDbs.length > 0 && !tenantDb) setTenantDb(r.tenantDbs[0].name);
      })
      .catch(e => setError(errorMessage(e)));
  }

  useEffect(() => {
    void reloadPools();
    void reloadTenantDbs();
    api.clientConfig()
      .then(cfg => setPlacementSupported(!!cfg.placementSupported))
      .catch(() => setPlacementSupported(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant]);





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
    setCpuEnabled(false);
    setCpuSlider(2);
    setMemEnabled(false);
    setMemSlider(8);
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
      setError(errorMessage(e));
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
        ...(cpuEnabled ? { cpu: String(cpuSlider) } : {}),
        ...(memEnabled ? { memory: `${memSlider}Gi` } : {}),
      });
      const justCreated = { tenantDb, pool: poolName };
      setAdding(false);
      resetForm();
      await reloadPools();
      // Open the newly-created pool inline, same panel.
      setBrowsing(justCreated);
    } catch (e) {
      setError(errorMessage(e));
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
                  {p.suspended && <span className="subtle"> (hibernated)</span>}
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
        <Modal maxWidth={560} scrollBackdrop onClose={cancelForm}>
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
              <div className="row" style={{ gap: 20, alignItems: 'flex-start', marginTop: '.5rem', flexWrap: 'wrap' }}>
                <div>
                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                    <input
                      type="checkbox"
                      checked={cpuEnabled}
                      onChange={ev => setCpuEnabled(ev.target.checked)}
                    />
                    CPU limit
                  </label>
                  {cpuEnabled && <CpuLimitSlider value={cpuSlider} onChange={setCpuSlider} />}
                </div>
                <div>
                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                    <input
                      type="checkbox"
                      checked={memEnabled}
                      onChange={ev => setMemEnabled(ev.target.checked)}
                    />
                    Memory limit
                  </label>
                  {memEnabled && <MemLimitSlider value={memSlider} onChange={setMemSlider} />}
                </div>
              </div>
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
        </Modal>
      )}

    </div>
  );
}
