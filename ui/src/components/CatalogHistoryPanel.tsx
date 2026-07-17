import { Fragment, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type { CatalogHistoryCommit } from '../api/types';
import RestoreDialog from './RestoreDialog';

const PAGE = 50;

const OPERATIONS = ['create', 'insert', 'delete', 'update', 'alter', 'drop', 'maintenance', 'unknown'];

const OP_COLORS: Record<string, string> = {
  create: 'rgba(34, 197, 94, 0.15)',
  insert: 'rgba(34, 197, 94, 0.15)',
  update: 'rgba(251, 191, 36, 0.15)',
  alter: 'rgba(251, 191, 36, 0.15)',
  delete: 'rgba(239, 68, 68, 0.15)',
  drop: 'rgba(239, 68, 68, 0.15)',
  maintenance: 'rgba(148, 163, 184, 0.2)',
  unknown: 'rgba(148, 163, 184, 0.2)',
};

/** datetime-local input value ("2026-07-09T10:00") to the ISO instant the API expects. */
function localToIso(v: string): string | undefined {
  if (!v) return undefined;
  const d = new Date(v);
  return isNaN(d.getTime()) ? undefined : d.toISOString();
}

function delta(c: CatalogHistoryCommit): string {
  if (c.rowsAdded === 0 && c.rowsRemoved === 0) return '';
  const parts: string[] = [];
  if (c.rowsAdded > 0) parts.push(`+${c.rowsAdded.toLocaleString()}`);
  if (c.rowsRemoved > 0) parts.push(`-${c.rowsRemoved.toLocaleString()}`);
  return parts.join(' / ');
}

/** Reverse-chronological commit timeline of one table (EPIC Spec 01). Filters are server-side;
 * load-more keyset pagination via before=oldest loaded snapshotId, same as the snapshots panel. */
export default function CatalogHistoryPanel({
  tenant, tenantDb, schema, table, onViewAsOf, onCompare,
}: {
  tenant: string;
  tenantDb: string;
  schema: string;
  table: string;
  /** "View table at this snapshot": sets the page-wide ?asOf selector. */
  onViewAsOf: (snapshotId: number) => void;
  /** "Compare schema": diff the previous commit against this one in the Compare tab. */
  onCompare: (from: number, to: number) => void;
}) {
  const [commits, setCommits] = useState<CatalogHistoryCommit[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<number | null>(null);
  const [fromTs, setFromTs] = useState('');
  const [toTs, setToTs] = useState('');
  const [op, setOp] = useState('');
  const [author, setAuthor] = useState('');
  const [authorQuery, setAuthorQuery] = useState('');
  const [restoreAt, setRestoreAt] = useState<number | null>(null);
  // Monotonic request sequence: only the newest in-flight request may commit
  // its result (or its error/loading flip), so a slow older response cannot
  // clobber a newer one.
  const seqRef = useRef(0);

  // Debounce the free-text author input: one request per typing pause, not
  // one per keystroke.
  useEffect(() => {
    const t = setTimeout(() => setAuthorQuery(author), 300);
    return () => clearTimeout(t);
  }, [author]);

  function load(reset: boolean) {
    const seq = ++seqRef.current;
    setLoading(true);
    setError(null);
    const before = reset ? undefined : commits[commits.length - 1]?.snapshotId;
    api.listTableHistory(tenant, tenantDb, schema, table, {
      limit: PAGE,
      before,
      from: localToIso(fromTs),
      to: localToIso(toTs),
      operation: op || undefined,
      author: authorQuery.trim() || undefined,
    })
      .then(r => {
        if (seq !== seqRef.current) return;
        setCommits(prev => (reset ? r.commits : [...prev, ...r.commits]));
        setHasMore(r.hasMore);
      })
      .catch(e => { if (seq === seqRef.current) setError(String(e)); })
      .finally(() => { if (seq === seqRef.current) setLoading(false); });
  }

  // Initial load + reload whenever a filter changes (pagination resets with it).
  useEffect(() => {
    setExpanded(null);
    load(true);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant, tenantDb, schema, table, fromTs, toTs, op, authorQuery]);

  return (
    <div>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end', marginBottom: 12 }}>
        <label style={{ fontSize: '0.85rem' }}>From<br />
          <input type="datetime-local" value={fromTs} onChange={e => setFromTs(e.target.value)} />
        </label>
        <label style={{ fontSize: '0.85rem' }}>To<br />
          <input type="datetime-local" value={toTs} onChange={e => setToTs(e.target.value)} />
        </label>
        <label style={{ fontSize: '0.85rem' }}>Operation<br />
          <select value={op} onChange={e => setOp(e.target.value)}>
            <option value="">all</option>
            {OPERATIONS.map(o => <option key={o} value={o}>{o}</option>)}
          </select>
        </label>
        <label style={{ fontSize: '0.85rem' }}>Author<br />
          <input type="text" value={author} placeholder="exact author"
                 onChange={e => setAuthor(e.target.value)} />
        </label>
      </div>

      {error && <p style={{ color: 'red' }}>Error: {error}</p>}
      {commits.length === 0 && !loading && !error && (
        <em style={{ color: '#888' }}>no commits match</em>
      )}

      {commits.length > 0 && (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th align="left">Committed</th>
              <th align="left">Operation</th>
              <th align="left">Author</th>
              <th align="left">Message</th>
              <th align="right">Rows</th>
              <th align="left">Schema</th>
            </tr>
          </thead>
          <tbody>
            {commits.map((c, i) => (
              <Fragment key={c.snapshotId}>
                <tr
                  style={{ borderTop: '1px solid #eee', cursor: 'pointer' }}
                  onClick={() => setExpanded(expanded === c.snapshotId ? null : c.snapshotId)}
                >
                  <td>{new Date(c.committedAt).toLocaleString()}</td>
                  <td>
                    <span style={{ background: OP_COLORS[c.operation] ?? OP_COLORS.unknown,
                                   borderRadius: 4, padding: '1px 8px', fontSize: '0.85rem' }}>
                      {c.operation}
                    </span>
                  </td>
                  <td>{c.author ?? <em style={{ color: '#888' }} title="written before author stamping was enabled">unknown</em>}</td>
                  <td>{c.commitMessage ?? ''}</td>
                  <td align="right"><code>{delta(c)}</code></td>
                  <td>{c.schemaChanged ? 'changed' : ''}</td>
                </tr>
                {expanded === c.snapshotId && (
                  <tr style={{ background: 'rgba(148, 163, 184, 0.06)' }}>
                    <td colSpan={6} style={{ padding: '8px 12px' }}>
                      <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', fontSize: '0.9rem' }}>
                        <span>snapshot <code>{c.snapshotId}</code></span>
                        <span>schema version <code>{c.schemaVersion}</code></span>
                        <span>files +{c.filesAdded} / -{c.filesRemoved}</span>
                        <span>rows +{c.rowsAdded.toLocaleString()} / -{c.rowsRemoved.toLocaleString()}</span>
                      </div>
                      <div style={{ marginTop: 8, display: 'flex', gap: 12 }}>
                        <button type="button" onClick={() => onViewAsOf(c.snapshotId)}>
                          View table at this snapshot
                        </button>
                        <button type="button" onClick={() => setRestoreAt(c.snapshotId)}>
                          Restore to this snapshot
                        </button>
                        <button
                          type="button"
                          disabled={!c.schemaChanged || i + 1 >= commits.length}
                          title={c.schemaChanged
                            ? (i + 1 >= commits.length ? 'previous commit not loaded' : '')
                            : 'no schema change at this commit'}
                          onClick={() => onCompare(commits[i + 1].snapshotId, c.snapshotId)}
                        >
                          Compare schema
                        </button>
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      )}

      <div style={{ marginTop: 12 }}>
        {loading && <p className="subtle">Loading...</p>}
        {!loading && hasMore && (
          <button type="button" onClick={() => load(false)}>Load more</button>
        )}
      </div>

      {restoreAt !== null && (
        <RestoreDialog
          tenant={tenant}
          tenantDb={tenantDb}
          schema={schema}
          table={table}
          toSnapshot={restoreAt}
          onClose={() => setRestoreAt(null)}
          onRestored={() => { setRestoreAt(null); load(true); }}
        />
      )}
    </div>
  );
}
