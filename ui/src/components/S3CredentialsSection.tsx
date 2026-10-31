import { FormEvent, useCallback, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import { EMPTY_S3_FORM, addS3Request, describeEndpoint, type S3Form } from '../api/s3Credentials';
import type { S3Credential } from '../api/types';

/** The caller's own S3 credentials, on the profile page (decision 23): the keys their statements
  * reach S3 with, each under a scope (`s3://bucket/` or a prefix in it). A database's data folder
  * stays unreachable whatever is registered. The secret and the session token are write-only: this
  * section never prefills or shows them, and clears them as soon as a save is done. */
export function S3CredentialsSection() {
  const [rows, setRows] = useState<S3Credential[]>([]);
  const [form, setForm] = useState<S3Form>(EMPTY_S3_FORM);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setRows((await api.listMyS3Credentials()).credentials);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  function field(name: keyof S3Form) {
    return (ev: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm(f => ({ ...f, [name]: ev.target.value }));
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      await api.addMyS3Credential(addS3Request(form));
      setForm(EMPTY_S3_FORM);
      await reload();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function remove(c: S3Credential) {
    if (!window.confirm(`Remove your credential for ${c.scope}? Your statements stop reaching it at once.`)) return;
    setError('');
    try {
      await api.removeMyS3Credential(c.id);
      await reload();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const ready = form.scope.trim() !== '' && form.accessKeyId.trim() !== '' && form.secretAccessKey !== '';
  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-title">S3 credentials</div>
      <p className="subtle" style={{ marginTop: 0 }}>
        Your statements reach an <code>s3://</code> location only under a scope you hold keys for,
        signed with those keys. A database's data folder is never reachable this way. The secret and
        the session token are write-only. Saving a scope you already hold replaces its keys.
      </p>
      {error && <div className="login-err">{error}</div>}
      <form onSubmit={submit} style={{ maxWidth: 360 }}>
        <label>
          Scope
          <input value={form.scope} onChange={field('scope')} placeholder="s3://bucket/ or s3://bucket/prefix/" />
        </label>
        <label>
          Access key id
          <input value={form.accessKeyId} onChange={field('accessKeyId')} autoComplete="off" />
        </label>
        <label>
          Secret access key
          <input type="password" value={form.secretAccessKey} onChange={field('secretAccessKey')} autoComplete="new-password" />
        </label>
        <label>
          Session token (optional)
          <input type="password" value={form.sessionToken} onChange={field('sessionToken')} autoComplete="new-password" />
        </label>
        <label>
          Region (optional)
          <input value={form.region} onChange={field('region')} placeholder="us-east-1" />
        </label>
        <label>
          Endpoint (optional)
          <input value={form.endpoint} onChange={field('endpoint')} placeholder="blank for AWS; one your operator allows" />
        </label>
        <label>
          URL style (optional)
          <select value={form.urlStyle} onChange={field('urlStyle')}>
            <option value="">(default)</option>
            <option value="path">path</option>
            <option value="vhost">vhost</option>
          </select>
        </label>
        <button type="submit" disabled={busy || !ready}>{busy ? 'Saving...' : 'Save credential'}</button>
      </form>
      {rows.length > 0 && (
        <table style={{ marginTop: 12 }}>
          <thead>
            <tr>
              <th>Scope</th>
              <th>Access key id</th>
              <th>Endpoint</th>
              <th>Region</th>
              <th>Token</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map(c => (
              <tr key={c.id}>
                <td><code>{c.scope}</code></td>
                <td><code>{c.accessKeyId}</code></td>
                <td className="subtle">{describeEndpoint(c.endpoint)}</td>
                <td className="subtle">{c.region ?? '-'}</td>
                <td className="subtle">{c.sessionTokenSet ? 'set' : '-'}</td>
                <td>
                  <button className="danger" type="button" onClick={() => { void remove(c); }}>Remove</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
