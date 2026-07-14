import { Fragment, useEffect, useRef, useState, type CSSProperties } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type {
  CatalogSnapshotEntry,
  CatalogTableDetailResponse,
  CatalogTagEntry,
  DataDiffResponse,
  PreviewResponse,
  SchemaDiffResponse,
} from '../api/types';
import Breadcrumb from '../components/Breadcrumb';
import SnapshotPicker, { parseSnapshotSelector, type SnapshotSelectorValue } from '../components/SnapshotPicker';
import Tabs from '../components/Tabs';
import CatalogHistoryPanel from '../components/CatalogHistoryPanel';

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

/** Read the current selector out of the URL: ?asOf=<id> | ?asOfTag=<name> | ?asOfTs=<iso>,
 * at most one is expected to be set at a time. Encodes into SnapshotPicker's value grammar. */
function selectorFromSearchParams(sp: URLSearchParams): SnapshotSelectorValue {
  const asOf = sp.get('asOf');
  if (asOf != null && /^\d+$/.test(asOf)) return `id:${asOf}`;
  const asOfTag = sp.get('asOfTag');
  if (asOfTag) return `tag:${asOfTag}`;
  const asOfTs = sp.get('asOfTs');
  if (asOfTs) return `ts:${asOfTs}`;
  return '';
}

function writeSelectorToSearchParams(next: URLSearchParams, value: SnapshotSelectorValue) {
  next.delete('asOf');
  next.delete('asOfTag');
  next.delete('asOfTs');
  const sel = parseSnapshotSelector(value);
  if (sel.asOf != null) next.set('asOf', String(sel.asOf));
  else if (sel.asOfTag) next.set('asOfTag', sel.asOfTag);
  else if (sel.asOfTs) next.set('asOfTs', sel.asOfTs);
}

function previewErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 403) return 'your data-plane roles do not grant SELECT on this table';
    if (e.status === 410) return 'the requested snapshot has been vacuumed (snapshot_expired)';
    if (e.status === 404) return 'preview needs a running pool';
    if (e.status === 502) return `preview failed: ${e.message}`;
    return e.message;
  }
  return String(e);
}

export default function CatalogTableDetail() {
  const { tenant, tenantDb, schema, table } = useParams<{
    tenant: string; tenantDb: string; schema: string; table: string;
  }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const selector = selectorFromSearchParams(searchParams);
  const [detail, setDetail] = useState<CatalogTableDetailResponse | null>(null);
  const [error, setError]   = useState<string | null>(null);
  const [snaps, setSnaps] = useState<CatalogSnapshotEntry[]>([]);
  const [tags, setTags] = useState<CatalogTagEntry[]>([]);

  // ----- Preview (fetched when the Preview tab is activated) -----
  const [preview, setPreview] = useState<PreviewResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  // ----- Compare (schema diff between two snapshot selectors) -----
  const diffFromParam = searchParams.get('diffFrom') ?? '';
  const diffToParam = searchParams.get('diffTo') ?? '';
  const [diffFrom, setDiffFrom] = useState<SnapshotSelectorValue>(
    diffFromParam ? (/^\d+$/.test(diffFromParam) ? `id:${diffFromParam}` : `tag:${diffFromParam}`) : ''
  );
  const [diffTo, setDiffTo] = useState<SnapshotSelectorValue>(
    diffToParam ? (/^\d+$/.test(diffToParam) ? `id:${diffToParam}` : `tag:${diffToParam}`) : ''
  );
  const [diff, setDiff] = useState<SchemaDiffResponse | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffError, setDiffError] = useState<string | null>(null);

  // ----- Compare tab "Data" mode (Spec 02 row-level diff) -----
  const [compareMode, setCompareMode] = useState<'schema' | 'data'>('schema');
  const [dataDiff, setDataDiff] = useState<DataDiffResponse | null>(null);
  const [dataDiffLoading, setDataDiffLoading] = useState(false);
  const [dataDiffError, setDataDiffError] = useState<string | null>(null);
  const [dataDiffFilter, setDataDiffFilter] = useState('');
  // Monotonic request sequence: only the newest in-flight data-diff request may
  // commit its result, so out-of-order responses cannot clobber newer ones.
  const dataDiffSeq = useRef(0);

  // Which tab is showing. Tabs runs controlled off this state so the
  // selector-change effect knows whether to refetch the preview AND the
  // history panel's Compare action can switch tabs programmatically.
  const initialTab = diffFromParam && diffToParam ? 'compare' : 'columns';
  const [activeTab, setActiveTab] = useState(initialTab);

  useEffect(() => {
    if (!tenant || !tenantDb || !schema || !table) return;
    let cancelled = false;
    setDetail(null);
    setError(null);
    api.getCatalogTable(tenant, tenantDb, schema, table, parseSnapshotSelector(selector))
      .then(r => { if (!cancelled) setDetail(r); })
      .catch(e => { if (!cancelled) setError(String(e)); });
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant, tenantDb, schema, table, selector]);

  useEffect(() => {
    if (!tenant || !tenantDb) return;
    let cancelled = false;
    api.listCatalogSnapshots(tenant, tenantDb)
      .then(r => { if (!cancelled) setSnaps(r); })
      .catch(() => { if (!cancelled) setSnaps([]); });
    api.listCatalogTags(tenant, tenantDb)
      .then(r => { if (!cancelled) setTags(r); })
      .catch(() => { if (!cancelled) setTags([]); });
    return () => { cancelled = true; };
  }, [tenant, tenantDb]);

  // Reset the preview whenever the table or its selector changes; refetch
  // right away when the Preview tab is the one showing, so the visible rows
  // always match the selected snapshot.
  useEffect(() => {
    setPreview(null);
    setPreviewError(null);
    setPreviewLoading(false);
    if (activeTab === 'preview') loadPreview(true);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant, tenantDb, schema, table, selector]);

  // Same for the compare diff -- but if the URL already carries diffFrom/diffTo,
  // run the diff automatically so the deep link is directly useful.
  useEffect(() => {
    setDiff(null);
    setDiffError(null);
    setDataDiff(null);
    setDataDiffError(null);
    if (tenant && tenantDb && schema && table && diffFromParam && diffToParam) {
      loadDiff(diffFromParam, diffToParam);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant, tenantDb, schema, table]);

  function loadPreview(force = false) {
    if (!tenant || !tenantDb || !schema || !table) return;
    if (!force && previewLoading) return;
    setPreviewLoading(true);
    setPreviewError(null);
    api.previewCatalogTable(tenant, tenantDb, schema, table, parseSnapshotSelector(selector), 100)
      .then(r => setPreview(r))
      .catch(e => setPreviewError(previewErrorMessage(e)))
      .finally(() => setPreviewLoading(false));
  }

  function selectorToBound(v: SnapshotSelectorValue): string | null {
    const sel = parseSnapshotSelector(v);
    if (sel.asOf != null) return String(sel.asOf);
    if (sel.asOfTag) return sel.asOfTag;
    return null;
  }

  function loadDiff(fromRaw?: string, toRaw?: string) {
    if (!tenant || !tenantDb || !schema || !table) return;
    const from = fromRaw ?? selectorToBound(diffFrom);
    const to = toRaw ?? selectorToBound(diffTo);
    if (!from || !to) {
      setDiffError('pick a "from" and a "to" snapshot or tag (a live timestamp cannot be diffed directly)');
      return;
    }
    setDiffLoading(true);
    setDiffError(null);
    api.catalogSchemaDiff(tenant, tenantDb, schema, table, from, to)
      .then(r => {
        setDiff(r);
        const next = new URLSearchParams(searchParams);
        next.set('diffFrom', from);
        next.set('diffTo', to);
        setSearchParams(next);
      })
      .catch(e => setDiffError(e instanceof ApiError ? e.message : String(e)))
      .finally(() => setDiffLoading(false));
  }

  function loadDataDiff(opts?: { cursor?: string; changeType?: string }) {
    if (!tenant || !tenantDb || !schema || !table) return;
    const from = selectorToBound(diffFrom);
    const to = selectorToBound(diffTo);
    if (!from || !to) {
      setDataDiffError('pick a "from" and a "to" snapshot or tag (a live timestamp cannot be diffed directly)');
      return;
    }
    const rawFilter = opts?.changeType !== undefined ? opts.changeType : dataDiffFilter;
    const seq = ++dataDiffSeq.current;
    setDataDiffLoading(true);
    setDataDiffError(null);
    api.catalogDataDiff(tenant, tenantDb, schema, table, from, to, {
      cursor: opts?.cursor,
      changeType: rawFilter || undefined,
    })
      .then(r => {
        if (seq !== dataDiffSeq.current) return;
        setDataDiff(prev => (opts?.cursor && prev ? { ...r, rows: [...prev.rows, ...r.rows] } : r));
      })
      .catch(e => {
        if (seq === dataDiffSeq.current) setDataDiffError(e instanceof ApiError ? e.message : String(e));
      })
      .finally(() => {
        if (seq === dataDiffSeq.current) setDataDiffLoading(false);
      });
  }

  function viewAsOfSnapshot(snapshotId: number) {
    const next = new URLSearchParams(searchParams);
    writeSelectorToSearchParams(next, `id:${snapshotId}`);
    setSearchParams(next);
  }

  // compareFromHistory loads the diff, stamps ?diffFrom/&diffTo, and switches to the Compare
  // tab (Tabs runs controlled off activeTab). viewAsOfSnapshot sets the page-wide AS OF
  // selector, so the banner appears immediately and every tab reflects the chosen snapshot.
  function compareFromHistory(from: number, to: number) {
    setDiffFrom(`id:${from}`);
    setDiffTo(`id:${to}`);
    loadDiff(String(from), String(to));
    setActiveTab('compare');
  }

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
        <SnapshotPicker
          tenant={tenant!}
          tenantDb={tenantDb!}
          value={selector}
          onChange={v => {
            const next = new URLSearchParams(searchParams);
            writeSelectorToSearchParams(next, v);
            setSearchParams(next);
          }}
          snapshots={snaps}
          tags={tags}
        />
        {selector !== '' && (
          <span style={{ background: 'rgba(251, 191, 36, 0.15)', border: '1px solid var(--warn)',
                         color: 'var(--warn)', borderRadius: 4, padding: '2px 8px', fontSize: '0.9rem' }}>
            Viewing as of {parseSnapshotSelector(selector).asOfTs
              ? `timestamp ${parseSnapshotSelector(selector).asOfTs}`
              : `snapshot ${detail?.resolvedSnapshot ?? parseSnapshotSelector(selector).asOf ?? ''}`}
            {detail?.resolvedAt && ` (resolved at ${new Date(detail.resolvedAt).toLocaleString()})`}
            {(() => {
              const asOfId = parseSnapshotSelector(selector).asOf;
              const s = asOfId != null ? snaps.find(x => x.snapshotId === asOfId) : undefined;
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
                writeSelectorToSearchParams(next, '');
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
      {!detail && !error && <p>Loading...</p>}

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

          {/* Preview/diff state lives in this component, so the Tabs
              remount-on-switch behavior does not drop loaded results. */}
          <Tabs
            initialId={initialTab}
            activeId={activeTab}
            onSelect={id => {
              setActiveTab(id);
              if (id === 'preview') loadPreview();
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
                            <td>{c.isPrimaryKey ? 'yes' : ''}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  ),
              },
              {
                id: 'files',
                label: 'Parquet files',
                body: detail.dataFiles.length === 0
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
                  ),
              },
              {
                id: 'preview',
                label: 'Preview',
                body: (
                  <>
                    {previewLoading && <p className="subtle">Loading preview...</p>}
                    {previewError && (
                      <p style={{ color: 'red', marginTop: 8 }}>Error: {previewError}</p>
                    )}
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
                                      {row.map((v, j) => (
                                        <td key={j}>
                                          {v === null || v === undefined
                                            ? <em style={{ color: '#888' }}>null</em>
                                            : String(v)}
                                        </td>
                                      ))}
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
                    <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
                      <button
                        type="button"
                        disabled={compareMode === 'schema'}
                        onClick={() => setCompareMode('schema')}
                      >
                        Schema
                      </button>
                      <button
                        type="button"
                        disabled={compareMode === 'data'}
                        onClick={() => setCompareMode('data')}
                      >
                        Data
                      </button>
                    </div>
                    <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'center', marginBottom: 12 }}>
                      <SnapshotPicker
                        tenant={tenant!}
                        tenantDb={tenantDb!}
                        value={diffFrom}
                        onChange={setDiffFrom}
                        snapshots={snaps}
                        tags={tags}
                        label="From"
                      />
                      <SnapshotPicker
                        tenant={tenant!}
                        tenantDb={tenantDb!}
                        value={diffTo}
                        onChange={setDiffTo}
                        snapshots={snaps}
                        tags={tags}
                        label="To"
                      />
                      <button
                        type="button"
                        onClick={() => (compareMode === 'schema' ? loadDiff() : loadDataDiff())}
                        disabled={compareMode === 'schema' ? diffLoading : dataDiffLoading}
                      >
                        {(compareMode === 'schema' ? diffLoading : dataDiffLoading)
                          ? 'Comparing...'
                          : 'Compare'}
                      </button>
                    </div>
                    {compareMode === 'schema' && diffError && (
                      <p style={{ color: 'red' }}>Error: {diffError}</p>
                    )}
                    {compareMode === 'schema' && diff && (
                      <div>
                        <p className="subtle">
                          Diffing snapshot {diff.from} against snapshot {diff.to}.
                        </p>
                        {diff.added.length === 0 && diff.removed.length === 0 &&
                          diff.typeChanged.length === 0 && diff.nullabilityChanged.length === 0
                          ? <em style={{ color: '#888' }}>no schema differences</em>
                          : (
                            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                              <thead>
                                <tr>
                                  <th align="left">Change</th>
                                  <th align="left">Column</th>
                                  <th align="left">Detail</th>
                                </tr>
                              </thead>
                              <tbody>
                                {diff.added.map(c => (
                                  <tr key={`added-${c.name}`} style={{ borderTop: '1px solid #eee', background: 'rgba(34, 197, 94, 0.1)' }}>
                                    <td>added</td>
                                    <td>{c.name}</td>
                                    <td><code>{c.typeName}</code>{c.nullable ? '' : ' not null'}</td>
                                  </tr>
                                ))}
                                {diff.removed.map(c => (
                                  <tr key={`removed-${c.name}`} style={{ borderTop: '1px solid #eee', background: 'rgba(239, 68, 68, 0.1)' }}>
                                    <td>removed</td>
                                    <td>{c.name}</td>
                                    <td><code>{c.typeName}</code>{c.nullable ? '' : ' not null'}</td>
                                  </tr>
                                ))}
                                {diff.typeChanged.map(c => (
                                  <tr key={`type-${c.column}`} style={{ borderTop: '1px solid #eee', background: 'rgba(251, 191, 36, 0.1)' }}>
                                    <td>type changed</td>
                                    <td>{c.column}</td>
                                    <td><code>{c.fromType}</code> {'->'} <code>{c.toType}</code></td>
                                  </tr>
                                ))}
                                {diff.nullabilityChanged.map(c => (
                                  <tr key={`null-${c.column}`} style={{ borderTop: '1px solid #eee', background: 'rgba(251, 191, 36, 0.1)' }}>
                                    <td>nullability changed</td>
                                    <td>{c.column}</td>
                                    <td>{c.fromNullable ? 'nullable' : 'not null'} {'->'} {c.toNullable ? 'nullable' : 'not null'}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          )}
                      </div>
                    )}
                    {compareMode === 'data' && (
                      <>
                        {dataDiffError && <p style={{ color: 'red' }}>Error: {dataDiffError}</p>}
                        {dataDiffLoading && !dataDiff && <p className="subtle">Loading...</p>}
                        {dataDiff && (
                          <div>
                            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center', marginBottom: 12 }}>
                              <span style={{ background: 'rgba(34, 197, 94, 0.15)', borderRadius: 4, padding: '2px 10px', fontSize: '0.9rem' }}>
                                +{dataDiff.summary.inserted.toLocaleString()} inserted
                              </span>
                              <span style={{ background: 'rgba(239, 68, 68, 0.15)', borderRadius: 4, padding: '2px 10px', fontSize: '0.9rem' }}>
                                -{dataDiff.summary.deleted.toLocaleString()} deleted
                              </span>
                              <span style={{ background: 'rgba(251, 191, 36, 0.15)', borderRadius: 4, padding: '2px 10px', fontSize: '0.9rem' }}>
                                ~{dataDiff.summary.updated.toLocaleString()} updated
                              </span>
                              <label style={{ fontSize: '0.85rem' }}>Change type{' '}
                                <select
                                  value={dataDiffFilter}
                                  onChange={e => {
                                    setDataDiffFilter(e.target.value);
                                    loadDataDiff({ changeType: e.target.value });
                                  }}
                                >
                                  <option value="">all</option>
                                  <option value="insert">insert</option>
                                  <option value="delete">delete</option>
                                  <option value="update">update</option>
                                </select>
                              </label>
                              <span className="subtle">
                                snapshots {dataDiff.from} {'->'} {dataDiff.to}
                              </span>
                            </div>
                            {dataDiff.rows.length === 0
                              ? <em style={{ color: '#888' }}>no row changes</em>
                              : (
                                <div style={{ overflowX: 'auto' }}>
                                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                                    <thead>
                                      <tr>
                                        <th align="left">Change</th>
                                        <th align="right">Snapshot</th>
                                        {dataDiff.columns.map(c => (
                                          <th key={c.name} align="left">
                                            {c.name}<br />
                                            <span className="subtle" style={{ fontWeight: 'normal' }}>{c.dataType}</span>
                                          </th>
                                        ))}
                                      </tr>
                                    </thead>
                                    <tbody>
                                      {dataDiff.rows.map((e, i) => {
                                        const cell = (v: unknown, j: number, style?: CSSProperties) => (
                                          <td key={j} style={style}>
                                            {v === null || v === undefined
                                              ? <em style={{ color: '#888' }}>null</em>
                                              : String(v)}
                                          </td>
                                        );
                                        if (e.changeType === 'update') {
                                          return (
                                            <Fragment key={i}>
                                              <tr style={{ borderTop: '1px solid #eee', background: 'rgba(251, 191, 36, 0.1)' }}>
                                                <td rowSpan={2}>update</td>
                                                <td rowSpan={2} align="right">{e.snapshotId}</td>
                                                {(e.before ?? []).map((v, j) =>
                                                  cell(v, j, { opacity: 0.6, textDecoration: 'line-through' }))}
                                              </tr>
                                              <tr style={{ background: 'rgba(251, 191, 36, 0.1)' }}>
                                                {(e.after ?? []).map((v, j) => cell(v, j))}
                                              </tr>
                                            </Fragment>
                                          );
                                        }
                                        const bg = e.changeType === 'insert'
                                          ? 'rgba(34, 197, 94, 0.1)'
                                          : e.changeType === 'delete'
                                            ? 'rgba(239, 68, 68, 0.1)'
                                            : 'rgba(148, 163, 184, 0.1)';
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
                                </div>
                              )}
                            {dataDiff.nextCursor && !dataDiffLoading && (
                              <button
                                type="button"
                                style={{ marginTop: 12 }}
                                onClick={() => loadDataDiff({ cursor: dataDiff.nextCursor! })}
                              >
                                Load more
                              </button>
                            )}
                            {dataDiffLoading && <p className="subtle">Loading...</p>}
                          </div>
                        )}
                      </>
                    )}
                  </>
                ),
              },
              {
                id: 'history',
                label: 'History',
                body: (
                  <CatalogHistoryPanel
                    tenant={tenant!}
                    tenantDb={tenantDb!}
                    schema={schema!}
                    table={table!}
                    onViewAsOf={viewAsOfSnapshot}
                    onCompare={compareFromHistory}
                  />
                ),
              },
            ]}
          />
        </>
      )}
    </div>
  );
}
