# Security follow-ups: column-level security (CLS) masking coverage

These are known, tracked gaps in the CLS rewriter's subquery/expression masking
(`src/main/scala/ai/starlake/quack/edge/cls/JsqltranspilerRewriter.scala`,
`PolicyVisitor`). They predate the metadata-query and EXISTS/ANY/NOT hardening and
are filed here for a follow-up pass. Both let a principal that holds a column mask
observe the true (unmasked) value indirectly, so they are membership/inference
oracles rather than direct disclosure, but they defeat the masking guarantee and
should be closed.

## 1. Correlated subquery: outer-table masked column not masked inside the subquery

When the visitor descends into a subquery (IN / EXISTS / ANY / scalar), it builds
`fromTables` only from that subquery's own FROM/JOIN items. A reference to the
OUTER query's table via its outer-scope alias is therefore unknown inside the
subquery, and `resolveTable` falls back to the raw alias string, which never
matches a policy keyed on the physical table name. The masked column is forwarded
unmasked.

Reproduction (principal has a `c_phone` mask on `tpch1.customer`):

```sql
SELECT c.c_id
FROM tpch1.customer c
WHERE EXISTS (
  SELECT 1 FROM tpch1.customer c2
  WHERE c2.c_id = c.c_id AND c.c_phone = '555'
)
```

Result today: Passthrough, `c.c_phone` compared against the real value.

Fix direction: thread the outer scope's resolved tables (alias to physical table)
into the subquery descent so an outer-alias-qualified column resolves to its base
table and its policy applies. This is a scope-threading change shared by every
subquery-descent case, not a per-operator patch.

## 2. Unresolved table inside a subquery fails open

An unknown table at the top level correctly denies (fail-closed) under
`UnresolvedMode.Deny` / STRICT. The same unknown table referenced only inside a
subquery (IN / EXISTS / ANY) yields Passthrough instead of Denied, because the
resolver does not enforce table-existence for subquery-nested FROM items. Masking
of known columns is unaffected (it is name-matched), but the advertised
"fail-closed, worst case a denied query, never a leak" invariant is weaker than
documented whenever the unresolvable table sits inside a subquery.

Reproduction:

```sql
SELECT 1 FROM information_schema.tables
WHERE EXISTS (SELECT 1 FROM tpch1.unknown_table)
```

Result today: Passthrough (top-level `FROM tpch1.unknown_table` would Deny).

Fix direction: extend the unresolved-table strictness check into subquery-nested
FROM items so an unresolvable table denies regardless of nesting depth.

## 3. Metadata access: implicit read with result filtering (design item)

Today an operator must grant `RO` on `<tenantDb>.information_schema.*` (and
`pg_catalog`) for a principal to browse the catalog. This is an obscure footgun
(the failure is a cryptic ACL denial), and it is effectively mandatory for the
Starlake integration: Starlake enumerates schemas/tables/columns to render its
UI, so without metadata read the product does not function for that principal.
So metadata should be readable without a bespoke grant.

The wrong way to do it is a blanket "any RO principal may read raw
information_schema": in DuckDB, `information_schema.tables` / `.columns` reflect
the ENTIRE tenant-db catalog, not just the objects the principal is granted. A
principal with `RO` on `customer` only would then be able to enumerate that
`salaries`, `ssn_vault`, etc. exist and read their column names. That downgrades
table-level ACL from "cannot read this table" to "cannot read its rows, but can
see it exists and its shape"; a sensitively-named column leaks via metadata even
when its values are CLS-masked.

Target design (how Postgres / Snowflake / BigQuery do it): metadata is readable
by any authenticated principal that can reach the pool, but the RESULT ROWS are
filtered to the catalogs/schemas/tables the principal has at least `RO` on. This
removes the footgun and keeps table-level ACL meaningful (no enumeration of
ungranted objects). Key it on "authenticated principal reaching the pool", not
"holds an RO grant somewhere", so a zero-grant user still gets a coherent
(empty/filtered) catalog for the UI to load.

Acceptable interim if filtered metadata is too large a lift now: implicit
UNFILTERED read of the system schemas only (`information_schema`, `pg_catalog`)
for any authenticated principal, shipped ONLY with an explicit documented
posture: within a tenant-db, catalog shape (table/column existence) is visible
to all its users, and only row/column VALUES are protected by RLS/CLS. Fine for
a single-tenant analytics deployment of semi-trusted users; NOT acceptable where
mutually-distrusting tenants share a tenant-db or where schema design is
sensitive.

This is an ACL-validator / edge change, separate from the CLS rewriter work
above: the rewriter fix only stopped metadata queries from failing closed in the
masking layer; the ACL grant requirement is the gate that currently forces the
`information_schema` grant.

## Status of the related hardening already landed

- Metadata queries over `information_schema` / `pg_catalog` no longer fail closed
  for principals with column policies (system-schema tables are seeded into the
  resolver so they resolve to a no-op passthrough; unknown system tables still
  fail closed).
- Covered columns inside `IN`, scalar subqueries, `EXISTS`, quantified
  `= ANY/ALL/SOME`, and their `NOT`-wrapped forms are now masked.
