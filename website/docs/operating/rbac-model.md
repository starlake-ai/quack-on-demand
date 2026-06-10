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

The SQL parser collapses every table touch into one of three access classes - `Read`, `Write`, `Ddl` - and a grant verb is matched against that class:

| Grant verb | Covers (access class) |
|---|---|
| `SELECT` | `Read` (SELECT, and the read side of subqueries / CTEs / `INSERT ... SELECT`). |
| `INSERT` / `UPDATE` / `DELETE` | `Write`. These are not distinguished today: holding any one authorizes all writes (INSERT, UPDATE, DELETE, MERGE, UPSERT, TRUNCATE) on the matched tables. |
| `ALL` | `Read`, `Write`, and `Ddl` (CREATE / DROP / ALTER) on the matched tables. |

Granular DDL verbs (`CREATE` / `DROP` / `ALTER`) are recognized by the matcher if present, but the operator-facing grant vocabulary is `SELECT` / `INSERT` / `UPDATE` / `DELETE` / `ALL`, so in practice DDL is authorized by an `ALL` grant.

### Wildcards

Each of the three name parts (catalog, schema, table) may be the literal string `*`, which matches any value in that position. For example, a permission with `catalogName=*`, `schemaName=*`, `tableName=*`, `verb=SELECT` grants read access to every table the user can name.

Matching is case-insensitive for literal values and exact for `*`.

The catalog `*` wildcard is scoped to the session's tenant: it matches only catalogs that belong to the connecting principal's tenant. A tenant admin holding `*.*.* ALL` therefore cannot reach a sibling tenant's catalog. To grant cross-tenant access deliberately, name the other catalog explicitly (for example `otherdb.*.* ALL`), which bypasses the tenant-scoped wildcard via the literal-match path.

### How a grant covers a table access

For each statement the SQL parser produces a set of accesses, each a `(table, class)` pair where `class` is `Read`, `Write`, or `Ddl`. An access is covered by a permission row `p` when all of these hold:

1. `p.verb` covers the access class (see the Verbs table: `ALL` covers any class; `SELECT` covers `Read`; `INSERT`/`UPDATE`/`DELETE` cover `Write`; `CREATE`/`DROP`/`ALTER` cover `Ddl`).
2. `p.catalogName` matches the access catalog (literal match, or the tenant-scoped `*` wildcard described above).
3. `p.schemaName` is `*` or matches the access schema.
4. `p.tableName` is `*` or matches the access table.

If any access in the statement is not covered, the whole statement is denied.

## Statement coverage

The parser extracts table accesses from each statement and the gate checks every one. Unqualified table names are filled in from the pool's default catalog and schema before the check.

### Read queries (SELECT)

`SELECT`, `WITH`, and `VALUES` are walked across the full AST: FROM clauses, JOINs, subqueries in WHERE/SELECT/HAVING, set operations (UNION/INTERSECT/EXCEPT), and CTE bodies. Every table is a `Read` access and must be covered by a `SELECT` or `ALL` grant.

### Writes (INSERT / UPDATE / DELETE / MERGE / TRUNCATE)

The write target is extracted as a `Write` access; any read source (for example the `SELECT` in `INSERT ... SELECT`, or an `UPDATE ... FROM` source) is extracted as a `Read` access. The target must be covered by an `INSERT`/`UPDATE`/`DELETE` (or `ALL`) grant and each source by a `SELECT` (or `ALL`) grant. Because the write verbs collapse to a single `Write` class, granting any one of `INSERT`/`UPDATE`/`DELETE` authorizes all writes on the matched tables.

### DDL (CREATE / DROP / ALTER)

The DDL target is extracted as a `Ddl` access; a `CREATE TABLE ... AS SELECT` also yields a `Read` access for its source. A `Ddl` access is covered by an `ALL` grant (the operator-facing grant vocabulary does not expose a dedicated DDL verb).

### Control-flow statements

`BEGIN`, `START`, `COMMIT`, `END`, `ROLLBACK`, `ABORT`, `SET`, `USE`, `SHOW`, and `EXPLAIN` (without an inner DML) carry no table references and are admitted unconditionally - they do not require any grant.

### Unparseable statements

If a statement fails to parse, it is denied unless the principal holds a `*.*.* ALL` grant (a conservative fallback so an unrecognized statement cannot slip through).

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
