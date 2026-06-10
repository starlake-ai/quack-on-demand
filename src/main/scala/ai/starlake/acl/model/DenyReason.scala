package ai.starlake.acl.model

/** Reasons the SQL parser can refuse to fully qualify a statement.
  *
  * Pre-cutover this enum carried a much larger set of policy-side outcomes (`NoMatchingGrant`,
  * `ExpiredGrant`, view-resolution cycles, ...). After the RBAC cutover the only consumer is
  * [[ai.starlake.acl.parser.SqlParser]], which signals just the qualification failures it can
  * detect locally; the policy side is gone and the table-permission check lives in
  * [[ai.starlake.quack.edge.sql.PostgresAclValidator]] over the cached EffectiveSet.
  */
enum DenyReason {
  case ParseError(message: String)
  case UnqualifiedTable(tableName: String, missingPart: String)
}
