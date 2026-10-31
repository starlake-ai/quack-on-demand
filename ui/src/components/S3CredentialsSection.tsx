import { FormEvent, useCallback, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import { EMPTY_S3_FORM, addS3Request, describeEndpoint, type S3Form } from '../api/s3Credentials';
import type { S3Credential } from '../api/types';

/** Credentials one user may hold: the manager's `UserS3Credential.MaxPerUser`. */
const MAX_CREDENTIALS = 32;

/** The open form: a new scope, or new keys for a scope already held (its scope fixed). */
type Editing = { kind: 'add' } | { kind: 'replace'; scope: string };

/** The caller's own S3 credentials, on the profile page (decision 23): the keys their statements
  * reach S3 with, one credential per scope (`s3://bucket/` or a prefix in it), so a user holding
  * several buckets adds one credential for each. A database's data folder stays unreachable
  * whatever is registered. The secret and the session token are write-only: this section never
  * prefills or shows them, and clears them as soon as a save is done. */
export function S3CredentialsSection() {
  const [rows, setRows] = useState<S3Credential[] | null>(null);
  const [form, setForm] = useState<S3Form>(EMPTY_S3_FORM);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setRows((await api.listMyS3Credentials()).credentials);
    } catch (e) {
      setRows([]);
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  function field(name: keyof S3Form) {
    return (ev: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm(f => ({ ...f, [name]: ev.target.value }));
  }

  function openAdd() {
    setForm(EMPTY_S3_FORM);
    setEditing({ kind: 'add' });
    setError('');
    setNotice('');
  }

  /** New keys for a held scope: everything but the write-only secret and token prefilled. */
  function openReplace(c: S3Credential) {
    setForm({
      ...EMPTY_S3_FORM,
      scope: c.scope,
      accessKeyId: c.accessKeyId,
      region: c.region ?? '',
      endpoint: c.endpoint ?? '',
      urlStyle: c.urlStyle ?? '',
    });
    setEditing({ kind: 'replace', scope: c.scope });
    setError('');
    setNotice('');
  }

  function close() {
    setForm(EMPTY_S3_FORM);
    setEditing(null);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const saved = await api.addMyS3Credential(addS3Request(form));
      close();
      setNotice(`Saved the credential for ${saved.scope}.`);
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
    setNotice('');
    try {
      await api.removeMyS3Credential(c.id);
      if (editing?.kind === 'replace' && editing.scope === c.scope) close();
      await reload();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const count = rows?.length ?? 0;
  const full = count >= MAX_CREDENTIALS;
  const ready = form.scope.trim() !== '' && form.accessKeyId.trim() !== '' && form.secretAccessKey !== '';
  return (
    <div className="card cred-section" style={{ marginBottom: 16 }}>
      <div className="cred-head">
        <div className="card-title" style={{ margin: 0 }}>
          S3 credentials
          {rows && <span className="subtle cred-count">{count} of {MAX_CREDENTIALS}</span>}
        </div>
        {editing == null && (
          <button type="button" onClick={openAdd} disabled={full || rows == null}
            title={full ? `You hold the maximum of ${MAX_CREDENTIALS}; remove one first` : undefined}>
            Add credential
          </button>
        )}
      </div>
      <p className="subtle">
        Add one credential for each bucket or prefix your statements read or write. A statement
        reaching an <code>s3://</code> URL is signed with the keys of the scope that covers it.
        A database's data folder is never reachable this way. Secrets and session tokens are
        write-only.
      </p>
      {error && <div className="login-err">{error}</div>}
      {notice && <div className="subtle" role="status">{notice}</div>}

      {rows == null ? (
        <div className="subtle">Loading...</div>
      ) : rows.length === 0 ? (
        editing == null && (
          <div className="empty">
            No S3 credentials yet. Add one for each bucket or prefix you need to reach.
          </div>
        )
      ) : (
        <table>
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
              <tr key={c.id} className={editing?.kind === 'replace' && editing.scope === c.scope ? 'cred-editing' : undefined}>
                <td><code>{c.scope}</code></td>
                <td><code>{c.accessKeyId}</code></td>
                <td className="subtle">{describeEndpoint(c.endpoint)}</td>
                <td className="subtle">{c.region ?? '-'}</td>
                <td className="subtle">{c.sessionTokenSet ? 'set' : '-'}</td>
                <td className="cred-actions">
                  <button type="button" className="secondary" onClick={() => openReplace(c)}>Replace keys</button>
                  <button className="danger" type="button" onClick={() => { void remove(c); }}>Remove</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {editing != null && (
        <form onSubmit={submit} className="cred-form">
          <div className="cred-form-title">
            {editing.kind === 'add'
              ? 'New credential'
              : <>New keys for <code>{editing.scope}</code></>}
          </div>
          <div className="cred-grid">
            <label className="cred-wide">
              Scope
              <input value={form.scope} onChange={field('scope')} placeholder="s3://bucket/ or s3://bucket/prefix/"
                readOnly={editing.kind === 'replace'} autoFocus={editing.kind === 'add'} />
            </label>
            <label>
              Access key id
              <input value={form.accessKeyId} onChange={field('accessKeyId')} autoComplete="off" />
            </label>
            <label>
              Secret access key
              <input type="password" value={form.secretAccessKey} onChange={field('secretAccessKey')}
                autoComplete="new-password" autoFocus={editing.kind === 'replace'} />
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
          </div>
          <div className="cred-form-actions">
            <button type="submit" disabled={busy || !ready}>
              {busy ? 'Saving...' : editing.kind === 'add' ? 'Save credential' : 'Replace keys'}
            </button>
            <button type="button" className="secondary" onClick={close} disabled={busy}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  );
}
