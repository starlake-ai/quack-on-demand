---
name: quack-on-demand
description: Operate a quack-on-demand FlightSQL gateway - boot/stop the manager, manage tenants/pools/ACLs, inspect nodes, run load tests
---

# Quack on Demand

Quack on Demand is a multi-tenant FlightSQL gateway in front of DuckDB Quack + DuckLake. The manager exposes a REST control plane (`/api/...`) and a React admin UI (`/ui/...`) on the same port (default `:20900`), and a FlightSQL edge on a separate port (default `:31338`). Pools of Quack nodes are spawned as local subprocesses or K8s pods.

Use this skill when the user wants to:
- Boot, restart, or stop the manager
- Create / scale / delete tenants and pools
- Grant / revoke ACLs
- Inspect node health, throughput, latency
- See what SQL recently ran and where
- Run load tests
- Diagnose typical failure modes (dead nodes, expired sessions, ACL denials)

## Repo layout (the bits operators touch)

- `scripts/run-jar.sh` - boot from the uber-jar; `BUILD=1` runs `sbt assembly` first
- `scripts/stop-jar.sh` - SIGTERM → wait → SIGKILL
- `scripts/loadtest/loadtest.py` - Python FlightSQL load tester (ADBC driver)
- `scripts/start-quack-ducklake.sh` - standalone single-node Quack for testing (no manager)
- `scripts/load-tpch-dbgen.sh` - generate TPCH (SF=1 by default; override via `SF=10`) into the metastore using DuckDB's `dbgen()` table function; self-skips when `lineitem` is already populated
- `src/main/resources/application.conf` - config (every key has a `QOD_*` env-var override)
- `docs/superpowers/FOLLOWUPS.md` - triaged backlog
- `README.md` - full feature list + operational notes

## Booting

```bash
# Default: TLS edge, DB auth on, Postgres state, admin user seeded
./scripts/run-jar.sh

# Build the uber-jar first
BUILD=1 ./scripts/run-jar.sh

# Disable DB auth (UI then skips the login screen)
QOD_AUTH_DB_ENABLED=false ./scripts/run-jar.sh

# Disable TLS on the FlightSQL edge
PROXY_TLS_ENABLED=false ./scripts/run-jar.sh

# Stop everything
./scripts/stop-jar.sh
```

The start script is idempotent on CWD (anchors at the repo root). Default credentials: `admin@localhost.local` / `admin` (rotate via `QOD_ADMIN_PASSWORD`). The manager logs `auth: providers configured` when DB auth is on, and `auth: OPEN` otherwise.

On every boot the manager bootstraps a starter tenant + pool: tenant `acme`, pool `sales`, 3 nodes (1 WriteOnly + 1 ReadOnly + 1 Dual). `defaultTenant`/`defaultPool` are pointed at the same pair so unrouted FlightSQL requests land here. Idempotent - already-existing tenant/pool are left alone. Override with `QOD_BOOTSTRAP_{TENANT,POOL,WRITEONLY,READONLY,DUAL}` or disable with `QOD_BOOTSTRAP_ENABLED=false`.

## Auth flow (REST + UI)

The REST API has two acceptable credentials:
1. **Static** `X-API-Key` header matching `QOD_API_KEY` (if set; not set by default)
2. **UI session token** minted via `POST /api/auth/login`

```bash
# Get a session token (admin role required)
TOKEN=$(curl -sS -X POST http://localhost:20900/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')

# Use it on every /api/* call
curl -H "X-API-Key: $TOKEN" http://localhost:20900/api/pool/list
```

If `QOD_API_KEY` is unset, the manager logs a loud warning at startup and `/api/...` accepts anonymous traffic - fine for local dev, never for prod.

## Tenant + pool management

```bash
# List tenants
curl -sS -H "X-API-Key: $TOKEN" http://localhost:20900/api/tenant/list | python3 -m json.tool

# Create a tenant with metastore overrides
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/create \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme","metastore":{"dbName":"tpch","schemaName":"tpch1"}}'

# Create a pool (1 WriteOnly + 1 ReadOnly + 1 Dual = 3 nodes)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/create \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","pool":"sales","size":3,
       "roleDistribution":{"writeonly":1,"readonly":1,"dual":1},
       "metastore":{}}'

# Scale up
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/scale \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","pool":"sales","targetSize":6,
       "roleDistribution":{"writeonly":1,"readonly":2,"dual":3}}'

# Stop a pool (force=true skips graceful drain)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/stop \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","pool":"sales","force":true}'

# Delete a tenant (must have no pools first)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/delete \
  -H 'Content-Type: application/json' -d '{"name":"acme"}'
```

## ACL grants (Postgres-relational)

Grants live in `slkstate_acl_grant` in the same Postgres database as DuckLake's metadata. Endpoints are mounted only when `stateStorage=postgres` (the default).

```bash
# Grant SELECT on tpch.tpch1.customer to user:alice
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/acl/grant/create \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"default","principal":"user:alice",
       "catalogName":"tpch","schemaName":"tpch1","tableName":"customer",
       "permission":"SELECT"}'

# Wildcard ALL for the admin role (NULL catalog/schema/table = any)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/acl/grant/create \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"default","principal":"role:admin","permission":"ALL"}'

# List + delete
curl -sS -H "X-API-Key: $TOKEN" 'http://localhost:20900/api/acl/grant/list?tenant=default'
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/acl/grant/delete/7
```

Principal format is `type:name` - `user:alice`, `group:engineers`, `role:admin`. At validation time the authenticated session expands into `user:<username>` + `group:<g>` per group + `role:<r>`; a grant matches any of them.

ACL is *off* by default (`acl.enabled=false`). Flip with `QOD_ACL_ENABLED=true` to actually enforce.

## Node status + metrics

```bash
# Live node table (used by the UI)
curl -sS -H "X-API-Key: $TOKEN" http://localhost:20900/api/pool/list \
  | python3 -c "import sys,json; d=json.load(sys.stdin); \
    [print(f'{n[\"nodeId\"]:28s} role={n[\"role\"]:9s} healthy={n[\"healthy\"]} \
served={n[\"totalServed\"]:5d} p50={n[\"p50Ms\"]:4.0f} p95={n[\"p95Ms\"]:4.0f} p99={n[\"p99Ms\"]:4.0f}') \
     for p in d['pools'] for n in p['nodes']]"

# Recent statement history (newest first)
curl -sS -H "X-API-Key: $TOKEN" 'http://localhost:20900/api/node/statements?limit=20' \
  | python3 -m json.tool
```

Per-node fields surfaced via `/api/pool/list`:
- `inFlight` - currently executing statements
- `totalServed` - lifetime counter since manager start
- `avgDurationMs` - EWMA latency
- `p50Ms`/`p95Ms`/`p99Ms` - rolling 256-sample window
- `healthy` / `draining` - tracker flags

## Load testing

```bash
# Defaults: 8 workers × 100 iterations against the live edge (TLS on)
./scripts/loadtest/loadtest.py

# Higher concurrency
./scripts/loadtest/loadtest.py --workers 24 --iterations 50 --warmup 5

# Custom credentials / URL
LT_USER=alice LT_PASSWORD=secret ./scripts/loadtest/loadtest.py

# Single query repeated
LT_QUERY='SELECT count(*) FROM lineitem' ./scripts/loadtest/loadtest.py
```

Reports throughput, success rate, latency percentiles (p50/p95/p99). Default workload is a TPCH-H subset (Q1, Q3, Q5, Q6, Q10, Q12, Q14) using the standard spec parameters.

The Python script needs `pip install adbc_driver_flightsql adbc_driver_manager pyarrow`. The auto-install on first run prints the right command if anything is missing.

## Typical failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `/api/*` returns 401 | `QOD_API_KEY` is set but the header is missing/wrong | Pass `X-API-Key: <key>` or log in via `/api/auth/login` |
| `no node with role READONLY or DUAL` | All nodes flipped unhealthy (port unreachable) | Check `pgrep -fl spawn-quack-node`; if 0, run `stop` + `start` (reconcile respawns) |
| `access denied: missing SELECT grant on ...` | ACL is enabled and the user has no matching grant | Add the grant via `/api/acl/grant/create` or set `QOD_ACL_ENABLED=false` |
| `session expired; please reconnect` | Bearer token unknown (manager restarted between calls) | Re-login or pass Basic credentials |
| `Could not connect to server` for `http://127.0.0.1:21NNN/quack` | Quack child died after manager restart | Reconcile respawns on next boot; until then `pool/stop` + `pool/create` |
| Python load test: "PyArrow not installed" | Missing pyarrow | `pip install --break-system-packages pyarrow` on macOS |
| Manager (or spawned node) hangs at startup right after `BaseAllocator` log line, java pegged at 100% CPU | `INSTALL quack` is blocked by a corporate proxy - DuckDB is silently retrying to fetch the extension from `extensions.duckdb.org` | Pass `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` env vars to the process (container `-e` or shell env). See README "Behind a corporate proxy". |

## Where state lives

- **Tenants + pools** - `slkstate_pool_state` (single JSONB row) in the Postgres database named by `defaultMetastore.dbName` (default `tpch`)
- **Admin users** - `slkstate_user` (bcrypt-hashed passwords)
- **ACL grants** - `slkstate_acl_grant`
- **DuckLake catalog metadata** - `ducklake_*` tables in the same database
- **DuckLake data files** - `defaultMetastore.dataPath` on disk (or s3://, gs://, az://)
- **Self-signed TLS cert** - `certs/server-{cert,key}.pem` (auto-generated by `openssl req -x509` on first boot if missing)
- **Manager log on startup script invocation** - `/tmp/quack-startup.log`

## When operating

- The default admin password is `admin`. Rotate via `QOD_ADMIN_PASSWORD` before exposing the edge.
- The REST API is OPEN by default (no `QOD_API_KEY` set). Set the env var or restrict the listening interface before going beyond localhost.
- All scalars in `application.conf` have matching `QOD_*` env-var overrides. Prefer env vars over editing the file (the conf is bundled into the jar at build time).
- When in doubt about state, check `docs/superpowers/FOLLOWUPS.md` - it's the authoritative list of known issues and recently-closed work, headed by a `HEAD <sha>` line so you can see the baseline.

## Common UI URLs

- `http://localhost:20900/ui/` → Nodes dashboard (landing page)
- `http://localhost:20900/ui/tenants` → Tenants list
- `http://localhost:20900/ui/tenant/<tenant>` → tenant detail + ACL editor
- `http://localhost:20900/ui/pool/<tenant>/<pool>` → per-pool nodes + JDBC URLs