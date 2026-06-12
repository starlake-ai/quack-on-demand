---
id: state-storage
title: State storage
---

The manager keeps its control-plane state (tenants, databases, pools, nodes, the RBAC graph, and federation registry) in a dedicated Postgres database. This is distinct from where tenant *data* lives, which is each tenant-db's DuckLake catalog; see [DuckLake catalogs](/concepts/catalogs).

## Postgres control-plane database

The control plane is a set of normalized tables with the `qodstate_` prefix, managed by Liquibase, living in a dedicated control-plane database (`qod` by default):

- **Registry:** `qodstate_tenant`, `qodstate_tenant_db`, `qodstate_pool`, `qodstate_node`.
- **RBAC graph:** `qodstate_user`, `qodstate_role`, `qodstate_role_permission`, `qodstate_group`, and the edge tables (`qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`), plus `qodstate_pool_permission`.
- **Federation:** `qodstate_federated_source`, `qodstate_federated_secret`.

Liquibase applies the changelog at boot (idempotent: already-applied changesets are skipped), so the schema is created and migrated automatically.

Connections are pooled via HikariCP (size 20 by default on the control-plane store, 10 on `UserStore`). The manager's shutdown hook drains both pools before JVM exit. The legacy file-backed store (single-JSON blob at `statePath`, gated by `stateStorage = file`) was dropped along with the `stateStorage` and `statePath` config keys; only the Postgres path is wired today.

## Table prefixes

The `qodstate_` prefix keeps control-plane tables from colliding with DuckLake's `ducklake_*` / `__ducklake_*` namespace. Those DuckLake tables live inside each managed tenant-db, never in the control-plane database, so the two never share a schema.

For the environment variables and how to point the control plane at a specific Postgres, see the [Configuration reference](/reference/configuration) and [Tenants and databases](/operating/tenants-databases).
