# Quack on Demand

[![Status](https://img.shields.io/badge/status-alpha-orange.svg)](RESILIENCE.md)
[![Build](https://github.com/starlake-ai/quack-on-demand/actions/workflows/snapshot.yml/badge.svg)](https://github.com/starlake-ai/quack-on-demand/actions/workflows/snapshot.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ai.starlake/quack-on-demand_3.svg?label=maven%20central)](https://central.sonatype.com/artifact/ai.starlake/quack-on-demand_3)
[![Docker Pulls](https://img.shields.io/docker/pulls/starlakeai/quack-on-demand.svg)](https://hub.docker.com/r/starlakeai/quack-on-demand)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Scala](https://img.shields.io/badge/scala-3.7-red.svg)](https://www.scala-lang.org/)
[![JDK](https://img.shields.io/badge/jdk-17%2B-blue.svg)](https://adoptium.net/)

**Multi-tenant Arrow Flight SQL gateway for DuckDB — shared DuckLake catalog, per-tenant pools, table-level ACLs, pluggable identity. Single uber-jar.**

Any JDBC / ODBC / ADBC client connects to one Flight SQL edge; the gateway authenticates the user (DB / JWT / OIDC), checks table-level ACLs against the parsed SQL, classifies the statement, and routes it to a compatible node in the tenant's pool. Control-plane state lives next to DuckLake's metadata in the same Postgres database.

### Why this exists

DuckDB's [Quack](https://duckdb.org/docs/current/core_extensions/quack) protocol lets DuckDB instances talk to each other over HTTP/2 — DuckDB is no longer just an embedded library. But Quack is intentionally minimal: a single static token for auth, no multi-tenancy, no authorization model, DuckDB-only on the client side. The docs themselves recommend putting infrastructure in front of it before any serious deployment. Quack on Demand is that infrastructure.

### Project status

**Alpha (`0.1.x`).** Designed to be **safely restartable**, not highly available — single-instance, no active-active manager yet. [`RESILIENCE.md`](RESILIENCE.md) is the honest failure-and-recovery matrix; [`docs/ROADMAP.md`](docs/ROADMAP.md) tracks what's planned next. APIs and config keys may change between `0.x` releases.

![Admin console - live per-node metrics, statement history, ACL editor](assets/metrics.jpg)

---

## Contents

- [Who is this for?](#who-is-this-for)
- [Features](#features)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [REST API](#rest-api)
- [Admin UI](#admin-ui)
- [ACL model](#acl-model)
- [Tech stack](#tech-stack)
- [Development](#development)
- [Operational notes](#operational-notes)
- [License](#license)
- [Community](#community)
- [Contributing](#contributing)

---

## Who is this for?

**Use Quack on Demand if you want to:**

- Expose a DuckLake / DuckDB warehouse to multiple teams or apps over a standard wire protocol (Arrow Flight SQL — works with JDBC, ODBC, ADBC, PyArrow, DBeaver, Spark, and other Flight-aware clients)
- Authenticate users against your existing identity provider (Keycloak / Azure AD / Google / Cognito / JWT / database) and enforce table-level ACLs at query time
- Run several tenants on shared infrastructure without giving each one a private DuckDB process to manage

**Look elsewhere if you:**

- Just need a single embedded DuckDB inside one application — use DuckDB directly
- Need a distributed query engine over data lakes with cross-node shuffles and joins on TB-scale tables — look at Trino / Dremio / StarRocks. Quack on Demand routes each statement to a single Quack node; it doesn't fan out across them

---

## Features

### Security & identity

- **Arrow Flight SQL edge** with auto-generated self-signed TLS (drop in a CA-signed cert for prod)
- **Pluggable authentication** through the vendored `AuthenticationService` chain:
  - Postgres / any JDBC backend (BCrypt-hashed passwords, free-form SQL template)
  - External JWT (HS256 / RS256 / public-key PEM)
  - OIDC providers: Keycloak (with ROPC), Google, Azure AD, AWS Cognito
- **Postgres-relational ACL** stored in `slkstate_acl_grant`, managed via REST, enforced per statement (SQL parser extracts table refs, the validator looks up grants for the user's principal)
- **Admin REST API** with an `X-API-Key` static key OR a session token minted via `/api/auth/login`

### Data plane

- **Multi-tenant pools** of Quack nodes. Each node is `READONLY`, `WRITEONLY`, or `DUAL`; the router classifies each statement and picks a compatible node
- **Single uber-jar** deployment; control-plane state lives next to DuckLake's metadata in the same Postgres database

### Operability

- **React admin console** at `/ui/` - tenant CRUD, pool CRUD, per-tenant ACL editor, live node dashboard (in-flight, total served, EWMA latency), admin-role gated
- **Observability built in** - Prometheus scrape endpoint at `/metrics`, or push to AWS CloudWatch / Azure Monitor / GCP Cloud Monitoring (one sink at a time, picked via `metrics.sink`). Ships with a Grafana 10.x dashboard at [observability/grafana-dashboard.json](observability/grafana-dashboard.json)
- **Self-healing on restart** - when the manager comes back up, every Quack node from `slkstate_pool_state` is reconciled: each child's recorded PID is checked, its port is probed, and any node that no longer answers is respawned before the Flight SQL edge starts accepting traffic. Full failure-and-recovery matrix in [`RESILIENCE.md`](RESILIENCE.md)
- **Every config key is overridable** via a `QOD_*` env var

---

## Architecture

```
┌─────────────────────┐
│  JDBC/ADBC client   │       Bearer or Basic auth
│  (DBeaver, Spark,   │  ────────────────────────────►
│   custom apps)      │                                ┌──────────────────────────────┐
└─────────────────────┘                                │   quack-on-demand manager    │
                                                       │                              │
┌─────────────────────┐    HTTPS (admin console)       │  ┌────────────────────────┐  │
│       Browser       │  ────────────────────────────► │  │  Tapir REST + React UI │  │
│   /ui/* admin page  │                                │  │   :20900               │  │
└─────────────────────┘                                │  └────────────────────────┘  │
                                                       │                              │
                                                       │  ┌────────────────────────┐  │
                                                       │  │  Arrow FlightSQL edge  │  │
                                                       │  │  :31338   (TLS)        │  │
                                                       │  │  • Auth (DB/JWT/OIDC)  │  │
                                                       │  │  • ACL validator       │  │
                                                       │  │  • Role-aware router   │  │
                                                       │  └────────┬───────────────┘  │
                                                       │           │                  │
                                                       │           ▼                  │
                                                       │   spawn-quack-node.sh        │
                                                       │           │                  │
                                                       └───────────┼──────────────────┘
                                                                   │
                                          ┌────────────────────────┼─────────────────────────┐
                                          ▼                        ▼                         ▼
                                  ┌──────────────┐         ┌──────────────┐          ┌──────────────┐
                                  │  Quack node  │         │  Quack node  │          │  Quack node  │
                                  │  WRITEONLY   │         │  READONLY    │          │   DUAL       │
                                  │  :21900      │         │  :21901      │          │  :21902      │
                                  └──────┬───────┘         └──────┬───────┘          └──────┬───────┘
                                         │                        │                         │
                                         └────────────────────────┼─────────────────────────┘
                                                                  │
                                                                  ▼
                                                ┌────────────────────────────────┐
                                                │   Postgres (DuckLake metadata) │
                                                │   • ducklake_*  (catalog)      │
                                                │   • slkstate_pool_state        │
                                                │   • slkstate_user              │
                                                │   • slkstate_acl_grant         │
                                                └────────────────────────────────┘
                                                                  +
                                                          object/file storage
                                                          (S3, GCS, FS, …)
```

**Two paths into Postgres** (not pictured to keep the diagram readable):

- The **manager** owns the control plane — it writes `slkstate_pool_state` / `slkstate_user` on tenant + pool CRUD, and reads `slkstate_acl_grant` from the ACL validator on every authenticated statement.
- Each **Quack node** owns the data plane — it reads and writes DuckLake's `ducklake_*` catalog tables directly when resolving and mutating tables.

Both sets of tables live in the same database so control-plane and catalog stay transactionally coherent.

---

## Requirements

| | Version | Notes |
|---|---|---|
| **JDK** | 17+ | Built and shipped on Temurin 17 (Dockerfile). Arrow's `arrow-memory-unsafe` allocator needs `--add-opens=java.base/java.nio` and `java.base/sun.nio.ch` on 17+ — the assembly jar sets these via an `Add-Opens` manifest attribute so `java -jar` just works |
| **Postgres** | 13+ | Tested with the `postgres:16-alpine` image bundled in `docker-compose.yml`. Both DuckLake's `ducklake_*` catalog tables and the `slkstate_*` control-plane tables live in the same database |
| **DuckDB CLI** | matched to the bundled libduckdb | Each Quack node is a child `duckdb` process. The Docker image ships its own; for **native** runs you need `duckdb` on `$PATH` (see [`RUNNING.md`](RUNNING.md) for the pinned version) |
| **Docker** | Engine 24+, Compose v2 | Compose path only |
| **Scala / sbt** | 3.7, sbt 1.x | Source builds only — not needed if you run the published jar or Docker image |
| **OS** | Linux, macOS | Manager and nodes; the assembly jar bundles `libduckdb_java.so` for `linux_amd64`, `linux_arm64`, and `osx_universal` |

---

## Quick start

Zero to first query in under 5 minutes — see **[`QUICKSTART.md`](QUICKSTART.md)** for the step-by-step. The short version:

```bash
cp .env.example .env                            # tweak ports / auth / admin password
LOAD_TPCH=1 ./scripts/run-docker-compose.sh     # pulls starlakeai/quack-on-demand:latest + seeds TPC-H SF=1
```

That brings up Postgres + the manager and seeds the DuckLake catalog with TPC-H at scale factor 1 (~6M lineitem rows) into schema `tpch.tpch1`. The admin UI is on `http://localhost:20900/ui/` — log in as `admin` (or the equivalent `admin@localhost.local`; `QOD_ADMIN_USERNAME` is a comma-separated list) with password `admin`. Change both before exposing anything beyond `localhost`. The FlightSQL edge is on `localhost:31338`.

**Prefer a bare-JVM run?** `./scripts/run-jar.sh` downloads the latest released uber-jar, probes Postgres, and `exec`s `java -jar` with the Arrow allocator pinned — `BUILD=1 ./scripts/run-jar.sh` builds from this checkout first. See [`RUNNING.md`](RUNNING.md) for the full native path (external Postgres, env vars, TLS).

Smoke-test the FlightSQL edge with the Python load tester:

```bash
pip install adbc_driver_flightsql adbc_driver_manager

# TLS-on server (compose default)
python3 ./scripts/loadtest/loadtest.py -w 2 -i 5

# Plaintext server (TLS=false in .env, or scripts/run-docker.sh default)
./scripts/loadtest/loadtest.py --url grpc://localhost:31338 -w 2 -i 5
```

Expected tail — a healthy first run looks like this (numbers depend on `-w`/`-i` and hardware):

```
Queries OK:       10
Queries failed:   0
Throughput:       ~25 qps
Latency  p50:     ~40 ms
```

`Queries failed: 0` is the success signal. If queries fail with `UNAUTHENTICATED`, your `.env` credentials don't match what the manager seeded; if they fail with TLS errors, see the `--insecure` / `grpc://` note in [`QUICKSTART.md`](QUICKSTART.md#2-run-your-first-query--30-seconds).

Everything else - native run, Docker against an external Postgres, TPC-H seeding, corporate proxy setup, JDBC client configuration, REST API recipes, the full loadtest parameter table - lives in **[`RUNNING.md`](RUNNING.md)**.

---

## Configuration

Every scalar in `src/main/resources/application.conf` accepts a matching `QOD_*` env-var override. The most-used:

| Setting | Env var | Default |
|---|---|---|
| Manager REST port | `QOD_ON_DEMAND_PORT` | `20900` |
| FlightSQL edge port | `PROXY_PORT` | `31338` |
| FlightSQL TLS on/off | `PROXY_TLS_ENABLED` | `true` |
| State backend | `QOD_STATE_STORAGE` | `postgres` |
| Metastore host | `QOD_PG_HOST` | `localhost` |
| Metastore user | `QOD_PG_USER` | `postgres` |
| Metastore password | `QOD_PG_PASSWORD` | `azizam` (change this!) |
| Metastore database | `QOD_PG_DBNAME` | `tpch` |
| Static admin key | `QOD_API_KEY` | unset |
| Admin usernames | `QOD_ADMIN_USERNAME` | `admin@localhost.local,admin` |
| Admin password | `QOD_ADMIN_PASSWORD` | `admin` |
| Enable DB auth | `QOD_AUTH_DB_ENABLED` | `false` |
| Enable ACL | `QOD_ACL_ENABLED` | `false` |

Pluggable auth backends, ACL store paths, K8s runtime, JWT keys - see `application.conf` for the full surface.

---

## REST API

All endpoints under `/api/*` require a valid `X-API-Key` (either the static `QOD_API_KEY` or a UI session token from `/api/auth/login`). `/health` and `/api/config/client` are open; `/ui/*` is open (the React app gates itself).

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/auth/login`           | mint a session token (admin role required) |
| `POST` | `/api/auth/logout`          | revoke the current token |
| `GET`  | `/api/auth/whoami`          | verify session |
| `GET`  | `/api/tenant/list`          | list tenants + effective metastore |
| `POST` | `/api/tenant/create`        | create a tenant |
| `POST` | `/api/tenant/setMetastore`  | patch a tenant's metastore overrides |
| `POST` | `/api/tenant/delete`        | delete a tenant (must have no pools) |
| `GET`  | `/api/pool/list`            | list pools with live node metrics |
| `POST` | `/api/pool/create`          | spin up a pool |
| `POST` | `/api/pool/scale`           | scale up/down with role redistribution |
| `POST` | `/api/pool/stop`            | tear down |
| `POST` | `/api/node/setMaxConcurrent`| per-node concurrency cap |
| `POST` | `/api/node/quarantine`      | mark a node unhealthy |
| `POST` | `/api/node/restart`         | drain + restart a node |
| `GET`  | `/api/acl/grant/list?tenant=…` | list grants |
| `POST` | `/api/acl/grant/create`     | grant `(principal, target, permission)` |
| `POST` | `/api/acl/grant/upload`     | bulk insert |
| `POST` | `/api/acl/grant/delete/:id` | revoke |
| `GET`  | `/api/config/client`        | discovery: edge host/port/TLS (open) |
| `GET`  | `/health`                   | liveness + pool/node counts (open) |
| `GET`  | `/metrics`                  | Prometheus text-format scrape (open). Disabled when `metrics.sink` pushes to CloudWatch / Azure Monitor / GCP Cloud Monitoring |

---

## Admin UI

| Page | What it shows |
|---|---|
| **Login** | Username/password, admin-role gated |
| **/ ** (Tenants) | List + create + delete tenants, effective metastore preview |
| **/tenant/:tenant** | Tenant detail · pools · storage · **ACL grants editor** |
| **/pool/:tenant/:pool** | Pool nodes, JDBC/ODBC/ADBC connection strings, scale/stop |
| **/nodes** | Live cluster dashboard: per-node `inFlight`, `totalServed`, EWMA latency, role + health badges, per-tenant filter, auto-refresh every 2s |

![Live node dashboard - per-node inFlight, totalServed, EWMA latency](assets/nodes-dashboard.jpg)

---

## ACL model

Grants live in `slkstate_acl_grant` with shape:

```sql
(tenant_id, principal, catalog_name, schema_name, table_name, permission)
```

- `principal` follows the `type:name` convention - `user:alice`, `group:engineers`, `role:admin`
- Any of `catalog_name / schema_name / table_name` may be `NULL` to act as a wildcard
- Permissions: `SELECT | INSERT | UPDATE | DELETE | ALL` (`ALL` always wins)

When `acl.enabled=true` and `stateStorage=postgres`, `PostgresAclValidator` parses each incoming SQL statement, extracts the table references, and queries the table for matching grants. Non-SELECT statements (DDL / DML) are denied unless the principal holds a wildcard `ALL` grant.

**Principal expansion.** At validation time the authenticated session's `username`, `groups`, and `role` are expanded into `user:<username>`, `group:<g>` (one per group), and `role:<r>` principals; a grant matches if *any* of them does. So an OIDC user `alice` with groups `[engineers, analysts]` and role `viewer` triggers lookups for four principals at once - write your grants against whichever level of identity is stable.

![ACL grants editor on the tenant detail page](assets/acl-editor.jpg)

Add a grant from the UI's tenant detail page, or via curl:

```bash
curl -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/acl/grant/create \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"acme","principal":"user:alice",
       "catalogName":"tpch","schemaName":"main","tableName":"customer",
       "permission":"SELECT"}'
```

---

## Tech stack

- **Scala 3.7** - `enum`, given/using, derived ConfigReader
- **Cats Effect** + **fs2** for the runtime
- **Tapir** + **HTTP4s Ember** for the REST API
- **Apache Arrow Flight SQL 14** + `arrow-memory-unsafe` (pinned via `-Darrow.allocation.manager.type=Unsafe`)
- **DuckLake** for shared catalog metadata + S3/FS data storage
- **PostgreSQL** for catalog metadata + control-plane state (`slkstate_*` tables)
- **React 18** + **Vite** + **react-router-dom** for the SPA (no UI framework - single dark-theme CSS file)

---

## Development

```bash
# Run the manager from sbt (forks JVM so `--add-opens` takes effect)
sbt run

# Run the test suite (574+ tests)
sbt test

# Build the uber-jar at distrib/quack-on-demand-assembly-*.jar
sbt assembly

# UI dev loop (proxies /api/* to localhost:20900)
cd ui && npm install && npm run dev
```

Project layout:

```
src/main/scala/ai/starlake/
├── quack/
│   ├── Main.scala                 # IOApp + wiring
│   ├── Config.scala               # ManagerConfig, FlightConfig, AdminConfig
│   ├── edge/                      # FlightSQL edge + router + adapter
│   │   ├── auth/                  # AuthenticationService + provider chain
│   │   ├── catalog/               # DuckLakeCatalogResolver (table/view lookup)
│   │   ├── config/                # AuthenticationConfig, AclConfig, SessionConfig, …
│   │   └── sql/                   # ACL + statement validators
│   ├── ondemand/                  # Pool supervisor + handlers + state
│   │   ├── api/                   # Tapir endpoints + DTOs + handlers
│   │   ├── runtime/               # Local + Kubernetes backends
│   │   └── state/                 # File + Postgres state stores
│   └── route/                     # StatementClassifier + Router + RoleMatcher
└── acl/                           # SQL parser + ACL model + multi-tenant store
scripts/
├── run-jar.sh       # Boot the manager from the uber-jar
├── stop-jar.sh        # Graceful SIGTERM → SIGKILL escalation
├── start-quack-ducklake.sh        # Standalone single-node Quack for testing
├── load-tpch-dbgen.sh             # Generate TPC-H (SF override via $SF) into the metastore via DuckDB's dbgen()
└── spawn-quack-node.sh            # Called by LocalQuackBackend; do not invoke directly
ui/
└── src/                           # React SPA, built into src/main/resources/ui
```

---

## Operational notes

Defaults and design choices an operator should be aware of before going to production:

- **FlightSQL edge is authenticated by default; the admin password is `admin`.** `auth.database.enabled = true` and the manager seeds `slkstate_user` with the configured admin at boot. The default credentials (`admin@localhost.local / admin`) are fine for first-run; **rotate via `QOD_ADMIN_PASSWORD` before exposing the edge.**
- **REST API is OPEN by default.** The control-plane REST API (`/api/...`) is a separate gate. Until you set `QOD_API_KEY`, anonymous requests are accepted. The manager logs a loud warning at startup. Set the env var, or restrict the listening interface, before exposing the manager beyond `localhost`.
- **DML grants in ACL mode are coarse-grained.** `INSERT`/`UPDATE`/`DELETE` are denied unless the principal holds a wildcard `ALL` grant. Per-table DML grants need the ACL `TableExtractor` to also walk non-SELECT statements; today it only enumerates reads.
- **K8s reconciliation is conservative.** Local mode detects dead child PIDs at startup and respawns; K8s mode trusts the apiserver's liveness probe (pods without a Linux PID are kept as-is, with the `HealthProbe` catching drift after one tick). Implementing pod-status reconciliation requires `KubernetesQuackBackend.discoverExisting()` to wire into the apiserver.
- **Edge session caching trades latency for revocation lag.** Auth re-validation happens at the TTL boundary (`sessionTtlSec`, default 1h), not on every call. A revoked OIDC token still works for up to one TTL window - shrink the TTL or restart the manager for immediate effect.
- **Metrics, dashboards and cloud monitoring.** Manager exposes Prometheus metrics at `/metrics` by default, or pushes to AWS CloudWatch / Azure Monitor / GCP Cloud Monitoring instead (one sink at a time, picked via `metrics.sink`). See [observability/README.md](observability/README.md) for the sink selector, env vars, credential discovery chains, cardinality budget, and the Grafana 10.x dashboard at [observability/grafana-dashboard.json](observability/grafana-dashboard.json).

---

## License

Apache 2.0.

## Community

- **Questions / discussion** -> [GitHub Discussions](https://github.com/starlake-ai/quack-on-demand/discussions)
- **Bug or feature** -> file an issue using the templates (the blank-issue path is disabled on purpose)
- **Security report (private)** -> email `hayssam.saleh@starlake.ai`

## Contributing

PRs welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the dev loop
(build, test, commit conventions, PR flow) and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for the community standards we
follow. Start with an issue labelled `good first issue` if you'd like a
small, well-scoped task.
