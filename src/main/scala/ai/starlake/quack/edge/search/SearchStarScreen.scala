package ai.starlake.quack.edge.search

import ai.starlake.acl.parser.AstChildren
import ai.starlake.sql.{ParserInput, SqlCommentStripper, TooDeep}
import net.sf.jsqlparser.expression.{AnalyticExpression, Function}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.{DescribeStatement, ExplainStatement}
import net.sf.jsqlparser.statement.piped.FromQuery
import net.sf.jsqlparser.statement.select.{AllColumns, PlainSelect, TableStatement}

import scala.jdk.CollectionConverters._
import scala.util.Try

/** Search statements refuse star projections, for every principal, superusers included (search spec
  * 4.2, the star rule; owner ruling 2026-11-14). In a statement that
  * [[SearchFunctions.callsSearch]] flags, the row-policy wrap keeps DuckDB's `rowid` (rule 3), so a
  * star over a wrapped table would expand differently for a row-policy holder than for anyone else;
  * refusing the star for everyone keeps one result shape per statement.
  *
  * Refused: `*` and `t.*` as a projection anywhere (any select list, subqueries, CTEs, set
  * operations, `RETURNING`), with any modifier (`* EXCLUDE (..)`, `* REPLACE (..)`, `* RENAME`, a
  * pattern), `COLUMNS(*)` and every other `COLUMNS(..)` call whose argument is a star, and a star
  * inside an expression; the implicit stars too: DuckDB's FROM-first query with no select list
  * (`FROM t`, also as a subquery or a set-operation arm), `TABLE t`, a pipe query, and DESCRIBE /
  * SUMMARIZE of a query, whose answer lists every column of it. Allowed: a function's sole
  * unmodified star argument (`count(*)`, `count(t.*)`, `count(*) OVER (..)`,
  * `count(*) FILTER (..)`), which DuckDB reads as no argument, and multiplication, which is no star
  * node at all.
  *
  * Decided on JSqlParser's AST of every statement of the submission (the parser the policy
  * rewriters use), read from the lexer-canonical rendering, and walked through every child
  * ([[AstChildren]]), so a star anywhere is reached. Fail closed: a submission that cannot be
  * rendered, a statement that does not parse, a value the walk has no rule for, or a walk that
  * overflows is refused with the same code.
  *
  * The router judges the client's own statement, never a gateway-built wrapper (the Flight SQL
  * schema probe `SELECT * FROM (<sql>) AS _qod_probe LIMIT 0` is one), and reads the search flag
  * once for the screen and both policy rewriters.
  */
private[quack] object SearchStarScreen:

  /** The stable error code of the refusal, carried in its message. */
  val Code = "search_star_unsupported"

  val Message: String =
    s"$Code: a statement calling a search function cannot use a star projection (*, t.*, " +
      "* EXCLUDE / REPLACE, COLUMNS(*)); list the columns instead"

  /** Some(Message) when `sql` calls a search function and holds a star projection, or calls one and
    * cannot be read; None otherwise. A submission with no search call is never parsed here.
    */
  def refusal(sql: String): Option[String] =
    refusal(sql, TooDeep.orElse(true)(SearchFunctions.callsSearch(sql)))

  /** [[refusal]] with the search flag already read from `sql` ([[SearchFunctions.callsSearch]]). */
  def refusal(sql: String, searchStatement: Boolean): Option[String] =
    if !searchStatement then None
    else if TooDeep.orElse(true)(holdsStarOrUnreadable(sql)) then Some(Message)
    else None

  private def holdsStarOrUnreadable(sql: String): Boolean =
    ParserInput.render(sql) match
      case Left(_)   => true
      case Right(in) =>
        val text  = in.text
        val spans = SqlCommentStripper.statementSpans(text)
        spans.isEmpty || spans.exists { (from, until) =>
          Try(CCJSqlParserUtil.parse(text.substring(from, until))).toOption match
            case None       => true
            case Some(stmt) => holdsStar(stmt)
        }

  /** True when the walk meets a star projection, or a value it cannot classify. */
  private def holdsStar(root: AnyRef): Boolean =
    val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    // Stars standing as a function's sole argument: no projection (`count(*)`).
    val argumentStars = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    var found = false

    def walk(node: AnyRef): Unit =
      if !found && seen.add(node) then
        node match
          case f: Function =>
            if isColumns(f) then found = true
            else soleStar(Option(f.getParameters).map(_.asScala.toList).getOrElse(Nil))
          case ae: AnalyticExpression =>
            if Option(ae.getName).exists(n => unquote(n).equalsIgnoreCase("columns")) then
              found = true
            else soleStar(Option(ae.getExpression).toList)
          case k: Function.KeywordArgument
              if Option(k.getKeyword).exists(_.trim.equalsIgnoreCase("columns")) =>
            found = true
          case star: AllColumns if !argumentStars.contains(star) =>
            found = true
          // DuckDB's `FROM t` with no select list reads as `SELECT *`.
          case ps: PlainSelect if Option(ps.getSelectItems).forall(_.isEmpty) =>
            found = true
          case _: TableStatement | _: FromQuery | _: DescribeStatement => found = true
          // JSqlParser reads SUMMARIZE (and DESCRIBE of a query) as an ExplainStatement under its
          // own keyword: its answer lists every column of the query.
          case ex: ExplainStatement
              if !Option(ex.getKeyword)
                .exists(_.trim.toLowerCase(java.util.Locale.ROOT).startsWith("explain")) =>
            found = true
          case _ => ()
        if !found then
          AstChildren.accessors(node.getClass).foreach(m => route(AstChildren.read(m, node)))

    def soleStar(args: List[Any]): Unit =
      args match
        case List(star: AllColumns) if unmodified(star) => argumentStars.add(star): Unit
        case _                                          => ()

    def route(value: Any): Unit =
      if !found then
        value match
          case null                                  => ()
          case it: java.lang.Iterable[?]             => it.forEach(route(_))
          case arr: Array[?]                         => arr.foreach(route(_))
          case e: java.util.Map.Entry[?, ?]          => route(e.getKey); route(e.getValue)
          case m: java.util.Map[?, ?]                => m.entrySet.forEach(route(_))
          case n: AnyRef if AstChildren.isAstNode(n) => walk(n)
          case leaf if AstChildren.isLeaf(leaf)      => ()
          case _                                     => found = true

    walk(root)
    found

  private def isColumns(f: Function): Boolean =
    Option(f.getName).exists(n => unquote(n).equalsIgnoreCase("columns"))

  private def unmodified(star: AllColumns): Boolean =
    Option(star.getTransformers).forall(_.isEmpty)

  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)
