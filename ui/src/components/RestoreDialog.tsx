import { useEffect, useState } from 'react';
import { api, ApiError, errorMessage } from '../api/client';
import type { RestoreResponse } from '../api/types';
import { Modal } from './Modal';

/** Two-step restore confirmation (Spec 04): fires the dry run on open, shows the summary of
  * changes that will be undone, and only then allows the execute, which carries
  * expectedCurrentSnapshot from the dry-run response so a concurrent write 409s instead of
  * silently restoring over it. On 409 the dialog re-runs the dry run and asks again.
  *
  * Conflict detection matches on the HTTP status (409), not on the error message text: the
  * server's ErrorResponse carries the machine code in `error` ("concurrent_write") and a
  * human string in `message`, but client.ts's `handle()` prefers `message` when building the
  * thrown ApiError, and the pre-check conflict message ("table has advanced to snapshot ...")
  * contains neither "concurrent_write" nor "concurrent". CatalogRestoreHandlers only ever
  * returns 409 for this conflict, so the status is the reliable signal. */
export default function RestoreDialog({ tenant, tenantDb, schema, table, toSnapshot, onClose, onRestored }: {
  tenant: string; tenantDb: string; schema: string; table: string; toSnapshot: number;
  onClose: () => void;
  /** Fired after a successful restore with the new snapshot id; parent refreshes its data. */
  onRestored: (newSnapshot: number | undefined) => void;
}) {
  const [preview, setPreview] = useState<RestoreResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [running, setRunning] = useState(false);
  const [done, setDone] = useState<RestoreResponse | null>(null);

  function dryRun() {
    setPreview(null);
    setError(null);
    api.restoreTable({ tenant, tenantDb, schema, table, to: String(toSnapshot), dryRun: true })
      .then(setPreview)
      .catch(e => setError(errorMessage(e)));
  }

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(dryRun, [tenant, tenantDb, schema, table, toSnapshot]);

  function execute() {
    if (!preview) return;
    setRunning(true);
    setError(null);
    setConflict(false);
    api.restoreTable({
      tenant, tenantDb, schema, table, to: String(toSnapshot),
      expectedCurrentSnapshot: preview.currentSnapshot,
    })
      .then(r => { setDone(r); onRestored(r.newSnapshot); })
      .catch(e => {
        if (e instanceof ApiError && e.status === 409) { setConflict(true); dryRun(); }
        else setError(errorMessage(e));
      })
      .finally(() => setRunning(false));
  }

  const s = preview?.summary;
  return (
    <Modal maxWidth={480} onClose={onClose}>
      <div className="card-title">Restore {schema}.{table} to snapshot {toSnapshot}</div>
      {done ? (
        <>
          <p>
            Restored. New snapshot{' '}
            <code>{done.newSnapshot ?? 'unknown'}</code>. The pre-restore state remains in
            history and is still queryable by time travel.
          </p>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose}>Close</button>
          </div>
        </>
      ) : (
        <>
          <p className="subtle" style={{ marginTop: 0 }}>
            Restore is non-destructive: it writes the table's state at snapshot {toSnapshot} as a
            new snapshot. History is preserved, but the table gets a new identity, so its history
            timeline restarts at the restore.
          </p>
          {conflict && (
            <p style={{ color: 'var(--warn)' }}>
              The table changed while you were looking. The preview below is fresh; confirm again.
            </p>
          )}
          {error && <p style={{ color: 'red' }}>{error}</p>}
          {!preview && !error && <p>Computing what will change...</p>}
          {preview && (
            <p>
              Undoes the changes since snapshot {toSnapshot} (currently {preview.currentSnapshot}):{' '}
              <strong>{s?.inserted ?? 0}</strong> inserted, <strong>{s?.deleted ?? 0}</strong>{' '}
              deleted, <strong>{s?.updated ?? 0}</strong> updated rows will be reverted.
            </p>
          )}
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: '1rem' }}>
            <button type="button" onClick={onClose}>Cancel</button>
            <button type="button" disabled={!preview || running} onClick={execute}>
              {running ? 'Restoring...' : 'Restore'}
            </button>
          </div>
        </>
      )}
    </Modal>
  );
}
