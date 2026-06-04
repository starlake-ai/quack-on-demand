# Quack on Demand

**A production-grade FlightSQL gateway in front of [DuckDB Quack](https://duckdb.org/docs/current/core_extensions/quack) + [DuckLake](https://duckdb.org/docs/extensions/ducklake.html).**

DuckDB just shipped [Quack](https://duckdb.org/docs/current/core_extensions/quack), a new client-server protocol that lets DuckDB instances talk to each other over HTTP/2. This is a big deal: DuckDB is no longer just an embedded library, it can now be deployed as a shared server.

But Quack is intentionally minimal. It ships with a single static token for auth, no multi-tenancy, no authorization model, and the client side is DuckDB-specific. The docs themselves recommend putting infrastructure in front of it before any serious deployment.

Quack on Demand is that infrastructure: an **Arrow Flight SQL** edge (any JDBC/ODBC/ADBC client works, not just DuckDB), **multi-tenant pools** with role-aware routing, **pluggable identity** (DB / JWT / OIDC), a **first-class RBAC graph** (users · groups · roles · table permissions · pool grants) enforced per statement, and a **live admin UI**. All in a single uber-jar that shares Postgres with DuckLake.

![Admin console - live per-node metrics, statement history, ACL editor](assets/metrics.jpg)

---

## Features

- **Arrow Flight SQL edge** with auto-generated self-signed TLS (drop in a CA-signed cert for prod)
- **Multi-tenant pools** of Quack nodes. Each node is `READONLY`, `WRITEONLY`, or `DUAL`; the router classifies each statement and picks a compatible node
- **Pluggable authentication** through the vendored `AuthenticationService` chain:
  - Postgres / any JDBC backend (BCrypt-hashed passwords, free-form SQL template)
  - External JWT (HS256 / RS256 / public-key PEM)
  - OIDC providers: Keycloak (with ROPC), Google, Azure AD, AWS Cognito
- **First-class RBAC graph** in `qodstate_role` / `qodstate_role_permission` / `qodstate_group` / `qodstate_user_*` / `qodstate_pool_permission`. Two gates run at handshake (user-scope, pool-access); per statement the SQL parser extracts table refs and matches them against the cached **EffectiveSet** pinned on the connection. Superusers (`qodstate_user.tenant IS NULL`) bypass both layers
- **Admin REST API** with an `X-API-Key` static key OR a session token minted via `/api/auth/login`
- **React admin console** at `/ui/` - tenant CRUD, pool CRUD, dedicated **Users page** (Users · Groups · Roles · Identities tabs) with a per-user "Effective permissions" drilldown, live node dashboard (in-flight, total served, EWMA latency), admin-role gated
- **Single uber-jar** deployment; control-plane state lives next to DuckLake's metadata in the same Postgres database
- **Self-healing on restart** - dead Quack child processes are detected (PID + port probe) and respawned automatically before the edge accepts traffic
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
                                                       │  │  • Per-statement ACL   │  │
                                                       │  │    (RBAC EffectiveSet) │  │
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
                                                │   Postgres (DuckLake metadata)      │
                                                │   • ducklake_*  (catalog)           │
                                                │   • qodstate_tenant / _tenant_db    │
                                                │     / _pool / _node                 │
                                                │   • qodstate_user                   │
                                                │   • qodstate_tenant_identity        │
                                                │   • qodstate_role / _role_permission│
                                                │   • qodstate_group / _user_group /  │
                                                │     _user_role / _group_role        │
                                                │   • qodstate_pool_permission        │
                                                │   • slkstate_pool_state (file mode) │
                                                └─────────────────────────────────────┘
                                                                  +
                                                          object/file storage
                                                          (S3, GCS, FS, …)
```

---

## Quick start

Zero to first query in under 5 minutes — see **[`QUICKSTART.md`](QUICKSTART.md)** for the step-by-step. The short version:

```bash
cp .env.example .env                            # tweak ports / auth / admin password
LOAD_TPCH=1 ./scripts/run-docker-compose.sh     # pulls starlakeai/quack-on-demand:latest + seeds TPC-H SF=1
```

That brings up Postgres + the manager and seeds the DuckLake catalog with TPC-H at scale factor 1 (~6M lineitem rows) into schema `tpch.tpch1`. The admin UI is on `http://localhost:20900/ui/` (default login `admin` / `admin` - change it in `.env`); the FlightSQL edge on `localhost:31338`.

Smoke-test the FlightSQL edge with the Python load tester:

```bash
pip install adbc_driver_flightsql adbc_driver_manager

# TLS-on server (compose default)
python3 ./scripts/loadtest/loadtest.py -w 2 -i 5

# Plaintext server (TLS=false in .env, or scripts/run-docker.sh default)
./scripts/loadtest/loadtest.py --url grpc://localhost:31338 -w 2 -i 5
```

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
| Metastore database | `QOD_PG_DBNAME` | `qod` |
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
| `POST` | `/api/membership/user-role/{add,remove}`   | wire user → role |
| `POST` | `/api/membership/user-group/{add,remove}`  | wire user → group |
| `POST` | `/api/membership/group-role/{add,remove}`  | wire group → role |
| `GET`  | `/api/pool/permission/list?tenant=&userId=&groupId=` | list pool grants |
| `POST` | `/api/pool/permission/grant`| grant a `(tenant, pool? )` to a user OR a group (`pool=null` = every pool in tenant) |
| `POST` | `/api/pool/permission/revoke` | revoke a pool grant |
| `GET`  | `/api/identity/list?tenantId=…` | list verified-identity → tenant rows |
| `POST` | `/api/identity/create`      | add an `(issuer, externalId)` → tenant mapping |
| `POST` | `/api/identity/delete`      | revoke an identity row |
| `GET`  | `/api/config/client`        | discovery: edge host/port/TLS (open) |
| `GET`  | `/health`                   | liveness + pool/node counts (open) |

---

## Admin UI

| Page | What it shows |
|---|---|
| **Login** | Username/password, admin-role gated |
| **/ ** (Tenants) | List + create + delete tenants, effective metastore preview |
| **/tenant/:tenant** | Tenant detail · Databases · Pools tabs |
| **/users** | Tenant selector + **Users · Groups · Roles · Identities** tabs. Per-user "Effective…" drilldown showing the closure of roles, groups, table permissions and pool grants |
| **/pool/:tenant/:pool** | Pool nodes, JDBC/ODBC/ADBC connection strings, scale/stop |
| **/nodes** | Live cluster dashboard: per-node `inFlight`, `totalServed`, EWMA latency, role + health badges, per-tenant filter, auto-refresh every 2s |

---

## Access control (RBAC)

Quack on Demand runs a first-class role-based access control model out of the box. Free-text `user:alice` / `group:eng` principals are gone — every grant is a relational FK in Postgres.

### Entities

- **User** (`qodstate_user`) — identified by `(tenant, username)`. A row with `tenant IS NULL` is a **superuser**: it can authenticate against any tenant via FlightSQL and bypasses both handshake and per-statement gates. Tenant-scoped principals carry a non-empty `tenant`.
- **Role** (`qodstate_role`) — per-tenant container for [TablePermission](#) rows. Every new tenant is auto-seeded with a built-in `admin` role plus a `*.*.* ALL` permission, all in one transaction (`PoolSupervisor.createTenant`).
- **Group** (`qodstate_group`) — per-tenant bundle of users that inherits role memberships and pool grants together.
- **TablePermission** (`qodstate_role_permission`) — `(role_id, catalog, schema, table, verb)`. `verb ∈ {SELECT, INSERT, UPDATE, DELETE, ALL}`; `*` in any of catalog/schema/table is the literal wildcard. **This is the only place table-level grants live.**
- **PoolPermission** (`qodstate_pool_permission`) — grants a user OR a group access to a `(tenant, pool?)`. `pool_id NULL` means "every pool in this tenant". Exactly one of `user_id` / `group_id` is set (DB CHECK enforced).
- **Memberships** — `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role` are bare FK pairs (cascade on delete).

### Two gates at the FlightSQL edge

1. **Handshake gate** (once per session, `PoolSupervisor.authorizeHandshake`):
   - **user-scope**: `user.tenant IS NULL OR user.tenant == target.tenant`, else `PERMISSION_DENIED`.
   - **pool-access**: at least one effective pool grant matches `(target.tenant, target.pool)` or `(target.tenant, *)`.
   The closure of `(roles, groups, table permissions, pool grants)` is computed once here and pinned onto `ConnectionContext` as an [`EffectiveSet`](#).
2. **Per-statement gate** (every RPC, `PostgresAclValidator`):
   - Reads `effectiveSet.permissions` from the pinned set — **no DB round-trip per statement**.
   - SQL parser extracts the referenced tables; each must be covered by a permission whose verb is the matching DML verb OR `ALL`, with `(catalog, schema, table)` matched literally or via `*`.

### Effective-set closure

For a tenant-scoped user `U`:

```
effective_roles(U) = direct_roles(U) ∪ ⋃ roles(g)   for g ∈ groups(U)
effective_pools(U) = direct_pools(U) ∪ ⋃ pools(g)   for g ∈ groups(U)
                     (a row with pool=NULL matches every pool in tenant)
effective_perms(U) = ⋃ permissions(r)               for r ∈ effective_roles(U)
```

Superuser (`U.tenant IS NULL`) bypasses both `effective_pools` and `effective_perms`.

### Examples

Create a role, attach a permission, link a user to it:

```bash
TOK=...     # session token from /api/auth/login

# create the role
curl -sS -H "X-API-Key: $TOK" -X POST http://localhost:20900/api/role/create \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"sales-reader"}'

# attach a SELECT permission on tpch.main.customer
curl -sS -H "X-API-Key: $TOK" -X POST http://localhost:20900/api/role/permission/grant \
  -H 'Content-Type: application/json' \
  -d '{"roleId":"r-XXXXXXXX","catalog":"tpch","schema":"main","table":"customer","verb":"SELECT"}'

# wire alice to the role
curl -sS -H "X-API-Key: $TOK" -X POST http://localhost:20900/api/membership/user-role/add \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u-XXXXXXXX","roleId":"r-XXXXXXXX"}'

# grant alice access to the sales pool
curl -sS -H "X-API-Key: $TOK" -X POST http://localhost:20900/api/pool/permission/grant \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","poolId":"p-XXXXXXXX","userId":"u-XXXXXXXX"}'
```

Or do all of the above from the admin UI's **/users** page (Users · Groups · Roles tabs) — the per-user "Effective…" button surfaces the resolved closure for verification.

### Caveats

- **DML coverage is coarse today.** The SQL parser's table extractor walks SELECT statements. INSERT/UPDATE/DELETE require a covering `ALL` grant; finer per-table DML coverage is on the roadmap.
- **DDL requires `*.*.* ALL`.** CREATE / ALTER / DROP / TRUNCATE go through the wildcard-ALL gate; first-class DDL grants will land when the operator concern materializes.
- **`acl.enabled=false`** falls back to allow-all (handshake gates still run, table-permission gate is skipped). The handshake gates are unconditional.

---

## Tech stack

- **Scala 3.7** - `enum`, given/using, derived ConfigReader
- **Cats Effect** + **fs2** for the runtime
- **Tapir** + **HTTP4s Ember** for the REST API
- **Apache Arrow Flight SQL 14** + `arrow-memory-unsafe` (pinned via `-Darrow.allocation.manager.type=Unsafe`)
- **DuckDB JDBC** embedded in the manager to bridge to Quack's binary wire (via `quack_query()` table function)
- **DuckLake** for shared catalog metadata + S3/FS data storage
- **PostgreSQL 16+** for catalog metadata + control-plane state (Liquibase-managed `qodstate_*` tables for tenants/pools/users/RBAC; legacy `slkstate_pool_state` blob when `stateStorage=file`)
- **React 18** + **Vite** + **react-router-dom** for the SPA (no UI framework - single dark-theme CSS file)

---

## Development

### Prerequisites

- **JDK 17+** (the assembly jar carries `--add-opens` in its manifest for Arrow's unsafe allocator).
- **Postgres 16+** reachable for the control plane. `scripts/run-jar.sh` refuses to start against an older server (the `0006-rbac` changeset uses `gen_random_uuid()` + `DROP DATABASE ... WITH (FORCE)`, both PG13+).
- **DuckDB 1.5.3 shared library** for the native FlightSQL client (`nativeClient = true` by default). The Java side ships `libquackwire.so` bundled in the jar; it `dlopen`s `libduckdb.so` at runtime. Two ways:

  ```bash
  # A. install system-wide (matches libquackwireVersion in build.sbt)
  curl -L -o /tmp/libduckdb.zip \
    https://github.com/duckdb/duckdb/releases/download/v1.5.3/libduckdb-linux-amd64.zip
  unzip -o /tmp/libduckdb.zip -d /tmp/libduckdb-1.5.3
  sudo install -m 0755 /tmp/libduckdb-1.5.3/libduckdb.so /usr/local/lib/
  sudo ldconfig

  # B. fall back to the embedded-DuckDB path (single-mutex, throughput-capped)
  QOD_NATIVE_CLIENT=false ./scripts/run-jar.sh
  ```

  The DuckDB ABI is pinned by `libquackwireVersion` in `build.sbt`; bumping that pin requires installing the matching `libduckdb.so`.

### Build / run loop

```bash
# Run the manager from sbt (forks JVM so `--add-opens` takes effect)
sbt run

# Run the test suite (714+ tests; Postgres-backed specs cancel cleanly when
# SL_TEST_PG_* env vars don't reach a live Postgres)
sbt test

# Build the uber-jar at distrib/quack-on-demand-assembly-*.jar (UI bundles in)
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
│   │   └── sql/                   # PostgresAclValidator (per-statement gate)
│   ├── ondemand/                  # Pool supervisor + handlers + state
│   │   ├── api/                   # Tapir endpoints (Endpoints + RbacEndpoints) + DTOs + handlers
│   │   ├── rbac/                  # RbacResolver, EffectiveSet, AuthorizedHandshake
│   │   ├── runtime/               # Local + Kubernetes backends
│   │   └── state/                 # File + Postgres state stores (incl. qodstate_* CRUD)
│   └── route/                     # StatementClassifier + Router + RoleMatcher
└── acl/                           # Vendored SQL parser still used by PostgresAclValidator
                                   # (the legacy file-based YAML grant store is unwired)
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

- **FlightSQL edge is authenticated by default; the admin password is `admin`.** `auth.database.enabled = true` and the manager seeds `qodstate_user` with the configured admin (as a superuser: `tenant IS NULL`) at boot. The default credentials (`admin@localhost.local / admin`) are fine for first-run; **rotate via `QOD_ADMIN_PASSWORD` before exposing the edge.**
- **REST API is OPEN by default.** The control-plane REST API (`/api/...`) is a separate gate. Until you set `QOD_API_KEY`, anonymous requests are accepted. The manager logs a loud warning at startup. Set the env var, or restrict the listening interface, before exposing the manager beyond `localhost`.
- **DML and DDL grants are coarse-grained today.** `INSERT`/`UPDATE`/`DELETE` and any DDL are denied unless the principal holds a wildcard `*.*.* ALL` permission on one of their effective roles. Per-table DML extraction is on the roadmap; DDL grants will follow when the operator concern lands.
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
