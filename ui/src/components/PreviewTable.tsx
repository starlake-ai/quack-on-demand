import type { ReactNode } from 'react';

export interface PreviewTableColumn {
  name: string;
  dataType: string;
}

/** Renders one data cell with the "null" styling shared by every preview/diff table in the
  * UI. Not exported: callers never need it directly, only through this table. */
function renderCell(v: unknown): ReactNode {
  return v === null || v === undefined
    ? <em style={{ color: '#888' }}>null</em>
    : String(v);
}

/** Bounded row/column grid shared by every "preview a bounded set of rows" view: the DuckLake
  * time-travel preview (CatalogTableDetail) and the Iceberg preview + data-diff views
  * (IcebergTableDetail). Handles the truncated notice, the empty state, the column header
  * (name + dataType), and null-cell rendering.
  *
  * `leadingHeader`/`leadingCell` let a caller prepend one extra column -- the Iceberg diff
  * view's "added"/"removed" badge -- without forking the whole table; omit both for a plain
  * preview grid. Callers own the surrounding "loading" / error / `{data && ...}` wrapper, same
  * as before this was extracted. */
export default function PreviewTable({
  columns,
  rows,
  truncated,
  emptyLabel = 'no rows',
  leadingHeader,
  leadingCell,
}: {
  columns: PreviewTableColumn[];
  rows: unknown[][];
  truncated: boolean;
  emptyLabel?: string;
  leadingHeader?: ReactNode;
  leadingCell?: (row: unknown[], index: number) => ReactNode;
}) {
  return (
    <div style={{ marginTop: 12 }}>
      {truncated && (
        <p className="subtle">
          Showing the first {rows.length} rows; the result set is truncated.
        </p>
      )}
      {rows.length === 0
        ? <em style={{ color: '#888' }}>{emptyLabel}</em>
        : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {leadingHeader !== undefined && <th align="left">{leadingHeader}</th>}
                  {columns.map(c => (
                    <th key={c.name} align="left">
                      {c.name}<br />
                      <span className="subtle" style={{ fontWeight: 'normal' }}>{c.dataType}</span>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row, i) => (
                  <tr key={i} style={{ borderTop: '1px solid #eee' }}>
                    {leadingCell && <td>{leadingCell(row, i)}</td>}
                    {row.map((v, j) => <td key={j}>{renderCell(v)}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
    </div>
  );
}
