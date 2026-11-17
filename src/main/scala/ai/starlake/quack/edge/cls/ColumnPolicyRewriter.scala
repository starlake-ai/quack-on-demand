package ai.starlake.quack.edge.cls

import java.util.Locale
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import cats.effect.IO
import cats.syntax.traverse._
import ai.starlake.acl.parser.TableExtractor
import ai.starlake.sql.{ParserInput, TooDeep}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.{ParenthesedSelect, PlainSelect, Select}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** Resolves the schema/catalog defaults in scope when parsing a SQL statement so the rewriter can
  * qualify bare table references (`customer` -> `acme_tpch.tpch1.customer`).
  *
  * `attachedCatalogs`: the catalogs attached on the pool's nodes (the tenant-db alias, federated
  * aliases, DuckDB's built-ins), as `ValidationContext.attachedCatalogs`. DuckDB binds a two-part
  * `X.t` catalog-first when `X` is one of them, so the protected-write guard refuses that name
  * instead of reading it schema-first (audit F20). Required: an empty set disables that refusal.
  */
/** `pool` is the pool the statement runs on: the column catalog reads there the columns of a table
  * only its nodes hold (a federated source, a `duckdb-file` or `memory` database). None (a caller
  * with no pool) leaves such a table without columns, which the rewriter refuses to mask through.
  */
final case class SchemaContext(
    defaultDatabase: Option[String],
    defaultSchema: Option[String],
    attachedCatalogs: Set[String],
    pool: Option[ai.starlake.quack.model.PoolKey] = None
)

object ColumnPolicyRewriter:
  sealed trait Outcome
  final case class Rewritten(sql: String) extends Outcome
  final case class Denied(reason: String) extends Outcome
  case object Passthrough                 extends Outcome

  /** An outcome with what the step learned of the statement's tables for the row-policy wrap
    * (search spec 4.3, review I6): the tables it resolved and those holding a real `rowid` column.
    */
  final case class Result(
      outcome: Outcome,
      realRowidTables: ai.starlake.quack.edge.rls.RealRowidTables
  )

  /** The rewriter could not parse the SQL, so it cannot prove the masked columns absent. The router
    * DENIES it (fail-closed), tagged `parse_failed` on the `column_policy_rewrites_total` counter
    * so dashboards can tell "rewriter blind" from a policy deny.
    */
  case object PassthroughParseFailed extends Outcome

  /** The deny reason for a rewrite that would be printed from a parser input that is not the
    * caller's statement (DuckDB's `//` integer division reaches JSqlParser as `/`), so shipping it
    * would run a different statement.
    */
  private[cls] val InexactRefusal: String =
    "column policies cannot be applied to a statement using integer division (//); " +
      "split the statement or select the division without the masked columns"

  /** A policy no table matches: its table name holds a NUL, which no SQL identifier can. */
  private[cls] val InertPolicy: ai.starlake.quack.ondemand.state.RoleColumnPolicy =
    ai.starlake.quack.ondemand.state.RoleColumnPolicy(
      id = "qod-inert",
      roleId = "",
      catalogName = "\u0000",
      schemaName = "\u0000",
      tableName = "\u0000",
      columnName = "\u0000",
      action = ai.starlake.quack.ondemand.state.RoleColumnPolicy.ActionMask,
      transformSql = Some("NULL")
    )

  /** Inner rewriter raised a deny because the table/schema/catalog could not be resolved, not
    * because a policy matched. Same wire-level error as [[Denied]] but tagged separately so
    * dashboards can split policy denies from missing-coordinate denies.
    */
  case object DeniedUnresolvedTable extends Outcome

  /** Heuristic for spotting a deny that originated from jsqltranspiler's
    * Table/Schema/Catalog/ColumnNotFound* exceptions: their messages contain "not found", "not
    * declared", or "unknown". Anything else falls through to a regular policy-driven [[Denied]].
    */
  private[cls] def looksUnresolvedTable(reason: String): Boolean =
    val r = reason.toLowerCase(Locale.ROOT)
    r.contains("not found") || r.contains("not declared") || r.contains("unknown")

/** Thin facade around a [[SchemaAwareSqlRewriter]]. Handles the IO surface (catalog lookups for the
  * FROM-item tables) plus the early-exit conditions (feature disabled, superuser, non-SELECT, no
  * policies) and delegates the actual SQL walk to the inner rewriter.
  *
  * `enabled` is the kill switch, on by default. When false, every call short-circuits to
  * [[ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Passthrough Passthrough]] without touching the
  * catalog or the inner rewriter. Operators opt out via `quack-on-demand.cls.enabled = false` (or
  * `QOD_CLS_ENABLED=false`).
  */
final class ColumnPolicyRewriter(
    catalog: ColumnCatalog,
    inner: SchemaAwareSqlRewriter = new JsqltranspilerRewriter,
    unresolvedMode: UnresolvedMode = UnresolvedMode.Pass,
    enabled: Boolean = true
):
  import ColumnPolicyRewriter._

  /** [[rewrite]] with the search flag read from `sql` itself (the protected-write guard's oracle,
    * the specs). The router passes the flag it read once from the client's own text.
    */
  def rewrite(
      sql: String,
      kind: StatementKind,
      eff: EffectiveSet,
      ctx: SchemaContext
  ): IO[Outcome] =
    rewrite(sql, kind, eff, ctx, searchFlagged(sql))

  /** `searchStatement`: the client's statement calls a search function
    * ([[ai.starlake.quack.edge.search.SearchFunctions.callsSearch]]); read only for a principal
    * holding a column policy.
    */
  def rewrite(
      sql: String,
      kind: StatementKind,
      eff: EffectiveSet,
      ctx: SchemaContext,
      searchStatement: => Boolean
  ): IO[Outcome] =
    rewriteWithTables(sql, kind, eff, ctx, searchStatement).map(_.outcome)

  /** [[rewrite]] with what the step learned of the statement's tables ([[Result]]), which the
    * router hands the row-policy rewriter: a search statement's wrap keeps `rowid` for a
    * column-policy holder only on tables known to hold no real `rowid` column.
    */
  def rewriteWithTables(
      sql: String,
      kind: StatementKind,
      eff: EffectiveSet,
      ctx: SchemaContext,
      searchStatement: => Boolean
  ): IO[Result] =
    val unknown                       = ai.starlake.quack.edge.rls.RealRowidTables.Unknown
    def plain(o: Outcome): IO[Result] = IO.pure(Result(o, unknown))
    if !enabled then plain(Passthrough)
    else if eff.user.tenant.isEmpty then plain(Passthrough)
    else if eff.columnPolicies.nonEmpty && kind != StatementKind.Select && searchStatement then
      // Search spec 4.3 (owner ruling 2026-11-14): a column-policy holder's flagged statement of
      // another kind is denied as a real search call is, before the row-policy wrap of rule 3 runs.
      plain(PassthroughParseFailed)
    else if kind != StatementKind.Select then plain(Passthrough)
    else if eff.columnPolicies.isEmpty then plain(Passthrough)
    else
      // Read once (by name at the call site), and only for a column-policy holder.
      val flagged = searchStatement
      // Every parse below reads the lexer-canonical rendering, never the raw text: JSqlParser
      // tokenizes DuckDB's escape and dollar-quoted strings differently, and a table it cannot see
      // is a table no policy masks. A rendering refusal is input DuckDB rejects anyway.
      // A too-deep statement overflows the parse and resolver walks below: denied (TooDeep).
      // Guarded twice because the walks run both eagerly (here) and inside the IO's map.
      TooDeep.orElse(plain(Denied(TooDeep.Reason))) {
        ParserInput.render(sql) match
          case Left(_)      => plain(PassthroughParseFailed)
          case Right(input) =>
            rewriteSearch(input.text, sql, eff, ctx, flagged).map { r =>
              r.outcome match
                // The rewrite is printed from the parser input, which is not the caller's
                // statement here (ParserInput.Rendered.exact): shipping it would run a different
                // statement.
                case Rewritten(_) if !input.exact => r.copy(outcome = Denied(InexactRefusal))
                case _                            => r
            }
      }

  /** The search detector, guarded: a scan that overflows counts as flagged (fail closed). */
  private def searchFlagged(sql: String): Boolean =
    TooDeep.orElse(true)(ai.starlake.quack.edge.search.SearchFunctions.callsSearch(sql))

  /** Search spec 12 Phase 1, change 9: each search call separated first ([[SearchSeparation]]),
    * before [[readsOnlyCatalogFunctions]] and [[buildSchema]]'s walk, which would otherwise report
    * the call as unsupported. A statement the separation cannot read is denied as a search call was
    * before (fail closed); one with no call is rewritten as before, byte for byte.
    */
  private def rewriteSearch(
      sql: String,
      written: String,
      eff: EffectiveSet,
      ctx: SchemaContext,
      flagged: Boolean
  ): IO[Result] =
    SearchSeparation.separate(sql, flagged, written) match
      case Left(_) =>
        IO.pure(Result(PassthroughParseFailed, ai.starlake.quack.edge.rls.RealRowidTables.Unknown))
      case Right(None)      => rewriteCanonical(sql, eff, ctx, Map.empty, input = false)
      case Right(Some(sep)) => rewriteSeparated(sep, eff, ctx)

  /** Each input rewritten as a statement of its own (a call inside one is refused there, as the
    * separation of the whole statement counted it), the outer statement with the placeholders as
    * known sources, then the calls put back. The input's rewrite must leave its select list as
    * written and add no derived table, so `rowid` stays a bare column of its scan (rule 1).
    */
  private def rewriteSeparated(
      sep: SearchSeparation.Separated,
      eff: EffectiveSet,
      ctx: SchemaContext
  ): IO[Result] =
    val withInput = sep.cuts.filter(_.input.nonEmpty)
    for
      inputs <- withInput.traverse { cut =>
        rewriteCanonical(cut.input, eff, ctx, Map.empty, input = true).map(r =>
          cut -> TooDeep.orElse(r.copy(outcome = Denied(TooDeep.Reason)))(inputShaped(cut.input, r))
        )
      }
      outer <- rewriteCanonical(sep.outer, eff, ctx, sep.placeholders, input = false)
    yield TooDeep.orElse(
      Result(Denied(TooDeep.Reason), ai.starlake.quack.edge.rls.RealRowidTables.Unknown)
    ) {
      val tables = inputs.foldLeft(outer.realRowidTables)(_ ++ _._2.realRowidTables)
      def refused(o: Outcome): Option[Outcome] =
        o match
          case Denied(reason) => Some(Denied(sep.describe(reason)))
          case Rewritten(_)   => None
          case Passthrough    => None
          case other          => Some(other)
      val denial = inputs.iterator
        .flatMap(i => refused(i._2.outcome))
        .nextOption()
        .orElse(refused(outer.outcome))
      denial match
        case Some(o) => Result(o, tables)
        case None    =>
          val rewrittenInputs = inputs.collect { case (cut, Result(Rewritten(s), _)) =>
            cut.call -> s
          }.toMap
          outer.outcome match
            case Passthrough if rewrittenInputs.isEmpty => Result(Passthrough, tables)
            case o                                      =>
              val outerText = o match
                case Rewritten(s) => s
                case _            => sep.outer
              sep.restore(outerText, rewrittenInputs) match
                case Left(reason) => Result(Denied(reason), tables)
                case Right(out)   =>
                  // The restored statement holds the calls separated and no other: a mask
                  // expression carrying a search name would add one.
                  if restoredCalls(out) == sep.cuts.size then Result(Rewritten(out), tables)
                  else Result(Denied(SearchSeparation.Unrestorable), tables)
    }

  private def restoredCalls(sql: String): Int =
    val scan = ai.starlake.quack.edge.sql.FunctionCalls.scan(sql)
    if scan.truncated then -1
    else scan.calls.count(c => ai.starlake.quack.edge.search.SearchFunctions.Names(c.name))

  /** An input's rewrite, refused (fail closed) when it changed the input's select list (a masked or
    * denied `rowid`) or added a derived table: the node's lineage rule reads `rowid` as a bare
    * column of the target scan.
    */
  private def inputShaped(input: String, r: Result): Result =
    r.outcome match
      case Rewritten(out) =>
        val before              = Try(ai.starlake.sql.JSqlStatements.parseFirst(input)).toOption
        val after               = Try(ai.starlake.sql.JSqlStatements.parseFirst(out)).toOption
        def derived(st: AnyRef) = StarExpansion.nodes(st).count(_.isInstanceOf[ParenthesedSelect])
        (before, after) match
          case (Some(b: PlainSelect), Some(a: PlainSelect))
              if String.valueOf(b.getSelectItems) == String.valueOf(a.getSelectItems) &&
                derived(b) == derived(a) =>
            r
          case _ => r.copy(outcome = PassthroughParseFailed)
      case _ => r

  private def rewriteCanonical(
      sql: String,
      eff: EffectiveSet,
      ctx: SchemaContext,
      placeholders: Map[String, List[(String, String)]],
      input: Boolean
  ): IO[Result] =
    val noTables = ai.starlake.quack.edge.rls.RealRowidTables(Set.empty, Set.empty)
    if readsOnlyCatalogFunctions(sql) then IO.pure(Result(Passthrough, noTables))
    else
      buildSchema(sql, eff.user.tenant.getOrElse(""), ctx, placeholders).map {
        case Left(reason) =>
          Result(Denied(reason), ai.starlake.quack.edge.rls.RealRowidTables.Unknown)
        case Right(r) =>
          val outcome = TooDeep.orElse(Denied(TooDeep.Reason)) {
            if input && !r.bindsOwnColumns(sql) then PassthroughParseFailed
            // Fix round 3: under deny the resolver reads the declared rowid below for its star
            // expansion too (one list per table), which would add a column DuckDB's star never
            // has; a star in a separated statement is refused there (the router's star screen
            // refuses a search statement's star for every principal anyway, rule 10).
            else if (input || placeholders.nonEmpty) && unresolvedMode == UnresolvedMode.Deny &&
              expandsStar(sql)
            then PassthroughParseFailed
            else
              val scoped = r.policies(eff.columnPolicies)
              // No policy covers a table of this statement. In Pass mode nothing is left to do;
              // Deny mode still refuses a table or column the resolver cannot find, so the
              // resolver runs with a policy no table can match.
              val policies =
                if scoped.nonEmpty || unresolvedMode == UnresolvedMode.Pass then scoped
                else List(InertPolicy)
              // Verification m1: the strict resolver (unresolvedTable = deny) knows no virtual
              // rowid, so a separated statement (a search input, or the outer statement with its
              // placeholders, whose join-back reads rowid) declares it on each listed table; an
              // unlisted table stays unknown and still refuses.
              val separated = input || placeholders.nonEmpty
              val resolving =
                if separated && unresolvedMode == UnresolvedMode.Deny then
                  r.schema.map { case (k, cols) =>
                    k -> (if cols.isEmpty || cols.exists(_.equalsIgnoreCase("rowid")) then cols
                          else cols :+ "rowid")
                  }
                else r.schema
              if policies.isEmpty then Passthrough
              else
                r.separation match
                  case None => rewriteResolved(sql, r.schema, resolving, r.homes, policies, ctx)
                  case Some(sep) =>
                    rewriteResolved(sep.sql, r.schema, resolving, r.homes, policies, ctx) match
                      case Rewritten(out) =>
                        sep.restore(out).fold(Denied(_), Rewritten(_))
                      case Denied(reason) => Denied(sep.describe(reason))
                      case other          => other
          }
          Result(outcome, r.rowidTables)
      }

  /** `schema` numbers positional references (the table's own columns); `resolving` is what the
    * resolver reads, which may also declare a separated statement's virtual `rowid`.
    */
  /** True when a select list of `sql` holds a star the resolver expands from a table's list: any
    * star select item, except in a SELECT whose items are all plain stars over derived tables only
    * (the Flight SQL prepare probe's wrap, which the resolver leaves as written). Unparseable:
    * true.
    */
  private def expandsStar(sql: String): Boolean =
    import net.sf.jsqlparser.statement.select.AllColumns
    Try(ai.starlake.sql.JSqlStatements.parseFirst(sql)).toOption match
      case None     => true
      case Some(st) =>
        StarExpansion.plainSelects(st).exists { ps =>
          val items = Option(ps.getSelectItems).map(_.asScala.toList).getOrElse(Nil)
          val stars = items.exists(si => si.getExpression.isInstanceOf[AllColumns])
          val from  = Option(ps.getFromItem).toList ++
            Option(ps.getJoins).map(_.asScala.toList).getOrElse(Nil).map(_.getFromItem)
          val derivedOnly = from.nonEmpty && from.forall(_.isInstanceOf[ParenthesedSelect]) &&
            items.forall(si =>
              si.getAlias == null && (si.getExpression match
                case a: AllColumns => StarExpansion.unmodified(a)
                case _             => false)
            )
          stars && !derivedOnly
        }

  private def rewriteResolved(
      sql: String,
      schema: Map[String, List[String]],
      resolving: Map[String, List[String]],
      homes: Map[String, (String, String)],
      policies: List[ai.starlake.quack.ondemand.state.RoleColumnPolicy],
      ctx: SchemaContext
  ): Outcome =
    PositionalReferences.resolve(sql, schema) match
      case Left(reason) => Denied(reason)
      case Right(named) =>
        inner.rewrite(
          sql = named,
          schema = resolving,
          policies = policies,
          defaultCatalog = ctx.defaultDatabase,
          defaultSchema = ctx.defaultSchema,
          unresolvedMode = unresolvedMode,
          homes = homes
        ) match
          case RewriteOutcome.Rewritten(s)   => Rewritten(s)
          case RewriteOutcome.Denied(reason) =>
            if looksUnresolvedTable(reason) then DeniedUnresolvedTable else Denied(reason)
          case RewriteOutcome.Passthrough => Passthrough
          case RewriteOutcome.ParseFailed => PassthroughParseFailed

  /** True for a single statement whose only FROM items are DuckDB's filterable catalog functions
    * (`duckdb_tables()`, `duckdb_views()`, `duckdb_schemas()`, `duckdb_columns()`) and which reads
    * no physical table anywhere: the quack client's attach-time catalog sync (`... FROM
    * duckdb_tables() UNION ALL ... FROM duckdb_views()`, issue #114). Nothing in it can carry a
    * policy-bearing column, while the column resolver cannot model a table function at all and
    * reports ParseFailed, which the router denies fail-closed: before this, every principal holding
    * ANY column policy could not ATTACH.
    *
    * Decided on the ACL parser's walk, which is complete by construction (ORDER BY, GROUP BY and
    * window subqueries included, unlike jsqlparser's own traversal), so a policy-bearing table
    * hidden anywhere still reaches the resolver. Exactly one statement: `parse` truncates a batch
    * to its first statement, so a shortcut judged on a batch's head would forward its tail
    * unmasked. The resolver below still parses only that first statement; what keeps a batch's tail
    * from going out unmasked is the protected-write guard, which runs before this rewriter and
    * analyses every statement of a multi-statement batch, denying it when any statement reads a
    * table under a column or row policy. Any other table function (`read_parquet`, ...) keeps the
    * fail-closed path, as does a catalog function beside a physical table.
    */
  private def readsOnlyCatalogFunctions(sql: String): Boolean =
    Try(CCJSqlParserUtil.parseStatements(sql)).toOption
      .filter(_.size == 1)
      .flatMap(sts => Option(sts.get(0)).collect { case sel: Select => sel })
      .exists { sel =>
        val extraction = TableExtractor.extract(sel)
        extraction.tables.isEmpty && extraction.unsupported.nonEmpty &&
        extraction.unsupported.forall(u =>
          TableExtractor.tableFunctionName(u).flatMap(TableExtractor.catalogFunctionCall).isDefined
        )
      }

  /** The statement's column lists by table key, each table named outside the defaults with where it
    * is named, and, when it reads tables of one bare name in several places, how they were renamed
    * apart ([[SameNameTables]]). `tables` is every table key with the catalog, schema and name it
    * reads, and `complete` whether the walk that found them read the whole statement.
    */
  private final case class Resolved(
      schema: Map[String, List[String]],
      homes: Map[String, (String, String)],
      separation: Option[SameNameTables.Separation],
      tables: List[(String, (String, String, String))] = Nil,
      complete: Boolean = false,
      rowidTables: ai.starlake.quack.edge.rls.RealRowidTables =
        ai.starlake.quack.edge.rls.RealRowidTables.Unknown
  ):

    /** True when every column reference of `sql` (a separated search input) resolves inside it,
      * scope by scope as DuckDB binds ([[SearchSeparation.unbound]]), each table's columns read
      * from this statement's lists: anything else would bind to the outer statement's scope (a
      * correlated input), which the input's rewrite alone cannot mask. A table whose columns are
      * not listed, or a walk that did not read the whole statement, leaves it undecided: false.
      */
    def bindsOwnColumns(sql: String): Boolean =
      val lists = schema.map { case (k, cols) => k.toLowerCase(Locale.ROOT) -> cols }
      def lookup(bare: String): Option[List[String]] = lists.get(bare).filter(_.nonEmpty)
      complete && Try(ai.starlake.sql.JSqlStatements.parseFirst(sql)).toOption.exists {
        case ps: PlainSelect => SearchSeparation.unbound(ps, Some(lookup)).isEmpty
        case _               => false
      }

    /** The policies the rewriter applies to this statement. The rewriter matches a policy to a
      * column by the bare name of the column's table, so a policy is handed to it only under the
      * key of a table it covers by catalog, schema and name (`PolicyCoverage`, the rule of the row
      * filters and the protected-write guard): a mask on `client_a.claims.claim` does not mask
      * `client_b.claims.claim` (issue #201). A policy whose table part is `*` stays as it is, as it
      * matches whatever table a column is attributed to, a column it cannot place included, once it
      * covers any table of the statement.
      *
      * When the walk did not read the whole statement (a construct it reports as unsupported, a
      * name it cannot place, a parse it could not make), a table it missed would lose its policies,
      * so every policy is applied by bare name, as before: over-masking, never a leak.
      */
    def policies(all: List[RoleColumnPolicy]): List[RoleColumnPolicy] =
      if !complete then separation.map(_.policies(all)).getOrElse(all)
      else
        tables.flatMap { case (key, (cat, sch, tab)) =>
          all.collect {
            case p
                if ai.starlake.quack.edge.policy.PolicyCoverage
                  .covers(p.catalogName, p.schemaName, p.tableName, cat, sch, tab) =>
              if p.tableName == RoleColumnPolicy.Wildcard then p else p.copy(tableName = key)
          }
        }.distinct

  /** Pre-parse to enumerate FROM-item tables and fetch their column lists from the catalog.
    * Failures (unparseable SQL, missing catalog entry) silently omit the table - the resolver's
    * `unresolvedMode` then decides what to do.
    *
    * System-schema tables (information_schema, pg_catalog) are skipped: the tenant catalog only
    * knows user tables (it returns Nil for them, which used to trip the STRICT resolver into a
    * fail-closed deny of harmless metadata queries), and this map's bare-table-name keys land under
    * the session's CURRENT_SCHEMA anyway, so they could never match a schema-qualified
    * `information_schema.x` reference. Instead the inner [[JsqltranspilerRewriter]] seeds the
    * resolver with DuckDB's fixed system-catalog shapes from [[SystemSchemaColumns]] under their
    * real schema names. A system table not in that static set stays unresolved and keeps failing
    * closed (worst case: a denied metadata query, never a leak).
    */
  private def buildSchema(
      sql: String,
      tenant: String,
      ctx: SchemaContext,
      placeholders: Map[String, List[(String, String)]]
  ): IO[Either[String, Resolved]] =
    val declared = placeholders.map { case (k, cols) => k.toLowerCase(Locale.ROOT) -> cols }
    Try(ai.starlake.sql.JSqlStatements.parseFirst(sql)) match
      case Failure(_)            => IO.pure(Right(Resolved(Map.empty, Map.empty, None)))
      case Success(stmt: Select) =>
        val (found, complete) = collectTables(stmt, ctx)
        val tables            = found.filterNot { case (_, (_, sch, _)) =>
          SystemSchemaColumns.isSystemSchema(sch) // resolved via SystemSchemaColumns inside
        }
        val clashing = SameNameTables.clashing(tables)
        // Tables of one bare name in several catalogs or schemas are renamed apart first, so
        // each reads its own column list (issue #201).
        val separated =
          if clashing.isEmpty then Right(None)
          else SameNameTables.separate(stmt, tables, clashing).map(Some(_))
        separated match
          case Left(reason) => IO.pure(Left(reason))
          case Right(sep)   =>
            val kept = tables.collect {
              case (t, coords @ (_, _, tab)) if !clashing.contains(tab.toLowerCase(Locale.ROOT)) =>
                (tab, coords)
            }
            val keyed = kept ++ sep.toList.flatMap(_.keys)
            // A search call's placeholder (SearchSeparation) is a known source: its columns
            // are the function's fixed outputs, never a catalog's.
            def placeholder(key: String) = declared.get(key.toLowerCase(Locale.ROOT))
            keyed.distinct
              .traverse { case (key, (cat, sch, tab)) =>
                placeholder(key) match
                  case Some(cols) => IO.pure(key -> cols.map(_._1))
                  case None       =>
                    catalog.columnsOf(tenant, ctx.pool, cat, sch, tab).map(cols => key -> cols)
              }
              .map { listed =>
                val homes = kept.map { case (key, (cat, sch, _)) => key -> (cat, sch) }.toMap
                Right(
                  Resolved(
                    listed.toMap,
                    homes,
                    sep,
                    keyed.distinct,
                    complete,
                    rowidTables(keyed.distinct, listed.toMap, declared.keySet)
                  )
                )
              }
      case Success(_) => IO.pure(Right(Resolved(Map.empty, Map.empty, None)))

  /** The statement's tables for the row-policy wrap ([[Result]]): each one resolved, and holding a
    * real `rowid` when its listed columns name one or when none are listed (fail closed, as
    * [[UnresolvedMode.Deny]] reads a table the catalog cannot list). Placeholders are not tables.
    */
  private def rowidTables(
      keyed: List[(String, (String, String, String))],
      listed: Map[String, List[String]],
      placeholders: Set[String]
  ): ai.starlake.quack.edge.rls.RealRowidTables =
    val real = keyed.filterNot { case (key, _) =>
      placeholders.contains(key.toLowerCase(Locale.ROOT))
    }
    val names = real.map { case (key, (cat, sch, tab)) =>
      val cols = listed.getOrElse(key, Nil)
      ai.starlake.quack.edge.rls.RealRowidTables.canonical(cat, sch, tab) ->
        (cols.isEmpty || cols.exists(c =>
          ai.starlake.sql.QuotedIdentifier.unquote(c).equalsIgnoreCase("rowid")
        ))
    }
    ai.starlake.quack.edge.rls.RealRowidTables(
      names.map(_._1).toSet,
      names.collect { case (n, true) => n }.toSet
    )

  /** Every physical table referenced ANYWHERE in the statement, with its bare name: the ACL
    * parser's walk ([[TableExtractor]]), which reaches FROM items, joins, CTE bodies, set-op arms
    * AND expression subqueries (a table the map never mentions can neither resolve nor deny), and
    * binds CTE names as DuckDB does. JSqlParser's own TablesNamesFinder dropped every table named
    * like a CTE of the statement, so the dbt shape `WITH customer AS (SELECT * FROM customer)
    * SELECT c_email FROM customer`, whose body reads the real table, could not be resolved and was
    * denied. A name missing a catalog or schema gets the session's. The flag is false when the walk
    * failed, reported a construct it does not read, or met a name of more than three parts.
    */
  private def collectTables(
      stmt: Select,
      ctx: SchemaContext
  ): (List[(net.sf.jsqlparser.schema.Table, (String, String, String))], Boolean) =
    val extraction         = Try(TableExtractor.extract(stmt)).toOption
    val tables             = extraction.map(_.tables).getOrElse(Nil)
    def unquote(s: String) = ai.starlake.sql.QuotedIdentifier.unquote(s)
    val placed             = tables.flatMap { t =>
      t.getNameParts.asScala.toList.reverse.map(unquote) match
        case tab :: Nil =>
          Some(t -> (ctx.defaultDatabase.getOrElse(""), ctx.defaultSchema.getOrElse(""), tab))
        case sch :: tab :: Nil =>
          Some(t -> (ctx.defaultDatabase.getOrElse(""), sch, tab))
        case cat :: sch :: tab :: Nil =>
          Some(t -> (cat, sch, tab))
        case _ => None
    }
    val complete = extraction.exists(_.unsupported.isEmpty) && placed.size == tables.size
    (placed, complete)

/** DuckDB positional column references (`#n`, 1-based, FROM-relative) resolved to the column names
  * the policy matcher needs. The quack client pushes every scan down as `SELECT #1, #2 FROM
  * <table>`; jsqlparser parses `#1` as a Column literally named `#1`, the resolver finds no such
  * column and, in the default lenient mode, forwards the scan UNMASKED: every column policy holder
  * could read the masked values through ATTACH (issue #114, second report).
  *
  * Only the shape whose numbering is unambiguous is resolved: one SELECT, no WITH, no set
  * operation, FROM exactly one physical table with no join, whose column list the catalog knows.
  * There `#n` means the table's n-th column in the projection, in WHERE / HAVING / GROUP BY and
  * inside expressions alike. Everything else is refused fail-closed rather than guessed: joins and
  * derived tables number a combined list, ORDER BY numbers the PROJECTION instead, and a leftover
  * `#n` after the walk (an ORDER BY term, a position the walk did not reach) trips the textual
  * count. A statement without any `#n` is returned untouched, so nothing else is reshaped.
  */
private[cls] object PositionalReferences:

  private val PositionalRe = """^#(\d+)$""".r

  /** `#n` outside single-quoted literals and quoted identifiers (`'#1'` is text, `"#1"` a name). */
  private val TextualRe = """(?<!["\w])#\d+""".r
  private val LiteralRe = """'(?:[^']|'')*'""".r

  private def textualCount(sql: String): Int =
    TextualRe.findAllMatchIn(LiteralRe.replaceAllIn(sql, "''")).size

  private val UnsupportedShape =
    "positional column references (#n) are supported only in a single-table SELECT " +
      "(no join, subquery or set operation) and not as an ORDER BY, GROUP BY or HAVING term; " +
      "name the columns instead"

  /** The clauses where DuckDB does NOT number the FROM columns: ORDER BY numbers the projection,
    * GROUP BY / HAVING bind `#n` to neither. A `#n` there is refused, never renamed.
    */
  private def hasPositionalInProjectionRelativeClause(ps: PlainSelect): Boolean =
    val orderBy = Option(ps.getOrderByElements).map(_.asScala.map(_.toString).mkString(" "))
    val groupBy = Option(ps.getGroupBy).map(_.toString)
    val having  = Option(ps.getHaving).map(_.toString)
    List(orderBy, groupBy, having).flatten.exists(textualCount(_) > 0)

  def resolve(sql: String, schema: Map[String, List[String]]): Either[String, String] =
    if textualCount(sql) == 0 then Right(sql)
    else
      Try(CCJSqlParserUtil.parseStatements(sql)).toOption
        .map(_.asScala.toList) match
        case Some(List(ps: PlainSelect))
            if ps.getWithItemsList == null || ps.getWithItemsList.isEmpty =>
          val single = Option(ps.getJoins).forall(_.isEmpty)
          Option(ps.getFromItem) match
            case Some(t: net.sf.jsqlparser.schema.Table) if single =>
              val cols = schema.getOrElse(t.getName, Nil)
              if hasPositionalInProjectionRelativeClause(ps) then Left(UnsupportedShape)
              else if cols.isEmpty then
                Left(s"positional column references (#n) on ${t.getName}: table columns unknown")
              else
                val walk = new Numbering(cols)
                Try(walk.getTables(ps: net.sf.jsqlparser.statement.Statement)).toOption match
                  case None    => Left(UnsupportedShape)
                  case Some(_) =>
                    walk.failure match
                      case Some(reason) => Left(reason)
                      case None         =>
                        val named = ps.toString
                        if textualCount(named) > 0 then Left(UnsupportedShape) else Right(named)
            case _ => Left(UnsupportedShape)
        case _ => Left(UnsupportedShape)

  /** Rides jsqlparser's own traversal and renames every `#n` Column in place. */
  private final class Numbering(cols: List[String])
      extends net.sf.jsqlparser.util.TablesNamesFinder[java.lang.Void]:
    var failure: Option[String] = None

    override def visit[S](column: net.sf.jsqlparser.schema.Column, context: S): java.lang.Void =
      Option(column.getColumnName).foreach {
        case PositionalRe(digits) =>
          val n = Try(digits.toInt).getOrElse(0)
          if n < 1 || n > cols.size then
            failure =
              Some(s"positional column reference #$digits out of range (${cols.size} columns)")
          else column.setColumnName(quoteIfNeeded(cols(n - 1)))
        case _ => ()
      }
      super.visit(column, context)

    private def quoteIfNeeded(name: String): String =
      if name.matches("[a-z_][a-z0-9_]*") then name
      else "\"" + name.replace("\"", "\"\"") + "\""
