import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { CatalogTableDetailResponse } from '../api/types';

/** Inline schema (column list) drilldown rendered as a card beneath a
  * table row in CatalogBrowser. Loads
  * `/api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}`
  * and lists ordinal / name / type / nullable / PK. Toggled open and
  * closed by clicking the table name in the parent table. */
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

  useEffect(() => {
    setDetail(null);
    setError(null);
    api.getCatalogTable(tenant, tenantDb, schema, table)
      .then(setDetail)
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }, [tenant, tenantDb, schema, table]);

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
          </>
        )
      )}
    </div>
  );
}
