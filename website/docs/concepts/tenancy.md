---
id: tenancy
title: Tenancy model
---

Quack on Demand is multi-tenant at three nested levels. This page describes those levels and how isolation between tenants is enforced. The provisioning steps are on [Tenants and databases](/operating/tenants-databases) and [Pools and cohorts](/operating/pools-cohorts); the access-control mechanics are in the [Access control model](/operating/rbac-model).

## The three levels

```
tenant  ──owns──▶  tenant-db (a database)  ──hosts──▶  pool  ──contains──▶  nodes
```

- A **tenant** is the isolation boundary. It owns databases and users, and selects the auth provider its users authenticate against (a tenant attribute, so different tenants can use different providers).
- A **tenant-db** is a database: a separate Postgres database holding its own DuckLake catalog and its own Parquet data path. See [DuckLake catalogs](/concepts/catalogs).
- A **pool** is a set of Quack nodes bound to exactly one tenant-db. Its full identity is the `(tenant, tenantDb, pool)` triple.

A client connects to a pool by supplying a tenant and pool name; the gateway resolves the tenant-db server-side. The client never names the database directly.

## How tenants are isolated

Isolation is enforced at three layers, not by a single guard:

1. **Physical separation.** Each tenant-db is its own Postgres database with its own DuckLake catalog and its own data path. Two tenants' data never shares a database or a catalog, so there is no path for one tenant's query to reach another's storage.
2. **Connection (pool-access gate).** At handshake the gateway resolves the requested pool to its tenant-db and checks that the principal's effective pools include it. A principal with no pool grant reaching a tenant-db cannot open a session against it at all.
3. **Statement (ACL catalog scoping).** The per-statement ACL catalog `*` wildcard is scoped to the session's tenant: a tenant admin holding `*.*.* ALL` matches only catalogs in their own tenant, never a sibling tenant's. Reaching another tenant's catalog requires naming it literally, which is the deliberate cross-tenant grant path.

Together these mean a tenant-scoped principal is confined to their tenant's databases by storage layout, by who may connect, and by what statements are authorized.

## Superusers

A superuser (`qodstate_user.tenant IS NULL`) is the one principal that is not tenant-scoped. Superusers bypass both the pool-access gate and the per-statement ACL gate, so they can connect to any pool in any tenant and run any statement. The bootstrap admin is a superuser, and only superusers can create other superusers. Manifest export and import, which span every tenant, are superuser-only for the same reason; see [Manifest backup and restore](/operating/manifest).

## What scopes to a tenant

| Object | Scope |
|---|---|
| Databases (tenant-dbs), pools | Belong to one tenant |
| Users, roles, groups, grants | Tenant-scoped (except superusers) |
| Auth provider | A tenant attribute |
| Catalog `*` wildcard in a grant | The session's tenant only |
| Superuser | Cross-tenant |
