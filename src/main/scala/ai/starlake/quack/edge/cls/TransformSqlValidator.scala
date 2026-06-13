package ai.starlake.quack.edge.cls

import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.{Function => SqlFunction}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.select.Select

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** Strict-containment validator for free-form transform expressions on column policies.
  * Runs once at policy create/update time. Pure (no I/O, no DuckDB connection).
  *
  * A `transform_sql` value must be ONE scalar DuckDB expression that:
  *   1. Parses via JSqlParser's `parseExpression` entry point.
  *   2. References only the protected column (every Column node has name == protectedColumn,
  *      case-insensitive).
  *   3. Contains no subqueries (parenthesed SELECTs).
  *   4. Calls no side-effect or escape functions (denylist + `pragma_*` prefix).
  *   5. Is bounded in size (<= 1024 chars in canonical form).
  */
object TransformSqlValidator:

  sealed trait Result
  final case class Valid(canonical: String) extends Result
  final case class Invalid(reason: String)  extends Result

  private val MaxLen = 1024

  private val Denylist: Set[String] = Set(
    "read_csv", "read_csv_auto", "read_parquet", "read_json", "read_json_auto",
    "query", "sql",
    "attach", "detach", "install", "load", "system",
    "current_setting", "set_setting",
    "pg_read_server_files", "pg_read_binary_file"
  )

  def validate(transformSql: String, protectedColumn: String): Result =
    val trimmed = Option(transformSql).map(_.trim).getOrElse("")
    if trimmed.isEmpty then return Invalid("transformSql is empty")

    // CCJSqlParserUtil.parseExpression parses an arbitrary scalar expression directly,
    // without needing a SELECT ... FROM wrapper.
    val expr: Expression = Try(CCJSqlParserUtil.parseExpression(trimmed)) match
      case Success(e)  => e
      case Failure(ex) => return Invalid(s"transformSql does not parse: ${ex.getMessage}")

    val violations = scala.collection.mutable.ListBuffer.empty[String]
    walk(expr) {
      case col: Column =>
        if !col.getColumnName.equalsIgnoreCase(protectedColumn) then
          violations +=
            s"transformSql references column '${col.getColumnName}' but the policy is on '$protectedColumn'"
      case _: Select =>
        // Select is the common supertype of PlainSelect, ParenthesedSelect, SetOperationList, etc.
        // Any SELECT inside an expression is a subquery - reject it.
        violations += "subqueries are not allowed in transformSql"
      case _: net.sf.jsqlparser.expression.operators.relational.ExistsExpression =>
        violations += "EXISTS subqueries are not allowed in transformSql"
      case fn: SqlFunction =>
        val name = Option(fn.getName).getOrElse("").toLowerCase
        if Denylist.contains(name) || name.startsWith("pragma_") then
          violations += s"function '$name' is not allowed in transformSql"
      case _ => ()
    }

    if violations.nonEmpty then return Invalid(violations.distinct.mkString("; "))

    val canonical = expr.toString
    if canonical.length > MaxLen then
      return Invalid(s"canonical transformSql exceeds $MaxLen chars (got ${canonical.length})")

    Valid(canonical)

  /** Walk reachable expression nodes depth-first.
    *
    * Covers the composite shapes that matter for validation: Function arguments, BinaryExpression,
    * SignedExpression, Parenthesis, CaseExpression, CastExpression. Subquery nodes (Select
    * subtypes) are flagged via the visitor and NOT recursed into so that we don't descend into
    * their internals and produce spurious column violations.
    */
  private def walk(start: Expression)(visit: PartialFunction[Any, Unit]): Unit =
    val stack = scala.collection.mutable.Stack[Any](start)
    while stack.nonEmpty do
      val node = stack.pop()
      if visit.isDefinedAt(node) then visit(node)
      node match
        case fn: SqlFunction =>
          if fn.getParameters != null then
            fn.getParameters.asScala.foreach(stack.push)
        case b: net.sf.jsqlparser.expression.BinaryExpression =>
          stack.push(b.getLeftExpression); stack.push(b.getRightExpression)
        case u: net.sf.jsqlparser.expression.SignedExpression =>
          stack.push(u.getExpression)
        case p: net.sf.jsqlparser.expression.Parenthesis =>
          stack.push(p.getExpression)
        case c: net.sf.jsqlparser.expression.CaseExpression =>
          if c.getSwitchExpression != null then stack.push(c.getSwitchExpression)
          if c.getElseExpression   != null then stack.push(c.getElseExpression)
          if c.getWhenClauses      != null then
            c.getWhenClauses.asScala.foreach { w =>
              stack.push(w.getWhenExpression)
              stack.push(w.getThenExpression)
            }
        case cast: net.sf.jsqlparser.expression.CastExpression =>
          stack.push(cast.getLeftExpression)
        case _: Select =>
          // Visited above; do NOT recurse - we reject subqueries wholesale.
          ()
        case _ => ()