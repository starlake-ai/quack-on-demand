import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import type { ConfigEntryView } from '../api/types';

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
