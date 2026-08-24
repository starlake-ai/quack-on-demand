import { FormEvent, useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { api, ApiError, errorMessage } from '../api/client';
import type { UsageDayEntry, StatementHistoryEntry, PatEntry } from '../api/types';

function shortTs(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString();
  } catch { return iso; }
}

// The profile usage endpoint groups by user only (never by pool), so it
// normally returns at most one group for a tenant-scoped session - but sum
// across whatever comes back, keyed by day, in case more than one shows up.
function mergeDays(days: UsageDayEntry[][]): UsageDayEntry[] {
  const byDay = new Map<string, UsageDayEntry>();
  for (const group of days) {
    for (const d of group) {
      const cur = byDay.get(d.day);
      if (cur) {
        cur.statements += d.statements;
        cur.errors += d.errors;
        cur.engineMs += d.engineMs;
      } else {
        byDay.set(d.day, { ...d });
      }
    }
  }
  return [...byDay.values()].sort((a, b) => b.day.localeCompare(a.day));
}

export default function Profile() {
  const { username, tenant, role, superuser } = useAuth();

  // ---- change password ----
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [pwErr, setPwErr] = useState('');
  const [pwNotice, setPwNotice] = useState('');
  const [pwBusy, setPwBusy] = useState(false);

  async function submitChangePassword(e: FormEvent) {
    e.preventDefault();
    setPwErr('');
    setPwNotice('');
    if (newPassword !== confirmPassword) {
      setPwErr('New password and confirmation do not match.');
      return;
    }
    setPwBusy(true);
    try {
      await api.changePassword({
        tenant: tenant ?? null,
        username: username ?? '',
        currentPassword,
        newPassword,
      });
      setPwNotice('Password changed. Your session stays signed in.');
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (e) {
      setPwErr(errorMessage(e));
    } finally {
      setPwBusy(false);
    }
  }

  // ---- personal access tokens ----
  const [pats, setPats] = useState<PatEntry[]>([]);
  const [patName, setPatName] = useState('');
  const [patExpiry, setPatExpiry] = useState('');
  const [patErr, setPatErr] = useState('');
  const [patBusy, setPatBusy] = useState(false);
  const [mintedName, setMintedName] = useState('');
  const [mintedToken, setMintedToken] = useState('');
  const [copied, setCopied] = useState(false);

  async function loadPats() {
    try {
      const resp = await api.listPats();
      setPats(resp.tokens);
    } catch (e) {
      setPatErr(errorMessage(e));
    }
  }

  useEffect(() => { loadPats(); }, []);

  async function submitCreatePat(e: FormEvent) {
    e.preventDefault();
    setPatErr('');
    setMintedToken('');
    setCopied(false);
    setPatBusy(true);
    try {
      const resp = await api.createPat({
        name: patName.trim(),
        // datetime-local yields a zone-less local timestamp; send it as ISO UTC.
        expiresAt: patExpiry ? new Date(patExpiry).toISOString() : null,
      });
      setMintedName(resp.name);
      setMintedToken(resp.token);
      setPatName('');
      setPatExpiry('');
      await loadPats();
    } catch (e) {
      setPatErr(errorMessage(e));
    } finally {
      setPatBusy(false);
    }
  }

  async function onRevokePat(p: PatEntry) {
    if (!window.confirm(`Revoke token "${p.name}"? Clients using it stop working immediately.`)) {
      return;
    }
    setPatErr('');
    try {
      await api.revokePat(p.id);
      await loadPats();
    } catch (e) {
      setPatErr(errorMessage(e));
    }
  }

  async function onDeletePat(p: PatEntry) {
    if (!window.confirm(`Delete token "${p.name}" from the list? This cannot be undone.`)) {
      return;
    }
    setPatErr('');
    try {
      await api.deletePat(p.id);
      await loadPats();
    } catch (e) {
      setPatErr(errorMessage(e));
    }
  }

  function patStatus(p: PatEntry): 'active' | 'revoked' | 'expired' {
    if (p.revoked) return 'revoked';
    if (p.expiresAt && new Date(p.expiresAt) < new Date()) return 'expired';
    return 'active';
  }

  async function copyMinted() {
    try {
      await navigator.clipboard.writeText(mintedToken);
      setCopied(true);
    } catch {
      // Clipboard can be unavailable (http origin); the token stays selectable.
    }
  }

  // ---- usage + statement stats ----
  const [days, setDays] = useState<UsageDayEntry[]>([]);
  const [statements, setStatements] = useState<StatementHistoryEntry[]>([]);
  const [statsErr, setStatsErr] = useState('');
  const [statsLoading, setStatsLoading] = useState(true);

  useEffect(() => {
    setStatsLoading(true);
    setStatsErr('');
    Promise.all([api.profileUsage(30), api.profileStatements(50)])
      .then(([usage, stmts]) => {
        setDays(mergeDays(usage.groups.map(g => g.days)));
        setStatements(stmts.statements);
      })
      .catch(e => {
        // A no_session_identity 400 shouldn't normally happen on this page
        // (it's only reachable with a live session), but surface it plainly
        // rather than pretending the stats loaded empty.
        const msg = e instanceof ApiError ? e.message : errorMessage(e);
        setStatsErr(msg);
      })
      .finally(() => setStatsLoading(false));
  }, []);

  return (
    <>
      <h2>Profile</h2>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-title">Account</div>
        <table>
          <tbody>
            <tr>
              <td className="muted">Username</td>
              <td>{username}</td>
            </tr>
            <tr>
              <td className="muted">Tenant</td>
              <td>{superuser ? '(superuser, all tenants)' : tenant ?? '-'}</td>
            </tr>
            <tr>
              <td className="muted">Role</td>
              <td>{role}</td>
            </tr>
          </tbody>
        </table>
        <p className="subtle" style={{ marginTop: 8, marginBottom: 0 }}>
          Username, tenant and role are managed by your administrator and cannot be changed here.
        </p>
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-title">Change password</div>
        <form onSubmit={submitChangePassword} style={{ maxWidth: 360 }}>
          {pwErr && <div className="login-err">{pwErr}</div>}
          {pwNotice && <div className="login-notice">{pwNotice}</div>}
          <label>
            Current password
            <input
              type="password"
              value={currentPassword}
              onChange={e => setCurrentPassword(e.target.value)}
              autoComplete="current-password"
            />
          </label>
          <label>
            New password
            <input
              type="password"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              autoComplete="new-password"
            />
          </label>
          <label>
            Confirm new password
            <input
              type="password"
              value={confirmPassword}
              onChange={e => setConfirmPassword(e.target.value)}
              autoComplete="new-password"
            />
          </label>
          <button
            type="submit"
            disabled={pwBusy || !currentPassword || !newPassword || !confirmPassword}
          >
            {pwBusy ? 'Updating…' : 'Change password'}
          </button>
        </form>
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-title">Personal access tokens</div>
        <p className="subtle" style={{ marginTop: 0 }}>
          Long-lived credentials for agents and scripts (MCP, CLI). A token acts with exactly your
          permissions and can be revoked here at any time.
        </p>
        {patErr && <div className="login-err">{patErr}</div>}
        {mintedToken && (
          <div className="login-notice" style={{ marginBottom: 12 }}>
            <div>
              Token <strong>{mintedName}</strong> created. This token is shown only once.
            </div>
            <code
              style={{
                display: 'block',
                margin: '8px 0',
                padding: '6px 8px',
                wordBreak: 'break-all',
                userSelect: 'all',
              }}
            >
              {mintedToken}
            </code>
            <button type="button" onClick={copyMinted}>
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>
        )}
        <form onSubmit={submitCreatePat} style={{ maxWidth: 360 }}>
          <label>
            Name
            <input
              value={patName}
              onChange={e => setPatName(e.target.value)}
              placeholder="claude-code"
            />
          </label>
          <label>
            Expires (optional)
            <input
              type="datetime-local"
              value={patExpiry}
              onChange={e => setPatExpiry(e.target.value)}
            />
          </label>
          <button type="submit" disabled={patBusy || !patName.trim()}>
            {patBusy ? 'Creating…' : 'Create token'}
          </button>
        </form>
        {pats.length > 0 && (
          <table style={{ marginTop: 12 }}>
            <thead>
              <tr>
                <th>Name</th>
                <th>Created</th>
                <th>Expires</th>
                <th>Last used</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {pats.map(p => {
                const status = patStatus(p);
                return (
                  <tr key={p.id}>
                    <td>{p.name}</td>
                    <td className="subtle">{shortTs(p.createdAt)}</td>
                    <td className="subtle">{p.expiresAt ? shortTs(p.expiresAt) : '-'}</td>
                    <td className="subtle">{p.lastUsedAt ? shortTs(p.lastUsedAt) : 'never'}</td>
                    <td>
                      <span className={`badge ${status === 'active' ? 'good' : 'bad'}`}>
                        {status}
                      </span>
                    </td>
                    <td>
                      {status === 'active' ? (
                        <button className="danger" type="button" onClick={() => onRevokePat(p)}>
                          Revoke
                        </button>
                      ) : (
                        <button className="danger" type="button" onClick={() => onDeletePat(p)}>
                          Delete
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-title">Usage (last 30 days)</div>
        {statsErr && <p className="login-err">{statsErr}</p>}
        {!statsErr && days.length === 0 && !statsLoading && (
          <p className="muted">No statements recorded in the last 30 days.</p>
        )}
        {days.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Day</th>
                <th>Statements</th>
                <th>Errors</th>
              </tr>
            </thead>
            <tbody>
              {days.map(d => (
                <tr key={d.day}>
                  <td>{d.day.slice(0, 10)}</td>
                  <td>{d.statements}</td>
                  <td>{d.errors}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <div className="card-title">Recent statements</div>
        {!statsErr && statements.length === 0 && !statsLoading && (
          <p className="muted">No statement history yet.</p>
        )}
        {statements.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Pool</th>
                <th style={{ textAlign: 'right' }}>Duration</th>
                <th>SQL</th>
              </tr>
            </thead>
            <tbody>
              {statements.map((s, i) => (
                <tr key={`${s.ts}-${i}`}>
                  <td className="subtle"><code title={s.ts}>{shortTs(s.ts)}</code></td>
                  <td>{s.pool}</td>
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{s.durationMs} ms</td>
                  <td>
                    <code
                      title={s.sql}
                      style={{
                        display: 'block',
                        maxWidth: 500,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {s.sql.trim()}
                    </code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="subtle" style={{ textAlign: 'right', marginTop: 8, marginBottom: 0 }}>
          Newest first, up to 50 shown - the manager keeps a rolling window of your most recent
          activity, not a full history.
        </p>
      </div>
    </>
  );
}
