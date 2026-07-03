import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { PoolResponse, NodeInfo, StatementHistoryEntry } from '../api/types';

/** DuckDB / standard SQL keywords surfaced in the Recent-statements
  * card. Kept deliberately small: the goal is "operator glances at it
  * and picks out structure", not lossless ANSI coverage. Case-insensitive. */
const SQL_KEYWORDS = new Set([
  'SELECT', 'FROM', 'WHERE', 'GROUP', 'BY', 'HAVING', 'ORDER', 'LIMIT', 'OFFSET',
  'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'FULL', 'CROSS', 'NATURAL', 'ON', 'USING',
  'UNION', 'INTERSECT', 'EXCEPT', 'ALL', 'ANY', 'SOME', 'DISTINCT', 'AS', 'WITH', 'RECURSIVE',
  'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'RETURNING',
  'CREATE', 'DROP', 'ALTER', 'RENAME', 'TABLE', 'VIEW', 'INDEX', 'SCHEMA',
  'DATABASE', 'CATALOG', 'IF', 'EXISTS', 'NOT', 'NULL', 'PRIMARY', 'KEY', 'FOREIGN',
  'REFERENCES', 'CONSTRAINT', 'DEFAULT', 'UNIQUE', 'CHECK',
  'ATTACH', 'DETACH', 'USE', 'INSTALL', 'LOAD', 'PRAGMA', 'COPY', 'EXPORT', 'IMPORT',
  'CALL', 'SECRET', 'TYPE', 'READ_ONLY', 'DATA_PATH',
  'BEGIN', 'COMMIT', 'ROLLBACK', 'TRANSACTION', 'TEMP', 'TEMPORARY',
  'CASCADE', 'RESTRICT', 'REPLACE', 'OR',
  'AND', 'IN', 'BETWEEN', 'LIKE', 'ILIKE', 'IS', 'TRUE', 'FALSE',
  'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'CAST', 'OVER', 'PARTITION', 'WINDOW',
  'ASC', 'DESC', 'NULLS', 'FIRST', 'LAST', 'EXCLUDE', 'QUALIFY',
]);

/** Render `sql` as a sequence of highlighted spans, preserving every
  * character (including whitespace). The tokenizer recognises: line
  * comments, block comments, single-quoted strings, double-quoted
  * identifiers, numbers, words (keyword vs identifier), and a catch-all
  * for everything else. Token classes map to CSS rules in styles.css
  * (`.sql-tok-*`). */
function SqlHighlight({ sql }: { sql: string }) {
  // Memoize the token list so we don't re-tokenize on every parent re-render
  // of the polling page. Token-list shape is small even for a kilobyte of SQL.
  const tokens = useMemo(() => tokenizeSql(sql), [sql]);
  return (
    <>
      {tokens.map((t, i) =>
        t.cls === null
          ? t.text                      // whitespace and unclassified passthrough
          : <span key={i} className={`sql-tok-${t.cls}`}>{t.text}</span>
      )}
    </>
  );
}

type SqlToken = { text: string; cls: 'kw' | 'str' | 'num' | 'com' | 'id' | null };

function tokenizeSql(sql: string): SqlToken[] {
  // Order matters: comments and strings must beat the word/number patterns.
  const re = new RegExp(
    [
      '(--[^\\n]*)',                       // 1: line comment
      '(\\/\\*[\\s\\S]*?\\*\\/)',          // 2: block comment
      "('(?:[^'\\\\]|\\\\.|'')*')",        // 3: single-quoted string
      '("(?:[^"\\\\]|\\\\.|"")*")',        // 4: double-quoted identifier
      '(\\b\\d+(?:\\.\\d+)?\\b)',          // 5: number
      '([A-Za-z_][A-Za-z0-9_]*)',          // 6: word (keyword or identifier)
    ].join('|'),
    'g'
  );
  const out: SqlToken[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(sql)) !== null) {
    if (m.index > last) out.push({ text: sql.slice(last, m.index), cls: null });
    if      (m[1]) out.push({ text: m[1], cls: 'com' });
    else if (m[2]) out.push({ text: m[2], cls: 'com' });
    else if (m[3]) out.push({ text: m[3], cls: 'str' });
    else if (m[4]) out.push({ text: m[4], cls: 'id' });
    else if (m[5]) out.push({ text: m[5], cls: 'num' });
    else if (m[6]) {
      const w = m[6];
      out.push({ text: w, cls: SQL_KEYWORDS.has(w.toUpperCase()) ? 'kw' : null });
    }
    last = re.lastIndex;
  }
  if (last < sql.length) out.push({ text: sql.slice(last), cls: null });
  return out;
}

interface Row extends NodeInfo {
  tenant:   string;
  tenantDb: string;
  pool:     string;
  qps:      number; // computed client-side from totalServed delta
}

/** Snapshot kept between polls so we can compute per-node QPS without
  * the backend having to maintain a rate window. */
interface Sample { totalServed: number; t: number; }

const POLL_MS = 2000;

/** Live node dashboard. Polls /api/pool/list every 2s. p50/p95/p99 come
  * from the backend's rolling-window per-node histogram; QPS is derived
  * client-side from the delta in totalServed between polls. */
export default function Nodes() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [rows, setRows] = useState<Row[]>([]);
  const [err, setErr]   = useState<string | null>(null);
  const [tenants, setTenants] = useState<string[]>([]);
  // Seed filters from URL so deep links from TenantDetail / PoolDetail
  // land pre-filtered. The two filters compose: ?tenant=acme&node=ro1
  // narrows both axes simultaneously.
  const [filter, setFilter]   = useState<string>(searchParams.get('tenant') ?? '');
  const [nodeFilter, setNodeFilter] = useState<string>(searchParams.get('node') ?? '');
  const [history, setHistory] = useState<StatementHistoryEntry[]>([]);
  const [expanded, setExpanded] = useState<number | null>(null);
  // Row index whose Copy button was just clicked, for the brief "Copied"
  // feedback. Cleared on a timeout.
  const [copied, setCopied] = useState<number | null>(null);
  const prev = useRef<Record<string, Sample>>({});

  async function copySql(sql: string, idx: number) {
    try {
      await navigator.clipboard.writeText(sql);
      setCopied(idx);
      window.setTimeout(() => setCopied(c => (c === idx ? null : c)), 1500);
    } catch {
      // Older browsers or non-secure contexts: clipboard API is unavailable.
      // Surface the failure quietly; the SQL is still visible to manually copy.
      setCopied(null);
    }
  }

  // Keep the URL and the filter dropdown in sync so deep-linked views
  // stay shareable and the back button works.
  function applyFilter(next: string) {
    setFilter(next);
    const params: Record<string, string> = {};
    if (next) params.tenant = next;
    if (nodeFilter) params.node = nodeFilter;
    setSearchParams(params);
  }
  function clearNodeFilter() {
    setNodeFilter('');
    const params: Record<string, string> = {};
    if (filter) params.tenant = filter;
    setSearchParams(params);
  }

  function refresh() {
    api.statementHistory(50)
      .then(r => setHistory(r.statements))
      .catch(() => { /* best effort - pools still useful even if history fails */ });
    api.listPools()
      .then((r: { pools: PoolResponse[] }) => {
        const now = Date.now();
        // Disabled pools' nodes don't accept fresh handshakes, so they're
        // noise on the live dashboard. Operators can still inspect them
        // from the tenant page where the disabled state is meaningful.
        const flat: Row[] = r.pools.filter(p => !p.disabled).flatMap(p =>
          p.nodes.map(n => {
            const key = `${p.tenant}/${p.tenantDb}/${p.pool}/${n.nodeId}`;
            const prior = prev.current[key];
            // First sample: QPS unknown, show 0 (instead of misleading values).
            let qps = 0;
            if (prior) {
              const dt = (now - prior.t) / 1000;
              if (dt > 0) qps = Math.max(0, (n.totalServed - prior.totalServed) / dt);
            }
            prev.current[key] = { totalServed: n.totalServed, t: now };
            return { ...n, tenant: p.tenant, tenantDb: p.tenantDb, pool: p.pool, qps };
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

  const visible = rows
    .filter(r => !filter || r.tenant === filter)
    .filter(r => !nodeFilter || r.nodeId === nodeFilter);
  const sumTotal    = visible.reduce((s, n) => s + n.totalServed, 0);
  const sumInFlight = visible.reduce((s, n) => s + n.inFlight, 0);
  const sumQps      = visible.reduce((s, n) => s + n.qps, 0);
  const avgLat = visible.length === 0
    ? 0
    : visible.reduce((s, n) => s + n.avgDurationMs, 0) / visible.length;
  // Cluster p95 = max of per-node p95s (worst tail across the fleet -
  // operators want to see the worst, not the average of percentiles).
  const clusterP95 = visible.reduce((m, n) => Math.max(m, n.p95Ms), 0);
  const healthyCount = visible.filter(n => n.healthy && !n.draining).length;
  // DuckDB engine totals across scraped nodes only (unscraped nodes report null).
  const sumEngineMem = visible.reduce((s, n) => s + (n.duckdbMemoryBytes ?? 0), 0);
  const sumSpill     = visible.reduce((s, n) => s + (n.duckdbSpillBytes ?? 0), 0);
  const anyEngine    = visible.some(n => n.duckdbMemoryBytes != null);

  return (
    <>
      <div className="row" style={{ marginBottom: '1rem', justifyContent: 'space-between' }}>
        <h1>Quack Nodes</h1>
        <label style={{ marginBottom: 0 }}>
          Tenant filter
          <select value={filter} onChange={e => applyFilter(e.target.value)} style={{ minWidth: 160 }}>
            <option value="">All tenants</option>
            {tenants.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
      </div>

      {nodeFilter && (
        <div className="card" style={{ padding: '8px 12px', marginBottom: '1rem' }}>
          Filtered to node <code>{nodeFilter}</code>
          <button
            onClick={clearNodeFilter}
            style={{ marginLeft: 12, fontSize: '0.85rem' }}
          >
            Clear node filter
          </button>
        </div>
      )}

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
        <div className="metric">
          <div className="label">DuckDB mem</div>
          <div className="value">{anyEngine ? fmtBytes(sumEngineMem) : '-'}</div>
        </div>
        <div className="metric">
          <div className="label">Spill</div>
          <div className="value">{anyEngine ? fmtBytes(sumSpill) : '-'}</div>
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
              <th style={{ textAlign: 'right' }}>Mem</th>
              <th style={{ textAlign: 'right' }}>Spill</th>
              <th style={{ textAlign: 'right' }}>Max conc.</th>
            </tr>
          </thead>
          <tbody>
            {visible.length === 0 ? (
              <tr><td colSpan={15} className="empty">No nodes running.</td></tr>
            ) : visible.map(n => (
              <tr key={`${n.tenant}/${n.tenantDb}/${n.pool}/${n.nodeId}`}>
                <td>
                  <Link
                    to={`/nodes?${filter ? `tenant=${encodeURIComponent(filter)}&` : ''}node=${encodeURIComponent(n.nodeId)}`}
                    style={{ textDecoration: 'none' }}
                  >
                    <code>{n.nodeId}</code>
                  </Link>
                </td>
                <td>
                  <Link to={`/tenant/${n.tenant}`}>{n.tenant}</Link>
                  {' / '}
                  <code style={{ color: '#666' }}>{n.tenantDb}</code>
                  {' / '}
                  <Link to={`/pool/${encodeURIComponent(n.tenant)}/${encodeURIComponent(n.tenantDb)}/${encodeURIComponent(n.pool)}`}>{n.pool}</Link>
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
                <td
                  style={{ textAlign: 'right' }}
                  title="DuckDB buffer-manager memory in use (health-probe scrape)"
                >
                  {fmtBytes(n.duckdbMemoryBytes)}
                </td>
                <td
                  style={{ textAlign: 'right' }}
                  title={
                    n.duckdbSpillFiles != null
                      ? `${n.duckdbSpillFiles} live spill file${n.duckdbSpillFiles === 1 ? '' : 's'}`
                      : 'Engine stats not scraped yet'
                  }
                >
                  {fmtBytes(n.duckdbSpillBytes)}
                  {(n.duckdbSpillFiles ?? 0) > 0 && (
                    <div style={{ fontSize: '.78em', opacity: 0.6 }}>
                      {n.duckdbSpillFiles} file{n.duckdbSpillFiles === 1 ? '' : 's'}
                    </div>
                  )}
                </td>
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
                .filter(h => !nodeFilter || h.nodeId === nodeFilter)
                .map((h, i) => {
                  const isOpen = expanded === i;
                  return (
                    <tr key={i} onClick={() => setExpanded(isOpen ? null : i)} style={{ cursor: 'pointer' }}>
                      <td className="subtle"><code>{shortTime(h.ts)}</code></td>
                      <td>{h.user}</td>
                      <td>
                        <Link to={`/tenant/${h.tenant}`}>{h.tenant}</Link>
                        {' / '}
                        <span>{h.pool}</span>
                      </td>
                      <td>
                        <Link
                          to={`/nodes?${filter ? `tenant=${encodeURIComponent(filter)}&` : ''}node=${encodeURIComponent(h.nodeId)}`}
                          style={{ textDecoration: 'none' }}
                          onClick={e => e.stopPropagation()}
                        >
                          <code>{h.nodeId}</code>
                        </Link>
                      </td>
                      <td><StatusBadge status={h.status} /></td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        <div>{h.durationMs} ms</div>
                        {h.prepareDurationMs != null && (
                          <div
                            style={{ fontSize: '.78em', opacity: 0.6 }}
                            title="FlightSQL Prepare-time LIMIT-0 probe duration"
                          >
                            prep {h.prepareDurationMs} ms
                          </div>
                        )}
                      </td>
                      <td>
                        <div className="sql-cell">
                          <pre style={{
                            margin: 0, fontSize: '.85em',
                            whiteSpace: isOpen ? 'pre-wrap' : 'nowrap',
                            overflow: isOpen ? 'visible' : 'hidden',
                            textOverflow: 'ellipsis',
                            maxWidth: isOpen ? 'none' : '500px',
                          }}><SqlHighlight sql={h.sql.trim()} /></pre>
                          <button
                            type="button"
                            className="copy-btn"
                            title="Copy SQL to clipboard"
                            onClick={e => { e.stopPropagation(); void copySql(h.sql, i); }}
                          >
                            {copied === i ? 'Copied' : 'Copy'}
                          </button>
                        </div>
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

/** Human-readable byte count (binary units, one decimal). Undefined/null means
  * the node's engine stats have not been scraped yet - render as a dash. */
function fmtBytes(n: number | null | undefined): string {
  if (n == null) return '-';
  if (n < 1024) return `${n} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let v = n;
  let u = -1;
  do { v /= 1024; u++; } while (v >= 1024 && u < units.length - 1);
  return `${v.toFixed(1)} ${units[u]}`;
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