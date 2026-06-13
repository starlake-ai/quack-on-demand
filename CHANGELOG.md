# Changelog

## 0.3.2-SNAPSHOT

### Security

- **RBAC tenant scope on every handler.** Every endpoint in `/api/{user,role,group,membership,pool/permission}/*` now checks the calling session's `manageableTenants` before mutating. Tenant-A admin sessions get `403 tenant_forbidden` on tenant-B resources; missing ids return `404` (no cross-tenant existence leak).
- **Statement-history cross-tenant leak fixed.** `GET /api/node/statements` filters the ring buffer by the session's manageable tenants. Allow-set covers both surrogate id and display-name forms so the filter survives the FlightSQL router recording in either shape.
- **JWT session cookies.** `SessionTokenStore` is now a stateless HS256 JWT signer/verifier; login also sets `qod_session=<jwt>` as `HttpOnly Secure SameSite=Lax`. Sessions survive manager restart when `QOD_SESSION_JWT_SECRET` is pinned, enabling horizontal scale-out. The UI drops the localStorage token path entirely — browsers use the cookie; CLI clients still use `X-API-Key`.
- **Cloud secret resolvers gated.** `secretStore = aws-sm | gcp-sm | azure-kv | vault` is refused at config load (resolvers are stubs). Under `dispatch` mode the stubs stay wired so postgres+env deployments work; the runtime error names the prefix and the supported alternatives. UI dropdown options for stub stores are disabled.
- **FederationBlobBuilder SQL-quote-safe.** Every resolved secret value and the expanded alias are doubled (`'` → `''`) before splicing into the operator template. Apostrophes in passwords no longer break the `ATTACH`.
- **DuckLake ATTACH escape hardened.** Postgres calls in `DuckLakeInitializer` switched to `PreparedStatement` binds; the DuckLake connstring uses a two-layer escape (`libpqValue` inside `duckdbLiteral`) so the bearer password can contain any character.
- **Full 128-bit ids.** Every surrogate id is now `<prefix>-<32 hex>` (`Names.newSurrogateId`) instead of `<prefix>-<8 hex>` (~77K-row collision). Legacy 8-char ids in existing rows continue to match.
- **K8s federation works.** Per-pool `qod-fedsql-${tenant}-${tenantDb}-${pool}` Secret carries `extraSetupSql`; pods reference it via `env.valueFrom.secretKeyRef`. `kubectl describe pod` never shows the credential; etcd doesn't either.
- **K8s node tokens persist across restart.** Per-pod `qod-token-${nodeId}` Secret carries the bearer; `discoverExisting` reads it back, so adopted pods don't 401 on manager restart.
- **REST stubs removed.** `POST /api/node/setRole` and `POST /api/node/restart` were stubs that lied about persisting state. Use `POST /api/pool/scale` or `POST /api/node/quarantine` instead.
- **K8s ServiceAccount can manage Secrets.** The chart's `Role` was granting `pods` + `services` but omitting `secrets`, so `KubernetesQuackBackend.ensureTokenSecret` (mandatory pre-pod step) failed with `403 forbidden` and the manager `CrashLoopBackOff`-ed on every fresh helm install.
- **`whoami` error codes differentiated.** A failed lookup used to surface as `{"error":"expired"}` for six different conditions (no token, malformed JWT, signature mismatch from a rotated secret, missing jti, exp past, jti revoked). New `SessionTokenStore.LookupResult` ADT maps each cause to a distinct response code (`no_session` / `invalid` / `expired` / `revoked`), so operators reading the network tab can tell "secret rotated on helm upgrade" apart from "session timed out".
- **Cookie `Secure` flag auto-derives from request scheme.** `sessionCookieSecure` defaults to `auto` (was `true`): the handler reads `X-Forwarded-Proto` per request — `https` → `Secure`, `http`/absent → not `Secure`. `run-jar.sh` on plaintext HTTP no longer drops the `Set-Cookie` response, while helm behind a TLS-terminating ingress keeps `Secure=true` automatically. `QOD_SESSION_COOKIE_SECURE=true|false` still forces either value for misconfigured proxies that strip the header.

### Performance

- **HikariCP on the control-plane store.** `PostgresControlPlaneStore` and `UserStore` use HikariCP (sizes 20 and 10). Handshake path went from 5 fresh TCP+TLS+auth handshakes per request to 5 borrow-from-idle.
- **EffectiveSet cache.** 60s TTL `ConcurrentHashMap` keyed by `(userId, jwtRolesHash, jwtGroupsHash)`; invalidated from every RBAC mutator + `restore()`. Collapses N FlightSQL handshakes for the same user into 1.
- **Manifest export/import.** Single `store.snapshot()` + name→entity index maps replaced N+1 list calls per tenant/user/role.
- **`unsafeRunSync` in `PoolSupervisor.createPool`** removed (proper `flatMap` composition).
- **K8s `waitReady`** switched from a `Thread.sleep(500)` busy-poll to fabric8's `waitUntilCondition` watch — ~120 fewer API-server calls per 60s pod startup.
- **`DuckLakeCatalogReader` evicted** on tenant-db delete: the per-tenant-db Hikari pool is closed promptly. Stale-credential foot-gun closed.
- **Sessions auto-expire.** Initial sliding-window idle TTL on `SessionTokenStore` (superseded by the stateless JWT exp).
- **FlightSQL Prepare cheaper + co-located with Execute.** ADBC `cur.execute()` was hitting the backend twice (Prepare ran the full SQL to read the result schema, Execute ran it again to stream rows), routed independently so the two halves could land on different nodes. Prepare now classifies the SQL through `PrepareStrategy`: DML/DDL skip the node entirely (also fixing a latent double-INSERT hazard), wrappable SELECTs send a `LIMIT-0` subquery probe whose schema feeds `dataset_schema` in milliseconds, and the rest fall back to today's path. The Execute then soft-pins to the node that served Prepare so DuckDB's per-process caches stay warm.
- **Prepare + Execute merged into one UI history row.** The `LIMIT-0` probe no longer records a separate row; the matching Execute carries the probe duration as `prepareDurationMs` and the UI renders it as subtext (`57 ms / prep 28 ms`) under the Execute duration.

### Demo, loaders, and bare-jar UX

- **`LOAD_TPC=N` split into `LOAD_TPCH=N` + `LOAD_TPCDS=N`.** Each benchmark is now opt-in at its own scale factor across `run-jar.sh`, `run-docker-compose.sh`, and the kind-based `run-local-stack-k8s.sh`. `LOAD_TPC=N` stays as a legacy shortcut for both (explicit per-bench vars override it). The Compose + k8s launchers also stop pre-creating Postgres databases that won't be seeded. Fixes a real k8s bug where the chart README already advertised `LOAD_TPCH=1` but the launcher script only honored `LOAD_TPC`, so users following the README got nothing seeded.
- **TPC-DS workload in the bundled load tester.** `loadtest.py` gains `--workload tpcds` (or `LT_WORKLOAD=tpcds`); the runner cycles 7 curated queries (Q3, Q7, Q19, Q42, Q52, Q55, Q98) covering per-group aggregation, multi-way joins, top-N, and a window function. Default schema auto-picks `tpcds1` to match `scripts/load-tpcds-dbgen.sh`. The existing TPC-H mix stays the default.
- **DuckDB CLI + `libduckdb` self-installed by `run-jar.sh`.** First boot fetches both into `$REPO_DIR/.duckdb/<version>/{bin,lib}` at the ABI libquackwire links against (pinned via `DUCKDB_VERSION`, relocatable via `DUCKDB_CACHE_DIR`), prepends them to `$PATH` and `LD_LIBRARY_PATH` / `DYLD_LIBRARY_PATH`, and skips re-downloads on subsequent runs. Mandatory by design: a system duckdb at the wrong ABI crashes the first node spawn with a confusing `UnsatisfiedLinkError`. Air-gapped operators pre-populate the cache directory and the fast path takes over.
- **Loader temp-spill fixed for SF >= 10.** `load-tpch-dbgen.sh` and `load-tpcds-dbgen.sh` now anchor `temp_directory` to an absolute `.tmp/duckdb-{tpch,tpcds}-load/` they pre-create, so DuckDB's spill-to-disk during `dbgen` / `dsdgen` actually lands somewhere writable. Previously the call aborted with `IO Error: Cannot open file ".tmp/duckdb_temp_storage_DEFAULT-1.tmp"` and every subsequent `CREATE TABLE ... AS SELECT FROM memory.main.<t>` failed because the source tables were never populated.
- **Release script attaches a GitHub Release.** `scripts/release.sh` now calls `gh release create v$version --generate-notes` against the tag sbt-release pushed, so the assembly jar lands on the GitHub Releases page alongside the Maven Central artifact.
- **Smoke test rewritten for the live demo bootstrap.** `test-api.sh` was creating a pool under tenant `tpch` (which never existed in the demo bootstrap) and missing the `tenantDb` field added when the pool key became `(tenant, tenantDb, pool)`. It now creates a `smoke` pool under `acme/acme_tpch` (cleaned up at the end), and tolerates 409 on tenant + pool creation so re-runs are safe.

### Cleanup

- **File-state mode dropped** (~250 LOC + ~80 LOC tests): `PostgresStateStore`, `StateStore`/`FileStateStore`, `StoredState`, `StoredPool`, `StoredTenant`. The `stateStorage` / `statePath` config knobs and all 5 `Main.scala` guards are gone. Postgres is the only control-plane store.
- **`ManifestIdentity` + `FederationImportSummary`** dropped (defined, never read).
- **`DbAdmin.dropDatabase` non-FORCE fallback** dropped (PG16 floor — always supported).

## 0.3.1

- Per-pool node placement (cohorts) — a pool can now be defined as a
  list of cohorts. Operators can express
  layouts such as "2 writers on nodes tagged `disktype=ssd` and 1 reader
  + 1 writer on nodes tagged `disktype=hdd`" against a single pool.
- JDK floor raised to 21.