# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test commands

```bash
sbt run                 # run the manager (forks JVM - see "JVM forking" below)
sbt test                # run the full Scala test suite (~2802 tests)
sbt assembly            # build distrib/quack-on-demand-assembly-*.jar (UI is bundled in)
sbt "testOnly ai.starlake.quack.route.RouterSpec"        # one test class
sbt "testOnly *RouterSpec -- -z \"picks the least-loaded\"" # one test by name fragment
sbt scalafmtAll         # format (scalafmt 3.10, scala3 dialect, maxColumn 100)
```

`sbt test` in a git worktree: the forked test JVM finds `libduckdb` (needed by the native `libquackwire`) through the main checkout's `.duckdb` cache, because [build.sbt](build.sbt) points `DUCKDB_CACHE_DIR` there for forked tests unless already set (macOS SIP strips `DYLD_*`, so the loader path cannot help). When the native cannot load, the affected specs fail fast with the preload paths named instead of hanging.

Boot the manager from the uber-jar (with TLS cert auto-gen + Postgres reachability probe):

```bash
./scripts/run-jar.sh           # foreground
QOD_VERSION=BUILD ./scripts/run-jar.sh   # sbt assembly first
QOD_VERSION=LOCAL ./scripts/run-jar.sh   # newest distrib/ jar, no rebuild
./scripts/stop-jar.sh            # SIGTERM → wait → SIGKILL
```

On **Windows** the manager runs natively (no WSL/Docker) via PowerShell twins:
`scripts/run-jar.ps1` / `scripts/stop-jar.ps1`, and nodes spawn through
`scripts/spawn-quack-node.ps1` (a mirror of the bash spawn script: it starts
`qod-node.exe` with the init SQL on its stdin as UTF-8 bytes, and qod-node exits when
its wrapper does). Teardown uses `taskkill /T` so the `qod-node.exe` grandchild dies
with its wrapper. The native `quackwire.dll` is
bundled automatically whenever `libquackwire/binaries/windows-x86_64/quackwire.dll`
(and `windows-aarch64/quackwire.dll` for Windows on ARM64) exists in the checkout; the
JVM loads the one for its own `os.arch`, so an ARM64 host needs an ARM64 JVM and the
windows-arm64 DuckDB (`run-jar.ps1` picks the DuckDB build from the JVM's `os.arch`). The native client is the manager's only client to its nodes (the embedded
JDBC fallback, `QOD_NATIVE_CLIENT` and `QOD_NODE_DISABLE_SSL` were dropped with DuckDB 2.0, whose
JDBC disables Arrow export): `QuackNativeSupport.bootCheck` loads it early in `Main.bootManager`
and a platform with no bundled binary or a native that
cannot load refuses to boot, naming `os.name`/`os.arch`, the expected
`libquackwire/binaries/<platform>/` file and the `DUCKDB_CACHE_DIR` hint.
Each libquackwire binary embeds `quackwire-build-info:protocol=..;duckdb=..;duckdb_source=..;quack=..`
(set by `native/quackwire/CMakeLists.txt`, which probes the linked libduckdb); `bootCheck` compares it
with `/native/VERSION` and the loaded libduckdb and refuses a stale native, and
`scripts/release/quackwire-build-info.sh` checks the same marker on the files. Until DuckDB v2.0.0
ships only osx-aarch64 is a 2.0 build: the other four vendored natives are refused at boot, so this
branch runs only on Apple Silicon. `scripts/release/check-release-pins.sh` (workflow `release pins`)
keeps the pre-release JDBC and `mavenLocal` off main. The qod CLI caches DuckDB per release
(`<user cache>/qod/duckdb/<version>/{bin,lib}`, the `DUCKDB_CACHE_DIR` layout) and exports
`DUCKDB_CACHE_DIR` to the manager JVM on every launcher path, so the preload never depends on
`DYLD_*` (stripped by SIP from `/usr/bin/java`) or a baked rpath.

**DuckDB 2.0.** The engine is the v2.0.0 release and the JDBC is `org.duckdb:duckdb_jdbc:2.0.0.0` from Maven Central. `run-jar.sh` and the qod CLI provision the CLI and libduckdb from the release's `duckdb-cli-<platform>.tar.gz` / `duckdb-shared-libs-<platform>.tar.gz` (2.x asset names; 1.x shipped `.zip`s). `scripts/dev/build-duckdb-prerelease.sh` is obsolete: it deletes the cache it writes to, the release's included, so never run it.

**DuckLake migration.** Only the manager's DuckLake initializer attaches with `AUTOMATIC_MIGRATION`, and only when the catalog needs it (`DuckLakeInitializer.migrationPlan`, decided under its advisory lock): a catalog older than the latest DuckLake, or one at the latest dev version last migrated by another engine build (recorded in `qod_meta.ducklake_migration` in the catalog's database; `LatestDuckLakeVersion` is pinned by a spec). A current catalog attaches with no DDL. The initializer's catalog connection carries `lock_timeout=5000`; a timeout is retry-later (`CatalogLockTimeoutException`, `PoolSupervisor.duckLakeInitPending`, retried by the leader's reconcile loop), never a blocking mismatch. Nodes, loaders and the demo seed attach as-is (DuckDB 2.0 attaches any catalog >= 1.0 without migrating). Migration is one-way: back up catalog databases before upgrading.

UI dev loop (proxies `/api/*` to `localhost:20900`):

```bash
cd ui && npm install && npm run dev
```

The UI is built into `src/main/resources/ui` by [project/UiBuild.scala](project/UiBuild.scala) as a resourceGenerator, so `sbt compile`/`sbt assembly` will run `npm ci` + `npm run build` automatically. There is no manual UI build step for a regular Scala compile.

## JVM forking (don't disable it)

[build.sbt](build.sbt) sets `Compile / run / fork := true` and `Test / fork := true` and adds `--add-opens=java.base/java.nio=ALL-UNNAMED` + `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`. Arrow Flight's `arrow-memory-unsafe` allocator reflects into `java.nio` internals; without forked JVM + the opens, Arrow crashes at class init under Java 17+. The assembly jar embeds an `Add-Opens` manifest attribute (JEP 261) so `java -jar` works without extra flags. The startup script also passes `-Darrow.allocation.manager.type=Unsafe` - without it Arrow auto-picks the netty allocator, which crashes with `NoSuchFieldError: chunkSize` because the assembly bundles a newer Netty than `arrow-memory-netty 14.0.1` reflects against.

## Architecture - the bits that span multiple files

The process is a single uber-jar that exposes **four** sockets (the fourth, the native Quack
front door on `:9494`, is described after the list):

1. **Manager REST + React UI** on `:20900` ([ManagerServer.scala](src/main/scala/ai/starlake/quack/ondemand/ManagerServer.scala), Tapir + HTTP4s Ember). Endpoints live under `ondemand/api/*Handlers.scala`. The React SPA at `/ui/*` is served from `src/main/resources/ui` (built by the resourceGenerator above).
2. **Arrow FlightSQL edge** on `:31338` ([FlightEdgeServer.scala](src/main/scala/ai/starlake/quack/edge/FlightEdgeServer.scala) → [FlightProducerImpl.scala](src/main/scala/ai/starlake/quack/edge/FlightProducerImpl.scala) → [FlightSqlRouter.scala](src/main/scala/ai/starlake/quack/edge/FlightSqlRouter.scala)). TLS is on by default, cert auto-generated at `certs/server-{cert,key}.pem` if missing.
3. **Quack nodes** on `:21900–22500` - child processes spawned by [scripts/spawn-quack-node.sh](scripts/spawn-quack-node.sh) (local mode) or pods ([KubernetesQuackBackend.scala](src/main/scala/ai/starlake/quack/ondemand/runtime/KubernetesQuackBackend.scala)). Each node is a qod-node process (see "qod-node" below) serving DuckDB Quack, with a metastore env-var contract: `TenantDb.NodeEnvKeys` = `pgHost / pgPort / pgUser / dbName / catalogAlias / schemaName / dataPath / encrypted`, and nothing else of a metastore (tenant-admin input: `BASH_ENV`, `PATH` or `QOD_NODE_BIN` there once ran commands as the launcher's user). Every launcher passes only those keys and the metastore's own secrets (local spawn env, Kubernetes pod env, fleet assignment env, and `qod fleet join`'s overlay, `_NODE_ENV_KEYS`), dropping any other key with one ERROR per pool and key that says how to remove it (`NodeMetastore`); `database/create`, `database/update` and a manifest import refuse a key outside `TenantDb.KnownMetastoreKeys` (those minus the manager-stamped `encrypted`, plus `pgPassword` / `encryptionKey`) with `400 invalid_metastore_key` before writing anything, except that `database/update` takes a secret-named one (`TenantDb.SecretKeys`: no response shows it, an update leaving it out keeps it) sent empty, which removes it (`TenantDb.metastoreUpdateKeyError`). A manifest export leaves such keys out, one ERROR each, so it always imports back; it drops an encryption key silently, in any spelling (no export carries one). The secrets (`pgPassword`, `encryptionKey`, federation and object-store SQL, the node token and the node key: `NodeSecretFiles.Keys`) arrive as files under `QOD_SECRETS_DIR`, never as env vars, and the catalog password then rides a temporary DuckDB secret, never an ATTACH string (`CatalogPgSecret`). **`spawn-quack-node.sh` (or `spawn-quack-node.ps1` on Windows) is invoked by [LocalQuackBackend.scala](src/main/scala/ai/starlake/quack/ondemand/runtime/LocalQuackBackend.scala) - don't run it directly.** `LocalQuackBackend.defaultCommand` picks the script by OS: bash on Unix, `powershell.exe -File spawn-quack-node.ps1` on Windows (path from `spawnScriptWindows` / `QOD_SPAWN_SCRIPT_WINDOWS`).

4. **Native Quack protocol front door** on `:9494` ([QuackFrontDoorServer.scala](src/main/scala/ai/starlake/quack/edge/quack/QuackFrontDoorServer.scala) → [QuackFrontDoor.scala](src/main/scala/ai/starlake/quack/edge/quack/QuackFrontDoor.scala)): what a DuckDB client `ATTACH 'quack:host:9494' AS qod (TYPE quack, TOKEN 'tenant=..&pool=..&user=..&password=..')`es to. The token is the FlightSQL JDBC query string; the handshake is the Flight edge's ([EdgeHandshake.scala](src/main/scala/ai/starlake/quack/edge/EdgeHandshake.scala)); each statement runs through `FlightSqlRouter.executeWith` (the pipeline generalized over the node call) and is relayed to one raw node connection per statement ([QuackNodeLink.scala](src/main/scala/ai/starlake/quack/edge/quack/QuackNodeLink.scala)), the node's result bytes going back to the client untouched. Every PREPARE a link sends follows a fresh session handle (see "qod-node" below); any failure on a client transaction's link (a handle that fails, a statement lost in transport) ends that transaction like an admin kill (the link is disconnected, the pin dropped, the statement and every later one answered with an aborted-transaction error until the client's ROLLBACK, which is served on a fresh link; a client COMMIT lost after it was sent is answered as of unknown outcome instead), because the transaction cannot go on there: DuckDB 2.0 refuses every statement but ROLLBACK and COMMIT in an aborted transaction, the handle's CALL included, and a statement lost in transport may have run; anywhere else a refused handle is a retryable failure. Statements calling a `duckdb_*()` catalog function are soft-pinned to the node that served the session's previous one (`QuackSession.catalogNode`): the client joins its ATTACH-time schema and table listings on per-process oids, so split across nodes they yield a catalog with no tables. The manager never encodes DuckDB chunks; [QuackWire.scala](src/main/scala/ai/starlake/quack/edge/quack/QuackWire.scala) parses only headers and the small request messages and speaks Quack protocol 3 only (DuckDB 2.0 clients; a 1.x client gets `requires a DuckDB 2.0 client`). It admits a 2.0 client's `BEGIN TRANSACTION` and its INSERT data path (`scan_data_from_quack_client`, scoped to that client's own connection). Config block `quack-native` (`QOD_QUACK_*`), TLS off by default because the DuckDB client only speaks plain HTTP to loopback hosts. `QuackCompatibilitySpec` drives the real `duckdb` CLI and Python client end to end (cancelled without `duckdb` on PATH). Design: docs/superpowers/specs/2026-09-20-native-quack-front-door-design.md.

   The manager-to-node native client (libquackwire, on by default) holds a heartbeat lease on each node connection: `QOD_QUACK_NODE_LEASE_SEC` (default 30, floor 1; a heartbeat goes out every third of it). Node connections are released on error and on cancel. It is the only manager-to-node client: there is no embedded fallback.

A FlightSQL request flows: `client → FlightProducerImpl → AuthenticationService → FlightSqlRouter.execute (policy holders' guard, CLS / RLS rewriters, metadata filter) → StatementClassifier (READ/WRITE/DDL) → Router.pick(snapshot, kind) → QuackHttpAdapter (session handle, then the statement) → child node's /quack HTTP endpoint → qod-node's parse screen and plan decision (verdict from the replica's DecideRoute / AuthzDecider) → Arrow stream back through Flight`. The node's verdict is the only table, function, setting and file check: the gateway has none of its own (in-engine authorization Phase D). Tests for the routing core ([RouterSpec.scala](src/test/scala/ai/starlake/quack/route/RouterSpec.scala), [StatementClassifierSpec.scala](src/test/scala/ai/starlake/quack/route/StatementClassifierSpec.scala), [FlightSqlRouterSpec.scala](src/test/scala/ai/starlake/quack/edge/FlightSqlRouterSpec.scala)) exercise this without the Flight wire.

### qod-node (the node process)

Every node is a qod-node process ([native/qod-node](native/qod-node/)): our own executable on the
unmodified official DuckDB release library (the shared libduckdb on Linux and macOS, the release
static libraries on Windows), the host the in-engine authorization work builds on (design:
docs/superpowers/specs/2026-10-23-in-engine-authorization-design.md). The spawn scripts hand it the
init SQL on stdin (`--init -`), never on disk; it runs it statement by statement and carries on past
a failing one, as the DuckDB CLI did, and does the strict steps itself: the node's secret directory
first, then `INSTALL` / `LOAD quack` (right before the first init statement that restricts the node's
file or network access, so after the trusted statements that
set `custom_extension_repository` / `extension_directory`; else after the init SQL), `remote_pushdown`
put back into `disabled_optimizers` if a trusted init statement dropped it, the parse screen and
`quack_serve`, with the token, the node key and the init nonce it reads from `QOD_SECRETS_DIR` (an
ephemeral directory it removes once read; the spawn script removes it too on every exit). The init
stream's first line is `-- qod:init-nonce <nonce>` (`QOD_INIT_NONCE`, fresh per start); qod-node
refuses to run any init SQL when it is missing or differs from the nonce it read. It has no CLI
dot-commands, so init SQL needs no dot-command screen. It exits when its
wrapper does (`--parent-pid`) or on SIGTERM; a stop while it is still starting ends it at once. A
failing init statement is logged with DuckDB's error minus the `LINE n:` statement excerpt (init SQL
carries credentials).

Every statement the manager sends a node runs for a verified principal. Before each PREPARE on a node
connection (a relayed client statement, a stamp prelude, a COMMIT) the manager sends its own PREPARE
`CALL system.main.qod_session('<handle>')`: a session handle `<base64url JSON>.<hex HMAC-SHA256>` naming
the principal (`NodePrincipal`; `System` for probes, stats, attach checks, Iceberg metadata,
maintenance, merges, front-door admin results and RoutedExecutor's system caller), the node, the pool,
a single-use nonce and a 60 s expiry, signed with the node's key (`NodeKeys`: `HMAC-SHA256(secret,
"qod-node-key/v1/" + nodeId)`). The call is qualified, like the screen, so that no macro a session
creates in its default catalog or temp schema can shadow it. The key reaches qod-node as the secret
file `QOD_NODE_AUTHZ_KEY` and the id as `QOD_NODE_ID` (`--node-id`); the secret is `QOD_NODE_KEY_SECRET`
when set (at least 32 characters, identical on every replica), else one value generated on first boot
and kept in the control plane (`qodstate_node_authz`, Liquibase `0047`), so rotating it means restarting
every node. Unless `QOD_NODE_KEY_SECRET` is set, whoever can read the control-plane database (backups,
dumps, read replicas) can mint a handle for any principal on any node. `QOD_INTERNAL_URL` (the replica
URL carried in every handle, which nodes call for verdicts; `quack-on-demand.nodeAuthz.internalUrl`)
defaults to `http://<QOD_POD_IP>:<port>` when the pod IP is set (the Helm chart sets it from the
downward API, so each replica names itself), else `http://<QOD_ON_DEMAND_HOST>:<port>` when the
manager binds one specific address, else `http://127.0.0.1:<port>`. `BootPreflight.checkInternalUrl`
refuses, before any socket opens, an unset one in fleet mode (each replica's own address on the
servers' private network, never a load balancer or an address several replicas share), a loopback or
unspecified host on Kubernetes (`kubernetes` or `k8s`, any loopback form `inet_aton` reads; a
hand-written manifest must set `QOD_POD_IP` or `QOD_INTERNAL_URL`), and any URL qod-node's parser
(`ParseHttpUrl`) refuses: not lowercase `http://`, user info, a query or fragment, a bad port. Boot
prints `node decide URL: <url>`. The chart fails on `env.QOD_INTERNAL_URL` with `replicaCount > 1`
and on `env.QOD_POD_IP`; its NetworkPolicies let node pods reach the manager pods on TCP 20900 (the
whole REST port: an L4 rule cannot filter by path, and every route authenticates), whatever
`networkPolicy.ingressFrom` says, in the release namespace only.

qod-node checks the handle in its parse screen (`qod_screen`, installed as
`quack_authorization_function`, which refuses any PREPARE on a connection with no current principal)
and in `qod_session`'s bind (nonce, one principal per connection), and a `post_bind` hook refuses
every statement on a quack connection without one. A refused handle drops the connection unsent
(retryable). The health probe's canary runs a handle-less `SELECT 1`: a node that answers it is
unhealthy and never routed to. A node started before session handles does not know `qod_session` and
is never routed to either. Nor is a node of an earlier node protocol, on any backend: the handle's
`v` is 3 (`NodeHandles.Version` = qod-node's `kHandleVersion`, node protocol 5), and such a node
refuses every handle of another version, the probe's included (and a protocol 5 node refuses theirs).
Restart every node after upgrading. What a principal may do is decided on
the node (below).

**Node-side authorization (node protocol 5).** A non-superuser statement is decided on what
DuckDB resolved for it, on the node, before it runs, and nowhere else. The parse screen re-parses every PREPARE with
DuckDB's own parser and refuses what binding would already do outside the node (IMPORT, CONNECT,
DESCRIBE / SHOW / SUMMARIZE of a table or query (the listing forms such as SHOW TABLES stay), PRAGMAs
off a read-only list, the fresh-connection functions,
`query()`/`query_table()` with a non-literal argument; a literal one is screened like the statement,
at any depth), any call-shaped name the generated classification does not list or classes superuser,
anywhere in the text, and, in every slot DuckDB evaluates while binding (SET values,
table-function and PRAGMA arguments, LIMIT/OFFSET, COPY options, AT clauses, ...), any function not
classed allowed and `nextval`/`setval`/`currval`; every location a statement names (file readers,
COPY, ATTACH, IMPORT, EXPORT, LOAD) is sent to the manager as a `kind = screen` description before
binding. Replacement scans are off for such a session (`ClientConfig::use_replacement_scans`, no SQL
reaches it): `FROM 'x.csv'` is "table not found". Every node opens with `remote_pushdown` in
`disabled_optimizers` (DuckLake-on-Postgres folded constant calls before `post_bind`).
Every principal, superusers and the manager included, is refused SET / RESET of
`disabled_optimizers`, `enable_optimizer`, `debug_disable_optimizer`,
`quack_authentication_function` and `quack_authorization_function` on a quack connection (`QoD:
<name> cannot be changed`, the describer's `IsLockedSetting`); only a trusted init statement can
touch them, and qod-node puts `remote_pushdown` back after the init SQL.
`post_bind` describes the bound plan (objects with access and read columns, functions, settings with
the scope DuckDB applies, control operations; canonical JSON, fingerprint = SHA-256) and walks the call
names as written, in the client text and transitively in every view, secure view, macro, table macro
and stored expression the statement reaches (generated columns read, defaults of write targets, CHECK
constraints, triggers), resolved as DuckDB resolves them: a name that resolves to a
non-built-in entry or that the classification does not list is refused. `pre_optimize` describes the
plan again, so an extension's own plan rewrite cannot slip past; `OnFinalizePrepare` refuses anything
that reached it without a verdict and `OnRebindPreparedStatement` rebinds when the principal or policy
version changed. The node asks the replica named in the handle: `POST /api/internal/authz/decide`
(`DecideRoute`, mounted outside `apiKeyGuard` like `/mcp`, never in OpenAPI, CLI parity or
`McpCoverageSpec`), body signed with the node key (`X-QoD-Node`, `X-QoD-Signature`), answered with a
verdict in the handles' wire format (`<base64url JSON>.<hex HMAC>`). `AuthzDecider` judges with the
effective set the handle was minted for (`MintedHandles`, by nonce, kept 5 min past expiry; an unknown
nonce is a retryable refusal): superusers and the system principal never call it; always-on rules
(superuser-only functions and settings, global scope, writes or DDL in `system`, the
`__ducklake_metadata_*` catalogs, extension operators; the temp catalog always allowed); objects by the
tenant's ACL mode through the existing grant matching or OPA (`OpaInput` gains `columns`, `via`,
`locations`); every function and setting by classification (`native/qod-node/functions.json` and
`macros.json` from qod-node, `src/main/resources/authz/function-classes.tsv` and
`setting-classes.tsv`; an unknown one is refused "function <name> is not classified"); files by
`LocationChecker`; PAT `branchOnly` and attenuation. There is no switch: enforcement is always on,
and `QOD_ACL_ENABLED`, `QOD_ACL_FILTERED_METADATA`, `QOD_ACL_DIALECT` and `QOD_NODE_LOCKDOWN` (or
their `quack-flightsql.acl.*` / `quack-flightsql.nodeLockdown` paths) set to any value refuse boot,
each naming the variable to unset (`BootPreflight.checkRemovedSwitches`, decision 28). A plan
verdict lives 60 s (an OPA tenant's: its OPA cache TTL,
at least 5 s) and is cached per node (LRU, `QOD_AUTHZ_CACHE_ENTRIES`, default 10000) by principal,
policy version, session context and fingerprint (statements that differ only in constants share
one); screen verdicts are never cached. Decide timeout `QOD_AUTHZ_DECIDE_TIMEOUT_MS` (default
2000). The manager reads both (`quack-on-demand.nodeAuthzLimits`, refused at boot outside qod-node's
ranges, 1 to 60000 ms and 0 to 10000000 entries) and forwards them to every node on every runtime:
the local spawn env, the Kubernetes pod env and the fleet assignment's `authzLimits`
(`NodeAuthzLimitsConfig`); a node never inherits them from elsewhere. A timeout, an unreachable
replica or a 503 is `QoD: authorization unavailable, retry`, which the gateway answers as Flight
`UNAVAILABLE` (`FlightSqlRouter.isNodeAuthzUnavailable`) for a single statement and the native front
door relays; a multi-statement submission so refused is answered `INVALID_ARGUMENT` "not retryable
as a whole: the statements before the refused one may have run" (`FlightSqlRouter.BatchNotRetried`).
Every other node refusal reads `QoD: <reason>`, is permanent and is booked as a denial
(`FlightSqlRouter.isNodeRefusal`: Flight `UNAUTHORIZED`, `access denied: Permission Error: QoD:
...`, history status `denied`, a `data-denial` audit event). qod-node
resolves a `QOD_INTERNAL_URL` host name once per 30 s per name (single-flight, stale addresses served
for 1 s after a failed refresh), outside the decide deadline. The policy version is
`qodstate_tenant.policy_version` (Liquibase `0050`, values from the global
`qodstate_policy_version_seq`), bumped by the plpgsql trigger `qod_bump_policy_version()` on every RBAC
table and on the tenant's ACL and OPA columns (`0051` adds `qodstate_tenant_location`, `0052`
`qodstate_user_s3_credential`, which bumps the user's tenant; `0054` adds
`qod_bump_every_policy_version()`, which bumps every tenant, on a change to the data-path deny set:
`qodstate_tenant_db` and `qodstate_managed_prefix`), carried in every handle
with `ctx`, the SHA-256 of the session's authorization context (JWT roles and groups, a PAT's
restriction, the pool, every claim of a bearer token), so a restricted token never reuses a full
session's verdict, and with `path`, the session's default `<catalog>.<schema>` (the router's USE
prelude), signed. A secure view's body runs with its owner's rights (`via` on its objects and
functions) and only for reads; a boundary is refused while the session holds a temp entry other than a
table, sequence, index or DuckDB's own pivot enum, a `SET VARIABLE` variable, a session setting other
than `schema` / `search_path`, a path other than the handle's, or when its definition holds an
unqualified table, a plain view, a non-built-in type, `query()`, a time-travel read or a call that
resolves outside the system catalog. The gateway's text screens, its table-ACL check
(`PostgresAclValidator`, `OpaValidator`, `StatementValidator`) and its file screens are gone (Phase D,
decision 29). What the manager still refuses before a node sees a statement: for a principal holding
column or row policies only, the parse screens the policy rewriters depend on (`ProtectedWriteGuard`
in `edge/policy`, decision 27: a quoted identifier holding a dot, a catalog-reaching call such as
`query()` / `query_table()` or a SQL-valued SET, an ambiguous two-part name headed by an attached
catalog, a batch that moves the schema or search path, and a policy rewrite reaching anything the
original did not, `RewriteScope`); for every caller, a client data scan off the native front door
(`FlightSqlRouter.clientDataScanRefusal`); and, for a principal the metadata filter narrows, a COPY
it cannot read alone (`FlightSqlRouter.copyListingRefusal`). For tenant principals the gateway rewrites `DESCRIBE`,
`SHOW <table>`, `PRAGMA table_info` / `show`, `SUMMARIZE <table>` and (when the metadata filter
narrows) `SHOW TABLES` into SELECTs over the filtered listings plus a read of the object
(`RefusedForms`), and has the node evaluate a computed file location (`ComputedLocations`, a File
function's first argument or COPY's `TO (<expr>)`, at most 4 KiB) as the principal and substitutes the
literal, so the full screen judges it; any failure leaves the statement as written (inside a native
front door transaction the evaluation runs on the transaction's own link, and a failing evaluation, a
NULL or a non-text value aborts the transaction, as DuckDB would; a gateway refusal of it leaves the
transaction open).

Files by path, for non-superusers (decisions 15, 18, 23). **S3 only with keys of the user's own.**
Every user registers credentials for a scope `s3://<bucket>/` or `s3://<bucket>/<prefix>/` (whole
segments, a lowercase bucket, never an access point alias) from their profile session or PAT: REST
`POST /api/profile/s3/list|add|remove` (on the profile allowlist), `qod profile s3 list|add|remove`
(the secret and a session token only from stdin or a prompt), the profile page. Rows are in
`qodstate_user_s3_credential` (Liquibase `0052`, one per user and scope, at most 32 per user:
`too_many_credentials`; the secret write-only, `secretSet` on responses, redacted in `toString` and
logs; not in manifests; deleted with the user). A tenant admin lists and removes their own users'
credentials (`POST /api/user/s3/list|remove`, `TenantScopeCheck`, `qod user s3 list|remove`, the admin
user view), never adds one; MCP lists and removes only (`list_my_s3_credentials`,
`remove_my_s3_credential`). An endpoint other than AWS's own must be listed in
`quack-on-demand.userS3.allowedEndpoints` (`QOD_USER_S3_ALLOWED_ENDPOINTS`: `host[:port]` for TLS,
`http://host[:port]` for plain HTTP; empty means AWS only). A scope at or under a database data folder
is refused when added (`400 scope_in_data_folder`) and again at use. Every handle carries `creds`, the
SHA-256 of the user's credential ids and update times (empty for none); when it differs from what a
node connection last installed, or a client ROLLBACK undid those secrets, `qod_session` fetches the set
from the handle's replica (`POST /api/internal/authz/credentials`, `CredentialsRoute`, mounted beside
the decide endpoint and signed the same way, honored only for a handle that replica minted
(`IssuedHandles`), answered in the verdicts' signed format and bound to the digest), keeps it per
principal and digest for 5 minutes, and installs one connection secret `qod_uc_<id8>` per credential
(DuckDB's `connection` secret storage: invisible to other connections, transactional, every s3 key
named so no session setting fills one in). A failed fetch refuses the statement, retryable, and no
statement runs on that connection until a prelude succeeds. A user's `s3://` URL is admitted only
under one of their own scopes and outside every data folder; one that a node secret of its own (an
Iceberg catalog's `qod_ice_*`, an operator's) would sign over the user's is refused on the node. The
keys travel manager to node signed, not encrypted, like the node token: a private network is
required. The keys sit in plaintext in the control-plane database (`qodstate_user_s3_credential.
secret_access_key`, `session_token`), so its backups and replicas carry them. Superusers are not
restricted by these rules, but the node's own keys no longer sign for them outside the data path: they
use credentials they registered, or a secret they create.
**Local folders are tenant-authorized** (`qodstate_tenant_location`, Liquibase `0051`, kind `local`
only; REST `POST /api/tenant/location/list|add|remove` (superusers and the tenant's admins; an `s3://`
location is refused, `400 invalid_location`), `qod tenant location list|add|remove`, MCP
`list_tenant_locations` / `add_tenant_location` / `remove_tenant_location`, the tenant page of the UI,
manifest `tenants[].locations`): an absolute POSIX folder under `<root>/<tenant>/`, `root` being
`QOD_TENANT_FILES_ROOT` (`quack-on-demand.tenantFiles.root`; unset = no local folder can be authorized;
a Windows path cannot be stored, so Windows nodes admit no local folder). No path may lie under any
database's data path (every tenant's, branch siblings, managed prefixes within retention), compared
on whole segments; relative paths, empty, `.` and `..` segments, percent-escapes and globs in a stored
folder are refused; the node resolves the deepest existing ancestor of a path (symlinks included)
before the check, but does not judge links below it (CONTRACT item 33): through a glob segment
(DuckDB expands one with `stat()`, following links) or a COPY with `PARTITION_BY`,
`PER_THREAD_OUTPUT` or `OVERWRITE` (its directory walk follows linked directories), a symbolic link
inside an authorized folder can lead out of it. Keeping tenant folders free of symbolic links is an
operator duty, uploads that preserve links (`rsync -a`, some SFTP clients) included; users cannot
create links with SQL. The stakes: such a link can point into any tenant's data folder, and a COPY
with `OVERWRITE` deletes files under its target. A data path the checker cannot place
on the node (a relative one off the local runtime, or `DataFolders.Unplaced`: a DuckLake or
duckdb-file row with no data path while the default metastore has none) denies every local location
to every tenant. Every other scheme (`http(s)`, `gs`, `az`, `r2`) is refused to non-superusers. Not
covered: an attached Iceberg catalog's raw warehouse files (decision 21). An Iceberg catalog that
neither vends credentials nor has `awsCredentials` reads its table data with each reading user's own
keys. Iceberg tables on S3 are read-only through QoD for every principal, superusers included, until
upstream duckdb-iceberg signs its commits with connection secrets (DuckDB v2.0.0 commits manifests
without them, and qod-node installs every registered credential `IN connection`: HTTP 403 at
commit). An Iceberg source's `awsCredentials` carry no
S3 endpoint, so an S3-compatible (non-AWS) warehouse is read only with users' own keys or vended
credentials. A change of the default metastore's `dataPath`
in the configuration bumps no policy version: restart the nodes after one.

**Node S3 hardening (decision 23).** Every node opens with `enable_external_file_cache` and
`enable_http_metadata_cache` false (both are node-wide and keyed by path, so a session could read
bytes another user's keys fetched). Nodes hold no unscoped `quack_s3` any more (only the manager's own
embedded DuckDB in `DuckLakeInitializer` does): the data path signs with `qod_data_s3` (the manager's
`QOD_S3_*` keys, or `PROVIDER credential_chain` after `INSTALL aws` / `LOAD aws` when there are none)
or the database's own `qod_db_store`, scoped to the data path and its branches' `<path>__br_` prefix
(a branch node: the parent's and its own), every key named. When httpfs loads, qod-node resets the
GLOBAL settings httpfs copied from `AWS_*` / `DUCKDB_S3_*` to their defaults
(`ResetS3EnvironmentDefaults`), and when aws loads it sets `aws_default_credentials` false (its default
storage would sign any URL no secret matches with the instance's credential chain). Non-superusers may
not CREATE or DROP a SECRET (any storage, `connection` included), SET or RESET an `s3_*` or
`external_file_cache_*` setting nor any of `kReservedSettings` in `screen_rules.cpp` (secret
defaults, certificate verification, proxies, `extra_http_headers`, the HTTP client and connection
caching, credential refresh, `gcs_user_project`, etag checks, the caches), nor ATTACH (an attached
database keeps the keys it was opened with and serves every connection).

`allow_community_extensions`, `allow_unsigned_extensions` and `autoinstall_known_extensions` are
false on every node unless the operator sets `quack-on-demand.nodeExtensions.{allowCommunity,
allowUnsigned, autoinstallKnown}` (`QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS`,
`QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS`, `QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS`; no Helm value: set them
in the chart's `env`), which reach qod-node as `--allow-community-extensions` etc. through the
non-secret spawn env on every backend and apply before the database opens, so init SQL cannot load
community or unsigned code either. Only superusers change them at runtime, and DuckDB refuses either
`allow_*` once the database runs. With autoinstall off, the spawn scripts `INSTALL httpfs` and
`INSTALL avro` before the tenant's initSql (an `extension_directory` or
`custom_extension_repository` a database's initSql sets does not apply to those two), and any other
statement that relied on autoinstall fails unless its extension is installed explicitly.

**Tenant initSql runs under an allowlist.** `initSqlTrusted` on `qodstate_tenant_db` and
`qodstate_pool` (Liquibase `0053`, read-only on responses) records who wrote the text: a superuser,
the manager (`qod serve` boot provisioning, manifest import, a branch copying its parent) or an empty
text is trusted; a tenant admin's is not (`InitSqlTrust`). Trust rides the secret file's name
(`tenantDbInitSqlTrusted` / `tenantPoolInitSqlTrusted` vs `tenantDbInitSql` / `tenantPoolInitSql`);
the spawn scripts wrap an untrusted text between `-- qod:untrusted-begin <nonce> <label>` and
`-- qod:untrusted-end <nonce>`, and qod-node (`init_policy.cpp`) runs such a section statement by
statement, each parsed by DuckDB and run only when it is one `SET` / `RESET` (default or GLOBAL scope,
one literal) of the 15 names in `native/qod-node/init-settings.txt`, a read-only `PRAGMA` with literal
arguments, or `INSTALL` / `LOAD` by bare name of the 21 in `init-extensions.txt` (no `FORCE`, `FROM`,
`VERSION`, path or URL); anything else is skipped and logged by position, never by text. A database's
initSql runs before the catalog ATTACH, a pool's after it and before the federation SQL. Writes
(`database/create|update`, `pool/create`, `POST /api/pool/setInitSql` / `qod pool set-init-sql` / MCP
`set_pool_init_sql`) check a tenant admin's changed text with `qod-node --check-init` (SQL on stdin,
nothing run) and refuse `400 init_sql_refused` naming each statement's position and reason; where the
manager has no local qod-node (Kubernetes, fleet without `QOD_FLEET_EPHEMERAL=local`) the write is
accepted unchecked with `initSqlWarning`. A tenant admin's initSql holds at most 65536 bytes; the
`qod:untrusted-` marker text is refused in any initSql, every writer. A superuser re-saving an
unchanged text marks it trusted; boot WARNs per untrusted row that loses statements
(`InitSqlTrustReport`).
Federation sources of type `sql` (free-form `setupSql`) are superuser-only to create or change (`403
superuser_required`; `setup_sql_trusted`, false for every sql row stored before `0053`,
boot WARN per such row), and every `{{secret.NAME}}` in a `sql` template must sit inside a plain
single-quoted literal (`SecretPlaceholderScan`, `400 invalid_federated_source` naming the
placeholder); a stored template's unquoted placeholder is substituted only for a plain-token value
next to no quote, `-`, `/`, `*`, `$` or letter, else that source's block is left out
with a WARN. Manifests carry `initSqlTrusted` and `setupSqlTrusted`.

**Read-only federated catalogs bind every principal (decision 24).** A federated source's `readOnly`
flag is enforced on the node, superusers and the system principal included: since the node never asks
the manager about a superuser's statement, the set travels in the signed handle (handle v3's required
`readOnly`, the pool's aliases folded with `Locale.ROOT`, sorted; `ReadOnlyCatalogs` /
`FederatedReadOnlyCatalogs`, disabled sources included, the system principal's handles carry `[]`).
qod-node refuses, in `post_bind` and `pre_optimize`, before the verdict cache and any decide call, a
plan that writes into such a catalog, creates, drops or alters there, or ATTACHes or DETACHes it
(`ReadOnlyRefusal`: `QoD: catalog <alias> is read-only`, not retryable). A tenant-db the lookup cannot
find fails the statement rather than caching "none". A federated source write (create, update,
delete) applies on the next statement: the serving replica drops the read-only and attached-catalog
caches of the tenant-db's pools (`FederatedSourceCaches.sourcesChanged`) and sends `qod_topology`,
whose `restore()` drops every peer's (`PoolSupervisor.onRestore`); the 60 s per-pool TTL stays only as a
backstop. Typed sources (`iceberg_rest`, `postgres`, `mysql`) keep the engine-level `READ_ONLY`
ATTACH option too, which binds at node spawn only. Residuals under "Residuals" below.

**Authorization metrics (decision 26).** Per replica: `qod_authz_decisions_total{tenant, kind=plan|screen,
outcome=allow|refuse|unavailable, mode=qod|opa}` and the distribution
`qod_authz_decide_duration_ms{kind}` (milliseconds, buckets 0.5 to 5000; Prometheus exports its
`_bucket`, `_count`, `_sum` and `_max`), both recorded by `DecideRoute` (`AuthzInstruments`); dry runs,
and calls refused by authentication or as malformed, are not decisions. The bounded caches report
Micrometer's Caffeine meters (`cache_size`, `cache_gets_total{result=hit|miss}`,
`cache_evictions_total`, ...) under `cache` = `qod_minted_handles`, `qod_issued_handles`,
`qod_dry_run_results`, `qod_effective_sets` and `qod_opa_decisions` (the last only while
`QOD_OPA_CACHE_TTL_SEC` > 0), plus `qod_cache_capacity{cache}`, their configured bound
(`CacheMetrics`); these are per replica, so sum them across replicas. Each node answers
`system.main.qod_authz_stats()` (one row of BIGINT columns since node start: `plan_cache_hits`,
`plan_cache_misses`, `decide_calls`, `decide_unavailable`, `decide_refused`, `local_refusals`,
`screen_calls`, `verdict_cache_size`, `verdict_cache_evictions`, `credential_sets_size`; system
principal only, a superuser gets `QoD: function qod_authz_stats is superuser-only` and a tenant user
the parse screen's `QoD: table function qod_authz_stats is not available to non-superusers`). Every
replica's health probe reads it after the canary (`NodeAuthzStats`; a failed read keeps the node
healthy and logs at DEBUG), but only the HA leader exports `qod_node_authz_<counter>_total{tenant,
pool, node}` (the seven counters plus `verdict_cache_evictions`, from deltas, a lower value counting
as a node restart) and the gauges `qod_node_authz_verdict_cache_size` /
`qod_node_authz_credential_sets_size` (`MetricsBindings`, `isLeader`; a single manager always
leads), on Prometheus scrapes only, after a node's first healthy probe tick. `screen_calls` includes
the manager's own statements (preludes, the probe's reads). `QOD_AUTHZ_HANDLE_CONTEXTS_MAX`
(`quack-on-demand.nodeAuthz.handleContextsMax`, default 200000, boot refuses below 1000) bounds the
session handle contexts and nonces a replica keeps, each for 360 s (`MintedHandles`,
`IssuedHandles`): about `QOD_AUTHZ_HANDLE_CONTEXTS_MAX / 360` tenant statements per second per
replica (about 555 at the default); past it statements fail with `QoD: authorization unavailable,
retry: authorization context expired; retry` (retryable), signalled by `cache_evictions_total{cache="qod_minted_handles"}`. A rising
`qod_node_authz_verdict_cache_evictions_total` means `QOD_AUTHZ_CACHE_ENTRIES` is too small.

**Open sessions follow the policy version.** A FlightSQL or native front door session resolves its
effective set again on its first statement after its tenant's policy version (or this replica's
policy generation) moved (`SessionGrants`): a revoke reaches it on its next statement, a re-grant
restores it without a reconnect, and a user disabled, deleted or without the pool is refused there
(a control-plane outage during that check is a retryable `UNAVAILABLE`). On the native door a refused
statement inside a client transaction ends that transaction ("transaction aborted: <reason>; ROLLBACK
to continue"), and only a lone ROLLBACK is admitted, under no grants. A native client transaction
whose pinned node leaves the pool is fenced the same way (`FlightSqlRouter.PinLostAnswer`).

**Accepted over-refusals** (owner rulings, CONTRACT Addendum 4 items 13-21, 24, 30-32a: changing one
needs the owner; never loosen a rule to fix one). For non-superusers: an unclassified name (a typo
included) reads "is not classified"; a superuser-made macro, or a catalog macro shadowing a built-in
name (inside a built-in macro's body too), is refused until classified or called as `system.main`; a
plain view or stored expression (generated column, default, CHECK, trigger) that calls a user macro, an
unclassified or (outside a secure view) superuser-class function, the `histogram(<table>, <column>)`
table macro, or a `query()` that is not a literal query, or views nested past 16, makes its view or
table unreadable or unwritable; secure views follow the definition and session rules above; DESCRIBE
`key` / table_info `pk` are NULL; an OPA tenant's DESCRIBE and SHOW TABLES are empty and SUMMARIZE is
refused; `*.*.*` never covers a federated catalog (grant it by its alias). It does cover DuckDB's
`memory` catalog on the memory database's own pool (never on another pool, where `memory` is
instance-wide) and a custom `catalogAlias` (CONTRACT item 34's gap closed with the gateway screens;
pinned by `AuthzEndToEndCases` "a memory database" and "a database with a custom catalog alias"), so a
`qod serve` user holding the default `qod_all_tables` reads a `.duckdb` file attached under its stem
(`sales`) and the loose-file views in `memory`.

**Residuals** (known, not holes to report): foldable constant arguments are evaluated during bind
before a refusal (no value reaches the client); `pg_sleep` / `sleep_ms` are allowed, in bind-time
slots too; SQL stored by others (view bodies, defaults) runs its bind-time slots as the reader; an
optimizer extension loaded after qod-node is not re-described; statements an extension runs on its own
untagged connections (DuckLake metadata, Iceberg commits) are admitted as node-internal; under the
FlightSQL prepare probe a computed location and a SUMMARIZE run twice, and a fresh FlightSQL query
costs two decisions (the GetFlightInfo schema probe is a plan of its own). Read-only federated catalogs
bind superusers only through what the node describes (decision 24): a superuser can still write
through a raw passthrough (`postgres_execute`, `postgres_query`, `mysql_*`, whose SQL argument the
node does not read), a maintenance function taking a catalog name, an ATTACH of the same source under
another alias, or CHECKPOINT / VACUUM; each is superuser-only, so no other principal reaches them. A
superuser's `SET GLOBAL` of a parser setting can make the policy rewriters read a policy holder's
statement differently from the node (decision 27's screens stay until part 2). A principal holding
create on a schema may create a plain view over a DuckLake metadata catalog (`__ducklake_metadata_*`):
nothing is read at creation, and every non-superuser read through it is refused ("... is DuckLake
catalog metadata, which only superusers reach"), because a plain view runs with its reader's rights
(spec decision 4; owner ruling C16). In an OPA dry run the plan never executes, but what happens while
binding still does (the parse screen's checks, a user's S3 credential fetch). A restore that cannot
take every permit of the versions lock within 5 s (`PoolSupervisor.RestoreLockWaitMs`) is skipped with
a WARN and owed to the next local mutation, NOTIFY or snapshot refresh; a slow `createTenantDb`
(database creation and DuckLake init run inside the mutation) skips restores that way, and the owed
restore's latency lands on the next request thread.

Which binary: `QodNodeBinary` (`ondemand/runtime/`) resolves it once at boot for every manager that
starts nodes on its host (runtime local, or fleet with `QOD_FLEET_EPHEMERAL=local`) and refuses to
boot without one. `QOD_NODE_BIN` wins (a local build; the Windows launchers' download), else the
binary the jar bundles for its platform, installed as `<root>/<sha12>/bin/qod-node` beside a
`<root>/<sha12>/lib` link to the libduckdb the manager loaded (a vendored build's only rpath is
`../lib`). Either way it must answer `--build-info` with this build's node protocol
(`QOD_NODE_PROTOCOL` in native/qod-node/CMakeLists.txt = `QodNodeBinary.Protocol`) and DuckDB
release; `--build-info` also checks the libduckdb it loaded. `LocalQuackBackend` hands it to every
spawn as `QOD_NODE_BIN`. Where no manager starts the node, the same protocol check runs elsewhere:
`qod fleet join` refuses (exit 2) a qod-node whose marker names another protocol than its
`qod_node.NODE_PROTOCOL`, and the node image build fails on one other than its `ARG
QOD_NODE_PROTOCOL` (both pinned to `QOD_NODE_PROTOCOL`).

Vendoring follows libquackwire: `native/qod-node/binaries/<platform>/qod-node` for linux-x86_64,
linux-aarch64, osx-x86_64 and osx-aarch64, with `.sha256` companions and a `VERSION` stamp,
refreshed by `scripts/release/refresh-qod-node-binaries.sh` (the host's cmake build plus the
artifacts of a green `qod-node` CI run of HEAD; `FETCH_CI=0` for the host alone), which also
refreshes the copies the qod wheel bundles for `qod fleet join` (`cli/src/qod_cli/qod-node/`, gated
by `cli/tests/test_qod_node_bundle.py`). `verify_qod_node_binaries` gates releases and snapshots.
Windows' static `qod-node.exe` is too large to bundle: release.yml publishes
`qod-node-windows-<arch>.exe` with each release (qod-node.yml refreshes the snapshot's), and
`qod start` / `qod serve` / `run-jar.ps1` download the one of the jar's release. The Kubernetes node
image ships the vendored binary and libduckdb in `/opt/qod-node`, and no DuckDB CLI. Build and test
locally: `cmake -S native/qod-node -B native/qod-node/build -DQOD_NODE_TESTS=ON
-DDUCKDB_HOME=<.duckdb/2.0.0, with include/>`, then `DUCKDB_CLI=<release duckdb> ctest --test-dir
native/qod-node/build`; forked Scala tests use that build (build.sbt sets `QOD_NODE_BIN`).

### State storage (Postgres only)

The control plane lives in a dedicated Postgres database (`qod` by default) holding normalized `qodstate_*` tables managed by Liquibase: `qodstate_tenant`, `qodstate_tenant_db`, `qodstate_pool`, `qodstate_node` for the registry; `qodstate_user`, `qodstate_role`, `qodstate_role_permission`, `qodstate_group`, `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`, `qodstate_pool_permission` for the RBAC graph; `qodstate_federated_source`, `qodstate_federated_secret` for federation. Each managed tenant-db (e.g. `tpch_tpch1`) is a **separate** Postgres database next to it, holding the DuckLake `__ducklake_*` catalog. The `qodstate_*` prefix keeps control-plane tables from colliding with DuckLake's `__ducklake_*` namespace inside any shared database.

**Built-in RBAC objects.** Every tenant carries four protected built-ins (`BuiltinRbac`): roles `qod_no_tables` / `qod_all_tables` (`*.*.* ALL`), groups `qod_no_pools` / `qod_all_pools` (tenant-wide pool grant, `pool_id NULL`), flagged `builtin` (Liquibase `0045`, which also renamed `qodstate_user.role` to `kind`). Seeded by `createTenantWithBuiltins`, the manifest importer, and the idempotent boot `BuiltinRbacBackfill`: it runs on every replica before `PoolSupervisor` loads its caches, one transaction per tenant with per-tenant failure isolation (a failing tenant is logged and skipped, never fatal to boot), renames a NON-built-in row already holding a built-in name to the first free `<name>_renamed` / `<name>_renamed_N`, and folds a pristine legacy `admin` role (exactly one `* * * ALL` permission, no column/row policy) into `qod_all_tables`, moving its user and group edges. Deleting or editing a built-in answers `409 builtin_protected` from the supervisor choke points; the `qod_` prefix is reserved (case-insensitive, `400 reserved_name`), and manifest export refuses (`400 reserved_name`) a user-made `qod_` row rather than emit an unreplayable manifest (export omits the built-ins themselves). Tenant-user creation (REST, CLI `--role` / `--group`, MCP, SQL `CREATE USER ... ROLES ... GROUPS ...`) requires `UserMemberships`: omitted lists default to the permissive built-ins (`qod_all_tables` + `qod_all_pools`, i.e. ALL on every table of every pool), empty lists are refused (`400 roles_required` / `groups_required`), unknown names `400 unknown_role` / `unknown_group`; superusers (sending either list is a 400), SCIM and manifest import are exempt. New tenants no longer get a legacy `admin` role. The seeded boot admin's account kind is `quack-on-demand.admin.kind` / `QOD_ADMIN_KIND` (formerly `QOD_ADMIN_ROLE`).

The legacy `file` mode (single JSON blob) was dropped 2026-06-12 along with the `stateStorage` and `statePath` config keys -- `PostgresControlPlaneStore` is always wired. Connections come from a HikariCP pool (size 20 on the control-plane store, 10 on `UserStore`); `close()` on the trait is called from the manager's shutdown hook.

### Managed object storage (QoD-provisioned data paths)

A DuckLake database can be created with `managedStorage: true` (REST `database/create`, CLI `qod database create --managed-storage`, admin UI storage mode "Managed (QoD-provisioned)") instead of a caller-supplied `dataPath`/`objectStore`. The manager then resolves `dataPath = s3://<bucket>/<tenant>_<dbname>-<id8>/` (`ManagedPrefix` in `ondemand/storage/`, `id8` = first 8 chars of the tenant-db surrogate id, so recreating a deleted name lands on a fresh empty prefix) and fills the database's `objectStore` map from the `quack-on-demand.managedObjectStore` config block (`QOD_MANAGED_STORE_ENABLED / _ENDPOINT / _REGION / _BUCKET / _ACCESS_KEY_ID / _SECRET_ACCESS_KEY / _URL_STYLE / _RETAIN_DAYS / _PURGE_SWEEP_SEC`, disabled by default), so every downstream mechanism (node `CREATE SECRET`, `SecretKeys` redaction, spawn env, manifest export) applies unchanged. `managedStorage` is exclusive with `dataPath`/`objectStore`, requires `kind=ducklake`, and 400s naming the env when the block is off; `database/update` has no `managedStorage` field, so there is no BYO-to-managed migration.

Every managed create writes a tombstone row in `qodstate_managed_prefix` (Liquibase `0027`); `database/delete` stamps `deleted_at` + `purge_eligible_at` (`+retainDays`, or now with `purgeManagedData: true` / `--purge-managed-data`). `ManagedStoreWiring` (in `boot/`) sweeps due rows every `purgeSweepSec` (60s floor), HA-leader-gated inside `IO.defer`, listing and batch-deleting objects in bounded batches per prefix per tick and stamping `purged_at` when a listing comes back empty; the retained window doubles as the undrop window. The root bucket is created if missing by a boot probe that only ever WARNs. **The managed bucket must have versioning OFF**: on a versioned bucket deletes write delete markers, listings go empty, and the worker stamps a prefix purged while non-current versions keep billing.

### Encryption at rest (create-time, per tenant-db)

`TenantDb.encrypted` (Liquibase `0039`) turns on at-rest encryption for one database. It is
**create-time only in both directions, and so is the key**: neither engine can encrypt, decrypt or
re-key in place, so `database/update` refuses a changed *or emptied* `encryptionKey`, and
`ManifestImporter` refuses any change of the flag itself (the only path in the system that could
flip it, since the update DTO has no such field).

- `kind=ducklake`: `ENCRYPTED` rides every ATTACH. DuckLake mints a key per Parquet file into
  `ducklake_data_file.encryption_key`; QoD holds no key material. Repeating `ENCRYPTED` on a
  non-creating attach is legal, and an unencrypted catalog records `encrypted='false'` explicitly.
- `kind=duckdb-file`: `ENCRYPTION_KEY` from `metastore.encryptionKey`, minted by `EncryptionKeyGen`
  when the caller supplies none. In `TenantDb.SecretKeys`, so it never round-trips through any
  response.
- `kind=memory`: refused, nothing is at rest.

The catalog-creating ATTACH is [DuckLakeInitializer.scala](src/main/scala/ai/starlake/quack/ondemand/DuckLakeInitializer.scala),
and it is the site that decides a DuckLake catalog's encryption **permanently**. `guardEncryption`
compares `ducklake_metadata.encrypted` against the row and throws a typed
`EncryptionMismatchException` (blocking, like `DataPathMismatchException`) rather than letting
DuckLake's raw error repeat once per spawn forever. Both metadata keys are read in one statement:
the guard runs before the advisory lock, so a two-statement read could observe a concurrent
initializer mid-write and block a pool until manager restart.

**`INSTALL httpfs; LOAD httpfs;` is forced whenever encryption is on**, regardless of dataPath
scheme. Encryption needs OpenSSL, which httpfs provides; under mbedtls DuckDB 1.4.1+ silently
REFUSES WRITES to an encrypted database. `STORAGE_SQL` only loads httpfs for remote paths, so a
local encrypted file would otherwise come up read-only with no error.

Asymmetry worth knowing: DuckLake errors when attaching an unencrypted catalog WITH `ENCRYPTED`,
but attaching an encrypted catalog WITHOUT the flag silently succeeds, so `guardEncryption` is the
only thing catching an `encrypted=false` row on an encrypted catalog.

Surfaces: REST `database/create`, `qod database create --encrypted [--encryption-key]`, the MCP
admin tool, manifest round-trip, admin UI. `QOD_REQUIRE_ENCRYPTION` (`quack-on-demand.requireEncryption`,
default false) refuses an unencrypted create on **both** the REST and manifest-import paths. Branches
inherit `parent.encrypted`; `BranchCloner` copies `ducklake_metadata` wholesale so the clone is
already correct. An exported manifest redacts `encryptionKey`, so it cannot recreate an encrypted
`duckdb-file` database: deliberate, the key must not leave the control plane.

**Trust boundary:** encryption at rest moves the boundary to the control plane. Whoever can read the
control-plane Postgres can decrypt the data, for both kinds, and no design at this layer changes
that. Note an exported manifest still carries `pgPassword` verbatim, which for an encrypted DuckLake
database opens the catalog holding every per-file key. Design: docs/superpowers/specs/2026-09-21-encryption-at-rest-design.md.

### Branches (Epic 1, writable zero-copy clones)

A branch of a DuckLake tenant-db is a separate DuckLake catalog: `BranchCloner`
(`ondemand/branch/`) copies every `ducklake_*` table into a new Postgres database
under one REPEATABLE READ transaction, rewrites data/delete file paths to the
parent's absolute location and points `data_path` at a **sibling** prefix
`<parent>__br_<id8>/` (never nested: the parent's orphan sweep would delete it).
The clone is registered as a `qodstate_tenant_db` row with `branch_of` set and
metastore key `catalogAlias` = the parent's alias, so the branch's own pool
(`__br_<id8>`, one Dual node) attaches it under the parent's DuckDB name and
grants, policies and user SQL apply unchanged (`TenantDb.catalogAlias` is the one
helper every alias-derivation site goes through; the spawn scripts honour
`catalogAlias`). Rows live in `qodstate_branch` / `qodstate_branch_merge`
(Liquibase `0037`). `BranchService` owns the lifecycle: create -> propose ->
merge (fast-forward only, approver != proposer, one `BEGIN ... COMMIT` batch on an
ephemeral `__merge` node with the branch attached as `qod_branch`, located
afterwards by its unique commit message, tagged `merge-<branch>-<id8>`, then torn
down; `MergeFence` holds that commit to the validated main snapshot through a
trigger on `ducklake_snapshot_changes` in the parent catalog DB, schema
`qod_merge_fence`, because DuckLake's own conflict check misses main writes that
land before the merge's first read or into a table the merge drops) / discard / TTL expiry (`BranchWiring` sweep, leader-gated). Change sets
come from `BranchChanges` (pure, over `ducklake_snapshot_changes` verbs; flush
artifacts are not touches). `PinnedSetResolver` pins every live branch's fork
snapshot; the maintenance scheduler skips branch rows; `deleteTenantDb` refuses a
parent with live branches. Targeting: FlightSQL header `branch=<name>` (authorized
against the named parent pool), MCP `branch` argument, `qod sql --branch`.
`TokenRestriction.branchOnly` (PAT `--branch-only`, column `branch_only`) refuses
WRITE/DDL on a non-branch pool in `routedExecutor`. Merge is REST/CLI only (no
MCP tool, `McpCoverageSpec` exclusion). See
docs/superpowers/specs/2026-09-20-branching-v1-design.md.

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
`qod serve` provisioning, DuckLake init, revoked-jti purge). Pool mutations serialize across replicas via
per-pool advisory locks (`PoolLocker`; a single non-HA manager serializes them in-process
through `PoolLocker.inProcess()`, so pool mutations are per-pool serialized on every runtime); caches propagate via LISTEN/NOTIFY on
`qod_topology` / `qod_rbac` / `qod_revocation` with a periodic snapshot-refresh
fallback (a federated source write sends `qod_topology` too, and a tenant's SSO settings rebuild its
`TenantOidcRegistry` authenticator on every replica whose tenant mirror changed). A `restore()` waits
up to 5 s for this replica's own running mutations (the versions lock, `PoolSupervisor.RestoreLockWaitMs`)
and is otherwise skipped with a WARN and run later, never published unlocked. JWT revocations persist in `qodstate_revoked_jti`. Every replica signs
sessions with the same secret without any configuration: an unset
`QOD_SESSION_JWT_SECRET` resolves to the one row in `qodstate_boot_secret` (see
"Session model"), so HA no longer refuses an empty one. HA with the local
backend is refused at config load. See
docs/superpowers/specs/2026-07-02-manager-ha-zero-downtime-design.md.

### `qod serve` boot provisioning (`ServeProvisionHook`)

`qod serve` has the manager provision its tenant, database and pool, so it holds no credential
for it: it writes `serve-provision.json` (owner-only: the database body can carry object-store
credentials) in its data dir, `{"tenant","database","pool"}`, the bodies of
`POST /api/tenant/create`, `/api/database/create` and `/api/pool/create`, deletes any earlier
outcome, and passes the spec's absolute path as `QOD_SERVE_PROVISION` (read like
`QOD_BOOTSTRAP_YAML`: env, else a JVM system property; unset or blank does nothing).
`ServeProvisionHook` (`ondemand/bootstrap/`) runs in the leader duties (`LeaderDuties`, `boot/`)
after `restore()` and before the DuckLake init and `reconcile()`, so a new database is
initialized and its pool spawned in the same boot; a single manager runs it before the REST
server binds, an HA follower only once promoted. It calls the REST create functions
(`createTenantAs` / `createTenantDbAs` / `createPoolAs`, every check and quota included) as
`SessionScope.Superuser`, audited with the actor `system`. Add-only: an object that exists and
matches is left alone; one that differs is refused and never updated (a database whose kind,
`dataPath` or `initSql` differ, or a pool name already serving another database, each reason
naming the way out: `qod serve <target> --name <other>` / `--pool <other>`), and nothing is ever
deleted. It stops at the first refusal and writes `serve-provision.outcome.json` beside the
spec, atomically: `{"status":"ok","created":[...]}` or
`{"status":"failed","step":"spec|tenant|database|pool","reason":"..."}` (`spec`: the file could
not be read or decoded). A failure never stops the boot, and no log line, audit row or outcome carries a
body or a secret. The launcher waits for `/ready`, then for the outcome within
`--ready-timeout`, deletes the spec once it has the outcome, and prints its banner or the failed
step with its hand-run command (`finish by hand:`). Attach mode (a manager already running)
still provisions over REST, with the caller's own credential. `DemoBootstrapHook`
(`QOD_BOOTSTRAP_YAML`) is unrelated: its manifest import replaces rows wholesale and runs only on
an empty store.

### Edge config + catalog ([quack.edge.config](src/main/scala/ai/starlake/quack/edge/config/), [quack.edge.catalog](src/main/scala/ai/starlake/quack/edge/catalog/))

The auth/ACL/session config types and the DuckLake catalog resolver live under [quack.edge.config](src/main/scala/ai/starlake/quack/edge/config/) and [quack.edge.catalog](src/main/scala/ai/starlake/quack/edge/catalog/). The pureconfig `ProductHint`s in [Main.scala](src/main/scala/ai/starlake/quack/Main.scala) override the `derives ConfigReader` defaults to use camelCase (matching `application.conf`) instead of pureconfig's kebab-case.

### Grant matching (the node decider)

Table grants are matched in one place, the node decision pipeline: `AuthzDecider` (`edge/authz/`, behind `DecideRoute`) judges the objects of the plan qod-node bound against the session's cached **EffectiveSet** (closure of roles · groups · `qodstate_role_permission` rows reachable through them) through `GrantMatch` (`edge/sql/`). Superusers (`qodstate_user.tenant IS NULL`) and the system principal are allowed by the node locally and never reach it; an OPA tenant's objects go to its OPA instead (below). There is no gateway validator and no switch to turn the check off: `PostgresAclValidator`, `OpaValidator`, `TenantRoutingValidator` and `StatementValidator.allowAll` were deleted in Phase D (decision 29).

The `EffectiveSet` is cached in `PoolSupervisor` (`qod_effective_sets`, at most 10000 entries) with a 60s TTL keyed by `(userId, jwtRoles.hashCode, jwtGroups.hashCode)`; every RBAC mutator (`createRole`, `addUserRole`, `grantPoolPermission`, etc.) calls `invalidateEffectiveCache()` so a freshly-granted role takes effect on the next statement without waiting on the TTL: a new handshake resolves it, and an open session resolves it again on its first statement after the policy version moved ("Open sessions follow the policy version" above).

**DML / DDL grants are enforced per-table.** The node describes every object a plan reads or writes with its access; `GrantMatch.verbCovers` maps the canonical grant verbs (`RO`/`RW`/`DDL`/`ALL`, see `RolePermission.ValidVerbs`) onto the collapsed `Read | Write | Ddl`. A role permission of `verb=ALL` covers anything; `verb=RW` covers `Read` and `Write` but not `Ddl`; `verb=RO` covers only `Read`. A wildcard catalog grant covers only the session's own catalogs (`GrantMatch.catalogMatch`), never a sibling tenant's. Trigger DDL is superuser-only (tenant admins included).

### OPA authorization (per tenant)

`qodstate_tenant.acl_mode` (Liquibase `0044`, null = `QOD_ACL_MODE`, default `qod`) hands a tenant's data-access decisions to its own OPA server: pool access at handshake (gate 4 through `OpaPoolAccess`) and table and file access per statement, asked by the node decider (`AuthzDecider`, `OpaAuthorizer.decide`) on the objects DuckDB resolved, after the rules QoD always enforces: each access carries `kind`, `columns` and `via` (the outermost secure view it is read through), and `locations` lists the files already admitted, with the scope that admitted each (`OpaInput.decide`, `client.edge` `node`, never `statement.text`). The gateway sends OPA nothing per statement. QoD grant tables are ignored for that tenant; memberships are still sent as identity. Any mode other than exactly `qod` is OPA.
Fail closed: only a literal `result.allow == true` allows; `row_filter` / `masks` are rejected; an outage, timeout or >1 MiB body is `UNAVAILABLE`, never a permission error, and the client sees only `"OPA unreachable or returned an error (see the manager log)"` (`OpaAuthorizer.UnavailableMessage`, also the dry run's text for a non-superuser): the tenant admin picks the OPA URL, so the transport cause (host, port, status) goes to the manager's WARN log only, and `OpaClient.jdkPost` masks the bearer token in it. Superusers never reach OPA. Decisions cache for `QOD_OPA_CACHE_TTL_SEC` keyed on every input field; Rego changes never kill live sessions.
Config block `quack-flightsql.opa` (`QOD_ACL_MODE`, `QOD_OPA_URL`, `QOD_OPA_TIMEOUT_MS`, `QOD_OPA_CACHE_TTL_SEC`); per tenant via `POST /api/tenant/setAcl` / `qod tenant set-acl` (token write-only, redacted in manifests). Dry run `qod tenant opa-test` (`POST /api/tenant/opaTest`, `OpaDryRun`): the handshake form asks the `connect` rule from the manager; the statement form (`--sql`, decision 25) runs one statement through `FlightSqlRouter.dryRun` on a node of the pool, as the named tenant user, under a `DecidePurpose.DryRun` context (`"purpose":"dryRun"` in the signed context, so it never shares a cached verdict), records the decider's outcome and the exact OPA input by nonce (`DryRunResults`, 5 min) and refuses the plan, so it never executes; it reports that input. It never wakes a pool: no routable node is `409 pool_not_running`. Other answers: `400 opa_mode_required` (tenant on QoD grants), `400 single_statement_required`, `400 refused_before_opa` (the gateway's or the node's refusal, or a superuser / system principal: a dry run runs only for a tenant user), `400 nothing_to_authorize`, `503 authz_unavailable`. Known limitation: filtered metadata still narrows listings by QoD grants, so an OPA tenant's users get empty DESCRIBE and SHOW TABLES answers and a refused SUMMARIZE (the gateway's rewrites read those listings). The starter policy in examples/opa covers `columns`, `via` and `locations`.
Starter policy and operator guide: [examples/opa/](examples/opa/README.md). Design: docs/superpowers/specs/2026-10-01-opa-authorization-design.md.

### REST authZ - tenant scope on every RBAC endpoint

Every handler in `api/{User,Role,Group,Membership,PoolPermission}Handlers.scala` calls `TenantScopeCheck.{reject,rejectForResource,rejectForUser}` before mutating. Id-only endpoints (e.g. `POST /api/role/delete {id}`) resolve the owning tenant via 5 supervisor lookup helpers (`tenantForUser`, `tenantForRole`, `tenantForGroup`, `tenantForRolePermission`, `tenantForPoolPermission`) before applying the gate. Tenant-A admin sessions and PATs get `403 tenant_forbidden` on any tenant-B resource; superusers bypass. Every caller is a session or a PAT (the static API key is gone), and the scope resolver is total (`SessionScope.resolver`): no credential, or one that stopped resolving, is `NoAccess`, never trusted. Missing ids 404 (no cross-tenant existence leak via differential error codes). See `RbacTenantScopeSpec` for the 14 cases that pin the contract.

### Tenant sign-in

A management login takes its authority only from `qodstate_user` rows. In db mode a profile the DB authenticator produced is its own grant (`AuthHandlers.mintSessionFor` tests `authMethod == DatabaseAuthenticator.AuthMethod`): its `(tenant, role)` is the row whose password was verified (for a tenant password login an admin row, or a regular user's row as a profile-only session). Every other login only proves who the user is, and its grants are read from the rows of the realm it was verified in (`LoginRealm`, `ondemand/auth/`): the tenant SSO callback, every system login under `identitySource=oidc` (the DB authenticator's too), and a password login answered by the manager-wide Keycloak or Azure password grant, under either `identitySource`. The basic chain tries that grant after the DB authenticator, so with one enabled a db tenant's password form also accepts the IdP's passwords (any failed DB check falls through to it). The IdP's role claim and any tenant it carries are discarded; `UserStore.grantsForIdentity(realm, ...)` is asked with `LoginRealm.Tenant(t)` for a tenant login (the SSO callback, or a db tenant's password form) and `LoginRealm.System` without a tenant. The email fallback of that lookup is decided by the entry point (`mintSessionFor`'s `lookupEmail`, which has no default): a password login hands none, so it signs in as the QoD user of that tenant with the same user name as the IdP account, with that row's rights only, and the lookup never retries with the email, which is why the IdP's user names must be assigned by an admin, never self-chosen (the seeded superusers `admin@localhost.local` and `admin` are names an IdP realm must not hand out; this holds on the data plane too, where a `superuser=true` FlightSQL or front door handshake binds the superuser row a token's `preferred_username` names); the SSO callback hands the token's email only when the IdP marks it verified (`email_verified` true), else an address the account holder typed could name the superuser's row. The IdP authenticators (`OidcBearerAuthenticator`, `JwtBearerAuthenticator`, `ResourceOwnerPasswordAuthenticator`) name the user by the token's `preferred_username`, else its `email` claim, else its subject, and refuse a token naming none of them (the password grant never falls back to the typed name, a bearer is never named `unknown`), so a token without `preferred_username` is named by an address its holder may have typed. One shared rule, `IdentityNaming.userNameMayBindRow` (`edge/auth/`), lets a name that came from the email claim (the profile's claims carry no `preferred_username` and `email` equals the user name) bind a row only when `email_verified` is `"true"`, on every plane: `mintSessionFor` then binds nothing and answers `403 not_provisioned` with no lookup; `EdgeHandshake` (FlightSQL and the native Quack front door, so the password grant on a basic handshake, a manager-wide bearer and a tenant's bearer from `TenantOidcRegistry` alike) refuses the identity as `Unauthenticated` with `IdentityNaming.UnverifiedEmailRefusal` before the supervisor gate, so no row is looked up and no JWT role or group is resolved (it refuses a blank user name, `IdentityNaming.namesNoUser`, the same way, which `TenantSelector` would otherwise replace with the token's `sub`). The database authenticator's profile carries no `email` claim and is never refused. `ResourceOwnerPasswordAuthenticator` carries `preferred_username` and `email_verified` into the profile's claims for that rule (`sub` and its other claims are as before: the FlightSQL data plane and OPA inputs read them). So a token with an `email` claim, no `preferred_username` and no true `email_verified` is refused on every plane (a token with neither claim is named by `sub` and unaffected); the likeliest breaks are an operator's external JWT signer that names users by `email` (fix: mint `preferred_username` or `email_verified: true`) and Azure v1 access tokens, since Azure never sends `email_verified` (fix: v2 tokens, which carry `preferred_username`). The tenant realm matches rows of tenant `t` only (its email fallback too), never a superuser row or another tenant's, so such a session is never `superuser`; its minted profile names tenant `t` whatever `authMethod` it carries (an SSO profile's is the issuer host, which a tenant admin picks), so a PAT minted from it is owned by the tenant's row; and an identity with rows only in other tenants or only a superuser row gets `403 not_provisioned` (the SSO callback lands on `/ui/?error=not_provisioned`). An IdP `admin` claim makes nobody admin or superuser. Superusers sign in through the bare `/ui/` or a password login without a tenant (`LoginRealm.System`, unchanged). The SSO service is wired whatever `identitySource` says, so tenant SSO works under the default `db` too (0.10.1 answered `/api/auth/oidc/start` with `404 auth_mode_disabled` unless `identitySource=oidc`); `identitySource` only picks the bare `/ui/` login mode and how that login's grants resolve, and under `db` that login stays password-only even when `QOD_MGMT_OIDC_*` are set. A tenant's SSO also needs the tenant itself to sign in through its IdP: `oidcStart` and `oidcCallback` serve a tenant scope only when `ManagementAuthModeResolver.modeFor(Some(t))` is `Oidc` (else `400 oidc_not_configured`, or a redirect to `/ui/?error=oidc_not_configured` with nothing minted), so a tenant switched to `db` without clearing its auth config keeps no SSO. On a db-mode manager the tenant SSO redirect URI is built from `QOD_MGMT_PUBLIC_BASE_URL` (`http://localhost:<port>` when unset, and the boot warning for that is gated on `identitySource=oidc`, at WARN). REST and MCP statements follow the realm rule: `ExecCaller.superuserAdmissible` (a session's `scope.superuser`; for a PAT, whether its owner has no tenant) reaches `AuthzRequest.superuserAdmissible`, which has no default (nor does `PoolSupervisor.authorizeHandshake`'s), so a caller that is not a superuser never binds a superuser row; the flag grants nothing, and `ExecCaller.system` stays the only route to the synthetic superuser set. A tenant whose console sign-in is OIDC (`ManagementAuthModeResolver`: a non-`db` provider with `issuerUrl`, `clientId` and a secret) takes no password on `POST /api/auth/login`: `400 sso_required` (its message links `/ui/?tenant=<tenant id>`), before any password check, so no lockout counter moves; an unknown tenant, or one whose sign-in is incomplete, still authenticates first (`401 invalid_credentials`, `400 tenant_oidc_misconfigured`), and the FlightSQL and Quack data planes still take its users' passwords. `GET /api/auth/mode` trims and lowercases the tenant it is given, as tenant ids are lowercase slugs.

Tenant admins configure their own tenant's sign-in (`tenant/setAuth`, MCP `set_tenant_auth`, `qod tenant set-auth`, the UI's Auth Provider page); `TenantSignInWrite` holds every write rule. The client secret is a write-only value (`qodstate_tenant.auth_client_secret`, Liquibase `0048`; `clientSecret` on the request, `clientSecretSet` on responses, `***REDACTED***` in manifests, redacted in `Tenant.toString`; the CLI never takes it on argv: `--client-secret-stdin`, `--client-secret-prompt`, `--clear-client-secret`) bound to the issuer it was saved with, the `setTenantAcl` rule for the OPA token: a save that changes `issuerUrl` drops the stored value unless it supplies a new one, and drops a carried `clientSecretRef` unless a superuser makes it (a superuser may pair a reference with the new issuer in one write; a tenant admin's carried reference is dropped). A `clientSecretRef` resolves from the manager's own environment, so only a superuser may add or change one (anyone else gets `403 superuser_required` and nothing is written), as for a federation `externalRef`. One secret source per tenant: a supplied value replaces a reference, a reference a superuser adds or changes replaces the value, and a write that does both is `400 secret_conflict`; a whitespace-only value is `400 invalid_client_secret`, and a `clientSecret` key inside `authConfig` is refused (`400 invalid_auth_config`, on `tenant/create` too). The admin UI warns before a save that would drop the secret or leave an OIDC sign-in incomplete (`issuerUrl`, `clientId` and a secret source are all needed; until then only a superuser, through the system login, can open the tenant's console).

`0048` parked every reference stored before it under `clientSecretRefUnconfirmed`, which nothing reads (that tenant's sign-in is misconfigured: `GET /api/auth/mode` answers `tenant_oidc_misconfigured`, the SSO start `oidc_not_configured`, and its password login `400 tenant_oidc_misconfigured` once the password verifies), so after the upgrade every tenant whose sign-in used a reference stays misconfigured until a superuser restores it (its admins can store the secret themselves only with a PAT or a session they already hold). Under an HA rolling upgrade a 0.10.1 replica still serving writes stores a tenant admin's reference without the superuser rule and the migration, which already ran, does not park it: finish the rollout before tenant admins edit sign-in settings, or review the references afterwards. Boot logs one ERROR line per parked tenant (`ParkedSecretRefs`): the tenant, the reference, a request to confirm that its `issuerUrl` is the tenant's own identity provider, and the restore command. The reference is shown only when it starts with a known prefix (`env:`, `aws-sm:`, `gcp-sm:`, `azure-kv:`, `vault:`) and goes into the command only when it is made of `[A-Za-z0-9_:./=-]` (else the command shows `clientSecretRef=...`, and the full value is in `qod --json tenant list`); any other parked value prints as `<not a secret reference, not shown>` and the line advises setting a secret source (the tenant's own client secret, or a reference) instead of restoring it. `TenantSignInWrite.settleParkedKey` is the one rule for the key, shared with the manifest import: a save keeps it only when it carries it unchanged, keeps `issuerUrl` and holds no secret source; any other save drops it (a secret value or a reference, a moved issuer, a key the request adds, changes or leaves out), whatever the client (REST, MCP, CLI, UI, manifest), which ends the error line while the sign-in stays misconfigured until a secret source is set. Manifests export `clientSecret` as `***REDACTED***`; import keeps the stored value only for the marker with an unchanged issuer (`TenantSignInWrite.issuerOf`), refuses the marker otherwise, a whitespace-only value (an empty string means no secret), a `clientSecret` key in `authConfig` and a secret together with a reference, and an absent `clientSecret` clears a stored one; a `clientSecretRef` imports as written, so an export taken before 0.10.2 re-activates its references without the parked review. `TenantOidcRegistry` (FlightSQL bearer validation) reads no secret: a tenant's public fields alone (`google`: `clientId`; `keycloak`: `baseUrl`, `realm`, `clientId`; `azure`: `tenantId`, `clientId`; `aws`: `region`, `userPoolId`, `clientId`) give it its own authenticator, so a tenant admin can turn it on, and its tokens must carry that client id as audience. Design: docs/superpowers/specs/2026-10-24-tenant-signin-isolation-design.md.

### SCIM 2.0 provisioning (`/api/scim/v2/{tenant}`)

`ScimEndpoints` / `ScimHandlers` (in `ondemand/api/`) serve RFC 7643/7644 Users and Groups
over the RBAC store for IdP connectors (Okta, Entra): CRUD, `eq` filters
(userName/externalId/displayName), pagination, PATCH (active toggle incl. Entra's string
booleans, member add/remove/replace), plus ServiceProviderConfig/ResourceTypes/Schemas.
Bodies are raw strings (SCIM clients send `application/scim+json`, and errors must be the
SCIM envelope, not `ErrorResponse`). Auth: a tenant-admin PAT (a session also works) as `Authorization: Bearer`,
accepted by `apiKeyGuard` for the `/api/scim/` prefix ONLY, alongside the usual
header/cookie; a tenant-scoped PAT reaches only its own tenant's base URL. Mapping: userName=username (immutable), active=enabled, primary
email=email (EmailPolicy applies), externalId=new `external_id` columns (Liquibase
`0036`, written only by SCIM). The superuser realm is invisible to SCIM; passwordless
creates get a random password (users sign in via tenant OIDC). SCIM routes are excluded
from CLI parity (`cli/tests/test_rest_parity.py`) as machine-to-machine. See `ScimSpec`.

### Session model - JWT in HttpOnly cookie

`SessionTokenStore` is a stateless JWT signer/verifier (HS256, secret from `manager.auth.management.sessionJwtSecret`). `mintWithScope` returns the compact JWT; `get` parses + verifies + reconstructs the `Session` from claims. Revocation: bounded in-process jti denylist (lost on restart; the documented trade-off for going stateless).

On the wire:
- `AuthHandlers.login` returns the JWT both in the JSON body (`LoginResponse.token`, for the CLI and scripts, which send it as `X-API-Key`) AND as `Set-Cookie: qod_session=<jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api`.
- `ManagerServer.apiKeyGuard` admits a session or a live PAT and nothing else, on the `X-API-Key` header (CLI, scripts; the name is historical) OR the `qod_session` cookie (browser), plus `Authorization: Bearer` on `/api/scim/**`. `/mcp`, outside the guard, admits only a live PAT as `Authorization: Bearer` (sessions are refused there).
- UI does not stash a token in localStorage; fetch's same-origin credentials policy auto-attaches the cookie.

Cookie attributes are configurable: `sessionCookieSecure` (env `QOD_SESSION_COOKIE_SECURE`, default `true`), `sessionCookiePath` (env `QOD_SESSION_COOKIE_PATH`, default `/api` -- override behind a path-rewriting reverse proxy). The JWT exp is absolute (8h from mint by default, env `QOD_SESSION_IDLE_TTL_SEC`); there's no sliding-window refresh. The secret is resolved by `BootPreflight.withSessionSecret` (over `SessionSecretStore`, `ondemand/state/`) right after the Liquibase apply and before anything reads it: a non-empty (after trim) `QOD_SESSION_JWT_SECRET` wins, is used as given and is never written to the database; otherwise the first boot generates one and stores it in `qodstate_boot_secret` (Liquibase `0049`, row `session_jwt`; Postgres generates the value, 96 hex digits from three `gen_random_uuid()`, at least as strong as `SessionTokenStore.randomSecret()`, so it never travels as a bind parameter that statement logging would record), and every later boot and every HA replica reads it (`INSERT ... ON CONFLICT (name) DO NOTHING` then `SELECT`, so replicas booting together converge on one value). Sessions and password-reset links therefore survive restarts. While a value is configured the stored row is left untouched, so removing the env falls back to it. Boot prints one line naming the source (`from QOD_SESSION_JWT_SECRET (quack-on-demand.auth.management.sessionJwtSecret)`, `generated and stored in the control plane`, `stored in the control plane`), never the value (println, not the logger, so the default ERROR level can't swallow it); `/api/config` shows the configured key only as `(set)` / `(unset)`, and neither manifest export nor telemetry reads the table. Every consumer takes the resolved `mgrCfg`: `SessionTokenStore`, `ResetTokenStore`, the management `OidcStateCodec` and `SqlTokenOidcService`. Rotation: change `QOD_SESSION_JWT_SECRET`, or, for the stored one, `DELETE FROM qodstate_boot_secret WHERE name = 'session_jwt'` and restart every replica; either way every session and outstanding reset link dies. Set a value only when another service verifies the sessions: Starflow (`qod start --with-starflow` generates one into the profile, Compose's `SESSION_JWT_SECRET` feeds both `QOD_SESSION_JWT_SECRET` and the `JWT_SECRET_KEY` bearer provider). **Trust boundary (read is not write):** a writer of the control-plane database (the Postgres admin, or anyone holding the control-plane login, nodes that inherit it included) gains nothing new, since it can already rewrite `qodstate_user`. A read-only holder (a `pg_dump` backup, a snapshot, a read replica, a SELECT-only role) now also gets the management plane: on an install with no `QOD_SESSION_JWT_SECRET` it can forge a superuser session. That comes on top of the credentials the same database already stores, among them: each tenant-db's catalog `pgPassword` and duckdb-file `encryptionKey` (`qodstate_tenant_db.metastore_params`), its object-store keys (`object_store_params`), inline federation secrets (`qodstate_federated_secret.value`), the node-key secret (`qodstate_node_authz`), the manager's bearer to each node's `/quack` endpoint (`qodstate_node.token`), tenant OIDC client secrets (`qodstate_tenant.auth_client_secret`), tenant OPA tokens (`qodstate_tenant.opa_token`) and each user's own S3 keys (`qodstate_user_s3_credential.secret_access_key`, `session_token`). Protect control-plane backups and replicas like the secret itself; when they are less trusted than the manager, pin `QOD_SESSION_JWT_SECRET` from an external secret store: nothing is persisted then, and backups never carry it.

Forced password change: `qodstate_user.must_change_password` (set via `mustChangePassword`
on `user/create` / `user/update`) makes `DatabaseAuthenticator` refuse the password on BOTH
the REST login (401 `password_change_required`) and the FlightSQL handshake, until the user
swaps it through the public pre-session `POST /api/auth/change-password` (current password
is the credential; new must differ; clears the flag). The auth queries MUST project
`(password_hash, kind, enabled, must_change_password)` - a shorter custom
`QOD_AUTH_DB_SYSTEM_QUERY` / `QOD_AUTH_DB_TENANT_QUERY` fails boot and every login.

**Regular-user profile sessions.** The manager UI is not admin-exclusive: a tenant-scoped
`kind=user` principal can log in through their tenant (the blank/system login and OIDC SSO
stay admin-only). `AuthHandlers.mintSessionFor` accepts a DB-mode `Tenant(t)` login from any
principal holding a grant on `t`, not just an admin, and mints a non-admin, profile-only
session (`LoginResponse.admin = false`). `ManagerServer.apiKeyGuard` demotes such sessions (and
a non-admin's PATs) to a fixed allowlist (`isProfileApi`: `/api/auth/whoami`,
`/api/auth/logout`, `/api/profile/usage`, `/api/profile/statements`, `/api/profile/pools`, the
caller's own `/api/auth/pat/{create,list,revoke,delete}`, their own S3 credentials
`/api/profile/s3/{list,add,remove}`, and `/api/auth/sso/ticket` when the
Starlake integration is on) and answers `403 admin_required` on everything else; the UI mounts
only the `/profile` route (own password change; own usage/statements; own S3 credentials) for them.
`GET /api/profile/pools` (`qod profile pools`) answers
`{"pools":[{"tenant":...,"tenantDb":...,"pool":...,"status":...}]}` (the field names and
`status` values of `/api/pool/list`, `ready` or `disabled`, nothing else) for the pools of the
caller's tenants (every tenant for a superuser) that it may connect to, each decided by the
FlightSQL handshake's own `PoolSupervisor.authorizeHandshakeDetailed` for the request
`ExecCaller.authzRequest` builds, and narrowed by a PAT's `databases` / `pools`; a denied
pool is omitted, and an unavailable decision (the tenant's OPA unreachable) answers
`503 authz_unavailable`. Starflow lists pools through it with the user's own session (a
starlake-api change), so it holds no QoD credential of its own.

### Account lockout, SMTP, and self-service password reset

`qodstate_user` carries an optional `email` column (Liquibase `0029-user-email`). A row with an email can use the public self-service reset flow and is eligible for lockout; a row with a non-email username and no email cannot reset by email and is never subject to lockout.

When the username itself is in email format, `email` is auto-set to it and immutable - `user/create` / `user/update`, manifest import, AND boot seeding (`BootPreflight.seedAdminUsers` derives `email = username` for an email-format admin, matching the 0031 backfill so fresh and upgraded installs agree) all follow the rule; a conflicting value is rejected with `400 invalid_email`; pre-existing rows are backfilled by Liquibase `0031-user-email-from-username`. This means the default seeded admin `admin@localhost.local` carries `email = admin@localhost.local`, so it IS lockable when lockout is on and reset-eligible. A locked or forgotten superuser password is recovered with `qod admin reset-password` (writes the new hash straight into the control-plane database and clears `failed_attempts` / `locked_at`, for every configured admin name or the one `--username` names; it re-enables a disabled superuser only when `--username` names it, which also revives that account's personal access tokens, while a reset without `--username` leaves a disabled account disabled and says so; works with the manager down against an external control plane; it never starts Postgres, so for the `qod serve` embedded one that Postgres must be running and a stopped one is refused), or by another superuser through `qod user update --password`; a deleted superuser row comes back at the next boot with `QOD_ADMIN_PASSWORD` set (`BootPreflight.seedAdminUsers` creates any configured admin whose row is missing). A restart does NOT reset it: the admin is seeded only when its row is missing, and `QOD_ADMIN_PASSWORD` is used on that first boot only (`qod start` / `qod serve` prompt for it then and never store it). With the default `QOD_ADMIN_USERNAME` (`admin@localhost.local,admin`) both superusers share that first-boot password: rotate each one (`qod auth change-password --username <name>` per name); `qod admin reset-password` resets every configured admin name by default (`--username` targets one). Because `admin@localhost.local` is not a routable mailbox, its self-service email reset does not deliver by default - set `QOD_ADMIN_USERNAME` to a real address for email self-recovery.

**SMTP** (`quack-on-demand.smtp`, env `QOD_SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_FROM` / `_STARTTLS`): when `host` is unset (default), Main wires `LogMailSender` (logs recipient/subject, never actually sends); setting `QOD_SMTP_HOST` switches to `SmtpMailSender` (Jakarta Mail). `QOD_PUBLIC_BASE_URL` (`quack-on-demand.publicBaseUrl`, default `""`) is the externally-visible origin used to build the mailed reset link (`$publicBaseUrl/ui/reset-password?token=...`); left empty the link is host-relative and Main logs a boot warning.

**Self-service reset**: `POST /api/auth/forgot-password {tenant?, username}` is a public endpoint (no credential) that always returns 200 regardless of whether the account exists or has an email - anti-enumeration is load-bearing, and the lookup + send are decoupled so response timing doesn't leak account existence either. `POST /api/auth/reset-password {token, newPassword}` (also public) redeems the token. The token (`ResetTokenStore`) is a stateless, single-use, 1-hour HS256 JWT signed with the same secret as `QOD_SESSION_JWT_SECRET`; single-use is enforced not by a redemption ledger but by embedding a fingerprint of the password hash at mint time, so any password change invalidates every outstanding link. `qod auth forgot-password` / `qod auth reset-password` are the CLI equivalents; `qod user create/update --email` sets the column.

**Account lockout** (`quack-on-demand.auth.lockout`, env `QOD_AUTH_LOCKOUT_ENABLED` default `false`, `QOD_AUTH_LOCKOUT_MAX_FAILURES` default `10`) is opt-in. Enabling it requires a working SMTP relay - `BootPreflight.checkLockoutSmtp` refuses to start otherwise, naming `QOD_SMTP_HOST` in the error - because a locked-out user with no mail path would have no way back in. Lockout only ever applies to rows with a non-null `email`; a row with a non-email username and no email can never be locked no matter how many failed attempts, whereas the seeded superuser `admin@localhost.local` carries `email = username` and so is lockable. `DatabaseAuthenticator` checks the lock before bcrypt and, on a locked row, returns `AuthFailure.AccountLocked` -> REST `401 {"error":"account_locked","message":"account locked - use forgot password to reset your credentials"}` (same failure wins the FlightSQL handshake too). Failures accumulate atomically per row and reset to zero on any successful login. Recovery is either the self-service reset above or an admin password reset (`user/update` with a new `password`, or the CLI equivalent) - both unconditionally clear `failed_attempts` and `locked_at` as part of the password write.

### K8s backend - per-pod and per-pool Secrets

**No secret ever sits in a node process's environment or command line, on any backend** (a node user with SQL reads `getenv()` and `/proc/<pid>/{environ,cmdline}` of qod-node and of its wrapper script). `NodeSecretFiles` (`ondemand/runtime/`) owns the key list (`pgPassword`, `encryptionKey`, `extraSetupSql`, `objectStoreSql`, `QOD_NODE_TOKEN`, `QOD_NODE_AUTHZ_KEY`, `tenantDbInitSql`, `tenantDbInitSqlTrusted`, `tenantPoolInitSql`, `tenantPoolInitSqlTrusted`, `QOD_INIT_NONCE`, the `QOD_S3_*` / `QOD_AZURE_*` / `QOD_GCS_*` credentials), mirrored by `SECRET_KEYS` in both spawn scripts and `_SECRET_KEYS` in `fleet_join.py` (`NodeSecretFilesSpec` pins all four). A metastore supplies only its own two (`pgPassword`, `encryptionKey`, any spelling); every other file comes from the manager. The spawn script reads them from one file per key in `$QOD_SECRETS_DIR` (all but the token and the node key, which qod-node reads itself, and the nonce, which both read) and ignores (with a WARN) any that still arrives as an env var; the token is no longer argv `$2`. Local (`LocalQuackBackend`, sh and ps1) and fleet (`qod fleet join`) write a fresh owner-only directory (0700 / files 0600, owner-only ACL on Windows) with `QOD_SECRETS_DIR_EPHEMERAL=1`, which the script deletes right after reading; the launcher also deletes it on process exit (local) or once the node is up / failed / stopped (fleet), and sweeps stale ones at boot. `LocalQuackBackend` also drops the manager's own inherited credentials (`NodeSecretFiles.isInheritedSecret`: `*PASSWORD*`, `*SECRET*`, `*TOKEN*`, `*API_KEY*`...; `AWS_*` / `AZURE_*` / `GOOGLE_*` kept for DuckDB's credential chain). The psql CREATE DATABASE fallback in the script uses a private `PGPASSFILE`, never `PGPASSWORD`. Users' own S3 keys never reach a node as a file or an env var either: qod-node fetches them per connection from the handle's replica and holds them only as that connection's secrets (decision 23).

`KubernetesQuackBackend` creates one Pod + one Service per node, plus up to four Secrets, all delivered as files of ONE read-only projected volume `qod-node-secrets` (mode 0400, which kubelet turns into 0440 group `fsGroup`, mounted at `/var/run/qod-secrets` = the pod's `QOD_SECRETS_DIR`), never as env vars or `secretKeyRef`s:
- **Per-pod token Secret** `qod-token-${nodeId}`. Holds the manager-minted bearer (`QOD_NODE_TOKEN`) the manager presents on calls to that pod's `/quack` endpoint, the node's key (`authzKey`, projected as the file `QOD_NODE_AUTHZ_KEY`), derived from the node-key secret and the node id so every start writes the same one, and a fresh init nonce on every write (`initNonce`, projected as `QOD_INIT_NONCE`). The pod's plain env carries its id as `QOD_NODE_ID` (not a secret; a metastore entry of that name is dropped). `discoverExisting` reads the Secret on manager restart to repopulate the in-memory token cache, so adopted pods don't 401 after a redeploy.
- **Per-pool federation Secret** `qod-fedsql-${tenant}-${tenantDb}-${pool}` (tenantDb hyphenized for RFC-1123). Holds the resolved federation SQL (`spec.extraSetupSql`) and the tenant-db's and the pool's initSql, each under the file name its trust gives it and only when non-empty; a pod projects only the keys of its own spec, so a pod started for a trusted text never reads a later untrusted one as trusted. GC'd when the last pod of the pool stops; rotation = update the Secret once, restart pods.
- **Per-pod object-store Secret** `qod-store-${nodeId}` when `spec.objectStoreSql` is non-empty.
- **Per-pool node-env Secret** `qod-nodeenv-${tenant}-${tenantDb}-${pool}` (all three segments hyphenized for RFC-1123: a slug may carry `_`). Holds the sensitive metastore values, `TenantDb.NodeSecretEnvKeys` = `pgPassword` + `encryptionKey`, plus the manager's own object-store credentials (`NodeSecretFiles.CloudCredKeys`, formerly forwarded as plain env). The Secret keeps a key's spelling (`PgPassword`); the volume item maps it to the canonical file name. The volume items and the Secret's contents derive from ONE filtered map. GC'd with the last pod of the pool.

All referenced Secrets must exist BEFORE pod create (kubelet will not start a pod whose volume references a missing Secret), so `start(spec)` runs `ensureTokenSecret`, `ensureFederationSecret`, `ensureNodeEnvSecret` and `ensureObjectStoreSecret` first, then creates the pod. The node image entrypoint passes only the port. The mounted files stay readable inside the pod by a node user with unrestricted SQL (a superuser; tenant users' file reads are decided on the node), and nothing bounds what that credential reaches yet: a database without its own metastore credentials inherits `QOD_PG_USER` / `QOD_PG_PASSWORD`, the control plane's login, so one read opens the `qod` database and every tenant's catalog (and the Postgres server itself when that login is a superuser). The per-database least-privilege Postgres role (docs/superpowers/specs/2026-10-11-node-least-privilege-pg-role-design.md) would bound it, but is design only. Interim mitigation: tenant users cannot read the mounted files (the node refuses every file outside a tenant's authorized folders and the user's own S3 scopes to non-superusers, on every pool; per-pool lockdown and `QOD_NODE_LOCKDOWN` are gone, and a set `QOD_NODE_LOCKDOWN` refuses boot), and in the Helm chart `postgres.user` has no default (the chart `required`s it and documents a non-superuser `CREATEDB` role). The chart also `required`s `admin.password` or `admin.existingSecret` (nobody could log in otherwise: the manager has no static API key) and `fail`s on a non-empty `apiKey` value (an all-empty block left in an older values file passes).

**Upgrading:** pods created by an earlier manager keep their old env shape and are NOT migrated in place. Restart every node after upgrading, for example by scaling each pool down and back up.

### Fleet backend (bare servers, no Kubernetes)

`QOD_RUNTIME_TYPE=fleet` plus `QOD_FLEET_JOIN_TOKEN` and `QOD_INTERNAL_URL` (boot refuses without it: each replica's own plain `http://` address on the servers' private network, which every node calls for its verdicts). Linux/macOS servers join by running
`qod fleet join` (`cli/src/qod_cli/fleet_join.py`, a long-running process), which heartbeats `POST /api/fleet/heartbeat` (header
`X-Fleet-Token`, public at the guard only in fleet mode; the handler checks the token) and
runs the one node the reply assigns (its token and node key `authzKey` as secret files, its id as `QOD_NODE_ID`, a fresh `QOD_INIT_NONCE` minted per start, the tenant-db's and pool's initSql as `tenantDbInitSql` / `tenantPoolInitSql` files with their trust flags; the manager's own cpu/memory SETs ride `resourceSql`, which replaced `dbInitSql`) via the bundled spawn script, bound to `QOD_NODE_BIND`. Servers must run the manager's qod release: an older join process does not know the split assignment.
Two tables: `qodstate_fleet_server` (identity, assignment; locked by claims) and
`qodstate_fleet_heartbeat` (server-reported state and capacity; rewritten every interval, never
locked by claims). `FleetQuackBackend` (`ondemand/runtime/`) claims with one
`UPDATE ... FOR UPDATE OF s SKIP LOCKED` plus a memory-fit predicate and waits for the server to
report `running`. Liveness comes from the database clock (`silent_seconds`), never the JVM's, so
HA replicas agree. `NoFreeServer(reason)` is the one failure the supervisor tolerates: the slot
stays pending and reconcile fills it when a server joins (`MissingSlots`). Silent past
`heartbeatTimeoutSec` = unroutable but kept; past `reassignAfterSec` = dead, respawned elsewhere
through `claimReplacing` (release the dead holder and claim the replacement in ONE store
transaction, rolled back when no server qualifies).
With no free server the dead server keeps its assignment and its node row, so if it returns
first its join process still runs the node at the same epoch and reconcile adopts it with no restart; a
drained or removed holder's node goes pending instead. A partial or cancelled spawn rolls back
the nodes it started (`spawnAll`, `guaranteeCase`). The server holding an assignment is the
truth for a live node's address and token: reconcile adopts through `QuackBackend.located` and
rewrites a node row that still names the previous server (a manager that died between the claim
and the node row write).
Join approval (Liquibase `0041`): a new server row starts unapproved and the claim query skips
it (`AND s.approved`), so a pending server never receives an assignment or its credentials. The
heartbeat handler resolves the client address from the TCP peer (`X-Forwarded-For` only when the
peer is in `QOD_FLEET_TRUSTED_PROXIES`, walked from the right; unknown never matches) and
auto-approves when it is in `QOD_FLEET_AUTO_APPROVE` (default `0.0.0.0/0,::/0`, boot WARNs while
open; empty = none). Pending servers are re-judged every heartbeat, approved ones never; an
accepted re-address (drained server) resets approval and is judged again. Approval is bound to the
source it was granted from (`approved_source`, Liquibase `0042`: the auto-approving heartbeat's, or
the latest source on an admin approve): a heartbeat from another source is refused
`409 source_change_refused` with nothing written, unless that source is itself in the list (rebind)
or the server is drained and unassigned (approval reset, judged again). An approved row with no
binding (the 0042 upgrade leaves every earlier server unbound; not backfilled, since the advertised
host is self-reported and differs from the source behind a proxy or NAT) binds only to a known
source inside the list; from anywhere else it is refused `409 approval_unbound` with nothing
written, unless drained and unassigned (approval reset, judged again): drain it, approve it
once it shows as pending, then undrain it.
Admin approve refuses `409 source_unknown` while the server's latest heartbeat has no known source
(`ApproveResult.SourceUnknown`), so an approval never starts unbound. The drain window is closed: no re-address while the row still holds
an assignment (drain flips unschedulable, then releases), and the heartbeat never returns an
assignment to an unapproved row or to a source the row is not bound to. `qod fleet approve` /
`POST /api/fleet/server/approve` approves by hand (recording the approving user in `approved_by`); `remove` forgets approval and also accepts a
live pending server. Existing rows were backfilled `approved_by = 'upgrade'`. Design:
docs/superpowers/specs/2026-09-26-fleet-join-approval-design.md.
A known name reporting a new address is refused unless drained and unassigned (shared-token takeover guard).
`QOD_FLEET_EPHEMERAL=local` runs maintenance and merge nodes on the manager host instead of a
fleet server. Manager-to-node is plain HTTP: fleet mode needs a private network.
Containers: `docker/fleet-worker` is the server image (`qod fleet join` + DuckDB; requires
`QOD_FLEET_NAME` and `QOD_FLEET_ADVERTISE_HOST`, publishes the node port under the same number),
`host_capacity` reports cgroup limits, and `QOD_FLEET_NODE_PG_HOST` / `_PORT` rewrite the managed
Postgres address in assignments only (`NodePgAddress`). `docker-compose.fleet.yml` layers fleet mode
with two workers on a separate network onto the bundled stack. Design:
docs/superpowers/specs/2026-10-05-fleet-docker-design.md.
Fleet backend design: docs/superpowers/specs/2026-09-25-fleet-backend-design.md.

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

Every scalar in [src/main/resources/application.conf](src/main/resources/application.conf) accepts a `QOD_*` env-var override (or `PROXY_*` for FlightSQL edge keys). Prefer env vars over editing the conf - the conf is bundled into the jar at build time. Defaults: `:20900` REST, `:31338` FlightSQL (TLS on), Postgres `localhost:5432` user `postgres` / `azizam`, admin users `admin@localhost.local` and `admin` with NO built-in password (chosen at the first `qod start` / `qod serve`, or `QOD_ADMIN_PASSWORD` on first boot; `scripts/run-jar.sh` exports `admin` for dev only). `quack-on-demand.defaultMetastore.dataPath` ships with a developer machine's absolute path - override it before running outside that environment.

`QOD_SESSION_JWT_SECRET` needs no pinning: when it is unset, the first boot generates the session secret and stores it in the control-plane database, and every restart and HA replica reads it (see "Session model"; no secret is ever printed). Pin one only when another service must verify the sessions (Starflow). The static API key is gone: a non-empty `QOD_API_KEY`, or `quack-on-demand.apiKey` in an operator's config file, refuses boot with a message naming the replacements (a PAT from `qod auth pat create`, or a `qod login` session), and an empty value passes. Right after it, boot refuses an empty FlightSQL auth chain (`BootPreflight.checkEdgeAuthAvailable`; `AuthenticationService.configuresProvider` mirrors the chains the service builds): with no basic and no bearer provider the FlightSQL edge (which has no enable switch) and the Quack front door would admit no client, since `EdgeHandshake` refuses every connection when the chain is empty. So `QOD_AUTH_DB_ENABLED=false` boots only with a Keycloak or Azure password backend or a bearer provider (`JWT_SECRET_KEY` / `JWT_PUBLIC_KEY_PATH`, `QOD_AUTH_GOOGLE_ENABLED`, `QOD_AUTH_AWS_ENABLED`); system-scope OIDC gates only the admin UI and REST, adds no FlightSQL provider, and is refused at boot as the only provider. Then boot refuses when nothing could ever create a session (`BootPreflight.checkLoginAvailable`, over `AuthenticationService.configuresPasswordProvider`): no password backend (`QOD_AUTH_DB_ENABLED=false` and no Keycloak or Azure password backend) and no system-scope OIDC sign-in, which counts only with `QOD_MGMT_IDENTITY_SOURCE=oidc` and both `QOD_MGMT_OIDC_ISSUER_URL` and `QOD_MGMT_OIDC_CLIENT_ID` set, so a bearer provider alone needs system-scope OIDC too. All three refusals read the configuration alone and run at the top of `bootManager`, before it opens any socket (with `QOD_PG_EMBEDDED` the embedded Postgres is already up, and stopped again on the refusal). Automation uses PATs, and `qod admin reset-password` recovers a superuser (`--username <name>` also re-enables a disabled one).

`federation.secretStore` defaults to `dispatch` (route per secret by externalRef prefix). The `aws-sm` / `gcp-sm` / `azure-kv` / `vault` single-backend modes are refused at config load -- their resolvers are stubs that `NotImplementedError` at node spawn. Under `dispatch` mode the stubs stay wired but the runtime error spells out the supported alternatives (`postgres` inline value, `env:` prefix).

## Operator runbook

[plugins/qod/skills/quack-on-demand/SKILL.md](plugins/qod/skills/quack-on-demand/SKILL.md) is the operator runbook: `qod` CLI recipes for tenant/pool/ACL CRUD, typical failure modes. When the user asks operational questions ("how do I create a pool", "why is auth failing"), prefer the patterns there over reinventing them. It is deliberately checkout-free (it must work for users who only installed the CLI or the Claude Code plugin) - never add repo paths, scripts, or sbt invocations to it. The wheel bundles a copy at `cli/src/qod_cli/skills/quack-on-demand/SKILL.md` (served by `qod skill install`); `cli/tests/test_skill_freshness.py` fails until the copy is refreshed after any skill edit.

## Things to avoid

- **Don't disable JVM forking** in build.sbt - see "JVM forking" above.
- **Classify every function and setting at a DuckDB pin bump** (and whenever qod-node registers a
  function or a node starts loading another extension). Nodes refuse an unclassified one to
  non-superusers ("function <name> is not classified"). Run `DUCKDB_CLI=<the node's release duckdb>
  scripts/dev/gen-qod-node-classification.sh` (it rebuilds `native/qod-node/functions.json`,
  `settings.json` and `macros.json` from the host's qod-node build and the release CLI with every
  extension a node loads, then the generated `src/main/resources/authz/function-classes.tsv`,
  `setting-classes.tsv`, `native/qod-node/file-functions.txt`, `admitted-table-functions.txt` and
  `native/qod-node/src/function_classes.inc`), review every `review:` default it prints, change the rule tables in
  `scripts/dev/classify-qod-node.py` where a default is wrong (a setting is `session` only when changing
  it for one session cannot reach credentials, files, I/O endpoints, resources or other sessions), then
  rebuild qod-node. `FunctionClassesSpec` (its fixture case needs `python3`), `FunctionClassesHeaderSpec`
  and `SettingClassesSpec` fail until the tables and the JSON agree; `InitSettingsAllowlistSpec` fails
  when a setting of the tenant initSql allowlist changes class. The classifier makes a built-in
  scalar macro whose body reaches a catalog listing superuser-class (`pg_get_viewdef`: with the
  gateway screens gone it would print every view's SQL); that rule reads scalar macros only, so
  review any new built-in table macro that reads a listing by hand (DuckDB 2.0.0 has none).
- **Add every new RBAC table to the policy-version trigger.** `qod_bump_policy_version()` (Liquibase
  `0050`, extended by `0051` and `0052`) bumps `qodstate_tenant.policy_version` on writes to the
  tables it is attached to, and `qod_bump_every_policy_version()` (`0054`) bumps every tenant on a
  change to the data-path deny set (attach a new source of data folders to that one); a table that changes what a principal may do but is not attached (a new
  grant or policy table, a new tenant ACL column, a new source of data folders) never invalidates
  cached verdicts, so nodes keep allowing for up to the verdict's 60 s. Attach it in the same changeset
  that creates it.
- **A node S3 secret is scoped to a data path and names every key.** DuckDB signs a URL with the
  secret of the longest matching scope, and httpfs fills any key a secret lacks from the session's
  `s3_*` settings. An unscoped or bucket-wide node secret would sign users' URLs with the node's keys
  (qod-node's screen then refuses every URL it shadows), and a missing key lets a session supply it.
  The spawn scripts' `qod_data_s3` / `qod_db_store`, `ObjectStoreSecret` and qod-node's `qod_uc_*`
  name `KEY_ID`, `SECRET`, `SESSION_TOKEN`, `REGION`, `ENDPOINT`, `URL_STYLE`, `USE_SSL`,
  `KMS_KEY_ID`, `URL_COMPATIBILITY_MODE` and `REQUESTER_PAYS` (re-check that list against httpfs's
  `S3AuthResolver::ReadConfig` at every DuckDB pin bump).
- **Don't invoke `scripts/spawn-quack-node.sh` directly** - it's spawned by `LocalQuackBackend` with the right `<port> <manager-marker>` argv, its secrets (node token and node key included) as files in a private `QOD_SECRETS_DIR`, and the env contract (`QOD_NODE_ID` included: it refuses to run without one). Manual invocation will leak ports and confuse the supervisor.
- **Refresh the bundled copies whenever you touch `scripts/` or the operator skill.** The CLI wheel ships its own copies at `cli/src/qod_cli/scripts/spawn-quack-node.{sh,ps1}` and `cli/src/qod_cli/skills/quack-on-demand/SKILL.md`. Nothing generates them: a plain `cp` is the mechanism, and `cli/tests/test_demo.py` / `test_skill_freshness.py` are the only enforcement. Editing a canonical script without refreshing its copy fails those tests in a way that reads like an unrelated pre-existing failure.
- **Never put two statements on one line of SQL fed to the DuckDB CLI** (the demo seeder, the loaders). When a statement fails, the CLI skips the rest of that line, so a failing `INSTALL` would silently skip the `LOAD` after it. A single statement may span several lines. (qod-node splits a node's init SQL on DuckDB's own tokenizer, so this does not bite there.)
- **Don't edit the bundled `application.conf` for local tweaks** - set the `QOD_*` env var instead, or the change vanishes on the next `sbt assembly`.
- **Always tear the manager down with `scripts/stop-jar.sh`, never an IDE/JVM kill** (or a bare `kill` of the java PID). Each node's qod-node lives exactly as long as its `spawn-quack-node.sh` wrapper (it watches the wrapper's process), so an unclean manager exit leaves the wrappers, reparented to PID 1, and their nodes running and holding node ports `21900+`. The next manager then spawns onto an occupied port, its node never passes the `SELECT 1` health probe, and it shows `healthy=false` / `served=0`. Recovery: kill the orphans with `scripts/kill-quack-nodes.sh --port <that manager's REST port>` (`--all` is the explicit blunt reset that sweeps every node on the host, every manager's, and the only mode that matches the orphans of a released manager from before the per-manager marker, whose wrappers carry the node token where the marker now goes; `--unmarked` matches only wrappers whose argv ends at the port, which at HEAD means a `qod fleet join` node on this host), then `POST /api/node/restart` (or scale the pool) so the node re-binds a free port. `stop-jar.sh` does SIGTERM -> wait -> SIGKILL and reaps its own manager's children, so it never leaves its own orphans; other managers' nodes it deliberately leaves alone (it counts only port-only `--unmarked` wrappers in its note, so an earlier release's orphans go unreported). Stops are per-manager: the wrapper argv and node directory carry the manager's marker `<rest-port>-<boot id>`, and `stop-jar.sh` / `stop-jar.ps1` / `qod stop` / `kill-quack-nodes.sh` only touch the nodes of the manager on their `QOD_ON_DEMAND_PORT`, so stopping an isolated test stack never takes down the nodes of the manager on :20900. Ctrl-C on a foreground `run-jar.sh` (or `qod start`) is safe: both supervise the JVM in its own process group and turn the interrupt into that same teardown.
