import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, errorMessage } from '../api/client';
import type { FleetServer } from '../api/types';
import { fmtBytes } from '../format';

const POLL_MS = 5000;

/** Fleet backend server inventory (QOD_RUNTIME_TYPE=fleet). Polls
  * /api/fleet/servers every 5s, same pattern as Nodes.tsx. A manager that
  * does not run the fleet backend answers 400 fleet_disabled - render one
  * explanatory sentence instead of an error banner. */
export default function Servers() {
  const [servers, setServers] = useState<FleetServer[]>([]);
  const [loading, setLoading] = useState(true);
  // Poll-cycle error (e.g. a transient fetch failure). Cleared on the next
  // successful poll.
  const [err, setErr] = useState<string | null>(null);
  // Error from a drain/undrain/remove click, kept separate from `err` so a
  // subsequent successful poll doesn't silently wipe it before the operator
  // has seen it - cleared only by starting another action or dismissing it.
  const [actionErr, setActionErr] = useState<string | null>(null);
  const [disabled, setDisabled] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  async function load() {
    try {
      const r = await api.listServers();
      setServers(r.servers);
      setErr(null);
      setDisabled(false);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'fleet_disabled') {
        setDisabled(true);
        // Nothing will ever turn this manager into a fleet backend without a
        // restart - stop polling a route that will keep 400ing.
        if (pollRef.current != null) {
          clearInterval(pollRef.current);
          pollRef.current = null;
        }
      } else {
        setErr(errorMessage(e));
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
    pollRef.current = setInterval(() => void load(), POLL_MS);
    return () => {
      if (pollRef.current != null) clearInterval(pollRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function act(fn: () => Promise<void>) {
    setActionErr(null);
    try {
      await fn();
      await load();
    } catch (e) {
      setActionErr(errorMessage(e));
    }
  }

  if (disabled) {
    return (
      <>
        <h1>Servers</h1>
        <p className="subtle">
          This manager does not run the fleet runtime (QOD_RUNTIME_TYPE=fleet).
        </p>
      </>
    );
  }

  return (
    <>
      <h1>Servers</h1>
      {err && <div className="login-err">{err}</div>}
      {actionErr && (
        <div className="login-err">
          {actionErr}{' '}
          <button type="button" className="copy-btn" onClick={() => setActionErr(null)}>Dismiss</button>
        </div>
      )}
      <div className="card" style={{ padding: 0 }}>
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Address</th>
              <th>Liveness</th>
              <th>Capacity</th>
              <th>Node</th>
              <th>Pool</th>
              <th>State</th>
              <th>Agent</th>
              <th className="actions">Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={9} className="empty">Loading…</td></tr>
            ) : servers.length === 0 ? (
              <tr>
                <td colSpan={9} className="empty">
                  No server has joined yet. Run <code>qod agent --manager ... --join-token ...</code> on a server.
                </td>
              </tr>
            ) : servers.map(s => {
              const removeDisabled = s.liveness === 'reachable' && !s.unschedulable;
              return (
                <tr
                  key={s.name}
                  className={s.liveness === 'dead' ? 'row-dead' : s.liveness === 'unreachable' ? 'row-warn' : undefined}
                >
                  <td>
                    <code>{s.name}</code>
                    {s.unschedulable && <span className="badge warn" style={{ marginLeft: 6 }}>drained</span>}
                  </td>
                  <td><code>{s.advertiseHost}:{s.nodePort}</code></td>
                  <td>
                    <LivenessBadge liveness={s.liveness} />
                    {s.liveness !== 'reachable' && (
                      <span className="subtle" style={{ marginLeft: 6 }}>silent {s.silentSeconds}s</span>
                    )}
                  </td>
                  <td>
                    {s.cpus != null ? `${s.cpus} cores` : '-'} / {fmtBytes(s.memoryBytes)}
                  </td>
                  <td>{s.assignedNodeId ? <code>{s.assignedNodeId}</code> : <span className="subtle">-</span>}</td>
                  <td>
                    {s.tenant ? (
                      <Link to={`/pool/${encodeURIComponent(s.tenant)}/${encodeURIComponent(s.tenantDb ?? '')}/${encodeURIComponent(s.pool ?? '')}`}>
                        {s.tenant}/{s.tenantDb}/{s.pool}
                      </Link>
                    ) : <span className="subtle">-</span>}
                  </td>
                  <td title={s.nodeError ?? ''}>
                    {s.nodeState}
                    {s.nodeError && <span className="badge bad" style={{ marginLeft: 6 }}>error</span>}
                  </td>
                  <td>
                    {s.agentVersion ?? '-'}
                    {s.duckdbVersion && <span className="subtle"> / duckdb {s.duckdbVersion}</span>}
                  </td>
                  <td className="actions">
                    {s.unschedulable
                      ? <button type="button" className="copy-btn" onClick={() => void act(() => api.undrainServer(s.name))}>Undrain</button>
                      : <button type="button" className="copy-btn" onClick={() => void act(() => api.drainServer(s.name))}>Drain</button>}
                    {' '}
                    {confirmRemove === s.name ? (
                      <>
                        <button type="button" className="danger" onClick={() => { setConfirmRemove(null); void act(() => api.removeServer(s.name)); }}>
                          Confirm remove
                        </button>{' '}
                        <button type="button" className="copy-btn" onClick={() => setConfirmRemove(null)}>Cancel</button>
                      </>
                    ) : (
                      <button
                        type="button"
                        className="copy-btn"
                        onClick={() => setConfirmRemove(s.name)}
                        disabled={removeDisabled}
                        title={removeDisabled ? 'Drain first, then stop the agent' : undefined}
                      >
                        Remove
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="subtle" style={{ textAlign: 'right' }}>
        Refreshing every {POLL_MS / 1000}s
      </p>
    </>
  );
}

function LivenessBadge({ liveness }: { liveness: FleetServer['liveness'] }) {
  const cls = liveness === 'reachable' ? 'good' : liveness === 'unreachable' ? 'warn' : 'bad';
  return <span className={`badge ${cls}`}>{liveness}</span>;
}
