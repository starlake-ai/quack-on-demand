import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';

export default function CreateTenant() {
  const nav = useNavigate();
  const [name, setName] = useState('');
  const [err, setErr]   = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    try {
      await api.createTenant({ name });
      nav(`/tenant/${name}`);
    } catch (e) { setErr(String(e)); }
  }

  return (
    <form onSubmit={submit}>
      <h2>Create tenant</h2>
      <p style={{ color: '#666', marginTop: 0 }}>
        A tenant is a logical owner. Storage details (metastore, data path,
        object store) are configured per-database, after the tenant is
        created -- pick "Add database" on the tenant page.
      </p>
      {err && <p style={{ color: 'red' }}>{err}</p>}
      <label>Name <input value={name} onChange={e => setName(e.target.value)} required /></label>
      <button type="submit" disabled={!name}>Create</button>
    </form>
  );
}
