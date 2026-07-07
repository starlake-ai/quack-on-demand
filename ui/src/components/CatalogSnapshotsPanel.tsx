import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { CatalogSnapshotEntry } from '../api/types';

export default function CatalogSnapshotsPanel({ tenant, tenantDb }: {
  tenant: string; tenantDb: string;
}) {
  const [snaps, setSnaps] = useState<CatalogSnapshotEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setSnaps(null);
    setError(null);
    api.listCatalogSnapshots(tenant, tenantDb)
      .then(setSnaps)
      .catch(e => setError(String(e)));
  }, [tenant, tenantDb]);

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (!snaps) return <p>Loading snapshots...</p>;

  return (
    <section style={{ marginTop: 24 }}>
      <h3>Snapshots</h3>
      {snaps.length === 0
        ? <em style={{ color: '#888' }}>no snapshots</em>
        : (
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
              {snaps.map(s => (
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
                              `/${encodeURIComponent(t.schema)}/${encodeURIComponent(t.name)}?asOf=${s.snapshotId}`}
                        >
                          {t.schema}.{t.name}
                        </Link>
                      </span>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
    </section>
  );
}
