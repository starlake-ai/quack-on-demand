import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import { databasesOf, undropRequest } from '../api/recoverTables';
import type { RecoverableTableEntry } from '../api/types';

/** "Recover a dropped table", on the profile page: the dropped tables of one of the caller's own
  * databases that they may recover (the name is free and their grants cover a read of it), each with
  * an Undrop action and an optional new name. The recovery runs as the caller, so it also needs DDL
  * rights in the table's schema; a refusal is shown as the server words it. */
export function RecoverTablesSection() {
  const [databases, setDatabases] = useState<string[]>([]);
  const [database, setDatabase] = useState('');
  const [tables, setTables] = useState<RecoverableTableEntry[]>([]);
  const [newNames, setNewNames] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.profilePools()
      .then(r => {
        const dbs = databasesOf(r.pools);
        setDatabases(dbs);
        if (dbs.length > 0) setDatabase(dbs[0]);
      })
      .catch(e => setError(errorMessage(e)));
  }, []);

  const reload = useCallback(async (db: string) => {
    if (!db) return;
    setError('');
    try {
      setTables((await api.profileRecoverable(db)).tables);
    } catch (e) {
      setTables([]);
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => { void reload(database); }, [database, reload]);

  // A name dropped twice is two entries: keyed by table id and drop snapshot, never by name alone.
  const key = (t: RecoverableTableEntry) => `${t.tableId}@${t.droppedAtSnapshot}`;
  const label = (t: RecoverableTableEntry) => `${t.schema}.${t.table}`;

  async function undrop(t: RecoverableTableEntry) {
    setError('');
    setNotice('');
    setBusy(true);
    try {
      const r = await api.profileUndrop(undropRequest(database, t.schema, t.table, newNames[key(t)] ?? ''));
      setNotice(`${r.schema}.${r.table} recovered as ${r.schema}.${r.restoredAs}.`);
      setNewNames(n => ({ ...n, [key(t)]: '' }));
      await reload(database);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-title">Recover a dropped table</div>
      {databases.length === 0 && !error && (
        <p className="muted">You may connect to no database.</p>
      )}
      {databases.length > 0 && (
        <p>
          <label>
            Database{' '}
            <select value={database} onChange={e => setDatabase(e.target.value)}>
              {databases.map(d => <option key={d} value={d}>{d}</option>)}
            </select>
          </label>
        </p>
      )}
      {error && <p className="login-err">{error}</p>}
      {notice && <p className="muted">{notice}</p>}
      {database && tables.length === 0 && !error && (
        <p className="muted">No dropped table of {database} that you may recover.</p>
      )}
      {tables.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Table</th>
              <th>Dropped at</th>
              <th>New name (optional)</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {tables.map(t => (
              <tr key={key(t)}>
                <td><code>{label(t)}</code></td>
                <td className="subtle">{t.droppedAt ?? `snapshot ${t.droppedAtSnapshot}`}</td>
                <td>
                  <input
                    value={newNames[key(t)] ?? ''}
                    placeholder={t.table}
                    onChange={e => setNewNames(n => ({ ...n, [key(t)]: e.target.value }))}
                  />
                </td>
                <td>
                  <button disabled={busy || !t.recoverable} onClick={() => void undrop(t)}>
                    Undrop
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="subtle" style={{ marginBottom: 0 }}>
        Recovering runs as you: you need a read grant on the table&apos;s last name, which no live
        table or view may hold, and the right to create tables in its schema. SQL:{' '}
        <code>UNDROP TABLE schema.table [AS new_name]</code>.
      </p>
    </div>
  );
}
