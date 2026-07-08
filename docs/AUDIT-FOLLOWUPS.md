# ondemand/ audit followups

Generated 2026-06-12 from a multi-pass audit of `src/main/scala/ai/starlake/quack/ondemand/` (70 files, ~8.5 K LOC). Updated as items ship. Each entry is independently actionable.

**Priority key:**

- **P0** - correctness / authZ; ship-blocker for any prod-facing deployment.
- **P1** - cheap wins (<1 day each), measurable user impact.
- **P2** - structural refactors (days to a week), reduce ongoing maintenance cost.
- **P3** - polish, naming, deferred decisions.

## Progress (as of 2026-06-12)

| Priority | Closed | Open |
|---|---:|---:|
| P0 - security & correctness | 7 | 0 |
| P0 - known limitations | 3 | 1 |
| P1 - perf cheap wins | 8 | 1 |
| P1 - code quality | 0 | 8 |
| P2 - structural | 0 | 13 |
| P3 - polish | 0 | 14 |
| Dead-code bundle (~250 LOC) | 0 | 15 |

---

# Open

## P0 - known limitations

- [x] **Cloud secret resolvers** ~~(`Vault`, `Aws`, `Azure`, `Gcp`) are stubs raising `NotImplementedError`.~~ Gated 2026-06-12: `Main.scala` refuses `secretStore = aws-sm/gcp-sm/azure-kv/vault` at config load with a `sys.error` pointing at the working alternatives. `dispatch` mode keeps the stubs wired so postgres+env-only deployments stay up; only sources whose externalRef carries a stub prefix hit the runtime error, and that error now names the secret + the prefix the operator selected + the alternatives (`postgres` / `env:`). UI dropdown options for stub stores are `disabled` with a tooltip + an inline red notice explains they raise at node spawn until the SDK is wired. Coverage in `SecretResolverSpec` (each stub asserts the actionable error message).

## P1 - performance

- [ ] **Narrow the advisory-lock window in `DuckLakeInitializer`.** Lines `DuckLakeInitializer.scala:143-156` hold the lock across `INSTALL/LOAD`; only `ATTACH` + `CREATE SCHEMA` actually need it. Low-impact unless boot reconcile is also parallelized.

## P1 - code quality / cleanup

- [ ] **Extract three duplicated predicates** in `api/`:
  - `superuserCheck` - three copies: `TenantHandlers.scala:38`, `ConfigHandlers.scala:21`, `ManifestHandlers.scala:27-39`.
  - `resolveTenantId` (displayName-or-id) - three copies: `RoleHandlers.scala:15-20`, `GroupHandlers.scala:12-17`, `PoolPermissionHandlers.scala:15-20`.
  - `redact(pgPassword)` - three copies: `PoolHandlers.scala:19-20`, `TenantDbHandlers.scala:23-24`, `FederatedSourceHandlers.scala:24`.

- [ ] **Stop error-classification by `startsWith("...")`/`contains("...")`** in ~12 handler sites (e.g. `TenantHandlers.scala:107, 121, 137`, `RoleHandlers.scala:38`, `MembershipHandlers.scala:18`). Replace supervisor `Left(String)` with a typed `enum SupervisorError`.

- [ ] **Endpoint signature drift.** `TenantEndpoints.listTenantDbs`, `NodeEndpoints.statementHistory`, and the 4 `CatalogEndpoints` browser GETs don't plumb `X-API-Key` while ~40 siblings do. Side-effect: those handlers can't reject by session scope and rely entirely on URL-path guard. Make signatures uniform. (The snapshot-tag surface in `TagEndpoints` deliberately does NOT mirror this drift.)

- [ ] **`UserHandlers.listUsers` `__forbidden__` sentinel** (`api/UserHandlers.scala:121`). Replace with a typed predicate.

- [ ] **Hand-rolled circe decoders** in `manifest/ConfigManifest.scala` (~150 LOC, L141-307) to honor Scala 3 case-class defaults. Replace with a single `ConfiguredCodec.withDefaults` helper.

- [ ] **Consolidate `UserStore.upsertUser` and `PostgresControlPlaneStore.upsertUserWithHash`.** Both implement `(tenant, username)`-keyed upsert + bcrypt against the same table. Have one call the other.

- [ ] **Single `***REDACTED***` constant** shared by `ManifestExporter.scala:14` and `ManifestImporter.scala:458` (currently duplicated string literals).

- [ ] **Fix `ManifestExporter` data loss:**
  - `password = None` at L143, L176 - exported manifests cannot round-trip without manual edit.
  - Hardcoded `enabled = true` at L145, L178 - silently loses disabled-user state.
  - Superuser pool grants emit `pool = None` (L171) - cross-tenant grants collapse to a useless global on export.

## P2 - structural

- [ ] **Parallelize boot reconcile.** `PoolSupervisor.reconcile` (PoolSupervisor.scala:155-162) and `reconcilePool` (L164-213) are both serial `foldLeft`. 10 pools × 3 dead pods × 60 s timeout = **30 min boot** vs ~60 s with `parTraverseN(8)`. Mind `tracker.remove` ordering.

- [ ] **Parallelize pool creation / scale-up.** `createPool` and `spawnFromDistribution` (PoolSupervisor.scala:240-244, 670-674) serial-fold `backend.start`. Same fix with `parTraverseN`. Safe now that the ATTACH race is solved by `DuckLakeInitializer`.

- [ ] **Parallelize `ensureDuckLakeInitialized`** (PoolSupervisor.scala:141-152). Different `dbName`s don't share advisory locks. `parTraverseN(4)` is safe.

- [ ] **Reverse `roleId → permIds` index** in `RbacResolver` (rbac/RbacResolver.scala:141-147). `permissionsForRoles` currently filters all permissions per call (called per handshake AND per user in `effectiveSetsForUsers`).

- [ ] **Name-keyed and `(tenant, pool)`-keyed indexes:**
  - `RbacResolver.rolesByNamesInTenant` / `groupsByNamesInTenant` (L123-139) - O(N) per JWT handshake. Add `TrieMap[(tenantId, name), id]`.
  - `PoolSupervisor.findPoolKeyByTenantAndPoolName` (L352-354) - linear scan. Add `(tenant, pool) → PoolKey` TrieMap.

- [ ] **Split `PoolSupervisor`** (~1283 LOC) along seams: `Registry` (CRUD + caches), `RbacGraph` (mutators + handshake closure), `Supervisor` (reconcile + spawn), `Handshake` (auth gates). One mutex per seam; clearer invariants.

- [ ] **Consolidate `LocalQuackBackend`'s 4 parallel TrieMaps** keyed by `nodeId` (`runtime/LocalQuackBackend.scala:21-27`: `processes`/`tokens`/`nodePorts`/`adoptedPids`) into one `case class NodeHandle`. Atomicity across the four is not currently guaranteed.

- [ ] **`BitSet`-backed `PortAllocator`** (`runtime/PortAllocator.scala:8-13`). Currently O(range) per call under a global lock. Fine at 600 ports; add a cursor for safety as pool sizes grow.

- [ ] **Dedicated bounded `ExecutionContext` for bcrypt + JDBC.** BCrypt cost-12 (~250 ms) on cats-effect blocking pool (`state/UserStore.scala:62`); under many concurrent OIDC bootstraps it saturates.

- [ ] **Manifest export ↔ import duplication.** Model↔YAML mapping encoded twice (~150 LOC, inverses) across `ManifestExporter`/`ManifestImporter`. Introduce `toManifest`/`fromManifest` per case class to prevent drift.

- [ ] **`ManifestImporter.apply` is one 252-line method;** `ManifestExporter.build` is one 160-line method. Split per resource type.

- [ ] **Transactional cache invalidation in `PoolSupervisor`.** Today every mutator does `store.X` then `cache.put` with no rollback on partial failure. Pick: (a) write-through with retry-from-store on cache exception, (b) treat the cache as derived and rebuild from `snapshot()` on any mutation error.

- [ ] **`ManifestImporter` bypasses `PoolSupervisor` side effects.** Imported tenants get no admin role seeded (REST `createTenant` seeds via `store.createTenantWithAdminRole`); imported tenant-dbs rely on the boot-time `ensureDuckLakeInitialized` sweep. Either route imports through the supervisor or extract the seeding logic into a shared helper.

## P3 - polish / deferred decisions

- [ ] **AuthZ enforcement spread across 3 layers**: `apiKeyGuard` middleware, `TenantScopeCheck.reject` in handlers, per-handler superuser checks. Single source of truth via Tapir middleware (related to the P0 RBAC fix).

- [ ] **`DemoBootstrapHook.DemoNames` collision check** (`bootstrap/DemoBootstrapHook.scala:64-65`) - a non-demo manifest with no demo tenants re-imports on every boot, and the importer's delete-then-upsert wipes REST-added sibling rows. Tighten to "any tenant exists" or make the gate explicit.

- [ ] **Rename or fix half-implemented backend ops:** `LocalQuackBackend.drainAndStop` is a stub that just calls `stop` (`PoolSupervisor.scala:807`).

- [ ] **`Tenant.name` vs `displayName`** - `name` is the lowercased addressing key, `displayName` the human label. Co-equal after normalization; collapse to one field where possible.

- [ ] **`SecretResolver` as `trait`, not `abstract class`** (`federation/SecretResolver.scala`). One abstract method; trait enables SAM literals.

- [ ] **`PostgresSecretResolver` is misnamed** - it returns the already-loaded `value` field; never touches Postgres. Rename to `InlineSecretResolver` or collapse into the dispatcher.

- [ ] **`ConfigRegistry.collect` reflects on every `/config/server`** (`api/ConfigRegistry.scala:50-62`). Cache the entry list at startup.

- [ ] **`ManagerServer` has a 14-arg constructor** (`ManagerServer.scala:18-43`). Wrap in `ManagerDeps`.

- [ ] **`Host.fromString(cfg.host).get` / `Port.fromInt(cfg.port).get`** at `ManagerServer.scala:293-294` crash with `NoSuchElementException` on malformed HOCON. Validate at config-load time.

- [ ] **`RbacResolver.replace` global `synchronized`** (L55) is fine because rare; but mutators aren't co-synchronized, so a concurrent replace during a write produces a torn graph. Either also synchronize mutators or document boot-only.

- [ ] **`RbacResolver.putRolePermission` doesn't validate `p.roleId`** exists (L84), unlike `addGroupRoleEdge` which checks both endpoints (L99).

- [ ] **Hand-rolled JDBC try/finally** in `DuckLakeInitializer` and `DuckLakeCatalogReader`. Replace with `scala.util.Using` or cats `Resource`. Several `catch case _: Throwable => ()` swallows on close.

- [ ] **Document the FederationBlobBuilder operator-trust model** in `federation/FederationBlobBuilder.scala` and in the federation manifest schema (related to the P0 SQL-safety item).

- [ ] **Cookie session over a path-rewriting reverse proxy.** The cookie path is now configurable via `QOD_SESSION_COOKIE_PATH` (default `/api`), so most reverse-proxy shapes work out of the box. If we ever move to a cross-origin UI/API split, also wire CORS + `Domain=` (currently no CORS layer).

## Dead code - safe one-shot deletion bundle

- [x] **Deleted 2026-06-12.** ~250 LOC prod + ~80 LOC tests dropped in one commit. File-state mode (`PostgresStateStore`, `StateStore`/`FileStateStore`, `StoredState`, `StoredPool`, `StoredTenant`, `StateStoreSpec`) gone along with the `stateStorage`/`statePath` config keys and the 5 `Main.scala` guards they gated. Also dropped: `DbAdmin.dropDatabase` non-FORCE fallback (PG16 floor), `ManifestIdentity` + codec + decode + orphan exporter assignment, `FederationImportSummary` + codec, and the stale `CLAUDE.md` line about YAML grants.

---

# Done - 2026-06-12 changelog

Sorted by ship date. Each entry names the commit and a one-line summary.

### P0 - security & correctness

- [x] `88dd7c5` - **Cross-tenant RBAC writes** closed. Every RBAC handler now gates via `TenantScopeCheck.{reject,rejectForResource,rejectForUser}`; new supervisor lookup helpers + store getters resolve id-only endpoints to their owning tenant. Coverage in `RbacTenantScopeSpec`.
- [x] `901ef3a` - **Statement-history cross-tenant leak** closed. `GET /api/node/statements` filters by session `manageableTenants`, allow-set carries both id and display name.
- [x] `558e4db` - **`NodeHandlers.setRole` removed** + tautological `LoginResponse.role` dropped.
- [x] `c47bcbb` - **`NodeHandlers.restartNode` removed**.
- [x] `f6f75a3` - **DuckLake `pgPassword` SQL escape** fixed. PG calls switched to PreparedStatement; ATTACH uses layered `libpqValue` + `duckdbLiteral` escape.
- [x] `5f5e3f8` - **`FederationBlobBuilder` SQL-quote-escape** for resolved secrets + alias.
- [x] `b959c78` - **8-char id truncation → full 128-bit UUIDs** (`Names.newSurrogateId`); pattern loosened so legacy ids still match.

### P0 - known limitations

- [x] `25195ff` - **`SessionTokenStore` idle-TTL** (initial sliding-window).
- [x] `b7e804a` - **`SessionTokenStore` → JWT + HttpOnly cookie**. Stateless sessions; `apiKeyGuard` admits via header OR cookie; UI dropped localStorage path; configurable `sessionJwtSecret` / `sessionCookieSecure` / `sessionCookiePath`.
- [x] `0d8f088` - **K8s federation `extraSetupSql`** propagated via per-pool Secret + `env.valueFrom.secretKeyRef`. GC on last pod stop.
- [x] `c3296cc` - **K8s node tokens persisted in per-pod Secret**; recovered on restart by `discoverExisting`.

### P1 - performance cheap wins

- [x] `d4984ca` - **HikariCP** on `PostgresControlPlaneStore` (size 20) + `UserStore` (size 10). `close()` on the trait wired into the shutdown hook.
- [x] `7b43c0d` - Three fixes in one commit:
  - `PoolSupervisor.createPool`: `unsafeRunSync` → `flatMap` so the federation-blob fetch composes properly.
  - `KubernetesQuackBackend.waitReady`: `Thread.sleep(500)` busy-poll → fabric8 `waitUntilCondition` watch.
  - `ManifestImporter.apply` + `ManifestExporter.build`: one `store.snapshot()` per call seeds mutable name→entity maps so the per-tenant loops don't go back to the store.
- [x] `c33cd1e` - **EffectiveSet cache** (60s TTL, `ConcurrentHashMap`, keyed by `(userId, jwtRolesHash, jwtGroupsHash)`); invalidated from every RBAC mutator + `restore()`. **DuckLakeCatalogReader eviction**: `PoolSupervisor.onTenantDbDeleted` callback fires on `deleteTenantDb` and the cascade through `deleteTenant`; Main's shutdown hook drains the cache.

---

# Not dead, but flagged as suspicious - left in place

- `RolePermission.Wildcard = "*"` - used 12+ times (PostgresAclValidator, PoolSupervisor admin seed).
- `Tenant.name` vs `displayName` - both reachable, see P3.
- `UserStore.upsertUser` vs `PostgresControlPlaneStore.upsertUserWithHash` - both reachable (different callers), see P1 consolidation.
- `EnvSecretResolver` prefix re-check - dead under `dispatch` mode, meaningful when wired directly (`Main.scala:200`).

---

# Audit provenance

Findings synthesized from six parallel agent passes on 2026-06-12 covering:

1. Top-level + runtime (`PoolSupervisor`, `ManagerServer`, `DuckLakeInitializer`, backends)
2. `api/` REST handlers (23 files)
3. `state/` + `rbac/` + `auth/`
4. `federation/` + `manifest/` + `bootstrap/`
5. Performance hot paths (handshake, REST, boot, K8s)
6. Dead-code sweep with cross-repo grep verification