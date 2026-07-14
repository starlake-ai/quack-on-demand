import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import styles from './index.module.css';

const FEATURES = [
  {
    k: '01',
    title: 'Multi-tenant by design',
    body: 'Tenants own databases and pools of DuckDB nodes. Each tenant gets its own catalog, users, and auth provider, isolated by storage layout and by access control.',
  },
  {
    k: '02',
    title: 'Access control on every query',
    body: 'Authentication via database, JWT, or OIDC, plus role-based, per-statement table permissions. A statement is authorized before it ever reaches a node.',
  },
  {
    k: '03',
    title: 'Horizontal scale, one endpoint',
    body: 'Statements are classified read or write and routed to the least-loaded node that can serve them, with transaction pinning and retry-on-failure, all behind a single Flight SQL endpoint.',
  },
  {
    k: '04',
    title: 'DuckLake catalogs',
    body: 'Nodes read and write Parquet through shared DuckLake catalogs on a filesystem or S3-compatible object store, so a pool presents one consistent view.',
  },
  {
    k: '05',
    title: 'Federation, same ACL',
    body: 'Attach external catalogs, Postgres, S3, Iceberg, anything DuckDB can ATTACH, under the same SQL surface and the same role-based access control.',
  },
  {
    k: '06',
    title: 'Built to operate',
    body: 'Prometheus and cloud metrics, a config manifest for backup and restore, and an admin UI for tenants, pools, users, and grants.',
  },
];

function Hero() {
  const mark = useBaseUrl('/img/mark-dark.svg');
  return (
    <header className={styles.hero}>
      <div className={styles.heroGrid}>
        <div className={styles.heroCopy}>
          <span className={styles.kicker}>Arrow Flight SQL gateway</span>
          <h1 className={styles.title}>
            Autoscale DuckDB fleets
            <br />
            <span className={styles.titleAccent}>on demand.</span>
          </h1>
          <p className={styles.subtitle}>
            From a single Docker container on one node to fleets of DuckDB Quack
            nodes on your own Kubernetes. Per-tenant isolation, fine-grained ACLs,
            federated queries. Query it from any ODBC/JDBC/ADBC client. Works with
            any ETL.
          </p>
          <div className={styles.ctaRow}>
            <Link className={styles.ctaPrimary} to="/getting-started/quickstart">
              Get started
            </Link>
            <Link className={styles.ctaGhost} to="/introduction">
              Read the docs
            </Link>
            <Link
              className={styles.ctaGhost}
              to="https://github.com/starlake-ai/quack-on-demand"
            >
              GitHub
            </Link>
          </div>
        </div>

        <div className={styles.terminalWrap}>
          <div className={styles.terminal}>
            <div className={styles.termBar}>
              <span className={styles.dot} data-c="r" />
              <span className={styles.dot} data-c="y" />
              <span className={styles.dot} data-c="g" />
              <span className={styles.termTitle}>client.py</span>
            </div>
            <pre className={styles.termBody}>
              <code>
                <span className={styles.cComment}>{'# One endpoint, JDBC / ADBC / ODBC / PyArrow\n'}</span>
                {'conn = flight_sql.connect(\n'}
                {'    '}<span className={styles.cStr}>"grpc+tls://gateway:31338"</span>{',\n'}
                {'    db_kwargs={\n'}
                {'      '}<span className={styles.cStr}>"username"</span>{': '}<span className={styles.cStr}>"alice"</span>{', '}<span className={styles.cStr}>"password"</span>{': '}<span className={styles.cStr}>"•••"</span>{',\n'}
                {'      '}<span className={styles.cKey}>tenant</span>{': '}<span className={styles.cStr}>"acme"</span>{', '}<span className={styles.cKey}>pool</span>{': '}<span className={styles.cStr}>"analytics"</span>{'})\n\n'}
                <span className={styles.cComment}>{'# routed to the least-loaded node for the tenant\n'}</span>
                {'cur = conn.cursor()\n'}
                {'cur.execute('}<span className={styles.cStr}>"SELECT count(*) FROM orders"</span>{')\n'}
                {'cur.fetchone()\n'}
                <span className={styles.cOut}>{'(1500000,)'}</span>
              </code>
            </pre>
          </div>
          <img className={styles.mark} src={mark} alt="" aria-hidden="true" />
        </div>
      </div>
    </header>
  );
}

function Features() {
  return (
    <section className={styles.features}>
      <div className={styles.featuresInner}>
        <h2 className={styles.sectionTitle}>What the gateway adds to DuckDB</h2>
        <div className={styles.grid}>
          {FEATURES.map((f) => (
            <div key={f.k} className={styles.card}>
              <span className={styles.cardNum}>{f.k}</span>
              <h3 className={styles.cardTitle}>{f.title}</h3>
              <p className={styles.cardBody}>{f.body}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function Closing() {
  return (
    <section className={styles.closing}>
      <div className={styles.closingInner}>
        <div>
          <h2 className={styles.closingTitle}>Reach for it when sharing matters.</h2>
          <p className={styles.closingBody}>
            Many users on shared DuckDB/DuckLake data, access control on every query,
            horizontal scale, tenant isolation, or federation. Querying one local file?
            Use DuckDB directly.
          </p>
        </div>
        <div className={styles.closingCtas}>
          <Link className={styles.ctaPrimary} to="/getting-started/quickstart">
            Get started
          </Link>
          <Link className={styles.ctaGhost} to="https://discord.gg/xHj9D6Rebp">
            Join Discord
          </Link>
        </div>
      </div>
    </section>
  );
}

export default function Home(): React.ReactElement {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout
      title="Multi-tenant Arrow Flight SQL gateway for DuckDB and DuckLake"
      description={siteConfig.tagline}
    >
      <Hero />
      <Features />
      <Closing />
    </Layout>
  );
}
