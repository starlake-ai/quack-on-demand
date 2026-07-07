import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { CatalogSnapshotEntry } from '../api/types';

const PAGE = 200;

export default function CatalogSnapshotsPanel({ tenant, tenantDb }: {
  tenant: string; tenantDb: string;
}) {
  const [snaps, setSnaps] = useState<CatalogSnapshotEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const genRef = useRef(0);

  useEffect(() => {
    let cancelled = false;
    genRef.current += 1;
    setSnaps(null);
    setError(null);
    setHasMore(false);
    api.listCatalogSnapshots(tenant, tenantDb, PAGE)
      .then(r => {
        if (!cancelled) {
          setSnaps(r);
          setHasMore(r.length === PAGE);
        }
      })
      .catch(e => { if (!cancelled) setError(String(e)); });
    return () => { cancelled = true; };
  }, [tenant, tenantDb]);

  function loadMore() {
    if (!snaps || loadingMore) return;
    const oldest = snaps[snaps.length - 1];
    const gen = genRef.current;
    setLoadingMore(true);
    api.listCatalogSnapshots(tenant, tenantDb, PAGE, oldest.snapshotId)
      .then(r => {
        if (genRef.current === gen) {
          setSnaps(prev => [...(prev ?? []), ...r]);
          setHasMore(r.length === PAGE);
        }
      })
      .catch(e => { if (genRef.current === gen) setError(String(e)); })
      .finally(() => { if (genRef.current === gen) setLoadingMore(false); });
  }

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (!snaps) return <p>Loading snapshots...</p>;

  return (
    <section style={{ marginTop: 24 }}>
      <h3>Snapshots</h3>
      {snaps.length === 0
        ? <em style={{ color: '#888' }}>no snapshots</em>
        : (
          <>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th align="right">Id</th>
                  <th align="left">Committed at</th>
                  <th align="left">Changes</th>
                  <th align="right">Rows added</th>
                  <th align="right">Files +/-</th>
                  <th align="left">Tables</th>
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
                          </span>
                        ))}
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
    </section>
  );
}
