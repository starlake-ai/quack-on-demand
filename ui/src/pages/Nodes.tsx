import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PoolResponse, NodeInfo, StatementHistoryEntry } from '../api/types';

interface Row extends NodeInfo {
  tenant: string;
  pool:   string;
  qps:    number; // computed client-side from totalServed delta
}

/** Snapshot kept between polls so we can compute per-node QPS without
  * the backend having to maintain a rate window. */
interface Sample { totalServed: number; t: number; }

const POLL_MS = 2000;

/** Live node dashboard. Polls /api/pool/list every 2s. p50/p95/p99 come
  * from the backend's rolling-window per-node histogram; QPS is derived
  * client-side from the delta in totalServed between polls. */
export default function Nodes() {
  const [rows, setRows] = useState<Row[]>([]);
  const [err, setErr]   = useState<string | null>(null);
  const [tenants, setTenants] = useState<string[]>([]);
  const [filter, setFilter]   = useState<string>('');
  const [history, setHistory] = useState<StatementHistoryEntry[]>([]);
  const [expanded, setExpanded] = useState<number | null>(null);
  const prev = useRef<Record<string, Sample>>({});

  function refresh() {
    api.statementHistory(50)
      .then(r => setHistory(r.statements))
      .catch(() => { /* best effort — pools still useful even if history fails */ });
    api.listPools()
      .then((r: { pools: PoolResponse[] }) => {
        const now = Date.now();
        const flat: Row[] = r.pools.flatMap(p =>
          p.nodes.map(n => {
            const key = `${p.tenant}/${p.pool}/${n.nodeId}`;
            const prior = prev.current[key];
            // First sample: QPS unknown, show 0 (instead of misleading values).
            let qps = 0;
            if (prior) {
              const dt = (now - prior.t) / 1000;
              if (dt > 0) qps = Math.max(0, (n.totalServed - prior.totalServed) / dt);
            }
            prev.current[key] = { totalServed: n.totalServed, t: now };
            return { ...n, tenant: p.tenant, pool: p.pool, qps };
          })
        );
        setRows(flat);
        const ts = Array.from(new Set(r.pools.map(p => p.tenant))).sort();
        setTenants(ts);
      })
      .catch(e => setErr(String(e)));
  }

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, POLL_MS);
    return () => clearInterval(id);
  }, []);

  const visible = filter ? rows.filter(r => r.tenant === filter) : rows;
  const sumTotal    = visible.reduce((s, n) => s + n.totalServed, 0);
  const sumInFlight = visible.reduce((s, n) => s + n.inFlight, 0);
  const sumQps      = visible.reduce((s, n) => s + n.qps, 0);
  const avgLat = visible.length === 0
    ? 0
    : visible.reduce((s, n) => s + n.avgDurationMs, 0) / visible.length;
  // Cluster p95 = max of per-node p95s (worst tail across the fleet —
  // operators want to see the worst, not the average of percentiles).
  const clusterP95 = visible.reduce((m, n) => Math.max(m, n.p95Ms), 0);
  const healthyCount = visible.filter(n => n.healthy && !n.draining).length;

  return (
    <>
      <div className="row" style={{ marginBottom: '1rem', justifyContent: 'space-between' }}>
        <h1>Quack Nodes</h1>
        <label style={{ marginBottom: 0 }}>
          Tenant filter
          <select value={filter} onChange={e => setFilter(e.target.value)} style={{ minWidth: 160 }}>
            <option value="">All tenants</option>
            {tenants.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
      </div>

      {err && <div className="login-err">{err}</div>}

      <div className="metric-grid" style={{ marginBottom: '1rem' }}>
        <div className="metric">
          <div className="label">Nodes</div>
          <div className="value">{visible.length}</div>
        </div>
        <div className="metric">
          <div className="label">Healthy</div>
          <div className="value">{healthyCount}<span className="unit">/ {visible.length}</span></div>
        </div>
        <div className="metric">
          <div className="label">In flight</div>
          <div className="value">{sumInFlight}</div>
        </div>
        <div className="metric">
          <div className="label">QPS</div>
          <div className="value">{sumQps.toFixed(1)}</div>
        </div>
        <div className="metric">
          <div className="label">Total served</div>
          <div className="value">{sumTotal.toLocaleString()}</div>
        </div>
        <div className="metric">
          <div className="label">Avg latency</div>
          <div className="value">{avgLat.toFixed(1)}<span className="unit">ms</span></div>
        </div>
        <div className="metric">
          <div className="label">Worst p95</div>
          <div className="value">{clusterP95.toFixed(0)}<span className="unit">ms</span></div>
        </div>
      </div>

      <div className="card" style={{ padding: 0 }}>
        <table>
          <thead>
            <tr>
              <th>Node</th>
              <th>Tenant / Pool</th>
              <th>Role</th>
              <th>Status</th>
              <th>Endpoint</th>
              <th style={{ textAlign: 'right' }}>In flight</th>
              <th style={{ textAlign: 'right' }}>QPS</th>
              <th style={{ textAlign: 'right' }}>Total served</th>
              <th style={{ textAlign: 'right' }}>Avg</th>
              <th style={{ textAlign: 'right' }}>p50</th>
              <th style={{ textAlign: 'right' }}>p95</th>
              <th style={{ textAlign: 'right' }}>p99</th>
              <th style={{ textAlign: 'right' }}>Max conc.</th>
            </tr>
          </thead>
          <tbody>
            {visible.length === 0 ? (
              <tr><td colSpan={13} className="empty">No nodes running.</td></tr>
            ) : visible.map(n => (
              <tr key={`${n.tenant}/${n.pool}/${n.nodeId}`}>
                <td><code>{n.nodeId}</code></td>
                <td>
                  <Link to={`/tenant/${n.tenant}`}>{n.tenant}</Link>
                  {' / '}
                  <Link to={`/pool/${n.tenant}/${n.pool}`}>{n.pool}</Link>
                </td>
                <td><RoleBadge role={n.role} /></td>
                <td><HealthBadge healthy={n.healthy} draining={n.draining} /></td>
                <td><code>{n.host}:{n.port}</code></td>
                <td style={{ textAlign: 'right' }}>{n.inFlight}</td>
                <td style={{ textAlign: 'right' }}>{n.qps.toFixed(1)}</td>
                <td style={{ textAlign: 'right' }}>{n.totalServed.toLocaleString()}</td>
                <td style={{ textAlign: 'right' }}>{n.avgDurationMs.toFixed(1)} ms</td>
                <td style={{ textAlign: 'right' }}>{n.p50Ms.toFixed(0)} ms</td>
                <td style={{ textAlign: 'right' }}>{n.p95Ms.toFixed(0)} ms</td>
                <td style={{ textAlign: 'right' }}>{n.p99Ms.toFixed(0)} ms</td>
                <td style={{ textAlign: 'right' }}>{n.maxConcurrent === 0 ? '∞' : n.maxConcurrent}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="subtle" style={{ textAlign: 'right' }}>
        Refreshing every {POLL_MS / 1000}s · QPS computed from delta; percentiles over a rolling 256-sample window per node.
      </p>

      <h2 style={{ marginTop: '2rem' }}>Recent statements</h2>
      <div className="card" style={{ padding: 0 }}>
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>User</th>
              <th>Tenant / Pool</th>
              <th>Node</th>
              <th>Status</th>
              <th style={{ textAlign: 'right' }}>Duration</th>
              <th>SQL</th>
            </tr>
          </thead>
          <tbody>
            {history.length === 0 ? (
              <tr><td colSpan={7} className="empty">No statements recorded yet.</td></tr>
            ) : history
                .filter(h => !filter || h.tenant === filter)
                .map((h, i) => {
                  const isOpen = expanded === i;
                  return (
                    <tr key={i} onClick={() => setExpanded(isOpen ? null : i)} style={{ cursor: 'pointer' }}>
                      <td className="subtle"><code>{shortTime(h.ts)}</code></td>
                      <td>{h.user}</td>
                      <td>
                        <Link to={`/tenant/${h.tenant}`}>{h.tenant}</Link>
                        {' / '}
                        <Link to={`/pool/${h.tenant}/${h.pool}`}>{h.pool}</Link>
                      </td>
                      <td><code>{h.nodeId}</code></td>
                      <td><StatusBadge status={h.status} /></td>
                      <td style={{ textAlign: 'right' }}>{h.durationMs} ms</td>
                      <td>
                        <pre style={{
                          margin: 0, fontSize: '.85em',
                          whiteSpace: isOpen ? 'pre-wrap' : 'nowrap',
                          overflow: isOpen ? 'visible' : 'hidden',
                          textOverflow: 'ellipsis',
                          maxWidth: isOpen ? 'none' : '500px',
                        }}>{h.sql.trim()}</pre>
                        {h.error && isOpen && (
                          <p className="login-err" style={{ marginTop: 4 }}>{h.error}</p>
                        )}
                      </td>
                    </tr>
                  );
                })}
          </tbody>
        </table>
      </div>
      <p className="subtle" style={{ textAlign: 'right' }}>
        Click a row to expand · last 50 statements · ring buffer (oldest evicted)
      </p>
    </>
  );
}

/** Human-readable time-of-day for the table; full ISO on hover via title would
  * also be nice but the row click already expands to full SQL + error. */
function shortTime(iso: string): string {
  try { return new Date(iso).toLocaleTimeString(); }
  catch { return iso; }
}

function StatusBadge({ status }: { status: string }) {
  const cls = status === 'ok' ? 'good'
            : status === 'denied' ? 'warn'
            : status === 'transient' ? 'warn'
            : 'bad';
  return <span className={`badge ${cls}`}>{status}</span>;
}

function RoleBadge({ role }: { role: string }) {
  const cls = `badge role-${role.toLowerCase()}`;
  return <span className={cls}>{role}</span>;
}

function HealthBadge({ healthy, draining }: { healthy: boolean; draining: boolean }) {
  if (draining) return <span className="badge warn">draining</span>;
  if (!healthy) return <span className="badge bad">unhealthy</span>;
  return <span className="badge good">healthy</span>;
}