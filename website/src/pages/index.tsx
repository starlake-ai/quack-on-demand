import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import styles from './index.module.css';
import FeatureRow from '../components/landing/FeatureRow';
import Terminal from '../components/landing/Terminal';
import Screenshot from '../components/landing/Screenshot';
import SecuritySplit from '../components/landing/SecuritySplit';
import GrowthDiagram from '../components/landing/GrowthDiagram';
import landing from '../components/landing/landing.module.css';

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

function BootBanner() {
  return (
    <code>
      <span className={landing.tDim}>{'$ uvx qod start\n'}</span>
      {'control-plane Postgres: '}
      <span className={landing.tBlue}>jdbc:postgresql://localhost:5432/qod</span>
      {' (user postgres)\n'}
      <span className={landing.tGreen}>
        <span aria-hidden="true">{'==============================================================================\n'}</span>
      </span>
      {' Quack on Demand 0.5.0 is up\n'}
      {'   control plane : '}
      <span className={landing.tBlue}>jdbc:postgresql://localhost:5432/qod</span>
      {'\n'}
      {'   REST API + UI : '}
      <span className={landing.tBlue}>http://localhost:20900</span>
      {'  (UI: '}
      <span className={landing.tBlue}>http://localhost:20900/ui</span>
      {')\n'}
      {'   FlightSQL     : '}
      <span className={landing.tBlue}>grpc+tls://localhost:31338</span>
      {'\n\n'}
      {' Client connection strings (replace <tenant>, <pool>, <user>):\n'}
      {'   JDBC : '}
      <span className={landing.tBlue}>
        {
          'jdbc:arrow-flight-sql://localhost:31338/?tenant=<tenant>&pool=<pool>&user=<user>&useEncryption=true&disableCertificateVerification=true'
        }
      </span>
      {'\n'}
      {'   ADBC : '}
      <span className={landing.tBlue}>uri=grpc+tls://localhost:31338</span>
      {'  (adbc_driver_flightsql)\n'}
      {'   ODBC : '}
      <span className={landing.tBlue}>
        {'Driver={Arrow Flight SQL ODBC Driver};Host=localhost;Port=31338;...'}
      </span>
      {'\n'}
      <span className={landing.tGreen}>
        <span aria-hidden="true">{'================================================================================'}</span>
      </span>
    </code>
  );
}

const ORDER_PRIORITY_QUERY = `qod> SELECT o_orderpriority, count(*) FROM orders GROUP BY 1 ORDER BY 1;
+-----------------+----------+
| o_orderpriority | count(*) |
+-----------------+----------+
| 1-URGENT        |    30044 |
| 2-HIGH          |    30076 |
| 3-MEDIUM        |    29949 |
+-----------------+----------+
3 rows in 0.04s`;

function FeatureRows() {
  return (
    <section className={styles.features}>
      <div className={styles.featuresInner}>
        <h2 className={styles.sectionTitle}>From a single database to a fleet</h2>

        <FeatureRow
          title="From zero to SQL in one command"
          visual={
            <Terminal title="uvx qod start">
              <BootBanner />
            </Terminal>
          }
        >
          <p>
            One command boots the whole platform: manager, FlightSQL endpoint, admin UI, and
            demo data. No cluster, no YAML, no prerequisites beyond Python and a JVM.
          </p>
          <p>
            The boot banner hands you working JDBC, ADBC, and ODBC connection strings. Paste
            one into your tool and you are querying.
          </p>
        </FeatureRow>

        <FeatureRow
          flip
          title="Talk to it from anything that speaks SQL"
          visual={<Terminal title="qod sql">{ORDER_PRIORITY_QUERY}</Terminal>}
        >
          <p>
            One Arrow FlightSQL endpoint serves every client: JDBC and ODBC for BI tools, ADBC
            and Python for notebooks, the qod CLI for your terminal.
          </p>
          <p>
            Statements are classified read or write and routed to the least-loaded node that
            can serve them, with transaction pinning, behind that single endpoint.
          </p>
        </FeatureRow>

        <FeatureRow
          title="A real multi-tenant control plane"
          visual={
            <Screenshot
              src="/img/landing/pools.jpg"
              alt="Manager UI: tenants and pools"
              caption="Tenants, pools, and a hibernated pool one click from waking."
            />
          }
        >
          <p>
            Tenants own databases, users, and pools of DuckDB nodes, isolated by storage layout
            and access control.
          </p>
          <p>
            Idle pools suspend to zero nodes and keep their catalogs; the first query wakes them
            automatically. You pay for compute only while queries run.
          </p>
        </FeatureRow>

        <FeatureRow flip title="Every user sees only their data" visual={<SecuritySplit />}>
          <p>
            The same query, two users, two answers: that is the access-control model working.
          </p>
          <p>
            Tenant isolation at the edge, per-table grants (read, write, DDL), row-level
            security filters, and column masking are all enforced before a statement ever
            reaches a node.
          </p>
        </FeatureRow>

        <FeatureRow
          title="Your data has an undo button"
          visual={
            <Screenshot
              src="/img/landing/timetravel.jpg"
              alt="Snapshot browser with history timeline"
              caption="Every write is a snapshot: browse, tag, diff, restore."
            />
          }
        >
          <p>
            DuckLake gives every table a history: time-travel reads, named tags, undrop for
            deleted tables, and restore that rolls back as a new snapshot with nothing lost.
          </p>
          <p>Mistakes stop being incidents and become queries.</p>
        </FeatureRow>

        <FeatureRow
          flip
          title="See everything"
          visual={
            <Screenshot
              src="/img/landing/usage.jpg"
              alt="Usage dashboard"
              caption="Per-statement history, audit trail, usage trends."
              fallbackSrc="/img/grafana-dashboard.jpg"
            />
          }
        >
          <p>
            Per-statement history with latency and routing, a full audit trail, usage trends per
            tenant, and Prometheus metrics for your dashboards.
          </p>
        </FeatureRow>

        <FeatureRow
          title="Start on a laptop. Grow to a cluster."
          visual={<GrowthDiagram />}
        >
          <p>
            The single-container mode is first-class, not a demo: run it on one box for years if
            that is all you need.
          </p>
          <p>
            When you outgrow it, point the same gateway at your Postgres and object store, then
            move to Kubernetes with pools of pods and HA managers. Your clients never notice:
            same endpoint, same tenants, same SQL.
          </p>
        </FeatureRow>
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
      <FeatureRows />
      <Closing />
    </Layout>
  );
}
