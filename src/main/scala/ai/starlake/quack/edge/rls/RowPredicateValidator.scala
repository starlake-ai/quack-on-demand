package ai.starlake.quack.edge.rls

import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.{Function => SqlFunction}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.Select

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** Strict-safety validator for free-form row-filter predicates on row policies. Runs once at policy
  * create/update time. Pure (no I/O, no DuckDB connection).
  *
  * A `predicate_sql` value must be ONE boolean DuckDB expression that:
  *   1. Parses via JSqlParser's `parseExpression` entry point once identity-substitution tokens
  *      (`${user}`, `${tenant}`, `${tenantId}`, `${groups}`, `${roles}`) are neutralised.
  *   2. Contains no subqueries (parenthesed SELECTs) or EXISTS clauses.
  *   3. Calls no side-effect or escape functions (denylist + `pragma_*` prefix).
  *   4. Is bounded in size (<= 1024 chars).
  *
  * Unlike [[ai.starlake.quack.edge.cls.TransformSqlValidator]] the predicate may reference ANY
  * column (it filters rows of the protected table), so there is no single-column containment check.
  * The canonical form returned keeps the `${...}` tokens intact - they are substituted by the
  * rewriter at query time, not here.
  */
object RowPredicateValidator:

  sealed trait Result
  final case class Valid(canonical: String) extends Result
  final case class Invalid(reason: String)  extends Result

  private val MaxLen = 1024

  /** Same escape/side-effect denylist as the CLS transform validator. */
  private val Denylist: Set[String] = Set(
    "read_csv",
    "read_csv_auto",
    "read_parquet",
    "read_json",
    "read_json_auto",
    "query",
    "sql",
    "attach",
    "detach",
    "install",
    "load",
    "system",
    "current_setting",
    "set_setting",
    "pg_read_server_files",
    "pg_read_binary_file"
  )

  private val TokenRegex = "\\$\\{[a-zA-Z]+\\}".r

  def validate(predicateSql: String): Result =
    val trimmed = Option(predicateSql).map(_.trim).getOrElse("")
    if trimmed.isEmpty then return Invalid("predicateSql is empty")
    if trimmed.length > MaxLen then
      return Invalid(s"predicateSql exceeds $MaxLen chars (got ${trimmed.length})")

    // Neutralise identity tokens before parsing: `${user}` -> NULL so both bare (`u = ${user}`)
    // and list (`g IN (${groups})`) forms parse. Tokens inside string literals collapse to the
    // literal 'NULL', which is harmless for the parse check.
    val probe = TokenRegex.replaceAllIn(trimmed, "NULL")

    val expr: Expression = Try(CCJSqlParserUtil.parseExpression(probe)) match
      case Success(e)  => e
      case Failure(ex) => return Invalid(s"predicateSql does not parse: ${ex.getMessage}")

    val violations = scala.collection.mutable.ListBuffer.empty[String]
    walk(expr) {
      case _: Select =>
        violations += "subqueries are not allowed in predicateSql"
      case _: net.sf.jsqlparser.expression.operators.relational.ExistsExpression =>
        violations += "EXISTS subqueries are not allowed in predicateSql"
      case fn: SqlFunction =>
        val name = Option(fn.getName).getOrElse("").toLowerCase
        if Denylist.contains(name) || name.startsWith("pragma_") then
          violations += s"function '$name' is not allowed in predicateSql"
      case _ => ()
    }

    if violations.nonEmpty then return Invalid(violations.distinct.mkString("; "))

    // Keep the original (tokens intact) as the canonical stored form.
    Valid(trimmed)

  /** Walk reachable expression nodes depth-first. Mirrors
    * [[ai.starlake.quack.edge.cls.TransformSqlValidator]] but additionally descends into IN-lists so
    * a subquery hidden in `x IN (SELECT ...)` is still flagged.
    */
  private def walk(start: Expression)(visit: PartialFunction[Any, Unit]): Unit =
    val stack = scala.collection.mutable.Stack[Any](start)
    while stack.nonEmpty do
      val node = stack.pop()
      if visit.isDefinedAt(node) then visit(node)
      node match
        case fn: SqlFunction =>
          if fn.getParameters != null then fn.getParameters.asScala.foreach(stack.push)
        case b: net.sf.jsqlparser.expression.BinaryExpression =>
          stack.push(b.getLeftExpression); stack.push(b.getRightExpression)
        case u: net.sf.jsqlparser.expression.SignedExpression =>
          stack.push(u.getExpression)
        case p: net.sf.jsqlparser.expression.Parenthesis =>
          stack.push(p.getExpression)
        case n: net.sf.jsqlparser.expression.NotExpression =>
          stack.push(n.getExpression)
        case in: net.sf.jsqlparser.expression.operators.relational.InExpression =>
          if in.getLeftExpression != null then stack.push(in.getLeftExpression)
          if in.getRightExpression != null then stack.push(in.getRightExpression)
        case c: net.sf.jsqlparser.expression.CaseExpression =>
          if c.getSwitchExpression != null then stack.push(c.getSwitchExpression)
          if c.getElseExpression != null then stack.push(c.getElseExpression)
          if c.getWhenClauses != null then
            c.getWhenClauses.asScala.foreach { w =>
              stack.push(w.getWhenExpression)
              stack.push(w.getThenExpression)
            }
        case cast: net.sf.jsqlparser.expression.CastExpression =>
          stack.push(cast.getLeftExpression)
        case _: Select =>
          () // visited above; do not recurse
        case _ => ()
