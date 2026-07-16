---
id: quickstart
title: Quickstart
---

Boot the gateway, connect a client, and run your first SQL query against a live TPC-H dataset.

## Demo mode (self-contained, no Postgres)

The fastest way to try Quack on Demand end to end. `demo` boots a fully seeded instance against an **embedded, ephemeral Postgres** - so unlike every other path below, it needs **no external Postgres and no Docker**.

With [uv](https://docs.astral.sh/uv/) installed there are no other prerequisites - the `qod` launcher downloads the release jar (verified against the published sha256), a Java runtime if none is on your machine (it asks first, cached), and the ABI-pinned `duckdb` CLI, all under your user cache directory. The same command works on macOS, Linux, and Windows:

```bash
uvx qod demo
```

`pip install qod && qod demo` is equivalent. Two more routes to the same demo:

```bash
# Docker (trivial on Linux; on Mac/Windows requires Docker Desktop or a
# drop-in replacement like Podman, Colima, or OrbStack)
docker run --rm -p 20900:20900 -p 31338:31338 starlakeai/quack-on-demand demo

# from a source checkout or a downloaded assembly jar
# (needs a JDK and the duckdb CLI on PATH):
java -Darrow.allocation.manager.type=Unsafe \
  -jar distrib/quack-on-demand-assembly-*.jar demo
```

It starts an embedded throwaway Postgres, applies the Liquibase schema, seeds the minimal demo (tenant `acme`, tenant-db `acme_tpch`, schema `tpch1`) with a small TPC-H dataset, boots the manager REST API on `:20900` and the FlightSQL edge on `:31338` (TLS on with an auto-generated self-signed cert; clients skip verification), and prints a connect snippet. All state lives under `/tmp/qod-demo` and is removed on exit (Ctrl-C).

The seeded principals demonstrate row + column security:

- `alice` / `demo-alice` (analyst) - `customer.c_phone` is masked to `***` and only `c_mktsegment = 'BUILDING'` rows are visible
- `acme-admin` / `demo-acme-admin` - full, unmasked data
- a table `alice` has no grant on - denied

:::warning
Demo mode is insecure by design (self-signed TLS, open REST, demo credentials, ephemeral catalog). It is for evaluation only - never expose it or use it in production. For a durable install, use the paths below with your own Postgres.
:::

## Prerequisites

- **Native path (Option A):** a **Postgres 16 or later** reachable at `localhost:5432` (the default) for the control-plane schema and tenant catalogs. Everything else (Java, DuckDB) is auto-provisioned by `qod start`.
- **Docker Compose path (Option B):** just Docker. The stack ships its own Postgres, so you need nothing else installed.
- **Ports 20900 and 31338 free** on the host (admin REST/UI on 20900, FlightSQL edge on 31338), for either path.

For alternative deployment paths (Kubernetes) and the full environment-variable model, see the [Installation guide](/getting-started/install).

## Boot the manager

### Option A: Native (`qod start`)

If you have a reachable Postgres:

```bash
uv tool install qod    # or: pip install qod
qod start
```

On first run `qod start` downloads the release jar from GitHub Releases (sha256-verified), self-installs the pinned DuckDB, creates the control-plane database (`qod`) when `psql` is available, then starts the JVM. Stop it with `qod stop` (SIGTERM, then SIGKILL after 10 seconds).

On **Windows** (experimental), the same `pip install qod && qod start` works natively; see [Run on Windows](/getting-started/install#run-on-windows) for the current gaps (no `qod stop`, no `LOAD_*` seeding).

### Option B: Docker Compose (no prerequisites)

Only Docker is required - the stack ships its own Postgres. From the root of a cloned repository:

```bash
./scripts/run-docker-compose.sh
```

This pulls the published `starlakeai/quack-on-demand` image plus a bundled `postgres:16-alpine`, brings the whole stack up, and waits for the manager to become ready - no local JDK or Postgres needed. Stop it with:

```bash
./scripts/stop-docker-compose.sh
```

### What comes up

Either path brings up the same surface:

- Admin REST + UI on `http://localhost:20900`
- Arrow FlightSQL edge on `localhost:31338` (TLS on, self-signed cert auto-generated under `certs/`)
- Two admin accounts seeded - `admin` and `admin@localhost.local` - both with password `admin`
- Two bootstrap tenants seeded from `src/main/resources/bootstrap-demo.yaml`: `acme` (tenant-db `acme_tpch`, pools `bi` + `etl`) and `globex` (tenant-db `globex_tpcds`, pool `bi`). Idempotent on restart.

### Boot flags

The same flags work on both `qod start` and `run-docker-compose.sh`, and they combine:

| Flag | Effect |
|---|---|
| `LOAD_TPCH=N` | Seeds TPC-H sf=N into `acme/acme_tpch` (8 tables in schema `tpch1`). The native path runs the loader on the host (DuckDB CLI + `libduckdb` are auto-installed by `qod start` on first boot, see [Run from Linux/MacOS](/getting-started/install#run-from-linuxmacos)); the Compose path seeds inside the container. `LOAD_TPCH=1` is ~6 M lineitem rows; SF=10 is ~60 M. Any seed flag being set also exports `QOD_BOOTSTRAP_YAML` so the JVM imports the bundled demo manifest. |
| `LOAD_TPCDS=N` | Seeds TPC-DS sf=N into `globex/globex_tpcds` (24 tables in schema `tpcds1`). Slower than TPC-H at the same SF (SF=10 ≈ several minutes; SF=100+ spills to disk). |
| `LOAD_SSB=N` | Seeds the SSB (Star Schema Benchmark) star schema at sf=N: 5 tables (`lineorder`, `customer`, `supplier`, `part`, `dwdate`) derived from TPC-H dbgen into schema `ssb1` of `acme/acme_tpch`, next to the TPC-H tables and served by the same acme pools. |
| `LOAD_TPC=N` | Legacy shortcut: equivalent to setting `LOAD_TPCH=N`, `LOAD_TPCDS=N`, and `LOAD_SSB=N`. Explicit per-bench vars override it. |
| `DEMO=full\|minimal` | Bootstrap profile for the demo seed. `full` (the default) imports the two-tenant `acme` + `globex` manifest and exercises multi-tenancy, multiple pools, and federation; `minimal` imports `bootstrap-demo-minimal.yaml` instead - the shape for fronting a single DuckDB/DuckLake database: one tenant (`acme`), one pool (`bi`), one dual node serving both reads and writes, plus the analyst RLS/CLS demo. Only consulted when a `LOAD_*` flag is set and `QOD_BOOTSTRAP_YAML` is unset. `DEMO=minimal` with `LOAD_TPCDS` warns and skips the TPC-DS loader (no `globex` tenant in this profile). |
| `NUKE=1` | Tear down and wipe local state (Postgres data, parquet under `ducklake/`, `certs/`) before booting. **Irreversible.** |
| `QOD_VERSION=latest-snapshot` | Use the latest snapshot image/jar instead of the latest release. |
| Build from source | Compose: `BUILD=1` builds the image from the repo Dockerfile instead of pulling. Native: `QOD_VERSION=BUILD` runs `sbt assembly` first, and `QOD_VERSION=LOCAL` reuses the newest jar already in `distrib/` without rebuilding. |

For a clean, freshly seeded environment in one shot, combine them. This wipes any previous state and boots with both demo datasets at scale-factor 1:

```bash
NUKE=1 LOAD_TPCH=1 LOAD_TPCDS=1 ./scripts/run-docker-compose.sh
```

Any seed flag (or the legacy `LOAD_TPC=1` shortcut, which enables all three) imports the bundled manifest under `src/main/resources/bootstrap-demo.yaml`, which declares the tenants, roles, groups, and users for both `acme` and `globex`; see the [Access control model](/operating/rbac-model) for the full ACL matrix.

To run the gateway in front of a single DuckDB instance instead, `DEMO=minimal` swaps in `bootstrap-demo-minimal.yaml`: one tenant (`acme`), one pool (`bi`), and one dual node serving both reads and writes, plus the analyst RLS/CLS demo. Bootstrap only imports into a fresh control plane, so switch profiles with `NUKE=1`:

```bash
NUKE=1 DEMO=minimal LOAD_TPCH=1 qod start
```

Pick one benchmark to keep boot snappy:

```bash
NUKE=1 LOAD_TPCH=1 ./scripts/run-docker-compose.sh        # TPC-H only, ~10 s seed
NUKE=1 LOAD_TPCDS=10 ./scripts/run-docker-compose.sh      # TPC-DS only, SF=10
```

The native path takes the same flags:

```bash
NUKE=1 LOAD_TPCH=1 LOAD_TPCDS=1 qod start
```

## Open the admin console

Browse to `http://localhost:20900` and log in with:

- Username: `admin`
- Password: `admin`

The Tenants page shows the two bootstrap tenants, `acme` and `globex`. Opening either reveals the Databases, Pools, and Auth provider tabs. The Nodes page shows the live cluster dashboard and the recent-statements history.

## Run your first query

### DBeaver / JDBC

Install the Apache Arrow Flight SQL JDBC driver (`org.apache.arrow:flight-sql-jdbc-driver`, available on Maven Central). In DBeaver, create a new connection and paste this URL directly into the JDBC URL field:

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=admin&password=admin&tenant=acme&pool=bi&superuser=true
```

Set the driver class to `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`.

The `disableCertificateVerification=true` parameter is required because the gateway starts with an auto-generated self-signed certificate (see the TLS guide for how to supply a CA-signed cert and remove that flag).

The `tenant=acme&pool=bi` parameters are routing headers: the FlightSQL edge requires both to resolve which pool services the connection. Swap in `tenant=globex&pool=bi` to drive the TPC-DS demo instead.

The `superuser=true` parameter is required when login as admin/admin.

### Python (ADBC)

Install the driver:

```bash
pip install --user adbc_driver_flightsql adbc_driver_manager
```

Run the bundled `tpch-load-test` as a one-shot client (defaults to the TPC-H workload against `acme`'s `tpch1` schema):

```bash
python3 ./scripts/tpch-load-test/tpch-load-test.py \
  --url grpc+tls://localhost:31338 --insecure \
  --user admin --password admin --superuser \
  --tenant acme --pool bi \
  -w 1 -i 1 --warmup 0
```

`--tenant` and `--pool` are required (or set `LT_TENANT` / `LT_POOL`); the demo bootstrap creates tenants `acme` and `globex`, each with a pool named `bi`. The `--insecure` flag skips certificate verification for the auto-generated self-signed cert. The `--superuser` flag adds the `superuser=true` gRPC header so the bootstrap `admin` user (which lives in `qodstate_user` with `tenant IS NULL`) authenticates against the system realm; drop it when running as a tenant-scoped user.

:::note Windows
Run the Python ADBC client from a neutral working directory (e.g. your home folder), **not** the repository root. Windows includes the current directory in the DLL search path, so a native `arrow`/`duckdb` DLL sitting in the checkout can get loaded into the client process ahead of the one shipped with `adbc_driver_flightsql` and crash it. The runner itself already forces UTF-8 output so the results table renders in a `cp1252` console.
:::

To switch workload, pass `--workload tpcds` (or set `LT_WORKLOAD=tpcds`). The runner ships two curated benchmarks:

| Workload | Default schema | Default tenant/pool wiring | Tables touched |
|---|---|---|---|
| `tpch` (default) | `tpch1` | `acme` / `bi` (demo bootstrap) | `lineitem`, `customer`, `orders`, `nation`, `region`, `supplier`, `part` |
| `tpcds` | `tpcds1` | `globex` / `bi` (demo bootstrap) | the 24 TPC-DS tables seeded by `scripts/load-tpcds-dbgen.sh` |

Each workload cycles a handful of representative queries (per-group aggregation, multi-way joins, top-N, window functions, date-range filters). The TPC-DS workload requires the target tenant-db to be seeded first:

```bash
SF=1 ./scripts/load-tpcds-dbgen.sh                                            # seeds globex_tpcds.tpcds1
python3 ./scripts/tpch-load-test/tpch-load-test.py --workload tpcds --tenant globex --pool bi \
  --user admin --password admin --superuser --insecure -w 4 -i 50
```

Override the schema with `--schema tpcds10` when you've seeded at a larger scale factor.

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
