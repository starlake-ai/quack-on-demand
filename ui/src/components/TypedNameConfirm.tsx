import { FormEvent, ReactNode, useState } from 'react';
import { errorMessage } from '../api/client';
import { typedNameMatches } from '../api/typedConfirm';
import { Modal } from './Modal';

/** Confirmation for a delete that cannot be undone: the Delete button stays off until `name` is
  * typed (`typedNameMatches`). `children` says what goes with it and may hold extra choices (a
  * checkbox); `onConfirm` runs the delete, and an error it throws is shown here with the dialog
  * left open, so a refused delete (pools still running) reads where it was asked. */
export function TypedNameConfirm({ title, name, confirmLabel, onConfirm, onCancel, children }: {
  title: string;
  name: string;
  confirmLabel: string;
  onConfirm: () => Promise<void>;
  onCancel: () => void;
  children: ReactNode;
}) {
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const matches = typedNameMatches(typed, name);

  async function submit(ev: FormEvent) {
    ev.preventDefault();
    if (!matches || busy) return;
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  }

  return (
    <Modal maxWidth={480} onClose={() => { if (!busy) onCancel(); }}>
      <form onSubmit={ev => void submit(ev)}>
        <div className="card-title">{title}</div>
        <div style={{ marginTop: '0.5rem' }}>{children}</div>
        <label style={{ display: 'block', marginTop: '0.75rem' }}>
          Type <code>{name}</code> to confirm
          <input
            type="text"
            value={typed}
            onChange={ev => setTyped(ev.target.value)}
            autoFocus
            autoComplete="off"
            spellCheck={false}
            aria-label={`Type ${name} to confirm`}
            disabled={busy}
          />
        </label>
        {error && <div className="login-err" style={{ marginTop: '0.5rem' }}>Error: {error}</div>}
        <div className="row" style={{ gap: 8, marginTop: '0.75rem', justifyContent: 'flex-end' }}>
          <button type="button" className="cancel-button" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="danger" disabled={!matches || busy}>
            {busy ? 'Deleting...' : confirmLabel}
          </button>
        </div>
      </form>
    </Modal>
  );
}
