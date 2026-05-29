# 🦆 Quack on Demand

**A production-grade FlightSQL gateway in front of [DuckDB Quack](https://duckdb.org/docs/current/core_extensions/quack) + [DuckLake](https://duckdb.org/docs/extensions/ducklake.html).**

DuckDB ships with Quack, a minimal HTTP SQL endpoint that listens on `localhost`, generates a random auth token at startup, and explicitly recommends a reverse proxy in front of it for TLS, external auth, and authorization. Quack on Demand is that proxy with multi-tenancy, pluggable identity, table-level ACLs, role-aware routing, health probes, and a live admin UI built in.

![Admin console — live per-node metrics, statement history, ACL editor](assets/metrics.jpg)

---

## Features

- **Arrow Flight SQL edge** with auto-generated self-signed TLS (drop in a CA-signed cert for prod)
- **Multi-tenant pools** of Quack nodes. Each node is `READONLY`, `WRITEONLY`, or `DUAL`; the router classifies each statement and picks a compatible node
- **Pluggable authentication** through the vendored `AuthenticationService` chain:
  - Postgres / any JDBC backend (BCrypt-hashed passwords, free-form SQL template)
  - External JWT (HS256 / RS256 / public-key PEM)
  - OIDC providers: Keycloak (with ROPC), Google, Azure AD, AWS Cognito
- **Postgres-relational ACL** stored in `slkstate_acl_grant`, managed via REST, enforced per statement (SQL parser extracts table refs, the validator looks up grants for the user's principal)
- **Admin REST API** with an `X-API-Key` static key OR a session token minted via `/api/auth/login`
- **React admin console** at `/ui/` — tenant CRUD, pool CRUD, per-tenant ACL editor, live node dashboard (in-flight, total served, EWMA latency), admin-role gated
- **Single uber-jar** deployment; control-plane state lives next to DuckLake's metadata in the same Postgres database
- **Self-healing on restart** — dead Quack child processes are detected (PID + port probe) and respawned automatically before the edge accepts traffic
- **Every config key is overridable** via a `SL_QUACK_*` env var

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

---

## Quick start

For a full walkthrough of every deployment path (native, Docker against an
external Postgres, full compose stack) and the optional TPC-H seed step,
see **[`RUNNING.md`](RUNNING.md)**.

### Docker Run

Three paths, increasing in flexibility:

**1. `docker compose` (recommended for first run)** brings up Postgres + the manager together, with all persistent state bind-mounted to the host:

```bash
cp .env.example .env                          # tweak ports / auth / admin password
./scripts/run-docker-compose.sh               # pulls starlakeai/quack-on-demand:latest from Docker Hub
BUILD=1 ./scripts/run-docker-compose.sh       # local Dockerfile build instead (dev mode)
```

Bind mounts created next to the compose file:
- `./pgdata/`   — Postgres data dir (`slkstate_*` + `ducklake_*` tables)
- `./ducklake/` — DuckLake Parquet files written by Quack nodes
- `./certs/`    — auto-generated self-signed TLS cert + key

To seed the metastore with a TPCH dataset (uses DuckDB's `dbgen()` — no input files needed), either start with the loader flag or exec the loader against a running stack:

```bash
LOAD_TPCH=true ./scripts/run-docker-compose.sh                # SF=1 → ~6M lineitem rows
LOAD_TPCH=true TPCH_SF=10 ./scripts/run-docker-compose.sh

# Or against a stack that is already up:
docker compose exec quack /app/scripts/load-tpch-dbgen.sh
```

Tables land in `tpch.tpch1.{region,nation,customer,supplier,part,partsupp,orders,lineitem}` by default. Override `TPCH_SF` / `TPCH_SCHEMA` in `.env`.

**2. `scripts/run-docker.sh` (against an existing Postgres)** runs only the manager container, points it at an already-running Postgres (RDS, Cloud SQL, host-installed, etc.). Default pulls `starlakeai/quack-on-demand:latest`; `BUILD=1` builds the local Dockerfile and tags the result under the same name:

```bash
PG_HOST=my-rds.amazonaws.com PG_PASSWORD=*** \
  AUTH=true ADMIN_PASSWORD=change-me TLS=true \
  ./scripts/run-docker.sh

# Pin a specific tag (release or snapshot)
QUACK_VERSION=0.1.0 PG_HOST=… PG_PASSWORD=… ./scripts/run-docker.sh

# Build locally instead of pulling
BUILD=1 PG_HOST=… PG_PASSWORD=… ./scripts/run-docker.sh
```

`AUTH`, `TLS`, `ADMIN_*`, `API_KEY`, `DATA_PATH`, `CERTS_DIR`, port mappings — all overridable via env. Defaults to no-auth, no-TLS for fast smoke tests. DuckLake data persists at `$PWD/ducklake/` by default.

**3. Raw `docker run`** — for one-off invocations:

```bash
docker run --rm -p 20900:20900 -p 31338:31338 \
  -e SL_QUACK_PG_HOST=host.docker.internal \
  -e SL_QUACK_PG_PASSWORD=azizam \
  -e SL_QUACK_ADMIN_PASSWORD=change-me \
  -v $(pwd)/ducklake:/app/ducklake \
  -v $(pwd)/certs:/app/certs \
  starlakeai/quack-on-demand:latest
```

The runtime image bundles the `duckdb` CLI (so spawned Quack nodes can serve), `psql` (catalog bootstrap), and `openssl` (auto-generates the self-signed TLS cert on first boot). All `SL_QUACK_*` / `PROXY_*` env vars from `application.conf` are accepted. The default exposed ports are `20900` (REST + UI), `31338` (FlightSQL), and `21900–22500` (lease range for in-container Quack nodes).

### Behind a corporate proxy

Both the **image build** and the **container runtime** need outbound HTTP/HTTPS access. Three sources of egress traffic to be aware of:

| Step                  | What it fetches                                                                          | Where to set proxy                  |
|-----------------------|------------------------------------------------------------------------------------------|-------------------------------------|
| `docker build`        | Ubuntu apt mirrors, NodeSource, sbt/Maven, npm registry, the DuckDB CLI zip from GitHub  | `--build-arg` on the build          |
| Manager + node boot   | `INSTALL quack` (and the `tpch` / `ducklake` / `postgres` extensions) from `extensions.duckdb.org` | container env vars (`HTTP(S)_PROXY`)|
| Outbound from spawned Quack nodes | Same DuckDB extension registry on first node start                            | inherited from the container env    |

If your proxy is reachable on the host's loopback (e.g. `localhost:3128`), the container won't see it unless you give it host networking. On Linux that's `--network=host`; on Docker Desktop, use `host.docker.internal:<port>`.

**Build the image behind a proxy:**

```bash
docker build --network=host \
  --build-arg HTTP_PROXY=http://localhost:3128 \
  --build-arg HTTPS_PROXY=http://localhost:3128 \
  --build-arg NO_PROXY=localhost,127.0.0.1 \
  -t starlakeai/quack-on-demand:latest .
```

(The lowercase `http_proxy` / `https_proxy` form is also honoured; pass both if you're unsure — some tools only read one variant.)

**Run the container behind a proxy** — without these env vars, the manager (and every Quack node it spawns) will hang on the first `INSTALL quack` call:

```bash
docker run -d --name quack-on-demand --network=host \
  -e HTTP_PROXY=http://localhost:3128 \
  -e HTTPS_PROXY=http://localhost:3128 \
  -e NO_PROXY=localhost,127.0.0.1 \
  -e SL_QUACK_PG_HOST=localhost \
  -e SL_QUACK_PG_PASSWORD=*** \
  starlakeai/quack-on-demand:latest
```

**Configure the Docker daemon globally** instead — once, in `~/.docker/config.json` — so every `docker build` and `docker run` inherit the proxy without per-command flags:

```json
{
  "proxies": {
    "default": {
      "httpProxy":  "http://localhost:3128",
      "httpsProxy": "http://localhost:3128",
      "noProxy":    "localhost,127.0.0.1"
    }
  }
}
```

**Bake the extensions into the image** if you want offline-capable nodes (no proxy required at runtime). Add the install step to the build stage of `Dockerfile`:

```dockerfile
RUN duckdb -c "INSTALL quack; INSTALL tpch; INSTALL ducklake; INSTALL postgres;"
```

The downloaded extensions live under `$HOME/.duckdb/extensions/` and the runtime image will use them in place of fetching from the registry.

### Native Run Prerequisites

- JDK 17+ (to run the jar)
- A running Postgres instance (`localhost:5432`, default user `postgres` / password `azizam` — both overridable)
- The `duckdb` CLI on `$PATH` (for spawning Quack nodes locally)
- `psql` on `$PATH` (catalog DB auto-create)
- `openssl` (for auto-generated TLS certs)
- **`sbt` 1.x and `npm` 18+** — only when `BUILD=1` (assembling the jar from this checkout instead of downloading)

### Boot the manager

```bash
# Default: download latest release from Maven Central, cache under
# ~/.cache/quack-on-demand/, run it. REST :20900, FlightSQL :31338, TLS on.
./scripts/run-jar.sh

# Pin a specific version (release or -SNAPSHOT)
QUACK_VERSION=0.1.0           ./scripts/run-jar.sh
QUACK_VERSION=latest-snapshot ./scripts/run-jar.sh

# Local source build (requires sbt + npm)
BUILD=1 ./scripts/run-jar.sh
```

The script auto-generates a self-signed TLS cert at `certs/server-{cert,key}.pem` if missing, probes Postgres reachability, creates the catalog DB if it doesn't exist, and prints the URLs.

### Log in to the admin console

Open `http://localhost:20900/ui/`. The default admin credentials are:

```
admin@localhost.local / admin   (or just `admin` / `admin`)
```

Set `SL_QUACK_ADMIN_USERNAME=alice,bob` and `SL_QUACK_ADMIN_PASSWORD=…` to seed your own. Multiple comma-separated usernames are supported; all get the same password + role.

### Starter tenant + pool (auto-bootstrapped)

On every boot the manager creates a starter tenant `acme` with a pool `sales` (3 nodes: 1 WriteOnly + 1 ReadOnly + 1 Dual) if they don't already exist. `defaultTenant` / `defaultPool` are pointed at the same `acme` / `sales`, so a FlightSQL client that doesn't pass a tenant or `X-Pool` header lands on this pool out of the box. The bootstrap is idempotent — a restart with the same config is a no-op.

Override or disable via `SL_QUACK_BOOTSTRAP_*` env vars:

| Env var                          | Default | Purpose                                  |
|----------------------------------|---------|------------------------------------------|
| `SL_QUACK_BOOTSTRAP_ENABLED`     | `true`  | Set to `false` to skip the bootstrap.    |
| `SL_QUACK_BOOTSTRAP_TENANT`      | `acme`  | Tenant name.                             |
| `SL_QUACK_BOOTSTRAP_POOL`        | `sales` | Pool name.                               |
| `SL_QUACK_BOOTSTRAP_WRITEONLY`   | `1`     | WriteOnly nodes in the bootstrap pool.   |
| `SL_QUACK_BOOTSTRAP_READONLY`    | `1`     | ReadOnly nodes.                          |
| `SL_QUACK_BOOTSTRAP_DUAL`        | `1`     | Dual nodes.                              |

### Create a pool from the UI or the CLI

```bash
TOKEN=$(curl -sS -X POST http://localhost:20900/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

# Tenant + pool
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/create \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme"}'

curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/create \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","pool":"sales","size":3,
       "roleDistribution":{"writeonly":1,"readonly":1,"dual":1},
       "metastore":{}}'
```

### Connect clients

Four entry points, all served by the same manager process:

| Surface | URL | Use case |
|---|---|---|
| Admin UI | `http://localhost:20900/ui/` | tenant/pool CRUD, ACL editor, live node dashboard. Log in with `admin@localhost.local` / `admin` (or whatever you set in `.env`). |
| FlightSQL (JDBC/ADBC) | `jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true` | SQL clients (DBeaver, intellij DataGrip, Spark, custom apps). |
| REST API | `http://localhost:20900/api/*` | scripting tenant/pool/ACL changes. Login via `POST /api/auth/login` to get a session token. |
| Python load tester | `./scripts/loadtest/loadtest.py` | ADBC-based concurrency + latency benchmark. Defaults to a TPC-H mix; pair with the seeding step above. |

**FlightSQL credentials** — same as the admin user:

```
User:     admin@localhost.local      # or admin
Password: admin
```

> **URL scheme must match the server's TLS setting.** Native and the shipped compose `.env.example` default to TLS **on** → use `grpc+tls://` (JDBC) / `useEncryption=true`. The `run-docker.sh` path defaults to TLS **off** → use `grpc://` / `useEncryption=false`. Mismatch surfaces as *"tls: first record does not look like a TLS handshake"* (client-TLS against plaintext server) or *"connection reset"* (the inverse).

**Smoke-test the FlightSQL edge with the Python load tester** (the same one we use in CI):

```bash
pip install adbc_driver_flightsql adbc_driver_manager

# Default TLS-on server (native + shipped compose)
./scripts/loadtest/loadtest.py -w 2 -i 5

# Plaintext server (run-docker.sh default, or TLS=false in .env)
./scripts/loadtest/loadtest.py --url grpc://localhost:31338 -w 2 -i 5
```

For TLS verification instead of `disableCertificateVerification`, import `certs/server-cert.pem` into your JDBC client's trust store. The cert is auto-generated on first boot and reused across restarts.

See [`RUNNING.md`](RUNNING.md#connecting-clients) for the full loadtest parameter table (workers, iterations, warmup, schema, custom query) and the heavy/stress example ladder.

### Stop everything

```bash
./scripts/stop-quack-on-demand.sh
```


---

## Configuration

Every scalar in `src/main/resources/application.conf` accepts a matching `SL_QUACK_*` env-var override. The most-used:

| Setting | Env var | Default |
|---|---|---|
| Manager REST port | `SL_QUACK_ON_DEMAND_PORT` | `20900` |
| FlightSQL edge port | `PROXY_PORT` | `31338` |
| FlightSQL TLS on/off | `PROXY_TLS_ENABLED` | `true` |
| State backend | `SL_QUACK_STATE_STORAGE` | `postgres` |
| Metastore host | `SL_QUACK_PG_HOST` | `localhost` |
| Metastore database | `SL_QUACK_PG_DBNAME` | `tpch` |
| Static admin key | `SL_QUACK_API_KEY` | unset |
| Admin usernames | `SL_QUACK_ADMIN_USERNAME` | `admin@localhost.local,admin` |
| Admin password | `SL_QUACK_ADMIN_PASSWORD` | `admin` |
| Enable DB auth | `SL_QUACK_AUTH_DB_ENABLED` | `false` |
| Enable ACL | `SL_QUACK_ACL_ENABLED` | `false` |

Pluggable auth backends, ACL store paths, K8s runtime, JWT keys — see `application.conf` for the full surface.

---

## REST API

All endpoints under `/api/*` require a valid `X-API-Key` (either the static `SL_QUACK_API_KEY` or a UI session token from `/api/auth/login`). `/health` and `/api/config/client` are open; `/ui/*` is open (the React app gates itself).

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

---

## Admin UI

| Page | What it shows |
|---|---|
| **Login** | Username/password, admin-role gated |
| **/ ** (Tenants) | List + create + delete tenants, effective metastore preview |
| **/tenant/:tenant** | Tenant detail · pools · storage · **ACL grants editor** |
| **/pool/:tenant/:pool** | Pool nodes, JDBC/ODBC/ADBC connection strings, scale/stop |
| **/nodes** | Live cluster dashboard: per-node `inFlight`, `totalServed`, EWMA latency, role + health badges, per-tenant filter, auto-refresh every 2s |

---

## ACL model

Grants live in `slkstate_acl_grant` with shape:

```sql
(tenant_id, principal, catalog_name, schema_name, table_name, permission)
```

- `principal` follows the `type:name` convention — `user:alice`, `group:engineers`, `role:admin`
- Any of `catalog_name / schema_name / table_name` may be `NULL` to act as a wildcard
- Permissions: `SELECT | INSERT | UPDATE | DELETE | ALL` (`ALL` always wins)

When `acl.enabled=true` and `stateStorage=postgres`, `PostgresAclValidator` parses each incoming SQL statement, extracts the table references, and queries the table for matching grants. Non-SELECT statements (DDL / DML) are denied unless the principal holds a wildcard `ALL` grant.

**Principal expansion.** At validation time the authenticated session's `username`, `groups`, and `role` are expanded into `user:<username>`, `group:<g>` (one per group), and `role:<r>` principals; a grant matches if *any* of them does. So an OIDC user `alice` with groups `[engineers, analysts]` and role `viewer` triggers lookups for four principals at once — write your grants against whichever level of identity is stable.

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

- **Scala 3.7** — `enum`, given/using, derived ConfigReader
- **Cats Effect** + **fs2** for the runtime
- **Tapir** + **HTTP4s Ember** for the REST API
- **Apache Arrow Flight SQL 14** + `arrow-memory-unsafe` (pinned via `-Darrow.allocation.manager.type=Unsafe`)
- **DuckDB JDBC** embedded in the manager to bridge to Quack's binary wire (via `quack_query()` table function)
- **DuckLake** for shared catalog metadata + S3/FS data storage
- **PostgreSQL** for catalog metadata + control-plane state (`slkstate_*` tables)
- **React 18** + **Vite** + **react-router-dom** for the SPA (no UI framework — single dark-theme CSS file)

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
├── stop-quack-on-demand.sh        # Graceful SIGTERM → SIGKILL escalation
├── start-quack-ducklake.sh        # Standalone single-node Quack for testing
├── load-tpch-dbgen.sh             # Generate TPC-H (SF override via $SF) into the metastore via DuckDB's dbgen()
└── spawn-quack-node.sh            # Called by LocalQuackBackend; do not invoke directly
ui/
└── src/                           # React SPA, built into src/main/resources/ui
```

---

## Operational notes

Defaults and design choices an operator should be aware of before going to production:

- **FlightSQL edge is authenticated by default; the admin password is `admin`.** `auth.database.enabled = true` and the manager seeds `slkstate_user` with the configured admin at boot. The default credentials (`admin@localhost.local / admin`) are fine for first-run; **rotate via `SL_QUACK_ADMIN_PASSWORD` before exposing the edge.**
- **REST API is OPEN by default.** The control-plane REST API (`/api/...`) is a separate gate. Until you set `SL_QUACK_API_KEY`, anonymous requests are accepted. The manager logs a loud warning at startup. Set the env var, or restrict the listening interface, before exposing the manager beyond `localhost`.
- **DML grants in ACL mode are coarse-grained.** `INSERT`/`UPDATE`/`DELETE` are denied unless the principal holds a wildcard `ALL` grant. Per-table DML grants need the ACL `TableExtractor` to also walk non-SELECT statements; today it only enumerates reads.
- **K8s reconciliation is conservative.** Local mode detects dead child PIDs at startup and respawns; K8s mode trusts the apiserver's liveness probe (pods without a Linux PID are kept as-is, with the `HealthProbe` catching drift after one tick). Implementing pod-status reconciliation requires `KubernetesQuackBackend.discoverExisting()` to wire into the apiserver.
- **Edge session caching trades latency for revocation lag.** Auth re-validation happens at the TTL boundary (`sessionTtlSec`, default 1h), not on every call. A revoked OIDC token still works for up to one TTL window — shrink the TTL or restart the manager for immediate effect.

See [`docs/superpowers/FOLLOWUPS.md`](docs/superpowers/FOLLOWUPS.md) for the full triaged list, including recently-closed items (admin user seeding, ACL Phase 2, Postgres state store, reconcile-on-restart, group/role propagation, etc.).

---

## License

Apache 2.0.

## Contributing

Issues + PRs welcome.
