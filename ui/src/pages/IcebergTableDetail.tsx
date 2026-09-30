import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, ApiError, errorMessage } from '../api/client';
import type {
  IcebergDiffResponse,
  IcebergPreviewResponse,
  IcebergSnapshotEntry,
  IcebergTableDetailResponse,
} from '../api/types';
import Breadcrumb from '../components/Breadcrumb';
import Tabs from '../components/Tabs';

const HISTORY_PAGE = 50;

const OPERATIONS = ['append', 'overwrite', 'delete', 'replace'];

/** Server error messages are shown verbatim, except `diff_too_large` (413), which the brief
  * asks to spell out in plain words rather than the raw server message. */
function diffErrorMessage(e: unknown): string {
  if (e instanceof ApiError && e.code === 'diff_too_large') {
    return 'This table is too large to diff here; narrow the comparison with qod sql.';
  }
  return errorMessage(e);
}

function renderCell(v: unknown): React.ReactNode {
  return v === null || v === undefined
    ? <em style={{ color: '#888' }}>null</em>
    : String(v);
}

export default function IcebergTableDetail() {
  const { tenant, tenantDb, alias, schema, table } = useParams<{
    tenant: string; tenantDb: string; alias: string; schema: string; table: string;
  }>();

  const [detail, setDetail] = useState<IcebergTableDetailResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  // ----- History (also feeds the Preview "As of" select and the Compare from/to selects) -----
  const [history, setHistory] = useState<IcebergSnapshotEntry[]>([]);
  const [historyHasMore, setHistoryHasMore] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [historyOperation, setHistoryOperation] = useState('');
  const historySeq = useRef(0);

  // ----- Preview -----
  // '' = current (no asOf sent); otherwise a snapshot id string picked from history.
  const [previewAsOf, setPreviewAsOf] = useState('');
  const [preview, setPreview] = useState<IcebergPreviewResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  // ----- Compare -----
  const [diffFrom, setDiffFrom] = useState('');
  const [diffTo, setDiffTo] = useState('');
  const [diffChangeType, setDiffChangeType] = useState('');
  const [diff, setDiff] = useState<IcebergDiffResponse | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffError, setDiffError] = useState<string | null>(null);

  const [activeTab, setActiveTab] = useState('columns');

  useEffect(() => {
    if (!tenant || !tenantDb || !alias || !schema || !table) return;
    let cancelled = false;
    setDetail(null);
    setError(null);
    api.getIcebergTable(tenant, tenantDb, alias, schema, table)
      .then(r => { if (!cancelled) setDetail(r); })
      .catch(e => { if (!cancelled) setError(errorMessage(e)); });
    return () => { cancelled = true; };
  }, [tenant, tenantDb, alias, schema, table]);

  function loadHistory(reset: boolean) {
    if (!tenant || !tenantDb || !alias || !schema || !table) return;
    const seq = ++historySeq.current;
    setHistoryLoading(true);
    setHistoryError(null);
    const before = reset ? undefined : history[history.length - 1]?.snapshotId;
    api.listIcebergHistory(tenant, tenantDb, alias, schema, table, {
      limit: HISTORY_PAGE,
      before,
      operation: historyOperation || undefined,
    })
      .then(r => {
        if (seq !== historySeq.current) return;
        setHistory(prev => (reset ? r.snapshots : [...prev, ...r.snapshots]));
        setHistoryHasMore(r.hasMore);
      })
      .catch(e => { if (seq === historySeq.current) setHistoryError(errorMessage(e)); })
      .finally(() => { if (seq === historySeq.current) setHistoryLoading(false); });
  }

  // Initial load + reload on filter change (resets pagination).
  useEffect(() => {
    if (!tenant || !tenantDb || !alias || !schema || !table) return;
    loadHistory(true);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant, tenantDb, alias, schema, table, historyOperation]);

  function loadPreview(asOf: string) {
    if (!tenant || !tenantDb || !alias || !schema || !table) return;
    setPreviewLoading(true);
    setPreviewError(null);
    api.previewIceberg(tenant, tenantDb, alias, schema, table, {
      asOf: asOf || undefined,
      limit: 100,
    })
      .then(r => setPreview(r))
      .catch(e => setPreviewError(errorMessage(e)))
      .finally(() => setPreviewLoading(false));
  }

  function loadDiff(from: string, to: string, changeType?: string) {
    if (!tenant || !tenantDb || !alias || !schema || !table) return;
    if (!from || !to) {
      setDiffError('pick a "from" and a "to" snapshot.');
      return;
    }
    setDiffLoading(true);
    setDiffError(null);
    api.diffIceberg(tenant, tenantDb, alias, schema, table, from, to, {
      changeType: (changeType !== undefined ? changeType : diffChangeType) || undefined,
    })
      .then(r => setDiff(r))
      .catch(e => setDiffError(diffErrorMessage(e)))
      .finally(() => setDiffLoading(false));
  }

  /** History row action: switch to the Preview tab showing that snapshot. */
  function previewFromHistory(snapshotId: string) {
    setPreviewAsOf(snapshotId);
    setPreview(null);
    setPreviewError(null);
    setActiveTab('preview');
    loadPreview(snapshotId);
  }

  /** History row action: switch to the Compare tab, from = that snapshot, to = current. */
  function compareWithCurrentFromHistory(snapshotId: string) {
    const to = detail?.currentSnapshot ?? '';
    setDiffFrom(snapshotId);
    setDiffTo(to);
    setDiff(null);
    setDiffError(null);
    setActiveTab('compare');
    if (to) loadDiff(snapshotId, to);
  }

  const tEnc = encodeURIComponent(tenant!);
  const tdEnc = encodeURIComponent(tenantDb!);

  // Compare selects offer every loaded history id, plus the current snapshot if it isn't
  // already among them (it usually is, as the newest entry with current=true).
  const selectorOptions = (() => {
    const opts = history.map(h => ({ id: h.snapshotId, label: h.snapshotId + (h.current ? ' (current)' : '') }));
    if (detail?.currentSnapshot && !opts.some(o => o.id === detail.currentSnapshot)) {
      opts.unshift({ id: detail.currentSnapshot, label: `${detail.currentSnapshot} (current)` });
    }
    return opts;
  })();

  return (
    <div>
      <Breadcrumb
        items={[
          { label: 'Catalog', to: '/catalog' },
          { label: tenant!, to: `/catalog?tenant=${tEnc}` },
          { label: tenantDb!, to: `/catalog?tenant=${tEnc}&tenantDb=${tdEnc}` },
          { label: alias! },
          { label: schema! },
          { label: table! },
        ]}
      />

      {error && <p style={{ color: 'red' }}>Error: {error}</p>}
      {!detail && !error && <p>Loading...</p>}

      {detail && (
        <>
          <section style={{ marginBottom: 24 }}>
            <h3 style={{ marginTop: 0 }}>Summary</h3>
            <table style={{ borderCollapse: 'collapse' }}>
              <tbody>
                <tr>
                  <td style={{ paddingRight: 16, color: '#555' }}>Catalog</td>
                  <td><code>{detail.alias}</code></td>
                </tr>
                <tr>
                  <td style={{ paddingRight: 16, color: '#555' }}>Namespace</td>
                  <td><code>{detail.schema}</code></td>
                </tr>
                <tr>
                  <td style={{ paddingRight: 16, color: '#555' }}>Current snapshot</td>
                  <td>
                    {detail.currentSnapshot
                      ? <code>{detail.currentSnapshot}</code>
                      : <em style={{ color: '#888' }}>no snapshot yet</em>}
                  </td>
                </tr>
              </tbody>
            </table>
          </section>

          <Tabs
            activeId={activeTab}
            onSelect={id => {
              setActiveTab(id);
              if (id === 'preview' && !preview && !previewLoading) loadPreview(previewAsOf);
            }}
            tabs={[
              {
                id: 'columns',
                label: 'Columns',
                body: detail.columns.length === 0
                  ? <em style={{ color: '#888' }}>no columns</em>
                  : (
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                      <thead>
                        <tr>
                          <th align="right">#</th>
                          <th align="left">Name</th>
                          <th align="left">Type</th>
                          <th align="left">Nullable</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.columns.map(c => (
                          <tr key={c.ordinal} style={{ borderTop: '1px solid #eee' }}>
                            <td align="right">{c.ordinal}</td>
                            <td>{c.name}</td>
                            <td><code>{c.typeName}</code></td>
                            <td>{c.nullable ? 'yes' : 'no'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  ),
              },
              {
                id: 'files',
                label: 'Files',
                body: detail.files.length === 0
                  ? <em style={{ color: '#888' }}>no files</em>
                  : (
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                      <thead>
                        <tr>
                          <th align="left">Path</th>
                          <th align="left">Content</th>
                          <th align="left">Format</th>
                          <th align="right">Records</th>
                          <th align="right">Sequence</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.files.map(f => (
                          <tr key={f.path} style={{ borderTop: '1px solid #eee' }}>
                            <td><code style={{ wordBreak: 'break-all' }}>{f.path}</code></td>
                            <td>{f.content}</td>
                            <td><code>{f.format}</code></td>
                            <td align="right">{f.recordCount.toLocaleString()}</td>
                            <td align="right">{f.sequenceNumber}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  ),
              },
              {
                id: 'preview',
                label: 'Preview',
                body: (
                  <>
                    <div style={{ display: 'flex', gap: 12, alignItems: 'end', marginBottom: 12 }}>
                      <label style={{ fontSize: '0.85rem' }}>As of<br />
                        <select
                          value={previewAsOf}
                          onChange={e => {
                            setPreviewAsOf(e.target.value);
                            loadPreview(e.target.value);
                          }}
                        >
                          <option value="">Current</option>
                          {history.map(h => (
                            <option key={h.snapshotId} value={h.snapshotId}>{h.snapshotId}</option>
                          ))}
                        </select>
                      </label>
                    </div>
                    {previewLoading && <p className="subtle">Loading preview...</p>}
                    {previewError && <p style={{ color: 'red', marginTop: 8 }}>Error: {previewError}</p>}
                    {preview && (
                      <div style={{ marginTop: 12 }}>
                        {preview.truncated && (
                          <p className="subtle">
                            Showing the first {preview.rows.length} rows; the result set is truncated.
                          </p>
                        )}
                        {preview.rows.length === 0
                          ? <em style={{ color: '#888' }}>no rows</em>
                          : (
                            <div style={{ overflowX: 'auto' }}>
                              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                                <thead>
                                  <tr>
                                    {preview.columns.map(c => (
                                      <th key={c.name} align="left">
                                        {c.name}<br />
                                        <span className="subtle" style={{ fontWeight: 'normal' }}>{c.dataType}</span>
                                      </th>
                                    ))}
                                  </tr>
                                </thead>
                                <tbody>
                                  {preview.rows.map((row, i) => (
                                    <tr key={i} style={{ borderTop: '1px solid #eee' }}>
                                      {row.map((v, j) => <td key={j}>{renderCell(v)}</td>)}
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
                          )}
                      </div>
                    )}
                  </>
                ),
              },
              {
                id: 'compare',
                label: 'Compare',
                body: (
                  <>
                    <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'end', marginBottom: 12 }}>
                      <label style={{ fontSize: '0.85rem' }}>From<br />
                        <select value={diffFrom} onChange={e => setDiffFrom(e.target.value)}>
                          <option value="">pick a snapshot</option>
                          {selectorOptions.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
                        </select>
                      </label>
                      <label style={{ fontSize: '0.85rem' }}>To<br />
                        <select value={diffTo} onChange={e => setDiffTo(e.target.value)}>
                          <option value="">pick a snapshot</option>
                          {selectorOptions.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
                        </select>
                      </label>
                      <label style={{ fontSize: '0.85rem' }}>Change type<br />
                        <select
                          value={diffChangeType}
                          onChange={e => {
                            setDiffChangeType(e.target.value);
                            if (diff) loadDiff(diffFrom, diffTo, e.target.value);
                          }}
                        >
                          <option value="">all</option>
                          <option value="added">added</option>
                          <option value="removed">removed</option>
                        </select>
                      </label>
                      <button
                        type="button"
                        onClick={() => loadDiff(diffFrom, diffTo)}
                        disabled={diffLoading}
                      >
                        {diffLoading ? 'Comparing...' : 'Compare'}
                      </button>
                    </div>
                    {diffError && <p style={{ color: 'red' }}>Error: {diffError}</p>}
                    {diff && (
                      <div>
                        <p className="subtle">
                          Diffing snapshot {diff.from} against snapshot {diff.to}.
                        </p>
                        {diff.truncated && (
                          <p className="subtle">
                            Showing the first {diff.rows.length} rows; the result set is truncated.
                          </p>
                        )}
                        {diff.rows.length === 0
                          ? <em style={{ color: '#888' }}>no row changes</em>
                          : (
                            <div style={{ overflowX: 'auto' }}>
                              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                                <thead>
                                  <tr>
                                    <th align="left">Change</th>
                                    {diff.columns.map(c => (
                                      <th key={c.name} align="left">
                                        {c.name}<br />
                                        <span className="subtle" style={{ fontWeight: 'normal' }}>{c.dataType}</span>
                                      </th>
                                    ))}
                                  </tr>
                                </thead>
                                <tbody>
                                  {diff.rows.map((r, i) => (
                                    <tr key={i} style={{ borderTop: '1px solid #eee' }}>
                                      <td>
                                        <span className={'badge ' + (r.change === 'added' ? 'good' : 'bad')}>
                                          {r.change}
                                        </span>
                                      </td>
                                      {r.values.map((v, j) => <td key={j}>{renderCell(v)}</td>)}
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
                          )}
                      </div>
                    )}
                  </>
                ),
              },
              {
                id: 'history',
                label: 'History',
                body: (
                  <div>
                    <div style={{ display: 'flex', gap: 12, alignItems: 'end', marginBottom: 12 }}>
                      <label style={{ fontSize: '0.85rem' }}>Operation<br />
                        <select value={historyOperation} onChange={e => setHistoryOperation(e.target.value)}>
                          <option value="">all</option>
                          {OPERATIONS.map(o => <option key={o} value={o}>{o}</option>)}
                        </select>
                      </label>
                    </div>
                    {historyError && <p style={{ color: 'red' }}>Error: {historyError}</p>}
                    {history.length === 0 && !historyLoading && !historyError && (
                      <em style={{ color: '#888' }}>no snapshots</em>
                    )}
                    {history.length > 0 && (
                      <div style={{ overflowX: 'auto' }}>
                        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                          <thead>
                            <tr>
                              <th align="left">Snapshot id</th>
                              <th align="left">Time</th>
                              <th align="left">Operation</th>
                              <th align="right">Rows added</th>
                              <th align="right">Rows deleted</th>
                              <th align="right">Files added</th>
                              <th align="right">Position deletes</th>
                              <th align="left"></th>
                            </tr>
                          </thead>
                          <tbody>
                            {history.map(h => (
                              <tr key={h.snapshotId} style={{ borderTop: '1px solid #eee' }}>
                                <td><code>{h.snapshotId}</code>{h.current ? ' (current)' : ''}</td>
                                <td>{new Date(h.committedAt).toLocaleString()}</td>
                                <td>{h.operation ?? <em style={{ color: '#888' }}>--</em>}</td>
                                <td align="right">{h.addedRecords?.toLocaleString() ?? '--'}</td>
                                <td align="right">{h.deletedRecords?.toLocaleString() ?? '--'}</td>
                                <td align="right">{h.addedDataFiles?.toLocaleString() ?? '--'}</td>
                                <td align="right">{h.addedPositionDeletes?.toLocaleString() ?? '--'}</td>
                                <td style={{ whiteSpace: 'nowrap' }}>
                                  <button type="button" onClick={() => previewFromHistory(h.snapshotId)}>
                                    Preview
                                  </button>
                                  {' '}
                                  <button type="button" onClick={() => compareWithCurrentFromHistory(h.snapshotId)}>
                                    Compare with current
                                  </button>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                    <div style={{ marginTop: 12 }}>
                      {historyLoading && <p className="subtle">Loading...</p>}
                      {!historyLoading && historyHasMore && (
                        <button type="button" onClick={() => loadHistory(false)}>Load more</button>
                      )}
                    </div>
                  </div>
                ),
              },
            ]}
          />
        </>
      )}
    </div>
  );
}
