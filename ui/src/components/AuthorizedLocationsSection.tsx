import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import { addLocationRequest, isLocalFolder } from '../api/tenantLocations';
import type { TenantLocation } from '../api/types';
import { DeleteIcon } from './Icons';
import { Modal } from './Modal';

/** Authorized locations of one tenant (`/t/:tenant/access-control`): the local folders under the
  * operator's QOD_TENANT_FILES_ROOT that the tenant's non-superuser users may read and write files
  * in. A database's data folder stays unreachable whatever is listed. S3 is per user: each user
  * registers keys of their own on their profile page. */
export function AuthorizedLocationsSection({ tenant }: { tenant: string }) {
  const [rows, setRows] = useState<TenantLocation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [location, setLocation] = useState('');

  const reload = useCallback(async () => {
    try {
      setRows((await api.listTenantLocations(tenant)).locations);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [tenant]);

  useEffect(() => { void reload(); }, [reload]);

  function closeEditor() {
    setAdding(false);
    setLocation('');
    setError(null);
  }

  async function add(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    try {
      await api.addTenantLocation(addLocationRequest(tenant, location));
      closeEditor();
      await reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function remove(row: TenantLocation) {
    if (!window.confirm(`Remove ${row.location}? The tenant's users lose access to it at once.`)) return;
    try {
      await api.removeTenantLocation({ tenant, id: row.id });
      await reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Authorized folders</div>
        {!adding && <button onClick={() => setAdding(true)}>Add folder</button>}
      </div>
      <p className="subtle">
        The local folders this tenant's users may read and write files in, under the operator's{' '}
        <code>QOD_TENANT_FILES_ROOT</code>/<code>{tenant}</code>/. No user ever reaches a database's
        data folder this way. S3 is per user: each user registers keys of their own on their profile
        page.
      </p>
      {error && !adding && <div className="login-err">Error: {error}</div>}

      {rows === null
        ? <div className="loading">Loading...</div>
        : rows.length === 0
          ? <p className="subtle">No authorized folder.</p>
          : (
            <table>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left' }}>Folder</th>
                  <th style={{ textAlign: 'left' }}>Added by</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map(r => (
                  <tr key={r.id}>
                    <td><code>{r.location}</code></td>
                    <td>{r.createdBy ?? <span className="subtle">-</span>}</td>
                    <td>
                      <button className="icon-btn" title="Remove" aria-label={`Remove ${r.location}`} onClick={() => { void remove(r); }}>
                        <DeleteIcon />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

      {adding && (
        <Modal maxWidth={560} onClose={closeEditor}>
          <div className="card-title">Add an authorized folder</div>
          <form onSubmit={add}>
            <label>
              Folder
              <input value={location} onChange={ev => setLocation(ev.target.value)} placeholder="/root/tenant/folder/" autoFocus />
            </label>
            {error && <div className="login-err">Error: {error}</div>}
            <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
              <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={closeEditor}>Cancel</button>
              <button type="submit" style={{ minWidth: '7rem' }} disabled={!isLocalFolder(location)}>Add</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
