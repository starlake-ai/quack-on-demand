---
id: quickstart
title: Quickstart
---

Boot the gateway, connect a client, and run your first SQL query against a live TPC-H dataset.

## Prerequisites

- **Docker Compose path (Option A):** just Docker. The stack ships its own Postgres, so you need nothing else installed.
- **Jar path (Option B):** **JDK 21 or later** (Arrow Flight requires Java 21+) and a **Postgres 16 or later** reachable at `localhost:5432` (the default) for the control-plane schema and tenant catalogs.
- **Ports 20900 and 31338 free** on the host (admin REST/UI on 20900, FlightSQL edge on 31338), for either path.

For alternative deployment paths (Kubernetes) and the full environment-variable model, see the [Installation guide](/getting-started/install).

## Boot the manager

### Option A: Docker Compose (no prerequisites)

The quickest start: only Docker is required. From the root of a cloned repository:

```bash
./scripts/run-docker-compose.sh
```

This pulls the published `starlakeai/quack-on-demand` image plus a bundled `postgres:16-alpine`, brings the whole stack up, and waits for the manager to become ready - no local JDK or Postgres needed. Stop it with:

```bash
./scripts/stop-docker-compose.sh
```

### Option B: From the jar

If you have JDK 21+ and a reachable Postgres:

```bash
./scripts/run-jar.sh
```

On first run the script downloads the latest release jar from Maven Central, probes Postgres, creates the control-plane database (`qod`), then starts the JVM. When Postgres is unreachable the script warns and aborts; start Postgres first or use the Docker Compose path above. Stop it with `./scripts/stop-jar.sh` (SIGTERM, then SIGKILL after 10 seconds).

### What comes up

Either path brings up the same surface:

- Admin REST + UI on `http://localhost:20900`
- Arrow FlightSQL edge on `localhost:31338` (TLS on, self-signed cert auto-generated under `certs/`)
- Two admin accounts seeded - `admin` and `admin@localhost.local` - both with password `admin`
- Bootstrap tenant `tpch`, tenant-db `tpch1`, pool `sales` created (idempotent on restart)

### Boot flags

The same flags work on both `run-docker-compose.sh` and `run-jar.sh`, and they combine:

| Flag | Effect |
|---|---|
| `LOAD_TPC=N` | Scale factor N seeds the demo: TPC-H sf=N into `acme/acme_tpch`, TPC-DS sf=N into `globex/globex_tpcds`. Also exports `QOD_BOOTSTRAP_YAML` so the JVM imports the bundled demo manifest. (`LOAD_TPC=1` is about 6 M lineitem rows for TPC-H; `LOAD_TPC=10` about 60 M.) The jar path needs the `duckdb` CLI on PATH; the Compose path seeds inside the container. |
| `NUKE=1` | Tear down and wipe local state (Postgres data, parquet under `ducklake/`, `certs/`) before booting. **Irreversible.** |
| `QOD_VERSION=latest-snapshot` | Use the latest snapshot image/jar instead of the latest release. |
| `BUILD=1` | Build from local source (Compose: from the repo Dockerfile; jar: `sbt assembly`) instead of pulling/downloading. |

For a clean, freshly seeded environment in one shot, combine them - this wipes any previous state and boots with a scale-factor-1 demo dataset:

```bash
NUKE=1 LOAD_TPC=1 ./scripts/run-docker-compose.sh
```

`LOAD_TPC=1` seeds two demo tenants: `acme` loaded with TPC-H (8 tables in schema `tpch1`) and `globex` loaded with TPC-DS (24 tables in schema `tpcds1`). The bundled manifest under `src/main/resources/bootstrap-demo.yaml` declares the tenants, roles, groups, and users; see the [Access control model](/operating/rbac-model) for the full ACL matrix.

The jar path takes the same combination:

```bash
NUKE=1 LOAD_TPC=1 ./scripts/run-jar.sh
```

`LOAD_TPC=1` seeds two demo tenants: `acme` loaded with TPC-H (8 tables in schema `tpch1`) and `globex` loaded with TPC-DS (24 tables in schema `tpcds1`). The bundled manifest under `src/main/resources/bootstrap-demo.yaml` declares the tenants, roles, groups, and users; see the [Access control model](/operating/rbac-model) for the full ACL matrix.

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
