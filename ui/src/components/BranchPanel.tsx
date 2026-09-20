import { CSSProperties, FormEvent, Fragment, useEffect, useState } from 'react';
import { api } from '../api/client';
import type {
  BranchChangesResponse,
  BranchDetailResponse,
  BranchEntry,
  BranchTableChange,
  DataDiffResponse,
} from '../api/types';
import { Modal } from './Modal';

const chipBtn: CSSProperties = {
  background: 'none', border: 'none', cursor: 'pointer', padding: 0,
  font: 'inherit', fontSize: '0.9em', color: 'inherit', textDecoration: 'underline',
};

function statusChip(status: string) {
  const palette: Record<string, { bg: string; fg: string }> = {
    open:      { bg: 'rgba(59, 130, 246, 0.15)', fg: '#1d4ed8' },
    proposed:  { bg: 'rgba(251, 191, 36, 0.15)', fg: '#a16207' },
    merged:    { bg: 'rgba(34, 197, 94, 0.15)',  fg: '#15803d' },
    discarded: { bg: 'rgba(107, 114, 128, 0.15)',fg: '#4b5563' },
    expired:   { bg: 'rgba(107, 114, 128, 0.15)',fg: '#4b5563' },
    failed:    { bg: 'rgba(239, 68, 68, 0.15)',  fg: '#b91c1c' },
    abandoned: { bg: 'rgba(107, 114, 128, 0.15)',fg: '#4b5563' },
  };
  const c = palette[status] ?? palette.discarded;
  return (
    <span style={{
      background: c.bg, color: c.fg, borderRadius: 4,
      padding: '0 6px', fontSize: '0.85em', fontWeight: 600,
    }}>{status}</span>
  );
}

function kindChip(t: BranchTableChange) {
  const palette: Record<string, string> = {
    created:   'rgba(34, 197, 94, 0.15)',
    dropped:   'rgba(239, 68, 68, 0.15)',
    recreated: 'rgba(251, 191, 36, 0.15)',
    modified:  'rgba(59, 130, 246, 0.15)',
    altered:   'rgba(239, 68, 68, 0.15)',
  };
  return (
    <span style={{ background: palette[t.kind] ?? 'rgba(148,163,184,0.15)', borderRadius: 4, padding: '0 6px', fontSize: '0.85em', fontWeight: 600 }}>
      {t.kind}
    </span>
  );
}

function fmtTs(s?: string | null): string {
  if (!s) return '-';
  return s.replace('T', ' ').replace(/\.\d+Z$/, 'Z');
}

/** Error text from the API client: the handlers' `code: message` envelope when present. */
function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** Row-level diff of one table between the branch's fork and its head, in a modal.
  * Same rendering as the catalog compare tab (Spec 02), paginated by keyset cursor. */
function BranchDiffModal({ tenant, tenantDb, branch, schema, table, onClose }: {
  tenant: string; tenantDb: string; branch: string; schema: string; table: string;
  onClose: () => void;
}) {
  const [diff, setDiff] = useState<DataDiffResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('');

  function load(opts?: { cursor?: string; changeType?: string }) {
    setLoading(true);
    setError(null);
    api.branchDiff(tenant, tenantDb, branch, schema, table, {
      limit: 100,
      cursor: opts?.cursor,
      changeType: opts?.changeType ?? (filter || undefined),
    })
      .then(page => {
        setDiff(prev => (opts?.cursor && prev)
          ? { ...page, rows: [...prev.rows, ...page.rows] }
          : page);
      })
      .catch(e => setError(errText(e)))
      .finally(() => setLoading(false));
  }

  useEffect(() => { load(); /* eslint-disable-next-line */ }, [tenant, tenantDb, branch, schema, table]);

  const cell = (v: unknown, j: number, style?: CSSProperties) => (
    <td key={j} style={style}>
      {v === null || v === undefined ? <em style={{ color: '#888' }}>null</em> : String(v)}
    </td>
  );

  return (
    <Modal maxWidth={1100} height="80vh" onClose={onClose}>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <div className="card-title" style={{ margin: 0 }}>
          Diff <code>{schema}.{table}</code> on branch <code>{branch}</code>
        </div>
        <button type="button" onClick={onClose}>Close</button>
      </div>
      <div className="row" style={{ gap: 12, alignItems: 'center', margin: '0.5rem 0' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span className="subtle">Change type</span>
          <select value={filter} onChange={ev => { setFilter(ev.target.value); load({ changeType: ev.target.value || undefined }); }}>
            <option value="">all</option>
            <option value="insert">insert</option>
            <option value="delete">delete</option>
            <option value="update">update</option>
          </select>
        </label>
        {diff && (
          <span className="subtle">
            snapshots {diff.from} {'->'} {diff.to}; {diff.summary.inserted} inserted, {diff.summary.deleted} deleted, {diff.summary.updated} updated
          </span>
        )}
      </div>
      {error && <div className="login-err">Error: {error}</div>}
      <div style={{ overflow: 'auto', flex: 1 }}>
        {diff && diff.rows.length === 0 && !loading && <em style={{ color: '#888' }}>no row changes</em>}
        {diff && diff.rows.length > 0 && (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th align="left">Change</th>
                <th align="right">Snapshot</th>
                {diff.columns.map(c => (
                  <th key={c.name} align="left">
                    {c.name}<br />
                    <span className="subtle" style={{ fontWeight: 'normal' }}>{c.dataType}</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {diff.rows.map((e, i) => {
                if (e.changeType === 'update') {
                  return (
                    <Fragment key={i}>
                      <tr style={{ borderTop: '1px solid #eee', background: 'rgba(251, 191, 36, 0.1)' }}>
                        <td rowSpan={2}>update</td>
                        <td rowSpan={2} align="right">{e.snapshotId}</td>
                        {(e.before ?? []).map((v, j) => cell(v, j, { opacity: 0.6, textDecoration: 'line-through' }))}
                      </tr>
                      <tr style={{ background: 'rgba(251, 191, 36, 0.1)' }}>
                        {(e.after ?? []).map((v, j) => cell(v, j))}
                      </tr>
                    </Fragment>
                  );
                }
                const bg = e.changeType === 'insert'
                  ? 'rgba(34, 197, 94, 0.1)'
                  : e.changeType === 'delete' ? 'rgba(239, 68, 68, 0.1)' : 'rgba(148, 163, 184, 0.1)';
                return (
                  <tr key={i} style={{ borderTop: '1px solid #eee', background: bg }}>
                    <td>{e.changeType}</td>
                    <td align="right">{e.snapshotId}</td>
                    {(e.row ?? []).map((v, j) => cell(v, j))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
      {diff?.nextCursor && !loading && (
        <button type="button" style={{ marginTop: 12, alignSelf: 'flex-start' }} onClick={() => load({ cursor: diff.nextCursor! })}>
          Load more
        </button>
      )}
      {loading && <p className="subtle">Loading...</p>}
    </Modal>
  );
}

/** The change set of one branch: touched tables with counts and the merge verdict, plus the
  * merge history. Expanded inline under the branch row. */
function BranchDetail({ tenant, tenantDb, branch, onDiff }: {
  tenant: string; tenantDb: string; branch: BranchEntry;
  onDiff: (schema: string, table: string) => void;
}) {
  const live = branch.status === 'open' || branch.status === 'proposed';
  const [changes, setChanges] = useState<BranchChangesResponse | null>(null);
  const [detail, setDetail] = useState<BranchDetailResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setChanges(null);
    setDetail(null);
    setError(null);
    api.getBranch(tenant, tenantDb, branch.name)
      .then(d => { if (!cancelled) setDetail(d); })
      .catch(e => { if (!cancelled) setError(errText(e)); });
    if (live) {
      api.branchChanges(tenant, tenantDb, branch.name)
        .then(c => { if (!cancelled) setChanges(c); })
        .catch(e => { if (!cancelled) setError(errText(e)); });
    }
    return () => { cancelled = true; };
  }, [tenant, tenantDb, branch.name, branch.status, live]);

  return (
    <div style={{ padding: '0.5rem 0 0.75rem 1.5rem' }}>
      {error && <div className="login-err">Error: {error}</div>}
      <p className="subtle" style={{ margin: '0 0 0.5rem 0' }}>
        Owner <code>{branch.owner}</code>; fork snapshot {branch.forkSnapshot}; pool <code>{branch.pool}</code>;
        catalog <code>{branch.catalogDb}</code>; expires {fmtTs(branch.expiresAt) === '-' ? 'never' : fmtTs(branch.expiresAt)}.
      </p>
      {live && !changes && !error && <p className="subtle">Computing change set...</p>}
      {changes && (
        <>
          <div className="row" style={{ gap: 12, alignItems: 'center', marginBottom: 6 }}>
            <strong>Changes since fork</strong>
            <span className="subtle">
              branch head {changes.headSnapshot}, main at {changes.mainSnapshot}
            </span>
            {changes.mergeable
              ? <span style={{ color: '#15803d', fontWeight: 600 }}>fast-forward possible</span>
              : <span style={{ color: '#b91c1c', fontWeight: 600 }}>not mergeable as is</span>}
          </div>
          {changes.tables.length === 0
            ? <em style={{ color: '#888' }}>no table changes</em>
            : (
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th align="left">Table</th>
                    <th align="left">Kind</th>
                    <th align="right">Inserted</th>
                    <th align="right">Deleted</th>
                    <th align="right">Updated</th>
                    <th align="left">Merge</th>
                    <th align="left"></th>
                  </tr>
                </thead>
                <tbody>
                  {changes.tables.map(t => (
                    <tr key={`${t.schema}.${t.table}`} style={{ borderTop: '1px solid #eee' }}>
                      <td><code>{t.schema}.{t.table}</code></td>
                      <td>{kindChip(t)}</td>
                      <td align="right">{t.inserted}</td>
                      <td align="right">{t.deleted}</td>
                      <td align="right">{t.updated}</td>
                      <td>{t.mergeable ? 'ok' : <span style={{ color: '#b91c1c' }}>{t.reason ?? 'refused'}</span>}</td>
                      <td>
                        {(t.kind === 'modified' || t.kind === 'created' || t.kind === 'recreated') && (
                          <button type="button" style={chipBtn} onClick={() => onDiff(t.schema, t.table)}>rows</button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          {changes.conflicts.length > 0 && (
            <div style={{ marginTop: 8 }}>
              <strong style={{ color: '#b91c1c' }}>Conflicts with main</strong>
              <ul style={{ margin: '4px 0' }}>
                {changes.conflicts.map(c => (
                  <li key={`${c.schema}.${c.table}`}><code>{c.schema}.{c.table}</code>: {c.reason}</li>
                ))}
              </ul>
            </div>
          )}
          {changes.unsupported.length > 0 && (
            <div style={{ marginTop: 8 }}>
              <strong style={{ color: '#b91c1c' }}>Not mergeable in v1</strong>
              <ul style={{ margin: '4px 0' }}>
                {changes.unsupported.map(u => <li key={u}><code>{u}</code></li>)}
              </ul>
            </div>
          )}
        </>
      )}
      {detail && detail.merges.length > 0 && (
        <div style={{ marginTop: 10 }}>
          <strong>Merge requests</strong>
          <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 4 }}>
            <thead>
              <tr>
                <th align="left">Status</th>
                <th align="left">Proposer</th>
                <th align="left">Approver</th>
                <th align="right">Main before</th>
                <th align="right">Main after</th>
                <th align="left">Tag</th>
                <th align="left">Proposed</th>
                <th align="left">Decided</th>
              </tr>
            </thead>
            <tbody>
              {detail.merges.map(m => (
                <tr key={m.id} style={{ borderTop: '1px solid #eee' }}>
                  <td>{statusChip(m.status)}{m.error && <span className="subtle" title={m.error}> (error)</span>}</td>
                  <td><code>{m.proposer}</code></td>
                  <td>{m.approver ? <code>{m.approver}</code> : '-'}</td>
                  <td align="right">{m.mainSnapshotAtPropose}</td>
                  <td align="right">{m.mainSnapshotAfter ?? '-'}</td>
                  <td>{m.tagName ? <code>{m.tagName}</code> : '-'}</td>
                  <td>{fmtTs(m.createdAt)}</td>
                  <td>{fmtTs(m.decidedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/** Per-database branches panel (Epic 1): create, list (live or all), expand a branch for its
  * change set, merge history and per-table row diffs, and the propose / merge / discard actions.
  * Merge is the human gate: the server refuses the proposer's own identity. */
export default function BranchPanel({ tenant, tenantDb }: { tenant: string; tenantDb: string }) {
  const [rows, setRows] = useState<BranchEntry[] | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [diffTarget, setDiffTarget] = useState<{ branch: string; schema: string; table: string } | null>(null);
  const [fName, setFName] = useState('');
  const [fTtl, setFTtl] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  function reload() {
    setError(null);
    api.listBranches(tenant, tenantDb, showAll)
      .then(r => setRows(r.branches))
      .catch(e => { setRows([]); setError(errText(e)); });
  }

  useEffect(() => { setRows(null); setExpanded(null); reload(); /* eslint-disable-next-line */ }, [tenant, tenantDb, showAll]);

  function create(ev: FormEvent) {
    ev.preventDefault();
    setCreateError(null);
    setNote(null);
    setCreating(true);
    const req = { tenant, tenantDb, name: fName.trim(), ...(fTtl.trim() !== '' ? { ttlHours: Number(fTtl) } : {}) };
    api.createBranch(req)
      .then(b => {
        setNote(`branch '${b.name}' created at snapshot ${b.forkSnapshot} (pool ${b.pool})`);
        setFName('');
        setFTtl('');
        reload();
      })
      .catch(e => setCreateError(errText(e)))
      .finally(() => setCreating(false));
  }

  function act(label: string, b: BranchEntry, run: () => Promise<unknown>, onOk: (r: unknown) => string) {
    setError(null);
    setNote(null);
    setBusy(b.name);
    run()
      .then(r => { setNote(onOk(r)); reload(); })
      .catch(e => setError(`${label} '${b.name}': ${errText(e)}`))
      .finally(() => setBusy(null));
  }

  const propose = (b: BranchEntry) =>
    act('propose', b, () => api.proposeBranch({ tenant, tenantDb, branch: b.name }), r => {
      const res = r as { changes: BranchChangesResponse };
      return `branch '${b.name}' proposed: ${res.changes.tables.length} table(s), ` +
        `${res.changes.conflicts.length} conflict(s), ${res.changes.mergeable ? 'mergeable' : 'not mergeable'}`;
    });

  const merge = (b: BranchEntry) => {
    if (!window.confirm(
      `Fast-forward merge branch '${b.name}' into ${tenantDb}? Main gains one snapshot with the ` +
      'branch changes, the merge is tagged, and the branch is torn down. The server refuses the ' +
      "proposer's own identity."
    )) return;
    act('merge', b, () => api.mergeBranch({ tenant, tenantDb, branch: b.name }), r => {
      const res = r as { merge: { mainSnapshotAfter?: number | null; tagName?: string | null } };
      return `branch '${b.name}' merged: main snapshot ${res.merge.mainSnapshotAfter ?? '?'}, tag ${res.merge.tagName ?? '?'}`;
    });
  };

  const discard = (b: BranchEntry) => {
    if (!window.confirm(`Discard branch '${b.name}'? Its pool, catalog and files are freed; this cannot be undone.`)) return;
    act('discard', b, () => api.discardBranch({ tenant, tenantDb, branch: b.name }), () => `branch '${b.name}' discarded`);
  };

  return (
    <div>
      <form onSubmit={create} className="row" style={{ gap: 8, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span className="subtle">New branch name</span>
          <input value={fName} onChange={ev => setFName(ev.target.value)} placeholder="feature-x" pattern="[a-z][a-z0-9_-]{0,47}" required />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span className="subtle">TTL hours (blank = default, 0 = never)</span>
          <input value={fTtl} onChange={ev => setFTtl(ev.target.value)} type="number" min={0} style={{ width: 120 }} />
        </label>
        <button type="submit" disabled={creating || fName.trim() === ''}>{creating ? 'Creating...' : 'Create branch'}</button>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginLeft: 'auto' }}>
          <input type="checkbox" checked={showAll} onChange={ev => setShowAll(ev.target.checked)} />
          <span className="subtle">include merged / discarded / expired</span>
        </label>
        <button type="button" className="copy-btn" onClick={reload}>refresh</button>
      </form>
      {createError && <div className="login-err">Create failed: {createError}</div>}
      {error && <div className="login-err">Error: {error}</div>}
      {note && <p className="subtle" style={{ color: '#15803d' }}>{note}</p>}

      {!rows && <p className="subtle">Loading branches...</p>}
      {rows && rows.length === 0 && (
        <em style={{ color: '#888' }}>
          {showAll ? 'no branches' : 'no live branches; agents create them with create_branch or `qod branch create`'}
        </em>
      )}
      {rows && rows.length > 0 && (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th align="left">Branch</th>
              <th align="left">Status</th>
              <th align="left">Owner</th>
              <th align="right">Fork</th>
              <th align="left">Created</th>
              <th align="left">Expires</th>
              <th align="left">Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(b => {
              const live = b.status === 'open' || b.status === 'proposed';
              const isOpen = expanded === b.name;
              return (
                <Fragment key={b.id}>
                  <tr style={{ borderTop: '1px solid #eee' }}>
                    <td>
                      <button type="button" style={chipBtn} onClick={() => setExpanded(isOpen ? null : b.name)}>
                        {isOpen ? '▾' : '▸'} <code>{b.name}</code>
                      </button>
                    </td>
                    <td>{statusChip(b.status)}</td>
                    <td><code>{b.owner}</code></td>
                    <td align="right">{b.forkSnapshot}</td>
                    <td>{fmtTs(b.createdAt)}</td>
                    <td>{live ? (b.expiresAt ? fmtTs(b.expiresAt) : 'never') : '-'}</td>
                    <td style={{ display: 'flex', gap: 10 }}>
                      {b.status === 'open' && (
                        <button type="button" style={chipBtn} disabled={busy === b.name} onClick={() => propose(b)}>propose</button>
                      )}
                      {b.status === 'proposed' && (
                        <button type="button" style={chipBtn} disabled={busy === b.name} onClick={() => merge(b)}>merge</button>
                      )}
                      {live && (
                        <button type="button" style={chipBtn} disabled={busy === b.name} onClick={() => discard(b)}>discard</button>
                      )}
                    </td>
                  </tr>
                  {isOpen && (
                    <tr>
                      <td colSpan={7} style={{ background: 'rgba(148,163,184,0.06)' }}>
                        <BranchDetail
                          tenant={tenant}
                          tenantDb={tenantDb}
                          branch={b}
                          onDiff={(schema, table) => setDiffTarget({ branch: b.name, schema, table })}
                        />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      )}
      {diffTarget && (
        <BranchDiffModal
          tenant={tenant}
          tenantDb={tenantDb}
          branch={diffTarget.branch}
          schema={diffTarget.schema}
          table={diffTarget.table}
          onClose={() => setDiffTarget(null)}
        />
      )}
    </div>
  );
}