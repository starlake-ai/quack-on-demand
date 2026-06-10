---
id: rbac-model
title: Access control model
---

This page describes the role-based access control (RBAC) model enforced by quack-on-demand at the FlightSQL edge. It covers the data model, how effective permissions are computed at connection time, what the per-statement gate checks, and where superusers fit. For step-by-step instructions on creating users, roles, groups, and grants, see the "Administering access" page.

ACL enforcement is disabled by default. Set `QOD_ACL_ENABLED=true` to enforce it. The rest of this page assumes enforcement is on.

## Entities

The RBAC graph is stored in the control-plane Postgres database (default `qod`) using tables with the `qodstate_` prefix.

### Core objects

| Table | What it holds |
|---|---|
| `qodstate_user` | One row per principal. `tenant IS NULL` marks a superuser. Tenant-scoped users carry a non-null `tenant` value. |
| `qodstate_role` | Named, per-tenant bundles of table permissions. |
| `qodstate_group` | Named, per-tenant containers that collect roles and pool grants. |
| `qodstate_role_permission` | Table-level grants attached to a role: a verb on a `catalog.schema.table` triple. |
| `qodstate_pool_permission` | Pool-access grants attached to a user or a group. A null `pool_id` matches every pool in the tenant. |

### Membership edges

| Table | Relationship |
|---|---|
| `qodstate_user_role` | Direct user-to-role assignment. |
| `qodstate_user_group` | User membership in a group. |
| `qodstate_group_role` | Role assignment to a group. |

A user therefore reaches table permissions through two paths: directly via `qodstate_user_role`, or indirectly via any group they belong to and that group's `qodstate_group_role` edges.

## The EffectiveSet

At handshake time the gateway computes a closed permission set for the connecting user and pins it on the `ConnectionContext` (keyed by peer ID). Every subsequent statement on that connection reads this cached snapshot without any further database queries.

The closure is defined as:

```
effective_roles(U)  = direct_roles(U)  ∪  ⋃ roles(g)   for g ∈ groups(U)
effective_pools(U)  = direct_pools(U)  ∪  ⋃ pools(g)   for g ∈ groups(U)
                      (pool_id IS NULL matches every pool in the tenant)
effective_perms(U)  = ⋃ permissions(r)                  for r ∈ effective_roles(U)
```

Where:
- `direct_roles(U)` are the `qodstate_user_role` rows for the user (fetched from Postgres at handshake time).
- `groups(U)` are the `qodstate_user_group` rows for the user (also fetched at handshake time).
- `roles(g)` are resolved from the in-memory `RbacResolver` cache, which mirrors all `qodstate_group_role` edges for the tenant without loading user-bound state.
- `permissions(r)` are the `qodstate_role_permission` rows for each effective role, also served from the in-memory cache.

The `RbacResolver` holds the schema-bounded slice of the graph (roles, groups, role permissions, group-role edges, group-scoped pool permissions). Its memory footprint is bounded by tenant cardinality, not by the number of users. User-bound state (user-role and user-group edges) is intentionally excluded from the cache and fetched per-handshake so user revocations take effect on the next connection.

The handshake produces an `AuthorizedHandshake` that carries the resolved `PoolKey`, the matched user row, and the full `EffectiveSet`. The edge server stores this on the connection and reads it for every subsequent ACL gate evaluation.

## Superusers

A user with `tenant IS NULL` in `qodstate_user` is a superuser. Superusers bypass both gates:

- The pool-access gate at handshake (they may connect to any pool in any tenant).
- The per-statement ACL gate (every statement is allowed without checking `effective_perms`).

The superuser flag is purely the absence of a tenant value. The bootstrap admin seeded at startup is a superuser. Only superusers can create other superusers; the API enforces this constraint at the application layer.

## Table permissions

Each `qodstate_role_permission` row grants one verb on one table triple to a role.

### Verbs

| Verb | Covers |
|---|---|
| `SELECT` | Read queries (SELECT, WITH, VALUES, SHOW, DESCRIBE, EXPLAIN). |
| `INSERT` | Insert statements (INSERT). |
| `UPDATE` | Update statements (UPDATE). |
| `DELETE` | Delete statements (DELETE, TRUNCATE in coarse mode). |
| `ALL` | All of the above, including DDL. |

### Wildcards

Each of the three name parts (catalog, schema, table) may be the literal string `*`, which matches any value in that position. For example, a permission with `catalogName=*`, `schemaName=*`, `tableName=*`, `verb=SELECT` grants read access to every table the user can name.

Matching is case-insensitive for literal values and exact for `*`.

### How a grant covers a table reference

A resolved `TableRef` from the SQL parser is covered by a permission row `p` when all three conditions hold:

1. `p.verb` equals the required verb or `ALL` (case-insensitive).
2. `p.catalogName` is `*` or matches the table's catalog name.
3. `p.schemaName` is `*` or matches the table's schema name.
4. `p.tableName` is `*` or matches the table's table name.

If any extracted table reference is not covered, the statement is denied.

## Statement coverage

The ACL gate applies different treatment depending on statement kind.

### SELECT statements

`SELECT`, `WITH`, `VALUES`, `SHOW`, `DESCRIBE`, and `EXPLAIN` are classified as read queries. The SQL parser (JSqlParser) walks the full AST: FROM clauses, JOINs, subqueries in WHERE/SELECT/HAVING, set operations (UNION/INTERSECT/EXCEPT), and CTE bodies. Every resolved table reference is checked against `effective_perms`. Unqualified table names are filled in using the pool's default catalog and schema before the check.

If any table in the statement is not covered by a `SELECT` or `ALL` grant, the statement is denied.

### DML statements (current behavior)

`INSERT`, `UPDATE`, `DELETE`, `MERGE`, `UPSERT`, `REPLACE`, and `COPY` are classified as DML. The current table extractor only walks `SELECT` AST nodes. DML statements return no extracted table references, so the per-table check finds nothing to deny and passes. In practice, DML is currently allowed for any authenticated tenant-scoped user regardless of their grants.

This is a known limitation. The documented operator path for production use is to grant `*.*.* ALL` to roles that need write access, which makes the intent explicit even before fine-grained DML extraction is added.

### DDL and session-level statements

`CREATE`, `DROP`, `ALTER`, `TRUNCATE` (when classified as DDL), `ATTACH`, `DETACH`, `COMMENT`, `GRANT`, and `REVOKE` are classified as DDL. `BEGIN`, `START`, `COMMIT`, `END`, `ROLLBACK`, and `ABORT` are classified as session-control kinds. All of these fall into the same branch in the ACL gate: the table extractor does not enumerate their targets, so the gate falls back to a wildcard check. The statement is allowed only if the user's `effective_perms` contains at least one row with `verb=ALL` and all three name parts set to `*`. Any other principal is denied.

Note: a comment in the source code describes BEGIN/COMMIT/ROLLBACK as short-circuiting to Allowed before the extraction step, but the current code routes them through the same wildcard-ALL branch as DDL. In practice, users that need to issue explicit transaction control statements must hold a `*.*.* ALL` grant, or run with `acl.enabled=false`.

## The two gates

Every FlightSQL request passes through two sequential gates.

### Gate 1: handshake (pool-access)

When a client opens a FlightSQL connection it supplies a tenant and pool name (via gRPC headers or JWT claims). The handshake:

1. Authenticates the credential (database bcrypt check, JWT validation, or OIDC; see the authentication page for details).
2. Resolves the user in `qodstate_user`.
3. Checks that the user's `effective_pools` covers the requested pool (or that `pool_id IS NULL` grants tenant-wide access).
4. Computes and caches the `EffectiveSet` on the `ConnectionContext`.

If the user's effective pools do not include the requested pool, the handshake is rejected before any SQL is processed. Superusers skip step 3.

### Gate 2: per-statement (ACL)

For every SQL statement sent over an established connection, `PostgresAclValidator` reads the `EffectiveSet` cached at handshake time and applies the rules described in the "Statement coverage" section above. No Postgres round-trip is made per statement.

### The `acl.enabled` switch

Both gates are always active for pool access. The per-statement ACL gate is guarded by:

```
quack-flightsql.acl.enabled = false   # default
```

Environment variable: `QOD_ACL_ENABLED=true`.

When `acl.enabled` is false, an allow-all validator replaces `PostgresAclValidator` and all statements pass gate 2 without table-ref checking. Pool-access checking (gate 1) is not affected by this flag.

The SQL parser dialect is configured by:

```
quack-flightsql.acl.dialect = "duckdb"   # default, only option wired today
```

Environment variable: `QOD_ACL_DIALECT`.

## Privilege-escalation guards

The following invariants are enforced at the API layer, not by the database schema alone:

- Only a superuser (`tenant IS NULL`) can create another superuser. A tenant-scoped admin attempting to create a user with `tenant IS NULL` is rejected.
- When granting a role permission, the granting principal must themselves hold that permission (or a covering wildcard) in their own effective set. A tenant-scoped admin cannot grant more than they hold.
- These checks apply to every mutation path: REST API, and any admin seeding at startup.

Superusers are not subject to these guards because they bypass the effective-set check entirely.
