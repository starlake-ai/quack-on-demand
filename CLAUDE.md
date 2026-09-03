# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test commands

```bash
sbt run                 # run the manager (forks JVM - see "JVM forking" below)
sbt test                # run the full Scala test suite (~714 tests)
sbt assembly            # build distrib/quack-on-demand-assembly-*.jar (UI is bundled in)
sbt "testOnly ai.starlake.quack.route.RouterSpec"        # one test class
sbt "testOnly *RouterSpec -- -z 'picks the least-loaded'" # one test by name fragment
sbt scalafmtAll         # format (scalafmt 3.10, scala3 dialect, maxColumn 100)
```

Boot the manager from the uber-jar (with TLS cert auto-gen + Postgres reachability probe):

```bash
./scripts/run-jar.sh           # foreground
QOD_VERSION=BUILD ./scripts/run-jar.sh   # sbt assembly first
QOD_VERSION=LOCAL ./scripts/run-jar.sh   # newest distrib/ jar, no rebuild
./scripts/stop-jar.sh            # SIGTERM → wait → SIGKILL
```

On **Windows** the manager runs natively (no WSL/Docker) via PowerShell twins:
`scripts/run-jar.ps1` / `scripts/stop-jar.ps1`, and nodes spawn through
`scripts/spawn-quack-node.ps1` (a mirror of the bash spawn script; keeps DuckDB
alive by holding stdin open instead of a FIFO). Teardown uses `taskkill /T` so
the `duckdb.exe` grandchild dies with its wrapper. The native `quackwire.dll` is
bundled automatically whenever `libquackwire/binaries/windows-x86_64/quackwire.dll`
exists in the checkout; without it, run the embedded client with
`QOD_NATIVE_CLIENT=false`. See guides/RUNNING.md "Path 1 on Windows".

UI dev loop (proxies `/api/*` to `localhost:20900`):

```bash
cd ui && npm install && npm run dev
```

The UI is built into `src/main/resources/ui` by [project/UiBuild.scala](project/UiBuild.scala) as a resourceGenerator, so `sbt compile`/`sbt assembly` will run `npm ci` + `npm run build` automatically. There is no manual UI build step for a regular Scala compile.

## JVM forking (don't disable it)

[build.sbt](build.sbt) sets `Compile / run / fork := true` and `Test / fork := true` and adds `--add-opens=java.base/java.nio=ALL-UNNAMED` + `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`. Arrow Flight's `arrow-memory-unsafe` allocator reflects into `java.nio` internals; without forked JVM + the opens, Arrow crashes at class init under Java 17+. The assembly jar embeds an `Add-Opens` manifest attribute (JEP 261) so `java -jar` works without extra flags. The startup script also passes `-Darrow.allocation.manager.type=Unsafe` - without it Arrow auto-picks the netty allocator, which crashes with `NoSuchFieldError: chunkSize` because the assembly bundles a newer Netty than `arrow-memory-netty 14.0.1` reflects against.

## Architecture - the bits that span multiple files

The process is a single uber-jar that exposes **three** sockets:

1. **Manager REST + React UI** on `:20900` ([ManagerServer.scala](src/main/scala/ai/starlake/quack/ondemand/ManagerServer.scala), Tapir + HTTP4s Ember). Endpoints live under `ondemand/api/*Handlers.scala`. The React SPA at `/ui/*` is served from `src/main/resources/ui` (built by the resourceGenerator above).
2. **Arrow FlightSQL edge** on `:31338` ([FlightEdgeServer.scala](src/main/scala/ai/starlake/quack/edge/FlightEdgeServer.scala) → [FlightProducerImpl.scala](src/main/scala/ai/starlake/quack/edge/FlightProducerImpl.scala) → [FlightSqlRouter.scala](src/main/scala/ai/starlake/quack/edge/FlightSqlRouter.scala)). TLS is on by default, cert auto-generated at `certs/server-{cert,key}.pem` if missing.
3. **Quack nodes** on `:21900–22500` - child processes spawned by [scripts/spawn-quack-node.sh](scripts/spawn-quack-node.sh) (local mode) or pods ([KubernetesQuackBackend.scala](src/main/scala/ai/starlake/quack/ondemand/runtime/KubernetesQuackBackend.scala)). Each node runs DuckDB Quack with a metastore env-var contract: `pgHost / pgPort / pgUser / pgPassword / dbName / schemaName / dataPath`. **`spawn-quack-node.sh` (or `spawn-quack-node.ps1` on Windows) is invoked by [LocalQuackBackend.scala](src/main/scala/ai/starlake/quack/ondemand/runtime/LocalQuackBackend.scala) - don't run it directly.** `LocalQuackBackend.defaultCommand` picks the script by OS: bash on Unix, `powershell.exe -File spawn-quack-node.ps1` on Windows (path from `spawnScriptWindows` / `QOD_SPAWN_SCRIPT_WINDOWS`).

A FlightSQL request flows: `client → FlightProducerImpl → AuthenticationService → FlightSqlRouter.execute → StatementValidator (ACL) → StatementClassifier (READ/WRITE/DDL) → Router.pick(snapshot, kind) → QuackHttpAdapter → child node's /quack HTTP endpoint → Arrow stream back through Flight`. Tests for the routing core ([RouterSpec.scala](src/test/scala/ai/starlake/quack/route/RouterSpec.scala), [StatementClassifierSpec.scala](src/test/scala/ai/starlake/quack/route/StatementClassifierSpec.scala), [FlightSqlRouterSpec.scala](src/test/scala/ai/starlake/quack/edge/FlightSqlRouterSpec.scala)) exercise this without the Flight wire.

### State storage (Postgres only)

The control plane lives in a dedicated Postgres database (`qod` by default) holding normalized `qodstate_*` tables managed by Liquibase: `qodstate_tenant`, `qodstate_tenant_db`, `qodstate_pool`, `qodstate_node` for the registry; `qodstate_user`, `qodstate_role`, `qodstate_role_permission`, `qodstate_group`, `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`, `qodstate_pool_permission` for the RBAC graph; `qodstate_federated_source`, `qodstate_federated_secret` for federation. Each managed tenant-db (e.g. `tpch_tpch1`) is a **separate** Postgres database next to it, holding the DuckLake `__ducklake_*` catalog. The `qodstate_*` prefix keeps control-plane tables from colliding with DuckLake's `__ducklake_*` namespace inside any shared database.

The legacy `file` mode (single JSON blob) was dropped 2026-06-12 along with the `stateStorage` and `statePath` config keys -- `PostgresControlPlaneStore` is always wired. Connections come from a HikariCP pool (size 20 on the control-plane store, 10 on `UserStore`); `close()` on the trait is called from the manager's shutdown hook.

### Managed object storage (QoD-provisioned data paths)

A DuckLake database can be created with `managedStorage: true` (REST `database/create`, CLI `qod database create --managed-storage`, admin UI storage mode "Managed (QoD-provisioned)") instead of a caller-supplied `dataPath`/`objectStore`. The manager then resolves `dataPath = s3://<bucket>/<tenant>_<dbname>-<id8>/` (`ManagedPrefix` in `ondemand/storage/`, `id8` = first 8 chars of the tenant-db surrogate id, so recreating a deleted name lands on a fresh empty prefix) and fills the database's `objectStore` map from the `quack-on-demand.managedObjectStore` config block (`QOD_MANAGED_STORE_ENABLED / _ENDPOINT / _REGION / _BUCKET / _ACCESS_KEY_ID / _SECRET_ACCESS_KEY / _URL_STYLE / _RETAIN_DAYS / _PURGE_SWEEP_SEC`, disabled by default), so every downstream mechanism (node `CREATE SECRET`, `SecretKeys` redaction, spawn env, manifest export) applies unchanged. `managedStorage` is exclusive with `dataPath`/`objectStore`, requires `kind=ducklake`, and 400s naming the env when the block is off; `database/update` has no `managedStorage` field, so there is no BYO-to-managed migration.

Every managed create writes a tombstone row in `qodstate_managed_prefix` (Liquibase `0027`); `database/delete` stamps `deleted_at` + `purge_eligible_at` (`+retainDays`, or now with `purgeManagedData: true` / `--purge-managed-data`). `ManagedStoreWiring` (in `boot/`) sweeps due rows every `purgeSweepSec` (60s floor), HA-leader-gated inside `IO.defer`, listing and batch-deleting objects in bounded batches per prefix per tick and stamping `purged_at` when a listing comes back empty; the retained window doubles as the undrop window. The root bucket is created if missing by a boot probe that only ever WARNs. **The managed bucket must have versioning OFF**: on a versioned bucket deletes write delete markers, listings go empty, and the worker stamps a prefix purged while non-current versions keep billing.

### Pool suspend/resume (scale-to-zero)

`qodstate_pool.suspended` marks a pool scaled to zero WITH its role
distribution kept (unlike `stopPool`, which zeroes it, and `disabled`, the
never-auto-woken kill switch). Reconcile skips suspended pools. Reconcile
also heals a crash mid-suspend by draining any still-live nodes of a
suspended pool. The FlightSQL edge wakes a suspended pool on the first
statement: `resumePool(key, "query")` then a bounded poll
(`resumeHoldTimeoutSec`, env `PROXY_RESUME_HOLD_TIMEOUT_SEC`, default 60s)
until a routable node appears, else a retryable "pool is resuming"
UNAVAILABLE. REST: `POST /api/pool/suspend`, `POST /api/pool/resume`,
`startSuspended` on create. SPI: `PoolSuspended` / `PoolResumed` events with
reason `rest | query | module | idle`. See
docs/superpowers/specs/2026-07-18-pool-suspend-resume-design.md.

**Idle hibernation (core, ported from the hosted module).** A leader-gated sweep
(`HibernationWiring` in `boot/`, config `quack-on-demand.hibernation`, envs
`QOD_HIBERNATE_ENABLED` / `QOD_HIBERNATE_SWEEP_SEC` / `QOD_HIBERNATE_IDLE_MIN`)
suspends a running pool once it has served no statements for its idle window,
with reason `idle`. Participation is per-pool opt-in via `Pool.idleTimeoutSec`
(0 = explicit opt-out, floor 5 min) unless `defaultIdleMinutes > 0` sets a
manager-wide default, so the sweep is inert on a fresh install, like autoscale
without a band. Signal: `PoolActivity` (in `route/`) records StatementExecuted
(router sink) and PoolResumed (supervisor sink) timestamps, flushed
GREATEST-upsert by every replica to `qodstate_pool_activity` (Liquibase `0035`);
the decision core (`ondemand/hibernate/Hibernation.candidates`) is pure, with a
leader-local first-seen baseline so never-queried pools hibernate too. With
`enabled=false` Main does not wire `PoolActivity.sink` at all (the sweep's flush
is its sole drainer, same invariant as `PoolLoadStats`).

### Demand scale-out (owner-declared band)

Pools with `minNodes`/`maxNodes` set (create-time or `POST /api/pool/setAutoscale`)
gain readers under sustained load and shed them when quiet, never leaving the
band; writers are never touched and scale-in stops at `minNodes` (zero stays
hibernation's job). Load signal: StatementExecuted -> per-pool 1-minute buckets
(`PoolLoadStats`) flushed upsert-add by every replica to `qodstate_pool_load`;
the HA leader decides (`Autoscale.decide`, pure) and acts through
`PoolSupervisor.scale(reason = "autoscale")`, so quota gating applies. Config
under `quack-on-demand.autoscale` (env `QOD_AUTOSCALE_*`); `enabled` (default
true, env `QOD_AUTOSCALE_ENABLED`) is the manager-wide kill switch and the sweep
is inert without a band anyway. `minNodes == maxNodes` is a legal band that holds
the pool at exactly that size (the per-pool way to opt out). With the switch off
Main does not wire `PoolLoadStats` into the router's event sink at all, since the
sweep is that sink's sole drainer. Manual scale outside the band is refused
(`outside_band`).
See docs/superpowers/specs/2026-08-11-demand-scale-out-policy-design.md.

### HA mode (opt-in, Kubernetes only)

`QOD_HA_ENABLED=true` (helm: `replicaCount > 1`) runs N active-active managers.
All replicas serve REST + FlightSQL; one holds a Postgres session advisory lock
(`HaCoordinator`) and runs the singleton duties (reconcile respawns, bootstrap,
DuckLake init, revoked-jti purge). Pool mutations serialize across replicas via
per-pool advisory locks (`PoolLocker`); caches propagate via LISTEN/NOTIFY on
`qod_topology` / `qod_rbac` / `qod_revocation` with a periodic snapshot-refresh
fallback. JWT revocations persist in `qodstate_revoked_jti`. HA with the local
backend is refused at config load. See
docs/superpowers/specs/2026-07-02-manager-ha-zero-downtime-design.md.

### Edge config + catalog ([quack.edge.config](src/main/scala/ai/starlake/quack/edge/config/), [quack.edge.catalog](src/main/scala/ai/starlake/quack/edge/catalog/))

The auth/ACL/session config types and the DuckLake catalog resolver live under [quack.edge.config](src/main/scala/ai/starlake/quack/edge/config/) and [quack.edge.catalog](src/main/scala/ai/starlake/quack/edge/catalog/). The pureconfig `ProductHint`s in [Main.scala](src/main/scala/ai/starlake/quack/Main.scala) override the `derives ConfigReader` defaults to use camelCase (matching `application.conf`) instead of pureconfig's kebab-case.

### RBAC validator

[Main.scala](src/main/scala/ai/starlake/quack/Main.scala) wires the `StatementValidator`:
- `PostgresAclValidator` is the default - resolves the session's `(tenant, user)` into the cached **EffectiveSet** (closure of roles · groups · `qodstate_role_permission` rows reachable through them), then per statement uses the [ACL SQL parser](src/main/scala/ai/starlake/acl/parser/) to extract table refs and matches them against that set. Superusers (`qodstate_user.tenant IS NULL`) bypass the check.
- `StatementValidator.allowAll` when `acl.enabled=false`.

The `EffectiveSet` is cached in `PoolSupervisor` with a 60s TTL keyed by `(userId, jwtRoles.hashCode, jwtGroups.hashCode)`; every RBAC mutator (`createRole`, `addUserRole`, `grantPoolPermission`, etc.) calls `invalidateEffectiveCache()` so a freshly-granted role takes effect on the next handshake without waiting on the TTL.

**DML / DDL grants are enforced per-table.** `SqlParser` walks INSERT / UPDATE / DELETE / MERGE / CREATE TABLE / CREATE VIEW / DROP / ALTER / TRUNCATE and emits `TableAccess(table, verb)` tuples with `verb` collapsed to `Read | Write | Ddl`. `PostgresAclValidator` matches each access against the principal's role permissions via a `verbCovers` helper that maps the canonical grant verbs (`RO`/`RW`/`DDL`/`ALL`, see `RolePermission.ValidVerbs`) onto the collapsed space. A role permission of `verb=ALL` covers anything; `verb=RW` covers `Read` and `Write` but not `Ddl`; `verb=RO` covers only `Read`. The DuckDB / BigQuery `FROM t` shorthand (`FromQuery extends Select`) is also walked. Statements with no table refs (COMMIT, ROLLBACK, SET, USE, SHOW) hit the `ControlFlow` arm and are admitted unconditionally.

### REST authZ - tenant scope on every RBAC endpoint

Every handler in `api/{User,Role,Group,Membership,PoolPermission}Handlers.scala` calls `TenantScopeCheck.{reject,rejectForResource,rejectForUser}` before mutating. Id-only endpoints (e.g. `POST /api/role/delete {id}`) resolve the owning tenant via 5 supervisor lookup helpers (`tenantForUser`, `tenantForRole`, `tenantForGroup`, `tenantForRolePermission`, `tenantForPoolPermission`) before applying the gate. Tenant-A admin sessions get `403 tenant_forbidden` on any tenant-B resource; superuser and static-key callers bypass. Missing ids 404 (no cross-tenant existence leak via differential error codes). See `RbacTenantScopeSpec` for the 14 cases that pin the contract.

### SCIM 2.0 provisioning (`/api/scim/v2/{tenant}`)

`ScimEndpoints` / `ScimHandlers` (in `ondemand/api/`) serve RFC 7643/7644 Users and Groups
over the RBAC store for IdP connectors (Okta, Entra): CRUD, `eq` filters
(userName/externalId/displayName), pagination, PATCH (active toggle incl. Entra's string
booleans, member add/remove/replace), plus ServiceProviderConfig/ResourceTypes/Schemas.
Bodies are raw strings (SCIM clients send `application/scim+json`, and errors must be the
SCIM envelope, not `ErrorResponse`). Auth: `Authorization: Bearer` (static key or PAT) is
accepted by `apiKeyGuard` for the `/api/scim/` prefix ONLY, alongside the usual
header/cookie. Mapping: userName=username (immutable), active=enabled, primary
email=email (EmailPolicy applies), externalId=new `external_id` columns (Liquibase
`0036`, written only by SCIM). The superuser realm is invisible to SCIM; passwordless
creates get a random password (users sign in via tenant OIDC). SCIM routes are excluded
from CLI parity (`cli/tests/test_rest_parity.py`) as machine-to-machine. See `ScimSpec`.

### Session model - JWT in HttpOnly cookie

`SessionTokenStore` is a stateless JWT signer/verifier (HS256, secret from `manager.auth.management.sessionJwtSecret`). `mintWithScope` returns the compact JWT; `get` parses + verifies + reconstructs the `Session` from claims. Revocation: bounded in-process jti denylist (lost on restart; the documented trade-off for going stateless).

On the wire:
- `AuthHandlers.login` returns the JWT both in the JSON body (`LoginResponse.token`, for CLI / static-key callers) AND as `Set-Cookie: qod_session=<jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api`.
- `ManagerServer.apiKeyGuard` admits on either path: `X-API-Key` header (CLI / static key) OR `qod_session` cookie (browser).
- UI does not stash a token in localStorage; fetch's same-origin credentials policy auto-attaches the cookie.

Cookie attributes are configurable: `sessionCookieSecure` (env `QOD_SESSION_COOKIE_SECURE`, default `true`), `sessionCookiePath` (env `QOD_SESSION_COOKIE_PATH`, default `/api` -- override behind a path-rewriting reverse proxy). The JWT exp is absolute (8h from mint by default, env `QOD_SESSION_IDLE_TTL_SEC`); there's no sliding-window refresh. When `QOD_SESSION_JWT_SECRET` is unset, `BootPreflight.withGeneratedBootSecrets` generates a fresh random secret at boot and prints it to stdout (println, not the logger, so the default ERROR level can't swallow it); sessions then die on restart, and HA refuses to boot on an empty secret. Pin a stable random >=32-char value for production.

Forced password change: `qodstate_user.must_change_password` (set via `mustChangePassword`
on `user/create` / `user/update`) makes `DatabaseAuthenticator` refuse the password on BOTH
the REST login (401 `password_change_required`) and the FlightSQL handshake, until the user
swaps it through the public pre-session `POST /api/auth/change-password` (current password
is the credential; new must differ; clears the flag). The auth queries MUST project
`(password_hash, role, enabled, must_change_password)` - a shorter custom
`QOD_AUTH_DB_SYSTEM_QUERY` / `QOD_AUTH_DB_TENANT_QUERY` fails boot and every login.

**Regular-user profile sessions.** The manager UI is not admin-exclusive: a tenant-scoped
`role=user` principal can log in through their tenant (the blank/system login and OIDC SSO
stay admin-only). `AuthHandlers.mintSessionFor` accepts a DB-mode `Tenant(t)` login from any
principal holding a grant on `t`, not just an admin, and mints a non-admin, profile-only
session (`LoginResponse.admin = false`). `ManagerServer.apiKeyGuard` demotes such sessions to
a fixed allowlist (`isProfileApi`: `/api/auth/whoami`, `/api/auth/logout`,
`/api/profile/usage`, `/api/profile/statements`) and answers `403 admin_required` on
everything else; the UI mounts only the `/profile` route (own password change; own
usage/statements) for them.

### Account lockout, SMTP, and self-service password reset

`qodstate_user` carries an optional `email` column (Liquibase `0029-user-email`). A row with an email can use the public self-service reset flow and is eligible for lockout; a row with a non-email username and no email cannot reset by email and is never subject to lockout.

When the username itself is in email format, `email` is auto-set to it and immutable - `user/create` / `user/update`, manifest import, AND boot seeding (`BootPreflight.seedAdminUsers` derives `email = username` for an email-format admin, matching the 0031 backfill so fresh and upgraded installs agree) all follow the rule; a conflicting value is rejected with `400 invalid_email`; pre-existing rows are backfilled by Liquibase `0031-user-email-from-username`. This means the default seeded admin `admin@localhost.local` carries `email = admin@localhost.local`, so it IS lockable when lockout is on and reset-eligible. A locked superuser is still recoverable without the email flow: a manager restart re-seeds the admin (rewriting the password to `QOD_ADMIN_PASSWORD` and clearing `failed_attempts` / `locked_at` in the same statement), and the static `X-API-Key` bypasses login lockout. Because `admin@localhost.local` is not a routable mailbox, its self-service email reset does not deliver by default - set `QOD_ADMIN_USERNAME` to a real address for email self-recovery, otherwise use restart or the API key.

**SMTP** (`quack-on-demand.smtp`, env `QOD_SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_FROM` / `_STARTTLS`): when `host` is unset (default), Main wires `LogMailSender` (logs recipient/subject, never actually sends); setting `QOD_SMTP_HOST` switches to `SmtpMailSender` (Jakarta Mail). `QOD_PUBLIC_BASE_URL` (`quack-on-demand.publicBaseUrl`, default `""`) is the externally-visible origin used to build the mailed reset link (`$publicBaseUrl/ui/reset-password?token=...`); left empty the link is host-relative and Main logs a boot warning.

**Self-service reset**: `POST /api/auth/forgot-password {tenant?, username}` is a public endpoint (no API key) that always returns 200 regardless of whether the account exists or has an email - anti-enumeration is load-bearing, and the lookup + send are decoupled so response timing doesn't leak account existence either. `POST /api/auth/reset-password {token, newPassword}` (also public) redeems the token. The token (`ResetTokenStore`) is a stateless, single-use, 1-hour HS256 JWT signed with the same secret as `QOD_SESSION_JWT_SECRET`; single-use is enforced not by a redemption ledger but by embedding a fingerprint of the password hash at mint time, so any password change invalidates every outstanding link. `qod auth forgot-password` / `qod auth reset-password` are the CLI equivalents; `qod user create/update --email` sets the column.

**Account lockout** (`quack-on-demand.auth.lockout`, env `QOD_AUTH_LOCKOUT_ENABLED` default `false`, `QOD_AUTH_LOCKOUT_MAX_FAILURES` default `10`) is opt-in. Enabling it requires a working SMTP relay - `BootPreflight.checkLockoutSmtp` refuses to start otherwise, naming `QOD_SMTP_HOST` in the error - because a locked-out user with no mail path would have no way back in. Lockout only ever applies to rows with a non-null `email`; a row with a non-email username and no email can never be locked no matter how many failed attempts, whereas the seeded superuser `admin@localhost.local` carries `email = username` and so is lockable. `DatabaseAuthenticator` checks the lock before bcrypt and, on a locked row, returns `AuthFailure.AccountLocked` -> REST `401 {"error":"account_locked","message":"account locked - use forgot password to reset your credentials"}` (same failure wins the FlightSQL handshake too). Failures accumulate atomically per row and reset to zero on any successful login. Recovery is either the self-service reset above or an admin password reset (`user/update` with a new `password`, or the CLI equivalent) - both unconditionally clear `failed_attempts` and `locked_at` as part of the password write.

### K8s backend - per-pod and per-pool Secrets

`KubernetesQuackBackend` creates one Pod + one Service per node, plus two Secrets:
- **Per-pod token Secret** `qod-token-${nodeId}`. Holds the manager-minted bearer (`QOD_NODE_TOKEN`) the manager presents on calls to that pod's `/quack` endpoint. The pod env injects it via `env.valueFrom.secretKeyRef` -- `kubectl describe pod` shows the ref, not the value. `discoverExisting` reads the Secret on manager restart to repopulate the in-memory token cache, so adopted pods don't 401 after a redeploy.
- **Per-pool federation Secret** `qod-fedsql-${tenant}-${tenantDb}-${pool}` (tenantDb hyphenized for RFC-1123). Holds the resolved federation SQL when `spec.extraSetupSql` is non-empty; all pods of the pool reference the same Secret via `env.valueFrom.secretKeyRef`. GC'd when the last pod of the pool stops; rotation = update the Secret once, restart pods.

Both Secrets must exist BEFORE pod create (kubelet rejects pods referencing missing Secrets), so `start(spec)` runs `ensureTokenSecret` and `ensureFederationSecret` first, then creates the pod.

### Manager module SPI (hosted-service plug-in)

`ai.starlake.quack.spi` defines `ManagerModule`: jars on the classpath declaring
`META-INF/services/ai.starlake.quack.spi.ManagerModule` are loaded at boot
(`ModuleLoader` in `ondemand/module/`), get their Liquibase changelog applied
(`qodhosted_*` tables on the control-plane DB), contribute Tapir endpoints /
public path prefixes / static SPA mounts to `ManagerServer`, receive async
`ManagerEvent`s (bounded lossy queues - freshness signals, not ledgers), and can
register HA-leader-gated recurring tasks. Zero-module boot is unchanged OSS
behavior. See docs/superpowers/specs/2026-07-18-manager-module-spi-design.md.

## Configuration

Every scalar in [src/main/resources/application.conf](src/main/resources/application.conf) accepts a `QOD_*` env-var override (or `PROXY_*` for FlightSQL edge keys). Prefer env vars over editing the conf - the conf is bundled into the jar at build time. Defaults: `:20900` REST, `:31338` FlightSQL (TLS on), Postgres `localhost:5432` user `postgres` / `azizam`, admin `admin@localhost.local` / `admin`. `quack-on-demand.defaultMetastore.dataPath` ships with a developer machine's absolute path - override it before running outside that environment.

Two security-critical knobs should be pinned before any non-localhost deploy: `QOD_API_KEY` and `QOD_SESSION_JWT_SECRET`. When either is unset, boot generates a random value and prints it to stdout in an unmissable banner (`BootPreflight.withGeneratedBootSecrets`); the values change on every restart, so sessions and API-key callers die with the process. Under HA nothing is generated: an empty session secret refuses boot, an unset API key just disables the static-key arm.

`federation.secretStore` defaults to `dispatch` (route per secret by externalRef prefix). The `aws-sm` / `gcp-sm` / `azure-kv` / `vault` single-backend modes are refused at config load -- their resolvers are stubs that `NotImplementedError` at node spawn. Under `dispatch` mode the stubs stay wired but the runtime error spells out the supported alternatives (`postgres` inline value, `env:` prefix).

## Operator runbook

[skills/quack-on-demand/SKILL.md](skills/quack-on-demand/SKILL.md) is the operator runbook: REST API curl recipes, tenant/pool/ACL CRUD, typical failure modes, load-test invocation. When the user asks operational questions ("how do I create a pool", "why is auth failing"), prefer the patterns there over reinventing them.

## Things to avoid

- **Don't disable JVM forking** in build.sbt - see "JVM forking" above.
- **Don't invoke `scripts/spawn-quack-node.sh` directly** - it's spawned by `LocalQuackBackend` with the right port + token + env contract. Manual invocation will leak ports and confuse the supervisor.
- **Don't edit the bundled `application.conf` for local tweaks** - set the `QOD_*` env var instead, or the change vanishes on the next `sbt assembly`.
- **Always tear the manager down with `scripts/stop-jar.sh`, never an IDE/JVM kill** (or a bare `kill` of the java PID). The spawn script keeps each DuckDB node alive via a held-open FIFO/stdin, so an unclean manager exit orphans the `duckdb` grandchildren (reparented to PID 1) and they keep holding node ports `21900+`. The next manager then spawns onto an occupied port, its node never passes the `SELECT 1` health probe, and it shows `healthy=false` / `served=0`. Recovery: kill the orphans (blunt reset: `scripts/kill-quack-nodes.sh`), then `POST /api/node/restart` (or scale the pool) so the node re-binds a free port. `stop-jar.sh` does SIGTERM -> wait -> SIGKILL and reaps the children, so it never leaves orphans. Ctrl-C on a foreground `run-jar.sh` (or `qod start`) is safe: both supervise the JVM in its own process group and turn the interrupt into that same teardown.
