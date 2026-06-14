package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import ai.starlake.transpiler.JSQLColumResolver
import ai.starlake.transpiler.schema.JdbcMetaData
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.{PlainSelect, Select, SelectItem}

final class JsqltranspilerRewriter extends SchemaAwareSqlRewriter:

  import RewriteOutcome._

  private final case class DenyException(reason: String) extends RuntimeException(reason)

  def rewrite(
      sql: String,
      schema: Map[String, List[String]],
      policies: List[RoleColumnPolicy],
      defaultCatalog: Option[String],
      defaultSchema: Option[String],
      unresolvedMode: UnresolvedMode = UnresolvedMode.Deny
  ): RewriteOutcome =
    if policies.isEmpty then return Passthrough

    val schemaDef: Array[Array[String]] = schema.toArray.map { case (tableKey, cols) =>
      (tableKey +: cols).toArray
    }

    // Use the 3-arg constructor so SQL schema qualifiers (e.g. `tpch1.customer`) match the
    // schemaless rows in `schemaDef`. The 1-arg form leaves currentSchema="" and silently
    // mismatches every schema-qualified table ref. defaultCatalog/defaultSchema come from the
    // caller's SchemaContext (the session-defaults pinned at handshake time).
    val resolver = new JSQLColumResolver(
      defaultCatalog.getOrElse(""),
      defaultSchema.getOrElse(""),
      schemaDef
    )
    resolver.setErrorMode(unresolvedMode match
      case UnresolvedMode.Deny        => JdbcMetaData.ErrorMode.STRICT
      case UnresolvedMode.Passthrough => JdbcMetaData.ErrorMode.LENIENT)

    val resolvedText: String =
      try resolver.getResolvedStatementText(sql)
      catch
        // jsqltranspiler 1.9 does NOT ship a single `JSQLDataException` umbrella; it raises one
        // of the table/column/schema/catalog-not-found exceptions under `ai.starlake.transpiler.*`.
        // Treat any of these as the "unknown coordinate" case the spec calls out, and let the
        // unresolvedMode arm decide deny vs passthrough.
        case e: ai.starlake.transpiler.TableNotFoundException    => return Denied(e.getMessage)
        case e: ai.starlake.transpiler.TableNotDeclaredException => return Denied(e.getMessage)
        case e: ai.starlake.transpiler.ColumnNotFoundException   => return Denied(e.getMessage)
        case e: ai.starlake.transpiler.SchemaNotFoundException   => return Denied(e.getMessage)
        case e: ai.starlake.transpiler.CatalogNotFoundException  => return Denied(e.getMessage)
        case _: net.sf.jsqlparser.JSQLParserException            => return ParseFailed
        case _: Throwable                                        => return ParseFailed

    val rsMeta                                          = resolver.getResultSetMetaData(sql)
    val projectionOrigins: IndexedSeq[(String, String)] =
      (1 to rsMeta.getColumnCount).toIndexedSeq.map { i =>
        (
          Option(rsMeta.getTableName(i)).getOrElse(""),
          Option(rsMeta.getColumnName(i)).getOrElse("")
        )
      }

    val parsed =
      try CCJSqlParserUtil.parse(resolvedText)
      catch case _: Throwable => return ParseFailed

    parsed match
      case sel: Select =>
        try
          val (changed, _) = applyPolicies(sel, projectionOrigins, policies)
          if changed then Rewritten(sel.toString) else Passthrough
        catch case e: DenyException => Denied(e.reason)
      case _ =>
        Passthrough

  /** Walk the resolved statement's projection items and replace any Column whose physical (table,
    * column) lineage matches a policy. Full visitor surface (CASE / CAST / OVER / IN / BETWEEN /
    * EXTRACT / WHERE / HAVING / GROUP / ORDER) is added in Task 5.
    */
  private def applyPolicies(
      sel: Select,
      origins: IndexedSeq[(String, String)],
      policies: List[RoleColumnPolicy]
  ): (Boolean, Select) =
    val changed = new java.util.concurrent.atomic.AtomicBoolean(false)
    val visitor = new PolicyVisitor(policies, changed)

    sel match
      case ps: PlainSelect =>
        // FROM-tables of this select (key -> table). Single-table case lets the visitor resolve
        // an unqualified column reference to the implicit table. Used inside composite expressions
        // (function args, BETWEEN bounds, CASE arms, etc.) where the resolver doesn't expand the
        // qualifier.
        visitor.fromTables = collectFromTables(ps)
        val items = ps.getSelectItems
        if items != null then
          val it  = items.listIterator()
          var idx = 0
          while it.hasNext do
            val si           = it.next()
            val (table, col) = if origins.indices.contains(idx) then origins(idx) else ("", "")
            // Top-level column origin override: the resolver knows the physical lineage of the
            // projection slot even if the expression at that slot is itself a Column whose name
            // is an alias of another column.
            visitor.topLevelOverride = Some((table, col))
            val expr     = si.getExpression
            val replaced = visitor.visit(expr)
            if (replaced ne expr) then
              si.asInstanceOf[SelectItem[Expression]].setExpression(replaced)
              changed.set(true)
            visitor.topLevelOverride = None
            idx += 1
        Option(ps.getWhere).foreach { w =>
          val nxt = visitor.visit(w)
          if nxt ne w then { ps.setWhere(nxt); changed.set(true) }
        }
        Option(ps.getHaving).foreach { h =>
          val nxt = visitor.visit(h)
          if nxt ne h then { ps.setHaving(nxt); changed.set(true) }
        }
        Option(ps.getGroupBy).foreach { gb =>
          val gbList = gb.getGroupByExpressionList
          if gbList != null then
            val it = gbList.listIterator()
            while it.hasNext do
              val cur = it.next()
              val nxt = visitor.visit(cur)
              if nxt ne cur then { it.set(nxt); changed.set(true) }
        }
        Option(ps.getOrderByElements).foreach { obs =>
          val it = obs.iterator()
          while it.hasNext do
            val ob  = it.next()
            val nxt = visitor.visit(ob.getExpression)
            if nxt ne ob.getExpression then { ob.setExpression(nxt); changed.set(true) }
        }
      case _ => ()
    (changed.get, sel)

  /** Build a list of (alias -> rawTableName) for the FROM + JOINs of a PlainSelect. */
  private def collectFromTables(ps: PlainSelect): List[(String, String)] =
    val buf = scala.collection.mutable.ListBuffer.empty[(String, String)]
    def add(t: net.sf.jsqlparser.schema.Table): Unit =
      val raw = t.getName
      val key = Option(t.getAlias).map(_.getName).getOrElse(raw)
      buf += (key -> raw)
    Option(ps.getFromItem).foreach {
      case t: net.sf.jsqlparser.schema.Table => add(t)
      case _                                 => ()
    }
    Option(ps.getJoins).foreach { joins =>
      val it = joins.iterator()
      while it.hasNext do
        Option(it.next().getRightItem).foreach {
          case t: net.sf.jsqlparser.schema.Table => add(t)
          case _                                 => ()
        }
    }
    buf.toList

  private final class PolicyVisitor(
      policies: List[RoleColumnPolicy],
      changed: java.util.concurrent.atomic.AtomicBoolean
  ):
    var topLevelOverride: Option[(String, String)] = None
    var fromTables: List[(String, String)]         = Nil

    /** Resolve the physical table for a Column reference. */
    private def resolveTable(col: net.sf.jsqlparser.schema.Column): String =
      Option(col.getTable).map(_.getName) match
        case Some(key) =>
          // Alias or table-name qualifier - look up the base table behind the alias.
          fromTables.find(_._1.equalsIgnoreCase(key)).map(_._2).getOrElse(key)
        case None =>
          // Unqualified - fall back to the single FROM item if there is exactly one.
          if fromTables.size == 1 then fromTables.head._2 else ""

    /** Walk `expr` and return its replacement (same instance if nothing changed). */
    def visit(expr: Expression): Expression =
      expr match
        case col: net.sf.jsqlparser.schema.Column =>
          val (tableName, columnName) = topLevelOverride.getOrElse {
            (resolveTable(col), col.getColumnName)
          }
          matchingPolicy(tableName, columnName, policies) match
            case Some(p) if p.action == RoleColumnPolicy.ActionDeny =>
              throw DenyException(s"column $tableName.$columnName is denied")
            case Some(p) =>
              changed.set(true)
              CCJSqlParserUtil.parseExpression(p.transformSql.get)
            case None => col

        case fn: net.sf.jsqlparser.expression.Function =>
          val saved = topLevelOverride
          topLevelOverride = None
          Option(fn.getParameters).foreach { params =>
            val list = params.asInstanceOf[
              net.sf.jsqlparser.expression.operators.relational.ExpressionList[Expression]
            ]
            val it = list.listIterator()
            while it.hasNext do
              val cur = it.next()
              val nxt = visit(cur)
              if nxt ne cur then it.set(nxt)
          }
          topLevelOverride = saved
          fn

        case b: net.sf.jsqlparser.expression.BinaryExpression =>
          val saved = topLevelOverride
          topLevelOverride = None
          b.setLeftExpression(visit(b.getLeftExpression))
          b.setRightExpression(visit(b.getRightExpression))
          topLevelOverride = saved
          b

        case p: net.sf.jsqlparser.expression.Parenthesis =>
          val saved = topLevelOverride
          topLevelOverride = None
          p.setExpression(visit(p.getExpression))
          topLevelOverride = saved
          p

        case ex: net.sf.jsqlparser.expression.ExtractExpression =>
          val saved = topLevelOverride
          topLevelOverride = None
          val nxt = visit(ex.getExpression)
          if nxt ne ex.getExpression then ex.setExpression(nxt)
          topLevelOverride = saved
          ex

        case bt: net.sf.jsqlparser.expression.operators.relational.Between =>
          val saved = topLevelOverride
          topLevelOverride = None
          val l = visit(bt.getLeftExpression)
          if l ne bt.getLeftExpression then bt.setLeftExpression(l)
          val s = visit(bt.getBetweenExpressionStart)
          if s ne bt.getBetweenExpressionStart then bt.setBetweenExpressionStart(s)
          val e = visit(bt.getBetweenExpressionEnd)
          if e ne bt.getBetweenExpressionEnd then bt.setBetweenExpressionEnd(e)
          topLevelOverride = saved
          bt

        case ix: net.sf.jsqlparser.expression.operators.relational.InExpression =>
          val saved = topLevelOverride
          topLevelOverride = None
          val l = visit(ix.getLeftExpression)
          if l ne ix.getLeftExpression then ix.setLeftExpression(l)
          ix.getRightExpression match
            case el: net.sf.jsqlparser.expression.operators.relational.ExpressionList[
                  Expression
                ] @unchecked =>
              val it = el.listIterator()
              while it.hasNext do
                val cur = it.next()
                val nxt = visit(cur)
                if nxt ne cur then it.set(nxt)
            case _ => ()
          topLevelOverride = saved
          ix

        case cx: net.sf.jsqlparser.expression.CastExpression =>
          val saved = topLevelOverride
          topLevelOverride = None
          val nxt = visit(cx.getLeftExpression)
          if nxt ne cx.getLeftExpression then cx.setLeftExpression(nxt)
          topLevelOverride = saved
          cx

        case ce: net.sf.jsqlparser.expression.CaseExpression =>
          val saved = topLevelOverride
          topLevelOverride = None
          Option(ce.getSwitchExpression).foreach { sw =>
            val nxt = visit(sw)
            if nxt ne sw then ce.setSwitchExpression(nxt)
          }
          Option(ce.getWhenClauses).foreach { clauses =>
            val it = clauses.iterator()
            while it.hasNext do
              val wc = it.next()
              val w  = visit(wc.getWhenExpression)
              if w ne wc.getWhenExpression then wc.setWhenExpression(w)
              val t  = visit(wc.getThenExpression)
              if t ne wc.getThenExpression then wc.setThenExpression(t)
          }
          Option(ce.getElseExpression).foreach { el =>
            val nxt = visit(el)
            if nxt ne el then ce.setElseExpression(nxt)
          }
          topLevelOverride = saved
          ce

        case other => other

  private def matchingPolicy(
      table: String,
      column: String,
      policies: List[RoleColumnPolicy]
  ): Option[RoleColumnPolicy] =
    policies.find { p =>
      (p.tableName == RoleColumnPolicy.Wildcard || p.tableName.equalsIgnoreCase(table)) &&
      p.columnName.equalsIgnoreCase(column)
    }
