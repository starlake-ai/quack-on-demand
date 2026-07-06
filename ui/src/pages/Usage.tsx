import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
} from 'recharts';
import { api } from '../api/client';
import type { UsageGroupEntry } from '../api/types';
import { useAuth } from '../auth/AuthContext';

const CHART_HEIGHT = 260;

// Stacked-segment palette; groups beyond the first 8 are aggregated into "other".
const PALETTE = [
  '#60a5fa', '#34d399', '#fbbf24', '#f87171',
  '#a78bfa', '#f472b6', '#2dd4bf', '#fb923c',
];
const C_OTHER = '#9ca3af';
const MAX_CHART_GROUPS = 8;

type GroupBy = 'tenant' | 'pool' | 'user';
type Metric = 'statements' | 'engineMs';

function currentMonth(): string {
  return new Date().toISOString().slice(0, 7); // YYYY-MM
}

// Half-open [from, to) instants for a YYYY-MM month, UTC.
function monthRange(m: string): { from: string; to: string } {
  const [y, mo] = m.split('-').map(Number);
  return {
    from: new Date(Date.UTC(y, mo - 1, 1)).toISOString(),
    to: new Date(Date.UTC(y, mo, 1)).toISOString(),
  };
}

// Inclusive date-picker end -> exclusive API bound (start of the next UTC day).
function dayAfter(d: string): string {
  const t = new Date(`${d}T00:00:00Z`);
  t.setUTCDate(t.getUTCDate() + 1);
  return t.toISOString();
}

function labelOf(g: UsageGroupEntry, groupBy: string): string {
  if (groupBy === 'pool') return `${g.tenant}/${g.pool ?? ''}`;
  if (groupBy === 'user') return `${g.tenant}/${g.username ?? ''}`;
  return g.tenant;
}

function csvEscape(v: string): string {
  return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

// Column contract (spec): tenant,pool,user,statements,errors,denied,engine_ms
// with absent group columns omitted.
export function buildCsv(groupBy: string, groups: UsageGroupEntry[]): string {
  const keyCols =
    groupBy === 'pool' ? ['tenant', 'pool'] :
    groupBy === 'user' ? ['tenant', 'user'] : ['tenant'];
  const header = [...keyCols, 'statements', 'errors', 'denied', 'engine_ms'].join(',');
  const lines = groups.map(g => {
    const keys =
      groupBy === 'pool' ? [g.tenant, g.pool ?? ''] :
      groupBy === 'user' ? [g.tenant, g.username ?? ''] : [g.tenant];
    return [...keys.map(csvEscape), g.statements, g.errors, g.denied, g.engineMs].join(',');
  });
  return [header, ...lines].join('\n') + '\n';
}

interface ChartRow {
  tick: string;
  [label: string]: number | string;
}

function chartRows(groups: UsageGroupEntry[], groupBy: string, metric: Metric): {
  rows: ChartRow[];
  labels: string[];
} {
  const top = groups.slice(0, MAX_CHART_GROUPS);
  const rest = groups.slice(MAX_CHART_GROUPS);
  const byDay = new Map<string, ChartRow>();
  const add = (label: string, day: string, value: number) => {
    const tick = day.slice(0, 10);
    const row = byDay.get(tick) ?? { tick };
    row[label] = ((row[label] as number) ?? 0) + value;
    byDay.set(tick, row);
  };
  for (const g of top) {
    const label = labelOf(g, groupBy);
    for (const d of g.days) add(label, d.day, metric === 'statements' ? d.statements : d.engineMs);
  }
  for (const g of rest) {
    for (const d of g.days) add('other', d.day, metric === 'statements' ? d.statements : d.engineMs);
  }
  const labels = top.map(g => labelOf(g, groupBy));
  if (rest.length > 0) labels.push('other');
  const rows = [...byDay.values()].sort((a, b) =>
    (a.tick as string).localeCompare(b.tick as string));
  return { rows, labels };
}

export default function Usage() {
  const { superuser } = useAuth();

  const [telemetryEnabled, setTelemetryEnabled] = useState(false);

  const [month, setMonth] = useState(currentMonth());
  const [custom, setCustom] = useState(false);
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  // Tenant admins land on the pool grouping; the tenant grouping is superuser-only.
  const [groupBy, setGroupBy] = useState<GroupBy>(superuser ? 'tenant' : 'pool');
  const [metric, setMetric] = useState<Metric>('statements');
  const [tenant, setTenant] = useState('');
  const [pool, setPool] = useState('');

  const [groups, setGroups] = useState<UsageGroupEntry[]>([]);
  const [resGroupBy, setResGroupBy] = useState<string>(groupBy);
  const [resFrom, setResFrom] = useState('');
  const [resTo, setResTo] = useState('');
  const [dataStart, setDataStart] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');

  // Latest filter values without re-creating fetch on every keystroke.
  const filterRef = useRef({ month, custom, fromDate, toDate, groupBy, tenant, pool });
  filterRef.current = { month, custom, fromDate, toDate, groupBy, tenant, pool };

  useEffect(() => {
    api.clientConfig()
      .then(cfg => setTelemetryEnabled(cfg.telemetryEnabled !== false))
      .catch(() => setTelemetryEnabled(false));
  }, []);

  const fetchUsage = useCallback(() => {
    const f = filterRef.current;
    const params: Record<string, string> = { groupBy: f.groupBy };
    if (f.custom && f.fromDate && f.toDate) {
      params.from = `${f.fromDate}T00:00:00Z`;
      params.to = dayAfter(f.toDate);
    } else {
      const r = monthRange(f.month);
      params.from = r.from;
      params.to = r.to;
    }
    if (f.tenant) params.tenant = f.tenant;
    if (f.pool) params.pool = f.pool;
    setLoading(true);
    setErr('');
    api.usage(params)
      .then(res => {
        setGroups(res.groups);
        setResGroupBy(res.groupBy);
        setResFrom(res.from);
        setResTo(res.to);
        setDataStart(res.dataStart);
      })
      .catch(e => setErr(String(e)))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (telemetryEnabled) fetchUsage();
  }, [telemetryEnabled, month, custom, fromDate, toDate, groupBy, fetchUsage]);

  if (!telemetryEnabled) {
    return (
      <>
        <h2>Usage</h2>
        <p>Telemetry is disabled (telemetry.store = none). Nothing is metered.</p>
      </>
    );
  }

  const onDownloadCsv = () => {
    const blob = new Blob([buildCsv(resGroupBy, groups)], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `qod-usage-${resFrom.slice(0, 10)}-${resTo.slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const { rows, labels } = chartRows(groups, resGroupBy, metric);
  const truncated = dataStart && resFrom && dataStart > resFrom;

  return (
    <>
      <h2>Usage</h2>
      <p className="muted">
        engine-ms is statement execution time as measured by the manager (routing to last byte
        streamed), not node CPU time. The current day is complete up to the last rollup tick
        (about 5 minutes behind). Accounting is best-effort measurement: journal overflow drops
        are counted in qod_journal_dropped_total.
      </p>

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', margin: '12px 0' }}>
        {!custom && (
          <input type="month" value={month} onChange={e => setMonth(e.target.value)} />
        )}
        {custom && (
          <>
            <input type="date" value={fromDate} onChange={e => setFromDate(e.target.value)} />
            <span className="muted">to</span>
            <input type="date" value={toDate} onChange={e => setToDate(e.target.value)} />
          </>
        )}
        <button type="button" className="copy-btn" onClick={() => setCustom(c => !c)}>
          {custom ? 'month' : 'custom range'}
        </button>
        <select value={groupBy} onChange={e => setGroupBy(e.target.value as GroupBy)}>
          {superuser && <option value="tenant">by tenant</option>}
          <option value="pool">by pool</option>
          <option value="user">by user</option>
        </select>
        <select value={metric} onChange={e => setMetric(e.target.value as Metric)}>
          <option value="statements">statements</option>
          <option value="engineMs">engine-ms</option>
        </select>
        {superuser && (
          <input
            placeholder="tenant"
            value={tenant}
            style={{ width: 140 }}
            onChange={e => setTenant(e.target.value)}
          />
        )}
        <input
          placeholder="pool"
          value={pool}
          style={{ width: 140 }}
          onChange={e => setPool(e.target.value)}
        />
        <button type="button" className="copy-btn" onClick={fetchUsage} disabled={loading}>
          Refresh
        </button>
      </div>

      {err && <p className="error">{err}</p>}
      {truncated && (
        <p className="muted">Data starts {dataStart!.slice(0, 10)} (older buckets purged).</p>
      )}

      {groups.length === 0 && !loading && (
        <p className="muted">No usage recorded for the selected period and filters.</p>
      )}

      {rows.length > 0 && (
        <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
          <BarChart data={rows} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            <XAxis dataKey="tick" tick={{ fontSize: 11 }} />
            <YAxis tick={{ fontSize: 11 }} allowDecimals={false} />
            <Tooltip />
            <Legend />
            {labels.map((label, i) => (
              <Bar
                key={label}
                dataKey={label}
                name={label}
                stackId="s"
                fill={label === 'other' ? C_OTHER : PALETTE[i % PALETTE.length]}
              />
            ))}
          </BarChart>
        </ResponsiveContainer>
      )}

      {groups.length > 0 && (
        <>
          <div style={{ display: 'flex', justifyContent: 'flex-end', margin: '8px 0' }}>
            <button type="button" className="copy-btn" onClick={onDownloadCsv}>
              Download CSV
            </button>
          </div>
          <div className="card" style={{ padding: 0, marginBottom: 8 }}>
            <table>
              <thead>
                <tr>
                  <th>tenant</th>
                  {resGroupBy === 'pool' && <th>pool</th>}
                  {resGroupBy === 'user' && <th>user</th>}
                  <th>statements</th>
                  <th>errors</th>
                  <th>denied</th>
                  <th>engine-ms</th>
                </tr>
              </thead>
              <tbody>
                {groups.map(g => (
                  <tr key={labelOf(g, resGroupBy)}>
                    <td>{g.tenant}</td>
                    {resGroupBy === 'pool' && <td>{g.pool}</td>}
                    {resGroupBy === 'user' && <td>{g.username}</td>}
                    <td>{g.statements}</td>
                    <td>{g.errors}</td>
                    <td>{g.denied}</td>
                    <td>{g.engineMs}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  );
}
