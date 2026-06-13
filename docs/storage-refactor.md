# Design: Multi-tenant control plane - storage refactor + minimal tenant resolution

**Status:** ready for implementation
**Target:** `quack-on-demand` (Scala 3.7 / JDK 21, sbt, Postgres + file state backends)
**Executor:** Claude Code
**Shape:** two ordered phases in one document. Phase 1 is a pure storage refactor; Phase 2 is the minimal auth/tenant-resolution slice that makes the multi-tenant model actually function. Phase 2 depends on Phase 1 and must be implemented after it.

---

## 1. Objective

Deliver a working multi-tenant, multi-environment control plane:

- **Phase 1 - storage.** Replace the single-blob control-plane store with a normalized relational model: a first-class **environment** layer between tenant and pool, with the environment owning the metastore + data path, and **desired config (pool) split from runtime state (node)**. Includes a migration framework and a one-time data migration. Preserves today's single-tenant behavior.
- **Phase 2 - tenant resolution.** Bind the authenticated identity to a `(tenant, environment)` and feed it through the supervisor and ACL validator, replacing the hard-coded `"default"` tenant. This is what turns the Phase 1 tables from inert storage into a functioning multi-tenant system.

Authentication already exists (the `AuthenticationService` chain: DB / JWT / OIDC). What is missing - and what Phase 2 adds - is the mapping from a verified identity to a tenant, plus environment selection at connect time.

---

## 2. Scope and phasing

### Phase 1 - storage refactor (in scope)
- New relational entities: tenant, environment, pool, node, with foreign keys.
- Environment owns the metastore connection params and data path (moved off tenant and pool).
- Pool record holds desired config only; a new node record holds runtime state (`RunningNode` extracted out of `StoredPool`).
- Evolve the store from "load/save the whole `StoredState` blob" to a per-entity repository, both backends (`PostgresStateStore`, `FileStateStore`).
- Schema-version + ordered-migration mechanism, plus the one-time data migration in §6.
- Update `PoolSupervisor` + self-healing reconciler to read the graph and write node runtime via per-entity upserts.

### Phase 2 - minimal tenant resolution (in scope, after Phase 1)
- A `qodstate_tenant_identity` mapping table: `(issuer, external_id) → tenant`, used as an allowlist.
- Per-issuer tenant derivation after authentication (Keycloak realm and Google `hd` are the concrete minimum; resolver must be pluggable for the others).
- Environment selection at connect (`x-quack-env` header), validated against the resolved tenant, bound to the session.
- Wire the resolved `(tenant, environment)` into `PoolSupervisor` (metastore/pool resolution) and `PostgresAclValidator` (`aclTenant`, default catalog/schema), replacing the `"default"` constant.

### Deferred (after both phases - do NOT implement here)
- **Secret hygiene.** Metastore credentials and per-node `token` values stay stored as-is (plain values in rows / JSON columns). Leave a `TODO(secrets)` marker where a credential is persisted; change nothing about how it is stored.
- **RLS / column masking** and any change to `slkstate_acl_grant` (e.g. adding `env_id` to grants - a follow-up this work enables).
- **Identity-management admin UI** beyond the minimal CRUD needed to populate the mapping table.
- **Group/role-keyed ACL enforcement.** `PostgresAclValidator` propagates only the `user:<name>` principal today; group/role propagation stays deferred.
- **Additional provider resolvers** (Azure `tid`, Cognito pool id, GCIP `firebase.tenant`) beyond the pluggable hook + the two concrete implementations.
- **Multi-manager HA / leader election.** Not implemented - but Phase 1 schema and store API must be *designed for it* (per-entity idempotent upserts, DB as single source of truth). No advisory-lock work here.

---

## 3. Current state (what we are replacing)

Persistence lives in `ai.starlake.quack.ondemand.state`:

- `StateStore` trait - `load(): StoredState`, `save(StoredState): Unit`. Whole-blob read/write on every mutation.
- `PostgresStateStore` - `slkstate_pool_state` is a **single row** (`id INTEGER PK DEFAULT 1 CHECK (id = 1)`, `content JSONB`, `updated_at`); the entire topology is one JSON document, upserted wholesale.
- `FileStateStore` - same `StoredState` document as an atomically-replaced JSON file.
- `StoredState(pools: Map[String, StoredPool], tenants: Map[String, StoredTenant])`.
- `StoredTenant(name, metastore: Map[String, String])`.
- `StoredPool(key: PoolKey, size, distribution: RoleDistribution, metastore: Map, s3: Map, nodes: List[RunningNode], maxConcurrentPerNode)` - **mixes desired config and the live node list.**
- `PoolKey(tenant, pool)` → `"tenant/pool"`.
- `RunningNode(nodeId, poolKey, role, host, port, token, pid, podName, startedAt, maxConcurrent)`.
- `RoleDistribution(writeonly, readonly, dual)`.
- `PoolSupervisor` resolves the effective metastore by layering `defaultMetastore ++ tenantOverrides ++ poolMetastore`.
- **Tenant resolution:** `PostgresAclValidator` reads `aclTenant` from session config (default `"default"`) and the principal from `ValidationContext.username`. The auth chain authenticates the user but the result is **not** mapped to a tenant - this is the gap Phase 2 closes.

Problems: no per-entity queries/concurrency (whole-topology rewrite → last-write-wins, an HA blocker); desired config and runtime share a record (config edits race the reconciler); no environment concept; metastore duplicated on tenant and pool with drift risk; and tenancy is effectively a single `"default"`.

---

## 4. Phase 1 - Target data model

### 4.1 Entities and relationships
```
tenant (1) ──< environment (1) ──< pool (1) ──< node
```
- **tenant** - ownership/grouping umbrella; surrogate immutable id + mutable display name.
- **environment** - owns one metastore (params + schema) and one data path; belongs to a tenant.
- **pool** - desired compute config (size, role distribution, limits); belongs to an environment; never spans environments.
- **node** - runtime instance of a Quack node (the extracted `RunningNode`); belongs to a pool.

Ids are app-generated and immutable (surrogate keys); display names are mutable and never used as foreign keys.

### 4.2 DDL (Postgres backend)
Use the `qodstate_` prefix for all new tables (matching the existing `QOD_*` env-var convention). The pre-existing tables - `slkstate_acl_grant`, `slkstate_user`, and the legacy `slkstate_pool_state` blob - keep their `slkstate_` names (renaming them is a separate migration, out of scope; the data migration in §6 must read `slkstate_pool_state` by its real name). Optionally place new tables in a dedicated `qodstate` Postgres schema to separate the control plane from per-environment DuckLake `__ducklake_*` metadata (configurable, default-compatible).

```sql
CREATE TABLE qodstate_tenant (
  id            TEXT PRIMARY KEY,              -- app-generated, immutable
  display_name  TEXT NOT NULL,
  disabled      BOOLEAN NOT NULL DEFAULT FALSE, -- soft-delete / decommission marker
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE qodstate_environment (
  id              TEXT PRIMARY KEY,
  tenant_id       TEXT NOT NULL REFERENCES qodstate_tenant(id) ON DELETE RESTRICT,
  name            TEXT NOT NULL,               -- e.g. 'default', 'prod', 'eu'
  metastore_params JSONB NOT NULL,             -- TODO(secrets): values stored as-is for now
  data_path       TEXT NOT NULL,
  object_store_params JSONB NOT NULL DEFAULT '{}'::jsonb,  -- former pool.s3; TODO(secrets)
  disabled        BOOLEAN NOT NULL DEFAULT FALSE, -- soft-delete / decommission marker
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, name)
);

CREATE TABLE qodstate_pool (
  id                      TEXT PRIMARY KEY,
  environment_id          TEXT NOT NULL REFERENCES qodstate_environment(id) ON DELETE RESTRICT,
  name                    TEXT NOT NULL,
  size                    INT  NOT NULL,
  dist_writeonly          INT  NOT NULL,
  dist_readonly           INT  NOT NULL,
  dist_dual               INT  NOT NULL,
  max_concurrent_per_node INT  NOT NULL DEFAULT 0,
  idle_timeout_sec        INT,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (environment_id, name)
);

CREATE TABLE qodstate_node (
  node_id        TEXT PRIMARY KEY,             -- = RunningNode.nodeId
  pool_id        TEXT NOT NULL REFERENCES qodstate_pool(id) ON DELETE RESTRICT,
  role           TEXT NOT NULL,                -- writeonly | readonly | dual
  host           TEXT NOT NULL,
  port           INT  NOT NULL,
  token          TEXT,                         -- TODO(secrets): stored as-is for now
  pid            BIGINT,                       -- local mode
  pod_name       TEXT,                         -- k8s mode
  max_concurrent INT NOT NULL DEFAULT 0,
  started_at     TIMESTAMPTZ NOT NULL,
  last_seen      TIMESTAMPTZ                   -- written by the reconciler
);

CREATE INDEX qodstate_environment_tenant ON qodstate_environment(tenant_id);
CREATE INDEX qodstate_pool_env           ON qodstate_pool(environment_id);
CREATE INDEX qodstate_node_pool          ON qodstate_node(pool_id);
```


### 4.3 File backend
`FileStateStore` keeps a single atomically-replaced JSON file, but its document mirrors the normalized model (`tenants[]`, `environments[]`, `pools[]`, `nodes[]`) and it implements the same repository API in-memory. The per-entity-upsert (HA) benefit applies to the Postgres backend only; the file backend is single-instance local/dev.

### 4.4 Lifecycle and deletion semantics (decided)
- **Tenant and environment: soft-delete.** Set `disabled = TRUE`; the row and its children are retained. A disabled tenant/environment is excluded from resolution (auth/env selection treats it as not found). Disabling cascades *operationally* - children's runtime nodes are drained/stopped - but never as a DB cascade. A separate, explicit admin "purge" is the only path that hard-deletes a tenant/environment, and only after its children are removed.
- **Pool and node: hard-delete, application-driven and ordered.** Deleting a pool means "stop running this compute": the app drains/stops the pool's node processes, deletes the `qodstate_node` rows, then deletes the `qodstate_pool` row. Nodes are pure runtime and carry no historical value, so they are hard-deleted.
- **FKs are `ON DELETE RESTRICT`** (no DB cascade). This guarantees the database never silently tears down a fleet; the application must perform the ordered teardown above. For soft-deleted parents this is moot (they are not hard-deleted in normal operation); for the pool→node path it forces nodes to be removed before the pool.

---

## 5. Phase 1 - Domain / model changes
- New `Environment(id, tenantId, name, metastore: Map[String,String], dataPath: String, objectStore: Map[String,String])`.
- `Tenant` gains `id` (immutable) and `displayName`; `metastore` moves to `Environment`.
- New `Pool` domain type (desired config) distinct from runtime nodes.
- `RunningNode` keeps its shape, gains `lastSeen`.
- **`PoolKey` (decided): extend to `PoolKey(tenant, environment, pool)`** → `"tenant/env/pool"`, with `parse` accepting a 2-segment legacy form mapped to the `default` environment. Surrogate ids (`qodstate_pool.id`, `qodstate_node.node_id`, etc.) remain the persistent identity and FK key in the DB; `PoolKey` is the natural addressing/routing/display key and matches the connection contract. Renames change the `PoolKey` but not the surrogate id, so FKs and history stay rename-stable. `RunningNode.poolKey` becomes the 3-part key; the node row references its pool by surrogate `pool_id`.

---

## 6. Phase 1 - Migration plan

### 6.1 Framework
Add a `qodstate_schema_version` table and an ordered, forward-only migration runner. Tables are created/evolved by migrations, not ad-hoc `CREATE TABLE IF NOT EXISTS`. Migration `0001` creates the §4.2 schema.

### 6.2 One-time data migration from the blob
Read the existing `slkstate_pool_state` row (or the JSON file) into the old `StoredState`, then:
1. For each `StoredTenant`: create a `qodstate_tenant` (generate `id`, `display_name = name`).
2. **Derive environments from distinct metastores.** For each tenant, compute the effective `(metastore, data_path)` in use: the tenant default defines its `default` environment; group the tenant's `StoredPool`s by their *effective* `(metastore, s3/data_path)` after the `default ++ tenantOverride ++ poolMetastore` merge - each distinct tuple becomes one `qodstate_environment`. This makes the conversion lossless (a pool that carried its own metastore becomes its own environment).
3. For each `StoredPool`: create a `qodstate_pool` under its resolved environment.
4. For each `RunningNode`: create a `qodstate_node` under that pool.
5. Mark applied in `qodstate_schema_version`. Retain the old `slkstate_pool_state` row (renamed `_migrated`) for one release for rollback inspection.

Run identical logic for both backends. Back-compat: an existing single-tenant deployment becomes one tenant → one `default` environment → its pools → its nodes, behaving exactly as before.

---

## 7. Phase 1 - Store API redesign
Replace `StateStore.load/save(StoredState)` with a per-entity repository (idempotent upserts):
```
trait ControlPlaneStore:
  def snapshot(): ControlPlaneSnapshot            // tenants + envs + pools + nodes (bootstrap)
  def listTenants(): List[Tenant];        def upsertTenant(t): Unit;       def deleteTenant(id): Unit
  def listEnvironments(tenantId): List[Environment]; def upsertEnvironment(e): Unit; def deleteEnvironment(id): Unit
  def listPools(environmentId): List[Pool];          def upsertPool(p): Unit;        def deletePool(id): Unit
  def listNodes(poolId): List[RunningNode];          def upsertNode(n): Unit;        def deleteNode(nodeId): Unit
```
`PostgresStateStore` → per-row SQL upserts; `FileStateStore` → in-memory normalized document. Keep `StoredState` only for the migration, then remove.

---

## 8. Phase 1 - Affected code
- `state/`: `StateStore`, `PostgresStateStore`, `FileStateStore`, `StoredState`, `StoredTenant`, `StoredPool` - replaced/rewritten per §7.
- `model/`: add `Environment`, `Pool`; evolve `PoolKey`, `Tenant`, `RunningNode`.
- `PoolSupervisor` - load from `snapshot()`; resolve effective metastore **from the environment** (drop the tenant/pool layering); write node runtime via `upsertNode`/`deleteNode`.
- Self-healing reconciler - read desired (`listPools`) vs observed (`listNodes`), reconcile, upsert node rows (`last_seen`).
- `ondemand/api/` (`Endpoints`, `Dtos`, handlers) - introduce environment between tenant and pool; pool creation targets `(tenant, environment, pool)`; tenant CRUD no longer carries metastore (moves to environment CRUD). An env-less request targets the tenant's `default` environment.
- Admin UI (`ui/`) - add environment as a level between tenant and pool (minimal list/create/delete; pool scoped to an environment).
- **Leave untouched in Phase 1:** `AclGrantStore`, `slkstate_acl_grant`, `UserStore`, `slkstate_user`, and `acl/`.

---

## 9. Phase 2 - Tenant identity model

Add one table; it handles all auth backends uniformly:
```sql
CREATE TABLE qodstate_tenant_identity (
  id           TEXT PRIMARY KEY,
  tenant_id    TEXT NOT NULL REFERENCES qodstate_tenant(id),
  issuer       TEXT NOT NULL,   -- OIDC iss, or a synthetic id for DB auth (e.g. 'db')
  external_id  TEXT NOT NULL,   -- tenant-level key per issuer (realm / hd), or username for DB auth
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (issuer, external_id)
);
```
- **OIDC issuers:** one row per tenant - `external_id` is the tenant-level value extracted from the token (Keycloak realm, Google `hd`). All users of that realm/domain resolve to the same tenant.
- **DB auth:** one row per user - `issuer = 'db'`, `external_id = username`. This keeps `slkstate_user` untouched (no tenant column added); the tenant linkage for DB-auth users lives here.

This table is an **allowlist**: an identity whose `(issuer, external_id)` is absent is rejected, never auto-provisioned.

---

## 10. Phase 2 - Resolution flow

After the existing `AuthenticationService` validates the credential:

1. **Derive the external tenant key** per issuer (pluggable resolver keyed on `iss`):
    - Keycloak (`iss = .../realms/<realm>`) → realm segment.
    - Google (`iss = https://accounts.google.com`) → `hd` claim (reject when absent, e.g. personal accounts).
    - DB auth → `username`.
    - (Pluggable hook for Azure `tid`, Cognito pool id, GCIP `firebase.tenant` - not implemented in this slice.)
2. **Look up** `(issuer, external_id)` in `qodstate_tenant_identity`. Miss → reject.
3. **Select the environment** from the `x-quack-env` Flight call header; validate it exists and belongs to the resolved tenant; absent → the tenant's `default` environment. Resolve env **once at connect**, not per RPC (the header may not be present on every RPC), and bind it to the session.
4. **Bind to the session** (`AuthenticatedProfile`): resolved `tenantId` + selected `environmentId` (+ existing principal `user:<name>`).
5. **Feed downstream:** `PoolSupervisor` resolves the pool + metastore from the environment; `PostgresAclValidator` reads `aclTenant` from the resolved tenant and `defaultDatabase`/`defaultSchema` from the environment's DuckLake - replacing the `"default"` constant.

Tenant is authoritative from the verified identity; environment is client-selected but authorization-checked against that tenant. A connection value may select among allowed things, never grant access.

---

## 11. Phase 2 - Affected code
- `qodstate_tenant_identity` store (CRUD) + a migration (`0002`).
- A `TenantResolver` with per-issuer extraction (Keycloak realm, Google `hd`, DB username) + allowlist lookup; pluggable for other issuers.
- `AuthenticationService` / `AuthenticatedProfile` - attach resolved `tenantId` + `environmentId` to the session after auth.
- Connection handling - read and authorize `x-quack-env` at connect, bind to session.
- `PoolSupervisor` + `PostgresAclValidator` - consume the resolved `(tenant, environment)` instead of `"default"` / session config.
- Minimal REST/UI to manage `qodstate_tenant_identity` rows (create/list/delete mappings).
- **Still untouched:** `slkstate_user` (tenant linkage for DB auth lives in `qodstate_tenant_identity`), `slkstate_acl_grant`, RLS, secret storage.

---

## 12. Testing requirements

**Phase 1**
- Migration golden-file tests (single tenant/no env; pools sharing a metastore; a pool with a divergent metastore → its own environment), both backends.
- Repository CRUD + idempotent upsert; parent delete applies the chosen cascade.
- Supervisor/reconciler: desired-vs-observed drives node create/respawn/removal via per-entity upserts; a pool-size edit and a concurrent reconcile do not clobber each other.
- Back-compat: an existing single-tenant deployment behaves identically after migration.
- Deletion semantics: disabling a tenant/environment excludes it from resolution and drains its nodes while retaining rows; deleting a pool stops its nodes and removes node rows before the pool row; a `RESTRICT` FK rejects an out-of-order hard delete.

**Phase 2**
- Resolver: Keycloak `iss` → realm → tenant; Google token with `hd` → tenant; Google token without `hd` → reject; DB user → tenant; unknown `(issuer, external_id)` → reject.
- Env selection: valid `x-quack-env` for the tenant → bound; env of another tenant or nonexistent → reject; absent → `default`.
- End-to-end: two identities resolving to different tenants reach different environments/pools/metastores; the `"default"` constant is no longer consulted.

---

## 13. Acceptance criteria
1. Tenants, environments, pools, nodes are stored relationally with FKs (Postgres) / a normalized document (file), per §4.
2. Environment owns metastore + data path; pool no longer carries its own metastore; resolution reads from the environment.
3. Pool (desired) and node (runtime) are separate; the reconciler writes node state via per-entity upserts, never a whole-topology rewrite.
4. Migration framework + one-time data migration convert existing blobs losslessly for both backends; an existing single-tenant deployment migrates to one `default` environment and behaves identically.
5. `qodstate_tenant_identity` resolves a verified identity to a tenant (Keycloak realm, Google `hd`, DB username) as an allowlist; unknown identities are rejected.
6. `x-quack-env` selects an environment, authorization-checked against the resolved tenant and bound at connect; the supervisor and validator consume the resolved `(tenant, environment)` instead of `"default"`.
7. Deferred items untouched: no secret-handling change, no RLS / `env_id`-on-grants, no group/role ACL, `slkstate_user`/`slkstate_acl_grant` unchanged.
8. Existing tests pass; new tests per §12 added.

---

## 14. Task breakdown (ordered)

**Phase 1**
1. Migration framework (`qodstate_schema_version` + runner) + migration `0001` (schema §4.2).
2. `Environment`/`Pool` domain types; evolve `PoolKey` (option A, legacy parse).
3. `ControlPlaneStore` for Postgres (per-row upserts) and file (normalized document); keep old `StateStore` for migration only.
4. One-time data migration (§6.2), both backends, with distinct-metastore→environment derivation.
5. Refactor `PoolSupervisor` + reconciler to the graph + per-entity upserts; metastore from environment.
6. REST/DTOs/handlers + admin UI: introduce the environment level (with `default`-env compatibility).
7. Remove the old `StoredState`/`slkstate_pool_state` write path (retain renamed for one release).
8. Phase 1 tests (§12).

**Phase 2**
9. `qodstate_tenant_identity` table + migration `0002` + CRUD store.
10. `TenantResolver` (Keycloak realm, Google `hd`, DB username; pluggable hook) + allowlist lookup.
11. Attach resolved `(tenantId, environmentId)` to `AuthenticatedProfile`; read/authorize `x-quack-env` at connect.
12. Replace the `"default"` constant: feed `(tenant, environment)` into `PoolSupervisor` + `PostgresAclValidator`.
13. Minimal REST/UI to manage identity mappings.
14. Phase 2 tests (§12).

---

## 15. Open questions / decisions
Decided (see body): `PoolKey` shape (§5), deletion/lifecycle semantics (§4.4).

Remaining:
- **Dedicated `qodstate` Postgres schema** vs `public` (optional, configurable, default-compatible).
- **Migrated divergent-metastore environment naming** (`env-1`, …): deterministic derived name; document it.
- **Phase 2 provider set:** Keycloak + Google confirmed as the concrete implementations; confirm whether any of Azure / Cognito / GCIP are needed now or left to the pluggable hook.
