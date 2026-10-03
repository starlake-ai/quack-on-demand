package ai.starlake.quack.edge.rls

import ai.starlake.acl.parser.{AstChildren, TableExtractor}
import ai.starlake.quack.edge.policy.TimeTravelCarrier
import ai.starlake.quack.edge.policy.TimeTravelCarrier.Carry
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RoleRowPolicy
import ai.starlake.sql.ParserInput
import com.typesafe.scalalogging.LazyLogging
import net.sf.jsqlparser.expression.{Alias, Expression}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.ExplainStatement
import net.sf.jsqlparser.statement.piped.FromQuery
import net.sf.jsqlparser.statement.select.{
  AllColumns,
  FromItem,
  Join,
  ParenthesedFromItem,
  ParenthesedSelect,
  PlainSelect,
  Select
}

import scala.jdk.CollectionConverters._
import scala.util.Try

/** Schema/catalog defaults in scope when the statement runs, used to qualify bare table references
  * (`customer` -> catalog `acme_tpch`, schema `tpch1`) so they match policies keyed on
  * `catalog.schema.table`. Mirrors [[ai.starlake.quack.edge.cls.SchemaContext]]; kept local so the
  * row-level rewriter does not depend on the column-level package.
  */
final case class SchemaContext(
    defaultDatabase: Option[String],
    defaultSchema: Option[String]
)

/** Row-level security rewriter. For every base table referenced by a SELECT that carries a matching
  * [[ai.starlake.quack.ondemand.state.RoleRowPolicy RoleRowPolicy]] for the requesting principal,
  * the table is replaced by an inline filtered view
  * `(SELECT * FROM <table> WHERE <predicate>) <alias>`, so the node only ever sees the rows the
  * predicate admits. Predicates from multiple policies on the same table are combined with **OR**
  * (permissive union - a row is visible if ANY of the principal's roles allow it).
  *
  * Wrapping the BASE table (rather than appending a top-level WHERE) keeps the filter correct under
  * joins, set operations, CTEs and subqueries, and - when stacked under the column-level rewriter -
  * means row filtering runs on the true values before any column masking is applied.
  *
  * `enabled` is the kill switch, on by default. When false every call short-circuits to
  * [[ai.starlake.quack.edge.rls.RowPolicyRewriter.Passthrough Outcome.Passthrough]]. Operators opt
  * out via `quack-on-demand.rls.enabled = false` (`QOD_RLS_ENABLED=false`).
  */
object RowPolicyRewriter:
  sealed trait Outcome
  final case class Rewritten(sql: String) extends Outcome
  case object Passthrough                 extends Outcome

  /** SQL could not be parsed, so its policy tables cannot be filtered. The router DENIES it
    * (fail-closed), tagged `parse_failed` on `row_policy_rewrites_total` so dashboards can split
    * "rewriter blind" from a policy refusal.
    */
  case object PassthroughParseFailed extends Outcome

  /** The statement parses but holds a shape the rewrite cannot filter or cannot ship (a
    * parenthesized statement reading a policy table, a syntax node the walk cannot follow, DuckDB's
    * `//` integer division). Denied by the router with `reason`, a fixed string that never carries
    * the SQL, so the caller learns what to change.
    */
  final case class Refused(reason: String) extends Outcome

  /** A stored row policy predicate could not be applied at splice time (e.g. it fails to re-parse
    * once wrapped and OR-combined with the statement's own SQL). Should never happen for a
    * predicate that passed [[RowPredicateValidator]] at create/update time, but is load-bearing for
    * rows written before that validator rejected the splice-unsafe shape (a trailing line comment,
    * a block comment, a bare semicolon) that produced this failure mode. A policy that cannot be
    * applied must deny the statement rather than forward it unfiltered - forwarding would silently
    * disable row-level security for every row the unapplied policy was meant to admit or restrict.
    * `reason` is a fixed, non-identifying string; it never carries the SQL or the predicate text,
    * since [[FlightSqlRouter]] surfaces it to the caller.
    */
  final case class Failed(reason: String) extends Outcome

  private val TokenRegex = "\\$\\{[a-zA-Z]+\\}".r

  /** A shape the rewrite cannot filter, refused with a fixed reason (never the SQL). */
  private final class Refusal(val reason: String) extends RuntimeException(reason)

  private val InexactRefusal =
    "row policies cannot be applied to a statement using integer division (//); " +
      "split the statement or compute the division outside the filtered read"

  private val UnwalkedRefusal =
    "the statement holds a syntax node the row policy rewrite cannot walk"

  private val StatementSourceRefusal =
    "a parenthesized statement in table position reads a row-policy table; select from it instead"

  private val UnfilteredRefusal =
    "a row-policy table is read where the row filter cannot reach it (a PIVOT or UNPIVOT " +
      "source); pivot a subquery instead: PIVOT (SELECT * FROM t) ..."

  private val ExplainRefusal =
    "EXPLAIN of a statement reading a row-policy table is refused (EXPLAIN ANALYZE runs it " +
      "unfiltered, and a plan reports row counts); run the statement itself"

  private val TableStatementRefusal =
    "a TABLE statement in expression position (ARRAY(TABLE t), ANY (TABLE t)) reads a " +
      "row-policy table; use a subquery selecting from it instead"

  /** SQL-escape a scalar value into a quoted literal: `O'Brien` -> `'O''Brien'`. */
  private def lit(v: String): String = "'" + v.replace("'", "''") + "'"

  /** jsqlparser keeps the quotes on quoted identifiers (`"customer"`), so policy matching must
    * strip them or a quoted reference silently escapes its row policies (fail-open). The shared
    * reading of both DuckDB 2.0 delimiters ([[ai.starlake.sql.QuotedIdentifier]]); only the MATCH
    * uses the stripped form - the rewritten SQL keeps the caller's quoted identifiers.
    */
  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)

  /** Build the identity-token substitution map for one principal. List tokens that resolve to an
    * empty set collapse to `NULL` so `col IN (${groups})` becomes `col IN (NULL)` - a predicate
    * that matches nothing (safe-restrictive) rather than invalid `IN ()`.
    */
  private def tokenValues(eff: EffectiveSet): Map[String, String] =
    def listLit(xs: List[String]): String =
      if xs.isEmpty then "NULL" else xs.map(lit).mkString(", ")
    val tenantId = eff.user.tenant.getOrElse("")
    Map(
      "user"     -> lit(eff.user.username),
      "tenant"   -> lit(tenantId),
      "tenantId" -> lit(tenantId),
      "roles"    -> listLit(eff.roles.map(_.name)),
      "groups"   -> listLit(eff.groups.map(_.name))
    )

  /** Replace every `${token}`. Known tokens expand to their literal/literal-list; any unknown
    * `${...}` that slipped past the create-time validator collapses to `NULL` (safe-restrictive).
    */
  private def substitute(predicate: String, values: Map[String, String]): String =
    TokenRegex.replaceAllIn(
      predicate,
      m =>
        val name = m.matched.substring(2, m.matched.length - 1)
        // replaceAllIn treats $ and \ in the replacement as group refs; quote them out.
        java.util.regex.Matcher.quoteReplacement(values.getOrElse(name, "NULL"))
    )

class RowPolicyRewriter(enabled: Boolean = true) extends LazyLogging:
  import RowPolicyRewriter._

  def rewrite(
      sql: String,
      kind: StatementKind,
      eff: EffectiveSet,
      ctx: SchemaContext
  ): Outcome =
    if !enabled then Passthrough
    else if eff.user.tenant.isEmpty then Passthrough // superuser: no row filtering
    else if kind != StatementKind.Select then Passthrough
    else if eff.rowPolicies.isEmpty then Passthrough
    else
      // Parsed from the lexer-canonical rendering, never the raw text: JSqlParser tokenizes
      // DuckDB's escape and dollar-quoted strings differently, and a table it cannot see is a
      // table no row policy filters. A rendering refusal is input DuckDB rejects anyway.
      // A time-travel clause is invisible to jsqlparser: carry it on its table reference, which
      // maybeWrap keeps as the BASE table inside the filtered view, and put it back afterwards
      // (TimeTravelCarrier). A Passthrough forwards the caller's original text, clause included.
      val parsed = ParserInput
        .render(sql)
        .toOption
        .flatMap { in =>
          TimeTravelCarrier.carry(in.text) match
            case Carry.Absent                    => Some((in.text, Vector.empty[String], in.exact))
            case Carry.Unplaceable               => None
            case Carry.Carried(carried, clauses) => Some((carried, clauses, in.exact))
        }
        .flatMap((text, clauses, exact) =>
          Try(ai.starlake.sql.JSqlStatements.parseFirst(text)).toOption.map((_, clauses, exact))
        )
      parsed match
        case None                                 => PassthroughParseFailed
        case Some((stmt: Select, clauses, exact)) =>
          val values  = tokenValues(eff)
          val changed = new java.util.concurrent.atomic.AtomicBoolean(false)
          try
            // Taken before the walk replaces them: every policy table the ACL parser's
            // depth-complete walk sees, as the very AST nodes the rewrite walk wraps.
            val policyReads = TableExtractor.extract(stmt).tables.filter { t =>
              matchingPolicies(t, eff, ctx).nonEmpty
            }
            val walker = new DeepWalker(eff, ctx, values, changed)
            walker.walk(stmt)
            // Completeness (audit F21): a policy table in a position the walk holds no slot for
            // (a PIVOT / UNPIVOT source) would reach the node unfiltered. Refused, never passed.
            if policyReads.exists(t => !walker.wrappedTables.contains(t)) then
              Refused(UnfilteredRefusal)
            // The rewrite is printed from the parser input; when that is not the caller's
            // statement (ParserInput.Rendered.exact) shipping it would run a different one.
            else if !changed.get() then Passthrough
            // A clause that cannot be put back would read the current data, not the snapshot.
            else if clauses.nonEmpty && !TimeTravelCarrier.restore(stmt, clauses) then
              PassthroughParseFailed
            else if exact then Rewritten(stmt.toString)
            else Refused(InexactRefusal)
          catch
            case r: Refusal => Refused(r.reason)
            // A predicate that fails to apply at rewrite time (should never happen - the
            // create-time validator already parsed it) must not crash the request path, but it
            // also must not forward the statement unfiltered: that would silently disable the
            // row policy for the caller. Fail closed instead of Passthrough. Load-bearing for
            // rows stored before RowPredicateValidator rejected splice-unsafe predicates (a
            // trailing `--` comment, etc.) - see Failed's doc comment.
            case _: Throwable =>
              logger.warn(
                "row policy failed to apply at rewrite time for tenant={} user={}; denying",
                eff.user.tenant.getOrElse("-"),
                eff.user.username
              )
              Failed("row policy failed to apply")
        // An executing EXPLAIN classifies as its statement (Select): DuckDB runs it under EXPLAIN
        // ANALYZE, and any plan reports row counts, so one reading a policy table is refused.
        case Some((ex: ExplainStatement, _, _)) =>
          if explainReadsPolicyTable(ex, eff, ctx) then Refused(ExplainRefusal) else Passthrough
        // DESCRIBE / SHOW: no row reaches the caller. Any other kind never gets here (not Select),
        // and the protected-write guard analyses it instead.
        case Some(_) => Passthrough

  private def explainReadsPolicyTable(
      ex: ExplainStatement,
      eff: EffectiveSet,
      ctx: SchemaContext
  ): Boolean =
    Option(ex.getTable).exists(t => matchingPolicies(t, eff, ctx).nonEmpty) ||
      (ex.getStatement match
        case sel: Select =>
          TableExtractor.extract(sel).tables.exists(t => matchingPolicies(t, eff, ctx).nonEmpty)
        case _ => false)

  // ---------- table-occurrence walk ----------

  /** Depth-complete rewrite walk. Every jsqlparser node reachable from the statement is visited
    * reflectively, and every holder that owns a from-item slot (PlainSelect, Join,
    * ParenthesedFromItem, FromQuery) gets a matching base table in that slot wrapped. Reaching
    * holders through their parents' accessors rather than a per-clause enumeration is what makes
    * the walk complete: a subquery in WHERE / EXISTS / IN / select items / HAVING / ORDER BY /
    * window clauses (or a clause type added by a future jsqlparser) arrives here without being
    * named, so it cannot silently escape row filtering - the fail-open class the hand-rolled FROM
    * walk this replaces had for FROM-shorthand, parenthesized joins, and every expression-position
    * subquery. A bare Table reached from an expression is a column qualifier, never a slot, so it
    * is left alone. Freshly built wrappers are marked visited so a wrapped table is not wrapped
    * again (and a policy predicate's own subtree is never rewritten, preserving prior semantics).
    */
  private final class DeepWalker(
      eff: EffectiveSet,
      ctx: SchemaContext,
      values: Map[String, String],
      changed: java.util.concurrent.atomic.AtomicBoolean
  ):
    private val visited: java.util.Set[AnyRef] =
      java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
      )

    /** The base tables this walk wrapped, by identity: the completeness check's other side. */
    val wrappedTables: java.util.Set[Table] =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[Table, java.lang.Boolean]())

    def walk(node: AnyRef): Unit =
      if node != null && visited.add(node) then
        node match
          case ps: PlainSelect => wrapped(ps.getFromItem).foreach(ps.setFromItem)
          case j: Join         => wrapped(j.getFromItem).foreach { w => j.setFromItem(w); () }
          case pfi: ParenthesedFromItem =>
            // `(TABLE t)` / `(SUMMARIZE t)`: JSqlParser holds `t` as an alias, so there is no slot
            // to wrap, while DuckDB reads every row of t. Refused when t carries a policy.
            TableExtractor
              .statementSource(pfi)
              .filter(_.forall(t => matchingPolicies(t, eff, ctx).nonEmpty))
              .foreach(_ => throw new Refusal(StatementSourceRefusal))
            wrapped(pfi.getFromItem).foreach(pfi.setFromItem)
          case fq: FromQuery => wrapped(fq.getFromItem).foreach { w => fq.setFromItem(w); () }
          case f: net.sf.jsqlparser.expression.Function =>
            // `ARRAY(TABLE t)` / `x = ANY (TABLE t)`: JSqlParser holds `t` as the argument column
            // of a function flagged TABLE, so there is no slot to wrap, while DuckDB reads every
            // row of t. Refused when t carries a policy (or cannot be read back).
            TableExtractor
              .tableStatementSources(f)
              .filter(_.exists(_.forall(t => matchingPolicies(t, eff, ctx).nonEmpty)))
              .foreach(_ => throw new Refusal(TableStatementRefusal))
          case ts: net.sf.jsqlparser.statement.select.TableStatement =>
            // JSqlParser 5.4.16x reads `TABLE t` as a statement wherever it appears (`x = ANY
            // (TABLE t)`, `IN (TABLE t)`, `FROM (TABLE t)`, the whole statement): t has no FROM
            // slot to wrap while DuckDB reads every row of it. Refused when t carries a policy.
            Option(ts.getTable)
              .filter(t => matchingPolicies(t, eff, ctx).nonEmpty)
              .foreach(_ => throw new Refusal(TableStatementRefusal))
          case _ => ()
        // Only the reflective call is guarded (some jsqlparser getters cast `this` and throw on
        // a sibling subtype): an exception from the walk itself, a refusal or a predicate that
        // fails to apply on a nested table, must reach rewrite's handler and deny.
        AstChildren.accessors(node.getClass).foreach(m => route(AstChildren.read(m, node)))

    /** Some(wrapper) when the slot holds a base table matching a policy, None otherwise. */
    private def wrapped(item: FromItem): Option[FromItem] =
      item match
        case t: Table =>
          val w = maybeWrap(t, eff, ctx, values, changed)
          if w eq t then None
          else
            visited.add(w): Unit
            wrappedTables.add(t): Unit
            Some(w)
        case _ => None

    /** Children JSqlParser keeps outside its node types are followed too (a MAP literal's
      * `Map.Entry` pairs, any `java.util.Map`); a value of a type with no rule here is refused,
      * since a table it holds could not be filtered.
      */
    private def route(value: Any): Unit =
      value match
        case null                                  => ()
        case it: java.lang.Iterable[?]             => it.asScala.foreach(route)
        case arr: Array[?]                         => arr.foreach(route)
        case e: java.util.Map.Entry[?, ?]          => route(e.getKey); route(e.getValue)
        case m: java.util.Map[?, ?]                => m.entrySet.asScala.foreach(route)
        case n: AnyRef if AstChildren.isAstNode(n) => walk(n)
        case leaf if AstChildren.isLeaf(leaf)      => ()
        case _                                     => throw new Refusal(UnwalkedRefusal)

  /** The principal's row policies covering `t`, its bare parts qualified by the session defaults. A
    * two-part name is read schema-first; one headed by an attached catalog, which DuckDB binds
    * catalog-first, never reaches here for a policy holder: the protected-write guard refuses it on
    * every statement kind (audit F20, F22).
    */
  private def matchingPolicies(
      t: Table,
      eff: EffectiveSet,
      ctx: SchemaContext
  ): List[RoleRowPolicy] =
    val name    = unquote(t.getName)
    val schema  = Option(t.getSchemaName).map(unquote).getOrElse(ctx.defaultSchema.getOrElse(""))
    val catalog = Option(t.getDatabase)
      .flatMap(d => Option(d.getDatabaseName))
      .map(unquote)
      .getOrElse(ctx.defaultDatabase.getOrElse(""))
    eff.rowPolicies.filter(p => policyMatches(p, catalog, schema, name))

  /** If `t` matches one or more of the principal's row policies, return a parenthesed
    * `(SELECT * FROM t WHERE <ORed predicate>)` carrying t's original alias; else return `t`
    * unchanged.
    */
  private def maybeWrap(
      t: Table,
      eff: EffectiveSet,
      ctx: SchemaContext,
      values: Map[String, String],
      changed: java.util.concurrent.atomic.AtomicBoolean
  ): net.sf.jsqlparser.statement.select.FromItem =
    val matched = matchingPolicies(t, eff, ctx)
    if matched.isEmpty then t
    else
      val predicate =
        matched.map(p => "(" + substitute(p.predicateSql, values) + ")").mkString(" OR ")
      val expr: Expression = CCJSqlParserUtil.parseCondExpression(predicate)

      val alias: Alias = t.getAlias
      // Rebuild from the RAW parts: a quoted identifier stays quoted in the output.
      val baseTable = new Table(t.getName)
      Option(t.getSchemaName).foreach(_ => baseTable.setSchemaName(t.getSchemaName))
      Option(t.getDatabase).foreach(baseTable.setDatabase)
      // A time-travel clause pins the BASE table: on the wrapper it would be invalid SQL, and
      // dropping it would filter the current data instead of the snapshot.
      baseTable.setTimeTravel(t.getTimeTravel): Unit
      baseTable.setTimeTravelStrAfterAlias(t.getTimeTravelStrAfterAlias): Unit

      val inner = new PlainSelect()
      inner.addSelectItem(new AllColumns())
      inner.setFromItem(baseTable)
      inner.setWhere(expr)

      val wrapped = new ParenthesedSelect()
      wrapped.setSelect(inner)
      // Preserve the caller's alias so outer `alias.col` references still resolve; when the table
      // had no alias, fall back to the table name so a bare `SELECT t.col` keeps working.
      wrapped.setAlias(if alias != null then alias else new Alias(t.getName))
      changed.set(true)
      wrapped

  private def policyMatches(
      p: RoleRowPolicy,
      catalog: String,
      schema: String,
      table: String
  ): Boolean =
    ai.starlake.quack.edge.policy.PolicyCoverage.covers(
      p.catalogName,
      p.schemaName,
      p.tableName,
      catalog,
      schema,
      table
    )
