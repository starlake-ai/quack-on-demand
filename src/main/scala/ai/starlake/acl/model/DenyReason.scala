package ai.starlake.acl.model

/** Reasons the SQL parser can refuse to fully qualify a statement. Only
  * [[ai.starlake.acl.parser.SqlParser]] emits these -- they signal the
  * qualification failures it can detect locally. The table-permission check
  * itself lives in [[ai.starlake.quack.edge.sql.PostgresAclValidator]] over
  * the cached EffectiveSet.
  */
enum DenyReason {
  case ParseError(message: String)
  case UnqualifiedTable(tableName: String, missingPart: String)
}
