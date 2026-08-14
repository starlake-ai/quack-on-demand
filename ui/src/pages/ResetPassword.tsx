import { FormEvent, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError, api, errorMessage } from '../api/client';

// Public, pre-session page: reached straight from the emailed reset link
// (`/ui/reset-password?token=...`, see PasswordResetHandlers.resetLink on the
// server), never from an in-app navigation. Must render without a live
// session or any completed auth check - wired as a pathname short-circuit in
// App.tsx's AuthGate, ahead of the loading/session gate the rest of the app
// goes through.
export default function ResetPassword() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [err, setErr] = useState<string | null>(
    () => (token ? null : 'This reset link is invalid or expired.')
  );
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    if (newPassword !== confirmPassword) {
      setErr('New password and confirmation do not match.');
      return;
    }
    setBusy(true);
    try {
      await api.resetPassword({ token, newPassword });
      navigate('/', { replace: true, state: { notice: 'Password reset - sign in with your new password.' } });
    } catch (e) {
      // invalid_token (bad, reused, or expired link) and invalid_password
      // (empty / over the byte limit) both come back as 400s; the link case
      // is by far the common one reaching this page, so lead with that copy
      // and fall back to the server's message for anything else.
      if (e instanceof ApiError && e.code === 'invalid_token') {
        setErr('This reset link is invalid or expired.');
      } else {
        setErr(errorMessage(e));
      }
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
          <p className="login-sub">Choose a new password</p>
        </div>
        {err && <div className="login-err">{err}</div>}
        <label>
          New password
          <input
            type="password"
            value={newPassword}
            onChange={e => setNewPassword(e.target.value)}
            autoComplete="new-password"
            autoFocus
            disabled={!token}
          />
        </label>
        <label>
          Confirm new password
          <input
            type="password"
            value={confirmPassword}
            onChange={e => setConfirmPassword(e.target.value)}
            autoComplete="new-password"
            disabled={!token}
          />
        </label>
        <button type="submit" disabled={busy || !token || !newPassword || !confirmPassword}>
          {busy ? 'Resetting…' : 'Reset password'}
        </button>
        <p className="login-hint">
          <a href="/ui/">Back to login</a>
        </p>
      </form>
    </div>
  );
}
