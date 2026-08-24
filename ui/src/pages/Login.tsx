import { FormEvent, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiError, api, errorMessage } from '../api/client';

// Anti-enumeration: this exact copy is shown after every forgot-password
// submission, whether or not the (tenant, username) account exists, has an
// email on file, or the request even reached the server successfully. Never
// branch this message on the outcome - see api.forgotPassword.
const FORGOT_PASSWORD_NOTICE = 'If that account exists, a reset link has been emailed.';

// Tenant prefill: remember the tenant of the last successful sign-in so the
// field comes back filled on the next visit. localStorage can throw (private
// browsing, storage disabled), so both sides are guarded - losing the prefill
// must never break the login form.
const TENANT_STORAGE_KEY = 'qod_login_tenant';

function loadSavedTenant(): string {
  try {
    return localStorage.getItem(TENANT_STORAGE_KEY) ?? '';
  } catch {
    return '';
  }
}

function saveTenant(tenant: string) {
  try {
    localStorage.setItem(TENANT_STORAGE_KEY, tenant);
  } catch {
    // Ignored: prefill is best-effort.
  }
}

export default function Login() {
  const { login } = useAuth();
  const location = useLocation();
  const [username, setUsername] = useState('admin@localhost.local');
  const [password, setPassword] = useState('');
  const [tenant,   setTenant]   = useState(loadSavedTenant);
  const [err, setErr]           = useState<string | null>(null);
  // Informational (non-error) notice on the login form, e.g. after a
  // password change whose automatic re-login attempt failed, or after
  // navigating back here from a successful password reset.
  const [notice, setNotice]     = useState<string | null>(
    () => (location.state as { notice?: string } | null)?.notice ?? null
  );
  const [busy, setBusy]         = useState(false);

  // Forced password-change pivot: a login attempt against an account
  // flagged mustChangePassword comes back 401 password_change_required
  // instead of a session. `password` still holds the value the user typed,
  // which doubles as currentPassword for the change-password call below.
  const [phase, setPhase]                 = useState<'login' | 'change' | 'forgot'>('login');
  const [newPassword, setNewPassword]     = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  // Forgot-password inline form. Defaults to whatever the user already
  // typed into the login form's username/tenant fields.
  const [forgotUsername, setForgotUsername] = useState('');
  const [forgotTenant,   setForgotTenant]   = useState('');
  const [forgotSent,     setForgotSent]     = useState(false);
  const [forgotBusy,     setForgotBusy]     = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    setNotice(null);
    setBusy(true);
    try {
      await login(username, password, tenant);
      saveTenant(tenant);
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

  function openForgot() {
    setForgotUsername(username);
    setForgotTenant(tenant);
    setForgotSent(false);
    setErr(null);
    setPhase('forgot');
  }

  function backToLoginFromForgot() {
    setPhase('login');
    setForgotSent(false);
    setErr(null);
  }

  async function submitForgot(e: FormEvent) {
    e.preventDefault();
    setForgotBusy(true);
    try {
      await api.forgotPassword({
        tenant: forgotTenant.trim() || undefined,
        username: forgotUsername.trim(),
      });
    } catch {
      // Deliberately ignored: the same static notice is shown whether the
      // account exists, has no email, or the request itself failed. Any
      // outcome-dependent message here would reopen the enumeration channel
      // the backend closes with its uniform 200 response.
    } finally {
      setForgotBusy(false);
      setForgotSent(true);
    }
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
      saveTenant(tenant);
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

  if (phase === 'forgot') {
    return (
      <div className="login-shell">
        <div className="login-card">
          <div className="login-brand">
            <img src="/ui/mark-dark.svg" alt="" className="login-logo" />
            <h1>Quack on Demand</h1>
            <p className="login-sub">Reset your password</p>
          </div>
          {forgotSent ? (
            <>
              <div className="login-notice">{FORGOT_PASSWORD_NOTICE}</div>
              <p className="login-hint">
                <a href="#" onClick={e => { e.preventDefault(); backToLoginFromForgot(); }}>Back to login</a>
              </p>
            </>
          ) : (
            <form onSubmit={submitForgot}>
              <label>
                Username
                <input
                  value={forgotUsername}
                  onChange={e => setForgotUsername(e.target.value)}
                  autoComplete="username"
                  autoFocus
                  required
                />
              </label>
              <label>
                Tenant
                <input
                  value={forgotTenant}
                  onChange={e => setForgotTenant(e.target.value)}
                  placeholder="leave blank for superuser"
                  autoComplete="off"
                />
              </label>
              <button type="submit" disabled={forgotBusy || !forgotUsername}>
                {forgotBusy ? 'Sending…' : 'Send reset link'}
              </button>
              <p className="login-hint">
                <a href="#" onClick={e => { e.preventDefault(); backToLoginFromForgot(); }}>Back to login</a>
              </p>
            </form>
          )}
        </div>
      </div>
    );
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
          <a href="#" onClick={e => { e.preventDefault(); openForgot(); }}>Forgot password?</a>
        </p>
        <p className="login-hint">
          Superusers leave Tenant blank. Tenant admins and other tenant users
          enter their tenant name or id (both shown on the Tenants page, e.g.{' '}
          <code>acme</code> or <code>t-02d0e86e</code>).
        </p>
      </form>
    </div>
  );
}