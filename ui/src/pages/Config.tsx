import { useEffect, useMemo, useRef, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { ConfigEntryView, ManifestImportSummary } from '../api/types';

/** Returns everything before the last `.` in a HOCON path - the natural
 * section grouping for rows ordered by path. */
function sectionOf(path: string): string {
  const i = path.lastIndexOf('.');
  return i < 0 ? path : path.slice(0, i);
}

/** Admin Config page: resolved values from application.conf, paired with
 * the env var that overrides each one. Sensitive values are masked
 * server-side (rendered as "(set)" / "(unset)"). */
export default function Config() {
  const [entries, setEntries] = useState<ConfigEntryView[]>([]);
  const [filter,  setFilter]  = useState<string>('');
  const [err,     setErr]     = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(true);

  const [importResult, setImportResult] = useState<ManifestImportSummary | null>(null);
  const [importError,  setImportError]  = useState<string | null>(null);
  // Staged file: chosen via drop or file picker, applied on explicit click.
  const [stagedFile,  setStagedFile]  = useState<File | null>(null);
  const [stagedYaml,  setStagedYaml]  = useState<string | null>(null);
  const [dragOver,    setDragOver]    = useState(false);
  const [applying,    setApplying]    = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  async function onExportClick() {
    try {
      const yaml = await api.exportManifest();
      const blob = new Blob([yaml], { type: 'application/yaml' });
      const url  = URL.createObjectURL(blob);
      const a    = document.createElement('a');
      a.href = url;
      a.download = `qod-manifest-${new Date().toISOString().slice(0,19).replace(/:/g,'-')}.yaml`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setImportError(String(e));
    }
  }

  async function stageFile(file: File) {
    setImportError(null);
    setImportResult(null);
    try {
      const yaml = await file.text();
      setStagedFile(file);
      setStagedYaml(yaml);
    } catch (e) {
      setImportError(`could not read ${file.name}: ${String(e)}`);
    }
  }

  function clearStaged() {
    setStagedFile(null);
    setStagedYaml(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  }

  async function applyStaged() {
    if (!stagedYaml) return;
    setApplying(true);
    setImportError(null);
    setImportResult(null);
    try {
      const summary = await api.importManifest(stagedYaml);
      setImportResult(summary);
      clearStaged();
    } catch (e) {
      setImportError(errorMessage(e));
    } finally {
      setApplying(false);
    }
  }

  function onUploadChange(ev: React.ChangeEvent<HTMLInputElement>) {
    const file = ev.target.files?.[0];
    if (!file) return;
    void stageFile(file);
  }

  function onDrop(ev: React.DragEvent<HTMLDivElement>) {
    ev.preventDefault();
    setDragOver(false);
    const file = ev.dataTransfer.files?.[0];
    if (file) void stageFile(file);
  }

  useEffect(() => {
    api.serverConfig()
      .then(r => { setEntries(r.entries); setLoading(false); })
      .catch(e => { setErr(String(e)); setLoading(false); });
  }, []);

  // Filter rows first, THEN derive section headers from what survives -
  // so a filter that matches only entries inside one section also hides
  // the headers for the empty sections.
  const visible = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return entries;
    return entries.filter(e =>
      e.path.toLowerCase().includes(q) ||
      e.envVar.toLowerCase().includes(q) ||
      e.description.toLowerCase().includes(q)
    );
  }, [entries, filter]);

  return (
    <>
      <h1>Configuration</h1>
      <p style={{ color: 'var(--text-mute)', maxWidth: 880 }}>
        Resolved values from <code>application.conf</code> (post env-var
        substitution). To change a value, set the listed environment variable
        and restart the manager. Sensitive values are masked
        as <code>(set)</code> / <code>(unset)</code>.
      </p>

      <div className="card">
        <input
          type="text"
          placeholder="Filter by path, env var, or description"
          value={filter}
          onChange={e => setFilter(e.target.value)}
          style={{ maxWidth: 480, fontFamily: 'var(--mono)' }}
        />

        {loading && <p>Loading…</p>}
        {err && <p style={{ color: 'var(--bad)' }}>{err}</p>}

        {!loading && !err && (
          <table className="config-table" style={{ marginTop: '1rem' }}>
            <thead>
              <tr>
                <th style={{ width: '28%' }}>Key</th>
                <th style={{ width: '22%' }}>Value</th>
                <th style={{ width: '20%' }}>Env var</th>
                <th>Description</th>
              </tr>
            </thead>
            <tbody>
              {renderRows(visible)}
              {visible.length === 0 && (
                <tr><td colSpan={4} style={{ color: 'var(--text-mute)' }}>No matches.</td></tr>
              )}
            </tbody>
          </table>
        )}
      </div>
      <div className="card" style={{ marginTop: '1rem' }}>
        <h2 style={{ marginTop: 0 }}>Manifest</h2>
        <p style={{ color: 'var(--text-mute)', maxWidth: 880 }}>
          The full control-plane state (tenants, tenant-dbs, federated sources,
          pools, users, roles, groups, pool grants) round-trips through a single
          YAML file. User passwords are never exported; existing hashes are
          preserved on re-import. Federation secret values are redacted on
          export and reused from the matching row on import.
        </p>

        <div className="manifest-grid">
          <section className="manifest-pane">
            <h3 className="manifest-pane-title">Export</h3>
            <p style={{ color: 'var(--text-mute)', marginTop: 0 }}>
              Download a snapshot of this manager's state as YAML.
            </p>
            <button onClick={onExportClick}>Download YAML</button>
          </section>

          <section className="manifest-pane">
            <h3 className="manifest-pane-title">Import</h3>
            <p style={{ color: 'var(--text-mute)', marginTop: 0 }}>
              Drop a YAML file below or click to browse. Review the file, then
              apply when ready. Resources absent from the file are left alone.
            </p>

            {!stagedFile && (
              <div
                className={`manifest-dropzone${dragOver ? ' is-over' : ''}`}
                onDragEnter={e => { e.preventDefault(); setDragOver(true); }}
                onDragOver ={e => { e.preventDefault(); setDragOver(true); }}
                onDragLeave={() => setDragOver(false)}
                onDrop     ={onDrop}
                onClick    ={() => fileInputRef.current?.click()}
                role="button"
                tabIndex={0}
                onKeyDown={e => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    fileInputRef.current?.click();
                  }
                }}
              >
                <div className="manifest-dropzone-headline">Drop a .yaml file here</div>
                <div className="manifest-dropzone-sub">or click to browse</div>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".yaml,.yml,application/yaml"
                  hidden
                  onChange={onUploadChange}
                />
              </div>
            )}

            {stagedFile && (
              <div className="manifest-staged">
                <div className="manifest-staged-info">
                  <div className="manifest-staged-name">{stagedFile.name}</div>
                  <div className="manifest-staged-meta">
                    {(stagedFile.size / 1024).toFixed(1)} kB
                    {stagedYaml ? ` · ${stagedYaml.split('\n').length} lines` : ''}
                  </div>
                </div>
                <div className="row" style={{ gap: '.5rem' }}>
                  <button onClick={() => void applyStaged()} disabled={applying}>
                    {applying ? 'Applying…' : 'Apply'}
                  </button>
                  <button type="button" className="cancel-button" onClick={clearStaged} disabled={applying}>
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </section>
        </div>

        {importResult && (
          <div className="manifest-summary">
            <div className="manifest-summary-title">Imported</div>
            <div className="manifest-summary-grid">
              <ManifestStat label="Tenants"     value={importResult.tenants} />
              <ManifestStat label="Tenant-DBs"  value={importResult.tenantDbs} />
              <ManifestStat label="Pools"       value={importResult.pools} />
              <ManifestStat label="Roles"       value={importResult.roles} />
              <ManifestStat label="Groups"      value={importResult.groups} />
              <ManifestStat label="Users"       value={importResult.users} />
            </div>
          </div>
        )}
        {importError && (
          <div className="manifest-error">{importError}</div>
        )}
      </div>
    </>
  );
}

function ManifestStat({ label, value }: { label: string; value: number }) {
  return (
    <div className="manifest-stat">
      <div className="manifest-stat-value">{value}</div>
      <div className="manifest-stat-label">{label}</div>
    </div>
  );
}

function renderRows(rows: ConfigEntryView[]): JSX.Element[] {
  const out: JSX.Element[] = [];
  let currentSection = '';
  rows.forEach(e => {
    const s = sectionOf(e.path);
    if (s !== currentSection) {
      currentSection = s;
      out.push(
        <tr key={`__section_${s}`} className="config-section">
          <td colSpan={4}>{s}</td>
        </tr>
      );
    }
    const leaf = e.path.slice(s.length + 1);
    out.push(
      <tr key={e.path}>
        <td><code>{leaf}</code></td>
        <td>
          <code className={e.sensitive ? 'config-value sensitive' : 'config-value'}>
            {e.value}
          </code>
        </td>
        <td><code>{e.envVar}</code></td>
        <td>{e.description}</td>
      </tr>
    );
  });
  return out;
}
