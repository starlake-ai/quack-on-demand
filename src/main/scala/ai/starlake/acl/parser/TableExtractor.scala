package ai.starlake.acl.parser

import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.*
import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.operators.relational.*

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Result of a table-reference walk. `tables` carries every base-table reference found;
  * `unsupported` carries a human-readable marker for every construct the walker cannot map to a
  * grantable table (table functions like `read_parquet`, string-literal file refs, FROM item or
  * SELECT node types it does not recognize). A non-empty `unsupported` means the access set is
  * INCOMPLETE and the validator must fail closed instead of admitting on what was extracted.
  */
final case class TableExtraction(tables: List[Table], unsupported: List[String])

/** Extracts all table references from a JSqlParser Select AST.
  *
  * Walks the AST comprehensively: FROM clauses, JOINs, subqueries in WHERE/SELECT/HAVING, set
  * operations (UNION/INTERSECT/EXCEPT), and CTE bodies. Filters out CTE self-references; table
  * functions, string-literal file references (e.g., DuckDB 'file.parquet') and unrecognized node
  * types are reported as `unsupported` so the ACL validator can deny rather than silently admit.
  */
object TableExtractor:

  /** Extract all Table references from a parsed Select statement.
    *
    * @param select
    *   the JSqlParser Select AST node
    * @return
    *   tables (may contain duplicates; caller deduplicates after qualification) plus any
    *   unsupported-construct markers encountered during the walk
    */
  def extract(select: Select): TableExtraction =
    val visitor = new TableExtractorVisitor()
    visitor.process(select)
    visitor.result

private[parser] class TableExtractorVisitor:
  val tables: mutable.ListBuffer[Table]       = mutable.ListBuffer.empty
  val unsupported: mutable.ListBuffer[String] = mutable.ListBuffer.empty
  private val cteNames: mutable.Set[String]   = mutable.Set.empty

  // Identity-keyed set of AST nodes already walked, so the explicit arms and the unconditional
  // descendChildren pass cannot re-walk the same subtree: keeps the walk O(n) and cycle-proof even
  // though every node is now reachable from two directions (its arm and its parent's descend).
  private val visited: java.util.Set[AnyRef] =
    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]())

  def result: TableExtraction = TableExtraction(tables.toList, unsupported.toList)

  def process(select: Select): Unit =
    // Collect CTE names first (from WITH clause)
    val withItems = select.getWithItemsList
    if withItems != null then
      withItems.asScala.foreach { wi =>
        val aliasName = wi.getUnquotedAliasName
        if aliasName != null then cteNames += aliasName.toLowerCase
      }
      // Then visit CTE bodies
      withItems.asScala.foreach { wi =>
        val cteSelect = wi.getSelect
        if cteSelect != null then visitSelect(cteSelect)
      }

    // Visit the main select body
    visitSelect(select)

  private def visitSelect(select: Select): Unit =
    if select != null && visited.add(select) then matchSelect(select)

  private def matchSelect(select: Select): Unit =
    select match
      case ps: PlainSelect       => visitPlainSelect(ps)
      case sol: SetOperationList => visitSetOperationList(sol)
      case v: Values             =>
        // A VALUES row can embed a scalar subquery, e.g.
        // `INSERT INTO t VALUES ((SELECT c FROM s))`. Walk its expressions so those
        // reads surface as grantable table refs instead of being silently dropped.
        Option(v.getExpressions).foreach(visitExpression)
      case ls: LateralSubSelect                            => visitParenthesedSelect(ls)
      case psel: ParenthesedSelect                         => visitParenthesedSelect(psel)
      case fq: net.sf.jsqlparser.statement.piped.FromQuery =>
        // DuckDB / BigQuery FROM-first shorthand: `FROM t [pipe ops...]`.
        // FromQuery extends Select but does not match the PlainSelect /
        // SetOperationList / ParenthesedSelect arms above, so handle it
        // explicitly or the table ref would silently drop and the validator
        // would admit the query unconditionally.
        val fromItem = fq.getFromItem
        if fromItem != null then visitFromItem(fromItem)
        val joins = fq.getJoins
        if joins != null then
          joins.asScala.foreach { j =>
            val rightItem = j.getFromItem
            if rightItem != null then visitFromItem(rightItem)
            val onExpressions = j.getOnExpressions
            if onExpressions != null then onExpressions.asScala.foreach(visitExpression)
          }
      case other =>
        // Fail closed: a Select subtype this walker does not recognize may
        // carry table refs we would otherwise silently drop.
        unsupported += s"unrecognized select type ${other.getClass.getSimpleName}"

  private def visitPlainSelect(ps: PlainSelect): Unit =
    // FROM clause
    val fromItem = ps.getFromItem
    if fromItem != null then visitFromItem(fromItem)

    // JOINs
    val joins = ps.getJoins
    if joins != null then
      joins.asScala.foreach { join =>
        val rightItem = join.getFromItem
        if rightItem != null then visitFromItem(rightItem)
        // Visit ON expressions for subqueries
        val onExpressions = join.getOnExpressions
        if onExpressions != null then onExpressions.asScala.foreach(visitExpression)
      }

    // SELECT items (scalar subqueries)
    val selectItems = ps.getSelectItems
    if selectItems != null then
      selectItems.asScala.foreach { si =>
        val expr = si.getExpression
        if expr != null then visitExpression(expr)
      }

    // WHERE clause
    val where = ps.getWhere
    if where != null then visitExpression(where)

    // HAVING clause
    val having = ps.getHaving
    if having != null then visitExpression(having)

    // ORDER BY (rare subqueries)
    val orderBy = ps.getOrderByElements
    if orderBy != null then
      orderBy.asScala.foreach { obe =>
        val expr = obe.getExpression
        if expr != null then visitExpression(expr)
      }

  private def visitSetOperationList(sol: SetOperationList): Unit =
    val selects = sol.getSelects
    if selects != null then selects.asScala.foreach(visitSelect)

  private def visitParenthesedSelect(psel: ParenthesedSelect): Unit =
    // ParenthesedSelect wraps another Select
    val inner = psel.getSelect
    if inner != null then visitSelect(inner)
    // Also check if it has its own WITH items
    val withItems = psel.getWithItemsList
    if withItems != null then
      withItems.asScala.foreach { wi =>
        val aliasName = wi.getUnquotedAliasName
        if aliasName != null then cteNames += aliasName.toLowerCase
        val cteSelect = wi.getSelect
        if cteSelect != null then visitSelect(cteSelect)
      }

  private[parser] def visitFromItem(fromItem: FromItem): Unit =
    if fromItem != null && visited.add(fromItem) then matchFromItem(fromItem)

  private def matchFromItem(fromItem: FromItem): Unit =
    fromItem match
      case table: Table =>
        val name = table.getUnquotedName
        if name != null then
          // Skip CTE self-references, but ONLY when the reference is unqualified:
          // `WITH lineitem AS (...) SELECT * FROM db.main.lineitem` still names
          // the REAL table (CTEs shadow only bare names), so a qualified ref must
          // be extracted or the shadowing CTE would launder the access away.
          val isQualified = table.getSchemaName != null ||
            (table.getDatabase != null && table.getDatabase.getDatabaseName != null)
          val isCteName = !isQualified && cteNames.contains(name.toLowerCase)
          val isFileRef = table.getName != null && table.getName.startsWith("'")
          if isFileRef then
            // DuckDB `FROM 'file.parquet'` reads straight from storage, escaping
            // the tenant-catalog boundary; there is no table to grant on.
            unsupported += s"file reference ${table.getName}"
          else if !isCteName then tables += table
      case ls: LateralSubSelect  => visitParenthesedSelect(ls)
      case ps: ParenthesedSelect => visitParenthesedSelect(ps)
      case tf: TableFunction     =>
        // Table functions (read_parquet, read_csv, ...) can read files directly,
        // escaping the tenant-catalog boundary; no grantable table ref exists.
        val fnName = Option(tf.getFunction).map(_.getName).getOrElse("?")
        unsupported += s"table function $fnName"
      case pfi: ParenthesedFromItem =>
        // `FROM (a JOIN b ON ...)`: recurse into the wrapped item and its joins.
        val inner = pfi.getFromItem
        if inner != null then visitFromItem(inner)
        val joins = pfi.getJoins
        if joins != null then
          joins.asScala.foreach { j =>
            val ri = j.getFromItem
            if ri != null then visitFromItem(ri)
            val onExpressions = j.getOnExpressions
            if onExpressions != null then onExpressions.asScala.foreach(visitExpression)
          }
      case v: Values =>
        // A VALUES used as a derived table, e.g. `FROM (VALUES ((SELECT c FROM s))) v`,
        // can embed a subquery just like the INSERT ... VALUES form. Walk its
        // expressions so those reads surface instead of being silently dropped.
        Option(v.getExpressions).foreach(visitExpression)
      case sol: SetOperationList => visitSetOperationList(sol)
      case plain: PlainSelect    => visitPlainSelect(plain)
      case other                 =>
        // Fail closed on FROM item types this walker does not recognize.
        unsupported += s"unrecognized FROM item ${other.getClass.getSimpleName}"

  private[parser] def visitExpression(expr: Expression): Unit =
    if expr != null && visited.add(expr) then
      matchExpression(expr)
      // Completeness backstop: after the explicit arms, unconditionally descend into every
      // child of this node reflectively. This subsumes each arm's own recursion (the visited
      // set dedups it) AND reaches children the arms miss - window/aggregate clauses
      // (OVER ORDER BY / PARTITION BY, FILTER, WITHIN GROUP) and any wrapper type - so no
      // nested subquery is ever silently dropped.
      descendChildren(expr)

  private def matchExpression(expr: Expression): Unit =
    expr match
      case psel: ParenthesedSelect =>
        visitParenthesedSelect(psel)
      case sel: Select =>
        visitSelect(sel)
      case bin: BinaryExpression =>
        val left = bin.getLeftExpression
        if left != null then visitExpression(left)
        val right = bin.getRightExpression
        if right != null then visitExpression(right)
      case inExpr: InExpression =>
        val left = inExpr.getLeftExpression
        if left != null then visitExpression(left)
        val right = inExpr.getRightExpression
        if right != null then visitExpression(right)
      case exists: ExistsExpression =>
        val right = exists.getRightExpression
        if right != null then visitExpression(right)
      case not: NotExpression =>
        val inner = not.getExpression
        if inner != null then visitExpression(inner)
      case caseExpr: CaseExpression =>
        val switchExpr = caseExpr.getSwitchExpression
        if switchExpr != null then visitExpression(switchExpr)
        val whenClauses = caseExpr.getWhenClauses
        if whenClauses != null then
          whenClauses.asScala.foreach { wc =>
            val whenExpr = wc.getWhenExpression
            if whenExpr != null then visitExpression(whenExpr)
            val thenExpr = wc.getThenExpression
            if thenExpr != null then visitExpression(thenExpr)
          }
        val elseExpr = caseExpr.getElseExpression
        if elseExpr != null then visitExpression(elseExpr)
      case anyComp: AnyComparisonExpression =>
        val inner = anyComp.getSelect
        if inner != null then visitSelect(inner)
      case exprList: ExpressionList[?] =>
        exprList.asScala.foreach(e => visitExpression(e.asInstanceOf[Expression]))
      case func: Function =>
        val params = func.getParameters
        if params != null then visitExpression(params)
      case analytic: AnalyticExpression =>
        val inner = analytic.getExpression
        if inner != null then visitExpression(inner)
      case cast: CastExpression =>
        val left = cast.getLeftExpression
        if left != null then visitExpression(left)
      case between: Between =>
        val left = between.getLeftExpression
        if left != null then visitExpression(left)
        val start = between.getBetweenExpressionStart
        if start != null then visitExpression(start)
        val end = between.getBetweenExpressionEnd
        if end != null then visitExpression(end)
      case isNull: IsNullExpression =>
        val left = isNull.getLeftExpression
        if left != null then visitExpression(left)
      case json: JsonExpression =>
        // `(SELECT ...)->>'k'` wraps an arbitrary expression; recurse.
        val inner = json.getExpression
        if inner != null then visitExpression(inner)
      case signed: SignedExpression =>
        val inner = signed.getExpression
        if inner != null then visitExpression(inner)
      case ext: ExtractExpression =>
        val inner = ext.getExpression
        if inner != null then visitExpression(inner)
      case _ =>
        // Leaves (Column, literals) and any node the explicit arms do not model: descendChildren
        // (called by visitExpression right after this) reaches their children reflectively.
        ()

  /** Reflectively descend into every child of `node` that can carry a table reference, so no
    * subquery is dropped regardless of which wrapper or clause holds it. Routes each accessor value
    * by its RUNTIME type; the accessor list is memoized per class so leaves stay cheap.
    */
  private def descendChildren(node: Expression): Unit =
    TableExtractorVisitor.childAccessors(node.getClass).foreach { m =>
      try routeChild(m.invoke(node))
      catch case _: Throwable => ()
    }

  /** Route one reflectively-fetched child value to the right walk. `Expression` is tested before
    * `Select` so a `ParenthesedSelect` (which is both) goes through the visited-guarded expression
    * path; collections and arrays are iterated one level and their elements routed the same way.
    */
  private def routeChild(value: Any): Unit =
    value match
      case null          => ()
      case e: Expression => visitExpression(e)
      case s: Select     => visitSelect(s)
      // A bare Table reached through an expression accessor is a column qualifier
      // (e.g. Column.getTable in `c.c_id`), already surfaced via the FROM clause if it is a
      // read source; recording it here would wrongly add aliases as table accesses. Only
      // descend into non-Table from-items (derived tables, table functions, lateral, ...).
      case _: Table                  => ()
      case fi: FromItem              => visitFromItem(fi)
      case oe: OrderByElement        => Option(oe.getExpression).foreach(visitExpression)
      case it: java.lang.Iterable[?] => it.asScala.foreach(routeChild)
      case arr: Array[?]             => arr.foreach(routeChild)
      case _                         => ()

private[parser] object TableExtractorVisitor:
  import java.lang.reflect.Method

  private val accessorCache =
    new java.util.concurrent.ConcurrentHashMap[Class[?], Array[Method]]()

  /** The no-arg `get*` accessors of `cls` whose return type can hold a table reference: an
    * `Expression`, `Select`, `FromItem`, `OrderByElement`, any collection (`Iterable`, so a raw
    * `List<OrderByElement>` or `List<Expression>` is reached), or an array. Memoized per class so
    * the common leaf path (Column, literals: no such accessor) stays cheap.
    */
  def childAccessors(cls: Class[?]): Array[Method] =
    accessorCache.computeIfAbsent(
      cls,
      c =>
        c.getMethods.filter { m =>
          val rt = m.getReturnType
          m.getParameterCount == 0 &&
          m.getName.startsWith("get") &&
          (classOf[Expression].isAssignableFrom(rt) ||
            classOf[Select].isAssignableFrom(rt) ||
            classOf[FromItem].isAssignableFrom(rt) ||
            classOf[OrderByElement].isAssignableFrom(rt) ||
            classOf[java.lang.Iterable[?]].isAssignableFrom(rt) ||
            rt.isArray)
        }
    )
