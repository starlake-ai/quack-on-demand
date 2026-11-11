package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import ai.starlake.transpiler.JSQLColumResolver
import ai.starlake.transpiler.schema.JdbcMetaData
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.{PlainSelect, Select, SelectItem}

import scala.jdk.CollectionConverters._

final class JsqltranspilerRewriter extends SchemaAwareSqlRewriter:

  import RewriteOutcome._

  private final case class DenyException(reason: String) extends RuntimeException(reason)

  def rewrite(
      sql: String,
      schema: Map[String, List[String]],
      policies: List[RoleColumnPolicy],
      defaultCatalog: Option[String],
      defaultSchema: Option[String],
      unresolvedMode: UnresolvedMode = UnresolvedMode.Deny,
      homes: Map[String, (String, String)] = Map.empty
  ): RewriteOutcome =
    if policies.isEmpty then Passthrough
    else
      // Star modifiers become explicit column lists first: the resolver drops them on the floor.
      StarExpansion.expand(sql, schema, policies) match
        case Left(reason)   => Denied(reason)
        case Right(starSql) =>
          rewriteWithPolicies(
            starSql,
            schema,
            policies,
            defaultCatalog,
            defaultSchema,
            unresolvedMode,
            homes
          )

  private def rewriteWithPolicies(
      sql: String,
      schema: Map[String, List[String]],
      policies: List[RoleColumnPolicy],
      defaultCatalog: Option[String],
      defaultSchema: Option[String],
      unresolvedMode: UnresolvedMode,
      homes: Map[String, (String, String)]
  ): RewriteOutcome =
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
    // A table the statement names outside the defaults (a federated source, another schema) is
    // registered where it is named too, so `pgfed.public.users` resolves to its own columns.
    homes.foreach { case (tableKey, (homeCatalog, homeSchema)) =>
      if !(homeCatalog.equalsIgnoreCase(currentCatalog) && homeSchema.equalsIgnoreCase(
          currentSchema
        ))
      then
        schema.get(tableKey).toList.flatten.foreach { col =>
          metaData.addTable(
            homeCatalog,
            homeSchema,
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
      try Right(resolvedText(resolver, metaData, sql))
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
        // None for a shape the resolver builds no result set for (the FROM-first `FROM t`): it
        // cannot be masked, so it fails closed below.
        val rsMeta = Option(
          resolver.getResultSetMetaData(ai.starlake.sql.JSqlStatements.firstText(sql))
        )
        val projectionOrigins: IndexedSeq[(String, String)] = rsMeta.toIndexedSeq.flatMap { m =>
          (1 to m.getColumnCount).map { i =>
            (
              Option(m.getTableName(i)).getOrElse(""),
              Option(m.getColumnName(i)).getOrElse("")
            )
          }
        }

        val parseAttempt: Either[RewriteOutcome, net.sf.jsqlparser.statement.Statement] =
          try Right(ai.starlake.sql.JSqlStatements.parseFirst(resolvedText))
          catch case _: Throwable => Left(ParseFailed)

        parseAttempt match
          case Left(failure)              => failure
          case Right(_) if rsMeta.isEmpty => ParseFailed
          case Right(parsed)              =>
            parsed match
              case sel: Select =>
                // The resolver's STRICT mode only enforces table existence at the
                // top level; a table referenced ONLY inside a subquery (IN / EXISTS /
                // ANY / scalar) slips past it. Enforce the same fail-closed invariant
                // at every nesting depth ourselves before any masking runs.
                val nestedUnknown =
                  if unresolvedMode == UnresolvedMode.Deny then
                    firstUnknownTable(sql, schema, currentCatalog, currentSchema, homes)
                  else None
                nestedUnknown match
                  case Some(name) => Denied(s"unresolvable table $name")
                  case None       =>
                    try
                      // `c_email.lower()` to `lower(c_email)`, so the visitor sees the column.
                      ResidualColumns
                        .normalizeMethodCalls(sel, policies, schema)
                        .foreach(reason => throw DenyException(reason))
                      // The visitor masks the positions it models and leaves every other node
                      // as it found it; a covered column still in the tree afterwards would
                      // reach the node unmasked, so it denies (ResidualColumns).
                      val suspects     = ResidualColumns.suspects(sel, policies, schema)
                      val (changed, _) = applyPolicies(sel, projectionOrigins, policies)
                      ResidualColumns.unmasked(sel, suspects) match
                        case Some(reason) => Denied(reason)
                        case None => if changed then Rewritten(sel.toString) else Passthrough
                    catch case e: DenyException => Denied(e.reason)
              case _ =>
                Passthrough

  /** The resolver's rewrite of `sql` (star expansion, column lineage) with every table name the
    * caller wrote printed exactly as written. `JSQLColumResolver.getResolvedStatementText` also
    * schema-qualifies every bare table reference, a CTE reference included, and a qualified name
    * never binds to a CTE: `WITH t AS (..) SELECT .. FROM t` became `.. FROM tpch1.t`, a read of
    * the real table `t` that the ACL gate never checked (it saw the CTE). The router runs the
    * statement under the session's default catalog and schema, so the caller's own spelling already
    * resolves where the gate assumed; the rewrite must change nothing but the masked expressions.
    *
    * A select list of plain stars over derived tables only keeps its stars too (see
    * [[derivedStarSelects]]).
    */
  private def resolvedText(
      resolver: JSQLColumResolver,
      metaData: JdbcMetaData,
      sql: String
  ): String =
    val statement = ai.starlake.sql.JSqlStatements.parseFirst(sql)
    statement match
      case select: Select =>
        val written =
          ResidualColumns.tablesOf(select).map(t => t -> new java.util.ArrayList(t.getNameParts))
        val stars =
          derivedStarSelects(select).map(ps => ps -> new java.util.ArrayList(ps.getSelectItems))
        select.accept(
          resolver: net.sf.jsqlparser.statement.select.SelectVisitor[
            ai.starlake.transpiler.schema.JdbcResultSetMetaData
          ],
          JdbcMetaData.copyOf(metaData)
        )
        written.foreach((t, parts) => t.setNameParts(parts))
        stars.foreach((ps, items) => ps.setSelectItems(items))
      case _ => ()
    val builder = new java.lang.StringBuilder()
    statement.accept(new net.sf.jsqlparser.util.deparser.StatementDeParser(builder))
    builder.toString

  /** The selects, at any depth, whose items are all plain stars (`*`, `t.*`: no EXCLUDE, REPLACE or
    * alias) and whose FROM items are all derived tables. The resolver expands such a star to the
    * derived table's outputs under its own name for an unaliased expression (`min(c_email)` became
    * `s.min`, `c_email || 'x'` became `s.Concat`), which DuckDB does not bind: the query, and the
    * FlightSQL prepare probe `SELECT * FROM (<query>) AS _qod_probe LIMIT 0` wrapping any query,
    * failed with a binder error. Such a star needs no expansion to be masked: it exposes only the
    * derived tables' outputs, and each derived table is masked inside it. A star that also covers a
    * base table or a CTE reference is left to the resolver, which the masking needs.
    */
  private def derivedStarSelects(select: Select): List[PlainSelect] =
    import net.sf.jsqlparser.statement.select.{AllColumns, AllTableColumns, ParenthesedSelect}
    def plainStar(si: SelectItem[?]): Boolean =
      si.getAlias == null && (si.getExpression match
        case star: AllColumns
            if star.getClass == classOf[AllColumns] || star.getClass == classOf[AllTableColumns] =>
          StarExpansion.unmodified(star)
        case _ => false)
    StarExpansion.plainSelects(select).filter { ps =>
      val items = Option(ps.getSelectItems).map(_.asScala.toList).getOrElse(Nil)
      val from  = Option(ps.getFromItem).toList ++
        Option(ps.getJoins).toList.flatMap(_.asScala).map(_.getFromItem)
      items.nonEmpty && items.forall(plainStar) &&
      from.nonEmpty && from.forall(_.isInstanceOf[ParenthesedSelect])
    }

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
      currentSchema: String,
      homes: Map[String, (String, String)]
  ): Option[String] =
    val names =
      try
        // Scan the ORIGINAL text, not the resolved statement: the resolver
        // schema-qualifies CTE references (FROM x -> tpch1.x), which would defeat
        // the finder's own WITH-name exclusion and false-deny every CTE read.
        val parsed = ai.starlake.sql.JSqlStatements.parseFirst(originalSql)
        val finder = new net.sf.jsqlparser.util.TablesNamesFinder()
        val tables = finder.getTables(parsed)
        scala.jdk.CollectionConverters.SetHasAsScala(tables).asScala.toList
      catch case _: Throwable => Nil
    def unquote(s: String) = ai.starlake.sql.QuotedIdentifier.unquote(s)
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
          // Or the table is named where the caller located it (its home, beside the defaults).
          val homeKnown = knownUserTable(name) &&
            homes.exists { case (k, (hc, hs)) =>
              k.equalsIgnoreCase(name) && hs.equalsIgnoreCase(q1) && (rest match
                case Nil      => hc.equalsIgnoreCase(currentCatalog)
                case c :: Nil => c.equalsIgnoreCase(hc)
                case _        => false)
            }
          sysKnown || userKnown || homeKnown
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
        // QUALIFY filters on window results like HAVING filters on aggregates: a predicate over
        // the masked values.
        Option(ps.getQualify).foreach { q =>
          val nxt = visitor.visit(q)
          if nxt ne q then { ps.setQualify(nxt); changed.set(true) }
        }
        // JOIN ... ON: a predicate over the joined rows, masked like WHERE, so the join behaves as
        // it would over a view exposing the masked values (an outer join included). The visitor's
        // FROM scope above already holds every joined table, so a qualified reference resolves to
        // its own side. A JOIN ... USING list names columns, not values, and cannot be masked: the
        // residual check denies it.
        Option(ps.getJoins).foreach(_.forEach { j =>
          Option(j.getOnExpressions).foreach { ons =>
            val before = ons.asScala.toList
            val after  = before.map(visitor.visit)
            if after.lazyZip(before).exists(_ ne _) then
              j.setOnExpressions(after.asJava)
              changed.set(true)
          }
        })
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
      val raw = unquoteName(t.getName)
      val key = Option(t.getAlias).map(a => unquoteName(a.getName)).getOrElse(raw)
      buf += (key -> raw)
    def addSub(sub: net.sf.jsqlparser.statement.select.ParenthesedSelect): Unit =
      Option(sub.getAlias).map(a => unquoteName(a.getName)).foreach(name => buf += (name -> name))
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
    val subAlias = Option(sub.getAlias).map(a => unquoteName(a.getName)).getOrElse("")
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
                    val baseTable = Option(col.getTable).map(t => unquoteName(t.getName)) match
                      case Some(key) =>
                        innerFromTables.find(_._1.equalsIgnoreCase(key)).map(_._2).getOrElse(key)
                      case None =>
                        if innerFromTables.size == 1 then innerFromTables.head._2 else ""
                    val baseCol     = unquoteName(col.getColumnName)
                    val exposedName =
                      Option(si.getAlias).map(a => unquoteName(a.getName)).getOrElse(baseCol)
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
      Option(col.getTable).map(t => unquoteName(t.getName)) match
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
          // `count(*) FILTER (WHERE c_email IS NOT NULL)`: the filter is a predicate like WHERE.
          Option(ae.getFilterExpression).foreach { f =>
            val nxt = visit(f)
            if nxt ne f then ae.setFilterExpression(nxt)
          }
          topLevelOverride = saved
          ae

        case isNull: net.sf.jsqlparser.expression.operators.relational.IsNullExpression =>
          // `c_email IS [NOT] NULL` tests the masked value, like `c_email = 'x'` does, so it
          // reveals no more than projecting the masked column would.
          val saved = topLevelOverride
          topLevelOverride = None
          val nxt = visit(isNull.getLeftExpression)
          if nxt ne isNull.getLeftExpression then isNull.setLeftExpression(nxt)
          topLevelOverride = saved
          isNull

        case trim: net.sf.jsqlparser.expression.TrimFunction =>
          // `trim([LEADING|TRAILING|BOTH] [chars FROM] c_email)`: an ordinary function of its
          // arguments, masked like any other function call.
          val saved = topLevelOverride
          topLevelOverride = None
          Option(trim.getExpression).foreach { e =>
            val nxt = visit(e)
            if nxt ne e then trim.setExpression(nxt)
          }
          Option(trim.getFromExpression).foreach { e =>
            val nxt = visit(e)
            if nxt ne e then trim.setFromExpression(nxt)
          }
          topLevelOverride = saved
          trim

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

  /** Names are compared unquoted ([[ai.starlake.sql.QuotedIdentifier]]): the parser keeps the
    * quotes of `"c_email"` on the column it hands the visitor, and a quoted name that matched no
    * policy was left in place for the backstop to deny instead of being masked.
    */
  private def matchingPolicy(
      table: String,
      column: String,
      policies: List[RoleColumnPolicy]
  ): Option[RoleColumnPolicy] =
    val t = unquoteName(table)
    val c = unquoteName(column)
    policies.find { p =>
      (p.tableName == RoleColumnPolicy.Wildcard || p.tableName.equalsIgnoreCase(t)) &&
      p.columnName.equalsIgnoreCase(c)
    }

  private def unquoteName(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)

/** The fail-closed backstop behind [[JsqltranspilerRewriter]]'s masking visitor, which models a
  * fixed set of expression types (columns, functions, CASE, CAST, IN, IS NULL, TRIM, ...) and
  * returns any other node untouched. A covered column inside such a node (a MAP or list literal, a
  * lambda, `->>`, a struct_pack named argument, a JOIN ... USING list, ...) was forwarded unmasked.
  *
  * Rather than enumerate those types, the check is structural: before the rewrite, every column
  * reference a policy could cover is noted by node identity ([[suspects]]); the visitor replaces
  * each one it masks with a new node, so any noted reference still reachable afterwards
  * ([[unmasked]]) sits where masking did not reach, and the statement is denied. The walk follows
  * the shared reflective child access ([[AstChildren]]), so a MAP literal's entries and a JSON
  * pair's halves are seen; a child of a type it has no rule for denies too.
  *
  * Coverage mirrors how DuckDB binds a name, per query block. Each select contributes one scope:
  * the sources its FROM / JOIN reads, a base table (with its catalog columns when known) or a
  * derived table / CTE reference (with its output column names when they can be read). A CTE body
  * sees the scopes ENCLOSING the select that declares it, not that select's own FROM (DuckDB:
  * "Referenced column not found"); a derived table in FROM sees its own select's scope too (DuckDB
  * joins one implicitly LATERAL), and a subquery in an expression sees every enclosing scope. An
  * unqualified reference binds innermost-first: the first scope holding a source that has the
  * column decides, and the reference is covered only if a policy table is among that scope's
  * candidates; a source whose columns are unknown never ends the search (the name may bind there or
  * further out), so ambiguity stays covered. A derived table or CTE carries values already masked
  * inside it, so a reference binding there is not a base read. A qualified reference `q.name`
  * resolves its qualifier the same way, innermost-first: a scope whose sources named `q` do not
  * have the column passes it outward, as DuckDB's correlated lookup does, and when no scope names
  * `q` it falls back to every alias of the statement. A qualifier that names a covered column is a
  * field read of that column (DuckDB's fallback when the binding `q` lacks the column), whatever
  * the binding has. The check can only add denials.
  */
private[cls] object ResidualColumns:
  import scala.jdk.CollectionConverters._
  import ai.starlake.acl.parser.AstChildren
  import net.sf.jsqlparser.schema.{Column, Table}
  import net.sf.jsqlparser.statement.select.{
    AllColumns,
    FromItem,
    ParenthesedFromItem,
    ParenthesedSelect,
    SetOperationList
  }

  final class Suspects private[ResidualColumns] (
      val columns: java.util.Set[Column],
      val unwalkable: Option[String],
      val refusal: Option[String]
  )

  /** One source a select reads: the lowercased names it is referenced by, its base table (None for
    * a derived table or a CTE reference), and its output columns when they can be read (a derived
    * table or CTE; a base table's come from the catalog).
    */
  private final case class Source(
      names: Set[String],
      table: Option[String],
      outputs: Option[Set[String]]
  )

  private type Scope = List[Source]

  /** Every column reference with the scopes it sits in (innermost first), every table node, and the
    * first child of an unknown type.
    */
  private final case class Scan(
      columns: List[(Column, List[Scope])],
      tables: List[Table],
      unknown: Option[String],
      forms: List[(Form, List[Scope])],
      methodCalls: List[(net.sf.jsqlparser.expression.Function, List[Scope])]
  )

  /** Nodes that read columns with no column node of their own for the visitor to mask. */
  private enum Form:
    /** `ARRAY(TABLE t)` / `x = ANY (TABLE t)`: JSqlParser holds t as a function's argument column.
      */
    case TableStatement

    /** A star the resolver did not expand (`* LIKE ..`, `*COLUMNS(..)`, a star in an expression) or
      * a COLUMNS(..) call: DuckDB expands it into the columns of the FROM clause.
      */
    case Expansion

    /** `(TABLE t)` / `(SUMMARIZE t)` in table position: the table read, None when unreadable. */
    case StatementSource(table: Option[String])

    /** A NATURAL join: it compares, raw, every column its two sides share. */
    case NaturalJoin(left: List[Source], right: List[Source])

    /** `t AS a(x, y)` over a base table, or `(t ..) AS a(x, y)` over a parenthesized FROM item
      * holding it: the list renames t's columns positionally, so a covered column is read under a
      * name no policy holds.
      */
    case AliasColumns(table: String)

  private def isColumnsCall(f: net.sf.jsqlparser.expression.Function): Boolean =
    Option(f.getMultipartName).exists(_.size == 1) &&
      Option(f.getName).exists(n => unquote(n).equalsIgnoreCase("columns"))

  /** The star of `count(*)` / `count(t.*)`, which counts rows and exposes no value. */
  private def countStar(f: net.sf.jsqlparser.expression.Function): Option[AllColumns] =
    val params = Option(f.getParameters).map(_.asScala.toList).getOrElse(Nil)
    params match
      case List(star: AllColumns)
          if Option(f.getMultipartName).exists(_.size == 1) &&
            Option(f.getName).exists(_.equalsIgnoreCase("count")) &&
            StarExpansion.unmodified(star) =>
        Some(star)
      case _ => None

  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)

  private def lower(s: String): String = s.toLowerCase(java.util.Locale.ROOT)

  /** The output column names of a query, lowercased, when every one can be read: a column projects
    * its name, an aliased item its alias; an unaliased expression projects its own text, which no
    * bare identifier can name, so it is skipped. A star or an unreadable shape: None.
    */
  private def outputsOf(sel: Any): Option[Set[String]] =
    sel match
      case ps: PlainSelect =>
        val items = Option(ps.getSelectItems).toList.flatMap(_.asScala)
        if items.exists(_.getExpression.isInstanceOf[AllColumns]) then None
        else
          Some(items.flatMap { si =>
            Option(si.getAlias).map(a => lower(unquote(a.getName))).orElse {
              si.getExpression match
                case c: Column => Option(c.getColumnName).map(n => lower(unquote(n)))
                case _         => None
            }
          }.toSet)
      case sol: SetOperationList =>
        Option(sol.getSelects).flatMap(_.asScala.headOption).flatMap(outputsOf)
      case psel: ParenthesedSelect => outputsOf(psel.getSelect)
      case _                       => None

  /** A derived table's output names: its alias column list when given, else its query's. */
  private def derivedOutputs(psel: ParenthesedSelect): Option[Set[String]] =
    Option(psel.getAlias)
      .flatMap(a => Option(a.getAliasColumns))
      .filter(!_.isEmpty)
      .map(_.asScala.map(c => lower(unquote(c.name))).toSet)
      .orElse(outputsOf(psel.getSelect))

  /** The sources a FROM item reads (a parenthesized join included), CTE references resolved against
    * the CTEs in scope.
    */
  private def sourcesOf(item: Any, ctes: Map[String, Option[Set[String]]]): List[Source] =
    item match
      case t: Table =>
        Option(t.getName).map(n => lower(unquote(n))).toList.map { name =>
          val alias = Option(t.getAlias).map(a => lower(unquote(a.getName)))
          val names = alias.toSet + name
          val cte   = Option.when(t.getSchemaName == null)(ctes.get(name)).flatten
          cte match
            case Some(outputs) => Source(alias.map(Set(_)).getOrElse(Set(name)), None, outputs)
            case None          => Source(names, Some(name), None)
        }
      case psel: ParenthesedSelect =>
        List(
          Source(
            Option(psel.getAlias).map(a => lower(unquote(a.getName))).toSet,
            None,
            derivedOutputs(psel)
          )
        )
      case pfi: ParenthesedFromItem =>
        sourcesOf(pfi.getFromItem, ctes) ++
          Option(pfi.getJoins).toList
            .flatMap(_.asScala)
            .flatMap(j => sourcesOf(j.getFromItem, ctes))
      case other =>
        // A table function, VALUES, PIVOT, ...: columns unknown, never a policy table.
        val alias = other match
          case fi: FromItem => Option(fi.getAlias).map(a => lower(unquote(a.getName))).toSet
          case _            => Set.empty[String]
        List(Source(alias, None, None))

  private def fromItemsOf(node: AnyRef): List[Any] =
    node match
      case ps: PlainSelect =>
        ps.getFromItem :: Option(ps.getJoins).toList.flatMap(_.asScala).map(_.getFromItem)
      case fq: net.sf.jsqlparser.statement.piped.FromQuery =>
        fq.getFromItem :: Option(fq.getJoins).toList.flatMap(_.asScala).map(_.getFromItem)
      case _ => Nil

  private def scan(root: AnyRef): Scan =
    val visited = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    val columns = scala.collection.mutable.ListBuffer.empty[(Column, List[Scope])]
    val tables  = scala.collection.mutable.ListBuffer.empty[Table]
    val forms   = scala.collection.mutable.ListBuffer.empty[(Form, List[Scope])]
    val calls   =
      scala.collection.mutable.ListBuffer
        .empty[(net.sf.jsqlparser.expression.Function, List[Scope])]
    val counted = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    var unknown = Option.empty[String]
    // TABLE statements a FROM clause reads (`FROM (TABLE t)`), marked by identity when the walk
    // reaches the select that holds them, which is before it reaches the statements themselves.
    val inTablePosition = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    def walk(node: AnyRef, scopes: List[Scope], ctes: Map[String, Option[Set[String]]]): Unit =
      if visited.add(node) then
        node match
          case c: Column => columns += (c -> scopes)
          case t: Table  =>
            tables += t
            val name = Option(t.getName).map(n => lower(unquote(n)))
            val cte  = t.getSchemaName == null && name.exists(ctes.contains)
            val list = Option(t.getAlias).flatMap(a => Option(a.getAliasColumns)).filter(!_.isEmpty)
            if list.isDefined && !cte then
              name.foreach(n => forms += (Form.AliasColumns(n) -> scopes))
          case f: net.sf.jsqlparser.expression.Function
              if ai.starlake.acl.parser.TableExtractor.tableStatementSources(f).isDefined =>
            forms += (Form.TableStatement -> scopes)
          // JSqlParser 5.4.16x reads `TABLE t` as a statement wherever it appears (`x = ANY (TABLE
          // t)`, `IN (TABLE t)`, `EXISTS (TABLE t)`, `SELECT (TABLE t)`, `FROM (TABLE t)`), not as a
          // flagged function: it reads every column of t raw. In a FROM clause, or as the whole
          // statement, it is refused like `(TABLE t)` in table position (when t has a policy);
          // anywhere else like a TABLE statement in expression position (always).
          case ts: net.sf.jsqlparser.statement.select.TableStatement =>
            if (ts eq root) || inTablePosition.contains(ts) then
              val table = Option(ts.getTable).flatMap(t => Option(t.getName)).map(unquote)
              forms += (Form.StatementSource(table) -> scopes)
            else forms += (Form.TableStatement -> scopes)
          case f: net.sf.jsqlparser.expression.Function if isColumnsCall(f) =>
            forms += (Form.Expansion -> scopes)
          case f: net.sf.jsqlparser.expression.Function =>
            countStar(f).foreach(counted.add(_))
            if Option(f.getMultipartName).exists(_.size > 1) then calls += (f -> scopes)
          case ae: net.sf.jsqlparser.expression.AnalyticExpression
              if Option(ae.getName).exists(_.equalsIgnoreCase("count")) =>
            // `count(*) FILTER (..)` / `count(*) OVER (..)`
            ae.getExpression match
              case star: AllColumns if StarExpansion.unmodified(star) =>
                counted.add(star): Unit
              case _ => ()
          case k: net.sf.jsqlparser.expression.Function.KeywordArgument
              if Option(k.getKeyword).exists(_.trim.equalsIgnoreCase("columns")) =>
            forms += (Form.Expansion -> scopes)
          case star: AllColumns if !counted.contains(star) =>
            forms += (Form.Expansion -> scopes)
          case pfi: ParenthesedFromItem =>
            ai.starlake.acl.parser.TableExtractor.statementSource(pfi).foreach { src =>
              forms += (Form.StatementSource(src.toOption.map(t => unquote(t.getName))) -> scopes)
            }
            // `(customer) AS c(a, b, e)`, `(customer JOIN orders ..) j(..)`: the list renames the
            // columns of every base table inside the parentheses, nested ones included.
            if Option(pfi.getAlias).flatMap(a => Option(a.getAliasColumns)).exists(!_.isEmpty) then
              sourcesOf(pfi, ctes).flatMap(_.table).foreach { t =>
                forms += (Form.AliasColumns(t) -> scopes)
              }
          case _ => ()
        // A WITH clause: its names are in scope for the whole select. Each body is walked with the
        // scopes ENCLOSING this select and sees only what DuckDB lets it see: the CTEs declared
        // before it, never its own name or a later one (both bind to the real table), except its
        // own name in the recursive term of a WITH RECURSIVE union (TableExtractor.recursiveSplit).
        val withItems = node match
          case sel: Select => Option(sel.getWithItemsList).toList.flatMap(_.asScala)
          case _           => Nil
        val declared = withItems.map { wi =>
          Option(wi.getUnquotedAliasName).map(n =>
            lower(n) -> (Option(wi.getWithItemList)
              .filter(!_.isEmpty)
              .map(_.asScala.map(i => lower(unquote(String.valueOf(i.getExpression)))).toSet)
              .orElse(outputsOf(wi.getParenthesedStatement)))
          )
        }
        val recursive = withItems.exists(_.isRecursive)
        withItems.zipWithIndex.foreach { (wi, i) =>
          val visible = ctes ++ declared.take(i).flatten
          val split   = Option
            .when(recursive)(wi.getParenthesedStatement)
            .collect { case body: Select => body }
            .flatMap(ai.starlake.acl.parser.TableExtractor.recursiveSplit)
          (split, declared(i)) match
            case (Some((anchor, term)), Some(own)) =>
              anchor.foreach(walk(_, scopes, visible))
              term.foreach(walk(_, scopes, visible + own))
            case _ => ()
          walk(wi, scopes, visible)
        }
        val inCtes = ctes ++ declared.flatten
        // A NATURAL join compares the columns its two sides share: the FROM items before it, and
        // its own item.
        val (head, joins) = node match
          case ps: PlainSelect => (ps.getFromItem, Option(ps.getJoins).toList.flatMap(_.asScala))
          case pfi: ParenthesedFromItem =>
            (pfi.getFromItem, Option(pfi.getJoins).toList.flatMap(_.asScala))
          case fq: net.sf.jsqlparser.statement.piped.FromQuery =>
            (fq.getFromItem, Option(fq.getJoins).toList.flatMap(_.asScala))
          case _ => (null, Nil)
        joins.zipWithIndex.foreach { (j, i) =>
          if j.isNatural then
            val left = (head :: joins.take(i).map(_.getFromItem)).filter(_ != null)
            forms += (Form.NaturalJoin(
              left.flatMap(sourcesOf(_, inCtes)),
              Option(j.getFromItem).toList.flatMap(sourcesOf(_, inCtes))
            ) -> scopes)
        }
        val inner = fromItemsOf(node) match
          case Nil   => scopes
          case items => items.filter(_ != null).flatMap(sourcesOf(_, inCtes)) :: scopes
        fromItemsOf(node).foreach {
          case ps: ParenthesedSelect =>
            ps.getSelect match
              case ts: net.sf.jsqlparser.statement.select.TableStatement => inTablePosition.add(ts)
              case _                                                     => ()
          case _ => ()
        }
        AstChildren.accessors(node.getClass).foreach { m =>
          AstChildren.read(m, node) match
            // A column's qualifier is not a table the statement reads.
            case _: Table if node.isInstanceOf[Column] => ()
            case value                                 => route(value, inner, inCtes)
        }
    def route(value: Any, scopes: List[Scope], ctes: Map[String, Option[Set[String]]]): Unit =
      value match
        case null                         => ()
        case it: java.lang.Iterable[?]    => it.forEach(route(_, scopes, ctes))
        case arr: Array[?]                => arr.foreach(route(_, scopes, ctes))
        case e: java.util.Map.Entry[?, ?] =>
          route(e.getKey, scopes, ctes); route(e.getValue, scopes, ctes)
        case m: java.util.Map[?, ?]                => m.entrySet.forEach(route(_, scopes, ctes))
        case n: AnyRef if AstChildren.isAstNode(n) => walk(n, scopes, ctes)
        case leaf if AstChildren.isLeaf(leaf)      => ()
        case other => unknown = unknown.orElse(Some(other.getClass.getName))
    walk(root, Nil, Map.empty)
    Scan(columns.toList, tables.toList, unknown, forms.toList, calls.toList)

  /** Every table node of `root` except column qualifiers, by identity. */
  def tablesOf(root: AnyRef): List[Table] = scan(root).tables

  /** How a policy covers the column references of one scan: DuckDB's binding of each name. */
  private final class Coverage(
      before: Scan,
      policies: List[RoleColumnPolicy],
      schema: Map[String, List[String]]
  ):
    private val aliases = before.tables.flatMap { t =>
      Option(t.getAlias).map(a => lower(unquote(a.getName)) -> unquote(t.getName))
    }
    def columnsOf(table: String): Option[List[String]] =
      schema.find(_._1.equalsIgnoreCase(table)).map(_._2).filter(_.nonEmpty)
    def isPolicyTable(table: String, byName: List[RoleColumnPolicy] = policies): Boolean =
      byName.exists(p =>
        p.tableName == RoleColumnPolicy.Wildcard || p.tableName.equalsIgnoreCase(table)
      )

    /** Whether `src` has the column: Some(true / false) when known, None when it cannot be told. */
    def has(src: Source, name: String): Option[Boolean] =
      src.table match
        case Some(t) => columnsOf(t).map(_.exists(_.equalsIgnoreCase(name)))
        case None    => src.outputs.map(_.contains(lower(name)))

    /** Innermost-first binding of an unqualified `name`. */
    private def unqualifiedCovered(
        name: String,
        scopes: List[Scope],
        byName: List[RoleColumnPolicy]
    ): Boolean =
      scopes match
        case Nil           => false
        case scope :: rest =>
          val candidates = scope.filter(src => !has(src, name).contains(false))
          val definite   = scope.exists(src => has(src, name).contains(true))
          if candidates.exists(_.table.exists(isPolicyTable(_, byName))) then true
          else if definite then false
          else unqualifiedCovered(name, rest, byName)

    /** Whether the bare name `name` binds to a covered column. */
    private def nameCovered(name: String, scopes: List[Scope]): Boolean =
      val byName = policies.filter(_.columnName.equalsIgnoreCase(name))
      byName.nonEmpty &&
      (byName.exists(_.tableName == RoleColumnPolicy.Wildcard) ||
        unqualifiedCovered(name, scopes, byName))

    /** Innermost-first binding of `q.name` over `named`, the sources named `q` in each scope that
      * names it: a scope none of whose sources has the column passes the reference outward
      * (DuckDB's correlated lookup), and one whose columns are unknown never ends the search.
      */
    private def qualifiedCovered(
        name: String,
        named: List[Scope],
        byName: List[RoleColumnPolicy]
    ): Boolean =
      named match
        case Nil            => false
        case bound :: outer =>
          if bound.exists(_.table.exists(isPolicyTable(_, byName))) then true
          else if bound.exists(has(_, name).contains(true)) then false
          else qualifiedCovered(name, outer, byName)

    def covered(col: Column, scopes: List[Scope]): Boolean =
      val name   = unquote(Option(col.getColumnName).getOrElse(""))
      val byName = policies.filter(_.columnName.equalsIgnoreCase(name))
      Option(col.getTable).flatMap(t => Option(t.getName)).map(unquote) match
        case Some(q) =>
          // The qualifier may itself be a column and `name` one of its fields (`c_email.x`,
          // `c.c_email.x`, `c_addr.city FROM customer AS c_addr`): DuckDB reads it so whenever
          // the binding `q` lacks the column, and a catalog may list a struct's fields as columns
          // (DuckLake), so a qualifier naming a covered column is a read of it whatever the
          // binding has.
          byName.exists(_.tableName == RoleColumnPolicy.Wildcard) || nameCovered(q, scopes) || {
            scopes.map(_.filter(_.names.contains(lower(q)))).filter(_.nonEmpty) match
              case Nil =>
                // Not named by any scope: any table the qualifier aliases anywhere, or itself.
                val targets = aliases.collect { case (a, t) if a == lower(q) => t } :+ q
                targets.exists(isPolicyTable(_, byName))
              case named => qualifiedCovered(name, named, byName)
          }
        case None => nameCovered(name, scopes)

  /** DuckDB's method call `x.f(args)` is `f(x, args)`; JSqlParser holds it as a function with the
    * multi-part name `[.., x, f]` and no node for `x`, so the masking visitor never saw the column
    * (`c_email.lower()`, `c.c_email.upper()`, in any clause). Each call whose leading parts name a
    * covered column, read as DuckDB binds a column reference, is rewritten in place to the function
    * form, which the visitor then masks like any other call. A call whose leading parts name no
    * covered column (a method on another column, a schema-qualified function) is left as written.
    * Some(reason) when a covered call cannot be rewritten exactly (named or keyword arguments,
    * DISTINCT, a chained argument list).
    */
  def normalizeMethodCalls(
      sel: AnyRef,
      policies: List[RoleColumnPolicy],
      schema: Map[String, List[String]]
  ): Option[String] =
    val before   = scan(sel)
    val coverage = new Coverage(before, policies, schema)
    val refusals = before.methodCalls.flatMap { (f, scopes) =>
      val parts  = f.getMultipartName.asScala.toList
      val column = receiverOf(f)
      if !coverage.covered(column, scopes) then None
      else if f.getNamedParameters != null || f.getKeywordArguments != null || f.isDistinct ||
        f.isAllColumns || f.getChainedParameters != null || f.getExtraKeyword != null
      then
        Some(
          s"a method call on the masked column ${column.getFullyQualifiedName} cannot be " +
            "rewritten to its function form; call the function instead"
        )
      else
        val args = new net.sf.jsqlparser.expression.operators.relational.ExpressionList[
          Expression
        ]()
        args.add(column)
        Option(f.getParameters).foreach(_.forEach(p => args.add(p.asInstanceOf[Expression])))
        f.setName(java.util.List.of(parts.last))
        f.setParameters(args)
        None
    }
    refusals.headOption

  /** The receiver of the method call `[.., x, f]`, read as a column reference: `[c_email, lower]`
    * is `c_email`, `[c, c_email, upper]` is `c.c_email`, `[c, to_json]` is `c`.
    */
  private def receiverOf(f: net.sf.jsqlparser.expression.Function): Column =
    val receiver = f.getMultipartName.asScala.toList.init
    if receiver.size == 1 then new Column(receiver.head)
    else new Column(new Table(receiver.init.asJava), receiver.last)

  /** The column references in `sel` a policy could cover, noted before the rewrite. */
  def suspects(
      sel: AnyRef,
      policies: List[RoleColumnPolicy],
      schema: Map[String, List[String]]
  ): Suspects =
    val before   = scan(sel)
    val coverage = new Coverage(before, policies, schema)
    import coverage.{covered, has, isPolicyTable}
    val noted = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[Column, java.lang.Boolean]()
    )
    before.columns.collect { case (c, scopes) if covered(c, scopes) => c }.foreach(noted.add(_))
    // Reads with no column node to mask, refused where a policy table is in scope.
    def policyTable(table: String): Boolean       = isPolicyTable(table, policies)
    def policyScope(scopes: List[Scope]): Boolean =
      scopes.exists(_.exists(_.table.exists(policyTable)))
    // The columns a NATURAL join compares are the names its sides share; with a side whose columns
    // are unknown the set cannot be told, so a policy table on either side denies.
    def naturalOverPolicy(left: List[Source], right: List[Source]): Boolean =
      def columns(srcs: List[Source]): Option[Set[String]] =
        srcs
          .map(src =>
            src.table match
              case Some(t) => coverage.columnsOf(t).map(_.map(lower).toSet)
              case None    => src.outputs
          )
          .foldLeft(Option(Set.empty[String]))((acc, c) => acc.zip(c).map(_ ++ _))
      val sides = left ++ right
      sides.exists(_.table.exists(policyTable)) && {
        (columns(left), columns(right)) match
          case (Some(l), Some(r)) =>
            (l intersect r).exists(name =>
              policies.exists(p =>
                p.columnName.equalsIgnoreCase(name) &&
                  (p.tableName == RoleColumnPolicy.Wildcard ||
                    sides.exists(_.table.exists(_.equalsIgnoreCase(p.tableName))))
              )
            )
          case _ => true
      }
    val formRefusal = before.forms.collectFirst {
      case (Form.TableStatement, _) =>
        "a TABLE statement in expression position (ARRAY(TABLE t), ANY (TABLE t)) reads every " +
          "column unmasked under column policies; select the columns with a subquery instead"
      case (Form.Expansion, scopes) if policyScope(scopes) =>
        "a star expansion (COLUMNS(..), * LIKE / ILIKE / GLOB / SIMILAR TO, *COLUMNS(..), a star " +
          "inside an expression) over a table with column policies cannot be masked; name the " +
          "columns instead"
      case (Form.StatementSource(table), _) if table.forall(policyTable) =>
        "a statement in table position ((TABLE t), (SUMMARIZE t), ..) reads a table with column " +
          "policies unmasked; select from the table instead"
      case (Form.NaturalJoin(left, right), _) if naturalOverPolicy(left, right) =>
        "a NATURAL join over a column with a column policy compares it unmasked; join with ON " +
          "over the masked column instead"
      case (Form.AliasColumns(table), _) if policyTable(table) =>
        "an alias column list on a table with column policies renames a masked column out of " +
          "the policy's reach; alias each column in the select list instead"
    }
    // DuckDB binds a name no source has as a column to a FROM binding of that name: the whole row
    // as a STRUCT (`SELECT c FROM customer c`, `main.customer`, `to_json(c)`), every column raw.
    def wholeRow(col: Column, scopes: List[Scope]): Boolean =
      val name    = lower(unquote(Option(col.getColumnName).getOrElse("")))
      val sources = scopes.flatten
      sources.exists(src => src.table.exists(policyTable) && src.names.contains(name)) &&
      !sources.exists(src => has(src, name).contains(true))
    // `c.to_json()` is `to_json(c)`: a method call's receiver binds like any column reference, so
    // a receiver naming a policy table's binding hands the whole row to the function.
    val receivers       = before.methodCalls.map((f, scopes) => receiverOf(f) -> scopes)
    val wholeRowRefusal = (before.columns ++ receivers).collectFirst {
      case (c, scopes) if wholeRow(c, scopes) =>
        "a table referenced as a column (the whole row as a struct) cannot be masked under " +
          "column policies; name the columns instead"
    }
    new Suspects(noted, before.unknown, formRefusal.orElse(wholeRowRefusal))

  /** Some(deny reason) when a noted reference is still in `sel` after the rewrite, or the statement
    * holds a node the walk cannot follow.
    */
  def unmasked(sel: AnyRef, suspects: Suspects): Option[String] =
    val after = scan(sel)
    suspects.unwalkable.orElse(after.unknown) match
      case Some(cls) => Some(s"column policy rewrite cannot walk a $cls node")
      case None if suspects.refusal.isDefined => suspects.refusal
      case None                               =>
        after.columns
          .map(_._1)
          .find(suspects.columns.contains)
          .map(c =>
            s"column ${c.getFullyQualifiedName} is in a position the column policy rewrite " +
              "cannot mask; select it directly instead"
          )
