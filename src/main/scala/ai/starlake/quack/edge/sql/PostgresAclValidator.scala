package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.{SqlParser, StatementResult}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RolePermission
import ai.starlake.quack.route.StatementClassifier
import com.typesafe.scalalogging.LazyLogging

/** Per-statement ACL gate backed by the cached
  * [[ai.starlake.quack.ondemand.rbac.EffectiveSet]] pinned on
  * [[ai.starlake.quack.edge.ConnectionContext]] at handshake time. Reads
  * a tenant-scoped principal's table permissions in-memory; superusers
  * (`effectiveSet.user.tenant.isEmpty`) bypass.
  *
  * Decision rule per statement:
  *   1. No `effectiveSet` on the validation context  -> deny (the
  *      handshake never bound an RBAC principal, so we err on the safe
  *      side).
  *   2. Superuser principal                          -> allow.
  *   3. Classify the statement (SELECT / INSERT / UPDATE / DELETE / DDL).
  *      DDL still requires a wildcard ALL grant -- DDL grants are out of
  *      scope for this iteration.
  *   4. For SELECT/INSERT/UPDATE/DELETE: extract the table refs and
  *      require each one to be covered by a [[RolePermission]] row whose
  *      verb is the matching DML verb OR `ALL`, and whose
  *      (catalog, schema, table) match (literal or `*` wildcard).
  *
  * Catalog/schema defaults: when a referenced table is unqualified, the
  * SQL parser fills them in from the pool's metastore via
  * [[ValidationContext.defaultDatabase]] / `defaultSchema`. */
final class PostgresAclValidator(
    defaultDatabase: String = "",
    defaultSchema:   String = "main",
    dialect:         String = "duckdb"
) extends StatementValidator,
      LazyLogging:

  private def parserConfigFor(context: ValidationContext): Config =
    val db     = context.defaultDatabase.getOrElse(defaultDatabase)
    val schema = context.defaultSchema.getOrElse(defaultSchema)
    if dialect.equalsIgnoreCase("duckdb") then Config.forDuckDB(db, schema)
    else Config.forGeneric(db, schema)

  override def validate(context: ValidationContext): ValidationResult =
    context.effectiveSet match
      case None =>
        // Defensive: no handshake state -> deny. In practice the FlightSQL
        // handshake always pins an EffectiveSet (even an empty one for
        // superusers), so reaching here means the wiring is broken.
        val msg = "no RBAC principal bound to session; deny"
        logger.warn(s"ACL DENIED: user=${context.username}: $msg")
        Denied(msg)

      case Some(eff) if eff.user.tenant.isEmpty =>
        // Superuser bypass. Logged at debug only so admin smoke tests
        // don't spam the info channel.
        logger.debug(s"ACL ALLOWED (superuser): user=${context.username}")
        Allowed

      case Some(eff) =>
        validateForPrincipal(context, eff)

  private def validateForPrincipal(
      context: ValidationContext,
      eff:     EffectiveSet
  ): ValidationResult =
    val kind = StatementClassifier.classify(context.statement)
    val required = requiredVerbFor(kind)

    required match
      case None =>
        // DDL or unknown. Today this requires an unrestricted `*.*.* ALL`
        // grant since the parser doesn't enumerate DDL targets. The spec
        // calls this out as a follow-up (DDL grants as a first-class
        // operator concern).
        if hasWildcardAll(eff) then
          logger.info(s"ACL: wildcard ALL covers ${kind} for user=${context.username}")
          Allowed
        else
          val msg = s"DDL/unknown statement requires wildcard ALL grant"
          logger.warn(s"ACL DENIED: user=${context.username}: $msg")
          Denied(msg)

      case Some(verb) =>
        val extraction = SqlParser.extract(context.statement, parserConfigFor(context))
        val unparsed = extraction.statements.collect {
          case StatementResult.ParseError(_, snippet, msg) => s"parse error: $msg ($snippet)"
        }
        if unparsed.nonEmpty then
          if hasWildcardAll(eff) then
            logger.info(s"ACL: wildcard ALL covers unparseable statement for user=${context.username}")
            Allowed
          else
            val msg = unparsed.mkString("; ")
            logger.warn(s"ACL DENIED: user=${context.username}: $msg")
            Denied(msg)
        else
          val unauthorized = extraction.allTables.filterNot { t =>
            eff.permissions.exists(p =>
              (p.verb.equalsIgnoreCase(verb) || p.verb.equalsIgnoreCase("ALL")) &&
                wildcardMatch(p.catalogName, t.database) &&
                wildcardMatch(p.schemaName,  t.schema) &&
                wildcardMatch(p.tableName,   t.table)
            )
          }
          if unauthorized.isEmpty then
            logger.info(
              s"ACL ALLOWED: user=${context.username} " +
                s"roles=${eff.roles.map(_.name).mkString(",")} " +
                s"tables=${extraction.allTables.map(_.canonical).mkString(",")}"
            )
            Allowed
          else
            val principal = if eff.user.tenant.isEmpty then "superuser"
                            else s"user:${context.username}"
            val msg = s"$principal lacks $verb on ${unauthorized.map(_.canonical).mkString(", ")}"
            logger.warn(s"ACL DENIED: $msg")
            Denied(msg)

  /** Map a [[StatementKind]] to the SQL verb that a grant must cover.
    * Select -> SELECT, Dml -> INSERT (any one INSERT/UPDATE/DELETE grant
    * suffices since the classifier doesn't distinguish them today; the
    * ALL wildcard is the documented operator path either way). Begin /
    * Commit / Rollback / Other are session-level statements that don't
    * carry table refs and short-circuit to Allowed upstream of the
    * extraction step. */
  private def requiredVerbFor(kind: StatementKind): Option[String] =
    kind match
      case StatementKind.Select => Some("SELECT")
      case StatementKind.Dml    => Some("INSERT")
      case _                    => None

  private def wildcardMatch(grant: String, ref: String): Boolean =
    grant == RolePermission.Wildcard || grant.equalsIgnoreCase(ref)

  private def hasWildcardAll(eff: EffectiveSet): Boolean =
    eff.permissions.exists(p =>
      p.verb.equalsIgnoreCase("ALL") &&
        p.catalogName == RolePermission.Wildcard &&
        p.schemaName  == RolePermission.Wildcard &&
        p.tableName   == RolePermission.Wildcard
    )