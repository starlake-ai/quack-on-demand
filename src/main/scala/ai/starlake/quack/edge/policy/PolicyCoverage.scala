package ai.starlake.quack.edge.policy

/** The (catalog, schema, table) wildcard coverage rule shared by the RLS rewriter and the
  * protected-write guard. A policy part of "*" matches anything; otherwise the comparison is
  * case-insensitive. Kept in one place so the guard's coverage check and the rewriter's cannot
  * drift.
  */
object PolicyCoverage:

  private val Wildcard = "*"

  def covers(
      policyCatalog: String,
      policySchema: String,
      policyTable: String,
      catalog: String,
      schema: String,
      table: String
  ): Boolean =
    def matchesPart(policyPart: String, actual: String): Boolean =
      policyPart == Wildcard || policyPart.equalsIgnoreCase(actual)
    matchesPart(policyTable, table) &&
    matchesPart(policySchema, schema) &&
    matchesPart(policyCatalog, catalog)
