import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import { describeEndpoint } from '../api/s3Credentials';
import type { S3Credential } from '../api/types';
import { DeleteIcon } from './Icons';

/** One user's S3 credentials as an admin sees them, under the user's row in the Users table: list and
  * remove, never add (a credential is its owner's to give, decision 23), never a secret. */
export default function UserS3CredentialsCard({ userId }: { userId: string }) {
  const [rows, setRows] = useState<S3Credential[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setRows((await api.listUserS3Credentials(userId)).credentials);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [userId]);

  useEffect(() => { void reload(); }, [reload]);

  async function remove(c: S3Credential) {
    if (!window.confirm(`Remove this user's credential for ${c.scope}? Their statements stop reaching it at once.`)) return;
    try {
      await api.removeUserS3Credential(userId, c.id);
      await reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div className="card" style={{ marginTop: 8 }}>
      <div className="card-title">S3 credentials</div>
      {error && <div className="login-err">Error: {error}</div>}
      {rows === null
        ? <div className="loading">Loading...</div>
        : rows.length === 0
          ? <p className="subtle">No S3 credential. Users register their own on their profile page.</p>
          : (
            <table>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left' }}>Scope</th>
                  <th style={{ textAlign: 'left' }}>Access key id</th>
                  <th style={{ textAlign: 'left' }}>Endpoint</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map(c => (
                  <tr key={c.id}>
                    <td><code>{c.scope}</code></td>
                    <td><code>{c.accessKeyId}</code></td>
                    <td className="subtle">{describeEndpoint(c.endpoint)}</td>
                    <td>
                      <button className="icon-btn" title="Remove" aria-label={`Remove ${c.scope}`} onClick={() => { void remove(c); }}>
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
