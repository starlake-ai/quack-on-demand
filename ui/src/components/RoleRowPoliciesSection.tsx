import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { RowPolicyDto } from '../api/types';
import { DeleteIcon } from './Icons';

/** Row-level policy tab body inside the Role edit modal.
  * Lists existing row policies for the role and provides an inline
  * "+ Add row policy" form that calls createRowPolicy. The predicate is a
  * boolean SQL expression that may embed identity tokens
  * (${user}, ${tenant}, ${tenantId}, ${roles}, ${groups}) substituted at
  * query time. Row-level security is experimental and gated by
  * QOD_RLS_ENABLED on the manager. */
export default function RoleRowPoliciesSection({ roleId }: { roleId: string }) {
  const [policies, setPolicies] = useState<RowPolicyDto[]>([]);
  const [error,    setError]    = useState<string | null>(null);
  const [adding,   setAdding]   = useState(false);

  // Form fields
  const [fCatalog,   setFCatalog]   = useState('*');
  const [fSchema,    setFSchema]    = useState('');
  const [fTable,     setFTable]     = useState('');
  const [fPredicate, setFPredicate] = useState('');

  function reload() {
    setError(null);
    api.listRowPolicies(roleId)
      .then(r => setPolicies(r.policies))
      .catch(e => setError(e instanceof ApiError ? e.message : String(e)));
  }

  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roleId]);

  function resetForm() {
    setFCatalog('*');
    setFSchema('');
    setFTable('');
    setFPredicate('');
    setError(null);
  }

  async function handleCreate(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    try {
      await api.createRowPolicy({
        roleId,
        catalogName:  fCatalog.trim() || '*',
        schemaName:   fSchema.trim(),
        tableName:    fTable.trim(),
        predicateSql: fPredicate.trim(),
      });
      setAdding(false);
      resetForm();
      reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function handleDelete(p: RowPolicyDto) {
    if (!confirm(`Delete row policy on ${p.tableName}?`)) return;
    setError(null);
    try {
      await api.deleteRowPolicy({ id: p.id });
      reload();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  return (
    <div>
      {error && <div className="login-err">Error: {error}</div>}

      {policies.length === 0 ? (
        <div className="empty">(no row policies yet)</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Catalog</th>
              <th>Schema</th>
              <th>Table</th>
              <th>Predicate SQL</th>
              <th className="actions"></th>
            </tr>
          </thead>
          <tbody>
            {policies.map(p => (
              <tr key={p.id}>
                <td><code>{p.catalogName}</code></td>
                <td><code>{p.schemaName}</code></td>
                <td><code>{p.tableName}</code></td>
                <td>
                  <code style={{ fontFamily: 'var(--mono)', fontSize: '0.85em', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{p.predicateSql}</code>
                </td>
                <td className="actions">
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
            + Add row policy
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
          <div className="row" style={{ gap: 8, marginBottom: 8, flexWrap: 'wrap', alignItems: 'flex-start' }}>
            <div style={{ flex: '1 1 8rem', minWidth: 80 }}>
              <label style={{ margin: 0 }}>
                Catalog
                <input
                  value={fCatalog}
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
                  onChange={ev => setFTable(ev.target.value)}
                  placeholder="table"
                  required
                />
              </label>
            </div>
          </div>
          <label style={{ marginBottom: '0.5rem' }}>
            Predicate SQL
            <textarea
              rows={4}
              style={{ fontFamily: 'var(--mono)', resize: 'vertical' }}
              value={fPredicate}
              onChange={ev => setFPredicate(ev.target.value)}
              placeholder="region = ${tenantId} OR owner = ${user}"
              required
            />
          </label>
          <div style={{ color: 'var(--text-mute)', fontSize: '0.8em', marginBottom: '0.5rem' }}>
            Boolean filter applied to each matching table. Identity tokens:{' '}
            <code>{'${user}'}</code>, <code>{'${tenant}'}</code>, <code>{'${tenantId}'}</code>,{' '}
            <code>{'${roles}'}</code>, <code>{'${groups}'}</code> (list tokens for <code>IN (…)</code>).
          </div>
          <div className="row" style={{ gap: 8, justifyContent: 'flex-end' }}>
            <button
              type="button"
              className="cancel-button"
              style={{ minWidth: '7rem' }}
              onClick={() => { setAdding(false); resetForm(); }}
            >
              Cancel
            </button>
            <button type="submit" style={{ minWidth: '7rem' }}>Create</button>
          </div>
        </form>
      )}
    </div>
  );
}