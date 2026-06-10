# Access Control List (ACL) Model

This document provides details on the statement-level authorization design, the database schema for grants, principal expansion, and management of access control list (ACL) rules in Quack on Demand.

## Schema

Grants live in the `slkstate_acl_grant` table (when using Postgres state storage) with the following shape:

```sql
(tenant_id, principal, catalog_name, schema_name, table_name, permission)
```

* **`principal`**: Follows the `type:name` convention. For example: `user:alice`, `group:engineers`, `role:admin`.
* **Wildcards**: Any of `catalog_name`, `schema_name`, or `table_name` may be `NULL` to act as a wildcard (matching all items at that level).
* **Permissions**: Supports `SELECT | INSERT | UPDATE | DELETE | ALL` (`ALL` always wins).

---

## Enforcement

When `acl.enabled=true` and `stateStorage=postgres`, the system checks permissions per query:

1. `PostgresAclValidator` parses each incoming SQL statement at the Arrow Flight SQL edge.
2. It extracts all referenced table and view names.
3. It queries the state database for grants matching the user's principal set and the referenced tables.
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
   | `COMMIT` / `ROLLBACK` / `SET` / `SHOW` / ... | no accesses; admitted unconditionally                         |

   Granular role-permission verbs collapse into the access space as follows: `SELECT` -> `Read`; `INSERT` / `UPDATE` / `DELETE` / `TRUNCATE` -> `Write`; `CREATE` / `DROP` / `ALTER` -> `Ddl`; `ALL` covers everything. The validator denies any access without a matching grant.

---

## Principal Expansion

At validation time, the authenticated session's `username`, `groups`, and `role` are expanded into multiple principals:
* `user:<username>`
* `group:<group_name>` (one principal per group the user belongs to)
* `role:<role_name>`

A grant matches if **any** of these expanded principals match. 

> [!NOTE]
> For example, an OIDC user named `alice` with groups `[engineers, analysts]` and role `viewer` triggers lookups for four principals at once:
> - `user:alice`
> - `group:engineers`
> - `group:analysts`
> - `role:viewer`
> 
> You can write your grants against whichever level of identity is most stable and appropriate.

---

## Managing Grants

Grants can be managed via the Admin UI (on the tenant detail page) or programmatically via the REST API:

```bash
curl -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/acl/grant/create \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"acme","principal":"user:alice",
       "catalogName":"tpch","schemaName":"main","tableName":"customer",
       "permission":"SELECT"}'
```
