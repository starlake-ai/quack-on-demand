package ai.starlake.acl.model

/** Reasons the SQL parser can refuse to fully qualify a statement. Only
  * [[ai.starlake.acl.parser.SqlParser]] emits these -- they signal the qualification failures it
  * can detect locally. The table-permission check itself is the node decision pipeline's
  * ([[ai.starlake.quack.edge.authz.AuthzDecider]], through
  * [[ai.starlake.quack.edge.sql.GrantMatch]]), on what DuckDB resolved.
  */
enum DenyReason {
  case ParseError(message: String)
  case UnqualifiedTable(tableName: String, missingPart: String)

  /** A two-part name whose head matches a catalog attached on the session. The engine would bind it
    * catalog-first while the schema interpretation may hold different grants, so the statement must
    * be fully qualified instead of guessed at.
    */
  case AmbiguousCatalogRef(tableName: String, catalog: String)

  /** A four-part name (`catalog.schema.subschema.table`, DuckDB 2.0 nested schemas). JSqlParser
    * reads it as `server.database.schema.table`, so qualifying it would silently drop the real
    * catalog. Grants have no nested-schema form yet, so it is refused.
    */
  case NestedSchemaRef(tableName: String)
}
