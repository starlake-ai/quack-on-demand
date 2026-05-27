import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';

export default function CreateTenant() {
  const nav = useNavigate();
  const [name, setName]       = useState('');
  const [pgHost, setPgHost]   = useState('');
  const [pgPort, setPgPort]   = useState('');
  const [pgUser, setPgUser]   = useState('');
  const [pgPass, setPgPass]   = useState('');
  const [dbName, setDbName]   = useState('');
  const [schemaName, setSchemaName] = useState('');
  const [dataPath, setDataPath] = useState('');
  const [err, setErr]         = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    const candidates: Record<string, string> = {
      pgHost, pgPort, pgUser, pgPassword: pgPass, dbName, schemaName, dataPath
    };
    const metastore: Record<string, string> = {};
    for (const [k, v] of Object.entries(candidates)) {
      if (v.trim().length > 0) metastore[k] = v;
    }
    try {
      await api.createTenant({ name, metastore });
      nav(`/tenant/${name}`);
    } catch (e) { setErr(String(e)); }
  }

  return (
    <form onSubmit={submit}>
      <h2>Create tenant</h2>
      {err && <p style={{ color: 'red' }}>{err}</p>}
      <label>Name <input value={name} onChange={e => setName(e.target.value)} required /></label>
      <fieldset>
        <legend>Per-tenant metastore overrides — leave blank to inherit global defaults</legend>
        <label>Host       <input value={pgHost}   onChange={e => setPgHost(e.target.value)}   placeholder="(global default)" /></label><br/>
        <label>Port       <input value={pgPort}   onChange={e => setPgPort(e.target.value)}   placeholder="(global default)" /></label><br/>
        <label>User       <input value={pgUser}   onChange={e => setPgUser(e.target.value)}   placeholder="(global default)" /></label><br/>
        <label>Password   <input type="password" value={pgPass} onChange={e => setPgPass(e.target.value)} placeholder="(global default)" /></label><br/>
        <label>DB name    <input value={dbName}   onChange={e => setDbName(e.target.value)}   placeholder="(global default)" /></label><br/>
        <label>Schema     <input value={schemaName} onChange={e => setSchemaName(e.target.value)} placeholder="(global default)" /></label><br/>
        <label>Data path  <input value={dataPath} onChange={e => setDataPath(e.target.value)} placeholder="(global default)" /></label>
      </fieldset>
      <button type="submit" disabled={!name}>Create</button>
    </form>
  );
}
