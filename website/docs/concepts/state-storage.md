---
id: state-storage
title: State storage
---

The manager keeps its control-plane state (tenants, databases, pools, nodes, and the RBAC graph) in one of two backends, selected by `quack-on-demand.stateStorage` (`QOD_STATE_STORAGE`). The default is `postgres`. This is distinct from where tenant *data* lives, which is each tenant-db's DuckLake catalog; see [DuckLake catalogs](/concepts/catalogs).

## `postgres` (default)

The control plane is a set of normalized tables with the `qodstate_` prefix, managed by Liquibase, living in a dedicated control-plane database (`qod` by default):

- **Registry:** `qodstate_tenant`, `qodstate_tenant_db`, `qodstate_pool`, `qodstate_node`.
- **RBAC graph:** `qodstate_user`, `qodstate_role`, `qodstate_role_permission`, `qodstate_group`, and the edge tables (`qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`), plus `qodstate_pool_permission`.

Several features are mounted **only** in this mode because they depend on these tables:

- Admin users are seeded at startup (the DB auth backend needs at least one credential).
- The RBAC REST endpoints (users, roles, groups, grants) and the per-statement ACL validator.
- The DuckLake catalog browser, which reads the `ducklake_*` tables.

Liquibase applies the changelog at boot (idempotent: already-applied changesets are skipped), so the schema is created and migrated automatically.

## `file` (legacy)

The `file` backend stores the entire control plane as a single JSON blob (`StoredState`). The blob is written whole on every change and read whole at boot. It is backed either by a file at `statePath`, or by a single-row JSONB table `slkstate_pool_state` (keyed on `id = 1`) when stored in Postgres.

This mode has **no** Liquibase, **no** admin seeding, **no** RBAC REST endpoints, and **no** catalog browser. It is useful for immutable deployments where the whole configuration ships as one artifact and is never mutated at runtime.

## Table prefixes

The `qodstate_` (normalized) and `slkstate_` (blob) prefixes keep control-plane tables from colliding with DuckLake's `ducklake_*` / `__ducklake_*` namespace. Those DuckLake tables live inside each managed tenant-db, never in the control-plane database, so the two never share a schema.

For the environment variables and how to point the control plane at a specific Postgres, see the [Configuration reference](/reference/configuration) and [Tenants and databases](/operating/tenants-databases).
