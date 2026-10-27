import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import { EMPTY_LOCATION_FORM, addLocationRequest, locationKind, nodesNote, type LocationForm } from '../api/tenantLocations';
import type { TenantLocation } from '../api/types';
import { DeleteIcon } from './Icons';
import { Modal } from './Modal';

/** Authorized locations of one tenant (`/t/:tenant/access-control`): the S3 buckets, each with its
  * own credentials, and the local folders under the operator's QOD_TENANT_FILES_ROOT that the
  * tenant's non-superuser users may read and write files in. A database's data folder stays
  * unreachable whatever is listed. The secret access key is write-only: the server only says
  * whether one is stored, so this section never prefills or shows it. */
export function AuthorizedLocationsSection({ tenant }: { tenant: string }) {
  const [rows, setRows] = useState<TenantLocation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState<LocationForm>(EMPTY_LOCATION_FORM);

  const reload = useCallback(async () => {
    try {
      setRows((await api.listTenantLocations(tenant)).locations);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [tenant]);

  useEffect(() => { void reload(); }, [reload]);

  function field(name: keyof LocationForm) {
    return (ev: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm(f => ({ ...f, [name]: ev.target.value }));
  }

  function closeEditor() {
    setAdding(false);
    setForm(EMPTY_LOCATION_FORM);
    setError(null);
  }

  async function add(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    try {
      const res = await api.addTenantLocation(addLocationRequest(tenant, form));
      closeEditor();
      setNote(nodesNote(res));
      await reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function remove(row: TenantLocation) {
    if (!window.confirm(`Remove ${row.location}? The tenant's users lose access to it at once.`)) return;
    try {
      const res = await api.removeTenantLocation({ tenant, id: row.id });
      setNote(nodesNote(res));
      await reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  const kind = locationKind(form.location);
  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Authorized locations</div>
        {!adding && <button onClick={() => setAdding(true)}>Add location</button>}
      </div>
      <p className="subtle">
        The S3 buckets and local folders this tenant's users may read and write files in. A bucket
        needs its own credentials; its secret is write-only. A local folder must lie under the
        operator's <code>QOD_TENANT_FILES_ROOT</code>/<code>{tenant}</code>/. No user ever reaches
        a database's data folder this way.
      </p>
      {error && !adding && <div className="login-err">Error: {error}</div>}
      {note && <p className="subtle">{note}</p>}

      {rows === null
        ? <div className="loading">Loading...</div>
        : rows.length === 0
          ? <p className="subtle">No authorized location.</p>
          : (
            <table>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left' }}>Location</th>
                  <th style={{ textAlign: 'left' }}>Kind</th>
                  <th style={{ textAlign: 'left' }}>Access key id</th>
                  <th style={{ textAlign: 'left' }}>Secret</th>
                  <th style={{ textAlign: 'left' }}>Endpoint</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map(r => (
                  <tr key={r.id}>
                    <td><code>{r.location}</code></td>
                    <td>{r.kind}</td>
                    <td>{r.objectStore.s3_access_key_id ? <code>{r.objectStore.s3_access_key_id}</code> : <span className="subtle">-</span>}</td>
                    <td>{r.kind === 's3' ? (r.secretSet ? 'set' : <span className="subtle">not set</span>) : <span className="subtle">-</span>}</td>
                    <td>{r.objectStore.s3_endpoint ? <code>{r.objectStore.s3_endpoint}</code> : <span className="subtle">-</span>}</td>
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
          <div className="card-title">Add an authorized location</div>
          <form onSubmit={add}>
            <label>
              Location
              <input value={form.location} onChange={field('location')} placeholder="s3://bucket/ or /root/tenant/folder/" autoFocus />
            </label>
            {kind === 's3' && (
              <>
                <label>
                  Access key id
                  <input value={form.accessKeyId} onChange={field('accessKeyId')} autoComplete="off" />
                </label>
                <label>
                  Secret access key
                  <input type="password" value={form.secretAccessKey} onChange={field('secretAccessKey')} autoComplete="new-password" />
                </label>
                <label>
                  Region
                  <input value={form.region} onChange={field('region')} placeholder="us-east-1" />
                </label>
                <label>
                  Endpoint
                  <input value={form.endpoint} onChange={field('endpoint')} placeholder="leave blank for AWS; http://minio:9000 for MinIO" />
                </label>
                <label>
                  URL style
                  <select value={form.urlStyle} onChange={field('urlStyle')}>
                    <option value="">path (default)</option>
                    <option value="path">path</option>
                    <option value="vhost">vhost</option>
                  </select>
                </label>
              </>
            )}
            {error && <div className="login-err">Error: {error}</div>}
            <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
              <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={closeEditor}>Cancel</button>
              <button type="submit" style={{ minWidth: '7rem' }} disabled={kind === null}>Add</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
