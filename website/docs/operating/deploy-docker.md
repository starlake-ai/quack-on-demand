---
id: deploy-docker
title: Docker deployment
---

## When to use

The Docker Compose stack runs the whole control plane as containers: the manager (REST + admin UI + FlightSQL edge) plus its Postgres metastore, with every DuckDB Quack node spawned as a child process inside the manager container. It is the right choice for:

- A single-host deployment where you want the manager and its Postgres bundled and supervised together, with persistent state on the host.
- A reproducible demo or evaluation host that boots with one command and no JVM, sbt, or Node toolchain on the box.
- Running off a dedicated data disk or NFS mount: the persistent folders are bind-mounted and each one accepts an external host path (see [Data folders](#data-folders-external-bind-mounts) below).

For first-boot-to-first-query, see the [Quickstart](/getting-started/quickstart). For a single manager container pointed at an external Postgres you already run, see the Docker section of [Installation](/getting-started/install). For multi-host or orchestrated deployments, use the [Kubernetes backend](/operating/deploy-kubernetes).

## What comes up

`docker compose up` (or the `scripts/run-docker-compose.sh` wrapper) starts two services:

| Service | Role |
|---|---|
| `postgres` | Control-plane database `qod` (the `qodstate_*` tables) plus every tenant database (`${tenant}_${tenantDb}`, e.g. `tpch_tpch1`) the manager provisions. DuckLake's `__ducklake_*` catalog lives inside each tenant database. |
| `quack` | The manager: REST + admin UI on `:20900`, the FlightSQL edge on `:31338`, and the child Quack node port range. |

Three optional profiles add services only when you ask for them: `seaweedfs` (an in-network S3 object store), and `observability` (Prometheus + Grafana). See [Profiles](#optional-profiles) below.

## Boot it

The `scripts/run-docker-compose.sh` wrapper handles port preflight, profile auto-detection, optional demo seeding, and readiness waiting. Two modes, selected by `BUILD`:

```bash
# Pull the published image starlakeai/quack-on-demand and start (default).
./scripts/run-docker-compose.sh

# Pin a version.
QOD_VERSION=0.3.2 ./scripts/run-docker-compose.sh

# Build the image from this repo's Dockerfile instead of pulling.
BUILD=1 ./scripts/run-docker-compose.sh
```

Settings come from a `.env` file (copied from `.env.example` on first run). Edit it before booting, or pass the same keys as environment variables. The admin UI is at `http://localhost:20900/ui/`; log in with `ADMIN_USERNAME` / `ADMIN_PASSWORD` (defaults `admin` / `admin`).

Plain `docker compose up --build` also works if you do not want the wrapper's preflight and seeding.

## Data folders (external bind mounts)

All persistent state is bind-mounted onto the host. Each mount defaults to a repo-relative folder, but every host path accepts an **external** override (an absolute path such as `/data/qod/pgdata`, or a relative one) so the data can live off the repo on a dedicated disk or NFS mount. Set the override in `.env` or as an environment variable:

| Host path (default) | Container path | Override | Holds |
|---|---|---|---|
| `./pgdata` | `/var/lib/postgresql/data` | `PGDATA_DIR` | Postgres data: the `qod` control plane plus every tenant database. |
| `./ducklake` | `/app/ducklake` | `DUCKLAKE_DIR` | DuckLake Parquet data files, one subdirectory per tenant database. |
| `./certs` | `/app/certs` | `CERTS_DIR` | Self-signed FlightSQL TLS cert and key, auto-generated on first boot. |
| `./seaweedfs` | `/data` | `SEAWEEDFS_DIR` | SeaweedFS object data (only with the `seaweedfs` profile). |
| `./seaweedfs-config` | `/etc/seaweedfs` | `SEAWEEDFS_CONFIG_DIR` | SeaweedFS S3 credentials (only with the `seaweedfs` profile). |

For example, to keep the metastore and DuckLake data under `/data`:

```bash
PGDATA_DIR=/data/qod/pgdata DUCKLAKE_DIR=/data/qod/ducklake \
  ./scripts/run-docker-compose.sh
```

When DuckLake writes to an S3-compatible bucket instead of the filesystem (`QOD_DUCKLAKE_DATA_PATH=s3://...`), the `./ducklake` mount is unused; the catalog persists the `s3://` URL and every Quack node resolves it identically regardless of host. See [Object storage](#object-storage-s3-compatible).

:::caution
`NUKE=1 ./scripts/run-docker-compose.sh` only wipes the repo-relative defaults (`./pgdata`, `./ducklake`, `./certs`, `./seaweedfs`, `./seaweedfs-config`). External folders you point these overrides at are **not** auto-wiped; remove them by hand.
:::

Do not mix a Docker run and a native-jar run against the same catalog database. DuckLake records the absolute data path in Postgres metadata: inside the container it is `/app/ducklake/<db>`, natively it is `<host-cwd>/ducklake/<db>`. Use a different control-plane database name per mode, or wipe the data between switches.

## Ports

The wrapper exposes these host ports (override in `.env`):

| Env var | Default | Purpose |
|---|---|---|
| `MANAGER_PORT` | `20900` | REST + admin UI |
| `EDGE_PORT` | `31338` | FlightSQL edge |
| `PG_PORT` | `5432` | Postgres (auto-bumped to `15432` when the host port is busy) |
| `QUACK_MIN_PORT` / `QUACK_MAX_PORT` | `21900` / `22500` | Child Quack node range |

## Fresh start and seeding

```bash
# Tear down the stack and wipe the repo-relative state folders, then boot clean.
NUKE=1 ./scripts/run-docker-compose.sh

# Seed the demo tenants before the manager is ready.
# Numeric value = scale factor for TPC-H, TPC-DS, and the SSB star schema.
# (SF=1 is roughly 6M lineitem rows for TPC-H.)
LOAD_TPC=1 ./scripts/run-docker-compose.sh

# Both flags combine.
NUKE=1 LOAD_TPC=1 ./scripts/run-docker-compose.sh
```

`LOAD_TPC=1` seeds two demo tenants inside the container: `acme` loaded with TPC-H (8 tables in schema `tpch1`, database `acme_tpch`) plus the SSB star schema derived from it (5 tables in schema `ssb1`, same database), and `globex` loaded with TPC-DS (24 tables in schema `tpcds1`, database `globex_tpcds`). Use `LOAD_TPCH` / `LOAD_TPCDS` / `LOAD_SSB` to seed each independently. The bundled manifest `bootstrap-demo.yaml` declares the tenants, pools, roles, groups, and users; `QOD_BOOTSTRAP_YAML=classpath:bootstrap-demo.yaml` is injected into `.env` automatically so the JVM imports it on startup.

You can also seed a running stack directly:

```bash
docker compose exec -e PG_HOST=postgres -e DB_NAME=acme_tpch -e SCHEMA_NAME=tpch1 \
  -e DATA_PATH=/app/ducklake/acme_tpch -e SF=1 quack /app/scripts/load-tpch-dbgen.sh
docker compose exec -e PG_HOST=postgres -e DB_NAME=globex_tpcds -e SCHEMA_NAME=tpcds1 \
  -e DATA_PATH=/app/ducklake/globex_tpcds -e SF=1 quack /app/scripts/load-tpcds-dbgen.sh
```

## Optional profiles

Activate with `--profile` on `docker compose`, or via `PROFILES=...` on the wrapper.

### Object storage (S3-compatible)

Set `QOD_DUCKLAKE_DATA_PATH` to an `s3://` URL and fill in the `QOD_S3_*` credentials to write DuckLake Parquet to a bucket instead of the `./ducklake` bind mount. Two options:

- **Bundled SeaweedFS** (no external dependency). Bring it up with `docker compose --profile seaweedfs up -d`; the wrapper auto-activates this profile when `QOD_S3_ENDPOINT` points at `seaweedfs:8333`. Pair with `QOD_DUCKLAKE_DATA_PATH=s3://${S3_BUCKET}/<db>`. The Filer UI on `:8888` doubles as a file browser.
- **External S3** (AWS, R2, MinIO, GCS HMAC). Leave the profile off and set the bucket plus credentials; omit `QOD_S3_ENDPOINT` to use the AWS default.

The `.env.example` file has ready-to-uncomment blocks for both. The `spawn-quack-node.sh` and TPC-H loader detect the `s3://` scheme, install DuckDB's `httpfs`, and `CREATE SECRET` so every node reads and writes Parquet against the bucket.

### Observability

```bash
docker compose --profile observability up -d
# or: PROFILES=observability ./scripts/run-docker-compose.sh
```

Prometheus scrapes the in-network `quack` service; Grafana serves a pre-provisioned dashboard on `:3000` (anonymous admin, so do not expose it to a public network as-is). See the Resilience and metrics references for what is scraped.

## Corporate proxy

When the host needs an HTTP proxy to reach the public internet (for DuckDB extension downloads from `extensions.duckdb.org`), set `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` in `.env`. Compose forwards them into both the build and the runtime, and the manager translates them into DuckDB's `SET http_proxy` before `INSTALL`. The in-network hostnames (`postgres`, `seaweedfs`) are always added to `NO_PROXY` so intra-stack traffic and S3 PUTs bypass the proxy. Note that `docker pull` itself reads the Docker daemon's proxy config, not these variables.

## Before exposing beyond localhost

The defaults are tuned for a fast local smoke test. Change these before any non-local exposure:

| Setting | Env var | Insecure default |
|---|---|---|
| Admin password | `ADMIN_PASSWORD` | `admin` |
| Postgres password | `PG_PASSWORD` | `azizam` |
| REST API key | `API_KEY` | unset (open API) |
| FlightSQL TLS | `TLS` | `false` in compose |

Enable edge TLS with `TLS=true` (clients then connect with `grpc+tls://...`); see [TLS](/operating/tls). For the full set of tunable keys, see the [Configuration reference](/reference/configuration).
