# Quack on Demand

[![Status](https://img.shields.io/badge/status-alpha-orange.svg)](RESILIENCE.md)
[![Build](https://github.com/starlake-ai/quack-on-demand/actions/workflows/snapshot.yml/badge.svg)](https://github.com/starlake-ai/quack-on-demand/actions/workflows/snapshot.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ai.starlake/quack-on-demand_3.svg?label=maven%20central)](https://central.sonatype.com/artifact/ai.starlake/quack-on-demand_3)
[![Docker Pulls](https://img.shields.io/docker/pulls/starlakeai/quack-on-demand.svg)](https://hub.docker.com/r/starlakeai/quack-on-demand)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Discord](https://img.shields.io/badge/discord-join-5865F2?logo=discord&logoColor=white)](https://discord.gg/VTxrj8KU)

**Multi-tenant Arrow Flight SQL gateway for DuckDB, per-tenant DuckLake catalog, per-tenant pools, first-class RBAC graph (users · groups · roles · table permissions · pool grants), pluggable identity. Single uber-jar.**

Any JDBC / ODBC / ADBC client connects to one Flight SQL edge; the gateway authenticates the user (DB / JWT / OIDC), gates the connection at handshake (user-scope + pool-access), parses each statement and matches its table refs against the user's cached **EffectiveSet**, then routes the statement to a compatible node in the tenant's pool. Each tenant owns its own DuckLake catalog database; the manager's normalized `qodstate_*` control plane sits in a separate database next to them.

### Why this exists

DuckDB's [Quack](https://duckdb.org/docs/current/core_extensions/quack) protocol lets DuckDB instances talk to each other over HTTP/2. DuckDB is no longer just an embedded library. But Quack is intentionally minimal: a single static token for auth, no multi-tenancy, no authorization model, DuckDB-only on the client side. The docs themselves recommend putting infrastructure in front of it before any serious deployment. Quack on Demand is that infrastructure.

### Project status

**Alpha (`0.1.x`).** Designed to be **safely restartable**, not highly available, single-instance, no active-active manager yet. [`RESILIENCE.md`](RESILIENCE.md) is the honest failure-and-recovery matrix; [`docs/ROADMAP.md`](docs/ROADMAP.md) tracks what's planned next. APIs and config keys may change between `0.x` releases.

![Admin console - live per-node metrics, statement history, Users page](assets/metrics.jpg)

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
- [Access control (RBAC)](#access-control-rbac)
- [Tech stack](#tech-stack)
- [Development](#development)
- [Operational notes](#operational-notes)
- [License](#license)
- [Community](#community)
- [Contributing](#contributing)

---

## Who is this for?

**Use Quack on Demand if you want to:**

- Expose a DuckLake / DuckDB warehouse to multiple teams or apps over a standard wire protocol (Arrow Flight SQL: works with JDBC, ODBC, ADBC, PyArrow, DBeaver, Spark, and other Flight-aware clients)
- Authenticate users against your existing identity provider (Keycloak / Azure AD / Google / Cognito / JWT / database) and enforce table-level RBAC at query time, with a per-user EffectiveSet built from role + group memberships
- Run several tenants on shared infrastructure without giving each one a private DuckDB process to manage; each tenant owns a separate DuckLake catalog DB (`${tenant}_${tenantDb}`)

**Look elsewhere if you:**

- Just need a single embedded DuckDB inside one application ? use DuckDB directly
- Need a distributed query engine over data lakes with cross-node shuffles and joins on TB-scale tables then look at Trino / Dremio / StarRocks. Quack on Demand routes each statement to a single Quack node; it doesn't fan out across them

---

## Features

### Security & identity

- **Arrow Flight SQL edge** with auto-generated self-signed TLS (drop in a CA-signed cert for prod)
- **Pluggable authentication** through the vendored `AuthenticationService` chain:
  - Postgres / any JDBC backend (BCrypt-hashed passwords, free-form SQL template)
  - External JWT (HS256 / RS256 / public-key PEM)
  - OIDC providers: Keycloak (with ROPC), Google, Azure AD, AWS Cognito
- **First-class RBAC graph** in `qodstate_role` / `qodstate_role_permission` / `qodstate_group` / `qodstate_user_*` / `qodstate_pool_permission`. Two gates run at handshake (user-scope, pool-access); per statement the SQL parser extracts table refs and matches them against the cached **EffectiveSet** pinned on the connection. Superusers (`qodstate_user.tenant IS NULL`) bypass both layers
- **Admin REST API** with an `X-API-Key` static key OR a session token minted via `/api/auth/login`

### Data plane

- **Multi-tenant pools** of Quack nodes. Each node is `READONLY`, `WRITEONLY`, or `DUAL`; the router classifies each statement and picks a compatible node
- **Per-tenant DuckLake catalog DB** (`${tenant}_${tenantDb}`) auto-provisioned next to the manager's control-plane DB (`qod`); tenant isolation is at the Postgres-database boundary, not just at the row level
- **Single uber-jar** deployment; the normalized `qodstate_*` control plane sits in a dedicated database alongside the per-tenant DuckLake catalogs

### Operability

- **React admin console** at `/ui/` - tenant CRUD (with Databases · Pools · Auth provider tabs), pool CRUD, a dedicated **/users page** (Users · Groups · Roles · Identities tabs) with a per-user "Effective permissions" drilldown, live node dashboard (in-flight, total served, EWMA latency), admin-role gated
- **Observability built in** - Prometheus scrape endpoint at `/metrics`, or push to AWS CloudWatch / Azure Monitor / GCP Cloud Monitoring (one sink at a time, picked via `metrics.sink`). Ships with a Grafana 10.x dashboard at [observability/grafana-dashboard.json](observability/grafana-dashboard.json)
- **Self-healing on restart** - when the manager comes back up, the registry restored from the normalized `qodstate_tenant` / `qodstate_tenant_db` / `qodstate_pool` / `qodstate_node` tables is reconciled against the runtime backend: each child's recorded PID is checked, its port is probed, and any node that no longer answers is respawned before the Flight SQL edge starts accepting traffic. Full failure-and-recovery matrix in [`RESILIENCE.md`](RESILIENCE.md)
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
                                                       │  │  • Handshake gates     │  │
                                                       │  │    (user-scope, pool)  │  │
                                                       │  │  • Per-statement RBAC  │  │
                                                       │  │    (EffectiveSet)      │  │
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
                                                ┌─────────────────────────────────────┐
                                                │   Postgres                          │
                                                │                                     │
                                                │  Control-plane DB (`qod`):          │
                                                │   • qodstate_tenant / _tenant_db    │
                                                │     / _pool / _node                 │
                                                │   • qodstate_user                   │
                                                │   • qodstate_tenant_identity        │
                                                │   • qodstate_role / _role_permission│
                                                │   • qodstate_group / _user_group /  │
                                                │     _user_role / _group_role        │
                                                │   • qodstate_pool_permission        │
                                                │   • slkstate_pool_state (file mode) │
                                                │                                     │
                                                │  Per-tenant catalog DBs             │
                                                │  (`${tenant}_${tenantDb}`):         │
                                                │   • __ducklake_*  (DuckLake catalog)│
                                                └─────────────────────────────────────┘
                                                                  +
                                                          object/file storage
                                                          (S3, GCS, FS, …)
```

**Two paths into Postgres** (not pictured to keep the diagram readable):

- The **manager** owns the control plane: it writes `qodstate_tenant` / `qodstate_tenant_db` / `qodstate_pool` / `qodstate_node` on tenant + pool CRUD, and resolves the cached **EffectiveSet** for each authenticated session from `qodstate_user` + the membership tables + `qodstate_role_permission` / `qodstate_pool_permission` at handshake.
- Each **Quack node** owns the data plane against its tenant-db: it reads and writes that DB's DuckLake `__ducklake_*` catalog tables directly when resolving and mutating tables.

Two databases per tenant-db deployment (control-plane `qod` + tenant-db `${tenant}_${tenantDb}`) keeps control-plane and DuckLake catalog cleanly separated while sharing one Postgres cluster.

---

## Requirements

| | Version | Notes |
|---|---|---|
| **JDK** | 17+ | Built and shipped on Temurin 17 (Dockerfile). Arrow's `arrow-memory-unsafe` allocator needs `--add-opens=java.base/java.nio` and `java.base/sun.nio.ch` on 17+. The assembly jar sets these via an `Add-Opens` manifest attribute so `java -jar` just works |
| **Postgres** | **16+** | Bundled `postgres:16-alpine` image used by `docker-compose.yml`. The control-plane DB (`qod` by default) hosts the Liquibase-managed `qodstate_*` tables; each managed tenant-db (`${tenant}_${tenantDb}`, e.g. `tpch_tpch1`) is a separate database next to it carrying the DuckLake `__ducklake_*` catalog. `scripts/run-jar.sh` gates the server version |
| **DuckDB CLI + libduckdb** | matched to the bundled `libduckdb_java` | Each Quack node is a child `duckdb` process; the manager also loads `libduckdb` natively for the DuckLake catalog reader. The Docker image ships both. For **native** runs install `duckdb` on `$PATH` and `libduckdb.{so,dylib}` somewhere `System.loadLibrary` finds it, or set `QOD_NATIVE_CLIENT=false` to disable the native reader (see [`RUNNING.md`](RUNNING.md)) |
| **Docker** | Engine 24+, Compose v2 | Compose path only |
| **Scala / sbt** | 3.7, sbt 1.x | Source builds only not needed if you run the published jar or Docker image |
| **OS** | Linux, macOS | Manager and nodes; the assembly jar bundles `libduckdb_java.so` for `linux_amd64`, `linux_arm64`, and `osx_universal` |

---

## Quick start

Zero to first query in under 5 minutes: see **[`QUICKSTART.md`](QUICKSTART.md)** for the step-by-step. The short version:

```bash
cp .env.example .env                            # tweak ports / auth / admin password
LOAD_TPCH=1 ./scripts/run-docker-compose.sh     # pulls starlakeai/quack-on-demand:latest + seeds TPC-H SF=1
```

That brings up Postgres + the manager, bootstraps the `tpch` tenant with a `tpch1` tenant-db (catalog DB `tpch_tpch1`) and a `sales` pool of 3 nodes (WO/RO/Dual), and seeds the DuckLake catalog with TPC-H at scale factor 1 (~6M lineitem rows) into schema `tpch1`. The admin UI is on `http://localhost:20900/ui/`. Log in as `admin` (or the equivalent `admin@localhost.local`; `QOD_ADMIN_USERNAME` is a comma-separated list) with password `admin`. Change both before exposing anything beyond `localhost`. The FlightSQL edge is on `localhost:31338`; every client scopes its session with `tenant=tpch` + `pool=sales` (JDBC URL param / ADBC call-header / loadtest `--tenant`/`--pool` flag).

**Prefer a bare-JVM run?** `./scripts/run-jar.sh` downloads the latest released uber-jar, probes Postgres, and `exec`s `java -jar` with the Arrow allocator pinned. `BUILD=1 ./scripts/run-jar.sh` builds from this checkout first. See [`RUNNING.md`](RUNNING.md) for the full native path (external Postgres, env vars, TLS).

Smoke-test the FlightSQL edge with the Python load tester:

```bash
pip install adbc_driver_flightsql adbc_driver_manager

# TLS-on server (compose default). --tenant/--pool default to tpch/sales
# (matching the bootstrap), so no flags are needed for the demo path.
python3 ./scripts/loadtest/loadtest.py -w 2 -i 5

# Plaintext server (TLS=false in .env, or scripts/run-docker.sh default)
./scripts/loadtest/loadtest.py --url grpc://localhost:31338 -w 2 -i 5
```

Expected tail: a healthy first run looks like this (numbers depend on `-w`/`-i` and hardware):

```
Load test: 2 workers x 5 iterations (+5 warmup) against grpc+tls://localhost:31338 as admin -> tpch/sales
...
Queries OK:       10
Queries failed:   0
Latency  p50:     ~60 ms
```

`Queries failed: 0` is the success signal. `[FlightSQL] missing tenant scope for Basic auth` means a custom client connected without `tenant`/`pool` routing headers; see [`QUICKSTART.md`](QUICKSTART.md#4-run-a-custom-sql---30-seconds) for the JDBC URL / ODBC string / ADBC db_kwargs shape. `UNAUTHENTICATED` (other variants) usually means the `.env` credentials don't match what the manager seeded; TLS errors mean a `grpc://` vs `grpc+tls://` mismatch.

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
| Control-plane database | `QOD_PG_DBNAME` | `qod` |
| Static admin key | `QOD_API_KEY` | unset |
| Admin usernames | `QOD_ADMIN_USERNAME` | `admin@localhost.local,admin` |
| Admin password | `QOD_ADMIN_PASSWORD` | `admin` |
| Enable DB auth | `QOD_AUTH_DB_ENABLED` | `true` |
| Enable per-statement RBAC | `QOD_ACL_ENABLED` | `false` |

Pluggable auth backends, K8s runtime, JWT keys, per-tenant bootstrap overrides - see `application.conf` for the full surface.

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
| `GET`  | `/api/user/list?tenant=…`   | list users (omit `tenant` for every user incl. superusers) |
| `POST` | `/api/user/create`          | create a `(tenant?, username, password, role)` principal; `tenant=null` = superuser |
| `POST` | `/api/user/update`          | rotate password / role |
| `POST` | `/api/user/delete`          | delete a user (cascades memberships + pool grants) |
| `GET`  | `/api/user/:id/effective`   | closure of roles · groups · table permissions · pool grants for one user |
| `GET`  | `/api/role/list?tenant=…`   | list roles in a tenant |
| `POST` | `/api/role/create`          | create a role |
| `POST` | `/api/role/delete`          | delete a role |
| `GET`  | `/api/role/permission/list?roleId=…` | list table permissions attached to a role |
| `POST` | `/api/role/permission/grant`| attach `(catalog, schema, table, verb)` to a role (`*` wildcard, `verb in SELECT/INSERT/UPDATE/DELETE/ALL`) |
| `POST` | `/api/role/permission/revoke` | detach a single permission row |
| `GET`  | `/api/group/list?tenant=…`  | list groups |
| `POST` | `/api/group/create`         | create a group |
| `POST` | `/api/group/delete`         | delete a group |
| `POST` | `/api/membership/user/role` | add/remove a user ↔ role membership |
| `POST` | `/api/membership/user/group` | add/remove a user ↔ group membership |
| `POST` | `/api/membership/group/role` | add/remove a group ↔ role membership |
| `GET`  | `/api/pool/permission/list?tenant=&userId=&groupId=` | list pool grants |
| `POST` | `/api/pool/permission/grant`| grant a `(tenant, pool?)` to a user OR a group (`pool=null` = every pool in tenant) |
| `POST` | `/api/pool/permission/revoke` | revoke a pool grant |
| `GET`  | `/api/identity/list?tenantId=…` | list verified-identity → tenant rows |
| `POST` | `/api/identity/create`      | add an `(issuer, externalId)` → tenant mapping |
| `POST` | `/api/identity/delete`      | revoke an identity row |
| `GET`  | `/api/config/client`        | discovery: edge host/port/TLS (open) |
| `GET`  | `/health`                   | liveness + pool/node counts (open) |
| `GET`  | `/metrics`                  | Prometheus text-format scrape (open). Disabled when `metrics.sink` pushes to CloudWatch / Azure Monitor / GCP Cloud Monitoring |

---

## Admin UI

| Page | What it shows |
|---|---|
| **Login** | Username/password, admin-role gated |
| **/ ** (Tenants) | List + create + delete tenants, effective metastore preview |
| **/tenant/:tenant** | Tenant detail · **Databases · Pools · Auth provider** tabs |
| **/users** | Tenant selector + **Users · Groups · Roles · Identities** tabs. Per-user "Effective…" drilldown showing the closure of roles, groups, table permissions and pool grants |
| **/pool/:tenant/:pool** | Pool nodes, JDBC/ODBC/ADBC connection strings (with `tenant`/`pool` baked in), scale/stop |
| **/nodes** | Live cluster dashboard: per-node `inFlight`, `totalServed`, EWMA latency, role + health badges, per-tenant filter, auto-refresh every 2s |

---

## Access control (RBAC)

Quack on Demand's RBAC graph is a normalized model across nine `qodstate_*` tables. Auth gates run in two places: at FlightSQL **handshake** (does this user belong to this tenant + may they reach this pool?) and **per statement** (does the user's EffectiveSet cover every table the SQL parser pulls out?).

### Entities

- **User** (`qodstate_user`): identified by `(tenant, username)`. A row with `tenant IS NULL` is a **superuser**: it can authenticate against any tenant via FlightSQL and bypasses both handshake and per-statement gates. Tenant-scoped principals carry a non-empty `tenant`.
- **Role** (`qodstate_role`): per-tenant container for TablePermission rows. Every new tenant is auto-seeded with a built-in `admin` role plus a `*.*.* ALL` permission, all in one transaction (`PoolSupervisor.createTenant`).
- **Group** (`qodstate_group`): per-tenant bundle of users that inherits role memberships and pool grants together.
- **TablePermission** (`qodstate_role_permission`): `(role_id, catalog, schema, table, verb)`. `verb ∈ {SELECT, INSERT, UPDATE, DELETE, ALL}`; `*` in any of catalog/schema/table is the literal wildcard. **This is the only place table-level grants live.**
- **PoolPermission** (`qodstate_pool_permission`): grants a user OR a group access to a `(tenant, pool?)`. `pool_id NULL` means "every pool in this tenant". Exactly one of `user_id` / `group_id` is set (DB CHECK enforced).
- **Memberships**: `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role` are bare FK pairs (cascade on delete).

### Gates

Both gates resolve from the same **EffectiveSet** pinned to the FlightSQL session at handshake:

```
EffectiveSet(user) =
    direct roles
  ∪ roles inherited via every group the user is in
  ∪ table permissions attached to any role in the closure
  ∪ pool permissions on the user OR on any of its groups
                     (a row with pool=NULL matches every pool in tenant)
```

- **Handshake** uses the EffectiveSet to answer "is the requested `(tenant, pool)` covered by any PoolPermission?". Superusers skip this entirely.
- **Per statement** uses the cached EffectiveSet to answer "is every `(catalog.schema.table, verb)` extracted from the SQL covered by some TablePermission?". Same superuser bypass.

Per-statement enforcement only kicks in when `acl.enabled=true` (`QOD_ACL_ENABLED`). The handshake gate is always on for tenant-scoped users; turning the per-statement gate off is for trusted-tenant deployments where the pool gate is enough.

### Caveats

- **Coarse DML/DDL**: the SQL `TableExtractor` only walks SELECT statements today, so `INSERT`/`UPDATE`/`DELETE` and `CREATE`/`DROP` are denied unless the principal holds a covering wildcard permission. Per-table DML grants need the extractor to walk those statement shapes too.

### Curl walkthrough

Bootstrap login → create a role + table permission → create a user → grant pool access → attach the role:

```bash
TOK=$(curl -sS -X POST -H 'Content-Type: application/json' \
   -d '{"username":"admin","password":"admin"}' \
   http://localhost:20900/api/auth/login | jq -r .token)

# A read-only role on tpch1.customer for tenant `tpch`
curl -sS -H "X-API-Key: $TOK" -X POST http://localhost:20900/api/role/create \
   -H 'Content-Type: application/json' \
   -d '{"tenant":"tpch","name":"customer_reader"}'

curl -sS -H "X-API-Key: $TOK" -X POST http://localhost:20900/api/role/permission/grant \
   -H 'Content-Type: application/json' \
   -d '{"roleId":2,"catalog":"tpch_tpch1","schema":"tpch1","table":"customer","verb":"SELECT"}'

# Tenant-scoped user
curl -sS -H "X-API-Key: $TOK" -X POST http://localhost:20900/api/user/create \
   -H 'Content-Type: application/json' \
   -d '{"tenant":"tpch","username":"alice","password":"hunter2","role":"reader"}'

# Pool access (every pool in tpch); a specific pool would use "pool":"sales"
curl -sS -H "X-API-Key: $TOK" -X POST http://localhost:20900/api/pool/permission/grant \
   -H 'Content-Type: application/json' \
   -d '{"tenant":"tpch","userId":5,"pool":null}'

# Attach the role
curl -sS -H "X-API-Key: $TOK" -X POST http://localhost:20900/api/membership/user/role \
   -H 'Content-Type: application/json' \
   -d '{"userId":5,"roleId":2}'

# Inspect the closure
curl -sS -H "X-API-Key: $TOK" http://localhost:20900/api/user/5/effective
```

---

## Tech stack

- **Scala 3.7** - `enum`, given/using, derived ConfigReader
- **Cats Effect** + **fs2** for the runtime
- **Tapir** + **HTTP4s Ember** for the REST API
- **Apache Arrow Flight SQL 14** + `arrow-memory-unsafe` (pinned via `-Darrow.allocation.manager.type=Unsafe`)
- **DuckLake** for per-tenant catalog metadata + S3/FS data storage
- **PostgreSQL 16+** for catalog metadata + control-plane state (Liquibase-managed `qodstate_*` tables for tenants/pools/users/RBAC; legacy `slkstate_pool_state` blob when `stateStorage=file`)
- **React 18** + **Vite** + **react-router-dom** for the SPA (no UI framework - single dark-theme CSS file)

---

## Development

```bash
# Run the manager from sbt (forks JVM so `--add-opens` takes effect)
sbt run

# Run the test suite (714+ tests)
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
│   │   └── sql/                   # RBAC + statement validators (PostgresAclValidator over EffectiveSet)
│   ├── rbac/                      # RBAC graph models + EffectiveSet resolver
│   ├── ondemand/                  # Pool supervisor + handlers + state
│   │   ├── api/                   # Tapir endpoints + DTOs + handlers
│   │   │                          # (user/role/group/membership/pool-permission/identity)
│   │   ├── runtime/               # Local + Kubernetes backends
│   │   └── state/                 # ControlPlaneStore (Postgres / file)
│   └── route/                     # StatementClassifier + Router + RoleMatcher
└── acl/                           # SQL parser (parser-only after Phase C; file-based store unwired)
scripts/
├── run-jar.sh                     # Boot the manager from the uber-jar
├── stop-jar.sh                    # SIGTERM → SIGKILL escalation
├── run-docker-compose.sh          # Full stack (Postgres + manager) with optional LOAD_TPCH=N seed
├── stop-docker-compose.sh         # compose down (+ NUKE=1 to wipe host state)
├── load-tpch-dbgen.sh             # Generate TPC-H (SF override via $SF) into a tenant-db via dbgen()
└── spawn-quack-node.sh            # Called by LocalQuackBackend; do not invoke directly
ui/
└── src/                           # React SPA, built into src/main/resources/ui
```

---

## Operational notes

Defaults and design choices an operator should be aware of before going to production:

- **FlightSQL edge is authenticated by default; the admin password is `admin`.** `auth.database.enabled = true` and the manager seeds `qodstate_user` with the configured admin (as a superuser: `tenant IS NULL`) at boot. The default credentials (`admin@localhost.local / admin`) are fine for first-run; **rotate via `QOD_ADMIN_PASSWORD` before exposing the edge.**
- **Every FlightSQL client scopes by tenant + pool at handshake.** Even the superuser admin must pass `tenant` + `pool` gRPC headers (JDBC URL params, ODBC `adbc.flight.sql.rpc.call_header.*`, ADBC `RPC_CALL_HEADER_PREFIX` db_kwargs, loadtest `--tenant`/`--pool`); the routing layer needs them to pick a pool. The per-statement RBAC gate has the superuser bypass, the routing handshake does not.
- **REST API is OPEN by default.** The control-plane REST API (`/api/...`) is a separate gate. Until you set `QOD_API_KEY`, anonymous requests are accepted. The manager logs a loud warning at startup. Set the env var, or restrict the listening interface, before exposing the manager beyond `localhost`.
- **DML / DDL grants are coarse-grained.** `INSERT`/`UPDATE`/`DELETE` and `CREATE`/`DROP` are denied unless the principal holds a covering wildcard permission. Per-table DML grants need the SQL `TableExtractor` to also walk non-SELECT statements; today it only enumerates reads.
- **K8s reconciliation is conservative.** Local mode detects dead child PIDs at startup and respawns; K8s mode trusts the apiserver's liveness probe (pods without a Linux PID are kept as-is, with the `HealthProbe` catching drift after one tick). Implementing pod-status reconciliation requires `KubernetesQuackBackend.discoverExisting()` to wire into the apiserver.
- **Edge session caching trades latency for revocation lag.** Auth re-validation happens at the TTL boundary (`sessionTtlSec`, default 1h), not on every call. A revoked OIDC token still works for up to one TTL window - shrink the TTL or restart the manager for immediate effect.
- **Metrics, dashboards and cloud monitoring.** Manager exposes Prometheus metrics at `/metrics` by default, or pushes to AWS CloudWatch / Azure Monitor / GCP Cloud Monitoring instead (one sink at a time, picked via `metrics.sink`). See [observability/README.md](observability/README.md) for the sink selector, env vars, credential discovery chains, cardinality budget, and the Grafana 10.x dashboard at [observability/grafana-dashboard.json](observability/grafana-dashboard.json).

---

## License

Apache 2.0.

## Community

- **Questions / discussion** -> [Discord](https://discord.gg/VTxrj8KU)
- **Bug or feature** -> file an issue using the templates (the blank-issue path is disabled on purpose)

## Contributing

PRs welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the dev loop
(build, test, commit conventions, PR flow) and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for the community standards we
follow. Start with an issue labelled `good first issue` if you'd like a
small, well-scoped task.
