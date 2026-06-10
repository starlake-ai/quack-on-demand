---
id: quickstart
title: Quickstart
---

Boot the gateway, connect a client, and run your first SQL query against a live TPC-H dataset.

## Prerequisites

- **JDK 21 or later.** The gateway uses Arrow Flight, which requires Java 21+.
- **Postgres 16 or later,** reachable at `localhost:5432` (the default). The control-plane schema and all tenant catalogs live here.
- **Ports 20900 and 31338 free** on the host. The manager binds the admin REST/UI on 20900 and the FlightSQL edge on 31338.

For installation details and alternative deployment paths (Docker, Kubernetes), see the [Installation guide](/getting-started/install).

## Boot the manager

From the root of a cloned repository:

```bash
./scripts/run-jar.sh
```

On first run the script downloads the latest release jar from Maven Central, probes Postgres, creates the control-plane database (`qod`), then starts the JVM. When Postgres is unreachable the script warns and aborts; start Postgres first or set `QOD_STATE_STORAGE=file` for a stateless local run.

What comes up after a successful boot:

- Admin REST + UI on `http://localhost:20900`
- Arrow FlightSQL edge on `localhost:31338` (TLS on, self-signed cert auto-generated under `certs/`)
- Two admin accounts are seeded - `admin` and `admin@localhost.local` - both with password `admin`
- Bootstrap tenant `tpch`, tenant-db `tpch1`, pool `sales` created (idempotent on restart)

**Useful boot flags:**

| Flag | Effect |
|---|---|
| `BUILD=1 ./scripts/run-jar.sh` | Build from local source with `sbt assembly` instead of downloading |
| `LOAD_TPCH=N ./scripts/run-jar.sh` | Seed TPC-H at scale factor N before the JVM starts (e.g. `LOAD_TPCH=1` is about 6 M lineitem rows, `LOAD_TPCH=10` is about 60 M). Requires the `duckdb` CLI on PATH. |
| `QOD_VERSION=latest-snapshot ./scripts/run-jar.sh` | Download the latest snapshot jar instead of the latest release |
| `NUKE=1 ./scripts/run-jar.sh` | Wipe local Postgres DB, parquet data, and certs before booting. **Irreversible.** |

To stop the manager and all child Quack nodes:

```bash
./scripts/stop-jar.sh
```

The stop script sends SIGTERM for a graceful shutdown and escalates to SIGKILL after 10 seconds if the process is still listening.

## Open the admin console

Browse to `http://localhost:20900` and log in with:

- Username: `admin`
- Password: `admin`

The Tenants page shows the bootstrap tenant `tpch`. Opening it reveals the Databases, Pools, and Auth provider tabs. The Nodes page shows the live cluster dashboard and the recent-statements history.

## Run your first query

### DBeaver / JDBC

Install the Apache Arrow Flight SQL JDBC driver (`org.apache.arrow:flight-sql-jdbc-driver`, available on Maven Central). In DBeaver, create a new connection and paste this URL directly into the JDBC URL field:

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=admin&password=admin&tenant=tpch&pool=sales
```

Set the driver class to `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`.

The `disableCertificateVerification=true` parameter is required because the gateway starts with an auto-generated self-signed certificate (see the TLS guide for how to supply a CA-signed cert and remove that flag).

The `tenant=tpch&pool=sales` parameters are routing headers: the FlightSQL edge requires both to resolve which pool services the connection.

### Python (ADBC)

Install the driver:

```bash
pip install --user adbc_driver_flightsql adbc_driver_manager
```

Run the bundled load tester as a one-shot client:

```bash
python3 ./scripts/loadtest/loadtest.py \
  --url grpc+tls://localhost:31338 --insecure \
  --user admin --password admin \
  -w 1 -i 1 --warmup 0
```

The `--insecure` flag skips certificate verification for the auto-generated self-signed cert. The tester defaults to `tenant=tpch` and `pool=sales`, matching the bootstrap configuration.

### Example SQL

Once connected, run:

```sql
SELECT count(*) FROM tpch1.customer;
```

Tables live under the `tpch1` schema inside the `tpch` tenant's database. The gateway auto-qualifies unqualified table names to the pool's default database and schema, so `customer` and `tpch1.customer` are equivalent once the session is scoped to the `tpch`/`sales` pool.

## Next steps

- [Installation](/getting-started/install) - native-jar setup, Docker Compose, Kubernetes, and environment variable reference.
- [Configuration reference](/reference/configuration) - every `QOD_*` / `PROXY_*` environment variable with its default and description.
- REST API reference - the interactive API explorer is linked in the top navigation of the admin UI.
