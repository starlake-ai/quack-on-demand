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
    if policies.isEmpty then Passthrough
    else
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
        case UnresolvedMode.Deny => JdbcMetaData.ErrorMode.STRICT
        case UnresolvedMode.Pass => JdbcMetaData.ErrorMode.LENIENT)

      // Capture try/catch outcomes as Either so the early-exit flows through a structured
      // match instead of an exception. jsqltranspiler 1.9 does NOT ship a single
      // `JSQLDataException` umbrella; it raises one of the table/column/schema/catalog-not-
      // found exceptions under `ai.starlake.transpiler.*`.
      val resolveAttempt: Either[RewriteOutcome, String] =
        try Right(resolver.getResolvedStatementText(sql))
        catch
          case e: ai.starlake.transpiler.TableNotFoundException    => Left(Denied(e.getMessage))
          case e: ai.starlake.transpiler.TableNotDeclaredException => Left(Denied(e.getMessage))
          case e: ai.starlake.transpiler.ColumnNotFoundException   => Left(Denied(e.getMessage))
          case e: ai.starlake.transpiler.SchemaNotFoundException   => Left(Denied(e.getMessage))
          case e: ai.starlake.transpiler.CatalogNotFoundException  => Left(Denied(e.getMessage))
          case _: net.sf.jsqlparser.JSQLParserException            => Left(ParseFailed)
          case _: Throwable                                        => Left(ParseFailed)

      resolveAttempt match
        case Left(failure)       => failure
        case Right(resolvedText) =>
          val rsMeta                                          = resolver.getResultSetMetaData(sql)
          val projectionOrigins: IndexedSeq[(String, String)] =
            (1 to rsMeta.getColumnCount).toIndexedSeq.map { i =>
              (
                Option(rsMeta.getTableName(i)).getOrElse(""),
                Option(rsMeta.getColumnName(i)).getOrElse("")
              )
            }

          val parseAttempt: Either[RewriteOutcome, net.sf.jsqlparser.statement.Statement] =
            try Right(CCJSqlParserUtil.parse(resolvedText))
            catch case _: Throwable => Left(ParseFailed)

          parseAttempt match
            case Left(failure) => failure
            case Right(parsed) =>
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

    // Recurse into CTE bodies first. Top-level lineage doesn't apply inside the CTE body, so pass
    // empty origins; the visitor will fall back to each Column's own (table, column) qualifier
    // resolved via the inner PlainSelect's FROM clause.
    Option(sel.getWithItemsList).foreach { wis =>
      val it = wis.iterator()
      while it.hasNext do
        val wi = it.next()
        Option(wi.getParenthesedStatement).foreach {
          case ps: net.sf.jsqlparser.statement.select.ParenthesedSelect =>
            val (innerChanged, _) = applyPolicies(ps.getSelect, IndexedSeq.empty, policies)
            if innerChanged then changed.set(true)
          case _ => ()
        }
    }

    sel match
      case sol: net.sf.jsqlparser.statement.select.SetOperationList =>
        // UNION / INTERSECT / EXCEPT: recurse into every arm. Each arm has its own FROM clause
        // and its own per-column policy lookup; outer-query origins don't apply.
        Option(sol.getSelects).foreach(_.forEach { arm =>
          val (armChanged, _) = applyPolicies(arm, IndexedSeq.empty, policies)
          if armChanged then changed.set(true)
        })
      case wrap: net.sf.jsqlparser.statement.select.ParenthesedSelect =>
        // Top-level parenthesized SELECT: unwrap and recurse.
        val (innerChanged, _) = applyPolicies(wrap.getSelect, IndexedSeq.empty, policies)
        if innerChanged then changed.set(true)
      case ps: PlainSelect =>
        // FROM-tables of this select (key -> table). Single-table case lets the visitor resolve
        // an unqualified column reference to the implicit table. Used inside composite expressions
        // (function args, BETWEEN bounds, CASE arms, etc.) where the resolver doesn't expand the
        // qualifier.
        visitor.fromTables = collectFromTables(ps)
        // FROM-item subquery: recurse so policies apply inside `FROM (SELECT ... FROM customer)`.
        // The outer projection still references the subquery via its alias and the projected name.
        // Before recursion (which would mutate inner Columns into transform literals) we snapshot
        // the inner SELECT's exposed covered columns and synthesize transient policies so the outer
        // projection masks `sub.c_email` too. The resolver's ResultSet lineage stops at the FROM
        // boundary in jsqltranspiler 1.9, so we trace it ourselves.
        val derivedPolicies = scala.collection.mutable.ListBuffer.empty[RoleColumnPolicy]
        Option(ps.getFromItem).foreach {
          case sub: net.sf.jsqlparser.statement.select.ParenthesedSelect =>
            derivedPolicies ++= deriveOuterPolicies(sub, policies)
            val (innerChanged, _) = applyPolicies(sub.getSelect, IndexedSeq.empty, policies)
            if innerChanged then changed.set(true)
          case _ => ()
        }
        Option(ps.getJoins).foreach(_.forEach { j =>
          Option(j.getRightItem).foreach {
            case sub: net.sf.jsqlparser.statement.select.ParenthesedSelect =>
              derivedPolicies ++= deriveOuterPolicies(sub, policies)
              val (innerChanged, _) = applyPolicies(sub.getSelect, IndexedSeq.empty, policies)
              if innerChanged then changed.set(true)
            case _ => ()
          }
        })
        // Augment the visitor's policies with any synthesized ones so the outer projection sees
        // `sub.c_email` (or alias.colalias) as covered. The synthesized policies use the FROM-item
        // alias as the tableName, so resolveTable's alias-equality fallback can match them.
        if derivedPolicies.nonEmpty then visitor.extraPolicies = derivedPolicies.toList
        val items = ps.getSelectItems
        if items != null then
          val it  = items.listIterator()
          var idx = 0
          while it.hasNext do
            val si = it.next()
            // Top-level column origin override: the resolver knows the physical lineage of the
            // projection slot even if the expression at that slot is itself a Column whose name
            // is an alias of another column. Only meaningful when origins are available (top-level
            // call); inside recursion (e.g. CTE body) origins is empty and the visitor falls back
            // to each Column's own (table, column) qualifier.
            visitor.topLevelOverride =
              if origins.indices.contains(idx) then Some(origins(idx)) else None
            val expr     = si.getExpression
            val replaced = visitor.visit(expr)
            if replaced ne expr then
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

  /** Build a list of (alias -> rawTableName) for the FROM + JOINs of a PlainSelect. FROM-item
    * subqueries are recorded as (alias -> alias) so synthetic policies keyed on the alias can be
    * matched by the visitor's resolveTable fallback (single-FROM unqualified column case).
    */
  private def collectFromTables(ps: PlainSelect): List[(String, String)] =
    val buf = scala.collection.mutable.ListBuffer.empty[(String, String)]
    def add(t: net.sf.jsqlparser.schema.Table): Unit =
      val raw = t.getName
      val key = Option(t.getAlias).map(_.getName).getOrElse(raw)
      buf += (key -> raw)
    def addSub(sub: net.sf.jsqlparser.statement.select.ParenthesedSelect): Unit =
      Option(sub.getAlias).map(_.getName).foreach(name => buf += (name -> name))
    Option(ps.getFromItem).foreach {
      case t: net.sf.jsqlparser.schema.Table                       => add(t)
      case s: net.sf.jsqlparser.statement.select.ParenthesedSelect => addSub(s)
      case _                                                       => ()
    }
    Option(ps.getJoins).foreach { joins =>
      val it = joins.iterator()
      while it.hasNext do
        Option(it.next().getRightItem).foreach {
          case t: net.sf.jsqlparser.schema.Table                       => add(t)
          case s: net.sf.jsqlparser.statement.select.ParenthesedSelect => addSub(s)
          case _                                                       => ()
        }
    }
    buf.toList

  /** Pre-scan a FROM-item subquery and synthesize policies for the outer scope. For each inner
    * SelectItem whose source column is covered by a base-table policy, emit a transient policy
    * keyed on `(subqueryAlias, projectedName)` so the outer projection masks `sub.x` references.
    * The projectedName is the user-supplied alias if any, else the inner Column's name. Items that
    * are not bare Columns (functions, expressions) are skipped - those don't expose a cleanly
    * maskable identity to the outer scope.
    */
  private def deriveOuterPolicies(
      sub: net.sf.jsqlparser.statement.select.ParenthesedSelect,
      policies: List[RoleColumnPolicy]
  ): List[RoleColumnPolicy] =
    val subAlias = Option(sub.getAlias).map(_.getName).getOrElse("")
    if subAlias.isEmpty then Nil
    else
      sub.getSelect match
        case ips: PlainSelect =>
          Option(ips.getSelectItems) match
            case None        => Nil
            case Some(items) =>
              val innerFromTables = collectFromTables(ips)
              val buf             = scala.collection.mutable.ListBuffer.empty[RoleColumnPolicy]
              val it              = items.iterator()
              while it.hasNext do
                val si = it.next()
                si.getExpression match
                  case col: net.sf.jsqlparser.schema.Column =>
                    val baseTable = Option(col.getTable).map(_.getName) match
                      case Some(key) =>
                        innerFromTables.find(_._1.equalsIgnoreCase(key)).map(_._2).getOrElse(key)
                      case None =>
                        if innerFromTables.size == 1 then innerFromTables.head._2 else ""
                    val baseCol     = col.getColumnName
                    val exposedName =
                      Option(si.getAlias).map(_.getName).getOrElse(baseCol)
                    matchingPolicy(baseTable, baseCol, policies).foreach { p =>
                      buf += p.copy(tableName = subAlias, columnName = exposedName)
                    }
                  case _ => ()
              buf.toList
        case _ => Nil

  private final class PolicyVisitor(
      policies: List[RoleColumnPolicy],
      changed: java.util.concurrent.atomic.AtomicBoolean
  ):
    var topLevelOverride: Option[(String, String)]  = None
    var fromTables: List[(String, String)]          = Nil
    var extraPolicies: List[RoleColumnPolicy]       = Nil
    private def allPolicies: List[RoleColumnPolicy] = extraPolicies ::: policies

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
          matchingPolicy(tableName, columnName, allPolicies) match
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

        case el: net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList[
              Expression
            ] @unchecked =>
          val saved = topLevelOverride
          topLevelOverride = None
          val it = el.listIterator()
          while it.hasNext do
            val cur = it.next()
            val nxt = visit(cur)
            if nxt ne cur then it.set(nxt)
          topLevelOverride = saved
          el

        case ae: net.sf.jsqlparser.expression.AnalyticExpression =>
          val saved = topLevelOverride
          topLevelOverride = None
          Option(ae.getExpression).foreach { e =>
            val nxt = visit(e)
            if nxt ne e then ae.setExpression(nxt)
          }
          Option(ae.getPartitionExpressionList).foreach { lst =>
            val typed = lst
              .asInstanceOf[net.sf.jsqlparser.expression.operators.relational.ExpressionList[
                Expression
              ]]
            val it = typed.listIterator()
            while it.hasNext do
              val cur = it.next()
              val nxt = visit(cur)
              if nxt ne cur then it.set(nxt)
          }
          Option(ae.getOrderByElements).foreach { obs =>
            val it = obs.iterator()
            while it.hasNext do
              val ob  = it.next()
              val nxt = visit(ob.getExpression)
              if nxt ne ob.getExpression then ob.setExpression(nxt)
          }
          topLevelOverride = saved
          ae

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
              val t = visit(wc.getThenExpression)
              if t ne wc.getThenExpression then wc.setThenExpression(t)
          }
          Option(ce.getElseExpression).foreach { el =>
            val nxt = visit(el)
            if nxt ne el then ce.setElseExpression(nxt)
          }
          topLevelOverride = saved
          ce

        case ps: net.sf.jsqlparser.statement.select.ParenthesedSelect =>
          // Scalar subquery in expression position, e.g. `SELECT (SELECT c_email FROM customer)`.
          // JSqlParser 5.x represents this as a ParenthesedSelect inside the expression tree.
          // Select extends Expression in 5.x; the inner FROM clause and per-column policy lookup
          // belong to the subquery itself, so the outer origins don't apply.
          val saved = topLevelOverride
          topLevelOverride = None
          val (innerChanged, _) = applyPolicies(ps.getSelect, IndexedSeq.empty, policies)
          if innerChanged then changed.set(true)
          topLevelOverride = saved
          ps

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
