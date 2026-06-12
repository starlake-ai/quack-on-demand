# Access Control List (ACL) Model

Statement-level authorization in Quack on Demand is RBAC: users → roles → table permissions, with optional group membership and pool-access grants. The graph lives in the control-plane Postgres database with the `qodstate_*` prefix and is enforced per-statement at the FlightSQL edge.

## Schema (`qodstate_*` tables)

| Table | What it holds |
|---|---|
| `qodstate_user` | One row per principal. `tenant IS NULL` is a superuser. |
| `qodstate_role` | Per-tenant named bundle of table permissions. |
| `qodstate_role_permission` | A `(catalog, schema, table, verb)` grant attached to a role. Wildcards: any of the three name columns can be `*`. |
| `qodstate_group` | Per-tenant named container. |
| `qodstate_group_role` | Group → role edge. |
| `qodstate_user_role` | User → role direct edge. |
| `qodstate_user_group` | User → group edge. |
| `qodstate_pool_permission` | Pool-access grant attached to a user OR a group. `pool_id IS NULL` matches every pool in the tenant. |

The collapsed verb set is `RO | RW | DDL | ALL`. The validator translates each parsed statement into per-table `(verb, …)` access tuples and checks them against the user's EffectiveSet via a `verbCovers` helper.

## Enforcement

When `acl.enabled=true`, every statement is validated:

1. `PostgresAclValidator` parses each incoming SQL statement.
2. It extracts all referenced table and view names.
3. It looks up the EffectiveSet for `(tenant, user)` — already cached on the FlightSQL `ConnectionContext` at handshake time, AND cached in the `PoolSupervisor` with a 60s TTL keyed by `(userId, jwtRoles.hashCode, jwtGroups.hashCode)`.
4. Each parsed statement is reduced to a set of `TableAccess(table, verb)` tuples with `verb` in `Read | Write | Ddl`. A grant of `verb=ALL` covers any access verb; granular grants map as follows:

   | Statement                                  | Emitted accesses                                                |
   |--------------------------------------------|-----------------------------------------------------------------|
   | `SELECT ...`                               | `Read` on every referenced table (incl. CTE / subquery sources) |
   | `WITH cte AS (...) SELECT ...`             | `Read` on the CTE source(s); CTE self-references excluded       |
   | `FROM t [pipe ops...]` (DuckDB/BigQuery)   | `Read` on the FROM target + JOINs                               |
   | `INSERT INTO t VALUES ...`                 | `Write` on `t`                                                  |
   | `INSERT INTO t SELECT FROM s`              | `Write` on `t` + `Read` on `s`                                  |
   | `UPDATE t SET ... [FROM b] WHERE ...`      | `Write` on `t` + `Read` on FROM/JOIN/WHERE subquery tables      |
   | `DELETE FROM t [USING s] WHERE ...`        | `Write` on `t` + `Read` on USING / WHERE subquery tables        |
   | `MERGE INTO t USING s ON ...`              | `Write` on `t` + `Read` on `s` (USING)                          |
   | `CREATE TABLE t [AS SELECT FROM s]`        | `Ddl` on `t` (+ `Read` on `s` if CTAS)                          |
   | `CREATE VIEW v AS SELECT FROM s`           | `Ddl` on `v` + `Read` on `s`                                    |
   | `DROP TABLE t` / `DROP SCHEMA s`           | `Ddl` on the named entity                                       |
   | `ALTER TABLE t ...`                        | `Ddl` on `t` (FK references not walked)                         |
   | `TRUNCATE TABLE t`                         | `Write` on `t` (mass-delete semantics)                          |
   | `COMMIT` / `ROLLBACK` / `SET` / `SHOW` / … | no accesses; admitted unconditionally                           |

   Granular role-permission verbs collapse into the access space as follows: `SELECT` → `Read`; `INSERT` / `UPDATE` / `DELETE` / `TRUNCATE` → `Write`; `CREATE` / `DROP` / `ALTER` → `Ddl`; `ALL` covers everything. The validator denies any access without a matching grant.

   Superusers (`qodstate_user.tenant IS NULL`) bypass the per-statement check.

## EffectiveSet closure

```
effective_roles(U)  = direct_roles(U)  ∪  ⋃ roles(g)   for g ∈ groups(U)
effective_pools(U)  = direct_pools(U)  ∪  ⋃ pools(g)   for g ∈ groups(U)
                      (pool_id IS NULL matches every pool in the tenant)
effective_perms(U)  = ⋃ permissions(r)                  for r ∈ effective_roles(U)
```

The schema-bounded slice (roles, groups, role permissions, group-role edges, group-scoped pool perms) lives in `RbacResolver` (in-memory, bounded by tenant cardinality not user count). User-bound state is fetched fresh on every handshake from Postgres so revocations land instantly. Every supervisor RBAC mutator (`createRole`, `addUserRole`, `grantPoolPermission`, …) calls `invalidateEffectiveCache()` so a fresh grant takes effect on the next handshake without waiting on the 60s TTL.

## Managing grants

All grants are managed via the REST API or the Admin UI (Tenant detail page → RBAC editor). Each call is gated by `TenantScopeCheck` — a tenant-A admin session cannot touch a tenant-B role / group / user / permission. See [API.md](API.md) for the endpoint list.

Example: grant SELECT on `tpch.tpch1.customer` to role `analyst`:

```bash
curl -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/role/permission/grant \
  -H 'Content-Type: application/json' \
  -d '{"roleId":"r-...","catalog":"tpch","schema":"tpch1","table":"customer","verb":"RO"}'
```
