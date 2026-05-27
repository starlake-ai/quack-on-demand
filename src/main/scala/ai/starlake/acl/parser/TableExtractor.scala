package ai.starlake.acl.parser

import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.*
import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.operators.relational.*

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Extracts all table references from a JSqlParser Select AST.
  *
  * Walks the AST comprehensively: FROM clauses, JOINs, subqueries in WHERE/SELECT/HAVING,
  * set operations (UNION/INTERSECT/EXCEPT), and CTE bodies. Filters out CTE self-references
  * and string-literal file references (e.g., DuckDB 'file.parquet').
  */
object TableExtractor:

  /** Extract all Table references from a parsed Select statement.
    *
    * @param select the JSqlParser Select AST node
    * @return list of Table objects (may contain duplicates; caller deduplicates after qualification)
    */
  def extract(select: Select): List[Table] =
    val visitor = new TableExtractorVisitor()
    visitor.process(select)
    visitor.tables.toList

private[parser] class TableExtractorVisitor:
  val tables: mutable.ListBuffer[Table] = mutable.ListBuffer.empty
  private val cteNames: mutable.Set[String] = mutable.Set.empty

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
    select match
      case ps: PlainSelect         => visitPlainSelect(ps)
      case sol: SetOperationList   => visitSetOperationList(sol)
      case _: Values               => () // VALUES clause, no tables
      case ls: LateralSubSelect    => visitParenthesedSelect(ls)
      case psel: ParenthesedSelect => visitParenthesedSelect(psel)
      case _ => () // Other select types, no-op

  private def visitPlainSelect(ps: PlainSelect): Unit =
    // FROM clause
    val fromItem = ps.getFromItem
    if fromItem != null then visitFromItem(fromItem)

    // JOINs
    val joins = ps.getJoins
    if joins != null then
      joins.asScala.foreach { join =>
        val rightItem = join.getRightItem
        if rightItem != null then visitFromItem(rightItem)
        // Visit ON expressions for subqueries
        val onExpressions = join.getOnExpressions
        if onExpressions != null then
          onExpressions.asScala.foreach(visitExpression)
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
    if selects != null then
      selects.asScala.foreach(visitSelect)

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

  private def visitFromItem(fromItem: FromItem): Unit =
    fromItem match
      case table: Table =>
        // Skip CTE self-references and string-literal file references
        val name = table.getUnquotedName
        if name != null then
          val isCteName = cteNames.contains(name.toLowerCase)
          val isFileRef = table.getName != null && table.getName.startsWith("'")
          if !isCteName && !isFileRef then tables += table
      case ls: LateralSubSelect  => visitParenthesedSelect(ls)
      case ps: ParenthesedSelect => visitParenthesedSelect(ps)
      case _: TableFunction => () // Silently ignore table functions
      case _: Values        => () // Silently ignore VALUES
      case sol: SetOperationList => visitSetOperationList(sol)
      case plain: PlainSelect    => visitPlainSelect(plain)
      case _ => () // Other FROM item types, no-op

  private def visitExpression(expr: Expression): Unit =
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
      case _ => () // Leaf expressions (Column, LongValue, StringValue, etc.) -- no-op
