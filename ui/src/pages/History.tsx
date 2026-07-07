import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
} from 'recharts';
import { api } from '../api/client';
import type {
  TrendBucketEntry,
  StatementHistoryRowEntry,
  TenantResponse,
  PoolResponse,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import SqlHighlight from '../components/SqlHighlight';

// Granularity thresholds: ranges <= 48 h use per-hour buckets, else per-day.
type Range = '1h' | '24h' | '7d' | '30d';

const RANGE_OPTIONS: { label: string; value: Range }[] = [
  { label: '1h',  value: '1h'  },
  { label: '24h', value: '24h' },
  { label: '7d',  value: '7d'  },
  { label: '30d', value: '30d' },
];

function rangeMs(r: Range): number {
  const h = 3_600_000;
  const d = 86_400_000;
  if (r === '1h')  return h;
  if (r === '24h') return 24 * h;
  if (r === '7d')  return 7 * d;
  return 30 * d;
}

function granularity(r: Range): 'hour' | 'day' {
  return r === '1h' || r === '24h' ? 'hour' : 'day';
}

function tickLabel(bucketStart: string, gran: 'hour' | 'day'): string {
  const d = new Date(bucketStart);
  if (gran === 'hour') {
    // HH:mm in UTC (buckets are UTC-aligned)
    const hh = String(d.getUTCHours()).padStart(2, '0');
    const mi = String(d.getUTCMinutes()).padStart(2, '0');
    return `${hh}:${mi}`;
  }
  // MM-DD
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(d.getUTCDate()).padStart(2, '0');
  return `${mm}-${dd}`;
}

// Aggregate buckets client-side: sum per bucketStart across (tenant, pool, username) groups.
interface AggBucket {
  bucketStart: string;
  tick: string;
  stmtCount: number;
  errorCount: number;
  deniedCount: number;
  engineMsSum: number;
  // Weighted p-tile numerators; divided by pctileStmtCount at render time.
  // Approximation: summing (pXX * stmtCount) and dividing by total stmtCount is not
  // strictly correct for merging percentiles - it approximates a weighted mean, not a
  // true merged percentile. We note this so future work can replace it with a t-digest.
  p50Weighted: number;
  p95Weighted: number;
  p99Weighted: number;
  // Count of statements from groups that actually contributed percentile values (non-null).
  // Used as denominator so that null-percentile groups do not dilute the weighted mean.
  pctileStmtCount: number;
  hasPercentiles: boolean;
}

function aggregate(buckets: TrendBucketEntry[], gran: 'hour' | 'day'): AggBucket[] {
  const map = new Map<string, AggBucket>();
  for (const b of buckets) {
    let agg = map.get(b.bucketStart);
    if (!agg) {
      agg = {
        bucketStart: b.bucketStart,
        tick: tickLabel(b.bucketStart, gran),
        stmtCount: 0,
        errorCount: 0,
        deniedCount: 0,
        engineMsSum: 0,
        p50Weighted: 0,
        p95Weighted: 0,
        p99Weighted: 0,
        pctileStmtCount: 0,
        hasPercentiles: false,
      };
      map.set(b.bucketStart, agg);
    }
    agg.stmtCount  += b.stmtCount;
    agg.errorCount += b.errorCount;
    agg.deniedCount += b.deniedCount;
    agg.engineMsSum += b.engineMsSum;
    if (b.p50Ms !== null && b.p95Ms !== null && b.p99Ms !== null) {
      agg.p50Weighted += b.p50Ms * b.stmtCount;
      agg.p95Weighted += b.p95Ms * b.stmtCount;
      agg.p99Weighted += b.p99Ms * b.stmtCount;
      agg.pctileStmtCount += b.stmtCount;
      agg.hasPercentiles = true;
    }
  }
  return Array.from(map.values()).sort((a, b) => a.bucketStart.localeCompare(b.bucketStart));
}

interface ThroughputRow {
  tick: string;
  ok: number;
  denied: number;
  error: number;
}

interface ErrorRateRow {
  tick: string;
  rate: number;
}

interface LatencyRow {
  tick: string;
  p50: number | null;
  p95: number | null;
  p99: number | null;
}

function toChartData(agg: AggBucket[]): {
  throughput: ThroughputRow[];
  errorRate: ErrorRateRow[];
  latency: LatencyRow[];
} {
  const throughput: ThroughputRow[] = [];
  const errorRate: ErrorRateRow[] = [];
  const latency: LatencyRow[] = [];

  for (const b of agg) {
    throughput.push({
      tick: b.tick,
      ok: Math.max(0, b.stmtCount - b.errorCount - b.deniedCount),
      denied: b.deniedCount,
      error: b.errorCount,
    });

    const rate = b.stmtCount > 0
      ? ((b.errorCount + b.deniedCount) / b.stmtCount) * 100
      : 0;
    errorRate.push({ tick: b.tick, rate: Math.round(rate * 10) / 10 });

    if (b.hasPercentiles && b.pctileStmtCount > 0) {
      latency.push({
        tick: b.tick,
        p50: Math.round(b.p50Weighted / b.pctileStmtCount),
        p95: Math.round(b.p95Weighted / b.pctileStmtCount),
        p99: Math.round(b.p99Weighted / b.pctileStmtCount),
      });
    } else {
      latency.push({ tick: b.tick, p50: null, p95: null, p99: null });
    }
  }

  return { throughput, errorRate, latency };
}

const CHART_HEIGHT = 220;

// Color palette drawn from existing design tokens.
const C_OK      = '#34d399'; // --good
const C_DENIED  = '#fbbf24'; // --warn
const C_ERROR   = '#f87171'; // --bad
const C_RATE    = '#f87171'; // --bad
const C_P50     = '#60a5fa'; // --link
const C_P95     = '#f59e0b'; // --accent
const C_P99     = '#f87171'; // --bad

const STATUS_OPTIONS = [
  { label: 'All statuses', value: '' },
  { label: 'ok',           value: 'ok' },
  { label: 'denied',       value: 'denied' },
  { label: 'transient',    value: 'transient' },
  { label: 'permanent',    value: 'permanent' },
  { label: 'no-node',      value: 'no-node' },
  { label: 'no-pool',      value: 'no-pool' },
  { label: 'pin-lost',     value: 'pin-lost' },
];

function shortTs(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString();
  } catch { return iso; }
}

function StmtStatusBadge({ status }: { status: string }) {
  const cls = status === 'ok'        ? 'good'
    : status === 'denied'            ? 'warn'
    : status === 'transient'         ? 'warn'
    : status === 'killed'            ? 'warn'
    : 'bad';
  return <span className={`badge ${cls}`}>{status}</span>;
}

export default function History() {
  const { superuser } = useAuth();

  const [telemetryEnabled, setTelemetryEnabled] = useState(false);
  const [range, setRange]   = useState<Range>('24h');
  const [tenant, setTenant] = useState('');
  const [pool, setPool]     = useState('');
  const [buckets, setBuckets] = useState<TrendBucketEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr]         = useState('');

  // Table-specific filters
  const [userFilter,   setUserFilter]   = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [sqlFilter,    setSqlFilter]    = useState('');

  // Statement table state
  const [stmts,       setStmts]       = useState<StatementHistoryRowEntry[]>([]);
  const [nextBefore,  setNextBefore]  = useState<string | null>(null);
  const [stmtLoading, setStmtLoading] = useState(false);
  const [stmtErr,     setStmtErr]     = useState('');
  const [expanded,    setExpanded]    = useState<string | null>(null);

  // Ref guard for load-more: prevents double-fire even if the button
  // becomes briefly enabled before React processes the loading state.
  const stmtInFlightRef = useRef(false);
  // Mirror of nextBefore in a ref so loadMore never closes over a stale value.
  const nextBeforeRef = useRef<string | null>(null);

  // Ref holding current filter values so fetch() always reads the latest without
  // re-creating the callback on every keystroke (avoids per-keystroke fetches).
  const filterRef = useRef({ range, tenant, pool, userFilter, statusFilter, sqlFilter });
  filterRef.current = { range, tenant, pool, userFilter, statusFilter, sqlFilter };

  useEffect(() => {
    api.clientConfig()
      .then(cfg => setTelemetryEnabled(cfg.telemetryEnabled !== false))
      .catch(() => setTelemetryEnabled(false));
  }, []);

  // Filter select options (mirrors the Usage page).
  const [tenantOptions, setTenantOptions] = useState<TenantResponse[]>([]);
  const [poolOptions, setPoolOptions] = useState<PoolResponse[]>([]);

  useEffect(() => {
    api.listPools().then(r => setPoolOptions(r.pools)).catch(() => setPoolOptions([]));
  }, []);

  useEffect(() => {
    if (superuser) {
      api.listTenants().then(r => setTenantOptions(r.tenants)).catch(() => setTenantOptions([]));
    }
  }, [superuser]);

  const fetch = useCallback(() => {
    const { range: r, tenant: t, pool: p } = filterRef.current;
    const from = new Date(Date.now() - rangeMs(r)).toISOString();
    const gran  = granularity(r);
    const params: Record<string, string> = { granularity: gran, from };
    if (t) params.tenant = t;
    if (p) params.pool   = p;
    setLoading(true);
    setErr('');
    api.historyTrends(params)
      .then(res => setBuckets(res.buckets))
      .catch(e => setErr(String(e)))
      .finally(() => setLoading(false));
  }, []);

  const fetchStmts = useCallback(() => {
    if (stmtInFlightRef.current) return;
    const { range: r, tenant: t, pool: p, userFilter: u, statusFilter: s, sqlFilter: sq } = filterRef.current;
    const from = new Date(Date.now() - rangeMs(r)).toISOString();
    const params: Record<string, string> = { from };
    if (t)  params.tenant = t;
    if (p)  params.pool   = p;
    if (u)  params.user   = u;
    if (s)  params.status = s;
    if (sq) params.q      = sq;
    setStmtLoading(true);
    stmtInFlightRef.current = true;
    setStmtErr('');
    api.historyStatements(params)
      .then(res => {
        setStmts(res.statements);
        setNextBefore(res.nextBefore);
        nextBeforeRef.current = res.nextBefore;
      })
      .catch(e => setStmtErr(String(e)))
      .finally(() => { setStmtLoading(false); stmtInFlightRef.current = false; });
  }, []);

  const loadMore = useCallback(() => {
    if (stmtInFlightRef.current || !nextBeforeRef.current) return;
    const { range: r, tenant: t, pool: p, userFilter: u, statusFilter: s, sqlFilter: sq } = filterRef.current;
    const from = new Date(Date.now() - rangeMs(r)).toISOString();
    const params: Record<string, string> = { before: nextBeforeRef.current, from };
    if (t)  params.tenant = t;
    if (p)  params.pool   = p;
    if (u)  params.user   = u;
    if (s)  params.status = s;
    if (sq) params.q      = sq;
    setStmtLoading(true);
    stmtInFlightRef.current = true;
    setStmtErr('');
    api.historyStatements(params)
      .then(res => {
        setStmts(prev => [...prev, ...res.statements]);
        setNextBefore(res.nextBefore);
        nextBeforeRef.current = res.nextBefore;
      })
      .catch(e => setStmtErr(String(e)))
      .finally(() => { setStmtLoading(false); stmtInFlightRef.current = false; });
  }, []);

  // Refetch charts AND table on range button click, tenant/pool selection, or initial load.
  useEffect(() => {
    if (telemetryEnabled) {
      fetch();
      fetchStmts();
    }
  }, [telemetryEnabled, range, tenant, pool, fetch, fetchStmts]);

  if (!telemetryEnabled) {
    return (
      <>
        <h2>Statements</h2>
        <p>Telemetry is disabled (telemetry.store = none). No statement history is recorded.</p>
      </>
    );
  }

  const gran = granularity(range);
  const agg  = aggregate(buckets, gran);
  const { throughput, errorRate, latency } = toChartData(agg);

  const poolNames = [...new Set(
    poolOptions.filter(p => !tenant || p.tenant === tenant).map(p => p.pool),
  )].sort();

  return (
    <>
      <h2>Statements</h2>

      {/* Shared chart + table filters */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12, alignItems: 'center' }}>
        {RANGE_OPTIONS.map(opt => (
          <button
            key={opt.value}
            type="button"
            className="copy-btn"
            style={range === opt.value ? { borderColor: 'var(--accent)', color: 'var(--accent)' } : undefined}
            onClick={() => setRange(opt.value)}
          >
            {opt.label}
          </button>
        ))}
        {superuser && (
          <select value={tenant} onChange={e => { setTenant(e.target.value); setPool(''); }}>
            <option value="">all tenants</option>
            {tenantOptions.map(t => (
              <option key={t.id} value={t.id}>
                {t.displayName === t.id ? t.id : `${t.displayName} (${t.id})`}
              </option>
            ))}
          </select>
        )}
        <select value={pool} onChange={e => setPool(e.target.value)}>
          <option value="">all pools</option>
          {poolNames.map(p => <option key={p} value={p}>{p}</option>)}
        </select>
        <button
          type="button"
          className="copy-btn"
          disabled={loading || stmtLoading}
          onClick={() => { fetch(); fetchStmts(); }}
        >
          {loading || stmtLoading ? 'Loading...' : 'Refresh'}
        </button>
      </div>

      {err && <p className="login-err">{err}</p>}

      {buckets.length === 0 && !loading && (
        <p className="muted">No data for the selected range and filters.</p>
      )}

      {buckets.length > 0 && (
        <>
          <div className="card" style={{ marginBottom: 16 }}>
            <div className="card-title">Throughput (statements / bucket)</div>
            <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
              <BarChart data={throughput} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                <XAxis dataKey="tick" tick={{ fontSize: 11 }} />
                <YAxis tick={{ fontSize: 11 }} allowDecimals={false} />
                <Tooltip />
                <Legend />
                <Bar dataKey="ok"     name="ok"     stackId="s" fill={C_OK}     />
                <Bar dataKey="denied" name="denied" stackId="s" fill={C_DENIED} />
                <Bar dataKey="error"  name="error"  stackId="s" fill={C_ERROR}  />
              </BarChart>
            </ResponsiveContainer>
          </div>

          <div className="card" style={{ marginBottom: 16 }}>
            <div className="card-title">Error rate (%)</div>
            <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
              <LineChart data={errorRate} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                <XAxis dataKey="tick" tick={{ fontSize: 11 }} />
                <YAxis tick={{ fontSize: 11 }} domain={[0, 100]} unit="%" />
                <Tooltip formatter={(v: number) => `${v}%`} />
                <Line type="monotone" dataKey="rate" name="error rate" stroke={C_RATE} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="card">
            <div className="card-title">Latency percentiles (ms)</div>
            {gran === 'day' ? (
              <p className="muted">Latency percentiles are available at hourly granularity.</p>
            ) : (
              <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
                <LineChart data={latency} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                  <XAxis dataKey="tick" tick={{ fontSize: 11 }} />
                  <YAxis tick={{ fontSize: 11 }} unit=" ms" />
                  <Tooltip formatter={(v) => (v === null || v === undefined ? 'n/a' : `${Number(v)} ms`)} />
                  <Legend />
                  <Line type="monotone" dataKey="p50" name="p50" stroke={C_P50} dot={false} connectNulls />
                  <Line type="monotone" dataKey="p95" name="p95" stroke={C_P95} dot={false} connectNulls />
                  <Line type="monotone" dataKey="p99" name="p99" stroke={C_P99} dot={false} connectNulls />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>
        </>
      )}

      {/* Statement history table */}
      <h3 style={{ marginTop: '2rem', marginBottom: 8 }}>Statement history</h3>

      {/* Table-specific filters (no per-keystroke fetch; use Refresh above) */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12, alignItems: 'center' }}>
        <input
          placeholder="user"
          value={userFilter}
          style={{ width: 140 }}
          onChange={e => setUserFilter(e.target.value)}
        />
        <select
          value={statusFilter}
          style={{ height: 32 }}
          onChange={e => setStatusFilter(e.target.value)}
        >
          {STATUS_OPTIONS.map(o => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
        <input
          placeholder="SQL substring"
          value={sqlFilter}
          style={{ width: 200 }}
          onChange={e => setSqlFilter(e.target.value)}
        />
        <span className="muted" style={{ fontSize: '.82rem' }}>
          Press Refresh above to apply filters
        </span>
      </div>

      {stmtErr && <p className="login-err">{stmtErr}</p>}

      <div className="card" style={{ padding: 0, marginBottom: 8 }}>
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>User</th>
              <th>Tenant</th>
              <th>Pool</th>
              <th>Node</th>
              <th style={{ textAlign: 'right' }}>Duration</th>
              <th>Status</th>
              <th>SQL</th>
            </tr>
          </thead>
          <tbody>
            {stmts.length === 0 && !stmtLoading ? (
              <tr>
                <td colSpan={8} className="empty">No statements found. Press Refresh to load.</td>
              </tr>
            ) : stmts.map(h => {
              const isOpen = expanded === h.id;
              return (
                <tr
                  key={h.id}
                  onClick={() => setExpanded(isOpen ? null : h.id)}
                  style={{ cursor: 'pointer' }}
                >
                  <td className="subtle">
                    <code title={h.ts}>{shortTs(h.ts)}</code>
                  </td>
                  <td>{h.username}</td>
                  <td>{h.tenant}</td>
                  <td>{h.pool}</td>
                  <td><code>{h.nodeId}</code></td>
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                    <div>{h.durationMs} ms</div>
                    {h.prepareMs != null && (
                      <div
                        style={{ fontSize: '.78em', opacity: 0.6 }}
                        title="FlightSQL Prepare-time LIMIT-0 probe duration"
                      >
                        prep {h.prepareMs} ms
                      </div>
                    )}
                  </td>
                  <td><StmtStatusBadge status={h.status} /></td>
                  <td>
                    <div className="sql-cell">
                      <pre style={{
                        margin: 0, fontSize: '.85em',
                        whiteSpace: isOpen ? 'pre-wrap' : 'nowrap',
                        overflow: isOpen ? 'visible' : 'hidden',
                        textOverflow: 'ellipsis',
                        maxWidth: isOpen ? 'none' : '500px',
                      }}>
                        <SqlHighlight sql={h.sql.trim()} />
                      </pre>
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

      {nextBefore !== null && (
        <div style={{ textAlign: 'center', marginBottom: 16 }}>
          <button
            type="button"
            className="copy-btn"
            disabled={stmtLoading}
            onClick={loadMore}
          >
            {stmtLoading ? 'Loading...' : 'Load more'}
          </button>
        </div>
      )}

      <p className="subtle" style={{ textAlign: 'right' }}>
        Newest first, 50 per page · click a row to expand SQL
      </p>
    </>
  );
}
