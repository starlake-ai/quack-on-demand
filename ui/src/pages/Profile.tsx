import { FormEvent, useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { api, ApiError, errorMessage } from '../api/client';
import type { UsageDayEntry, StatementHistoryEntry } from '../api/types';

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
