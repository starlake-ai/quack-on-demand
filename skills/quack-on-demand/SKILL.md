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
- `scripts/tpch-load-test/tpch-load-test.py` - Python FlightSQL load tester (ADBC driver)
- `scripts/adbc.sh` - run one SQL query against the FlightSQL edge and print it as a table (ADBC driver, self-provisioning venv)
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

Bootstrap is driven by `QOD_BOOTSTRAP_YAML` - a path (or `classpath:` reference) to a YAML manifest. Bootstrap runs only when you request demo data: pass `LOAD_TPCH=1` or `LOAD_TPCDS=1` to `run-jar.sh`, or the equivalent bench flag to `run-docker-compose.sh`. In that case the script sets `QOD_BOOTSTRAP_YAML` to the bundled demo manifest (`run-jar.sh` uses the filesystem path `src/main/resources/bootstrap-demo.yaml`; `run-docker-compose.sh` uses `classpath:bootstrap-demo.yaml`). A bare `./scripts/run-jar.sh` does NOT bootstrap. The demo manifest imports two tenants (`acme` with pools `bi` and `etl`, `globex` with pool `bi`), 2 nodes per pool, and a starter RBAC role graph. The import is idempotent: it is skipped when the demo tenants already exist, so restarting the manager is safe.

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
  -d '{"tenant":"acme","tenantDb":"acme_tpch","pool":"bi","size":3,
       "roleDistribution":{"writeonly":1,"readonly":1,"dual":1}}'

# Scale up
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/scale \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"acme_tpch","pool":"bi","targetSize":6,
       "roleDistribution":{"writeonly":1,"readonly":2,"dual":3}}'

# Stop a pool: scales it down to 0 nodes but KEEPS the pool (force=true skips graceful drain)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/stop \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"acme_tpch","pool":"bi","force":true}'

# Delete a pool: stops nodes AND removes the pool from the registry
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/delete \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"acme_tpch","pool":"bi","force":true}'

# Delete a tenant (must have no pools first)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/delete \
  -H 'Content-Type: application/json' -d '{"name":"acme"}'
```

## RBAC grants

Grants live in the normalized `qodstate_*` tables in Postgres. The endpoints are always mounted (Postgres is the only control-plane store since 2026-06-12).

### Endpoint reference

```bash
# Roles
GET  /api/role/list?tenant=acme
POST /api/role/create       body: {"tenant":"acme","name":"analyst","description":"..."}
POST /api/role/delete       body: {"id":"<roleId>"}

# Role table permissions  (verb: SELECT | INSERT | UPDATE | DELETE | ALL)
GET  /api/role/permission/list?roleId=<roleId>
POST /api/role/permission/grant   body: {"roleId":"<roleId>","catalog":"acme_tpch","schema":"tpch1","table":"customer","verb":"SELECT"}
POST /api/role/permission/revoke  body: {"id":"<permissionId>"}

# Users
POST /api/user/create       body: {"tenant":"acme","username":"alice","password":"...","role":"user"}

# Groups
POST /api/group/create      body: {"tenant":"acme","name":"analysts"}

# Memberships (each has a matching /remove)
POST /api/membership/group-role/add   body: {"groupId":"<groupId>","roleId":"<roleId>"}
POST /api/membership/user-group/add   body: {"userId":"<userId>","groupId":"<groupId>"}
POST /api/membership/user-role/add    body: {"userId":"<userId>","roleId":"<roleId>"}

# Pool access - governs which pools a principal can reach
GET  /api/pool/permission/list?tenant=acme
POST /api/pool/permission/grant   body: {"tenant":"acme","poolId":"<poolId>","groupId":"<groupId>"}
POST /api/pool/permission/revoke  body: {"id":"<id>"}
```

### Grant a team read access (6-step flow)

```bash
# 1. Create a role
ROLE_ID=$(curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/role/create \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"analyst","description":"Read-only analyst"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

# 2. Grant SELECT on acme_tpch.tpch1.customer (repeat per table, or use "*" to wildcard any field)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/role/permission/grant \
  -H 'Content-Type: application/json' \
  -d "{\"roleId\":\"$ROLE_ID\",\"catalog\":\"acme_tpch\",\"schema\":\"tpch1\",\"table\":\"customer\",\"verb\":\"SELECT\"}"

# 3. Create a group
GROUP_ID=$(curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/group/create \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"analysts"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

# 4. Attach the role to the group
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/membership/group-role/add \
  -H 'Content-Type: application/json' \
  -d "{\"groupId\":\"$GROUP_ID\",\"roleId\":\"$ROLE_ID\"}"

# 5. Add a user to the group (or use membership/user-role/add to attach the role directly to a user)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/membership/user-group/add \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"<userId>\",\"groupId\":\"$GROUP_ID\"}"

# 6. Grant the group access to the pool (REQUIRED - without this the group cannot reach the pool)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/permission/grant \
  -H 'Content-Type: application/json' \
  -d "{\"tenant\":\"acme\",\"poolId\":\"<poolId>\",\"groupId\":\"$GROUP_ID\"}"
```

Retrieve `<userId>` and `<poolId>` from `/api/user/list?tenant=acme` and `/api/pool/list` respectively.

### DML and DDL grants

Use the same `role/permission/grant` endpoint with `verb` set to `INSERT` / `UPDATE` / `DELETE` for DML writes, or `CREATE` / `DROP` / `ALTER` for DDL. Use `ALL` to cover every verb on a table at once. The validator collapses granular verbs to `Read`, `Write`, or `Ddl` per table at enforcement time.

### Revoking access

```bash
# Remove a table permission from a role
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/role/permission/revoke \
  -H 'Content-Type: application/json' -d '{"id":"<permissionId>"}'

# Detach a role from a group
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/membership/group-role/remove \
  -H 'Content-Type: application/json' -d "{\"groupId\":\"<groupId>\",\"roleId\":\"<roleId>\"}"

# Remove pool access
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/permission/revoke \
  -H 'Content-Type: application/json' -d '{"id":"<poolPermissionId>"}'
```

The EffectiveSet cache is invalidated on every RBAC mutation, so changes take effect on the next handshake - no TTL window to wait for.

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

### Set a database's init SQL

```bash
# Set a database's init SQL: engine defaults for every node of that database,
# run at boot BEFORE the quack extension loads and the catalog ATTACH, ahead of
# the pool's own initSql (a pool SET overrides a db SET) and before federation
# ATTACHes. Takes effect on the next node spawn; restart the database's nodes to
# apply immediately. Empty initSql clears it.
# Engine defaults only, never credentials: the value is stored unredacted and inlined in pod specs; secrets belong in federation sources.
curl -sS -X POST "http://localhost:20900/api/database/setInitSql" -H "X-API-Key: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"acme_tpch","initSql":"SET memory_limit = '\''8GB'\'';"}'
```

Nodes unhealthy right after editing init SQL usually means the SQL is bad: fix or clear the field, then restart the nodes.

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

## Incident response

Quarantine is durable operator state, separate from node health. Check the quarantined flag in `pool/list` before debugging a node that shows unhealthy-like symptoms.

```bash
# Quarantine a node: stop routing new statements to it (running ones finish).
# Durable: survives manager restarts; only unquarantine clears it. Superuser only.
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/node/quarantine \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"acme_tpch","pool":"bi","nodeId":"bi-1"}'

curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/node/unquarantine \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"acme_tpch","pool":"bi","nodeId":"bi-1"}'

# Restart a node: kills everything running on it, respawns with the same id, and clears any quarantine. Superuser only.
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/node/restart \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"acme_tpch","pool":"bi","nodeId":"bi-1"}'

# In-flight statements (tenant admins see only their tenant).
curl -sS -H "X-API-Key: $TOKEN" http://localhost:20900/api/node/active-statements

# Best-effort kill by statement id from the list above. "accepted" is not a guarantee:
# the manager closes the stream; a node that ignores disconnect keeps executing.
# Response is "accepted" (stream closed, best-effort) or "already-completed" (statement finished before the kill arrived).
# Escalate with node/restart when the statement must die.
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/statement/kill \
  -H 'Content-Type: application/json' \
  -d '{"id":"<statement-id>"}'
```

## Ad-hoc queries

`scripts/adbc.sh` runs a single SQL statement against the FlightSQL edge and prints the result as a terminal table. It's the quickest way to confirm what a given user actually sees - handy for spot-checking ACL, column-, and row-level policies. On first run it provisions an ADBC driver venv under `${QOD_ADBC_VENV:-$HOME/.cache/qod-adbc/venv}`; behind a proxy set `PIP_PROXY` so the one-time install can reach PyPI.

```bash
# Query as a tenant user (Basic auth + tenant/pool routing headers).
# --insecure trusts the edge's self-signed dev cert.
scripts/adbc.sh --url grpc+tls://localhost:31338 \
  --user alice --password demo-alice \
  --tenant acme --pool bi --insecure \
  --query "SELECT c_mktsegment, count(*) FROM tpch1.customer GROUP BY 1 ORDER BY 1"

# Same query as the bootstrap superuser (system realm) -- bypasses RLS/CLS,
# so diffing the two outputs shows exactly what a policy filtered or masked.
scripts/adbc.sh --url grpc+tls://localhost:31338 \
  --user root --password demo-root \
  --tenant acme --pool bi --superuser --insecure \
  --query "SELECT c_mktsegment, count(*) FROM tpch1.customer GROUP BY 1 ORDER BY 1"

# SQL on stdin instead of --query; edge with TLS off uses grpc://
echo "SELECT 1" | scripts/adbc.sh --url grpc://localhost:31338 --tenant acme --pool bi
```

Flags: `--url` (required), `--user` / `--password` (or `LT_USER` / `LT_PASSWORD`), `--query` (or `LT_QUERY`, or stdin), `--tenant` / `--pool` (or `LT_TENANT` / `LT_POOL`), `--superuser`, `--insecure`. Unqualified table names resolve against the pool's default schema, but the FlightSQL prepare-time probe needs a real table - schema-qualify (`tpch1.customer`) if you hit "Table … does not exist" at prepare.

## Load testing

`--tenant` and `--pool` are REQUIRED on every invocation (or set `LT_TENANT` / `LT_POOL`). The demo bootstrap creates tenants `acme` + `globex` with pool `bi`.

```bash
# Defaults: 8 workers × 100 iterations against the live edge (TLS on)
./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi

# Higher concurrency
./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi \
  --workers 24 --iterations 50 --warmup 5

# Custom credentials / URL
LT_USER=alice LT_PASSWORD=secret \
  ./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi

# Or pin tenant/pool via env vars
LT_TENANT=acme LT_POOL=bi ./scripts/tpch-load-test/tpch-load-test.py

# Single query repeated
LT_QUERY='SELECT count(*) FROM lineitem' \
  ./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi

# System-realm login (bootstrap `admin`, qodstate_user.tenant IS NULL).
# Adds the `superuser=true` gRPC header; tenant/pool still drive routing.
./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi --superuser
LT_SUPERUSER=true LT_TENANT=acme LT_POOL=bi ./scripts/tpch-load-test/tpch-load-test.py

# TPC-DS workload (requires `scripts/load-tpcds-dbgen.sh` to have seeded the
# globex_tpcds tenant-db; --schema defaults to tpcds1 to match the SF=1 seed).
./scripts/tpch-load-test/tpch-load-test.py --workload tpcds --tenant globex --pool bi
LT_WORKLOAD=tpcds LT_TENANT=globex LT_POOL=bi ./scripts/tpch-load-test/tpch-load-test.py
```

Reports throughput, success rate, latency percentiles (p50/p95/p99). Two curated workloads ship:

- `--workload tpch` (default) - TPC-H subset (Q1, Q3, Q5, Q6, Q10, Q12, Q14) against schema `tpch1`.
- `--workload tpcds` - TPC-DS subset (Q3, Q7, Q19, Q42, Q52, Q55, Q98) against schema `tpcds1`. Mixes per-group aggregation, 5- and 6-way joins, top-N, and a window function (Q98's `OVER (PARTITION BY i_class)`).

Override `--schema` (or `$LT_SCHEMA`) when the target tenant-db was seeded at a non-1 scale factor (e.g. `--schema tpcds10`).

The Python script needs `pip install adbc_driver_flightsql adbc_driver_manager pyarrow`. The auto-install on first run prints the right command if anything is missing.

## Typical failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `/api/*` returns 401 | `QOD_API_KEY` is set but the header is missing/wrong | Pass `X-API-Key: <key>` or log in via `/api/auth/login` |
| `no node with role READONLY or DUAL` | All nodes flipped unhealthy (port unreachable) | Check `pgrep -fl spawn-quack-node`; if 0, run `stop` + `start` (reconcile respawns) |
| `access denied: missing RO grant on ...` | ACL is enabled and the user has no matching grant | Add the grant via the role-permission API or set `QOD_ACL_ENABLED=false` |
| `session expired; please reconnect` | Bearer token unknown (manager restarted between calls) | Re-login or pass Basic credentials |
| `Could not connect to server` for `http://127.0.0.1:21NNN/quack` | Quack child died after manager restart | Reconcile respawns on next boot; until then `pool/delete` + `pool/create` |
| Python load test: "PyArrow not installed" | Missing pyarrow | `pip install --break-system-packages pyarrow` on macOS |
| Manager (or spawned node) hangs at startup right after `BaseAllocator` log line, java pegged at 100% CPU | `INSTALL quack` is blocked by a corporate proxy - DuckDB is silently retrying to fetch the extension from `extensions.duckdb.org` | Pass `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` env vars to the process (container `-e` or shell env). See README "Behind a corporate proxy". |

## Where state lives

- **Tenants, tenant-dbs, pools, nodes** - normalized `qodstate_tenant` / `qodstate_tenant_db` / `qodstate_pool` / `qodstate_node` tables (Liquibase-managed) in the dedicated control-plane Postgres database (`qod` by default, override with `QOD_PG_DBNAME`). The legacy single-JSONB-row `slkstate_pool_state` blob was dropped.
- **Users + RBAC** - `qodstate_user` (bcrypt-hashed passwords), plus `qodstate_role`, `qodstate_group`, `qodstate_role_permission`, `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`, `qodstate_pool_permission`. The old `slkstate_user` and `slkstate_acl_grant` tables were dropped in the RBAC cutover.
- **Federation** - `qodstate_federated_source`, `qodstate_federated_secret`
- **DuckLake catalog metadata** - `ducklake_*` tables in each managed tenant-db's own Postgres database (`${tenant}_${suffix}`), separate from the control plane
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