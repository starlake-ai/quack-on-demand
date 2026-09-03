package ai.starlake.quack.edge.rls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RoleRowPolicy
import net.sf.jsqlparser.expression.{Alias, Expression}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
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

  private val TokenRegex = "\\$\\{[a-zA-Z]+\\}".r

  /** SQL-escape a scalar value into a quoted literal: `O'Brien` -> `'O''Brien'`. */
  private def lit(v: String): String = "'" + v.replace("'", "''") + "'"

  /** jsqlparser keeps the double quotes on quoted identifiers (`"customer"`), so policy matching
    * must strip them or a quoted reference silently escapes its row policies (fail-open). Mirrors
    * ColumnPolicyRewriter's unquote; only the MATCH uses the stripped form - the rewritten SQL
    * keeps the caller's original (possibly quoted) identifiers.
    */
  private def unquote(s: String): String = s.stripPrefix("\"").stripSuffix("\"")

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

  // ---- reflective descent plumbing (mirrors TableExtractorVisitor.childAccessors, which is
  // private to the ACL parser package) ----

  private val accessorCache =
    new java.util.concurrent.ConcurrentHashMap[Class[?], Array[java.lang.reflect.Method]]()

  /** The no-arg `get*` accessors of `cls` that can reach a child AST node: return type is a
    * collection, an array, or any jsqlparser AST type outside the `net.sf.jsqlparser.parser`
    * package (excluded so the walk never follows an upward parent link into a cycle).
    */
  private def childAccessors(cls: Class[?]): Array[java.lang.reflect.Method] =
    accessorCache.computeIfAbsent(
      cls,
      c =>
        c.getMethods.filter { m =>
          val rt = m.getReturnType
          m.getParameterCount == 0 &&
          m.getName.startsWith("get") &&
          (classOf[java.lang.Iterable[?]].isAssignableFrom(rt) || rt.isArray || isJsqlAstType(rt))
        }
    )

  private def isJsqlAstType(rt: Class[?]): Boolean =
    val n = rt.getName
    n.startsWith("net.sf.jsqlparser.") && !n.startsWith("net.sf.jsqlparser.parser.")

  private def isJsqlNode(value: AnyRef): Boolean = isJsqlAstType(value.getClass)

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
            new DeepWalker(eff, ctx, values, changed).walk(stmt)
            if changed.get() then Rewritten(stmt.toString) else Passthrough
          catch
            // A predicate that fails to parse at rewrite time (should never happen - the
            // create-time validator already parsed it) must not crash the request path.
            case _: Throwable => Passthrough
        case Success(_) => Passthrough

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

    def walk(node: AnyRef): Unit =
      if node != null && visited.add(node) then
        node match
          case ps: PlainSelect => wrapped(ps.getFromItem).foreach(ps.setFromItem)
          case j: Join         => wrapped(j.getFromItem).foreach { w => j.setFromItem(w); () }
          case pfi: ParenthesedFromItem => wrapped(pfi.getFromItem).foreach(pfi.setFromItem)
          case fq: FromQuery => wrapped(fq.getFromItem).foreach { w => fq.setFromItem(w); () }
          case _             => ()
        childAccessors(node.getClass).foreach { m =>
          try route(m.invoke(node))
          catch case _: Throwable => ()
        }

    /** Some(wrapper) when the slot holds a base table matching a policy, None otherwise. */
    private def wrapped(item: FromItem): Option[FromItem] =
      item match
        case t: Table =>
          val w = maybeWrap(t, eff, ctx, values, changed)
          if w eq t then None
          else
            visited.add(w): Unit
            Some(w)
        case _ => None

    private def route(value: Any): Unit =
      value match
        case null                       => ()
        case it: java.lang.Iterable[?]  => it.asScala.foreach(route)
        case arr: Array[?]              => arr.foreach(route)
        case n: AnyRef if isJsqlNode(n) => walk(n)
        case _                          => ()

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
    val name    = unquote(t.getName)
    val schema  = Option(t.getSchemaName).map(unquote).getOrElse(ctx.defaultSchema.getOrElse(""))
    val catalog = Option(t.getDatabase)
      .flatMap(d => Option(d.getDatabaseName))
      .map(unquote)
      .getOrElse(ctx.defaultDatabase.getOrElse(""))

    val matched = eff.rowPolicies.filter(p => policyMatches(p, catalog, schema, name))
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
