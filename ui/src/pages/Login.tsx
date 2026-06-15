import { FormEvent, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../api/client';

export default function Login() {
  const { login } = useAuth();
  const [username, setUsername] = useState('admin@localhost.local');
  const [password, setPassword] = useState('');
  const [tenant,   setTenant]   = useState('');
  const [err, setErr]           = useState<string | null>(null);
  const [busy, setBusy]         = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    setBusy(true);
    try {
      await login(username, password, tenant);
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : String(e);
      setErr(msg);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-shell">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand">
          <img src="/ui/mark-dark.svg" alt="" className="login-logo" />
          <h1>Quack on Demand</h1>
          <p className="login-sub">Admin console</p>
        </div>
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
          Superusers leave Tenant blank. Tenant admins enter their tenant
          name or id (both shown on the Tenants page, e.g. <code>acme</code> or{' '}
          <code>t-02d0e86e</code>).
        </p>
      </form>
    </div>
  );
}