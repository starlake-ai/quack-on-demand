import { useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { CatalogTableDetailResponse, PreviewResponse } from '../api/types';
import PreviewTable from './PreviewTable';

/** Inline schema (column list) drilldown rendered as a card beneath a
  * table row in CatalogBrowser. Loads
  * `/api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}`
  * and lists ordinal / name / type / nullable / PK, with an on-demand
  * preview of the first rows (the only preview entry point for a
  * duckdb-file / memory database, which has no snapshots to link from).
  * Toggled open and closed by clicking the table name in the parent table. */
export default function TableSchemaCard({
  tenant, tenantDb, schema, table,
}: {
  tenant:   string;
  tenantDb: string;
  schema:   string;
  table:    string;
}) {
  const [detail, setDetail] = useState<CatalogTableDetailResponse | null>(null);
  const [error,  setError]  = useState<string | null>(null);
  const [preview, setPreview] = useState<PreviewResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  useEffect(() => {
    setDetail(null);
    setError(null);
    setPreview(null);
    setPreviewError(null);
    api.getCatalogTable(tenant, tenantDb, schema, table)
      .then(setDetail)
      .catch(e => setError(errorMessage(e)));
  }, [tenant, tenantDb, schema, table]);

  function loadPreview() {
    setPreviewLoading(true);
    setPreviewError(null);
    api.previewCatalogTable(tenant, tenantDb, schema, table, undefined, 100)
      .then(setPreview)
      .catch(e => setPreviewError(errorMessage(e)))
      .finally(() => setPreviewLoading(false));
  }

  return (
    <div className="effective-perms-card">
      {error && <div className="login-err">Error: {error}</div>}
      {!detail && !error && <div className="loading">Loading…</div>}
      {detail && (
        detail.columns.length === 0 ? (
          <div className="empty">(no columns)</div>
        ) : (
          <>
            <h4>Columns ({detail.columns.length})</h4>
            <table>
              <thead>
                <tr>
                  <th align="right">#</th>
                  <th>Name</th>
                  <th>Type</th>
                  <th>Nullable</th>
                  <th>PK</th>
                </tr>
              </thead>
              <tbody>
                {detail.columns.map(c => (
                  <tr key={c.ordinal}>
                    <td align="right">{c.ordinal}</td>
                    <td><code>{c.name}</code></td>
                    <td><code>{c.typeName}</code></td>
                    <td className="subtle">{c.nullable ? 'yes' : 'no'}</td>
                    <td className="subtle">{c.isPrimaryKey ? '✓' : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div style={{ marginTop: 8 }}>
              <button type="button" disabled={previewLoading} onClick={loadPreview}>
                {previewLoading ? 'Loading rows...' : preview ? 'Refresh preview' : 'Preview rows'}
              </button>
            </div>
            {previewError && <div className="login-err">Preview error: {previewError}</div>}
            {preview && (
              <PreviewTable
                columns={preview.columns}
                rows={preview.rows}
                truncated={preview.truncated}
              />
            )}
          </>
        )
      )}
    </div>
  );
}
