import { useEffect, useState } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { ClientConfigResponse, PoolResponse } from '../api/types';

export default function PoolDetail() {
  const { tenant, pool } = useParams<{ tenant: string; pool: string }>();
  const navigate = useNavigate();
  const [data, setData] = useState<PoolResponse | null>(null);
  const [cfg, setCfg]   = useState<ClientConfigResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.clientConfig().then(setCfg).catch(e => setError(String(e)));
  }, []);

  useEffect(() => {
    if (!tenant || !pool) return;
    let cancelled = false;
    const fetchOnce = () =>
      api.poolStatus(tenant, pool)
        .then(r => { if (!cancelled) setData(r); })
        .catch(e => { if (!cancelled) setError(String(e)); });
    fetchOnce();
    const id = setInterval(fetchOnce, 2000);
    return () => { cancelled = true; clearInterval(id); };
  }, [tenant, pool]);

  /** Build the host the user's clients should target. Substitutes the browser
    * hostname when the server-advertised host is a bind-address like 0.0.0.0. */
  function effectiveHost(host: string): string {
    if (!host || host === '0.0.0.0' || host === '::' || host === '0:0:0:0:0:0:0:0') {
      return typeof window !== 'undefined' ? window.location.hostname : 'localhost';
    }
    return host;
  }

  async function handleStop(force: boolean) {
    if (!tenant || !pool) return;
    if (!confirm(`Stop pool ${tenant}/${pool}${force ? ' (FORCE)' : ''}?`)) return;
    await api.stopPool({ tenant, pool, force });
    navigate(`/tenant/${tenant}`);
  }

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (!data)  return <p>Loading…</p>;

  return (
    <div>
      <header style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h2>{data.tenant} / {data.pool}</h2>
        <div>
          <Link to={`/pool/${data.tenant}/${data.pool}/scale`}>Scale</Link>
          {' | '}
          <button onClick={() => handleStop(false)}>Stop (drain)</button>
          {' '}
          <button onClick={() => handleStop(true)} style={{ color: 'crimson' }}>
            Stop (force)
          </button>
        </div>
      </header>
      <section>
        <h3>Storage</h3>
        {Object.keys(data.metastore).length === 0 ? (
          <p style={{ color: '#888' }}>(no effective metastore - manager defaults apply)</p>
        ) : (
          <table>
            <tbody>
              {data.metastore.dataPath && (
                <tr><th align="left">Data path</th><td><code>{data.metastore.dataPath}</code></td></tr>
              )}
              {data.metastore.dbName && (
                <tr><th align="left">Catalog DB</th><td><code>{data.metastore.dbName}</code></td></tr>
              )}
              {data.metastore.schemaName && (
                <tr><th align="left">Schema</th><td><code>{data.metastore.schemaName}</code></td></tr>
              )}
              {data.metastore.pgHost && (
                <tr><th align="left">Postgres</th>
                  <td><code>{data.metastore.pgUser || '?'}@{data.metastore.pgHost}:{data.metastore.pgPort || '5432'}</code></td>
                </tr>
              )}
              {Object.entries(data.metastore)
                .filter(([k]) => !['dataPath', 'dbName', 'schemaName', 'pgHost', 'pgPort', 'pgUser', 'pgPassword'].includes(k))
                .map(([k, v]) => (
                  <tr key={k}><th align="left">{k}</th><td><code>{v}</code></td></tr>
                ))}
            </tbody>
          </table>
        )}
      </section>

      {cfg && (
        <section>
          <h3>Client connection</h3>
          {(() => {
            const host = effectiveHost(cfg.flightSqlHost);
            const port = cfg.flightSqlPort;
            const scheme = cfg.flightSqlTls ? 'arrow-flight-sql' : 'arrow-flight-sql';
            // Encode tenant/pool individually so `<user>` (and the `/`
            // separators) stay literal in the URL — encoding the whole
            // string would turn `<user>` into `%3Cuser%3E` and confuse
            // operators copy-pasting the template.
            const userQuery = `${encodeURIComponent(tenant!)}/${encodeURIComponent(pool!)}/<user>`;
            const user = `${tenant}/${pool}/<user>`;
            const tlsQuery = cfg.flightSqlTls
              ? 'useEncryption=true&disableCertificateVerification=true'
              : 'useEncryption=false';
            const jdbc = `jdbc:${scheme}://${host}:${port}?${tlsQuery}&user=${userQuery}&password=<password>`;
            const adbcScheme = cfg.flightSqlTls ? 'grpc+tls' : 'grpc';
            const adbcUri = `${adbcScheme}://${host}:${port}`;
            const odbc = `Driver={Arrow Flight SQL ODBC Driver};Host=${host};Port=${port};UseEncryption=${cfg.flightSqlTls ? '1' : '0'};UID=${user};PWD=<password>`;
            const tlsKwarg = cfg.flightSqlTls
              ? `, "adbc.flight.sql.client_option.tls_skip_verify": "true"`
              : '';
            const adbcSnippet = `adbc_driver_flightsql.connect(uri="${adbcUri}", db_kwargs={"username": "${user}", "password": "<password>"${tlsKwarg}})`;
            return (
              <>
                <p style={{ color: '#888', marginTop: 0 }}>
                  Route clients through the FlightSQL edge for capacity-aware,
                  role-respecting load balancing. The username encodes the pool you target:
                  <code> {tenant}/{pool}/&lt;user&gt;</code>.
                  {' '}You can also bypass the edge and talk to one specific Quack node - see "Direct node URIs" below.
                </p>
                <table>
                  <tbody>
                    <tr><th align="left">JDBC</th><td><code>{jdbc}</code></td></tr>
                    <tr><th align="left">ODBC</th><td><code>{odbc}</code></td></tr>
                    <tr><th align="left">ADBC (Python)</th><td><code>{adbcSnippet}</code></td></tr>
                  </tbody>
                </table>
                <h4 style={{ marginBottom: 4 }}>Direct node URIs (DuckDB <code>quack</code> extension)</h4>
                <ul style={{ marginTop: 0 }}>
                  {data.nodes.map(n => (
                    <li key={n.nodeId}>
                      <code>ATTACH 'quack:{n.host}:{n.port}' AS remote (TYPE quack, TOKEN '&lt;token&gt;');</code>
                      {' - token visible in '}<code>state/quack-on-demand-state.json</code>
                    </li>
                  ))}
                </ul>
              </>
            );
          })()}
        </section>
      )}

      <h3>Nodes</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr><th align="left">Node</th><th align="left">Role</th>
              <th align="left">Host</th><th align="left">Port</th>
              <th align="left">Max concurrent</th></tr>
        </thead>
        <tbody>
          {data.nodes.map(n => (
            <tr key={n.nodeId} style={{ borderTop: '1px solid #eee' }}>
              <td><code>{n.nodeId}</code></td>
              <td>{n.role}</td>
              <td>{n.host}</td>
              <td>{n.port}</td>
              <td>
                <input
                  type="number"
                  min={0}
                  defaultValue={n.maxConcurrent}
                  style={{ width: 60 }}
                  onBlur={async e => {
                    const max = Number(e.target.value);
                    if (Number.isFinite(max) && max !== n.maxConcurrent && tenant && pool) {
                      await api.setMaxConcurrent({ tenant, pool, nodeId: n.nodeId, max });
                    }
                  }}
                />
                <span style={{ marginLeft: 4, color: '#888' }}>
                  {n.maxConcurrent === 0 ? '(unlimited)' : ''}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}