import { useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { ColumnPolicyDto } from '../api/types';
import { DeleteIcon, EditIcon } from './Icons';

/** Column-level policy tab body inside the Role edit modal.
  * Lists existing policies for the role and provides an inline
  * "+ Add column policy" form that calls createColumnPolicy. `onCount` hears the
  * policy count after every load, so the tab title can show it. */
export default function RoleColumnPoliciesSection({ roleId, onCount }: { roleId: string; onCount?: (n: number) => void }) {
  const [policies, setPolicies] = useState<ColumnPolicyDto[]>([]);
  const [error,    setError]    = useState<string | null>(null);
  const [adding,   setAdding]   = useState(false);
  // The policy the form edits in place (action and transform only: the update endpoint keeps
  // what it covers), or null when the form creates one.
  const [editing,  setEditing]  = useState<ColumnPolicyDto | null>(null);

  // Form fields
  const [fCatalog,   setFCatalog]   = useState('*');
  const [fSchema,    setFSchema]    = useState('');
  const [fTable,     setFTable]     = useState('');
  const [fColumn,    setFColumn]    = useState('');
  const [fAction,    setFAction]    = useState<'mask' | 'deny'>('mask');
  const [fTransform, setFTransform] = useState('');
  const [colError,   setColError]   = useState<string | null>(null);

  function reload() {
    setError(null);
    api.listColumnPolicies(roleId)
      .then(r => { setPolicies(r.policies); onCount?.(r.policies.length); })
      .catch(e => setError(errorMessage(e)));
  }

  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roleId]);

  function resetForm() {
    setFCatalog('*');
    setFSchema('');
    setFTable('');
    setFColumn('');
    setFAction('mask');
    setFTransform('');
    setColError(null);
    setError(null);
    setEditing(null);
  }

  function startEdit(p: ColumnPolicyDto) {
    setFCatalog(p.catalogName);
    setFSchema(p.schemaName);
    setFTable(p.tableName);
    setFColumn(p.columnName);
    setFAction(p.action === 'deny' ? 'deny' : 'mask');
    setFTransform(p.transformSql ?? '');
    setColError(null);
    setError(null);
    setEditing(p);
    setAdding(true);
  }

  /** One update, so the policy never lapses as it would between a delete and a create. */
  async function handleUpdate(p: ColumnPolicyDto) {
    try {
      await api.updateColumnPolicy({
        id:           p.id,
        action:       fAction,
        transformSql: fAction === 'deny' ? null : (fTransform.trim() || null),
      });
      setAdding(false);
      resetForm();
      reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function handleCreate(ev: React.FormEvent) {
    ev.preventDefault();
    setColError(null);
    setError(null);
    if (editing) { await handleUpdate(editing); return; }
    // A blank schema or table matches no table, so the policy would never apply.
    if (!fSchema.trim() || !fTable.trim()) {
      setError('Schema and table are required (use * for every one).');
      return;
    }

    if (fColumn.trim() === '*') {
      setColError('Column name must not be a wildcard (*).');
      return;
    }

    try {
      await api.createColumnPolicy({
        roleId,
        catalogName:  fCatalog.trim() || '*',
        schemaName:   fSchema.trim(),
        tableName:    fTable.trim(),
        columnName:   fColumn.trim(),
        action:       fAction,
        transformSql: fAction === 'deny' ? null : (fTransform.trim() || null),
      });
      setAdding(false);
      resetForm();
      reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function handleDelete(p: ColumnPolicyDto) {
    if (!confirm(`Delete column policy for ${p.tableName}.${p.columnName}?`)) return;
    setError(null);
    try {
      await api.deleteColumnPolicy({ id: p.id });
      reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div>
      {error && <div className="login-err">Error: {error}</div>}

      {policies.length === 0 ? (
        <div className="empty">(no column policies yet)</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Catalog</th>
              <th>Schema</th>
              <th>Table</th>
              <th>Column</th>
              <th>Action</th>
              <th>Transform SQL</th>
              <th className="actions"></th>
            </tr>
          </thead>
          <tbody>
            {policies.map(p => (
              <tr key={p.id}>
                <td><code>{p.catalogName}</code></td>
                <td><code>{p.schemaName}</code></td>
                <td><code>{p.tableName}</code></td>
                <td><code>{p.columnName}</code></td>
                <td><code>{p.action}</code></td>
                <td>
                  {p.transformSql
                    ? <code style={{ fontFamily: 'var(--mono)', fontSize: '0.85em', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{p.transformSql}</code>
                    : <em style={{ color: 'var(--text-mute)' }}>-</em>
                  }
                </td>
                <td className="actions">
                  <button
                    className="icon-btn"
                    title="Edit"
                    aria-label="Edit"
                    onClick={() => startEdit(p)}
                  >
                    <EditIcon />
                  </button>{' '}
                  <button
                    className="icon-btn danger"
                    title="Delete"
                    aria-label="Delete"
                    onClick={() => handleDelete(p)}
                  >
                    <DeleteIcon />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {!adding && (
        <div style={{ marginTop: '0.75rem' }}>
          <button type="button" className="link-button" onClick={() => setAdding(true)}>
            + Add column policy
          </button>
        </div>
      )}

      {adding && (
        <form
          onSubmit={handleCreate}
          style={{
            marginTop: '0.75rem',
            background: 'var(--bg-elev)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius)',
            padding: '0.75rem 1rem',
          }}
        >
          {editing && (
            <div className="subtle" style={{ marginBottom: 8 }}>
              Editing the policy on <code>{editing.tableName}.{editing.columnName}</code>: what it covers is fixed; delete and re-create it to change that.
            </div>
          )}
          <div className="row" style={{ gap: 8, marginBottom: 8, flexWrap: 'wrap', alignItems: 'flex-start' }}>
            <div style={{ flex: '1 1 8rem', minWidth: 80 }}>
              <label style={{ margin: 0 }}>
                Catalog
                <input
                  value={fCatalog}
                  disabled={editing != null}
                  onChange={ev => setFCatalog(ev.target.value)}
                  placeholder="*"
                />
              </label>
            </div>
            <div style={{ flex: '1 1 8rem', minWidth: 80 }}>
              <label style={{ margin: 0 }}>
                Schema
                <input
                  value={fSchema}
                  disabled={editing != null}
                  onChange={ev => setFSchema(ev.target.value)}
                  placeholder="schema"
                  required
                />
              </label>
            </div>
            <div style={{ flex: '1 1 8rem', minWidth: 80 }}>
              <label style={{ margin: 0 }}>
                Table
                <input
                  value={fTable}
                  disabled={editing != null}
                  onChange={ev => setFTable(ev.target.value)}
                  placeholder="table"
                  required
                />
              </label>
            </div>
            <div style={{ flex: '1 1 8rem', minWidth: 80 }}>
              <label style={{ margin: 0 }}>
                Column
                <input
                  value={fColumn}
                  disabled={editing != null}
                  onChange={ev => { setFColumn(ev.target.value); setColError(null); }}
                  placeholder="column (no wildcards)"
                  required
                />
              </label>
              {colError && (
                <div style={{ color: 'var(--bad)', fontSize: '0.8em', marginTop: '0.2rem' }}>
                  {colError}
                </div>
              )}
            </div>
            <div style={{ flex: '0 0 7rem', minWidth: 80 }}>
              <label style={{ margin: 0 }}>
                Action
                <select value={fAction} onChange={ev => setFAction(ev.target.value as 'mask' | 'deny')}>
                  <option value="mask">mask</option>
                  <option value="deny">deny</option>
                </select>
              </label>
            </div>
          </div>
          <label style={{ marginBottom: '0.5rem' }}>
            Transform SQL
            <textarea
              rows={5}
              style={{ fontFamily: 'var(--mono)', resize: 'vertical' }}
              value={fTransform}
              onChange={ev => setFTransform(ev.target.value)}
              placeholder={fAction === 'deny' ? '(not applicable for deny)' : "SHA256(CAST(col AS VARCHAR)) or 'REDACTED'"}
              disabled={fAction === 'deny'}
            />
          </label>
          <div className="row" style={{ gap: 8, justifyContent: 'flex-end' }}>
            <button
              type="button"
              className="cancel-button"
              style={{ minWidth: '7rem' }}
              onClick={() => { setAdding(false); resetForm(); }}
            >
              Cancel
            </button>
            <button type="submit" style={{ minWidth: '7rem' }}>{editing ? 'Save' : 'Create'}</button>
          </div>
        </form>
      )}
    </div>
  );
}
