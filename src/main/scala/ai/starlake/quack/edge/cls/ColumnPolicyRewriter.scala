package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import cats.effect.IO
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.{
  ParenthesedSelect, PlainSelect, Select, SelectItem, SetOperationList, WithItem
}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** Resolves the schema/catalog defaults in scope when parsing a SQL statement so the rewriter can
  * qualify bare table references (`customer` -> `acme_tpch.tpch1.customer`).
  */
final case class SchemaContext(
    defaultDatabase: Option[String],
    defaultSchema:   Option[String]
)

object ColumnPolicyRewriter:
  sealed trait Outcome
  final case class Rewritten(sql: String) extends Outcome
  final case class Denied(reason: String) extends Outcome
  case object Passthrough                  extends Outcome

/** Rewrites SELECT statements to enforce column-level policies carried in the caller's
  * [[EffectiveSet]]. Non-SELECT statements, superusers, users with no column policies, and
  * unparseable SQL all pass through unchanged (the query will succeed or fail on its own merit).
  *
  * Handles flat projection rewriting and recursive descent through every nested SELECT:
  * subqueries in the projection, FROM-item subqueries, CTE bodies via WITH, and
  * UNION / INTERSECT / EXCEPT arms via SetOperationList.
  */
final class ColumnPolicyRewriter(catalog: ColumnCatalog):
  import ColumnPolicyRewriter._

  def rewrite(
      sql:  String,
      kind: StatementKind,
      eff:  EffectiveSet,
      ctx:  SchemaContext
  ): IO[Outcome] = IO.defer {
    if eff.user.tenant.isEmpty            then IO.pure(Passthrough)
    else if kind != StatementKind.Select  then IO.pure(Passthrough)
    else if eff.columnPolicies.isEmpty    then IO.pure(Passthrough)
    else
      Try(CCJSqlParserUtil.parse(sql)) match
        case Failure(_)            => IO.pure(Passthrough)
        case Success(stmt: Select) => IO.delay(applyToStatement(stmt, eff, ctx))
        case Success(_)            => IO.pure(Passthrough)
  }

  private final case class DenyException(reason: String) extends RuntimeException(reason)

  /** Top-level coordinator. Walks the entire statement tree calling per-PlainSelect logic. */
  private def applyToStatement(stmt: Select, eff: EffectiveSet, ctx: SchemaContext): Outcome =
    val changed = new java.util.concurrent.atomic.AtomicBoolean(false)
    try
      // CTEs declared on the top-level Select.
      Option(stmt.getWithItemsList).foreach(_.asScala.foreach { wi =>
        applyToWithItem(wi, eff, ctx, changed)
      })
      // The Select body (PlainSelect, SetOperationList, ParenthesedSelect, ...).
      applyToSelectBody(stmt, eff, ctx, changed)
      if changed.get then Rewritten(stmt.toString) else Passthrough
    catch
      case e: DenyException => Denied(e.reason)

  /** Dispatch helper: recurses into the appropriate kind of select node. */
  private def applyToSelectBody(
      sel:     Select,
      eff:     EffectiveSet,
      ctx:     SchemaContext,
      changed: java.util.concurrent.atomic.AtomicBoolean
  ): Unit =
    sel match
      case ps: PlainSelect =>
        applyToPlainSelect(ps, eff, ctx, changed)
      case ps: ParenthesedSelect =>
        // A ParenthesedSelect may carry its own WITH clause.
        Option(ps.getWithItemsList).foreach(_.asScala.foreach { wi =>
          applyToWithItem(wi, eff, ctx, changed)
        })
        applyToSelectBody(ps.getSelect, eff, ctx, changed)
      case sol: SetOperationList =>
        Option(sol.getSelects).foreach(_.asScala.foreach { arm =>
          applyToSelectBody(arm, eff, ctx, changed)
        })
      case _ => ()

  /** Handle a CTE body. In JSqlParser 5.x, WithItem carries the body as a ParenthesedStatement
    * (accessible via getParenthesedStatement()), which at runtime is a ParenthesedSelect.
    */
  private def applyToWithItem(
      wi:      WithItem[?],
      eff:     EffectiveSet,
      ctx:     SchemaContext,
      changed: java.util.concurrent.atomic.AtomicBoolean
  ): Unit =
    Option(wi.getParenthesedStatement).foreach {
      case ps: ParenthesedSelect => applyToSelectBody(ps, eff, ctx, changed)
      case _                     => ()
    }

  /** Rewrite the projection items of a PlainSelect and recurse into FROM-item subqueries. */
  private def applyToPlainSelect(
      ps:      PlainSelect,
      eff:     EffectiveSet,
      ctx:     SchemaContext,
      changed: java.util.concurrent.atomic.AtomicBoolean
  ): Unit =
    val tableMap = resolveTables(ps, ctx)

    // (1) Recurse into FROM-item subqueries before walking the projection.
    Option(ps.getFromItem).foreach {
      case sub: ParenthesedSelect => applyToSelectBody(sub, eff, ctx, changed)
      case _                      => ()
    }
    Option(ps.getJoins).foreach(_.asScala.foreach { j =>
      Option(j.getRightItem).foreach {
        case sub: ParenthesedSelect => applyToSelectBody(sub, eff, ctx, changed)
        case _                      => ()
      }
    })

    // (2) Walk projection items.
    val items = ps.getSelectItems
    if items != null && !items.isEmpty then
      val it = items.listIterator()
      while it.hasNext do
        val si   = it.next()
        val expr = si.getExpression
        rewriteExpression(expr, tableMap, eff, ctx, changed) match
          case Some(newExpr) =>
            // SelectItem<T>.setExpression(T) - erase the wildcard via asInstanceOf.
            si.asInstanceOf[SelectItem[Expression]].setExpression(newExpr)
            changed.set(true)
          case None => ()

  /** Build alias -> (catalog, schema, table) from FROM + JOINs. Defaults come from ctx. */
  private def resolveTables(
      ps:  PlainSelect,
      ctx: SchemaContext
  ): Map[String, (String, String, String)] =
    val acc = scala.collection.mutable.Map.empty[String, (String, String, String)]
    Option(ps.getFromItem).foreach {
      case t: net.sf.jsqlparser.schema.Table => indexTable(t, ctx, acc)
      case _                                 => ()
    }
    Option(ps.getJoins).foreach(_.asScala.foreach { j =>
      Option(j.getRightItem).foreach {
        case t: net.sf.jsqlparser.schema.Table => indexTable(t, ctx, acc)
        case _                                 => ()
      }
    })
    acc.toMap

  private def indexTable(
      t:   net.sf.jsqlparser.schema.Table,
      ctx: SchemaContext,
      acc: scala.collection.mutable.Map[String, (String, String, String)]
  ): Unit =
    val rawName    = t.getName
    val schemaName = Option(t.getSchemaName).getOrElse(ctx.defaultSchema.getOrElse(""))
    val catalog    = Option(t.getDatabase).flatMap(d => Option(d.getDatabaseName))
                       .getOrElse(ctx.defaultDatabase.getOrElse(""))
    val key        = Option(t.getAlias).map(_.getName).getOrElse(rawName)
    acc(key) = (catalog, schemaName, rawName)

  /** Replace the expression if it resolves to a covered column.
    * Returns Some(newExpr) if anything changed, None if nothing did.
    * Throws DenyException on deny. If the expression contains a ParenthesedSelect,
    * recurses into it and reports changed if any inner rewrite happened.
    */
  private def rewriteExpression(
      expr:     Expression,
      tableMap: Map[String, (String, String, String)],
      eff:      EffectiveSet,
      ctx:      SchemaContext,
      changed:  java.util.concurrent.atomic.AtomicBoolean
  ): Option[Expression] =
    expr match
      case col: net.sf.jsqlparser.schema.Column =>
        matchingPolicy(col, tableMap, eff) match
          case Some(p) if p.action == "deny" =>
            throw DenyException(s"column ${formatColumn(col)} is denied")
          case Some(p) =>
            Some(CCJSqlParserUtil.parseExpression(p.transformSql.get))
          case None => None
      case sub: ParenthesedSelect =>
        val before = changed.get
        applyToSelectBody(sub, eff, ctx, changed)
        if changed.get && !before then Some(sub) else None
      case _ =>
        val local = new java.util.concurrent.atomic.AtomicBoolean(false)
        walkAndReplace(expr, tableMap, eff, ctx, local, changed)
        if local.get then Some(expr) else None

  private def walkAndReplace(
      expr:    Expression,
      tableMap: Map[String, (String, String, String)],
      eff:     EffectiveSet,
      ctx:     SchemaContext,
      local:   java.util.concurrent.atomic.AtomicBoolean,
      changed: java.util.concurrent.atomic.AtomicBoolean
  ): Expression =
    expr match
      case col: net.sf.jsqlparser.schema.Column =>
        matchingPolicy(col, tableMap, eff) match
          case Some(p) if p.action == "deny" =>
            throw DenyException(s"column ${formatColumn(col)} is denied")
          case Some(p) =>
            local.set(true)
            changed.set(true)
            CCJSqlParserUtil.parseExpression(p.transformSql.get)
          case None => col
      case sub: ParenthesedSelect =>
        applyToSelectBody(sub, eff, ctx, changed)
        sub
      case fn: net.sf.jsqlparser.expression.Function =>
        Option(fn.getParameters).foreach { params =>
          val rawList = params.asInstanceOf[ExpressionList[Expression]]
          val it = rawList.listIterator()
          while it.hasNext do
            val cur = it.next()
            val nxt = walkAndReplace(cur, tableMap, eff, ctx, local, changed)
            if nxt ne cur then it.set(nxt)
        }
        fn
      case b: net.sf.jsqlparser.expression.BinaryExpression =>
        b.setLeftExpression(walkAndReplace(b.getLeftExpression, tableMap, eff, ctx, local, changed))
        b.setRightExpression(walkAndReplace(b.getRightExpression, tableMap, eff, ctx, local, changed))
        b
      case p: net.sf.jsqlparser.expression.Parenthesis =>
        p.setExpression(walkAndReplace(p.getExpression, tableMap, eff, ctx, local, changed))
        p
      case other => other

  private def matchingPolicy(
      col:      net.sf.jsqlparser.schema.Column,
      tableMap: Map[String, (String, String, String)],
      eff:      EffectiveSet
  ): Option[RoleColumnPolicy] =
    val colName  = Option(col.getColumnName).getOrElse("").toLowerCase
    val tableKey = Option(col.getTable).map(_.getName).getOrElse {
      // When there is exactly one FROM item treat it as the implicit table source.
      if tableMap.size == 1 then tableMap.keys.head else ""
    }
    val (cat, sch, tab) = tableMap.getOrElse(tableKey, ("", "", ""))
    // Primary lookup: match on fully-resolved (catalog, schema, table) coordinates.
    val direct = eff.columnPolicies.find { p =>
      matchWildcard(p.catalogName, cat) &&
      matchWildcard(p.schemaName,  sch) &&
      matchWildcard(p.tableName,   tab) &&
      p.columnName.equalsIgnoreCase(colName)
    }
    if direct.isDefined then direct
    // Fallback for columns in derived-table contexts where the base table cannot be resolved
    // (e.g. outer query referencing a column produced by a subquery alias). Apply masking by
    // column name alone when the column has no explicit table qualifier and no base-table
    // coordinates could be inferred.  This is conservative: if ANY policy covers this column
    // name we mask it, preventing a masked column from leaking through a derived table.
    else if tableKey.isEmpty || tab.isEmpty then
      eff.columnPolicies.find { p => p.columnName.equalsIgnoreCase(colName) }
    else None

  private def matchWildcard(grant: String, ref: String): Boolean =
    grant == RoleColumnPolicy.Wildcard || grant.equalsIgnoreCase(ref)

  private def formatColumn(col: net.sf.jsqlparser.schema.Column): String =
    Option(col.getTable).map(_.getName + "." + col.getColumnName).getOrElse(col.getColumnName)