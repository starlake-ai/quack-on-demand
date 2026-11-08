import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { PoolPermissionResponse, PoolResponse } from '../api/types';
import { DeleteIcon } from './Icons';

/** The pool grants made to one tenant user directly, under the user's row in the Users table:
  * list, grant and revoke, each applied at once. Grants through the user's groups are the groups'
  * to change (Groups page); the row's "Pool grants" column shows both. `onChange` fires after a
  * grant or a revoke so the table can refresh. */
export default function UserPoolGrantsCard({
  userId,
  tenantId,
  pools,
  onChange,
}: {
  userId:   string;
  tenantId: string;
  /** Every pool the caller sees; narrowed here to the user's tenant. */
  pools:    PoolResponse[];
  onChange?: () => void;
}) {
  const [rows, setRows]       = useState<PoolPermissionResponse[] | null>(null);
  const [error, setError]     = useState<string | null>(null);
  const [poolId, setPoolId]   = useState('');   // '' = every pool in the tenant

  const tenantPools = pools.filter(p => p.tenant === tenantId);
  const poolName = (id: string | null) =>
    id == null ? '*' : (tenantPools.find(p => p.id === id)?.pool ?? id);

  const reload = useCallback(async () => {
    try {
      setRows((await api.listPoolPermissions({ userId })).permissions);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [userId]);

  useEffect(() => { void reload(); }, [reload]);

  // A pool already granted is not offered again (the store holds one grant per user and pool).
  const granted = new Set((rows ?? []).map(r => r.poolId ?? ''));
  const offered = [
    { value: '', label: '(every pool in tenant)' },
    ...tenantPools.map(p => ({ value: p.id, label: p.pool })),
  ].filter(o => !granted.has(o.value));
  const selected = offered.some(o => o.value === poolId) ? poolId : (offered[0]?.value ?? null);

  async function grant(ev: React.FormEvent) {
    ev.preventDefault();
    if (selected == null) return;
    try {
      await api.grantPoolPermission({ tenant: tenantId, poolId: selected || null, userId });
      setPoolId('');
      await reload();
      onChange?.();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function revoke(p: PoolPermissionResponse) {
    if (!window.confirm(`Revoke this user's grant on ${poolName(p.poolId)}? Grants through their groups stay.`)) return;
    try {
      await api.revokePoolPermission({ id: p.id });
      await reload();
      onChange?.();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div className="card" style={{ marginTop: 8 }}>
      <div className="card-title">Direct pool grants</div>
      {error && <div className="login-err">Error: {error}</div>}
      {offered.length > 0 && (
        <form onSubmit={grant} className="row" style={{ gap: 8, marginBottom: 8, alignItems: 'center' }}>
          <select style={{ flex: 1 }} value={selected ?? ''} onChange={ev => setPoolId(ev.target.value)}>
            {offered.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          <button type="submit" style={{ whiteSpace: 'nowrap' }}>+ Grant pool</button>
        </form>
      )}
      {rows === null
        ? <div className="loading">Loading...</div>
        : rows.length === 0
          ? <p className="subtle">No direct pool grant. The user reaches pools through their groups only.</p>
          : (
            <table>
              <thead><tr><th style={{ textAlign: 'left' }}>Pool</th><th className="actions" /></tr></thead>
              <tbody>
                {rows.map(p => (
                  <tr key={p.id}>
                    <td><code>{poolName(p.poolId)}</code></td>
                    <td className="actions">
                      <button className="icon-btn danger" title="Revoke" aria-label={`Revoke ${poolName(p.poolId)}`} onClick={() => { void revoke(p); }}>
                        <DeleteIcon />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
    </div>
  );
}
