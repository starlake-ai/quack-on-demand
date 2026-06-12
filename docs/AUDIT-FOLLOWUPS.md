# ondemand/ audit followups

Generated 2026-06-12 from a multi-pass audit of `src/main/scala/ai/starlake/quack/ondemand/` (70 files, ~8.5 K LOC). Each item is independently actionable. Priority key:

- **P0** — correctness / authZ; ship-blocker for any prod-facing deployment.
- **P1** — cheap wins (<1 day each), measurable user impact.
- **P2** — structural refactors (days to a week), reduce ongoing maintenance cost.
- **P3** — polish, naming, deferred decisions.

---

## P0 — security & correctness

- [x] **Cross-tenant RBAC writes.** ~~RBAC handlers do not enforce body-tenant scope.~~ Fixed 2026-06-12: `X-API-Key` plumbed into all RBAC endpoint signatures (`api/RbacEndpoints.scala`); two new helpers `TenantScopeCheck.rejectForResource` / `rejectForUser` resolve id-only endpoints to their owning tenant via 5 new `PoolSupervisor.tenantFor*` lookups + 2 new store methods (`getRolePermission`, `getPoolPermission`); every handler in `UserHandlers`/`RoleHandlers`/`GroupHandlers`/`MembershipHandlers`/`PoolPermissionHandlers` now gates before mutating. E2E coverage in `test/.../security/RbacTenantScopeSpec.scala`. Follow-up still wanted: make `TenantScopeCheck` a Tapir middleware so future handlers cannot forget it.

- [x] **Statement-history endpoint leaks SQL across tenants.** ~~`GET /api/node/statements` returns user/tenant/pool/sql for every caller's queries.~~ Fixed 2026-06-12: `X-API-Key` plumbed into the endpoint, supervisor injected into `StatementHistoryHandlers`, ring snapshot filtered by session `manageableTenants`. Allow-set carries both surrogate id AND display name per tenant so the filter stays agnostic to which form upstream chose to record (mirrors the FlightSQL handshake's accept-either convention). Coverage in `test/.../security/StatementHistoryScopeSpec.scala`.

- [x] **`NodeHandlers.setRole` silently no-ops.** ~~Endpoint is registered, parses input, returns 204 without persisting.~~ Removed 2026-06-12: per-user decision (option 1) — the endpoint, handler, DTO + codec, and ManagerServer binding all dropped. `PoolSupervisor.scale` is the right primitive for rebalancing a pool's role distribution.

- [ ] **`NodeHandlers.restartNode` does not restart.** Only stops the node (`api/NodeHandlers.scala:80-91`). Rename to `stopNode` or finish the restart loop.

- [x] **`LoginResponse.role` hardcodes `"admin"`** ~~while `WhoamiResponse.role` reflects the actual profile.~~ Fixed 2026-06-12 (folded into the setRole removal commit): dropped `role` from `LoginResponse` case class + hand-rolled codec + `AuthHandlers.login`; matching TS type + `AuthContext.login` updated to fetch role from `/whoami` after a successful login. `WhoamiResponse.role` remains the single source of truth for the descriptive label.

- [x] **`DuckLakeInitializer` interpolates `pgPassword` into a quoted SQL blob.** ~~Apostrophe in password breaks the ATTACH.~~ Fixed 2026-06-12: 3 Postgres calls switched to `PreparedStatement` binds (`pg_database WHERE datname=?`, `pg_advisory_lock(hashtext(?))`, `pg_advisory_unlock(hashtext(?))`); the DuckLake ATTACH now uses a two-layer escape (`libpqValue` for inner connstring + `duckdbLiteral` for the outer literal). Same helper now used for the `SET http_proxy` and `CREATE SECRET` literals. Coverage in `test/.../ondemand/DuckLakeInitializerEscapeSpec.scala`.

- [ ] **`FederationBlobBuilder.substitute` is not SQL-safe** against a hostile secret value (`federation/FederationBlobBuilder.scala:82`). Only quotes regex metachars (`Matcher.quoteReplacement`), not SQL quotes. Either escape SQL quotes or document the operator-trust model in-tree and in the manifest schema.

- [ ] **Manifest UUIDs truncated to 8 hex chars** (~10 sites in `manifest/ManifestImporter.scala`). Birthday collision at ~77K rows would surface as PK violations on import. Use full UUIDs.

---

## P0 — known limitations to surface or fix

- [ ] **K8s backend loses node tokens on manager restart.** `runtime/KubernetesQuackBackend.scala:17-19, 239` — `discoverExisting` returns adopted pods with empty token; every Flight call 401s until a full pool respawn. Persist tokens in a per-pod K8s Secret.

- [ ] **K8s backend silently drops `spec.extraSetupSql`** (TODO at `runtime/KubernetesQuackBackend.scala:88-91`). Federation does not work on K8s.

- [ ] **`SessionTokenStore` never expires tokens** (`api/SessionTokenStore.scala:17`). Manager restart is the only revocation path. Add Caffeine with idle TTL.

- [ ] **Cloud secret resolvers** (`Vault`, `Aws`, `Azure`, `Gcp`) are stubs raising `NotImplementedError`. Wired in `Main.scala:194-204`. Either implement, or gate the UI option as "not yet implemented" and refuse on config load.

---

## P1 — performance cheap wins (each <1 day)

- [ ] **Add HikariCP to `PostgresControlPlaneStore.withConn`** (`state/PostgresControlPlaneStore.scala:36-39`). Single biggest perf gain in the codebase. Fixes:
  - Handshake: 5 fresh JDBC connections per FlightSQL handshake (PoolSupervisor.scala:1121, 1167) → 15–25 ms local, 150–500 ms remote/TLS today.
  - REST: every list endpoint pays the same per-call connection cost; `listUsers` alone takes 4 conns.
  - Also wrap `state/UserStore.scala` the same way.

- [ ] **Fix `unsafeRunSync()` inside `IO.defer`** at `PoolSupervisor.scala:633`. Replace with `flatMap`. Blocks a cats-effect compute-pool thread under load.

- [ ] **Replace K8s `waitReady` busy-poll** at `runtime/KubernetesQuackBackend.scala:196-203` (`Thread.sleep(500)` inside `IO.blocking`) with `fabric8 pods().withName(...).waitUntilCondition(...)`. 120 unnecessary API calls per 60 s startup → ~zero.

- [ ] **Cache `EffectiveSet` per `(userId, jwtRoles.hashCode, jwtGroups.hashCode)`** with Caffeine, invalidated on RBAC mutations (`putRole`, `putRolePermission`, `addUserRole`, etc.). Today recomputed for every new FlightSQL connection. PoolSupervisor.scala:1167.

- [ ] **Snapshot maps once in `ManifestImporter.apply`** instead of re-fetching per iteration. N+1 reads at `manifest/ManifestImporter.scala:186, 194, 214, 216, 317, 343, 353, 369-374`. Compounds with the no-pool penalty above. 10–50× speedup expected.

- [ ] **Switch `ManifestExporter.build` to `store.snapshot()`** and walk in memory. Same N+1 shape at `manifest/ManifestExporter.scala:37, 111, 130, 134, 137, 170`. `ControlPlaneStore.snapshot()` already exists.

- [ ] **Evict + close cached `DuckLakeCatalogReader`** on `deleteTenantDb` and on auth-credential change. `catalog/DuckLakeCatalogReader.scala:174-183` opens a Hikari pool per `apply()`; cached by tenant-db in `Main.scala:286-291` and never evicted. Pool stays open with idle conns forever.

- [ ] **Narrow the advisory-lock window in `DuckLakeInitializer`.** Lines `DuckLakeInitializer.scala:143-156` hold the lock across `INSTALL/LOAD`; only `ATTACH` + `CREATE SCHEMA` actually need it.

---

## P1 — code quality / cleanup

- [ ] **Extract three duplicated predicates** in `api/`:
  - `superuserCheck` — three copies: `TenantHandlers.scala:38`, `ConfigHandlers.scala:21`, `ManifestHandlers.scala:27-39`.
  - `resolveTenantId` (displayName-or-id) — three copies: `RoleHandlers.scala:15-20`, `GroupHandlers.scala:12-17`, `PoolPermissionHandlers.scala:15-20`.
  - `redact(pgPassword)` — three copies: `PoolHandlers.scala:19-20`, `TenantDbHandlers.scala:23-24`, `FederatedSourceHandlers.scala:24`.

- [ ] **Stop error-classification by `startsWith("...")`/`contains("...")`** in ~12 handler sites (e.g. `TenantHandlers.scala:107, 121, 137`, `RoleHandlers.scala:38`, `MembershipHandlers.scala:18`). Replace supervisor `Left(String)` with a typed `enum SupervisorError`.

- [ ] **Endpoint signature drift.** `listTenantDbs`, `statementHistory`, and 4 catalog endpoints don't plumb `X-API-Key` while ~40 siblings do (`api/Endpoints.scala:250-252, 277-283, 357-388`). Side-effect: those handlers can't reject by session scope and rely entirely on URL-path guard. Make signatures uniform.

- [ ] **`UserHandlers.listUsers` `__forbidden__` sentinel** (`api/UserHandlers.scala:121`). Replace with a typed predicate.

- [ ] **Hand-rolled circe decoders** in `manifest/ConfigManifest.scala` (~150 LOC, L141-307) to honor Scala 3 case-class defaults. Replace with a single `ConfiguredCodec.withDefaults` helper.

- [ ] **Consolidate `UserStore.upsertUser` and `PostgresControlPlaneStore.upsertUserWithHash`.** Both implement `(tenant, username)`-keyed upsert + bcrypt against the same table. Have one call the other.

- [ ] **Single `***REDACTED***` constant** shared by `ManifestExporter.scala:14` and `ManifestImporter.scala:458` (currently duplicated string literals).

- [ ] **Fix `ManifestExporter` data loss:**
  - `password = None` at L143, L176 — exported manifests cannot round-trip without manual edit.
  - Hardcoded `enabled = true` at L145, L178 — silently loses disabled-user state.
  - Superuser pool grants emit `pool = None` (L171) — cross-tenant grants collapse to a useless global on export.

---

## P2 — structural

- [ ] **Parallelize boot reconcile.** `PoolSupervisor.reconcile` (PoolSupervisor.scala:155-162) and `reconcilePool` (L164-213) are both serial `foldLeft`. 10 pools × 3 dead pods × 60 s timeout = **30 min boot** vs ~60 s with `parTraverseN(8)`. Mind `tracker.remove` ordering.

- [ ] **Parallelize pool creation / scale-up.** `createPool` and `spawnFromDistribution` (PoolSupervisor.scala:240-244, 670-674) serial-fold `backend.start`. Same fix with `parTraverseN`. Safe now that the ATTACH race is solved by `DuckLakeInitializer`.

- [ ] **Parallelize `ensureDuckLakeInitialized`** (PoolSupervisor.scala:141-152). Different `dbName`s don't share advisory locks. `parTraverseN(4)` is safe.

- [ ] **Reverse `roleId → permIds` index** in `RbacResolver` (rbac/RbacResolver.scala:141-147). `permissionsForRoles` currently filters all permissions per call (called per handshake AND per user in `effectiveSetsForUsers`).

- [ ] **Name-keyed and `(tenant, pool)`-keyed indexes:**
  - `RbacResolver.rolesByNamesInTenant` / `groupsByNamesInTenant` (L123-139) — O(N) per JWT handshake. Add `TrieMap[(tenantId, name), id]`.
  - `PoolSupervisor.findPoolKeyByTenantAndPoolName` (L352-354) — linear scan. Add `(tenant, pool) → PoolKey` TrieMap.

- [ ] **Split `PoolSupervisor`** (~1283 LOC) along seams: `Registry` (CRUD + caches), `RbacGraph` (mutators + handshake closure), `Supervisor` (reconcile + spawn), `Handshake` (auth gates). One mutex per seam; clearer invariants.

- [ ] **Consolidate `LocalQuackBackend`'s 4 parallel TrieMaps** keyed by `nodeId` (`runtime/LocalQuackBackend.scala:21-27`: `processes`/`tokens`/`nodePorts`/`adoptedPids`) into one `case class NodeHandle`. Atomicity across the four is not currently guaranteed.

- [ ] **`BitSet`-backed `PortAllocator`** (`runtime/PortAllocator.scala:8-13`). Currently O(range) per call under a global lock. Fine at 600 ports; add a cursor for safety as pool sizes grow.

- [ ] **Dedicated bounded `ExecutionContext` for bcrypt + JDBC.** BCrypt cost-12 (~250 ms) on cats-effect blocking pool (`state/UserStore.scala:62`); under many concurrent OIDC bootstraps it saturates.

- [ ] **Manifest export ↔ import duplication.** Model↔YAML mapping encoded twice (~150 LOC, inverses) across `ManifestExporter`/`ManifestImporter`. Introduce `toManifest`/`fromManifest` per case class to prevent drift.

- [ ] **`ManifestImporter.apply` is one 252-line method;** `ManifestExporter.build` is one 160-line method. Split per resource type.

- [ ] **Transactional cache invalidation in `PoolSupervisor`.** Today every mutator does `store.X` then `cache.put` with no rollback on partial failure. Pick: (a) write-through with retry-from-store on cache exception, (b) treat the cache as derived and rebuild from `snapshot()` on any mutation error.

- [ ] **`ManifestImporter` bypasses `PoolSupervisor` side effects.** Imported tenants get no admin role seeded (REST `createTenant` seeds via `store.createTenantWithAdminRole`); imported tenant-dbs rely on the boot-time `ensureDuckLakeInitialized` sweep. Either route imports through the supervisor or extract the seeding logic into a shared helper.

---

## P3 — polish / deferred decisions

- [ ] **`AuthZ enforcement spread across 3 layers**: `apiKeyGuard` middleware, `TenantScopeCheck.reject` in handlers, per-handler superuser checks. Single source of truth via Tapir middleware (related to the P0 RBAC fix).

- [ ] **`DemoBootstrapHook.DemoNames` collision check** (`bootstrap/DemoBootstrapHook.scala:64-65`) — a non-demo manifest with no demo tenants re-imports on every boot, and the importer's delete-then-upsert wipes REST-added sibling rows. Tighten to "any tenant exists" or make the gate explicit.

- [ ] **Rename or fix half-implemented backend ops:** `NodeHandlers.restartNode` (only stops), `LocalQuackBackend.drainAndStop` is a stub that just calls `stop` (`PoolSupervisor.scala:807`).

- [ ] **`Tenant.name` vs `displayName`** — `name` is the lowercased addressing key, `displayName` the human label. Co-equal after normalization; collapse to one field where possible.

- [ ] **`SecretResolver` as `trait`, not `abstract class`** (`federation/SecretResolver.scala`). One abstract method; trait enables SAM literals.

- [ ] **`PostgresSecretResolver` is misnamed** — it returns the already-loaded `value` field; never touches Postgres. Rename to `InlineSecretResolver` or collapse into the dispatcher.

- [ ] **`ConfigRegistry.collect` reflects on every `/config/server`** (`api/ConfigRegistry.scala:50-62`). Cache the entry list at startup.

- [ ] **`ManagerServer` has a 14-arg constructor** (`ManagerServer.scala:18-43`). Wrap in `ManagerDeps`.

- [ ] **`Host.fromString(cfg.host).get` / `Port.fromInt(cfg.port).get`** at `ManagerServer.scala:293-294` crash with `NoSuchElementException` on malformed HOCON. Validate at config-load time.

- [ ] **`RbacResolver.replace` global `synchronized`** (L55) is fine because rare; but mutators aren't co-synchronized, so a concurrent replace during a write produces a torn graph. Either also synchronize mutators or document boot-only.

- [ ] **`RbacResolver.putRolePermission` doesn't validate `p.roleId`** exists (L84), unlike `addGroupRoleEdge` which checks both endpoints (L99).

- [ ] **Hand-rolled JDBC try/finally** in `DuckLakeInitializer` and `DuckLakeCatalogReader`. Replace with `scala.util.Using` or cats `Resource`. Several `catch case _: Throwable => ()` swallows on close.

- [ ] **Document the FederationBlobBuilder operator-trust model** in `federation/FederationBlobBuilder.scala` and in the federation manifest schema (related to the P0 SQL-safety item).

---

## Dead code — safe one-shot deletion bundle (~250 LOC prod + ~80 LOC tests)

`Main.scala:213-214` unconditionally builds `PostgresControlPlaneStore` — the `stateStorage` config knob only gates side-features.

- [ ] `src/main/scala/ai/starlake/quack/ondemand/state/PostgresStateStore.scala` (109 LOC) — imported by `Main.scala:51`, never instantiated.
- [ ] `src/main/scala/ai/starlake/quack/ondemand/state/StateStore.scala` (82 LOC) — `FileStateStore` only referenced by `StateStoreSpec`.
- [ ] `src/main/scala/ai/starlake/quack/ondemand/state/StoredState.scala` (12 LOC) — schema-stale.
- [ ] `src/main/scala/ai/starlake/quack/ondemand/state/StoredPool.scala` (13 LOC) — schema-stale (no `cohorts`/`idleTimeoutSec`/`disabled`/`tenantDbId`).
- [ ] `src/main/scala/ai/starlake/quack/ondemand/state/StoredTenant.scala` (9 LOC) — schema-stale (no `authProvider`/`authConfig`/`displayName`/`disabled`).
- [ ] `src/test/scala/ai/starlake/quack/ondemand/state/StateStoreSpec.scala` — delete with above.
- [ ] `Main.scala` — drop `stateStorage` guards at L139, 226, 284, 300, 512; drop the `PostgresStateStore` import.
- [ ] `Config.scala:185-192` — drop the `stateStorage` field.
- [ ] `application.conf:22-33` — drop `stateStorage` + the stale `slkstate_pool_state` doc comment.
- [ ] `test/.../ManagerServerHarness.scala:51` — drop `stateStorage = "file"`.
- [ ] `state/DbAdmin.scala:69-72` — drop the non-FORCE `DROP DATABASE` fallback (PG16 floor → unreachable).
- [ ] `manifest/ConfigManifest.scala` — drop `ManifestIdentity` (L68-72 + codec L243-251 + decode L263-264). Exported empty, never read by importer.
- [ ] `ManifestExporter.scala:160` — remove the now-orphan `identities = Nil`.
- [ ] `api/Dtos.scala` — drop `FederationImportSummary` case class + codec (defined, never referenced).
- [ ] `CLAUDE.md` — drop the "YAML grant store ... unwired after the RBAC cutover" line; the code is already gone.

---

## Not dead, but flagged as suspicious — left in place

- `RolePermission.Wildcard = "*"` — used 12+ times (PostgresAclValidator, PoolSupervisor admin seed).
- `Tenant.name` vs `displayName` — both reachable, see P3.
- `UserStore.upsertUser` vs `PostgresControlPlaneStore.upsertUserWithHash` — both reachable (different callers), see P1 consolidation.
- `EnvSecretResolver` prefix re-check — dead under `dispatch` mode, meaningful when wired directly (`Main.scala:200`).

---

## Audit provenance

Findings synthesized from six parallel agent passes on 2026-06-12 covering:
1. Top-level + runtime (`PoolSupervisor`, `ManagerServer`, `DuckLakeInitializer`, backends)
2. `api/` REST handlers (23 files)
3. `state/` + `rbac/` + `auth/`
4. `federation/` + `manifest/` + `bootstrap/`
5. Performance hot paths (handshake, REST, boot, K8s)
6. Dead-code sweep with cross-repo grep verification