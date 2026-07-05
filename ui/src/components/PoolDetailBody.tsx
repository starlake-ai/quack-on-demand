import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { ClientConfigResponse, NodeInfo, PoolResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import Tabs from './Tabs';


/** Header (title + Back / Scale / Delete pool actions) + the four-tab
  * body for one pool. No breadcrumb -- callers compose that themselves.
  * The `onStopped` callback lets the standalone page navigate elsewhere
  * after a successful delete, while the inline view inside the Pools
  * tab just collapses back to its list. */
export default function PoolDetailBody({
  tenant,
  tenantDb,
  pool,
  onBack,
}: {
  tenant:    string;
  tenantDb:  string;
  pool:      string;
  onStopped?: () => void;
  /** Custom Back-button handler. When provided, the header renders a
    * "Back to pools" button that calls this (used by PoolSection to
    * collapse the inline view). When omitted, the header falls back to
    * a `<Link>` back to the tenant page (used by the standalone
    * `/pool/...` route). */
  onBack?:   () => void;
}) {

  const { superuser: isSuperuser } = useAuth();
  const [data, setData] = useState<PoolResponse | null>(null);
  const [cfg, setCfg]   = useState<ClientConfigResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);

  // Pool resource edit state (Nodes tab). Checkbox enables; slider sets the value.
  const [resCpuEnabled, setResCpuEnabled] = useState(false);
  const [resCpuSlider, setResCpuSlider]   = useState(2);
  const [resMemEnabled, setResMemEnabled] = useState(false);
  const [resMemSlider, setResMemSlider]   = useState(8);
  const [resSaving, setResSaving]         = useState(false);
  const [resInitialized, setResInitialized] = useState(false);
  // Round-trip flags: true when the stored API value can be represented exactly
  // by the slider (so slider state is canonical for that dimension).
  const [cpuRoundTrips, setCpuRoundTrips] = useState(false);
  const [memRoundTrips, setMemRoundTrips] = useState(false);
  // Touch flags: set true when the user actively interacts with a dimension.
  // An untouched dimension whose stored value does NOT round-trip is preserved
  // verbatim on Save so an operator-set millicore / MiB value isn't silently
  // clamped by the slider.
  const [cpuTouched, setCpuTouched] = useState(false);
  const [memTouched, setMemTouched] = useState(false);
  // Raw stored values kept for verbatim preservation on untouched dimensions.
  const [rawCpu, setRawCpu]       = useState('');
  const [rawMemory, setRawMemory] = useState('');

  useEffect(() => {
    api.clientConfig().then(setCfg).catch(e => setError(String(e)));
  }, []);

  useEffect(() => {
    if (!tenant || !tenantDb || !pool) return;
    let cancelled = false;
    const fetchOnce = () =>
      api.poolStatus(tenant, tenantDb, pool)
        .then(r => { if (!cancelled) setData(r); })
        .catch(e => { if (!cancelled) setError(String(e)); });
    fetchOnce();
    const id = setInterval(fetchOnce, 2000);
    return () => { cancelled = true; clearInterval(id); };
  }, [tenant, tenantDb, pool]);

  // Populate slider state from the first successful poll; ignore
  // subsequent polls so in-progress edits are not overwritten.
  useEffect(() => {
    if (data && !resInitialized) {
      const storedCpu = data.cpu || '';
      const storedMem = data.memory || '';
      setRawCpu(storedCpu);
      setRawMemory(storedMem);

      if (storedCpu) {
        const parsed = parseFloat(storedCpu);
        // A CPU value round-trips through the slider only when it is a plain
        // decimal number (no suffix) in the slider range [0.5, 16].
        const rt = /^\d+(\.\d+)?$/.test(storedCpu) && !isNaN(parsed) && parsed >= 0.5 && parsed <= 16;
        setCpuRoundTrips(rt);
        if (rt) {
          setResCpuEnabled(true);
          setResCpuSlider(parsed);
        }
      }

      if (storedMem) {
        const parsed = parseInt(storedMem.replace(/Gi$/, ''), 10);
        // A memory value round-trips only when it is an integer Gi value in
        // the slider range [1, 64].
        const rt = /^\d+Gi$/.test(storedMem) && !isNaN(parsed) && parsed >= 1 && parsed <= 64;
        setMemRoundTrips(rt);
        if (rt) {
          setResMemEnabled(true);
          setResMemSlider(parsed);
        }
      }

      setResInitialized(true);
    }
  }, [data, resInitialized]);

  async function saveResources() {
    setResSaving(true);
    setActionErr(null);
    try {
      // For each dimension: if the user touched it (or the stored value round-
      // trips cleanly through the slider) we send the slider-derived value.
      // Otherwise we preserve the raw API-set value verbatim so an operator
      // who set cpu="500m" via the API and then opens this page and clicks
      // Save without touching the slider doesn't lose their setting.
      const cpu = (cpuTouched || cpuRoundTrips)
        ? (resCpuEnabled ? String(resCpuSlider) : '')
        : rawCpu;
      const memory = (memTouched || memRoundTrips)
        ? (resMemEnabled ? `${resMemSlider}Gi` : '')
        : rawMemory;
      await api.setPoolResources({ tenant, tenantDb, pool, cpu, memory });
    } catch (e) {
      setActionErr(e instanceof ApiError ? e.message : String(e));
    } finally {
      setResSaving(false);
    }
  }

  /** Build the host the user's clients should target. Substitutes the
    * browser hostname when the server-advertised host is a bind-address
    * like 0.0.0.0. */
  function effectiveHost(host: string): string {
    if (!host || host === '0.0.0.0' || host === '::' || host === '0:0:0:0:0:0:0:0') {
      return typeof window !== 'undefined' ? window.location.hostname : 'localhost';
    }
    return host;
  }

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (!data)  return <p>Loading…</p>;

  async function toggleQuarantine(n: NodeInfo) {
    if (!data) return;
    if (!n.quarantined) {
      const peers = data.nodes.filter(r =>
        r.nodeId !== n.nodeId && r.healthy && !r.draining && !r.quarantined);
      const lastWarning = peers.length === 0
        ? '\n\nWARNING: this is the pool\'s last routable node. The pool will refuse new statements until it is un-quarantined.'
        : '';
      if (!window.confirm(
        `Quarantine node "${n.nodeId}"?\n\n` +
        `New statements stop routing to it; running statements finish normally. ` +
        `Only an explicit un-quarantine restores it.${lastWarning}`)) return;
    }
    try {
      const req = { tenant, tenantDb, pool, nodeId: n.nodeId };
      if (n.quarantined) await api.unquarantineNode(req);
      else await api.quarantineNode(req);
      setActionErr(null);
    } catch (e) {
      setActionErr(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function restartNode(n: NodeInfo) {
    if (!window.confirm(
      `Restart node "${n.nodeId}"?\n\n` +
      `All statements currently running on it will fail. ` +
      `The node respawns with the same id and comes back un-quarantined.`)) return;
    try {
      await api.restartNode({ tenant, tenantDb, pool, nodeId: n.nodeId });
      setActionErr(null);
    } catch (e) {
      setActionErr(e instanceof ApiError ? e.message : String(e));
    }
  }

  const nodesTab = (
    <div className="card">
      <div className="card-title">Nodes</div>
      <div style={{ marginBottom: '0.75rem' }}>
        <div className="row" style={{ gap: 20, alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div>
            {rawCpu && !cpuRoundTrips && !cpuTouched ? (
              <div style={{ marginBottom: 4 }}>
                <span className="subtle">CPU: {rawCpu} (set via API)</span>
                {' '}
                <button
                  type="button"
                  className="copy-btn"
                  onClick={() => {
                    const parsed = parseFloat(rawCpu);
                    setResCpuSlider(isNaN(parsed) ? 2 : Math.min(16, Math.max(0.5, parsed)));
                    setResCpuEnabled(true);
                    setCpuTouched(true);
                  }}
                >Adjust with slider</button>
              </div>
            ) : (
              <>
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                  <input
                    type="checkbox"
                    checked={resCpuEnabled}
                    onChange={e => { setResCpuEnabled(e.target.checked); setCpuTouched(true); }}
                  />
                  CPU limit
                </label>
                {resCpuEnabled && (
                  <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                    <input
                      type="range"
                      min={0.5}
                      max={16}
                      step={0.5}
                      value={resCpuSlider}
                      onChange={e => { setResCpuSlider(Number(e.target.value)); setCpuTouched(true); }}
                      style={{ width: 140 }}
                    />
                    <span className="subtle">{resCpuSlider} cores</span>
                  </div>
                )}
              </>
            )}
          </div>
          <div>
            {rawMemory && !memRoundTrips && !memTouched ? (
              <div style={{ marginBottom: 4 }}>
                <span className="subtle">Memory: {rawMemory} (set via API)</span>
                {' '}
                <button
                  type="button"
                  className="copy-btn"
                  onClick={() => {
                    const parsed = parseInt(rawMemory.replace(/Gi$/, ''), 10);
                    setResMemSlider(isNaN(parsed) ? 8 : Math.min(64, Math.max(1, parsed)));
                    setResMemEnabled(true);
                    setMemTouched(true);
                  }}
                >Adjust with slider</button>
              </div>
            ) : (
              <>
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                  <input
                    type="checkbox"
                    checked={resMemEnabled}
                    onChange={e => { setResMemEnabled(e.target.checked); setMemTouched(true); }}
                  />
                  Memory limit
                </label>
                {resMemEnabled && (
                  <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                    <input
                      type="range"
                      min={1}
                      max={64}
                      step={1}
                      value={resMemSlider}
                      onChange={e => { setResMemSlider(Number(e.target.value)); setMemTouched(true); }}
                      style={{ width: 140 }}
                    />
                    <span className="subtle">{resMemSlider} Gi</span>
                  </div>
                )}
              </>
            )}
          </div>
          <div>
            <button
              type="button"
              disabled={resSaving}
              onClick={() => void saveResources()}
            >
              {resSaving ? 'Saving...' : 'Save'}
            </button>
          </div>
        </div>
        <p className="subtle" style={{ fontSize: '0.85em', marginTop: '0.4rem', marginBottom: 0 }}>
          Restart nodes to apply resource changes (Kubernetes only).
        </p>
      </div>
      {actionErr && <div className="login-err">{actionErr}</div>}
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th align="left">Node</th>
            <th align="left">Role</th>
            <th align="left">Host</th>
            <th align="left">Port</th>
            <th align="left">Status</th>
            <th align="left">Max concurrent</th>
            {isSuperuser && <th align="left">Actions</th>}
          </tr>
        </thead>
        <tbody>
          {data.nodes.map(n => (
            <tr key={n.nodeId} style={{ borderTop: '1px solid #eee' }}>
              <td>
                <Link
                  to={`/nodes?tenant=${encodeURIComponent(data.tenant)}&node=${encodeURIComponent(n.nodeId)}`}
                  style={{ textDecoration: 'none' }}
                >
                  <code>{n.nodeId}</code>
                </Link>
              </td>
              <td>{n.role}</td>
              <td>{n.host}</td>
              <td>{n.port}</td>
              <td>
                <span className={
                  n.quarantined ? 'badge warn' :
                  n.draining ? 'badge warn' :
                  n.healthy ? 'badge good' : 'badge bad'
                }>
                  {n.quarantined ? 'quarantined' : n.draining ? 'draining' : n.healthy ? 'healthy' : 'unhealthy'}
                </span>
              </td>
              <td>
                <input
                  type="number"
                  min={0}
                  defaultValue={n.maxConcurrent}
                  style={{ width: 60 }}
                  onBlur={async e => {
                    const max = Number(e.target.value);
                    if (Number.isFinite(max) && max !== n.maxConcurrent) {
                      await api.setMaxConcurrent({ tenant, tenantDb, pool, nodeId: n.nodeId, max });
                    }
                  }}
                />
                <span style={{ marginLeft: 4, color: '#888' }}>
                  {n.maxConcurrent === 0 ? '(unlimited)' : ''}
                </span>
              </td>
              {isSuperuser && (
                <td style={{ whiteSpace: 'nowrap' }}>
                  <button type="button" className="copy-btn" onClick={() => void toggleQuarantine(n)}>
                    {n.quarantined ? 'Unquarantine' : 'Quarantine'}
                  </button>{' '}
                  <button type="button" className="copy-btn" onClick={() => void restartNode(n)}>
                    Restart
                  </button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  const connectionsTab = (
    <div className="card">
      <div className="card-title">Client connection</div>
      {!cfg ? (
        <p className="subtle">Loading client config…</p>
      ) : (() => {
        // System-realm users (qodstate_user.tenant IS NULL) must additionally
        // pass `superuser=true` on every FlightSQL connection. The flag picks
        // the realm; the tenant and pool params still drive query routing.
        // When the operator viewing this page is a superuser themselves, the
        // recipes below pre-fill superuser=true so the copy/paste URL works.
        const host = effectiveHost(cfg.flightSqlHost);
        const port = cfg.flightSqlPort;
        const scheme = 'arrow-flight-sql';
        const tlsQuery = cfg.flightSqlTls
          ? 'useEncryption=true&disableCertificateVerification=true'
          : 'useEncryption=false';
        const superuserQuery = isSuperuser ? '&superuser=true' : '';
        const jdbc =
          `jdbc:${scheme}://${host}:${port}?${tlsQuery}` +
          `&tenant=${encodeURIComponent(tenant)}` +
          `&pool=${encodeURIComponent(pool)}` +
          superuserQuery +
          `&user=<user>&password=<password>`;
        const adbcScheme = cfg.flightSqlTls ? 'grpc+tls' : 'grpc';
        const adbcUri = `${adbcScheme}://${host}:${port}`;
        const odbcSuperuser = isSuperuser ? ';superuser=true' : '';
        const odbc =
          `Driver={Arrow Flight SQL ODBC Driver};Host=${host};Port=${port}` +
          `;UseEncryption=${cfg.flightSqlTls ? '1' : '0'}` +
          `;tenant=${tenant};pool=${pool}${odbcSuperuser};UID=<user>;PWD=<password>`;
        const tlsKwarg = cfg.flightSqlTls
          ? `, "adbc.flight.sql.client_option.tls_skip_verify": "true"`
          : '';
        const adbcSuperuserKwarg = isSuperuser
          ? `, "adbc.flight.sql.rpc.call_header.superuser": "true"`
          : '';
        const adbcSnippet =
          `adbc_driver_flightsql.connect(uri="${adbcUri}", db_kwargs={` +
          `"username": "<user>", "password": "<password>", ` +
          `"adbc.flight.sql.rpc.call_header.tenant": "${tenant}", ` +
          `"adbc.flight.sql.rpc.call_header.pool": "${pool}"${adbcSuperuserKwarg}${tlsKwarg}})`;
        return (
          <>
            <p style={{ color: '#888', marginTop: 0 }}>
              Route clients through the FlightSQL edge for capacity-aware,
              role-respecting load balancing. Pass the target as
              {' '}<code>?tenant=…&amp;pool=…</code> URL params; the owning
              database is resolved server-side (pool names are unique per
              tenant). System-realm superusers add
              {' '}<code>&amp;superuser=true</code> alongside {' '}<code>tenant</code>
              {' '}and {' '}<code>pool</code> (the tenant/pool params still
              drive query routing; the flag only picks which realm validates
              the credential and bypasses the per-statement ACL gate). The
              recipes below pre-fill the flag when the operator viewing this
              page is a superuser. You can also bypass the edge and talk to
              one specific Quack node - see "Direct node URIs" below.
            </p>
            <table>
              <tbody>
                <tr><th align="left">JDBC</th><td><code>{jdbc}</code></td></tr>
                <tr><th align="left">ODBC</th><td><code>{odbc}</code></td></tr>
                <tr><th align="left">ADBC (Python)</th><td><code>{adbcSnippet}</code></td></tr>
              </tbody>
            </table>
            <h4 style={{ marginBottom: 4 }}>Direct node URIs (DuckDB <code>quack</code> extension)</h4>
            <ul style={{ marginTop: 0 }}>
              {data.nodes.map(n => (
                <li key={n.nodeId}>
                  <code>ATTACH 'quack:{n.host}:{n.port}' AS remote (TYPE quack, TOKEN '&lt;token&gt;');</code>
                  {' - token visible in '}<code>state/quack-on-demand-state.json</code>
                </li>
              ))}
            </ul>
          </>
        );
      })()}
    </div>
  );

  const placementTab = (
    <div className="card">
      <div className="card-title">Node placement</div>
      {(!data.cohorts || data.cohorts.length === 0) ? (
        <p style={{ color: '#888' }}>
          No placement plan: every node is scheduled wherever the cluster's
          default scheduler chooses (or, in local mode, as a child process).
          To pin nodes to specific Kubernetes node labels, recreate the pool
          and tick "Pin nodes to Kubernetes node labels" in the New pool form.
        </p>
      ) : (
        <>
          <p style={{ color: '#888', marginTop: 0 }}>
            The pool was created with {data.cohorts.length} cohort
            {data.cohorts.length === 1 ? '' : 's'}. Each cohort's nodes are
            scheduled only on Kubernetes nodes whose labels match every
            entry in its <code>nodeSelector</code>.
          </p>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th align="left">#</th>
                <th align="left">Roles</th>
                <th align="left">nodeSelector</th>
                <th align="left">Tolerations</th>
              </tr>
            </thead>
            <tbody>
              {data.cohorts.map((c, i) => {
                const selectorEntries = Object.entries(c.placement?.nodeSelector ?? {});
                const tolerations = c.placement?.tolerations ?? [];
                return (
                  <tr key={i} style={{ borderTop: '1px solid var(--border)' }}>
                    <td><code>{i + 1}</code></td>
                    <td>
                      {c.distribution.writeonly > 0 && <span>WO×{c.distribution.writeonly} </span>}
                      {c.distribution.readonly  > 0 && <span>RO×{c.distribution.readonly} </span>}
                      {c.distribution.dual      > 0 && <span>Dual×{c.distribution.dual}</span>}
                    </td>
                    <td>
                      {selectorEntries.length === 0
                        ? <span style={{ color: '#888' }}>(none)</span>
                        : selectorEntries.map(([k, v]) => (
                            <div key={k}><code>{k}={v}</code></div>
                          ))}
                    </td>
                    <td>
                      {tolerations.length === 0
                        ? <span style={{ color: '#888' }}>(none)</span>
                        : tolerations.map((t, ti) => (
                            <div key={ti}>
                              <code>
                                {t.key}
                                {t.operator && t.operator !== 'Equal' ? ` ${t.operator}` : ''}
                                {t.value ? `=${t.value}` : ''}
                                {t.effect ? ` :${t.effect}` : ''}
                              </code>
                            </div>
                          ))}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </>
      )}
    </div>
  );

  const storageTab = (
    <div className="card">
      <div className="card-title">Storage</div>
      {Object.keys(data.metastore).length === 0 ? (
        <p style={{ color: '#888' }}>(no effective metastore - manager defaults apply)</p>
      ) : (
        <table>
          <tbody>
            {data.metastore.dataPath && (
              <tr><th align="left">Data path</th><td><code>{data.metastore.dataPath}</code></td></tr>
            )}
            {data.metastore.dbName && (
              <tr><th align="left">Catalog DB</th><td><code>{data.metastore.dbName}</code></td></tr>
            )}
            {data.metastore.schemaName && (
              <tr><th align="left">Schema</th><td><code>{data.metastore.schemaName}</code></td></tr>
            )}
            {data.metastore.pgHost && (
              <tr><th align="left">Postgres</th>
                <td><code>{data.metastore.pgUser || '?'}@{data.metastore.pgHost}:{data.metastore.pgPort || '5432'}</code></td>
              </tr>
            )}
            {Object.entries(data.metastore)
              .filter(([k]) => !['dataPath', 'dbName', 'schemaName', 'pgHost', 'pgPort', 'pgUser', 'pgPassword'].includes(k))
              .map(([k, v]) => (
                <tr key={k}><th align="left">{k}</th><td><code>{v}</code></td></tr>
              ))}
          </tbody>
        </table>
      )}
    </div>
  );

  return (
    <>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>{data.tenant} / {data.tenantDb} / {data.pool}</h2>
        <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'nowrap', alignItems: 'center' }}>
          {onBack
            ? <button type="button" className="link-button" onClick={onBack}>← Back to pools</button>
            : (
              <Link to={`/tenant/${encodeURIComponent(data.tenant)}`}>
                <button type="button" className="link-button">← Back to pools</button>
              </Link>
            )}
        </div>
      </header>

      <Tabs
        tabs={[
          { id: 'nodes',       label: 'Nodes',       body: nodesTab },
          { id: 'connections', label: 'Connections', body: connectionsTab },
          { id: 'storage',     label: 'Storage',     body: storageTab },
          { id: 'placement',   label: 'Placement',   body: placementTab },
        ]}
      />
    </>
  );
}
