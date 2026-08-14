import { FormEvent, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { ApiError, api, errorMessage } from '../api/client';

export default function Login() {
  const { login } = useAuth();
  const [username, setUsername] = useState('admin@localhost.local');
  const [password, setPassword] = useState('');
  const [tenant,   setTenant]   = useState('');
  const [err, setErr]           = useState<string | null>(null);
  // Informational (non-error) notice on the login form, e.g. after a
  // password change whose automatic re-login attempt failed.
  const [notice, setNotice]     = useState<string | null>(null);
  const [busy, setBusy]         = useState(false);

  // Forced password-change pivot: a login attempt against an account
  // flagged mustChangePassword comes back 401 password_change_required
  // instead of a session. `password` still holds the value the user typed,
  // which doubles as currentPassword for the change-password call below.
  const [phase, setPhase]                 = useState<'login' | 'change'>('login');
  const [newPassword, setNewPassword]     = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    setNotice(null);
    setBusy(true);
    try {
      await login(username, password, tenant);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'password_change_required') {
        setErr(null);
        setPhase('change');
      } else {
        setErr(errorMessage(e));
      }
    } finally {
      setBusy(false);
    }
  }

  function backToLogin() {
    setPhase('login');
    setNewPassword('');
    setConfirmPassword('');
    setErr(null);
  }

  async function submitChange(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    if (newPassword !== confirmPassword) {
      setErr('New password and confirmation do not match.');
      return;
    }
    setBusy(true);
    try {
      await api.changePassword({
        tenant: tenant || null,
        username,
        currentPassword: password,
        newPassword,
      });
    } catch (e) {
      // The change itself failed (wrong current password, policy rejection,
      // etc.) - stay on the change form and show the error, unchanged from
      // today's behavior.
      setErr(errorMessage(e));
      setBusy(false);
      return;
    }
    try {
      await login(username, newPassword, tenant);
    } catch (e) {
      // The password WAS changed; only the automatic re-login failed (e.g. a
      // transient blip). Staying on the change form here would dead-end the
      // user against a stale `password` field. Drop back to the login form
      // instead, with an informational notice - not an error, since nothing
      // about the change failed.
      backToLogin();
      setPassword('');
      setNotice('Password changed - sign in with your new password.');
      setBusy(false);
      return;
    }
    setBusy(false);
  }

  if (phase === 'change') {
    return (
      <div className="login-shell">
        <form className="login-card" onSubmit={submitChange}>
          <div className="login-brand">
            <img src="/ui/mark-dark.svg" alt="" className="login-logo" />
            <h1>Quack on Demand</h1>
            <p className="login-sub">Password change required</p>
          </div>
          {err && <div className="login-err">{err}</div>}
          <label>
            Username
            <input value={username} disabled autoComplete="username" />
          </label>
          <label>
            New password
            <input
              type="password"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              autoComplete="new-password"
              autoFocus
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
          <button type="submit" disabled={busy || !newPassword || !confirmPassword}>
            {busy ? 'Updating…' : 'Change password'}
          </button>
          <p className="login-hint">
            <a href="#" onClick={e => { e.preventDefault(); backToLogin(); }}>Back to login</a>
          </p>
        </form>
      </div>
    );
  }

  return (
    <div className="login-shell">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand">
          <img src="/ui/mark-dark.svg" alt="" className="login-logo" />
          <h1>Quack on Demand</h1>
          <p className="login-sub">Sign in</p>
        </div>
        {notice && <div className="login-notice">{notice}</div>}
        {err && <div className="login-err">{err}</div>}
        <label>
          Username
          <input
            value={username}
            onChange={e => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        <label>
          Tenant
          <input
            value={tenant}
            onChange={e => setTenant(e.target.value)}
            placeholder="leave blank for superuser"
            autoComplete="off"
          />
        </label>
        <button type="submit" disabled={busy || !username || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="login-hint">
          Superusers leave Tenant blank. Tenant admins and other tenant users
          enter their tenant name or id (both shown on the Tenants page, e.g.{' '}
          <code>acme</code> or <code>t-02d0e86e</code>).
        </p>
      </form>
    </div>
  );
}