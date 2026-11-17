package ai.starlake.quack.edge.search

import ai.starlake.acl.parser.AstChildren
import ai.starlake.sql.{ParserInput, SqlCommentStripper, SqlLexer, TooDeep}
import net.sf.jsqlparser.expression.{AnalyticExpression, Function}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.{DescribeStatement, ExplainStatement}
import net.sf.jsqlparser.statement.piped.FromQuery
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.select.{AllColumns, FromItem, PlainSelect, TableStatement}

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
      "* EXCLUDE / REPLACE, COLUMNS(*)) or a whole-row reference; list the columns instead"

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

  /** Each statement of the submission on its own (the statement splitter), transaction control
    * skipped, parsed with every DuckLake time-travel clause removed (Task 18 fix round 1, lead
    * ruling A): JSqlParser reads no `AT (VERSION => n)` and no `BEGIN`, and neither holds a
    * projection. The removal serves this check alone; what the router sends keeps the clause. A
    * clause holding anything a projection could hide in (a query, a star) is refused.
    */
  private def holdsStarOrUnreadable(sql: String): Boolean =
    ParserInput.timeTravelClauses(sql).exists(mayHoldProjection) ||
      (ParserInput.renderWithoutTimeTravel(sql) match
        case Left(_)   => true
        case Right(in) =>
          val text  = in.text
          val spans = SqlCommentStripper.statementSpans(text)
          spans.isEmpty || spans.exists { (from, until) =>
            val statement = text.substring(from, until)
            !transactionControl(statement) &&
            (Try(CCJSqlParserUtil.parse(statement)).toOption match
              case None       => true
              case Some(stmt) => holdsStar(stmt))
          })

  /** A time-travel clause's text holding a `*`, or a word that starts a query or names columns: the
    * clause is a constant in DuckDB, so a legitimate one holds none of them.
    */
  private def mayHoldProjection(clause: String): Boolean =
    SqlLexer.tokens(clause).exists { t =>
      val text = clause.substring(t.start, t.end)
      (t.kind == SqlLexer.Kind.Operator && text.contains('*')) ||
      (t.kind == SqlLexer.Kind.Word &&
        QueryWords.contains(text.toLowerCase(java.util.Locale.ROOT)))
    }

  private val QueryWords = Set(
    "select",
    "from",
    "table",
    "columns",
    "with",
    "values",
    "pivot",
    "unpivot",
    "summarize",
    "describe",
    "show"
  )

  /** One statement that is exactly transaction control, as DuckDB's tokenizer reads it, and nothing
    * else but trivia and a final `;`: `BEGIN [TRANSACTION | WORK] [<mode>]`,
    * `START TRANSACTION [<mode>]`, and `COMMIT`, `ROLLBACK`, `END` or `ABORT`, each optionally
    * followed by `TRANSACTION` or `WORK`. A mode is one of `READ ONLY`, `READ WRITE` or `ISOLATION
    * LEVEL` with `SERIALIZABLE`, `REPEATABLE READ`, `READ COMMITTED` or `READ UNCOMMITTED`; one
    * mode only, anything longer is parsed (and refused when it does not parse).
    */
  private def transactionControl(statement: String): Boolean =
    val toks  = SqlLexer.tokens(statement).filterNot(_.isTrivia)
    val parts = toks.map(t => (t.kind, statement.substring(t.start, t.end)))
    val words = parts.lastOption match
      case Some((SqlLexer.Kind.Punct, ";")) => parts.init
      case _                                => parts
    def isMode(rest: List[String]): Boolean = rest match
      case Nil                                          => true
      case List("read", "only") | List("read", "write") => true
      case "isolation" :: "level" :: level              => IsolationLevels(level)
      case _                                            => false
    words.forall(_._1 == SqlLexer.Kind.Word) &&
    (words.map(_._2.toLowerCase(java.util.Locale.ROOT)).toList match
      case "start" :: "transaction" :: mode                     => isMode(mode)
      case "begin" :: ("transaction" | "work") :: mode          => isMode(mode)
      case "begin" :: mode                                      => isMode(mode)
      case List(head) if TxEnds(head)                           => true
      case List(head, ("transaction" | "work")) if TxEnds(head) => true
      case _                                                    => false)

  private val TxEnds = Set("commit", "rollback", "end", "abort")

  private val IsolationLevels: Set[List[String]] = Set(
    List("serializable"),
    List("repeatable", "read"),
    List("read", "committed"),
    List("read", "uncommitted")
  )

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
    // Task 18 fix rounds 2 and 4 (security O1 and R3-1, lead rulings): the names a whole-row
    // reference can use (every FROM item's alias and every table name, qualifiers included: an
    // over-inclusion, fail closed), against which the whole statement is judged once the walk ends.
    val fromNames = scala.collection.mutable.Set.empty[String]

    def walk(node: AnyRef): Unit =
      if !found && seen.add(node) then
        node match
          case fi: FromItem =>
            Option(fi.getAlias).flatMap(a => Option(a.getName)).foreach(fromNames += folded(_))
          case _ => ()
        node match
          case t: Table => Option(t.getName).foreach(fromNames += folded(_))
          case _        => ()
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
    found || wholeRow(root, fromNames.toSet)

  /** True when the statement holds, in any expression wherever JSqlParser keeps one (select items,
    * FROM table function arguments and VALUES rows, JOIN conditions, WHERE, GROUP BY, HAVING, ORDER
    * BY, QUALIFY, windows, LIMIT and OFFSET, CTE bodies, subqueries at any depth, set operation
    * arms, RETURNING), a whole-row reference: a bare identifier naming a FROM item, or DuckDB's
    * dot-call on one (`p.to_json()`, `pages.to_json()`). DuckDB reads either as the whole row, a
    * struct of every column, `rowid` included for a row-policy holder's wrap (search spec 4.2 rule
    * 10's one result shape). The positions are not listed: the walk reaches every child of every
    * node ([[AstChildren]], the reflective walk every fail-closed JSqlParser walk of QoD shares;
    * the star walk above, which refuses a value it has no rule for, has already met every one).
    * Accepted over-refusals: such a reference in a predicate or an ordering (`WHERE p IS NOT NULL`,
    * `ORDER BY p`), and a real column, a target column or a dot-call on a column whose name equals
    * a FROM item's.
    */
  private def wholeRow(root: AnyRef, names: Set[String]): Boolean =
    val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    var found                   = false
    def visit(value: Any): Unit =
      if !found then
        value match
          case null                     => ()
          case c: Column if seen.add(c) =>
            val bare = Option(c.getTable).flatMap(t => Option(t.getName)).isEmpty
            if bare && Option(c.getColumnName).exists(n => names(folded(n))) then found = true
          case it: java.lang.Iterable[?]    => it.forEach(visit(_))
          case arr: Array[?]                => arr.foreach(visit(_))
          case e: java.util.Map.Entry[?, ?] => visit(e.getKey); visit(e.getValue)
          case m: java.util.Map[?, ?]       => m.entrySet.forEach(visit(_))
          case n: AnyRef if AstChildren.isAstNode(n) && seen.add(n) =>
            n match
              case f: Function =>
                // `p.to_json()` reads as the function `p.to_json`: its last qualifier is the row.
                val parts = Option(f.getMultipartName).map(_.asScala.toList).getOrElse(Nil)
                if parts.size >= 2 && names(folded(parts(parts.size - 2))) then found = true
              case _ => ()
            AstChildren.accessors(n.getClass).foreach(m => visit(AstChildren.read(m, n)))
          case _ => ()
    visit(root)
    found

  private def folded(name: String): String = unquote(name).toLowerCase(java.util.Locale.ROOT)

  private def isColumns(f: Function): Boolean =
    Option(f.getName).exists(n => unquote(n).equalsIgnoreCase("columns"))

  private def unmodified(star: AllColumns): Boolean =
    Option(star.getTransformers).forall(_.isEmpty)

  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)
