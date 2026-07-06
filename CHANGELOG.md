# Changelog

## 0.3.6 (unreleased)

### Audit log

- **Persisted, tenant-scoped audit trail.** `QOD_TELEMETRY_STORE=postgres` (the default) records control-plane mutations, auth events (`auth.login`, `auth.login.failure`, `auth.logout`, `auth.revoke`, `auth.api-key.failure`), data-plane ACL denials (`sql.denied`), and data-plane writes (`sql.write`, `sql.ddl`) into a new `qodstate_audit` table. The action taxonomy covers all mutating handlers across tenant, database, pool, user, role, group, membership, node, federation, and manifest surfaces. Detail fields are sanitization-guaranteed at construction time: keys matching `password`, `secret`, `token`, `jwt`, or `credential` are rejected before any write reaches the store. `QOD_TELEMETRY_STORE=none` disables all recording, skips the journal fiber, purge, and rollup duties, and hides the Audit UI page. `GET /api/audit/list` (filters: family, tenant, actor, action, q, from, to, limit up to 500, keyset `before` cursor) is tenant-scoped: superusers see all rows including null-tenant ones; tenant admins see only their tenant. An hourly purge enforces `QOD_AUDIT_RETENTION_DAYS` (default 90). The `EventJournal` routes data-plane events through a bounded async queue (capacity 8192); queue overflow or append failure increments `qod_journal_dropped_total{table}` without blocking the statement hot path. A new **Audit** page in the admin UI (superusers and tenant admins; hidden when telemetry is off) surfaces a filter bar (family toggle, tenant, actor, action, time range), a newest-first table with expandable detail, and keyset load-more pagination.

### Statement history and trends

- **Persisted, tenant-scoped statement history with trend rollups.** `QOD_TELEMETRY_STORE=postgres` (the default) records every FlightSQL statement (including reads, unlike the audit log) into a new `qodstate_stmt_history` table; SQL text is capped at 500 characters. `PREPARE` probe statements are excluded: the matching `EXECUTE` row carries a `prepareDurationMs` field instead. A background rollup job (watermark-based, idempotent, leader-gated in HA mode) aggregates raw rows into a new `qodstate_stmt_rollup` table on a configurable interval (`QOD_ROLLUP_INTERVAL_SEC`, default 300s, with a 60s skew buffer). Hourly rollups (per `tenant+pool`, `percentile_cont` p50/p95/p99, retained for `QOD_HOURLY_ROLLUP_RETENTION_DAYS` default 90) power trend charts; daily rollups (per `tenant+pool+username`, null percentiles) accumulate for the upcoming per-user usage feature whose retention knob ships with it. An outage shorter than the raw retention window (`QOD_STMT_HISTORY_RETENTION_DAYS`, default 7) recomputes cleanly on the next tick; a longer outage permanently loses the truncated rollup range. Raw retention must stay at or above 2 days because the daily recompute rebuilds the watermark's full day. `GET /api/history/statements` (filters: tenant, pool, user, status, q, from, to, limit up to 500, keyset `before` cursor) and `GET /api/history/trends` (granularity: hour or day; tenant, pool, from, to) are both tenant-scoped: superusers see everything, tenant admins see only their tenant. A new **History** page in the admin UI (superusers and tenant admins; hidden when telemetry is off) shows a range picker, three charts (statement volume, latency percentiles on hourly granularity, error rate), and a searchable statement table with keyset load-more pagination. `qod_journal_dropped_total{table="stmt_history"}` counts missed statement rows, which translate directly to undercounted rollup buckets. `QOD_TELEMETRY_STORE=none` disables all recording and hides the History page.

### High availability (opt-in, Kubernetes only)

- **Active-active manager replicas with zero-downtime rollout.** `QOD_HA_ENABLED=true` (helm: `replicaCount > 1`) runs N managers, all serving REST + FlightSQL. One replica holds a Postgres session advisory lock (`HaCoordinator`) and runs the singleton duties (reconcile respawns, bootstrap, DuckLake init, revoked-jti purge); leader duties re-run on promotion. Pool mutations serialize across replicas via per-pool advisory locks (`PoolLocker`); caches propagate via LISTEN/NOTIFY on `qod_topology` / `qod_rbac` / `qod_revocation` (published from every `PoolSupervisor` mutator and manifest import) with a periodic snapshot-refresh fallback and a deletion-aware `restore()`. JWT revocations persist in the new `qodstate_revoked_jti` table (Liquibase changelog `0014`) so a session revoked on one replica is denied on all. `/health` stays liveness-only (503 when Postgres is unreachable); new `/ready` gates readiness on Postgres. Helm wires the env flag, requires a pinned session JWT secret, sets `RollingUpdate maxUnavailable: 0`, adds a PodDisruptionBudget, and couples termination grace to `drainTimeoutSec`. HA off (default) preserves single-manager behavior; HA with the local backend is refused at config load. Documented in the RESILIENCE guide with a kind failover check.

### Security - whole-codebase audit 2026-07-02

The full report lives at `docs/security-audit-2026-07-02.md`; the findings below are fixed.

- **Node-bootstrap SQL injection closed.** `TenantDb.validate` rejects injection metacharacters in `schemaName`, `dbName`, `dataPath`, and the `pg*` connection params (denylist on `pgHost`/`pgUser`/`pgPassword`, numeric `pgPort`) at the control-plane trust boundary, covering both the local and Kubernetes backends; `spawn-quack-node.sh` also quotes SQL identifiers. Manifest import runs the injection-safety subset (`validateSafety`) so DuckLake tenant-dbs that legitimately omit keys merged from the default metastore still import.
- **ACL fails closed on parser blind spots.** `TableExtractor` now reports constructs it cannot map to a grantable table (table functions like `read_parquet`, string-literal file refs, unrecognized FROM-item / statement node types) as `unsupported` markers instead of silently dropping them, and `PostgresAclValidator` denies whenever any are present (unless the principal holds `*.*.*` ALL). Missing walker arms added (parenthesized joins, UPDATE SET-value subqueries, MERGE action subqueries, Json/Signed/Extract wrappers); CTE shadowing only suppresses unqualified names; `EXPLAIN ANALYZE <stmt>` is authorized as the inner statement it executes; `ControlFlow` is now an explicit allowlist so unknown statement types fall through to `ParseError`.
- **RLS/CLS parse failures deny.** `FlightSqlRouter` denies when `RowPolicyRewriter` / `ColumnPolicyRewriter` cannot parse a statement that has policies attached (a parse failure means filtering cannot be verified), and the transient-failure retry resends the fully rewritten (RLS-wrapped) SQL instead of the CLS-only intermediate.
- **Prepared statements peer-bound and re-authorized live.** A prepared-statement handle only executes for the peer that created it (a leaked handle replayed from another connection gets UNAUTHORIZED), and both Execute paths re-read the `EffectiveSet` from the live session at call time instead of replaying the Prepare-time snapshot - a grant revoked mid-session now takes effect on prepared statements exactly as it does on one-shot statements, and a handle whose session has expired resolves to a deny.
- **Cross-tenant RBAC privilege escalation closed.** `addUserRole` / `addUserGroup` / `addGroupRole` enforce that the referenced role/group shares the principal's tenant.
- **Cookie-authenticated sessions are now tenant/superuser-scoped on all
  REST endpoints.** Body-tenant, id-only, and read/superuser-gated endpoints
  previously enforced scope only for the X-API-Key header path; a browser
  session (HttpOnly qod_session cookie, no header) reached the handlers with
  no resolved identity and was admitted. This let a tenant admin mutate other
  tenants' pools, databases, and RBAC objects, reach superuser-only operations
  (manifest export/import, server config), and receive unfiltered cross-tenant
  reads from pool/tenant list and statement history. The worst case was
  manifest import: a cookie-authenticated tenant admin could overwrite the
  entire control plane. All such endpoints now resolve the session cookie into
  the scope check (header still takes precedence). Path/query-tenant endpoints
  were already covered by the perimeter guard.
- **Constant-time API-key compare.** `apiKeyGuard` compared `X-API-Key` with `String.equals`, leaking the key length-by-length via response timing; replaced with `MessageDigest.isEqual`.
- **Internal exception text no longer reaches Flight clients.** Every catch-all INTERNAL arm in `FlightProducerImpl` now logs the full detail server-side against a short random errorId and returns only `internal error (errorId=...)`; raw messages could carry SQL fragments, hostnames, and file paths. Curated `RouterFailure` messages still flow through unchanged.
- **K8s spawn-failure orphans cleaned up.** `start()` deletes the pod, service, and per-pod token Secret when a spawn fails partway, preventing orphan accumulation and a deterministic-nodeId respawn deadlock.

### Observability

- **DuckDB engine metrics per node.** The manager previously collected nothing from inside the DuckDB engine - every per-node signal was derived from wire round-trip latency, so memory pressure and spill-to-disk were invisible until they degraded latency. A new `EngineStats` scrape runs a single round-trip over `duckdb_memory()` and `duckdb_temporary_files()` through the existing `quack_query` wire (no node-side change), piggybacked on every successful health-probe tick; a failed scrape keeps the previous sample and can never flip a node unhealthy. Surfaced in three places: four Prometheus gauges tagged `(tenant, pool, node_id, role)` (`node_duckdb_memory_used_bytes`, `node_duckdb_temp_storage_bytes`, `node_duckdb_spill_files`, `node_duckdb_spill_bytes` - nodes never scraped publish no row rather than a false zero), new Mem / Spill columns plus fleet-total cards on the admin UI's Nodes page (`NodeInfo` gains the four fields), and a "DuckDB Engine" row in the bundled Grafana dashboard. Verified live against a TPC-H load: the buffer-manager gauge tracks query activity and REST, `/metrics`, and the UI agree. Two live-run fixes are folded in: the quack wire's schema-only first Arrow batch is skipped when decoding the scrape result, and `NodeInfo`'s hand-rolled circe codec now carries the new fields (a `NodeInfoCodecSpec` round-trip pins the shape so an added field can no longer silently vanish from `/api/pool/list`).

### Incident response

- **Incident response for administrators:** durable node quarantine/unquarantine (survives restarts, HA-propagated, probe-proof), superuser node restart via the reconcile respawn path, live in-flight statement view on the Nodes page, and best-effort statement kill (stream disconnect locally, qod_kill NOTIFY fan-out across HA replicas). New endpoints: POST /api/node/unquarantine, POST /api/node/restart, GET /api/node/active-statements, POST /api/statement/kill.

### Demo, scripts, and release

- **Releases are announced on Discord #news.** `release-jar.sh` posts the repo link plus the version's changelog section (chunked to Discord's message limit) via the new `scripts/announce-release-discord.sh` right after creating the GitHub release; webhook from `QOD_DISCORD_WEBHOOK_URL` or the untracked `.env`, failures warn without failing the release.
- **SSB star schema seeded via `LOAD_SSB=N`** (bundled into the `LOAD_TPC` shortcut next to TPC-H and TPC-DS). DuckDB has no ssb extension, so `scripts/load-ssb-dbgen.sh` generates TPC-H in-memory and derives the 5 SSB tables (`lineorder`, `customer`, `supplier`, `part`, `dwdate`) per the O'Neil spec mapping; the date dimension is named `dwdate` so the 13 canonical queries run unquoted. Lands in schema `ssb1` of the existing `acme_tpch` tenant-db, wired into all three install paths.
- **kind local stack unbroken + loaders no longer OOM at SF >= 10.** The helm step died with `HELM_EXTRA_ARGS[@]: unbound variable` on bash 3.2 when no TPC seed was requested; the TPC loaders were OOM-killed at SF >= 10 because DuckDB's default memory budget ignores the cgroup limit - a cgroup-aware `memory_limit` (default 40% of the cgroup cap, `MEMORY_LIMIT`-overridable) makes it spill to disk instead.
- **Compose summary crash fixed.** The end-of-script summary still read the removed `_profile_set` array, so under `set -u` every `run-docker-compose.sh` run died after the stack was already up, losing the URL summary and the zero exit code.
- **Release: tag pushed before `gh release create`** (which requires the tag on the remote).

### Kubernetes node pods

- **Kubernetes node-pod sizing and templates.** Pools carry `cpu` and `memory`
  (each applied as request and limit on the quack container, Guaranteed QoS
  when both set) via `POST /api/pool/setResources` and sliders on the pool
  create form and pool detail page (CPU 0.5-128 cores, memory 1-1024 Gi).
  Behind `QOD_POD_TEMPLATE_ENABLED` (default off, superuser only), a pool may
  carry a full Pod-manifest template through `POST /api/pool/setPodTemplate`
  (API-only); the manager overlays the pod identity labels, the quack
  container's env contract, and resources onto it, so sidecars/volumes/affinity
  are expressible while adoption and routing keep working. Applies on next node
  spawn. The local backend ignores these (Kubernetes-only).

### Database configuration

- **Per-database init SQL.** Tenant-dbs carry an `initSql` executed at node boot
  before the quack extension loads, ahead of the pool's own initSql and the
  federation blob, so operators set temp directory, memory limit, or extension
  loads once per database and pools can still override. Field on database create,
  the manifest, and the Databases UI.
  New endpoint POST /api/database/update edits any safe subset of a database
  (metastore, object store, default database/schema, init SQL; name, kind, and
  data path stay immutable) and restarts all the database's nodes when
  node-affecting fields change. The Databases UI opens the editor on name
  click, shows per-database table counts linking into the catalog browser,
  and reports the effective data path for databases inheriting the default.

## 0.3.5

### Connectivity and auth

- **Browser OAuth token page for JDBC / DBeaver.** New guard-exempt `/api/auth/sql-token` start + callback flow on the manager port: the browser is redirected to the edge OIDC provider (endpoints resolved via OIDC discovery, so split-horizon deployments work), logs in, and the callback renders the id token (aud = the edge client id) for pasting into DBeaver's token property. Retires the dormant standalone `:8888` OAuth broker - `OAuthHttpServer`, the `auth.oauth{}` config block, and the `QOD_AUTH_OAUTH_*` env vars are gone; a single `auth.oauthScopes` key remains. State token uses a constant-time HMAC compare. The kind rig's Keycloak realm enables the auth-code flow and derives the token's tenant claim from a per-user attribute instead of hardcoding one.
- **Multi-FETCH Arrow streams no longer corrupt.** `ChainedQuackArrowReader` swapped to a fresh child root on every FETCH round while the Flight stream kept flushing the first (closed) root, so any result spanning more than one PREPARE/FETCH round-trip surfaced client-side as `mismatch number of rows in column: got=0, want=N`. The reader now owns one stable `VectorSchemaRoot` and copies each child batch into it, restoring the ArrowReader contract.

### Pools and supervision

- **Drain / force stop scales the pool to 0 instead of deleting it.** The pool row survives and stays drained across manager restarts. Deletion is now a dedicated path: `POST /api/pool/delete` plus a Delete button in the UI.
- **Periodic reconcile.** `reconcile()` runs on a background fiber every `reconcileIntervalSec` (default 30s, env `QOD_RECONCILE_INTERVAL_SEC`, `0` restores boot-only), so a node that dies while the manager is up is respawned on the next tick. Also fixes a node stuck in "draining" after drain + rescale: the stale `NodeLoadTracker` entry is reset before respawn and removed on drain/delete.

### Examples and docs

- **FlightSQL client examples in four languages** under `examples/`: TypeScript (raw gRPC + apache-arrow, since Node has no first-party driver), Python (ADBC), Java (Flight SQL JDBC), and Rust (arrow-flight over tonic). Each runs single queries and the 22 standard TPC-H queries against a live edge, sharing the `QOD_*` env-var contract. An n8n community node (catalog operations via FlightSQL) was added and then moved to its own repo, as was the Power BI connector.
- **Administration docs section**: onboarding golden paths, access-control, day-2 operations, lifecycle and config playbooks, an overview + task map, and a "Manage by manifest" page with YAML fragments aligned to each playbook. Corrections along the way: state lives in `qodstate_*` tables, the removed `/api/acl/grant` API replaced with real RBAC calls, required `tenantDb` added to pool curls.
- **Connecting guides** for DBeaver and Tableau; README repositioned around the DuckLake serving layer with an honest comparison table and a data-residency diagram; `.env.example` clarifies public vs internal ports.

### Build and release

- **`release.sh` split into resumable per-artifact phases**: a shared `release-lib.sh` plus `release-libquackwire.sh` (native jars to Maven Central), `release-jar.sh` (manager jar, tag, GitHub release), and `release-docker.sh` (multi-arch image), each idempotent so a mid-release failure is resumed by re-running the failed phase.
- **libquackwire CI publishes on manual `workflow_dispatch`** from main, enabling a snapshot re-publish without a code push.
- `run-jar.sh` keeps sbt/JLine from leaving the WSL pty in raw mode; the UI pool Delete button shows only the icon.

## 0.3.4

- **DuckDB upgraded 1.5.3 -> 1.5.4** across every pinned layer: the libquackwire native shim rebuilt against libduckdb 1.5.4 (`1.5.4-40de7badae41-1`, duckdb-quack submodule at the v1.5-variegata tip), the DuckDB JDBC driver (`1.5.4.0`), and the runtime libduckdb / DuckDB CLI fetched by the Dockerfiles and `run-jar.sh`.

## 0.3.3

### Security

- **Row-level security.** Per-role row policies - a boolean SQL predicate authored via REST, the admin UI's Role "Row policy" tab, or a YAML manifest - are enforced on every SELECT before it reaches a Quack node. The gateway rewrites the query to fold the policy predicate into the statement's WHERE so a role only ever sees the rows its predicate admits; superusers bypass, matching the table- and column-level ACLs. New `qodstate_role_row_policy` table (Liquibase changelog `0013`), `RoleRowPolicy` model, an `EffectiveSet` row-policy field, REST + effective-set + manifest plumbing, a demo policy seeded on `acme/analyst`, and an `adbc.sh` query helper to exercise it. Documented in the rbac-model guide.

### Admin-UI authentication (OIDC SSO)

- **OIDC single sign-on for the admin UI.** Provider-agnostic OpenID Connect discovery (Keycloak, Google, Azure, AWS) drives an authorize / callback / end-session flow protected by a signed state token and PKCE. New `OidcSsoService` plus `AuthHandlers.oidcStart/oidcCallback/oidcLogout` (system scope, superuser-only); `auth.management.publicBaseUrl` builds the redirect URIs; `/api/config/client` surfaces `identitySource` + `ssoProviderName` so the UI shows a pure-SSO login branch when `identitySource=oidc`. Open-redirect guard and PKCE-verifier round-trip are test-covered. The Helm chart gains admin-UI OIDC SSO config and a local-stack demo.
- **Per-tenant admin-UI login mode.** `GET /api/auth/mode` resolves the login mode (local password vs OIDC SSO) per tenant via `ManagementAuthModeResolver`; the UI queries it on the login screen so different tenants can present different flows. `mintSessionFor` keys grant derivation on the resolved per-scope mode, and `identitySource` is now a system-scope-only concept.
- **Keycloak split-horizon bearer validation.** A Keycloak issuer override lets a bearer minted against an internal issuer URL validate when discovery advertises a public one. FlightSQL identity now maps from `preferred_username` (not `sub`), and `x-qod-superuser` is accepted as an alias of the superuser flag.

### FlightSQL edge - Power BI / ODBC compatibility

- **Apache Arrow Flight SQL ODBC driver compatibility (Power BI).** Conformance fixes for the Arrow Flight SQL ODBC driver: `GetSchema` for query commands, `parameter_schema` set on Prepare results (empty = 0 params), `FlightSqlServerTransaction=NONE` instead of `TRANSACTION`, R7/R12 conformance, per-RPC auth on `DoGet`, `x-qod-authorization` accepted as an alias for `Authorization`, and `LIKE` (not `=`) for the catalog filter in `getStreamSchemas` / `getStreamTables`. Adds a Power BI connector install/connect guide, the signed `.pqx` + self-signed cert path, and per-provider OAuth (Azure / AWS / Google) docs.
- **Literal DML / DDL over FlightSQL.** `INSERT` / `UPDATE` / `DELETE` and DDL statements execute over the Flight SQL update path; the result advertises a `Count` schema so ADBC accepts the stream.

### Features

- **Per-pool `initSql`.** SQL set on a pool is prepended to the federation blob at node spawn (Liquibase changelog `0011`), so every node in the pool runs operator-supplied setup before serving queries.
- **Remote-access local stack.** `PUBLIC_HOST`, a NodePort on `31338`, and Power BI OAuth wiring let the kind-based local stack be reached from off-box clients.

### Fixes and internals

- **Tenant slug as the single key.** A tenant's slug id is now the one identity key, decoupled from its display name; fixtures and specs realigned.
- **Caches moved to Caffeine.** The RBAC effective-set cache and `GoogleGroupsLookup.groupsCache` migrated from `ConcurrentHashMap` to bounded Caffeine caches.
- **UI clears the prior tenant's data on tenant switch** in the Users screen.
- **`run-docker-compose.sh` made bash 3.2 compatible**; the truncated `scopes` value in the Helm `values-local-stack.yaml` fixed.
- **Release hardening.** `scripts/release.sh` now purges stray Metals Scala-CLI scratch dirs before building (a Scala 3.8 TASTy left under the source tree broke `Compile / doc` during `publishSigned`) and builds the Docker image from the `v<version>` tag rather than the post-release `-SNAPSHOT` working tree.
- Node distribution display uses the user name instead of the node position.

## 0.3.2

### Security

- **RBAC tenant scope on every handler.** Every endpoint in `/api/{user,role,group,membership,pool/permission}/*` now checks the calling session's `manageableTenants` before mutating. Tenant-A admin sessions get `403 tenant_forbidden` on tenant-B resources; missing ids return `404` (no cross-tenant existence leak).
- **Statement-history cross-tenant leak fixed.** `GET /api/node/statements` filters the ring buffer by the session's manageable tenants. Allow-set covers both surrogate id and display-name forms so the filter survives the FlightSQL router recording in either shape.
- **JWT session cookies.** `SessionTokenStore` is now a stateless HS256 JWT signer/verifier; login also sets `qod_session=<jwt>` as `HttpOnly Secure SameSite=Lax`. Sessions survive manager restart when `QOD_SESSION_JWT_SECRET` is pinned, enabling horizontal scale-out. The UI drops the localStorage token path entirely - browsers use the cookie; CLI clients still use `X-API-Key`.
- **Cloud secret resolvers gated.** `secretStore = aws-sm | gcp-sm | azure-kv | vault` is refused at config load (resolvers are stubs). Under `dispatch` mode the stubs stay wired so postgres+env deployments work; the runtime error names the prefix and the supported alternatives. UI dropdown options for stub stores are disabled.
- **FederationBlobBuilder SQL-quote-safe.** Every resolved secret value and the expanded alias are doubled (`'` → `''`) before splicing into the operator template. Apostrophes in passwords no longer break the `ATTACH`.
- **DuckLake ATTACH escape hardened.** Postgres calls in `DuckLakeInitializer` switched to `PreparedStatement` binds; the DuckLake connstring uses a two-layer escape (`libpqValue` inside `duckdbLiteral`) so the bearer password can contain any character.
- **Full 128-bit ids.** Every surrogate id is now `<prefix>-<32 hex>` (`Names.newSurrogateId`) instead of `<prefix>-<8 hex>` (~77K-row collision). Legacy 8-char ids in existing rows continue to match.
- **K8s federation works.** Per-pool `qod-fedsql-${tenant}-${tenantDb}-${pool}` Secret carries `extraSetupSql`; pods reference it via `env.valueFrom.secretKeyRef`. `kubectl describe pod` never shows the credential; etcd doesn't either.
- **K8s node tokens persist across restart.** Per-pod `qod-token-${nodeId}` Secret carries the bearer; `discoverExisting` reads it back, so adopted pods don't 401 on manager restart.
- **REST stubs removed.** `POST /api/node/setRole` and `POST /api/node/restart` were stubs that lied about persisting state. Use `POST /api/pool/scale` or `POST /api/node/quarantine` instead.
- **K8s ServiceAccount can manage Secrets.** The chart's `Role` was granting `pods` + `services` but omitting `secrets`, so `KubernetesQuackBackend.ensureTokenSecret` (mandatory pre-pod step) failed with `403 forbidden` and the manager `CrashLoopBackOff`-ed on every fresh helm install.
- **`whoami` error codes differentiated.** A failed lookup used to surface as `{"error":"expired"}` for six different conditions (no token, malformed JWT, signature mismatch from a rotated secret, missing jti, exp past, jti revoked). New `SessionTokenStore.LookupResult` ADT maps each cause to a distinct response code (`no_session` / `invalid` / `expired` / `revoked`), so operators reading the network tab can tell "secret rotated on helm upgrade" apart from "session timed out".
- **Cookie `Secure` flag auto-derives from request scheme.** `sessionCookieSecure` defaults to `auto` (was `true`): the handler reads `X-Forwarded-Proto` per request - `https` → `Secure`, `http`/absent → not `Secure`. `run-jar.sh` on plaintext HTTP no longer drops the `Set-Cookie` response, while helm behind a TLS-terminating ingress keeps `Secure=true` automatically. `QOD_SESSION_COOKIE_SECURE=true|false` still forces either value for misconfigured proxies that strip the header.
- **Column-level security.** Per-role, per-column policies (action: `deny` or `mask`) authored via REST, admin UI, or YAML manifest. The gateway rewrites SELECTs before they reach a Quack node: every covered column reference (in projection, WHERE, HAVING, subqueries, CTE bodies, UNION arms) is replaced with the operator-authored transform expression; `SELECT *` is expanded against the DuckLake column catalog and masked in place; a `deny` match short-circuits the query with a 403-style error. Transform SQL is strict-containment-validated at write time (must be one scalar expression referencing only the protected column; subqueries, denylisted functions like `read_parquet`, `attach`, `pragma_*`, and references to other columns are rejected). Superusers bypass, matching the existing table-level ACL. New `qodstate_role_column_policy` table; new `EffectiveSet.columnPolicies` field; three Micrometer metrics (`column_policy_rewrites_total`, `column_policy_catalog_lookups_total`, `column_policy_rewrite_duration_seconds`); ~25 new tests across `TransformSqlValidatorSpec`, `ColumnPolicyRewriterSpec`, `RoleColumnPolicyStoreSpec`, `RoleColumnPolicyHandlersSpec`, `ManifestColumnPolicyRoundTripSpec`; new admin UI tab "Column policies" on the Role detail page; manifest YAML round-trip with example policy seeded in `bootstrap-demo.yaml`.

### Performance

- **HikariCP on the control-plane store.** `PostgresControlPlaneStore` and `UserStore` use HikariCP (sizes 20 and 10). Handshake path went from 5 fresh TCP+TLS+auth handshakes per request to 5 borrow-from-idle.
- **EffectiveSet cache.** 60s TTL `ConcurrentHashMap` keyed by `(userId, jwtRolesHash, jwtGroupsHash)`; invalidated from every RBAC mutator + `restore()`. Collapses N FlightSQL handshakes for the same user into 1.
- **Manifest export/import.** Single `store.snapshot()` + name→entity index maps replaced N+1 list calls per tenant/user/role.
- **`unsafeRunSync` in `PoolSupervisor.createPool`** removed (proper `flatMap` composition).
- **K8s `waitReady`** switched from a `Thread.sleep(500)` busy-poll to fabric8's `waitUntilCondition` watch - ~120 fewer API-server calls per 60s pod startup.
- **`DuckLakeCatalogReader` evicted** on tenant-db delete: the per-tenant-db Hikari pool is closed promptly. Stale-credential foot-gun closed.
- **Sessions auto-expire.** Initial sliding-window idle TTL on `SessionTokenStore` (superseded by the stateless JWT exp).
- **FlightSQL Prepare cheaper + co-located with Execute.** ADBC `cur.execute()` was hitting the backend twice (Prepare ran the full SQL to read the result schema, Execute ran it again to stream rows), routed independently so the two halves could land on different nodes. Prepare now classifies the SQL through `PrepareStrategy`: DML/DDL skip the node entirely (also fixing a latent double-INSERT hazard), wrappable SELECTs send a `LIMIT-0` subquery probe whose schema feeds `dataset_schema` in milliseconds, and the rest fall back to today's path. The Execute then soft-pins to the node that served Prepare so DuckDB's per-process caches stay warm.
- **Prepare + Execute merged into one UI history row.** The `LIMIT-0` probe no longer records a separate row; the matching Execute carries the probe duration as `prepareDurationMs` and the UI renders it as subtext (`57 ms / prep 28 ms`) under the Execute duration.

### Demo, loaders, and bare-jar UX

- **`LOAD_TPC=N` split into `LOAD_TPCH=N` + `LOAD_TPCDS=N`.** Each benchmark is now opt-in at its own scale factor across `run-jar.sh`, `run-docker-compose.sh`, and the kind-based `run-local-stack-k8s.sh`. `LOAD_TPC=N` stays as a legacy shortcut for both (explicit per-bench vars override it). The Compose + k8s launchers also stop pre-creating Postgres databases that won't be seeded. Fixes a real k8s bug where the chart README already advertised `LOAD_TPCH=1` but the launcher script only honored `LOAD_TPC`, so users following the README got nothing seeded.
- **TPC-DS workload in the bundled load tester.** `tpch-load-test.py` gains `--workload tpcds` (or `LT_WORKLOAD=tpcds`); the runner cycles 7 curated queries (Q3, Q7, Q19, Q42, Q52, Q55, Q98) covering per-group aggregation, multi-way joins, top-N, and a window function. Default schema auto-picks `tpcds1` to match `scripts/load-tpcds-dbgen.sh`. The existing TPC-H mix stays the default.
- **DuckDB CLI + `libduckdb` self-installed by `run-jar.sh`.** First boot fetches both into `$REPO_DIR/.duckdb/<version>/{bin,lib}` at the ABI libquackwire links against (pinned via `DUCKDB_VERSION`, relocatable via `DUCKDB_CACHE_DIR`), prepends them to `$PATH` and `LD_LIBRARY_PATH` / `DYLD_LIBRARY_PATH`, and skips re-downloads on subsequent runs. Mandatory by design: a system duckdb at the wrong ABI crashes the first node spawn with a confusing `UnsatisfiedLinkError`. Air-gapped operators pre-populate the cache directory and the fast path takes over.
- **Loader temp-spill fixed for SF >= 10.** `load-tpch-dbgen.sh` and `load-tpcds-dbgen.sh` now anchor `temp_directory` to an absolute `.tmp/duckdb-{tpch,tpcds}-load/` they pre-create, so DuckDB's spill-to-disk during `dbgen` / `dsdgen` actually lands somewhere writable. Previously the call aborted with `IO Error: Cannot open file ".tmp/duckdb_temp_storage_DEFAULT-1.tmp"` and every subsequent `CREATE TABLE ... AS SELECT FROM memory.main.<t>` failed because the source tables were never populated.
- **Release script attaches a GitHub Release.** `scripts/release.sh` now calls `gh release create v$version --generate-notes` against the tag sbt-release pushed, so the assembly jar lands on the GitHub Releases page alongside the Maven Central artifact.
- **Smoke test rewritten for the live demo bootstrap.** `test-api.sh` was creating a pool under tenant `tpch` (which never existed in the demo bootstrap) and missing the `tenantDb` field added when the pool key became `(tenant, tenantDb, pool)`. It now creates a `smoke` pool under `acme/acme_tpch` (cleaned up at the end), and tolerates 409 on tenant + pool creation so re-runs are safe.

### Cleanup

- **File-state mode dropped** (~250 LOC + ~80 LOC tests): `PostgresStateStore`, `StateStore`/`FileStateStore`, `StoredState`, `StoredPool`, `StoredTenant`. The `stateStorage` / `statePath` config knobs and all 5 `Main.scala` guards are gone. Postgres is the only control-plane store.
- **`ManifestIdentity` + `FederationImportSummary`** dropped (defined, never read).
- **`DbAdmin.dropDatabase` non-FORCE fallback** dropped (PG16 floor - always supported).

## 0.3.1

- Per-pool node placement (cohorts) - a pool can now be defined as a
  list of cohorts. Operators can express
  layouts such as "2 writers on nodes tagged `disktype=ssd` and 1 reader
  + 1 writer on nodes tagged `disktype=hdd`" against a single pool.
- JDK floor raised to 21.