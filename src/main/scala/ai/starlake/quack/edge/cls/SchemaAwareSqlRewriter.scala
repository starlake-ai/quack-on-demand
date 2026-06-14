package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.RoleColumnPolicy

/** Outcome of a schema-aware rewrite attempt. */
enum RewriteOutcome:
  case Rewritten(sql: String)
  case Denied(reason: String)
  case Passthrough
  case ParseFailed

/** How the rewriter handles a table the SQL references that the schema map does not cover. */
enum UnresolvedMode:
  case Deny
  case Pass

/** Rewrites a SQL statement so every reference to a covered column is replaced with its mask
  * transform, or refuses with `Denied` if any reference matches a deny policy. The trait is pure
  * (no I/O): all schema lookups have already been done by the caller and handed in via `schema`.
  *
  * `schema` maps each FROM-item alias OR base table name the SQL references to its column list.
  * Empty list means "table exists but no columns known"; missing key means "table not in catalog at
  * all" and triggers `unresolvedMode`.
  *
  * `policies` is the EffectiveSet's columnPolicies list, already filtered to the session tenant.
  *
  * `defaultCatalog` / `defaultSchema` provide qualification defaults for bare table refs
  * (`customer` -> `acme_tpch.tpch1.customer`).
  */
trait SchemaAwareSqlRewriter:
  def rewrite(
      sql: String,
      schema: Map[String, List[String]],
      policies: List[RoleColumnPolicy],
      defaultCatalog: Option[String],
      defaultSchema: Option[String],
      unresolvedMode: UnresolvedMode = UnresolvedMode.Deny
  ): RewriteOutcome
