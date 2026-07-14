import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type {
  MaintenanceEffectiveEntry,
  MaintenancePolicyEntry,
  MaintenancePolicyUpsertRequest,
  MaintenanceRunEntry,
} from '../api/types';

const RUNS_PAGE = 20;

/** Chain order; mirrors the server's ValidOperations vocabulary. */
const ALL_OPERATIONS = ['flush', 'expire', 'merge', 'rewrite', 'cleanup', 'orphans'] as const;

const chipBtn: React.CSSProperties = {
  background: 'none', border: 'none', cursor: 'pointer', padding: 0,
  font: 'inherit', fontSize: '0.9em', color: 'inherit', textDecoration: 'underline',
};

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n;
  let i = -1;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1; }
  return `${v.toFixed(1)} ${units[i]}`;
}

function runDuration(r: MaintenanceRunEntry): string {
  if (!r.startedAt || !r.finishedAt) return '-';
  const ms = new Date(r.finishedAt).getTime() - new Date(r.startedAt).getTime();
  if (ms < 0) return '-';
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${(ms / 60_000).toFixed(1)} min`;
}

function statusChip(status: string) {
  const palette: Record<string, { bg: string; fg: string }> = {
    succeeded: { bg: 'rgba(34, 197, 94, 0.15)',  fg: '#15803d' },
    partial:   { bg: 'rgba(251, 191, 36, 0.15)', fg: '#a16207' },
    failed:    { bg: 'rgba(239, 68, 68, 0.15)',  fg: '#b91c1c' },
    running:   { bg: 'rgba(59, 130, 246, 0.15)', fg: '#1d4ed8' },
    queued:    { bg: 'rgba(107, 114, 128, 0.15)',fg: '#4b5563' },
  };
  const c = palette[status] ?? palette.queued;
  return (
    <span style={{
      background: c.bg, color: c.fg, borderRadius: 4,
      padding: '0 6px', fontSize: '0.85em', fontWeight: 600,
    }}>{status}</span>
  );
}

/** Summarize a policy row's overridden fields for the overrides table. */
function overrideSummary(p: MaintenancePolicyEntry): string {
  const parts: string[] = [];
  if (p.enabled != null) parts.push(`enabled=${p.enabled}`);
  if (p.retentionDays != null) parts.push(`retention=${p.retentionDays}d`);
  if (p.compactionEnabled != null) parts.push(`compaction=${p.compactionEnabled}`);
  if (p.targetFileSize != null) parts.push(`targetFileSize=${p.targetFileSize}`);
  if (p.smallFileMinCount != null) parts.push(`smallFileMinCount=${p.smallFileMinCount}`);
  if (p.rewriteDeleteThreshold != null) parts.push(`rewriteDeleteThreshold=${p.rewriteDeleteThreshold}`);
  if (p.cleanupGraceDays != null) parts.push(`cleanupGrace=${p.cleanupGraceDays}d`);
  if (p.orphanMinAgeDays != null) parts.push(`orphanMinAge=${p.orphanMinAgeDays}d`);
  if (p.cron != null) parts.push(`cron=${p.cron}`);
  return parts.length > 0 ? parts.join(', ') : '(no overrides)';
}

/** Per-database managed-maintenance panel: tenantdb policy form (create on
  * first save), schema/table override rows, run history with keyset
  * pagination, and a manual-run disclosure. */
export default function MaintenancePanel({ tenant, tenantDb }: {
  tenant: string; tenantDb: string;
}) {
  const [rows, setRows] = useState<MaintenancePolicyEntry[] | null>(null);
  const [effective, setEffective] = useState<MaintenanceEffectiveEntry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [policyError, setPolicyError] = useState<string | null>(null);
  const [savedNote, setSavedNote] = useState<string | null>(null);
  const genRef = useRef(0);

  // Pinned summary (reuses the snapshot-tag endpoint).
  const [protectedTagCount, setProtectedTagCount] = useState<number | null>(null);

  // Policy form state (tenantdb scope). Empty string = inherit the default.
  const [fEnabled, setFEnabled] = useState('');
  const [fRetention, setFRetention] = useState('');
  const [fCompaction, setFCompaction] = useState('');
  const [fTargetFileSize, setFTargetFileSize] = useState('');
  const [fSmallFileMinCount, setFSmallFileMinCount] = useState('');
  const [fRewriteThreshold, setFRewriteThreshold] = useState('');
  const [fCleanupGrace, setFCleanupGrace] = useState('');
  const [fOrphanMinAge, setFOrphanMinAge] = useState('');
  const [fCron, setFCron] = useState('');

  // Run history.
  const [runs, setRuns] = useState<MaintenanceRunEntry[] | null>(null);
  const [runsError, setRunsError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  // Manual run form. Schema/table come from the catalog browser endpoints so
  // they render as selects; when the catalog has no schemas (or the fetch
  // fails, e.g. a non-DuckLake tenant-db) the form falls back to free text.
  const [runScopeKind, setRunScopeKind] = useState<'tenantdb' | 'table'>('tenantdb');
  const [runScopeSchema, setRunScopeSchema] = useState('');
  const [runScopeTable, setRunScopeTable] = useState('');
  const [runSchemas, setRunSchemas] = useState<string[]>([]);
  const [runTables, setRunTables] = useState<string[]>([]);

  useEffect(() => {
    if (runScopeKind !== 'table') return;
    let cancelled = false;
    api.listCatalogSchemas(tenant, tenantDb)
      .then(r => { if (!cancelled) setRunSchemas(r.map(s => s.name)); })
      .catch(() => { if (!cancelled) setRunSchemas([]); });
    return () => { cancelled = true; };
  }, [tenant, tenantDb, runScopeKind]);

  useEffect(() => {
    if (runScopeKind !== 'table' || !runScopeSchema || runSchemas.length === 0) {
      setRunTables([]);
      return;
    }
    let cancelled = false;
    api.listCatalogTables(tenant, tenantDb, runScopeSchema)
      .then(r => { if (!cancelled) setRunTables(r.map(t => t.name)); })
      .catch(() => { if (!cancelled) setRunTables([]); });
    return () => { cancelled = true; };
  }, [tenant, tenantDb, runScopeKind, runScopeSchema, runSchemas.length]);
  const [runOps, setRunOps] = useState<string[]>([...ALL_OPERATIONS]);
  const [runError, setRunError] = useState<string | null>(null);
  const [runNote, setRunNote] = useState<string | null>(null);
  const [triggering, setTriggering] = useState(false);

  function bindForm(policyRows: MaintenancePolicyEntry[]) {
    const base = policyRows.find(p => p.scopeKind === 'tenantdb');
    setFEnabled(base?.enabled == null ? '' : String(base.enabled));
    setFRetention(base?.retentionDays == null ? '' : String(base.retentionDays));
    setFCompaction(base?.compactionEnabled == null ? '' : String(base.compactionEnabled));
    setFTargetFileSize(base?.targetFileSize ?? '');
    setFSmallFileMinCount(base?.smallFileMinCount == null ? '' : String(base.smallFileMinCount));
    setFRewriteThreshold(base?.rewriteDeleteThreshold == null ? '' : String(base.rewriteDeleteThreshold));
    setFCleanupGrace(base?.cleanupGraceDays == null ? '' : String(base.cleanupGraceDays));
    setFOrphanMinAge(base?.orphanMinAgeDays == null ? '' : String(base.orphanMinAgeDays));
    setFCron(base?.cron ?? '');
  }

  function reloadPolicy(gen: number) {
    api.getMaintenancePolicy(tenant, tenantDb)
      .then(r => {
        if (genRef.current !== gen) return;
        setRows(r.rows);
        setEffective(r.effective);
        bindForm(r.rows);
      })
      .catch(e => { if (genRef.current === gen) setError(String(e)); });
  }

  function reloadRuns(gen: number) {
    api.listMaintenanceRuns(tenant, tenantDb, RUNS_PAGE)
      .then(r => {
        if (genRef.current !== gen) return;
        setRuns(r);
        setHasMore(r.length === RUNS_PAGE);
      })
      .catch(e => { if (genRef.current === gen) setRunsError(String(e)); });
  }

  useEffect(() => {
    genRef.current += 1;
    const gen = genRef.current;
    setRows(null);
    setEffective(null);
    setError(null);
    setPolicyError(null);
    setSavedNote(null);
    setRuns(null);
    setRunsError(null);
    setHasMore(false);
    setLoadingMore(false);
    setRunError(null);
    setRunNote(null);
    setProtectedTagCount(null);
    reloadPolicy(gen);
    reloadRuns(gen);
    api.listCatalogTags(tenant, tenantDb)
      .then(tags => {
        if (genRef.current === gen) setProtectedTagCount(tags.filter(t => t.protected).length);
      })
      .catch(() => { if (genRef.current === gen) setProtectedTagCount(null); });
    // eslint-disable-next-line
  }, [tenant, tenantDb]);

  function handleSave(ev: React.FormEvent) {
    ev.preventDefault();
    setPolicyError(null);
    setSavedNote(null);
    const req: MaintenancePolicyUpsertRequest = { tenant, tenantDb, scopeKind: 'tenantdb' };
    if (fEnabled !== '') req.enabled = fEnabled === 'true';
    if (fRetention.trim() !== '') req.retentionDays = Number(fRetention);
    if (fCompaction !== '') req.compactionEnabled = fCompaction === 'true';
    if (fTargetFileSize.trim() !== '') req.targetFileSize = fTargetFileSize.trim();
    if (fSmallFileMinCount.trim() !== '') req.smallFileMinCount = Number(fSmallFileMinCount);
    if (fRewriteThreshold.trim() !== '') req.rewriteDeleteThreshold = Number(fRewriteThreshold);
    if (fCleanupGrace.trim() !== '') req.cleanupGraceDays = Number(fCleanupGrace);
    if (fOrphanMinAge.trim() !== '') req.orphanMinAgeDays = Number(fOrphanMinAge);
    if (fCron.trim() !== '') req.cron = fCron.trim();
    api.upsertMaintenancePolicy(req)
      .then(() => {
        setSavedNote('Policy saved.');
        reloadPolicy(genRef.current);
      })
      .catch(e => setPolicyError(String(e)));
  }

  function removeOverride(p: MaintenancePolicyEntry) {
    const scope = p.scopeKind === 'table'
      ? `${p.scopeSchema ?? ''}.${p.scopeTable ?? ''}`
      : p.scopeSchema ?? '';
    if (!window.confirm(`Delete the ${p.scopeKind} override for '${scope}'?`)) return;
    setPolicyError(null);
    api.deleteMaintenancePolicy(p.id)
      .then(() => reloadPolicy(genRef.current))
      .catch(e => setPolicyError(String(e)));
  }

  function loadMoreRuns() {
    if (!runs || loadingMore || runs.length === 0) return;
    const oldest = runs[runs.length - 1];
    const gen = genRef.current;
    setLoadingMore(true);
    api.listMaintenanceRuns(tenant, tenantDb, RUNS_PAGE, oldest.id)
      .then(r => {
        if (genRef.current === gen) {
          setRuns(prev => [...(prev ?? []), ...r]);
          setHasMore(r.length === RUNS_PAGE);
        }
      })
      .catch(e => { if (genRef.current === gen) setRunsError(String(e)); })
      .finally(() => { if (genRef.current === gen) setLoadingMore(false); });
  }

  function toggleOp(op: string) {
    setRunOps(prev => prev.includes(op) ? prev.filter(o => o !== op) : [...prev, op]);
  }

  function handleTriggerRun(ev: React.FormEvent) {
    ev.preventDefault();
    setRunError(null);
    setRunNote(null);
    setTriggering(true);
    const req: { tenant: string; tenantDb: string; scope?: string; operations?: string } =
      { tenant, tenantDb };
    if (runScopeKind === 'table')
      req.scope = `table:${runScopeSchema.trim()}.${runScopeTable.trim()}`;
    // All boxes checked = full chain = omit the field (same as the API default).
    if (runOps.length < ALL_OPERATIONS.length)
      req.operations = ALL_OPERATIONS.filter(o => runOps.includes(o)).join(',');
    const gen = genRef.current;
    api.triggerMaintenanceRun(req)
      .then(r => {
        if (genRef.current !== gen) return;
        setRunNote(`Run ${r.id} queued.`);
        reloadRuns(gen);
      })
      .catch(e => { if (genRef.current === gen) setRunError(String(e)); })
      .finally(() => { if (genRef.current === gen) setTriggering(false); });
  }

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (!rows || !effective) return <p>Loading maintenance policy...</p>;

  const overrides = rows.filter(p => p.scopeKind !== 'tenantdb');

  return (
    <section style={{ marginTop: 24 }}>
      <h3>Maintenance policy</h3>
      <p className="subtle">
        Blank fields inherit the effective default shown as the placeholder.
        The policy applies to this database; add schema or table override rows
        via the REST API for finer scopes.
      </p>
      {protectedTagCount != null && protectedTagCount > 0 && (
        <p className="subtle">
          {protectedTagCount} protected tag{protectedTagCount === 1 ? '' : 's'} pin
          snapshots against expiry (see the Snapshots panel under Databases).
        </p>
      )}
      {policyError && <p style={{ color: 'red' }}>Policy error: {policyError}</p>}
      {savedNote && <p style={{ color: 'var(--ok, #15803d)' }}>{savedNote}</p>}

      <form onSubmit={handleSave}>
        <fieldset>
          <legend>Database policy (<code>{tenantDb}</code>)</legend>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: '0.5rem 1rem' }}>
            <label>
              Enabled
              <select value={fEnabled} onChange={ev => setFEnabled(ev.target.value)}>
                <option value="">inherit ({String(effective.enabled)})</option>
                <option value="true">enabled</option>
                <option value="false">disabled</option>
              </select>
            </label>
            <label>
              Retention (days)
              <input
                type="number" min={1} value={fRetention}
                onChange={ev => setFRetention(ev.target.value)}
                placeholder={String(effective.retentionDays)}
              />
              <span className="subtle" style={{ display: 'block', fontSize: '0.8em' }}>
                bounds time-travel / undrop / history horizons
              </span>
            </label>
            <label>
              Compaction
              <select value={fCompaction} onChange={ev => setFCompaction(ev.target.value)}>
                <option value="">inherit ({String(effective.compactionEnabled)})</option>
                <option value="true">enabled</option>
                <option value="false">disabled</option>
              </select>
            </label>
            <label>
              Target file size
              <input
                value={fTargetFileSize}
                onChange={ev => setFTargetFileSize(ev.target.value)}
                placeholder={effective.targetFileSize}
              />
            </label>
            <label>
              Small-file min count
              <input
                type="number" min={1} value={fSmallFileMinCount}
                onChange={ev => setFSmallFileMinCount(ev.target.value)}
                placeholder={String(effective.smallFileMinCount)}
              />
            </label>
            <label>
              Rewrite delete threshold
              <input
                type="number" min={0} max={1} step={0.05} value={fRewriteThreshold}
                onChange={ev => setFRewriteThreshold(ev.target.value)}
                placeholder={String(effective.rewriteDeleteThreshold)}
              />
            </label>
            <label>
              Cleanup grace (days)
              <input
                type="number" min={1} value={fCleanupGrace}
                onChange={ev => setFCleanupGrace(ev.target.value)}
                placeholder={String(effective.cleanupGraceDays)}
              />
            </label>
            <label>
              Orphan min age (days)
              <input
                type="number" min={1} value={fOrphanMinAge}
                onChange={ev => setFOrphanMinAge(ev.target.value)}
                placeholder={String(effective.orphanMinAgeDays)}
              />
            </label>
            <label>
              Cron
              <input
                value={fCron}
                onChange={ev => setFCron(ev.target.value)}
                placeholder={effective.cron}
              />
            </label>
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '0.75rem' }}>
            <button type="submit">
              {rows.some(p => p.scopeKind === 'tenantdb') ? 'Save policy' : 'Create policy'}
            </button>
          </div>
        </fieldset>
      </form>

      <h4 style={{ marginTop: '1.5rem' }}>Scope overrides</h4>
      {overrides.length === 0
        ? <em style={{ color: '#888' }}>no schema or table overrides</em>
        : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th align="left">Scope</th>
                <th align="left">Kind</th>
                <th align="left">Overrides</th>
                <th align="left"></th>
              </tr>
            </thead>
            <tbody>
              {overrides.map(p => (
                <tr key={p.id} style={{ borderTop: '1px solid #eee' }}>
                  <td>
                    <code>
                      {p.scopeKind === 'table'
                        ? `${p.scopeSchema ?? ''}.${p.scopeTable ?? ''}`
                        : p.scopeSchema ?? ''}
                    </code>
                  </td>
                  <td>{p.scopeKind}</td>
                  <td><code style={{ fontSize: '0.85em' }}>{overrideSummary(p)}</code></td>
                  <td>
                    <button type="button" style={chipBtn} onClick={() => removeOverride(p)}>
                      delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: '1.5rem' }}>
        <h4 style={{ margin: 0 }}>Run history</h4>
        <button
          type="button"
          className="copy-btn"
          onClick={() => { setRunsError(null); reloadRuns(genRef.current); }}
        >
          Refresh
        </button>
      </div>
      {runsError && <p style={{ color: 'red' }}>Runs error: {runsError}</p>}
      {!runs
        ? <p>Loading runs...</p>
        : runs.length === 0
          ? <em style={{ color: '#888' }}>no maintenance runs yet</em>
          : (
            <>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th align="right">Id</th>
                    <th align="left">Trigger</th>
                    <th align="left">Scope</th>
                    <th align="left">Status</th>
                    <th align="left">Queued at</th>
                    <th align="right">Expired</th>
                    <th align="right">Pinned skips</th>
                    <th align="right">Merged / rewritten</th>
                    <th align="right">Cleaned / orphans</th>
                    <th align="right">Reclaimed</th>
                    <th align="right">Duration</th>
                  </tr>
                </thead>
                <tbody>
                  {runs.map(r => (
                    <tr key={r.id} style={{ borderTop: '1px solid #eee' }} title={r.error ?? undefined}>
                      <td align="right">{r.id}</td>
                      <td>{r.trigger}</td>
                      <td><code style={{ fontSize: '0.85em' }}>{r.scope}</code></td>
                      <td>
                        {statusChip(r.status)}
                        {r.error && (
                          <span className="subtle" style={{ marginLeft: 6, fontSize: '0.8em' }}>
                            {r.error.length > 60 ? `${r.error.slice(0, 60)}...` : r.error}
                          </span>
                        )}
                      </td>
                      <td>{new Date(r.queuedAt).toLocaleString()}</td>
                      <td align="right">{r.snapshotsExpired}</td>
                      <td align="right">{r.snapshotsSkippedPinned}</td>
                      <td align="right">{r.filesMerged} / {r.filesRewritten}</td>
                      <td align="right">{r.filesCleaned} / {r.orphansDeleted}</td>
                      <td align="right">{formatBytes(r.bytesReclaimed)}</td>
                      <td align="right">{runDuration(r)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {hasMore && (
                <div style={{ marginTop: 12, textAlign: 'center' }}>
                  <button onClick={loadMoreRuns} disabled={loadingMore}>
                    {loadingMore ? 'Loading...' : 'Load older runs'}
                  </button>
                </div>
              )}
            </>
          )}

      <details style={{ marginTop: '1.5rem' }}>
        <summary style={{ cursor: 'pointer', color: 'var(--text-mute)' }}>
          Advanced: run maintenance now
        </summary>
        <p className="subtle" style={{ marginBottom: 4 }}>
          Enqueues a manual run for this database. Whole database runs the full
          chain; single table runs only the table-safe steps. Unchecking
          operations restricts the run to the checked subset.
        </p>
        <form onSubmit={handleTriggerRun}>
          <label>
            Scope
            <select
              value={runScopeKind}
              onChange={ev => setRunScopeKind(ev.target.value as 'tenantdb' | 'table')}
            >
              <option value="tenantdb">whole database (full chain)</option>
              <option value="table">single table (table-safe steps)</option>
            </select>
          </label>
          {runScopeKind === 'table' && (
            <>
              <label>
                Schema
                {runSchemas.length > 0
                  ? (
                    <select
                      value={runScopeSchema}
                      onChange={ev => { setRunScopeSchema(ev.target.value); setRunScopeTable(''); }}
                    >
                      <option value="">pick a schema</option>
                      {runSchemas.map(s => <option key={s} value={s}>{s}</option>)}
                    </select>
                  )
                  : (
                    <input
                      value={runScopeSchema}
                      onChange={ev => setRunScopeSchema(ev.target.value)}
                      placeholder="tpch1"
                    />
                  )}
              </label>
              <label>
                Table
                {runSchemas.length > 0
                  ? (
                    <select
                      value={runScopeTable}
                      onChange={ev => setRunScopeTable(ev.target.value)}
                      disabled={!runScopeSchema}
                    >
                      <option value="">{runScopeSchema ? 'pick a table' : 'pick a schema first'}</option>
                      {runTables.map(t => <option key={t} value={t}>{t}</option>)}
                    </select>
                  )
                  : (
                    <input
                      value={runScopeTable}
                      onChange={ev => setRunScopeTable(ev.target.value)}
                      placeholder="lineitem"
                    />
                  )}
              </label>
            </>
          )}
          <fieldset style={{ border: 'none', margin: '0.5rem 0 0', padding: 0 }}>
            <legend className="subtle" style={{ padding: 0 }}>Operations</legend>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.25rem 1rem' }}>
              {ALL_OPERATIONS.map(op => (
                <label key={op} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <input
                    type="checkbox"
                    checked={runOps.includes(op)}
                    onChange={() => toggleOp(op)}
                  />
                  {op}
                </label>
              ))}
            </div>
          </fieldset>
          {runError && <p style={{ color: 'red' }}>Run error: {runError}</p>}
          {runNote && <p style={{ color: 'var(--ok, #15803d)' }}>{runNote}</p>}
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '0.5rem' }}>
            <button
              type="submit"
              disabled={
                triggering ||
                runOps.length === 0 ||
                (runScopeKind === 'table' &&
                  (runScopeSchema.trim() === '' || runScopeTable.trim() === ''))
              }
              title={
                runOps.length === 0
                  ? 'check at least one operation'
                  : runScopeKind === 'table' &&
                      (runScopeSchema.trim() === '' || runScopeTable.trim() === '')
                    ? 'schema and table are required for a single-table run'
                    : undefined
              }
            >
              {triggering ? 'Queuing...' : 'Run maintenance now'}
            </button>
          </div>
        </form>
      </details>
    </section>
  );
}
