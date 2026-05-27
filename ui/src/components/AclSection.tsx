import { FormEvent, useEffect, useState } from 'react';
import { api } from '../api/client';
import type { AclGrant } from '../api/types';

const PERMISSIONS = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'ALL'];

interface Props { tenant: string; }

/** Per-tenant ACL grants panel. Lists existing grants and offers a single
  * "add grant" form (principal + optional catalog/schema/table + permission)
  * plus a bulk JSON upload. NULL catalog/schema/table = wildcard. */
export default function AclSection({ tenant }: Props) {
  const [grants, setGrants] = useState<AclGrant[]>([]);
  const [err, setErr]       = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const [principal, setPrincipal] = useState('user:');
  const [catalog, setCatalog]     = useState('');
  const [schema,  setSchema]      = useState('');
  const [table,   setTable]       = useState('');
  const [perm,    setPerm]        = useState('SELECT');
  const [busy,    setBusy]        = useState(false);

  function refresh() {
    setLoading(true);
    api.listAclGrants(tenant)
      .then(r => setGrants(r.grants))
      .catch(e => setErr(String(e)))
      .finally(() => setLoading(false));
  }

  useEffect(() => { refresh(); }, [tenant]);

  async function add(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    setBusy(true);
    try {
      await api.createAclGrant({
        tenantId:    tenant,
        principal:   principal.trim(),
        catalogName: catalog.trim() || null,
        schemaName:  schema.trim() || null,
        tableName:   table.trim() || null,
        permission:  perm,
      });
      // Reset table-related fields but keep principal so adding multiple
      // grants for the same user is quick.
      setCatalog(''); setSchema(''); setTable('');
      refresh();
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: number) {
    if (!confirm(`Revoke grant #${id}?`)) return;
    try {
      await api.deleteAclGrant(id);
      refresh();
    } catch (e) {
      setErr(String(e));
    }
  }

  function fmtTarget(g: AclGrant): string {
    const c = g.catalogName ?? '*';
    const s = g.schemaName  ?? '*';
    const t = g.tableName   ?? '*';
    return `${c}.${s}.${t}`;
  }

  return (
    <div className="card">
      <div className="card-title">ACL grants for {tenant}</div>

      {err && <div className="login-err">{err}</div>}

      <form onSubmit={add} className="row" style={{ alignItems: 'flex-end', gap: '.5rem' }}>
        <label style={{ flex: '1 1 200px', marginBottom: 0 }}>
          Principal
          <input
            value={principal}
            onChange={e => setPrincipal(e.target.value)}
            placeholder="user:alice / group:eng / role:admin"
            required
          />
        </label>
        <label style={{ flex: '1 1 120px', marginBottom: 0 }}>
          Catalog
          <input value={catalog} onChange={e => setCatalog(e.target.value)} placeholder="(any)" />
        </label>
        <label style={{ flex: '1 1 120px', marginBottom: 0 }}>
          Schema
          <input value={schema} onChange={e => setSchema(e.target.value)} placeholder="(any)" />
        </label>
        <label style={{ flex: '1 1 120px', marginBottom: 0 }}>
          Table
          <input value={table} onChange={e => setTable(e.target.value)} placeholder="(any)" />
        </label>
        <label style={{ flex: '0 0 130px', marginBottom: 0 }}>
          Permission
          <select value={perm} onChange={e => setPerm(e.target.value)}>
            {PERMISSIONS.map(p => <option key={p} value={p}>{p}</option>)}
          </select>
        </label>
        <button type="submit" disabled={busy || !principal}>Grant</button>
      </form>

      <div className="spacer-y" />

      {loading ? (
        <div className="loading">Loading…</div>
      ) : grants.length === 0 ? (
        <div className="empty">No grants for this tenant yet. Without grants, ACL-enabled deployments will deny every statement.</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>Principal</th>
              <th>Target (catalog.schema.table)</th>
              <th>Permission</th>
              <th>Granted</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {grants.map(g => (
              <tr key={g.id}>
                <td className="muted">{g.id}</td>
                <td><code>{g.principal}</code></td>
                <td><code>{fmtTarget(g)}</code></td>
                <td><span className="badge role-dual">{g.permission}</span></td>
                <td className="subtle">{new Date(g.grantedAt).toLocaleString()}</td>
                <td className="right">
                  <button className="danger" onClick={() => remove(g.id)}>Revoke</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
