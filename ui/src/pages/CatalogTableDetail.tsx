import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { CatalogSnapshotEntry, CatalogTableDetailResponse } from '../api/types';
import Breadcrumb from '../components/Breadcrumb';

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export default function CatalogTableDetail() {
  const { tenant, tenantDb, schema, table } = useParams<{
    tenant: string; tenantDb: string; schema: string; table: string;
  }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const asOfRaw = searchParams.get('asOf');
  const asOf = asOfRaw != null && /^\d+$/.test(asOfRaw) ? Number(asOfRaw) : undefined;
  const [detail, setDetail] = useState<CatalogTableDetailResponse | null>(null);
  const [error, setError]   = useState<string | null>(null);
  const [snaps, setSnaps] = useState<CatalogSnapshotEntry[]>([]);

  useEffect(() => {
    if (!tenant || !tenantDb || !schema || !table) return;
    let cancelled = false;
    setDetail(null);
    setError(null);
    api.getCatalogTable(tenant, tenantDb, schema, table, asOf)
      .then(r => { if (!cancelled) setDetail(r); })
      .catch(e => { if (!cancelled) setError(String(e)); });
    return () => { cancelled = true; };
  }, [tenant, tenantDb, schema, table, asOf]);

  useEffect(() => {
    if (!tenant || !tenantDb) return;
    let cancelled = false;
    api.listCatalogSnapshots(tenant, tenantDb)
      .then(r => { if (!cancelled) setSnaps(r); })
      .catch(() => { if (!cancelled) setSnaps([]); });
    return () => { cancelled = true; };
  }, [tenant, tenantDb]);

  const tEnc  = encodeURIComponent(tenant!);
  const tdEnc = encodeURIComponent(tenantDb!);
  const sEnc  = encodeURIComponent(schema!);

  return (
    <div>
      <Breadcrumb
        items={[
          { label: 'Catalog', to: '/catalog' },
          { label: tenant!,   to: `/catalog?tenant=${tEnc}` },
          { label: tenantDb!, to: `/catalog?tenant=${tEnc}&tenantDb=${tdEnc}` },
          { label: schema!,   to: `/catalog?tenant=${tEnc}&tenantDb=${tdEnc}&schema=${sEnc}` },
          { label: table! },
        ]}
      />

      <div style={{ margin: '12px 0', display: 'flex', alignItems: 'center', gap: 12 }}>
        <label>
          Snapshot&nbsp;
          <select
            value={asOf ?? ''}
            onChange={e => {
              const v = e.target.value;
              const next = new URLSearchParams(searchParams);
              if (v === '') { next.delete('asOf'); } else { next.set('asOf', v); }
              setSearchParams(next);
            }}
          >
            <option value="">current</option>
            {snaps.map(s => (
              <option key={s.snapshotId} value={s.snapshotId}>
                {s.snapshotId} ({new Date(s.committedAt).toLocaleString()})
              </option>
            ))}
          </select>
        </label>
        {asOf != null && (
          <span style={{ background: 'rgba(251, 191, 36, 0.15)', border: '1px solid var(--warn)',
                         color: 'var(--warn)', borderRadius: 4, padding: '2px 8px', fontSize: '0.9rem' }}>
            Viewing as of snapshot {asOf}
            {(() => {
              const s = snaps.find(x => x.snapshotId === asOf);
              return s ? ` (${new Date(s.committedAt).toLocaleString()})` : '';
            })()}
            <button
              type="button"
              style={{
                marginLeft: 8, background: 'none', border: 'none', color: 'var(--text)',
                cursor: 'pointer', padding: 0, font: 'inherit', textDecoration: 'underline',
              }}
              onClick={() => {
                const next = new URLSearchParams(searchParams);
                next.delete('asOf');
                setSearchParams(next);
              }}
            >back to current</button>
          </span>
        )}
      </div>

      <div style={{ marginBottom: 16, fontSize: '0.9rem' }}>
        <Link to={`/tenant/${tEnc}`}>Open tenant {tenant} →</Link>
        <span style={{ color: '#bbb', margin: '0 8px' }}>·</span>
        <Link to={`/nodes?tenant=${tEnc}`}>Live nodes for this tenant</Link>
      </div>

      {error && <p style={{ color: 'red' }}>Error: {error}</p>}
      {!detail && !error && <p>Loading…</p>}

      {detail && (
        <>
          <section style={{ marginBottom: 24 }}>
            <h3 style={{ marginTop: 0 }}>Summary</h3>
            <table style={{ borderCollapse: 'collapse' }}>
              <tbody>
                <tr>
                  <td style={{ paddingRight: 16, color: '#555' }}>Rows</td>
                  <td>
                    {detail.table.rowCount < 0
                      ? <em style={{ color: '#888' }}>--</em>
                      : detail.table.rowCount.toLocaleString()}
                  </td>
                </tr>
                <tr>
                  <td style={{ paddingRight: 16, color: '#555' }}>Data files</td>
                  <td>{detail.table.dataFileCount}</td>
                </tr>
                <tr>
                  <td style={{ paddingRight: 16, color: '#555' }}>Total parquet size</td>
                  <td>{fmtBytes(detail.dataFiles.reduce((s, f) => s + f.sizeBytes, 0))}</td>
                </tr>
                <tr>
                  <td style={{ paddingRight: 16, color: '#555' }}>Folder</td>
                  <td>
                    {detail.table.folder
                      ? <code style={{ fontSize: '0.9em', color: '#444' }}>{detail.table.folder}</code>
                      : <em style={{ color: '#888' }}>--</em>}
                  </td>
                </tr>
              </tbody>
            </table>
          </section>

          <section style={{ marginBottom: 24 }}>
            <h3>Columns</h3>
            {detail.columns.length === 0
              ? <em style={{ color: '#888' }}>no columns</em>
              : (
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>
                      <th align="right">#</th>
                      <th align="left">Name</th>
                      <th align="left">Type</th>
                      <th align="left">Nullable</th>
                      <th align="left">PK</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.columns.map(c => (
                      <tr key={c.ordinal} style={{ borderTop: '1px solid #eee' }}>
                        <td align="right">{c.ordinal}</td>
                        <td>{c.name}</td>
                        <td><code>{c.typeName}</code></td>
                        <td>{c.nullable ? 'yes' : 'no'}</td>
                        <td>{c.isPrimaryKey ? '✓' : ''}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
          </section>

          <section>
            <h3>Parquet files</h3>
            {detail.dataFiles.length === 0
              ? <em style={{ color: '#888' }}>no parquet files</em>
              : (
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>
                      <th align="left">Path</th>
                      <th align="right">Size</th>
                      <th align="right">Rows</th>
                      <th align="right">Snapshot</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.dataFiles.map(f => (
                      <tr key={f.path} style={{ borderTop: '1px solid #eee' }}>
                        <td><code style={{ wordBreak: 'break-all' }}>{f.path}</code></td>
                        <td align="right">{fmtBytes(f.sizeBytes)}</td>
                        <td align="right">{f.rowCount.toLocaleString()}</td>
                        <td align="right">{f.snapshotId}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
          </section>
        </>
      )}
    </div>
  );
}
