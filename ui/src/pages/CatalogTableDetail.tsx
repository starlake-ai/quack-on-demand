import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { CatalogTableDetailResponse } from '../api/types';
import Breadcrumb from '../components/Breadcrumb';

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export default function CatalogTableDetail() {
  const { tenant, schema, table } = useParams<{
    tenant: string; schema: string; table: string;
  }>();
  const [detail, setDetail] = useState<CatalogTableDetailResponse | null>(null);
  const [error, setError]   = useState<string | null>(null);

  useEffect(() => {
    if (!tenant || !schema || !table) return;
    api.getCatalogTable(tenant, schema, table)
      .then(setDetail)
      .catch(e => setError(String(e)));
  }, [tenant, schema, table]);

  if (error)  return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (!detail) return <p>Loading…</p>;

  const totalBytes = detail.dataFiles.reduce((s, f) => s + f.sizeBytes, 0);

  const tEnc = encodeURIComponent(tenant!);
  const sEnc = encodeURIComponent(schema!);
  return (
    <div>
      <Breadcrumb
        items={[
          { label: 'Catalog', to: '/catalog' },
          { label: tenant!,   to: `/catalog?tenant=${tEnc}` },
          { label: schema!,   to: `/catalog?tenant=${tEnc}&schema=${sEnc}` },
          { label: table! },
        ]}
      />

      <div style={{ marginBottom: 16, fontSize: '0.9rem' }}>
        <Link to={`/tenant/${tEnc}`}>Open tenant {tenant} →</Link>
        <span style={{ color: '#bbb', margin: '0 8px' }}>·</span>
        <Link to={`/nodes?tenant=${tEnc}`}>Live nodes for this tenant</Link>
      </div>

      <section style={{ marginBottom: 24 }}>
        <h3 style={{ marginTop: 0 }}>Summary</h3>
        <table style={{ borderCollapse: 'collapse' }}>
          <tbody>
            <tr>
              <td style={{ paddingRight: 16, color: '#555' }}>Rows</td>
              <td>
                {detail.table.rowCount < 0
                  ? <em style={{ color: '#888' }}>—</em>
                  : detail.table.rowCount.toLocaleString()}
              </td>
            </tr>
            <tr>
              <td style={{ paddingRight: 16, color: '#555' }}>Data files</td>
              <td>{detail.table.dataFileCount}</td>
            </tr>
            <tr>
              <td style={{ paddingRight: 16, color: '#555' }}>Total parquet size</td>
              <td>{fmtBytes(totalBytes)}</td>
            </tr>
            <tr>
              <td style={{ paddingRight: 16, color: '#555' }}>Folder</td>
              <td>
                {detail.table.folder
                  ? <code style={{ fontSize: '0.9em', color: '#444' }}>{detail.table.folder}</code>
                  : <em style={{ color: '#888' }}>—</em>}
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
    </div>
  );
}