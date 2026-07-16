package ai.starlake.quack.edge.rls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RoleRowPolicy
import net.sf.jsqlparser.expression.{Alias, Expression}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.{
  AllColumns,
  ParenthesedSelect,
  PlainSelect,
  Select,
  SetOperationList
}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

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
  * [[RoleRowPolicy]] for the requesting principal, the table is replaced by an inline filtered view
  * `(SELECT * FROM <table> WHERE <predicate>) <alias>`, so the node only ever sees the rows the
  * predicate admits. Predicates from multiple policies on the same table are combined with **OR**
  * (permissive union - a row is visible if ANY of the principal's roles allow it).
  *
  * Wrapping the BASE table (rather than appending a top-level WHERE) keeps the filter correct under
  * joins, set operations, CTEs and subqueries, and - when stacked under the column-level rewriter -
  * means row filtering runs on the true values before any column masking is applied.
  *
  * `enabled` is the kill switch, on by default. When false every call short-circuits to
  * [[Outcome.Passthrough]]. Operators opt out via `quack-on-demand.rls.enabled = false`
  * (`QOD_RLS_ENABLED=false`).
  */
object RowPolicyRewriter:
  sealed trait Outcome
  final case class Rewritten(sql: String) extends Outcome
  case object Passthrough                 extends Outcome

  /** SQL could not be parsed. Routed identically to [[Passthrough]] (original SQL forwarded) but
    * tagged separately on `row_policy_rewrites_total` so dashboards can split "rewriter blind" from
    * "no policy applied".
    */
  case object PassthroughParseFailed extends Outcome

  private val Wildcard   = RoleRowPolicy.Wildcard
  private val TokenRegex = "\\$\\{[a-zA-Z]+\\}".r

  /** SQL-escape a scalar value into a quoted literal: `O'Brien` -> `'O''Brien'`. */
  private def lit(v: String): String = "'" + v.replace("'", "''") + "'"

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

class RowPolicyRewriter(enabled: Boolean = true):
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
      Try(CCJSqlParserUtil.parse(sql)) match
        case Failure(_)            => PassthroughParseFailed
        case Success(stmt: Select) =>
          val values  = tokenValues(eff)
          val changed = new java.util.concurrent.atomic.AtomicBoolean(false)
          try
            rewriteSelect(stmt, eff, ctx, values, changed)
            if changed.get() then Rewritten(stmt.toString) else Passthrough
          catch
            // A predicate that fails to parse at rewrite time (should never happen - the
            // create-time validator already parsed it) must not crash the request path.
            case _: Throwable => Passthrough
        case Success(_) => Passthrough

  // ---------- table-occurrence walk (mirrors ColumnPolicyRewriter.collectTables) ----------

  private def rewriteSelect(
      sel: Select,
      eff: EffectiveSet,
      ctx: SchemaContext,
      values: Map[String, String],
      changed: java.util.concurrent.atomic.AtomicBoolean
  ): Unit =
    // CTE bodies first, so a base table inside a WITH item is filtered too.
    Option(sel.getWithItemsList).foreach(_.asScala.foreach { wi =>
      Option(wi.getParenthesedStatement).foreach {
        case ps: ParenthesedSelect => rewriteSelect(ps.getSelect, eff, ctx, values, changed)
        case _                     => ()
      }
    })
    sel match
      case ps: PlainSelect =>
        Option(ps.getFromItem).foreach {
          case t: Table               => ps.setFromItem(maybeWrap(t, eff, ctx, values, changed))
          case sub: ParenthesedSelect => rewriteSelect(sub.getSelect, eff, ctx, values, changed)
          case _                      => ()
        }
        Option(ps.getJoins).foreach(_.asScala.foreach { j =>
          Option(j.getRightItem).foreach {
            case t: Table               => j.setRightItem(maybeWrap(t, eff, ctx, values, changed))
            case sub: ParenthesedSelect => rewriteSelect(sub.getSelect, eff, ctx, values, changed)
            case _                      => ()
          }
        })
      case ps: ParenthesedSelect => rewriteSelect(ps.getSelect, eff, ctx, values, changed)
      case sol: SetOperationList =>
        Option(sol.getSelects)
          .foreach(_.asScala.foreach(rewriteSelect(_, eff, ctx, values, changed)))
      case _ => ()

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
    val name    = t.getName
    val schema  = Option(t.getSchemaName).getOrElse(ctx.defaultSchema.getOrElse(""))
    val catalog = Option(t.getDatabase)
      .flatMap(d => Option(d.getDatabaseName))
      .getOrElse(ctx.defaultDatabase.getOrElse(""))

    val matched = eff.rowPolicies.filter(p => policyMatches(p, catalog, schema, name))
    if matched.isEmpty then t
    else
      val predicate =
        matched.map(p => "(" + substitute(p.predicateSql, values) + ")").mkString(" OR ")
      val expr: Expression = CCJSqlParserUtil.parseCondExpression(predicate)

      val alias: Alias = t.getAlias
      val baseTable    = new Table(name)
      Option(t.getSchemaName).foreach(_ => baseTable.setSchemaName(t.getSchemaName))
      Option(t.getDatabase).foreach(baseTable.setDatabase)

      val inner = new PlainSelect()
      inner.addSelectItem(new AllColumns())
      inner.setFromItem(baseTable)
      inner.setWhere(expr)

      val wrapped = new ParenthesedSelect()
      wrapped.setSelect(inner)
      // Preserve the caller's alias so outer `alias.col` references still resolve; when the table
      // had no alias, fall back to the table name so a bare `SELECT t.col` keeps working.
      wrapped.setAlias(if alias != null then alias else new Alias(name))
      changed.set(true)
      wrapped

  private def policyMatches(
      p: RoleRowPolicy,
      catalog: String,
      schema: String,
      table: String
  ): Boolean =
    def matchesPart(policyPart: String, actual: String): Boolean =
      policyPart == Wildcard || policyPart.equalsIgnoreCase(actual)
    matchesPart(p.tableName, table) &&
    matchesPart(p.schemaName, schema) &&
    matchesPart(p.catalogName, catalog)
