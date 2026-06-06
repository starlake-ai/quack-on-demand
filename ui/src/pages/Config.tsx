import { useEffect, useMemo, useState } from 'react';
import { api, ApiError } from '../api/client';
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

  async function onUploadChange(ev: React.ChangeEvent<HTMLInputElement>) {
    const file = ev.target.files?.[0];
    if (!file) return;
    if (!window.confirm(
      `Apply ${file.name} against this manager?\n\n` +
      `This upserts every resource in the file. Resources not in the file are left alone.`)) {
      ev.target.value = '';
      return;
    }
    setImportError(null);
    setImportResult(null);
    try {
      const yaml = await file.text();
      const summary = await api.importManifest(yaml);
      setImportResult(summary);
    } catch (e) {
      setImportError(e instanceof ApiError ? e.message : String(e));
    } finally {
      ev.target.value = '';
    }
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
          Export the manager's control-plane state (tenants, pools, users, roles, groups,
          pool grants) to a YAML file, or upload one to apply against this manager.
          User passwords are never included in the export; existing hashes are preserved
          on re-import.
        </p>

        <div className="row" style={{ gap: '.75rem', flexWrap: 'wrap' }}>
          <button onClick={onExportClick}>Download YAML</button>
          <label className="secondary" style={{ display: 'inline-block', cursor: 'pointer',
                                                 padding: '.55rem 1.1rem', borderRadius: 6,
                                                 border: '1px solid var(--border)' }}>
            Upload YAML…
            <input type="file" accept=".yaml,.yml,application/yaml" hidden
                   onChange={onUploadChange} />
          </label>
        </div>

        {importResult && (
          <p style={{ marginTop: '1rem', color: 'var(--good)' }}>
            Imported: {importResult.tenants} tenants, {importResult.tenantDbs} tenant-dbs,
            {' '}{importResult.pools} pools, {importResult.roles} roles,
            {' '}{importResult.groups} groups, {importResult.users} users.
          </p>
        )}
        {importError && (
          <p style={{ marginTop: '1rem', color: 'var(--bad)' }}>{importError}</p>
        )}
      </div>
    </>
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
