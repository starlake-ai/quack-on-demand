---
id: install
title: Installation
---

Quack on Demand ships as a single uber-jar. Three installation paths are supported: download from Maven Central (the recommended default), build from source, and Docker.

## Prerequisites

- **JDK 21 or later.** The assembly jar embeds an `Add-Opens` manifest attribute (JEP 261) so no extra `--add-opens` flags are required when running `java -jar`.
- **Postgres 16 or later.** The control plane stores all state in a dedicated database (default name `qod`) on `localhost:5432`. The `run-jar.sh` startup script probes Postgres before the JVM boots and refuses to proceed against a server older than PG 16.

  If you do not have a local Postgres instance, the quickest path is:

  ```bash
  docker run -d --name qod-pg \
    -e POSTGRES_PASSWORD=azizam \
    -p 5432:5432 \
    postgres:16-alpine
  ```

## Run from Maven Central

`scripts/run-jar.sh` handles everything: it resolves the latest release from Maven Central (`ai.starlake:quack-on-demand_3:<version>`), caches the jar under `~/.cache/quack-on-demand/`, probes Postgres, creates the control-plane database if it does not exist, and starts the JVM with the Arrow allocator pinned.

```bash
# Latest release (default)
./scripts/run-jar.sh

# Pinned release
QOD_VERSION=0.3.2 ./scripts/run-jar.sh

# Latest snapshot from Central snapshots
QOD_VERSION=latest-snapshot ./scripts/run-jar.sh
```

On first run against a freshly-provisioned Postgres, the script creates the `qod` database and Liquibase applies the control-plane schema. The admin UI is at `http://localhost:20900/ui/`; log in as `admin` with password `admin` (change this before any exposure beyond localhost -- see [Configuration model](#configuration-model) below).

The FlightSQL edge listens on `localhost:31338` with TLS on by default (self-signed cert auto-generated under `certs/`).

To stop the manager:

```bash
./scripts/stop-jar.sh
```

### Seeding the demo dataset for a quick smoke test

Pass `LOAD_TPCH=1` and/or `LOAD_TPCDS=1` to seed each benchmark independently before the JVM starts. Requires the `duckdb` CLI on PATH.

```bash
LOAD_TPCH=1 ./scripts/run-jar.sh                          # TPC-H only (acme, ~10 s)
LOAD_TPCDS=1 ./scripts/run-jar.sh                         # TPC-DS only (globex, ~30 s)
LOAD_TPCH=1 LOAD_TPCDS=1 ./scripts/run-jar.sh             # both
LOAD_TPC=1 ./scripts/run-jar.sh                           # legacy shortcut for both
```

Either flag seeds the matching demo tenant: `acme` loaded with TPC-H (8 tables in schema `tpch1`) or `globex` loaded with TPC-DS (24 tables in schema `tpcds1`). The bundled manifest under `src/main/resources/bootstrap-demo.yaml` declares the tenants, roles, groups, and users; see the [Access control model](/operating/rbac-model) for the full ACL matrix.

For a completely fresh start (drops the control-plane DB and wipes local state directories before booting):

```bash
NUKE=1 LOAD_TPCH=1 LOAD_TPCDS=1 ./scripts/run-jar.sh
```

## Build from source

`BUILD=1` tells `run-jar.sh` to call `sbt assembly` first instead of downloading:

```bash
BUILD=1 ./scripts/run-jar.sh
```

To build the jar separately and run it manually:

```bash
sbt assembly
java -jar distrib/quack-on-demand-assembly-*.jar
```

The assembly output lands in `distrib/quack-on-demand-assembly-<version>.jar`. The jar carries the `Add-Opens` manifest attribute covering `java.base/java.nio` and `java.base/sun.nio.ch`, which Arrow Flight's unsafe allocator needs on Java 17+. No extra `--add-opens` flags are needed on the command line.

Note: `sbt compile` and `sbt assembly` also run `npm ci` and `npm run build` inside the `ui/` directory as a resource generator, so the React admin UI is bundled automatically. There is no separate UI build step.

## Docker

The published image is `starlakeai/quack-on-demand`. Use `scripts/run-docker.sh` to pull and run it against an external Postgres:

```bash
PG_HOST=db.internal PG_PASSWORD=*** ./scripts/run-docker.sh
```

The script requires `PG_HOST` and `PG_PASSWORD`. Key options:

| Variable | Default | Description |
|---|---|---|
| `QOD_VERSION` | `latest` | Image tag to pull |
| `PG_PORT` | `5432` | Postgres port |
| `PG_USER` | `postgres` | Postgres user |
| `MANAGER_PORT` | `20900` | Host port for REST + admin UI |
| `EDGE_PORT` | `31338` | Host port for FlightSQL edge |
| `TLS` | `false` | Enable FlightSQL TLS inside the container |
| `ADMIN_PASSWORD` | `admin` | Admin login password (rotate before production) |
| `API_KEY` | unset | REST `X-API-Key` static key |
| `BUILD` | `0` | Set to `1` to build the image from the local Dockerfile instead of pulling |

To pin a version or use the latest snapshot:

```bash
QOD_VERSION=0.3.2 PG_HOST=... PG_PASSWORD=*** ./scripts/run-docker.sh
QOD_VERSION=latest-snapshot PG_HOST=... PG_PASSWORD=*** ./scripts/run-docker.sh
```

**Note:** do not mix Docker and native-jar runs against the same catalog DB. DuckLake records the absolute data path in Postgres metadata. Inside the container that path is `/app/ducklake/<db>`; natively it is `<host-cwd>/ducklake/<db>`. Use a different `PG_DBNAME` (or `QOD_PG_DBNAME`) per mode, or wipe the data directory between switches.

## Configuration model

Every scalar in `application.conf` accepts a matching `QOD_*` environment variable override (FlightSQL edge keys use the `PROXY_*` prefix). The bundled `application.conf` is baked into the jar at build time, so you should use environment variables rather than editing it directly.

**Defaults you must change before production:**

| Setting | Env var | Insecure default |
|---|---|---|
| Admin password | `QOD_ADMIN_PASSWORD` | `admin` |
| Postgres password | `QOD_PG_PASSWORD` | `azizam` |
| REST API key | `QOD_API_KEY` | unset (open API) |

Setting `QOD_API_KEY` to a secret string requires that value in the `X-API-Key` header on every REST call. Leaving it unset means the REST API is accessible without authentication (acceptable only on localhost or behind a trusted network boundary).

Other commonly used variables:

| Setting | Env var | Default |
|---|---|---|
| Manager REST port | `QOD_ON_DEMAND_PORT` | `20900` |
| FlightSQL edge port | `PROXY_PORT` | `31338` |
| FlightSQL TLS on/off | `PROXY_TLS_ENABLED` | `true` |
| State backend | `QOD_STATE_STORAGE` | `postgres` |
| Postgres host | `QOD_PG_HOST` | `localhost` |
| Postgres user | `QOD_PG_USER` | `postgres` |
| Control-plane database | `QOD_PG_DBNAME` | `qod` |
| Admin usernames | `QOD_ADMIN_USERNAME` | `admin@localhost.local,admin` |
| Enable per-statement RBAC | `QOD_ACL_ENABLED` | `false` |

For the full list of configuration keys, see the [Configuration reference](/reference/configuration).
