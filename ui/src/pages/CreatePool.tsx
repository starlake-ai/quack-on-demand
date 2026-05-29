import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';

export default function CreatePool() {
  const nav = useNavigate();
  const { tenant } = useParams<{ tenant: string }>();
  const [pool, setPool]       = useState('');
  const [ro, setRo]           = useState(0);
  const [wo, setWo]           = useState(0);
  const [dual, setDual]       = useState(1);

  // The tenant's effective metastore (global defaults + tenant overrides).
  // Loaded once on mount so the form can show what this pool will inherit
  // when fields are left blank.
  const [inherited, setInherited] = useState<Record<string, string>>({});

  // Per-pool overrides. Blank means "use the inherited value".
  const [pgHost, setPgHost]   = useState('');
  const [pgPort, setPgPort]   = useState('');
  const [pgUser, setPgUser]   = useState('');
  const [pgPass, setPgPass]   = useState('');
  const [dbName, setDbName]   = useState('');
  const [schemaName, setSchemaName] = useState('');
  const [dataPath, setDataPath] = useState('');

  const [maxConcurrent, setMaxConcurrent] = useState(0);  // 0 = unlimited
  const [err, setErr]         = useState<string | null>(null);

  useEffect(() => {
    if (!tenant) return;
    api.listTenants()
      .then(r => {
        const t = r.tenants.find(x => x.name === tenant);
        if (t) setInherited(t.effectiveMetastore);
      })
      .catch(e => setErr(String(e)));
  }, [tenant]);

  const size = ro + wo + dual;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    if (!tenant) { setErr('missing tenant in URL'); return; }

    // Drop empty entries so the server's defaultMetastore + tenant overrides
    // fill the blanks.
    const metastoreCandidates: Record<string, string> = {
      pgHost, pgPort, pgUser, pgPassword: pgPass, dbName, schemaName, dataPath
    };
    const metastore: Record<string, string> = {};
    for (const [k, v] of Object.entries(metastoreCandidates)) {
      if (v.trim().length > 0) metastore[k] = v;
    }

    try {
      await api.createPool({
        tenant, pool, size,
        roleDistribution: { writeonly: wo, readonly: ro, dual },
        metastore,
        maxConcurrentPerNode: maxConcurrent
      });
      nav(`/pool/${tenant}/${pool}`);
    } catch (e) { setErr(String(e)); }
  }

  const pg = inherited.pgHost
    ? `${inherited.pgUser || '?'}@${inherited.pgHost}:${inherited.pgPort || '5432'}`
    : '';

  return (
    <form onSubmit={submit}>
      <h2>Create pool in {tenant}</h2>
      {err && <p style={{ color: 'red' }}>{err}</p>}

      <section>
        <h3>Storage (inherited from {tenant})</h3>
        <p style={{ color: '#888', marginTop: 0 }}>
          New pools inherit these values from global defaults and the tenant's overrides.
          Use the metastore fields below only to override a specific value for this pool.
        </p>
        {Object.keys(inherited).length === 0 ? (
          <p style={{ color: '#888' }}>(loading…)</p>
        ) : (
          <table>
            <tbody>
              {inherited.dataPath && (
                <tr><th align="left">Data path</th><td><code>{inherited.dataPath}</code></td></tr>
              )}
              {inherited.dbName && (
                <tr><th align="left">Catalog DB</th><td><code>{inherited.dbName}</code></td></tr>
              )}
              {inherited.schemaName && (
                <tr><th align="left">Schema</th><td><code>{inherited.schemaName}</code></td></tr>
              )}
              {pg && (
                <tr><th align="left">Postgres</th><td><code>{pg}</code></td></tr>
              )}
            </tbody>
          </table>
        )}
      </section>

      <label>Pool <input value={pool} onChange={e => setPool(e.target.value)} required /></label><br/>
      <fieldset>
        <legend>Role distribution (size = {size})</legend>
        <label>WriteOnly <input type="number" min={0} value={wo}   onChange={e => setWo(+e.target.value)} /></label>
        <label>ReadOnly  <input type="number" min={0} value={ro}   onChange={e => setRo(+e.target.value)} /></label>
        <label>Dual      <input type="number" min={0} value={dual} onChange={e => setDual(+e.target.value)} /></label>
      </fieldset>
      <fieldset>
        <legend>Metastore overrides - blank inherits from above</legend>
        <label>Host       <input value={pgHost}   onChange={e => setPgHost(e.target.value)}   placeholder={inherited.pgHost   || '(default)'} /></label><br/>
        <label>Port       <input value={pgPort}   onChange={e => setPgPort(e.target.value)}   placeholder={inherited.pgPort   || '(default)'} /></label><br/>
        <label>User       <input value={pgUser}   onChange={e => setPgUser(e.target.value)}   placeholder={inherited.pgUser   || '(default)'} /></label><br/>
        <label>Password   <input type="password" value={pgPass} onChange={e => setPgPass(e.target.value)} placeholder="(inherited)" /></label><br/>
        <label>DB name    <input value={dbName}   onChange={e => setDbName(e.target.value)}   placeholder={inherited.dbName   || '(default)'} /></label><br/>
        <label>Schema     <input value={schemaName} onChange={e => setSchemaName(e.target.value)} placeholder={inherited.schemaName || '(default)'} /></label><br/>
        <label>Data path  <input value={dataPath} onChange={e => setDataPath(e.target.value)} placeholder={inherited.dataPath || '(default)'} /></label>
      </fieldset>
      <fieldset>
        <legend>Concurrency</legend>
        <label>
          Max concurrent per node{' '}
          <input
            type="number"
            min={0}
            value={maxConcurrent}
            onChange={e => setMaxConcurrent(+e.target.value)}
          />
        </label>
        <span style={{ marginLeft: 8, color: '#888' }}>
          {maxConcurrent === 0 ? '(0 = unlimited)' : ''}
        </span>
      </fieldset>
      <button type="submit" disabled={size === 0 || !tenant || !pool}>Create</button>
    </form>
  );
}