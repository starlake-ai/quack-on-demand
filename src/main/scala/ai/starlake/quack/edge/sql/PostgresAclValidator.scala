package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.{Config, DenyReason}
import ai.starlake.acl.parser.{SqlParser, StatementResult, TableAccess, Verb}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RolePermission
import com.typesafe.scalalogging.LazyLogging

/** Per-statement ACL gate backed by the cached [[ai.starlake.quack.ondemand.rbac.EffectiveSet]]
  * pinned on [[ai.starlake.quack.edge.ConnectionContext]] at handshake time. Reads a tenant-scoped
  * principal's table permissions in-memory; superusers (`effectiveSet.user.tenant.isEmpty`) bypass.
  *
  * Decision rule per statement:
  *   1. No `effectiveSet` on the validation context -> deny (the handshake never bound an RBAC
  *      principal, so we err on the safe side).
  *   2. Superuser principal -> allow.
  *   3. Parse the statement with [[SqlParser.extract]] to enumerate every `(table, verb)` tuple.
  *      Parse errors short-circuit to Denied unless the principal holds an unrestricted ALL grant.
  *   4. `ControlFlow` statements (COMMIT, ROLLBACK, SET, ...) carry no table refs and are admitted
  *      unconditionally.
  *   5. For each `TableAccess(table, verb)` check that at least one [[RolePermission]] in the
  *      effective set covers it: `verbCovers(p.verb, verb)` AND wildcard-or-literal match on
  *      catalog / schema / table.
  *
  * Catalog/schema defaults: when a referenced table is unqualified, the SQL parser fills them in
  * from the pool's metastore via [[ValidationContext.defaultDatabase]] / `defaultSchema`.
  */
/** `tenantCatalogs(tenantId)` returns the catalog names (= tenant-db names) the given tenant owns.
  * Used to scope wildcard catalog grants (`*.*.* ALL`) so a tenant admin cannot wildcard-match a
  * sibling tenant's catalog. An empty set or an unknown tenant id collapses to "no catalogs
  * admissible via wildcard for this session"; explicit (non-wildcard) catalog grants are honored
  * regardless of the session's tenant. Default is a no-op lookup that returns `Set.empty`, which
  * makes wildcard catalog matches fail-closed: callers that don't wire a real lookup get the safer
  * behavior.
  */
final class PostgresAclValidator(
    defaultDatabase: String = "",
    defaultSchema: String = "main",
    dialect: String = "duckdb",
    tenantCatalogs: String => Set[String] = _ => Set.empty
) extends StatementValidator,
      LazyLogging:

  private def parserConfigFor(context: ValidationContext): Config =
    val db     = context.defaultDatabase.getOrElse(defaultDatabase)
    val schema = context.defaultSchema.getOrElse(defaultSchema)
    if dialect.equalsIgnoreCase("duckdb") then
      Config.forDuckDB(Some(db), Some(schema), context.attachedCatalogs)
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

  /** Deny `msg` unless the principal holds an unrestricted `*.*.* ALL` grant, which covers
    * statements the parser could not fully resolve (unparseable / unsupported constructs /
    * qualification errors). `covered` names what the wildcard admitted in the allow log line.
    */
  private def denyUnlessWildcardAll(
      eff: EffectiveSet,
      username: String,
      covered: String,
      msg: String
  ): ValidationResult =
    if hasWildcardAll(eff) then
      logger.info(s"ACL: wildcard ALL covers $covered for user=$username")
      Allowed
    else
      logger.warn(s"ACL DENIED: user=$username: $msg")
      Denied(msg)

  private def validateForPrincipal(
      context: ValidationContext,
      eff: EffectiveSet
  ): ValidationResult =
    val extraction = SqlParser.extract(context.statement, parserConfigFor(context))

    // Parse errors short-circuit to Denied unless the principal holds an
    // unrestricted ALL grant (preserves prior behavior).
    val parseErrors = extraction.statements.collect {
      case StatementResult.ParseError(_, snippet, msg) => s"parse error: $msg ($snippet)"
    }
    // Constructs the walker could not resolve to a grantable table (table
    // functions like read_parquet, string-literal file refs, unrecognized
    // FROM-item / node types). Fail closed: these escape the tenant-catalog
    // boundary or would otherwise be silently dropped, turning the
    // empty-access fail-open into an allow. Wildcard ALL still covers them.
    val unsupported = extraction.statements.collect {
      case StatementResult.Extracted(_, _, _, _, u) if u.nonEmpty => u
    }.flatten
    // Qualification errors were previously ignored, silently DROPPING the
    // offending ref from the access set (fail-open). AmbiguousCatalogRef denies
    // unconditionally: admitting it under the wildcard would reopen the
    // cross-catalog bypass the attached-catalog check exists to close. Other
    // qualification errors mirror the unsupported arm (wildcard ALL covers them).
    val qualErrors = extraction.statements.collect {
      case StatementResult.Extracted(_, _, _, q, _) if q.nonEmpty => q
    }.flatten
    val ambiguous = qualErrors.collect { case a: DenyReason.AmbiguousCatalogRef => a }
    val otherQual = qualErrors.filterNot(_.isInstanceOf[DenyReason.AmbiguousCatalogRef])

    if parseErrors.nonEmpty then
      denyUnlessWildcardAll(
        eff,
        context.username,
        "unparseable statement",
        parseErrors.mkString("; ")
      )
    else if unsupported.nonEmpty then
      denyUnlessWildcardAll(
        eff,
        context.username,
        s"unsupported constructs (${unsupported.mkString(", ")})",
        s"unsupported constructs (deny, fail-closed): ${unsupported.mkString(", ")}"
      )
    else if ambiguous.nonEmpty then
      val msg = ambiguous
        .map(a =>
          s"ambiguous two-part name '${a.tableName}': '${a.catalog}' is an attached " +
            s"catalog; qualify fully as '${a.catalog}.<schema>.<table>'"
        )
        .mkString("; ")
      logger.warn(s"ACL DENIED: user=${context.username}: $msg")
      Denied(msg)
    else if otherQual.nonEmpty then
      denyUnlessWildcardAll(
        eff,
        context.username,
        s"qualification errors (${otherQual.mkString(", ")})",
        s"unresolvable table references (deny, fail-closed): ${otherQual.mkString("; ")}"
      )
    else
      // Collect all (table, verb) tuples from every Extracted statement.
      // ControlFlow statements (COMMIT, ROLLBACK, SET, ...) carry no accesses
      // and contribute nothing.
      val accesses: Set[TableAccess] = extraction.statements
        .collect { case StatementResult.Extracted(_, _, a, _, _) =>
          a
        }
        .flatten
        .toSet

      if accesses.isEmpty then
        // Pure ControlFlow (or every arm yielded zero refs). Admit
        // unconditionally -- there is nothing to authorize.
        logger.info(s"ACL ALLOWED: user=${context.username} no table refs")
        Allowed
      else
        // Pre-compute the catalogs admissible via wildcard for this session.
        // Used below to scope `*` catalog matches to the user's tenant; an
        // explicit (non-wildcard) catalog grant still bypasses this check, so
        // operators can deliberately grant cross-tenant access by naming a
        // sibling tenant's catalog.
        val sessionCatalogs: Set[String] =
          eff.user.tenant.map(tenantCatalogs).getOrElse(Set.empty)

        val unauthorized = accesses.filterNot { ta =>
          eff.permissions.exists(p =>
            verbCovers(p.verb, ta.verb) &&
              catalogMatch(p.catalogName, ta.table.database, sessionCatalogs) &&
              wildcardMatch(p.schemaName, ta.table.schema) &&
              wildcardMatch(p.tableName, ta.table.table)
          )
        }

        if unauthorized.isEmpty then
          logger.info(
            s"ACL ALLOWED: user=${context.username} " +
              s"roles=${eff.roles.map(_.name).mkString(",")} " +
              s"accesses=${accesses.map(a => s"${a.table.canonical}:${a.verb}").mkString(",")}"
          )
          Allowed
        else
          // Superusers never reach this method (validate() short-circuits
          // them), so the principal is always the tenant-scoped user.
          val msg =
            s"user:${context.username} lacks grants on ${unauthorized
                .map(a => s"${a.table.canonical}:${a.verb}")
                .mkString(", ")}"
          logger.warn(s"ACL DENIED: $msg")
          Denied(msg, unauthorized)

  /** Whether a role-permission verb covers a parser-emitted access verb. Grant verbs are the
    * canonical (RO / RW / DDL / ALL) set; the parser emits the collapsed
    * `Verb.Read | Verb.Write | Verb.Ddl` per access. `RW` is the only multi-cover grant (Read +
    * Write); DDL stays separate because CREATE/DROP/ALTER are deliberately higher-privilege.
    */
  private def verbCovers(grantVerb: String, access: Verb): Boolean =
    val gu = grantVerb.toUpperCase
    if gu == "ALL" then true
    else
      access match
        case Verb.Read  => gu == "RO" || gu == "RW"
        case Verb.Write => gu == "RW"
        case Verb.Ddl   => gu == "DDL"

  private def wildcardMatch(grant: String, ref: String): Boolean =
    grant == RolePermission.Wildcard || grant.equalsIgnoreCase(ref)

  /** Catalog-specific wildcard match: the literal-equal arm behaves like `wildcardMatch`, but the
    * `*` arm additionally requires the referenced catalog to be in the session's allowed-catalog
    * set. This closes the cross-tenant wildcard leak (a tenant admin with `*.*.* ALL` could
    * otherwise SELECT from a sibling tenant's catalog -- see project memory
    * `project-catalog-wildcard-cross-tenant`).
    *
    * An empty `sessionCatalogs` means no catalog matches via wildcard for this session. Operators
    * who want a tenant admin to retain cross-tenant read access can still grant an explicit catalog
    * (`otherdb.*.* ALL`), which bypasses the wildcard arm via the literal-equal check below.
    */
  private def catalogMatch(
      grant: String,
      ref: String,
      sessionCatalogs: Set[String]
  ): Boolean =
    if grant == RolePermission.Wildcard then sessionCatalogs.exists(_.equalsIgnoreCase(ref))
    else grant.equalsIgnoreCase(ref)

  private def hasWildcardAll(eff: EffectiveSet): Boolean =
    eff.permissions.exists(p =>
      p.verb.equalsIgnoreCase("ALL") &&
        p.catalogName == RolePermission.Wildcard &&
        p.schemaName == RolePermission.Wildcard &&
        p.tableName == RolePermission.Wildcard
    )
