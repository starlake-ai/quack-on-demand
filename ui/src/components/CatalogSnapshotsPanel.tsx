import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { CatalogSnapshotEntry, CatalogTagEntry } from '../api/types';
import { Modal } from './Modal';
import RestoreDialog from './RestoreDialog';

const PAGE = 200;

const chipBtn: React.CSSProperties = {
  background: 'none', border: 'none', cursor: 'pointer', padding: 0,
  font: 'inherit', fontSize: '0.9em', color: 'inherit', textDecoration: 'underline',
};

export default function CatalogSnapshotsPanel({ tenant, tenantDb, refreshToken = 0 }: {
  tenant: string; tenantDb: string;
  /** Bump to force a refetch after a sibling panel commits a catalog write
    * (e.g. an undrop recovery snapshot). */
  refreshToken?: number;
}) {
  const [snaps, setSnaps] = useState<CatalogSnapshotEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const genRef = useRef(0);
  // Text filter driving the `table=` query param, format "schema.table". Free
  // text (rather than a dropdown fed from listCatalogTables) keeps the panel
  // simple: it does not need to know which schema is selected anywhere else.
  const [tableFilter, setTableFilter] = useState('');
  const [tableFilterInput, setTableFilterInput] = useState('');

  const [tags, setTags] = useState<CatalogTagEntry[]>([]);
  const [tagError, setTagError] = useState<string | null>(null);
  // Snapshot targeted by the create-tag modal; null = modal closed.
  const [tagging, setTagging] = useState<CatalogSnapshotEntry | null>(null);
  const [tagName, setTagName] = useState('');
  const [tagProtect, setTagProtect] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);

  const [restoring, setRestoring] = useState<{ schema: string; table: string; toSnapshot: number } | null>(null);
  // Local refresh bump so a completed restore refetches this panel without
  // depending on a sibling panel's refreshToken bump.
  const [localRefresh, setLocalRefresh] = useState(0);

  function reloadTags() {
    api.listCatalogTags(tenant, tenantDb).then(setTags).catch(() => setTags([]));
  }

  useEffect(() => {
    let cancelled = false;
    genRef.current += 1;
    setSnaps(null);
    setError(null);
    setHasMore(false);
    // An in-flight loadMore's .finally is gen-gated and will skip its own
    // reset after the switch, so clear the flag here or it sticks true.
    setLoadingMore(false);
    setTagError(null);
    setTagging(null);
    api.listCatalogSnapshots(tenant, tenantDb, PAGE, undefined, tableFilter || undefined)
      .then(r => {
        if (!cancelled) {
          setSnaps(r);
          // A filtered page can legitimately come back short of PAGE without
          // that meaning end-of-history (the server applies the limit before
          // the table filter narrows results) -- keep Load-older enabled
          // whenever the last fetch returned any rows at all.
          setHasMore(r.length > 0);
        }
      })
      .catch(e => { if (!cancelled) setError(String(e)); });
    api.listCatalogTags(tenant, tenantDb)
      .then(r => { if (!cancelled) setTags(r); })
      .catch(() => { if (!cancelled) setTags([]); });
    return () => { cancelled = true; };
  }, [tenant, tenantDb, tableFilter, refreshToken, localRefresh]);

  const tagsBySnapshot = useMemo(() => {
    const m = new Map<number, CatalogTagEntry[]>();
    for (const t of tags) {
      const l = m.get(t.snapshotId) ?? [];
      l.push(t);
      m.set(t.snapshotId, l);
    }
    return m;
  }, [tags]);
  const pinned = tags.filter(t => t.protected);
  const pinnedSnapshotCount = new Set(pinned.map(t => t.snapshotId)).size;

  function loadMore() {
    if (!snaps || loadingMore) return;
    const oldest = snaps[snaps.length - 1];
    const gen = genRef.current;
    setLoadingMore(true);
    api.listCatalogSnapshots(tenant, tenantDb, PAGE, oldest.snapshotId, tableFilter || undefined)
      .then(r => {
        if (genRef.current === gen) {
          setSnaps(prev => [...(prev ?? []), ...r]);
          // Same "any rows -> keep going" rule as the initial load: a table
          // filter can make a page short without meaning history is exhausted.
          setHasMore(r.length > 0);
        }
      })
      .catch(e => { if (genRef.current === gen) setError(String(e)); })
      .finally(() => { if (genRef.current === gen) setLoadingMore(false); });
  }

  function applyTableFilter(ev: React.FormEvent) {
    ev.preventDefault();
    setTableFilter(tableFilterInput.trim());
  }

  function openTagModal(s: CatalogSnapshotEntry) {
    setTagName('');
    setTagProtect(false);
    setModalError(null);
    setTagging(s);
  }

  function handleCreateTag(ev: React.FormEvent) {
    ev.preventDefault();
    if (!tagging) return;
    api.createCatalogTag({
      tenant, tenantDb, name: tagName.trim(),
      snapshotId: tagging.snapshotId, protected: tagProtect,
    })
      .then(() => { setTagging(null); reloadTags(); })
      .catch(e => setModalError(String(e)));
  }

  function removeTag(t: CatalogTagEntry) {
    if (t.protected && !window.confirm(`Remove retention hold '${t.name}'?`)) return;
    setTagError(null);
    api.deleteCatalogTag({ tenant, tenantDb, name: t.name })
      .then(reloadTags)
      .catch(e => setTagError(String(e)));
  }

  function toggleProtect(t: CatalogTagEntry) {
    setTagError(null);
    api.protectCatalogTag({ tenant, tenantDb, name: t.name, protected: !t.protected })
      .then(reloadTags)
      .catch(e => setTagError(String(e)));
  }

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (!snaps) return <p>Loading snapshots...</p>;

  return (
    <section style={{ marginTop: 24 }}>
      <h3>Snapshots</h3>
      <form onSubmit={applyTableFilter} style={{ margin: '8px 0', display: 'flex', gap: 8, alignItems: 'center' }}>
        <label>
          Filter by table&nbsp;
          <input
            value={tableFilterInput}
            onChange={ev => setTableFilterInput(ev.target.value)}
            placeholder="schema.table"
            style={{ width: 200 }}
          />
        </label>
        <button type="submit">Apply</button>
        {tableFilter && (
          <button type="button" onClick={() => { setTableFilterInput(''); setTableFilter(''); }}>
            Clear
          </button>
        )}
      </form>
      {pinned.length > 0 && (
        <p className="subtle">
          {pinnedSnapshotCount} snapshot{pinnedSnapshotCount === 1 ? '' : 's'} pinned
          by {pinned.length} protected tag{pinned.length === 1 ? '' : 's'}
        </p>
      )}
      {tagError && <p style={{ color: 'red' }}>Tag error: {tagError}</p>}
      {snaps.length === 0
        ? <em style={{ color: '#888' }}>no snapshots</em>
        : (
          <>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th align="right">Id</th>
                  <th align="left">Committed at</th>
                  <th align="left">Author</th>
                  <th align="left">Message</th>
                  <th align="left">Changes</th>
                  <th align="right">Rows added</th>
                  <th align="right">Files +/-</th>
                  <th align="left">Tables</th>
                  <th align="left">Tags</th>
                </tr>
              </thead>
              <tbody>
                {snaps.map(s => {
                  // A drop snapshot's affected tables are the dropped ones; they are not
                  // visible AS OF snapshotId (end_snapshot <= snapshotId), so we link one
                  // snapshot earlier to show the table's last visible state.
                  const isDrop = s.changes?.includes('dropped_table');
                  const tableAsOf = isDrop ? s.snapshotId - 1 : s.snapshotId;
                  return (
                    <tr key={s.snapshotId} style={{ borderTop: '1px solid #eee' }}>
                      <td align="right">{s.snapshotId}</td>
                      <td>{new Date(s.committedAt).toLocaleString()}</td>
                      <td>
                        {s.author
                          ? s.author
                          : <em style={{ color: '#888' }}>unknown</em>}
                      </td>
                      <td
                        title={s.commitMessage ?? undefined}
                        style={{
                          maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap', display: 'inline-block', verticalAlign: 'middle',
                        }}
                      >
                        {s.commitMessage ?? <span style={{ color: '#888' }}>--</span>}
                      </td>
                      <td><code style={{ fontSize: '0.85em' }}>{s.changes || '--'}</code></td>
                      <td align="right">{s.rowsAdded.toLocaleString()}</td>
                      <td align="right">{s.filesAdded} / {s.filesRemoved}</td>
                      <td>
                        {s.affectedTables.map((t, i) => (
                          <span key={`${t.schema}.${t.name}`}>
                            {i > 0 && ', '}
                            <Link
                              to={`/catalog/${encodeURIComponent(tenant)}/${encodeURIComponent(tenantDb)}` +
                                  `/${encodeURIComponent(t.schema)}/${encodeURIComponent(t.name)}?asOf=${tableAsOf}`}
                            >
                              {t.schema}.{t.name}
                            </Link>
                            <button
                              type="button"
                              style={chipBtn}
                              title={`Restore ${t.schema}.${t.name} to its state at this snapshot`}
                              onClick={() => setRestoring({ schema: t.schema, table: t.name, toSnapshot: tableAsOf })}
                            >
                              restore
                            </button>
                          </span>
                        ))}
                      </td>
                      <td>
                        {(tagsBySnapshot.get(s.snapshotId) ?? []).map(t => (
                          <span
                            key={t.name}
                            style={{
                              display: 'inline-flex', alignItems: 'center', gap: 6,
                              border: '1px solid #ccc', borderRadius: 12,
                              padding: '1px 8px', marginRight: 6, fontSize: '0.85em',
                              opacity: t.exists ? 1 : 0.5,
                            }}
                          >
                            {t.name}
                            {t.protected && (
                              <span style={{
                                background: 'rgba(251, 191, 36, 0.15)', border: '1px solid var(--warn)',
                                color: 'var(--warn)', borderRadius: 4, padding: '0 4px', fontSize: '0.9em',
                              }}>hold</span>
                            )}
                            {!t.exists && (
                              <span style={{ color: '#888', fontSize: '0.9em' }}>missing</span>
                            )}
                            <button
                              type="button"
                              style={chipBtn}
                              title={t.protected
                                ? 'Remove the retention hold (snapshot becomes expirable)'
                                : 'Protect: pin this snapshot against expiry'}
                              onClick={() => toggleProtect(t)}
                            >
                              {t.protected ? 'unprotect' : 'protect'}
                            </button>
                            <button
                              type="button"
                              style={chipBtn}
                              title={`Delete tag '${t.name}'`}
                              onClick={() => removeTag(t)}
                            >
                              &times;
                            </button>
                          </span>
                        ))}
                        <button type="button" style={chipBtn} onClick={() => openTagModal(s)}>
                          + tag
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {hasMore && (
              <div style={{ marginTop: 12, textAlign: 'center' }}>
                <button onClick={loadMore} disabled={loadingMore}>
                  {loadingMore ? 'Loading...' : 'Load older snapshots'}
                </button>
              </div>
            )}
          </>
        )}

      {tagging && (
        <Modal maxWidth={420} onClose={() => setTagging(null)}>
            <div className="card-title">Tag snapshot {tagging.snapshotId}</div>
            <p className="subtle" style={{ marginTop: 0 }}>
              Tag names are per-database handles for this snapshot; a protected
              tag also pins the snapshot (and every file it references) against
              expiry. Names cannot be all digits.
            </p>
            <form onSubmit={handleCreateTag}>
              <label>
                Tag name
                <input
                  value={tagName}
                  onChange={ev => setTagName(ev.target.value)}
                  placeholder="pre-migration"
                  autoFocus
                  required
                />
              </label>
              <label style={{ display: 'block', marginTop: '0.5rem' }}>
                <input
                  type="checkbox"
                  checked={tagProtect}
                  onChange={ev => setTagProtect(ev.target.checked)}
                />{' '}
                protect this snapshot (retention hold)
              </label>
              {modalError && <p style={{ color: 'red' }}>{modalError}</p>}
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: '1rem' }}>
                <button type="button" onClick={() => setTagging(null)}>Cancel</button>
                <button type="submit">Create</button>
              </div>
            </form>
        </Modal>
      )}

      {restoring && (
        <RestoreDialog
          tenant={tenant}
          tenantDb={tenantDb}
          schema={restoring.schema}
          table={restoring.table}
          toSnapshot={restoring.toSnapshot}
          onClose={() => setRestoring(null)}
          onRestored={() => setLocalRefresh(x => x + 1)}
        />
      )}
    </section>
  );
}