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
      // Register the caller-provided tables under CURRENT_CATALOG/CURRENT_SCHEMA so SQL schema
      // qualifiers (e.g. `tpch1.customer`) match them when tpch1 IS the current schema; a table
      // with an empty column list is deliberately NOT registered (same as the old 3-arg
      // JSQLColumResolver constructor), which keeps "catalog knows nothing" -> unresolvedMode.
      // defaultCatalog/defaultSchema come from the caller's SchemaContext (the session-defaults
      // pinned at handshake time).
      val currentCatalog = defaultCatalog.getOrElse("")
      val currentSchema  = defaultSchema.getOrElse("")
      val metaData       = new JdbcMetaData(currentCatalog, currentSchema)
      schema.foreach { case (tableKey, cols) =>
        cols.foreach { col =>
          metaData.addTable(
            currentCatalog,
            currentSchema,
            tableKey,
            new ai.starlake.transpiler.schema.JdbcColumn(col)
          )
        }
      }
      // Seed DuckDB's fixed system-catalog shapes under their REAL schema names so metadata
      // queries (`information_schema.schemata`, `pg_catalog.pg_tables`, ...) resolve instead of
      // tripping the STRICT resolver into a fail-closed deny. They must be schema-qualified
      // entries: the flat `schema` map above lands under CURRENT_SCHEMA and can never match an
      // `information_schema.x` reference. Column policies never target system schemas, so these
      // seeds can only make a metadata query resolve, never unmask a user column; a system table
      // absent from SystemSchemaColumns stays unresolved and keeps failing closed.
      SystemSchemaColumns.all.foreach { case ((sysSchema, table), cols) =>
        cols.foreach { col =>
          metaData.addTable(
            currentCatalog,
            sysSchema,
            table,
            new ai.starlake.transpiler.schema.JdbcColumn(col)
          )
        }
      }
      val resolver = new JSQLColumResolver(metaData)
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
                  // The resolver's STRICT mode only enforces table existence at the
                  // top level; a table referenced ONLY inside a subquery (IN / EXISTS /
                  // ANY / scalar) slips past it. Enforce the same fail-closed invariant
                  // at every nesting depth ourselves before any masking runs.
                  val nestedUnknown =
                    if unresolvedMode == UnresolvedMode.Deny then
                      firstUnknownTable(sql, schema, currentCatalog, currentSchema)
                    else None
                  nestedUnknown match
                    case Some(name) => Denied(s"unresolvable table $name")
                    case None       =>
                      try
                        val (changed, _) = applyPolicies(sel, projectionOrigins, policies)
                        if changed then Rewritten(sel.toString) else Passthrough
                      catch case e: DenyException => Denied(e.reason)
                case _ =>
                  Passthrough

  /** First table reference, at ANY depth, that neither the caller-provided schema nor the
    * system-schema seeds know. CTE names are excluded by [[TablesNamesFinder]] itself. Matching is
    * deliberately the same name space the resolver was fed: a bare name must be a schema key; a
    * qualified name must be `[currentCatalog.]currentSchema.key` or a seeded system-schema table.
    * This check can only ADD denials on top of the resolver's own STRICT pass, never admit.
    */
  private def firstUnknownTable(
      originalSql: String,
      schema: Map[String, List[String]],
      currentCatalog: String,
      currentSchema: String
  ): Option[String] =
    val names =
      try
        // Scan the ORIGINAL text, not the resolved statement: the resolver
        // schema-qualifies CTE references (FROM x -> tpch1.x), which would defeat
        // the finder's own WITH-name exclusion and false-deny every CTE read.
        val parsed = CCJSqlParserUtil.parse(originalSql)
        val finder = new net.sf.jsqlparser.util.TablesNamesFinder()
        val list   = finder.getTableList(parsed)
        scala.jdk.CollectionConverters.ListHasAsScala(list).asScala.toList
      catch case _: Throwable => Nil
    def unquote(s: String) = s.stripPrefix("\"").stripSuffix("\"")
    // Known = the caller's catalog produced a NON-EMPTY column list for the name (an
    // unknown table lands in the schema map with Nil, which the resolver also refuses
    // to register), or the name is a seeded system-schema table.
    def knownUserTable(name: String): Boolean =
      schema.exists((k, cols) => k.equalsIgnoreCase(name) && cols.nonEmpty)
    def known(raw: String): Boolean =
      raw.split('.').toList.map(unquote).reverse match
        case Nil                => true
        case name :: Nil        => knownUserTable(name)
        case name :: q1 :: rest =>
          val sysKnown = SystemSchemaColumns.all.keys.exists { case (s, t) =>
            s.equalsIgnoreCase(q1) && t.equalsIgnoreCase(name)
          }
          val qualifierOk = rest match
            case Nil      => true
            case c :: Nil => c.equalsIgnoreCase(currentCatalog)
            case _        => false
          val userKnown = q1.equalsIgnoreCase(currentSchema) && knownUserTable(name) && qualifierOk
          sysKnown || userKnown
    names.find(n => !known(n))

  /** Walk the resolved statement's projection items and replace any Column whose physical (table,
    * column) lineage matches a policy. Full visitor surface (CASE / CAST / OVER / IN / BETWEEN /
    * EXTRACT / WHERE / HAVING / GROUP / ORDER) is added in Task 5.
    *
    * `outerScope` threads the ENCLOSING queries' (alias -> table) bindings into subquery descent,
    * so a correlated reference through an outer alias (`EXISTS (... WHERE c.c_phone = ...)`)
    * resolves to its base table and its policy applies. Local FROM items shadow outer ones.
    */
  private def applyPolicies(
      sel: Select,
      origins: IndexedSeq[(String, String)],
      policies: List[RoleColumnPolicy],
      outerScope: List[(String, String)] = Nil
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
        // and its own per-column policy lookup; outer-query origins don't apply, but a set
        // operation nested in a correlated subquery still sees the enclosing scope.
        Option(sol.getSelects).foreach(_.forEach { arm =>
          val (armChanged, _) = applyPolicies(arm, IndexedSeq.empty, policies, outerScope)
          if armChanged then changed.set(true)
        })
      case wrap: net.sf.jsqlparser.statement.select.ParenthesedSelect =>
        // Top-level parenthesized SELECT: unwrap and recurse.
        val (innerChanged, _) =
          applyPolicies(wrap.getSelect, IndexedSeq.empty, policies, outerScope)
        if innerChanged then changed.set(true)
      case ps: PlainSelect =>
        // FROM-tables of this select (key -> table). Single-table case lets the visitor resolve
        // an unqualified column reference to the implicit table. Used inside composite expressions
        // (function args, BETWEEN bounds, CASE arms, etc.) where the resolver doesn't expand the
        // qualifier.
        visitor.fromTables = collectFromTables(ps)
        visitor.outerTables = outerScope
        // The scope a nested subquery of THIS select sees: local FROM items first
        // (they shadow), then whatever this select itself inherited.
        val childScope = visitor.fromTables ::: outerScope
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
            // Derived tables see the outer scope too: only legal for LATERAL, but
            // over-masking an illegal reference is harmless (the engine rejects it).
            val (innerChanged, _) =
              applyPolicies(sub.getSelect, IndexedSeq.empty, policies, childScope)
            if innerChanged then changed.set(true)
          case _ => ()
        }
        Option(ps.getJoins).foreach(_.forEach { j =>
          Option(j.getFromItem).foreach {
            case sub: net.sf.jsqlparser.statement.select.ParenthesedSelect =>
              derivedPolicies ++= deriveOuterPolicies(sub, policies)
              val (innerChanged, _) =
                applyPolicies(sub.getSelect, IndexedSeq.empty, policies, childScope)
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
              val item = si.asInstanceOf[SelectItem[Expression]]
              item.setExpression(replaced)
              // A bare `SELECT c_phone` (no explicit AS) relies on the projected Column's own name
              // for the result-set's field name. Masking replaces that Column with a value
              // expression (e.g. the literal `'***'`), which has no name of its own -- DuckDB would
              // otherwise synthesize one from the expression text, changing the column name a
              // client sees out from under it (breaking `SELECT c_phone ...` -> a result column NOT
              // named `c_phone`). Preserve the original name as an explicit alias so masking is
              // transparent to the caller. Only for a directly-masked top-level Column: an explicit
              // user alias (`SELECT c_phone AS foo`) is left alone, and a masked column nested
              // inside a function/CASE/etc. keeps whatever name that enclosing expression gets --
              // there's no bare column name to preserve there.
              if item.getAlias == null then
                expr match
                  case col: net.sf.jsqlparser.schema.Column =>
                    item.setAlias(new net.sf.jsqlparser.expression.Alias(col.getColumnName))
                  case _ => ()
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
        Option(it.next().getFromItem).foreach {
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
    var topLevelOverride: Option[(String, String)] = None
    var fromTables: List[(String, String)]         = Nil
    // Enclosing queries' (alias -> table) bindings, threaded through subquery descent so
    // correlated outer-alias references resolve; local fromTables always shadow these.
    var outerTables: List[(String, String)]         = Nil
    var extraPolicies: List[RoleColumnPolicy]       = Nil
    private def allPolicies: List[RoleColumnPolicy] = extraPolicies ::: policies

    /** Resolve the physical table for a Column reference. */
    private def resolveTable(col: net.sf.jsqlparser.schema.Column): String =
      Option(col.getTable).map(_.getName) match
        case Some(key) =>
          // Alias or table-name qualifier - local FROM items first, then the outer scope
          // (a correlated subquery referencing the outer query's alias).
          (fromTables ::: outerTables)
            .find(_._1.equalsIgnoreCase(key))
            .map(_._2)
            .getOrElse(key)
        case None =>
          // Unqualified - fall back to the single LOCAL FROM item if there is exactly
          // one. Deliberately local-only: SQL resolves unqualified names innermost-first,
          // and attributing them to an outer table would mis-key single-FROM lookups.
          if fromTables.size == 1 then fromTables.head._2 else ""

    /** Walk `expr` and return its replacement (same instance if nothing changed). */
    // Parenthesis is deprecated in jsqlparser 5.x (ParenthesedExpressionList is the
    // replacement, matched below) but the class still exists and dropping the case
    // would silently skip rewriting any subtree the parser still wraps in it.
    @scala.annotation.nowarn("msg=class Parenthesis")
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
            case other =>
              // e.g. `IN (SELECT c_phone FROM customer)`: the right side is a ParenthesedSelect,
              // which visit() recurses into so a covered column inside the subquery is masked
              // (otherwise the IN filter would act as a membership oracle on the true values).
              val nxt = visit(other)
              if nxt ne other then ix.setRightExpression(nxt)
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

        case ne: net.sf.jsqlparser.expression.NotExpression =>
          // A leading NOT parses as a NotExpression wrapper (NOT ExistsExpression.isNot), so
          // `NOT EXISTS (...)` and `NOT (x = ANY(...))` arrive here. Descend into the wrapped
          // predicate like the other unary wrappers, otherwise the negated form leaks the
          // covered column unmasked. (`x NOT IN (...)` stays an InExpression with isNot=true
          // and rides the InExpression case below.)
          val saved = topLevelOverride
          topLevelOverride = None
          val nxt = visit(ne.getExpression)
          if nxt ne ne.getExpression then ne.setExpression(nxt)
          topLevelOverride = saved
          ne

        case ex: net.sf.jsqlparser.expression.operators.relational.ExistsExpression =>
          // `EXISTS (SELECT ...)`: the wrapped select must be descended exactly like the
          // InExpression right side, otherwise a covered column inside the subquery is
          // forwarded unmasked and EXISTS acts as a membership oracle on the true values.
          val saved = topLevelOverride
          topLevelOverride = None
          val nxt = visit(ex.getRightExpression)
          if nxt ne ex.getRightExpression then ex.setRightExpression(nxt)
          topLevelOverride = saved
          ex

        case ac: net.sf.jsqlparser.expression.AnyComparisonExpression =>
          // `x = ANY(SELECT ...)` / `= ALL` / `= SOME` (AnyType covers all three): descend into
          // the quantified subquery so covered columns inside it are masked; otherwise the
          // comparison is a true/false oracle on the masked values. The select field is final
          // (no setter), so mutate it in place via applyPolicies like the ParenthesedSelect case.
          val saved = topLevelOverride
          topLevelOverride = None
          Option(ac.getSelect).foreach { sel =>
            val (innerChanged, _) =
              applyPolicies(sel, IndexedSeq.empty, policies, fromTables ::: outerTables)
            if innerChanged then changed.set(true)
          }
          topLevelOverride = saved
          ac

        case ps: net.sf.jsqlparser.statement.select.ParenthesedSelect =>
          // Scalar subquery in expression position, e.g. `SELECT (SELECT c_email FROM customer)`.
          // JSqlParser 5.x represents this as a ParenthesedSelect inside the expression tree.
          // Select extends Expression in 5.x; the inner FROM clause and per-column policy lookup
          // belong to the subquery itself, so the outer origins don't apply.
          val saved = topLevelOverride
          topLevelOverride = None
          val (innerChanged, _) =
            applyPolicies(ps.getSelect, IndexedSeq.empty, policies, fromTables ::: outerTables)
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
