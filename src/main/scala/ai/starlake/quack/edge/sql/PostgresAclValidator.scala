package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.{SqlParser, StatementResult}
import ai.starlake.quack.ondemand.state.AclGrantStore
import com.typesafe.scalalogging.LazyLogging

/** SQL ACL validator backed by the Postgres-relational `slkstate_acl_grant`
  * table. Uses the ACL `SqlParser` to extract table refs from SELECT
  * statements, then queries the grant table for the user's principal.
  *
  * Decision rule:
  *   - All `SELECT`s: every referenced table must have at least one grant
  *     with permission `SELECT` or `ALL` for one of the user's principals.
  *   - Non-SELECT (DDL/DML) or `ParseError`: deny unless the user has a
  *     wildcard `ALL` grant on `*.*.*`. The strict default is intentional
  *     - JSqlParser doesn't enumerate writes the same way as reads, so
  *     we err on the side of "explicit grant required".
  *
  * Principals come from `ValidationContext.username` (as `user:<name>`).
  * Group/role propagation is deferred to a later iteration: ConnectionContext
  * needs to carry `AuthenticatedProfile.groups` + `role` for that.
  *
  * `tenantId` is the SessionConfig.aclTenant (defaults to "default").
  * `defaultDatabase` / `defaultSchema` come from the pool's metastore so
  * unqualified `FROM customer` resolves to the pool's catalog/schema and
  * matches grants written against the fully-qualified table. */
final class PostgresAclValidator(
    store: AclGrantStore,
    tenantId: String,
    defaultDatabase: String,
    defaultSchema: String,
    dialect: String = "duckdb"
) extends StatementValidator,
      LazyLogging:

  /** Build the SQL parser config from per-call overrides (ValidationContext)
    * falling back to the validator's construction-time defaults. Lets a
    * single validator instance serve multiple pools with different schemas. */
  private def parserConfigFor(context: ValidationContext): Config =
    val db     = context.defaultDatabase.getOrElse(defaultDatabase)
    val schema = context.defaultSchema.getOrElse(defaultSchema)
    if dialect.equalsIgnoreCase("duckdb") then Config.forDuckDB(db, schema)
    else Config.forGeneric(db, schema)

  override def validate(context: ValidationContext): ValidationResult =
    val principals = principalsFor(context)
    val extraction = SqlParser.extract(context.statement, parserConfigFor(context))

    // Bail out on parse errors / unsupported statements: deny unless the
    // user has an admin-style wildcard grant.
    val unparsed = extraction.statements.collect {
      case StatementResult.ParseError(_, snippet, msg) => s"parse error: $msg ($snippet)"
      case StatementResult.NonSelect(_, snippet, kind)  => s"non-select statement: $kind ($snippet)"
    }
    if unparsed.nonEmpty then
      if hasWildcardAll(principals) then
        logger.info(s"ACL: wildcard ALL grant covers non-select statement (user=${context.username})")
        Allowed
      else
        val reason = unparsed.mkString("; ")
        logger.warn(s"ACL DENIED (no wildcard ALL): user=${context.username}: $reason")
        Denied(reason)
    else
      // All tables must be authorized.
      val unauthorized = extraction.allTables.filterNot { t =>
        store.hasAccess(
          tenantId      = tenantId,
          principals    = principals,
          catalog       = t.database,
          schema        = t.schema,
          table         = t.table,
          requiredPerms = Set("SELECT")
        )
      }
      if unauthorized.isEmpty then
        logger.info(
          s"ACL ALLOWED: user=${context.username} principals=${principals.mkString(",")} " +
          s"tables=${extraction.allTables.map(_.canonical).mkString(",")}"
        )
        Allowed
      else
        val reason =
          s"missing SELECT grant on ${unauthorized.map(_.canonical).mkString(", ")} " +
          s"for ${principals.mkString(", ")}"
        logger.warn(s"ACL DENIED: $reason")
        Denied(reason)

  /** Build the principal set from the authenticated session. Combines
    * `user:<username>` with `group:<g>` (one per group the profile carries)
    * and `role:<r>` (when the profile has a non-empty role). Grants
    * targeted at any of these match. */
  private def principalsFor(ctx: ValidationContext): Set[String] =
    val user  = if ctx.username.isEmpty then Set.empty else Set(s"user:${ctx.username}")
    val groups = ctx.groups.iterator.filter(_.nonEmpty).map(g => s"group:$g").toSet
    val role   = if ctx.role.isEmpty then Set.empty else Set(s"role:${ctx.role}")
    user ++ groups ++ role

  /** Does the user have a wildcard ALL grant? Passing a synthetic table
    * name that no real grant row would ever target means the OR-with-NULL
    * predicate matches only the wildcard rows. Combined with
    * requiredPerms={"ALL"}, this answers "is there a row with NULL/NULL/NULL
    * and permission=ALL for any of our principals". */
  private def hasWildcardAll(principals: Set[String]): Boolean =
    store.hasAccess(
      tenantId      = tenantId,
      principals    = principals,
      catalog       = "__quack_synthetic_no_match__",
      schema        = "__quack_synthetic_no_match__",
      table         = "__quack_synthetic_no_match__",
      requiredPerms = Set("ALL")
    )