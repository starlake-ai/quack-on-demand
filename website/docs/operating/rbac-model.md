---
id: rbac-model
title: Access control model
---

This page describes the role-based access control (RBAC) model enforced by quack-on-demand at the FlightSQL edge. It covers the data model, how effective permissions are computed at connection time, what the per-statement gate checks, and where superusers fit. For step-by-step instructions on creating users, roles, groups, and grants, see the "Administering access" page.

ACL enforcement is disabled by default. Set `QOD_ACL_ENABLED=true` to enforce it. The rest of this page assumes enforcement is on.

## Pools and databases

Access to a database is granted through pools, so it helps to be precise about how the two relate before looking at table permissions.

- A **tenant** owns one or more **tenant-dbs**. A tenant-db *is* a database: in the default `ducklake` kind it is a Postgres database holding a DuckLake catalog, with a catalog name and a default schema.
- A **pool** is a set of DuckDB Quack nodes and is bound to exactly one tenant-db (`qodstate_pool.tenant_db_id`). Every node in the pool opens that tenant-db's catalog. A pool's full identity is the triple `(tenant, tenantDb, pool)`, the `PoolKey`.
- A client handshake supplies only a `tenant` and a `pool` (via gRPC headers / URL params). The gateway resolves which tenant-db that pool belongs to server-side; the client never names the database directly. JWT claims are never trusted for routing — the URL is authoritative.

The consequence is the part that is easy to miss: **admission to a pool is admission to that pool's database.** The pool-access check (gate 1 below) is therefore the database-access gate. Granting a user or group a `qodstate_pool_permission` row for pool `p` is what lets them open a session against `p`'s tenant-db; a principal with no pool permission reaching a tenant-db cannot query that database at all, regardless of any table grants they hold. Once admitted, the tenant-db's catalog name and default schema become the session's defaults, which the per-statement gate uses to qualify unqualified table names.

Table permissions (the rest of this page) then decide *which tables inside the reachable databases* a statement may touch. The two are independent: pool access decides the database; table permissions decide the tables.

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

The full `EffectiveSet` is also cached in `PoolSupervisor` with a 60s TTL keyed by `(userId, jwtRoles.hashCode, jwtGroups.hashCode)` — under load the same user + claims tuple repeats every few seconds, so the cache collapses N handshakes' worth of work into one. Every RBAC mutator (`createRole`, `grantRolePermission`, `addUserRole`, `addGroupRole`, `grantPoolPermission`, etc.) calls `invalidateEffectiveCache()`, so a freshly-granted role takes effect on the next handshake regardless of the TTL.

The handshake produces an `AuthorizedHandshake` that carries the resolved `PoolKey`, the matched user row, and the full `EffectiveSet`. The edge server stores this on the connection and reads it for every subsequent ACL gate evaluation.

## Superusers

A user with `tenant IS NULL` in `qodstate_user` is a superuser. Superusers bypass both gates:

- The pool-access gate at handshake (they may connect to any pool in any tenant).
- The per-statement ACL gate (every statement is allowed without checking `effective_perms`).

The superuser flag is purely the absence of a tenant value. The bootstrap admin seeded at startup is a superuser. Only superusers can create other superusers; the API enforces this constraint at the application layer.

A superuser logs in via the system auth realm: on the UI the **Tenant ID** field is left blank; on FlightSQL the URL carries `?superuser=true` alongside the routing `tenant=X&pool=Y` params. The tenant-realm path looks up `qodstate_user WHERE tenant = ?` and never matches a `tenant IS NULL` row, so a superuser cannot accidentally be authenticated through the tenant realm. See [Authentication](/operating/authentication) for the realm-selection mechanics.

## Table permissions

Each `qodstate_role_permission` row grants one verb on one table triple to a role.

### Verbs

The SQL parser collapses every table touch into one of three access classes - `Read`, `Write`, `Ddl` - and a grant verb is matched against that class. The grant vocabulary is the four values below; storing granular SQL verbs would be misleading because the validator only distinguishes these three classes anyway.

| Grant verb | Covers (access class) |
|---|---|
| `RO`  | `Read` only (SELECT, and the read side of subqueries / CTEs / `INSERT ... SELECT`). |
| `RW`  | `Read` and `Write` (any DML on the matched tables: INSERT, UPDATE, DELETE, MERGE, UPSERT, TRUNCATE). |
| `DDL` | `Ddl` only (CREATE / DROP / ALTER on the matched tables). |
| `ALL` | `Read`, `Write`, and `Ddl` on the matched tables. |

`RW` is the only multi-class grant: it bundles `Read` so that a data worker who can write to a table can also read it, which matches every real-world DML workload. DDL is intentionally separate because CREATE / DROP / ALTER are higher-privilege.

### Wildcards

Each of the three name parts (catalog, schema, table) may be the literal string `*`, which matches any value in that position. For example, a permission with `catalogName=*`, `schemaName=*`, `tableName=*`, `verb=RO` grants read access to every table the user can name.

Matching is case-insensitive for literal values and exact for `*`.

The catalog `*` wildcard is scoped to the session's tenant: it matches only catalogs that belong to the connecting principal's tenant. A tenant admin holding `*.*.* ALL` therefore cannot reach a sibling tenant's catalog. To grant cross-tenant access deliberately, name the other catalog explicitly (for example `otherdb.*.* ALL`), which bypasses the tenant-scoped wildcard via the literal-match path.

### How a grant covers a table access

For each statement the SQL parser produces a set of accesses, each a `(table, class)` pair where `class` is `Read`, `Write`, or `Ddl`. An access is covered by a permission row `p` when all of these hold:

1. `p.verb` covers the access class (see the Verbs table: `ALL` covers any class; `RO` covers `Read`; `RW` covers `Read` and `Write`; `DDL` covers `Ddl`).
2. `p.catalogName` matches the access catalog (literal match, or the tenant-scoped `*` wildcard described above).
3. `p.schemaName` is `*` or matches the access schema.
4. `p.tableName` is `*` or matches the access table.

If any access in the statement is not covered, the whole statement is denied.

## Statement coverage

The parser extracts table accesses from each statement and the gate checks every one. Unqualified table names are filled in from the pool's default catalog and schema before the check.

### Read queries (SELECT)

`SELECT`, `WITH`, and `VALUES` are walked across the full AST: FROM clauses, JOINs, subqueries in WHERE/SELECT/HAVING, set operations (UNION/INTERSECT/EXCEPT), and CTE bodies. Every table is a `Read` access and must be covered by an `RO`, `RW`, or `ALL` grant.

### Writes (INSERT / UPDATE / DELETE / MERGE / TRUNCATE)

The write target is extracted as a `Write` access; any read source (for example the `SELECT` in `INSERT ... SELECT`, or an `UPDATE ... FROM` source) is extracted as a `Read` access. The target must be covered by an `RW` or `ALL` grant. Each source is a `Read` access and can be covered by `RO`, `RW`, or `ALL` on that source - holding `RW` on the target alone is not enough to read from a different source table.

### DDL (CREATE / DROP / ALTER)

The DDL target is extracted as a `Ddl` access; a `CREATE TABLE ... AS SELECT` also yields a `Read` access for its source. A `Ddl` access is covered by a `DDL` or `ALL` grant; the source still needs `RO`/`RW`/`ALL` independently.

### Control-flow statements

`BEGIN`, `START`, `COMMIT`, `END`, `ROLLBACK`, `ABORT`, `SET`, `USE`, `SHOW`, and `EXPLAIN` (without an inner DML) carry no table references and are admitted unconditionally - they do not require any grant.

### Unparseable statements

If a statement fails to parse, it is denied unless the principal holds a `*.*.* ALL` grant (a conservative fallback so an unrecognized statement cannot slip through).

## Column-level policies

The table-level ACL described above is "all-or-nothing" per table: once a role has `RO` on `customer`, every column on `customer` is readable. Many regulated workloads (GDPR, HIPAA, PCI) need finer control: a role may read `customer` but `c_ssn` is forbidden, or `c_email` is returned as `'***'` instead of the raw value. The column-policy system enforces these patterns by rewriting the user's SELECT before it reaches a Quack node.

### Data model

Policies live in `qodstate_role_column_policy` (one row per `(role, catalog, schema, table, column)`) and attach to roles only; users inherit them through the same role-membership graph as table permissions.

| Column | Meaning |
|---|---|
| `role_id`        | The role that carries the policy. |
| `catalog_name` / `schema_name` / `table_name` | The protected table. `*` wildcards work the same way as for table permissions, including tenant scoping on `catalog_name`. |
| `column_name`    | The protected column. **Always literal — `*` is rejected.** A wildcard column policy would mean "deny everything", which is already expressible via absence of a table-level grant. |
| `action`         | `deny` (refuse any query that references the column) or `mask` (return a transformed value). |
| `transform_sql`  | The transform expression. `NULL` for `deny`; a strict-containment-validated scalar SQL expression for `mask`. |

`UNIQUE (role_id, catalog_name, schema_name, table_name, column_name)` so updates replace in place. `ON DELETE CASCADE from qodstate_role` so dropping a role drops its policies.

### Where the rewriter sits in the pipeline

```
client SQL
   │
   ▼  table-level ACL (per the gates above)              ─── unchanged
   │     returns Allowed at the (role, table) level
   │
   ▼  ColumnPolicyRewriter                                ─── new
   │     - parses the SQL via JSqlParser
   │     - walks every PlainSelect reachable (projections,
   │       WHERE / HAVING, subqueries, CTE bodies, UNION arms)
   │     - expands `SELECT *` against the DuckLake column catalog
   │     - for each column reference, looks up a matching policy
   │         · `deny` → refuses the query (403)
   │         · `mask` → replaces the reference with `transform_sql`
   │
   ▼  adapter sends the rewritten SQL to the Quack node
```

The rewriter only runs for SELECT-shaped statements; DML and DDL pass through unchanged (the table-level ACL has already gated whether the user can write to the target). Statements that fail to parse are passed through unmodified — DuckDB returns the syntax error to the client, just as it does today.

Superusers bypass the rewriter, matching the bypass behaviour of the table-level ACL.

### Column-level security guarantees

The rewriter walks every projection-eligible JSqlParser node kind: direct column references,
CASE arms, CAST inner, IN list members, BETWEEN bounds, EXTRACT inner, window PARTITION BY and
ORDER BY expressions, LIKE patterns, and row constructors. Star (`*`) projections are expanded
against the DuckLake catalog before the policy walk, so multi-table joins and qualified `t.*`
both apply per-column policies. CTE, FROM-item subqueries, scalar subqueries in projection, and
UNION / INTERSECT / EXCEPT arms are all recursed into; the v1 bypass list is closed.

CTE and view indirection are resolved at the physical (table, column) level via jsqltranspiler's
lineage tree. A column projected through `WITH x AS (SELECT c_email FROM customer) SELECT c_email
FROM x` is masked according to the base table's policy. For FROM-item subqueries
(`SELECT c_email FROM (SELECT c_email FROM customer) sub`) the rewriter synthesises a transient
outer-scope policy keyed on `(subAlias, exposedName)` so the outer projection is masked too,
since jsqltranspiler's lineage stops at the FROM boundary.

### Unresolved-table behaviour

When the rewriter encounters a table the DuckLake catalog cannot enumerate (federated alias
the catalog does not expose, metadata fetch raced a DDL), the request falls back per
`QOD_CLS_UNRESOLVED_TABLE`:

- `pass` (default): forward the unrewritten SQL to DuckDB. Matches v1 behaviour.
- `deny`: refuse the statement with an "unknown table" error. Safer; recommended for
  production tenants where any unexpected SQL is suspicious.

### Catalog cache and schema evolution (known limitation)

`DuckLakeColumnCatalog` caches column-name lookups at a 5-second TTL. After a `CREATE / ALTER /
DROP TABLE` or column DDL, the rewriter sees a stale column list for up to 5 seconds. Concrete
race: a freshly-added column plus a freshly-added policy on it leaves a window where the policy
lookup runs but the resolver does not see the new column. In `pass` mode the unmasked SQL
reaches DuckDB during this window; in `deny` mode the statement is denied. Operators expecting
simultaneous DDL + RBAC changes should pin tenants to `deny` mode, or accept the 5-second
exposure on `pass`.

A future release will hook DDL classification into cache invalidation so the window closes to
under one statement; until then this is operator-visible.

### Action: deny

A `deny` policy refuses any query that references the protected column anywhere — projection, WHERE clause, HAVING, GROUP BY, ORDER BY, JOIN condition, subquery, CTE body — and short-circuits with a 403 carrying the column qualifier. `SELECT *` deny is fail-closed: if star expansion uncovers a denied column, the query is refused rather than silently dropping the column from the projection.

### Action: mask + transform_sql

A `mask` policy replaces every reference to the column with the operator-authored `transform_sql` expression. The replacement happens in place: composite expressions like `length(c_email)` become `length('***')`; predicates like `WHERE c_email LIKE '%@acme.com'` become `WHERE '***' LIKE '%@acme.com'` (which trivially matches no rows, the right behaviour for a masked column under predicate inference). Projection aliases are preserved (`SELECT c_email AS e FROM customer` stays aliased as `e`).

`transform_sql` is **strict-containment-validated** at policy create time. The validator runs once when the operator writes the policy and rejects expressions that would let the policy leak its own column:

1. The expression parses as a single scalar SQL expression.
2. Every column reference in the expression names only the protected column (case-insensitive). `concat(c_email, c_phone)` is rejected when the policy is on `c_email`.
3. No subqueries, `EXISTS`, or any other form of nested SELECT.
4. No side-effect or escape functions: the denylist includes `read_csv`, `read_parquet`, `read_json`, `attach`, `detach`, `install`, `load`, `system`, `current_setting`, `set_setting`, `pg_read_server_files`, `pg_read_binary_file`, and anything matching `pragma_*` (prefix).
5. Canonical form ≤ 1024 chars.

A failed validation returns `400 invalid_policy` with the violated rule named in the message. Typical valid expressions:

| `transform_sql` | Meaning |
|---|---|
| `'***'`                                           | constant string |
| `NULL`                                             | scrub to NULL |
| `md5(c_email)`                                     | hash |
| `concat('user_', md5(c_email))`                    | prefix + hash |
| `regexp_replace(c_phone, '\d', 'X')`               | character substitution |
| `concat('****', substr(c_phone, length(c_phone) - 3, 4))` | last-4 reveal |

### SELECT * expansion

When the user issues `SELECT *` (or `SELECT t.*`), the rewriter reads the column list from the DuckLake catalog and expands the star to a literal projection BEFORE applying policies. Operators see the same number of columns the table actually has, with masked columns rewritten in place. When the catalog cannot enumerate the table (federated source temporarily unreachable, etc.), the rewriter passes the original `*` through — table-level ACL still decided whether the user could read the table in the first place. A `column_policy_catalog_lookups_total{result=error}` metric surfaces this case for operators.

### Effective policies and caching

Column policies join the `RolePermission` and pool-permission rows in the per-user `EffectiveSet`. The same 60-second `PoolSupervisor` cache covers them, and the same mutator-invalidation contract applies: `createColumnPolicy`, `updateColumnPolicy`, and `deleteColumnPolicy` each call `invalidateEffectiveCache()`, so a freshly-authored policy takes effect on the next handshake regardless of the TTL.

Per-statement cost is in-memory only: a small AST walk + per-column lookup against the cached `EffectiveSet.columnPolicies` list. No Postgres traffic on the hot path.

### Observability

Three Micrometer metrics expose the rewriter's behaviour:

| Metric | Tags | Use |
|---|---|---|
| `column_policy_rewrites_total`       | `tenant`, `pool`, `outcome` ∈ `{rewritten, denied, unresolved_deny, parse_failed, passthrough}` | Fraction of reads paying the rewrite cost; spot policy regressions. |
| `column_policy_catalog_lookups_total` | `tenant`, `pool`, `result` ∈ `{hit, miss, error}` | `ColumnCatalog` cache effectiveness; alert on `error > 0` to catch federation breakage. |
| `column_policy_rewrite_duration_seconds` | `tenant`, `pool` | Histogram of rewrite step wall time; alert on percentile regressions. |

The `column_policy_rewrites_total{outcome=...}` counter emits five outcomes:

- `rewritten`: at least one projection or predicate column was replaced with a mask transform.
- `denied`: a deny policy matched a referenced column.
- `unresolved_deny`: `QOD_CLS_UNRESOLVED_TABLE=deny` refused a query because the catalog did
  not know the referenced table.
- `parse_failed`: jsqltranspiler could not parse / resolve the SQL; the original statement is
  forwarded unchanged. Distinct from `passthrough` so operators can tell "rewriter inactive
  because nothing to rewrite" from "rewriter inactive because it gave up".
- `passthrough`: the rewriter inspected the SQL and decided no rewrite was needed (no covered
  column referenced; non-SELECT; superuser; user has no column policies).

`Denied` outcomes also surface in the statement-history view (with `status=denied` and the offending column qualifier in the error message) and in the audit log line `column policy denied: user=… column=… role=… statement=…`.

### Authoring

Column policies are administered the same way as table permissions: via REST endpoints under `/api/role/column-policy/{create,update,delete,list}`, via the admin UI's "Column policies" tab on the role detail page, and via YAML manifest (`columnPolicies` list under each role). The bundled demo manifest (`bootstrap-demo.yaml`) ships an example mask policy on `acme/analyst/customer.c_phone` so a fresh `LOAD_TPCH=1` boot demonstrates the feature end-to-end without manual setup.

## The two gates

Every FlightSQL request passes through two sequential gates.

### Gate 1: handshake (pool-access)

This is the database-access gate. Because each pool is bound to exactly one tenant-db (see "Pools and databases" above), admitting the pool admits its database. When a client opens a FlightSQL connection it supplies a tenant and pool name (via gRPC headers or JWT claims). The handshake:

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

## Worked examples

These scenarios show how the entities, the EffectiveSet, and the two gates combine in practice. Each one lists the setup as entities plus grants, then the resulting allow/deny decision for representative statements. The grant notation `verb on catalog.schema.table` is one `qodstate_role_permission` row. For the REST and UI steps to create these, see the "Administering access" page; this section is about understanding the outcomes. All examples assume `QOD_ACL_ENABLED=true`. The users, roles, groups, and grants shown match the bundled demo manifest at `src/main/resources/bootstrap-demo.yaml`, which is imported automatically when `LOAD_TPC=N` is set.

### Read-only analyst

A BI analyst may query a handful of curated TPC-H tables and nothing else.

Setup:

| Entity | Value |
|---|---|
| User | `alice` (tenant `acme`) |
| Role | `analyst` |
| Role permissions | `RO on acme_tpch.tpch1.customer`, `RO on acme_tpch.tpch1.orders`, `RO on acme_tpch.tpch1.nation`, `RO on acme_tpch.tpch1.region` |
| Pool grant | `alice` holds a direct pool permission for pool `bi` |
| Edge | `alice` assigned `analyst` directly (`qodstate_user_role`) |

Results, connecting to pool `bi` (default catalog `acme_tpch`, default schema `tpch1`):

| Statement | Decision | Why |
|---|---|---|
| `SELECT * FROM customer` | Allowed | Unqualified name resolves to `acme_tpch.tpch1.customer`; covered by `RO on acme_tpch.tpch1.customer`. |
| `SELECT * FROM lineitem` | Denied | Resolves to `acme_tpch.tpch1.lineitem`; no grant on `lineitem` exists for `analyst`. |
| `INSERT INTO customer VALUES (...)` | Denied | Write access needs `RW` or `ALL`; `analyst` holds only `RO` on `customer`. |

### ETL pipeline through a group

A data-engineering account runs nightly loads. Permissions are attached to roles, the roles to a group, and the account to the group, so onboarding a second loader is one group-membership change.

Setup:

| Entity | Value |
|---|---|
| User | `bob` (tenant `acme`) |
| Group | `data-eng` |
| Roles via group | `etl` and `dba` (`qodstate_group_role`) |
| `etl` permissions | `RW on acme_tpch.tpch1.lineitem`, `RO on acme_tpch.tpch1.orders` |
| `dba` permissions | `DDL on acme_tpch.tpch1.*` |
| Edge | `bob` is a member of `data-eng` (`qodstate_user_group`) |
| Pool grant | `bob` holds a direct pool permission for pool `etl` |

Results, connecting to pool `etl` (default catalog `acme_tpch`, default schema `tpch1`):

| Statement | Decision | Why |
|---|---|---|
| `INSERT INTO lineitem SELECT * FROM orders` | Allowed | Write target `acme_tpch.tpch1.lineitem` covered by `RW on acme_tpch.tpch1.lineitem` (via `etl`); read source `acme_tpch.tpch1.orders` covered by `RO on acme_tpch.tpch1.orders` (via `etl`). Both sides must pass. |
| `DELETE FROM lineitem WHERE l_orderkey < 0` | Allowed | DELETE is a `Write` access on `acme_tpch.tpch1.lineitem`, covered by `RW on acme_tpch.tpch1.lineitem`. |
| `CREATE TABLE foo (id INT)` | Allowed | `CREATE` is a `Ddl` access on `acme_tpch.tpch1.foo`; covered by `DDL on acme_tpch.tpch1.*` (via `dba`). |
| `SELECT * FROM customer` | Denied | Resolves to `acme_tpch.tpch1.customer`; neither `etl` nor `dba` grants `RO` (or broader) on `customer`. |

`RW` on a table covers both reads and writes on that table; if the workload needs to write somewhere but read from elsewhere, add the matching read grant separately as in the example above.

### Cross-tenant denial via wildcard scope

A tenant administrator holds `ALL on *.*.*` inside their tenant. The catalog `*` wildcard is scoped to the session's tenant, so it does not reach a sibling tenant's catalog.

Setup:

| Entity | Value |
|---|---|
| User | `acme-admin` (tenant `acme`, not a superuser) |
| Role | `tenant_admin` |
| Role permission | `ALL on *.*.*` |
| Pool grants | pool `bi` and pool `etl` (both in tenant `acme`) |

Results, connecting to pool `bi`:

| Statement | Decision | Why |
|---|---|---|
| `SELECT * FROM customer` | Allowed | Resolves to `acme_tpch.tpch1.customer`; `ALL` covers `Read` and `*` matches any catalog within tenant `acme`. |
| `CREATE TABLE tpch1.new_table (id INT)` | Allowed | `ALL` covers `Ddl`; the wildcard matches `acme_tpch`. |
| `SELECT * FROM globex_tpcds.tpcds1.store_sales` | Denied | `globex_tpcds` belongs to tenant `globex`, not `acme`; the catalog `*` wildcard is scoped to the session's tenant and does not match it. |

To grant access to `globex_tpcds` deliberately, name it literally in a separate permission: `RO on globex_tpcds.*.*`. That bypasses the tenant-scoped wildcard through the literal-match path. Contrast with a superuser (`tenant IS NULL`), who skips the per-statement gate entirely and needs no such explicit grant.

### Pool gate versus statement gate

Table permissions and pool access are independent. Holding the right table grants does not let a user onto a pool they were never granted.

Setup:

| Entity | Value |
|---|---|
| User | `alice` (tenant `acme`) with role `analyst` granting `RO on acme_tpch.tpch1.customer` (and others) |
| Pool grant | a pool permission for pool `bi` only |

Results:

| Action | Decision | Why |
|---|---|---|
| Connect to pool `bi`, then `SELECT * FROM customer` | Allowed | Gate 1 admits the pool; gate 2 admits the read (`acme_tpch.tpch1.customer` covered by `RO`). |
| Connect to pool `etl` | Denied at handshake | Gate 1: `alice`'s effective pools do not include `etl`. No SQL is ever evaluated. |

A pool permission with a null `pool_id` would admit `alice` to every pool in tenant `acme`, collapsing the two rows above into one tenant-wide grant.

## Privilege-escalation guards

The following invariants are enforced at the API layer, not by the database schema alone:

- Only a superuser (`tenant IS NULL`) can create another superuser. A tenant-scoped admin attempting to create a user with `tenant IS NULL` is rejected.
- When granting a role permission, the granting principal must themselves hold that permission (or a covering wildcard) in their own effective set. A tenant-scoped admin cannot grant more than they hold.
- These checks apply to every mutation path: REST API, and any admin seeding at startup.

Superusers are not subject to these guards because they bypass the effective-set check entirely.
