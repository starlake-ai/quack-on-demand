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
# Grant RO (read-only) on tpch.tpch1.customer to user:alice
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/acl/grant/create \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"default","principal":"user:alice",
       "catalogName":"tpch","schemaName":"tpch1","tableName":"customer",
       "permission":"RO"}'

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

## Federation - external catalogs via DuckDB extensions

Quack-on-Demand supports per-tenant-db federated catalogs that attach external sources (Postgres, S3, Iceberg, any DuckDB extension) under DuckDB catalog aliases. Existing RBAC covers federated tables - a `RolePermission(catalog='fedpg', schema='public', table='orders', verb='RO')` grants read access to a federated alias just like a DuckLake table.

### Tenant-db kinds

When creating a tenant-db, pick a `kind`:

- `ducklake` (default) - Postgres metastore + object-store dataPath. Production multi-node persistence.
- `duckdb-file` - Local `.duckdb` file at `dataPath`. Single-node only (file must exist on every node).
- `memory` - No persistent default catalog. Use with `defaultDatabase` pointing at a federated alias.

Example: create an in-memory tenant-db that only serves federated sources.

```bash
curl -X POST -H 'X-API-Key: '"$API_KEY" -H 'Content-Type: application/json' \
  "$MGR/api/database/create" \
  -d '{
    "tenant": "acme",
    "name": "fed",
    "kind": "memory",
    "metastore": {},
    "dataPath": "",
    "defaultDatabase": "fedpg",
    "defaultSchema": "public"
  }'
```

### Register a federated source

```bash
curl -X POST -H 'X-API-Key: '"$API_KEY" -H 'Content-Type: application/json' \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources" \
  -d '{
    "alias": "fedpg",
    "description": "Prod warehouse Postgres",
    "setupSql": "INSTALL postgres; LOAD postgres; CREATE OR REPLACE SECRET fedpg_sec (TYPE POSTGRES, HOST '\''pg.prod'\'', PORT 5432, DATABASE '\''warehouse'\'', USER '\''svc_qod'\'', PASSWORD '\''{{secret.PG_PWD}}'\''); ATTACH '\'''\'' AS {{alias}} (TYPE POSTGRES, SECRET fedpg_sec, READ_ONLY);"
  }'
```

Placeholders:
- `{{alias}}` - replaced with the source's `alias` field.
- `{{secret.NAME}}` - replaced with the resolved value of the secret named `NAME`.

### Add a Postgres-backed secret

```bash
curl -X PUT -H 'X-API-Key: '"$API_KEY" -H 'Content-Type: application/json' \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/fedpg/secrets" \
  -d '{"name": "PG_PWD", "value": "hunter2"}'
```

Or a secret backed by an external store (env var, AWS Secrets Manager, etc.):

```bash
curl -X PUT -H 'X-API-Key: '"$API_KEY" -H 'Content-Type: application/json' \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/fedpg/secrets" \
  -d '{"name": "PG_PWD", "externalRef": "vault:secret/data/qod/fedpg#password"}'
```

### Switch the secret resolver

`secretStore = postgres | env | aws-sm | gcp-sm | azure-kv | vault`. Set via `QOD_FEDERATION_SECRET_STORE=env` (and per-backend config keys). The four KMS backends are stubbed in v1; calling `resolve()` raises `NotImplementedError`. To enable one, fill in the corresponding resolver class with the real SDK call.

`externalRef` formats:

| Backend  | Format                                                       |
| -------- | ------------------------------------------------------------ |
| env      | `env:SL_QOD_SECRET_FOO`                                      |
| aws-sm   | `aws-sm:arn:aws:secretsmanager:...` or `aws-sm:name#jsonKey` |
| gcp-sm   | `gcp-sm:projects/<p>/secrets/<name>/versions/latest`         |
| azure-kv | `azure-kv:<secretName>` (vault URL from config)              |
| vault    | `vault:secret/data/<path>#<key>`                             |

### Export / import as YAML

Export (`***REDACTED***` replaces every value-backed secret; `externalRef` is left as-is):

```bash
curl -H 'X-API-Key: '"$API_KEY" \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/yaml/export" > fed.yaml
```

Re-import after editing. Secrets with `value: "***REDACTED***"` (and no `externalRef`) reuse the existing row's value, so a round-trip never requires re-typing passwords:

```bash
curl -X POST -H 'X-API-Key: '"$API_KEY" -H 'Content-Type: text/plain' \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/yaml/import" --data-binary @fed.yaml
```

Import semantics: replace-by-alias inside the tenant-db. Sources absent from the YAML are deleted; secrets absent from a source are deleted.

### Lifecycle

- **Edits take effect on the next spawn.** Editing or disabling a source affects every Quack node spawned after the commit: idle-timeout replacement, manual restart, scale-up additions, pool recreation. Already-running nodes keep their attached catalogs until they exit.
- **Boot-time failure is fatal.** If a source's `setupSql` errors at node startup (extension missing, bad credentials, DNS), the spawn script exits 91 and the supervisor surfaces the last lines of stderr.
- **Disabled sources** are filtered out at blob assembly. No live DETACH.

### Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `unresolved secret 'X' in source '<alias>'` in supervisor log | Source's setupSql references `{{secret.X}}` but no matching row | Add the secret row via `PUT .../federated-sources/<alias>/secrets` |
| `unsubstituted placeholder` at boot | Typo in setupSql like `{{secret.X}` (missing brace) | Fix setupSql via re-create (POST upserts); recycle the pool |
| `catalog 'fedpg' does not exist` from the client | Pool was not recycled after editing the source | Drop and recreate the pool, or wait for idle-timeout recycle |
| `missing RO grant on fedpg.public.X` | ACL not granted on the federated alias | `INSERT INTO qodstate_role_permission(role_id, catalog_name, schema_name, table_name, verb) VALUES (..., 'RO')` |
| `kind env var is required` from spawn script | Manager invoked the script without setting `kind` | Old `LocalQuackBackend` build - rebuild the manager (`sbt assembly`) and restart |
| `secret '<name>' for source '<alias>' has no existing value to reuse` on YAML import | Imported `***REDACTED***` for a new source that didn't exist before | Provide the actual `value` or `externalRef` for that secret in the YAML |
| YAML import HTTP 400 `duplicate alias '<X>' in payload` | Two sources in the imported YAML have the same alias | Dedupe in the YAML before re-importing |

### What does NOT need an ACL change

The existing RBAC graph covers federated tables with zero changes:
- Grant `RO` on `fedpg.public.orders` to role `analyst` via `INSERT INTO qodstate_role_permission` (verb `RO`).
- Federated writes (INSERT/UPDATE/DELETE on a federated alias) require an `RW` grant on the same triple; otherwise they are denied.
- Read-only is enforced at ATTACH time (the user's `setupSql` should include `READ_ONLY`), not in the validator.

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
| `access denied: missing RO grant on ...` | ACL is enabled and the user has no matching grant | Add the grant via the role-permission API or set `QOD_ACL_ENABLED=false` |
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