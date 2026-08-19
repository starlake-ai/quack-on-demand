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

## Status of the related hardening already landed

- Metadata queries over `information_schema` / `pg_catalog` no longer fail closed
  for principals with column policies (system-schema tables are seeded into the
  resolver so they resolve to a no-op passthrough; unknown system tables still
  fail closed).
- Covered columns inside `IN`, scalar subqueries, `EXISTS`, quantified
  `= ANY/ALL/SOME`, and their `NOT`-wrapped forms are now masked.
