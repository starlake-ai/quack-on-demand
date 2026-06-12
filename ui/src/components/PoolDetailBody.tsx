import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { ClientConfigResponse, PoolResponse } from '../api/types';
import Tabs from './Tabs';

/** Header (title + Back / Scale / Delete pool actions) + the four-tab
  * body for one pool. No breadcrumb -- callers compose that themselves.
  * The `onStopped` callback lets the standalone page navigate elsewhere
  * after a successful delete, while the inline view inside the Pools
  * tab just collapses back to its list. */
export default function PoolDetailBody({
  tenant,
  tenantDb,
  pool,
  onBack,
}: {
  tenant:    string;
  tenantDb:  string;
  pool:      string;
  /** Reserved for callers that still navigate on stop; the detail view
    * no longer renders a Stop/Drain button so it's never invoked here. */
  onStopped?: () => void;
  /** Custom Back-button handler. When provided, the header renders a
    * "Back to pools" button that calls this (used by PoolSection to
    * collapse the inline view). When omitted, the header falls back to
    * a `<Link>` back to the tenant page (used by the standalone
    * `/pool/...` route). */
  onBack?:   () => void;
}) {
  const [data, setData] = useState<PoolResponse | null>(null);
  const [cfg, setCfg]   = useState<ClientConfigResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.clientConfig().then(setCfg).catch(e => setError(String(e)));
  }, []);

  useEffect(() => {
    if (!tenant || !tenantDb || !pool) return;
    let cancelled = false;
    const fetchOnce = () =>
      api.poolStatus(tenant, tenantDb, pool)
        .then(r => { if (!cancelled) setData(r); })
        .catch(e => { if (!cancelled) setError(String(e)); });
    fetchOnce();
    const id = setInterval(fetchOnce, 2000);
    return () => { cancelled = true; clearInterval(id); };
  }, [tenant, tenantDb, pool]);

  /** Build the host the user's clients should target. Substitutes the
    * browser hostname when the server-advertised host is a bind-address
    * like 0.0.0.0. */
  function effectiveHost(host: string): string {
    if (!host || host === '0.0.0.0' || host === '::' || host === '0:0:0:0:0:0:0:0') {
      return typeof window !== 'undefined' ? window.location.hostname : 'localhost';
    }
    return host;
  }

  if (error) return <p style={{ color: 'red' }}>Error: {error}</p>;
  if (!data)  return <p>Loading…</p>;

  const nodesTab = (
    <div className="card">
      <div className="card-title">Nodes</div>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr><th align="left">Node</th><th align="left">Role</th>
              <th align="left">Host</th><th align="left">Port</th>
              <th align="left">Max concurrent</th></tr>
        </thead>
        <tbody>
          {data.nodes.map(n => (
            <tr key={n.nodeId} style={{ borderTop: '1px solid #eee' }}>
              <td>
                <Link
                  to={`/nodes?tenant=${encodeURIComponent(data.tenant)}&node=${encodeURIComponent(n.nodeId)}`}
                  style={{ textDecoration: 'none' }}
                >
                  <code>{n.nodeId}</code>
                </Link>
              </td>
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
                    if (Number.isFinite(max) && max !== n.maxConcurrent) {
                      await api.setMaxConcurrent({ tenant, tenantDb, pool, nodeId: n.nodeId, max });
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

  const connectionsTab = (
    <div className="card">
      <div className="card-title">Client connection</div>
      {!cfg ? (
        <p className="subtle">Loading client config…</p>
      ) : (() => {
        const host = effectiveHost(cfg.flightSqlHost);
        const port = cfg.flightSqlPort;
        const scheme = 'arrow-flight-sql';
        const tlsQuery = cfg.flightSqlTls
          ? 'useEncryption=true&disableCertificateVerification=true'
          : 'useEncryption=false';
        const jdbc =
          `jdbc:${scheme}://${host}:${port}?${tlsQuery}` +
          `&tenant=${encodeURIComponent(tenant)}` +
          `&pool=${encodeURIComponent(pool)}` +
          `&user=<user>&password=<password>`;
        const adbcScheme = cfg.flightSqlTls ? 'grpc+tls' : 'grpc';
        const adbcUri = `${adbcScheme}://${host}:${port}`;
        const odbc =
          `Driver={Arrow Flight SQL ODBC Driver};Host=${host};Port=${port}` +
          `;UseEncryption=${cfg.flightSqlTls ? '1' : '0'}` +
          `;tenant=${tenant};pool=${pool};UID=<user>;PWD=<password>`;
        const tlsKwarg = cfg.flightSqlTls
          ? `, "adbc.flight.sql.client_option.tls_skip_verify": "true"`
          : '';
        const adbcSnippet =
          `adbc_driver_flightsql.connect(uri="${adbcUri}", db_kwargs={` +
          `"username": "<user>", "password": "<password>", ` +
          `"adbc.flight.sql.rpc.call_header.tenant": "${tenant}", ` +
          `"adbc.flight.sql.rpc.call_header.pool": "${pool}"${tlsKwarg}})`;
        return (
          <>
            <p style={{ color: '#888', marginTop: 0 }}>
              Route clients through the FlightSQL edge for capacity-aware,
              role-respecting load balancing. Pass the target as
              {' '}<code>?tenant=…&amp;pool=…</code> URL params; the owning
              database is resolved server-side (pool names are unique per
              tenant). Superusers swap the {' '}<code>tenant</code> param for
              {' '}<code>superuser=true</code> alongside {' '}<code>pool=…</code>;
              the per-statement ACL gate is bypassed and the session can reach
              any catalog. You can also bypass the edge and talk to one
              specific Quack node - see "Direct node URIs" below.
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
    </div>
  );

  const placementTab = (
    <div className="card">
      <div className="card-title">Node placement</div>
      {(!data.cohorts || data.cohorts.length === 0) ? (
        <p style={{ color: '#888' }}>
          No placement plan: every node is scheduled wherever the cluster's
          default scheduler chooses (or, in local mode, as a child process).
          To pin nodes to specific Kubernetes node labels, recreate the pool
          and tick "Pin nodes to Kubernetes node labels" in the New pool form.
        </p>
      ) : (
        <>
          <p style={{ color: '#888', marginTop: 0 }}>
            The pool was created with {data.cohorts.length} cohort
            {data.cohorts.length === 1 ? '' : 's'}. Each cohort's nodes are
            scheduled only on Kubernetes nodes whose labels match every
            entry in its <code>nodeSelector</code>.
          </p>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th align="left">#</th>
                <th align="left">Roles</th>
                <th align="left">nodeSelector</th>
                <th align="left">Tolerations</th>
              </tr>
            </thead>
            <tbody>
              {data.cohorts.map((c, i) => {
                const selectorEntries = Object.entries(c.placement?.nodeSelector ?? {});
                const tolerations = c.placement?.tolerations ?? [];
                return (
                  <tr key={i} style={{ borderTop: '1px solid var(--border)' }}>
                    <td><code>{i + 1}</code></td>
                    <td>
                      {c.distribution.writeonly > 0 && <span>WO×{c.distribution.writeonly} </span>}
                      {c.distribution.readonly  > 0 && <span>RO×{c.distribution.readonly} </span>}
                      {c.distribution.dual      > 0 && <span>Dual×{c.distribution.dual}</span>}
                    </td>
                    <td>
                      {selectorEntries.length === 0
                        ? <span style={{ color: '#888' }}>(none)</span>
                        : selectorEntries.map(([k, v]) => (
                            <div key={k}><code>{k}={v}</code></div>
                          ))}
                    </td>
                    <td>
                      {tolerations.length === 0
                        ? <span style={{ color: '#888' }}>(none)</span>
                        : tolerations.map((t, ti) => (
                            <div key={ti}>
                              <code>
                                {t.key}
                                {t.operator && t.operator !== 'Equal' ? ` ${t.operator}` : ''}
                                {t.value ? `=${t.value}` : ''}
                                {t.effect ? ` :${t.effect}` : ''}
                              </code>
                            </div>
                          ))}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </>
      )}
    </div>
  );

  const storageTab = (
    <div className="card">
      <div className="card-title">Storage</div>
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
    </div>
  );

  return (
    <>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>{data.tenant} / {data.tenantDb} / {data.pool}</h2>
        <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'nowrap', alignItems: 'center' }}>
          {onBack
            ? <button type="button" className="link-button" onClick={onBack}>← Back to pools</button>
            : (
              <Link to={`/tenant/${encodeURIComponent(data.tenant)}`}>
                <button type="button" className="link-button">← Back to pools</button>
              </Link>
            )}
        </div>
      </header>

      <Tabs
        tabs={[
          { id: 'nodes',       label: 'Nodes',       body: nodesTab },
          { id: 'connections', label: 'Connections', body: connectionsTab },
          { id: 'storage',     label: 'Storage',     body: storageTab },
          { id: 'placement',   label: 'Placement',   body: placementTab },
        ]}
      />
    </>
  );
}
