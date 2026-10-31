package ai.starlake.quack.edge.sql

import ai.starlake.quack.edge.{NodeRows, NodeValue}
import ai.starlake.quack.edge.authz.{FunctionClass, FunctionClasses}
import ai.starlake.quack.model.SqlLiterals
import ai.starlake.sql.SqlLexer

import java.nio.charset.StandardCharsets
import java.util.Locale

/** File locations a tenant principal's statement computes (Task 19 part D): the dated-file pattern
  * `read_csv('s3://acme-etl/in/' || strftime(today(), '%Y%m%d') || '.csv')`, or a COPY whose target
  * is `TO (<expression>)`. A file location must be a literal for the node to judge it (Task 8), so
  * such a statement is refused as written.
  *
  * The gateway decides nothing here either. It finds each computed location on DuckDB's own tokens
  * (never JSqlParser): the first positional argument of a call to a function of class `File`, up to
  * the first top-level `,` or `)` (strings and comments are tokens of their own), or a COPY's
  * parenthesized `FROM` / `TO` location. The router then has the node evaluate each one
  * ([[evaluation]], as its own statement, with the principal's own handle, judged like any other)
  * and puts the value in its place as a string literal ([[literal]], [[substitute]]), so the
  * statement that runs names a literal location that the gateway's policy screens and the node's
  * full screen judge exactly as if the client had typed it. Nothing else in the statement is touched.
  *
  * Recognized only when the statement is one statement that runs once (a query, a SUMMARIZE, a
  * PIVOT or UNPIVOT, a COPY, an INSERT, UPDATE, DELETE or MERGE: [[RunOnceWords]]; a stored
  * definition would keep the value of its creation forever) and the expression holds no subquery
  * and no parameter (a statement evaluated alone could not read one); anything else, and any
  * failure (the evaluation refused or failing, a NULL, a value that is not one non-empty VARCHAR,
  * or a LIST of them for a reader, more than [[MaxBytes]]), leaves the statement as the client
  * wrote it, so the node refuses it as before.
  */
private[quack] object ComputedLocations:

  /** One computed location: `[from, until)` of the statement is replaced, `expression` is what the
    * node evaluates, `list` whether a LIST of VARCHAR may stand there (a reader's file list).
    */
  final case class Location(from: Int, until: Int, expression: String, list: Boolean)

  /** The most bytes (UTF-8) one computed location may evaluate to, every element of a list counted.
    */
  val MaxBytes: Int = 4096

  /** The most computed locations one statement may hold; past it nothing is evaluated. */
  val MaxLocations: Int = 16

  /** The functions of class `File` (function-classes.tsv): their first argument names files. */
  private lazy val FileFunctions: Set[String] = FunctionClasses.all.collect {
    case (k, FunctionClass.File) => k.name
  }.toSet

  /** Words that start a query: an expression holding one holds a subquery. */
  private val QueryWords =
    Set(
      "select",
      "from",
      "with",
      "values",
      "table",
      "pivot",
      "unpivot",
      "summarize",
      "describe",
      "show"
    )

  /** The words a statement that runs once begins with, past any opening parentheses: a query (its
    * FROM-first and CTE forms included), a SUMMARIZE, a PIVOT or UNPIVOT, a COPY in either
    * direction, an INSERT, UPDATE, DELETE or MERGE. A statement that stores its text to run later
    * (anything beginning with CREATE: CREATE VIEW, CREATE TABLE AS, CREATE MACRO; PREPARE) or does
    * not run it at all (EXPLAIN) is not one: its location would be computed once, at submission.
    */
  private val RunOnceWords =
    Set(
      "select",
      "with",
      "from",
      "summarize",
      "pivot",
      "unpivot",
      "copy",
      "insert",
      "update",
      "delete",
      "merge"
    )

  /** The computed locations of `sql`, in text order, or Nil when it is not one statement beginning
    * with a word of [[RunOnceWords]] (a query, SUMMARIZE, PIVOT, UNPIVOT, COPY, INSERT, UPDATE,
    * DELETE, MERGE), holds none this reader takes, or holds more than [[MaxLocations]].
    */
  def find(sql: String): List[Location] =
    val view = SqlLexer.engineView(Option(sql).getOrElse(""))
    val all  = SqlLexer.tokens(view).filterNot(_.isTrivia)
    val toks = if all.nonEmpty && text(view, all.last) == ";" then all.dropRight(1) else all
    if toks.isEmpty || toks.exists(t => text(view, t) == ";") || !runsOnce(view, toks) then Nil
    else
      val r     = Reader(sql, view, toks)
      val found = r.copyLocation.toList ++ r.callLocations
      if found.length > MaxLocations then Nil else found.sortBy(_.from)

  /** Whether the statement of `toks` begins, past any opening parentheses, with a word of
    * [[RunOnceWords]].
    */
  private def runsOnce(view: String, toks: Seq[SqlLexer.Token]): Boolean =
    toks
      .dropWhile(t => t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == '(')
      .headOption
      .exists(t =>
        t.kind == SqlLexer.Kind.Word &&
          RunOnceWords.contains(text(view, t).toLowerCase(Locale.ROOT))
      )

  /** The statement the node evaluates for `location`, as the principal. */
  def evaluation(location: Location): String = s"SELECT (${location.expression}) AS location"

  /** The SQL literal standing for what `rows` (the [[evaluation]]'s result) holds for `location`,
    * or why none does (Left): it is not one non-empty VARCHAR (or, where a list may stand, a
    * non-empty LIST of them), holds a NUL (which a C string would end the statement at), or is
    * longer than [[MaxBytes]].
    */
  def literal(location: Location, rows: NodeRows): Either[String, String] =
    def checked(values: List[String]): Either[String, List[String]] =
      if values.exists(_.isEmpty) then Left("the location is an empty string")
      else if values.exists(_.indexOf(0) >= 0) then Left("the location holds a NUL character")
      else if values.map(_.getBytes(StandardCharsets.UTF_8).length.toLong).sum > MaxBytes then
        Left(s"the location is longer than $MaxBytes bytes")
      else Right(values)
    rows.rows match
      case List(value :: _) =>
        value match
          case NodeValue.Text(text) => checked(List(text)).map(_ => SqlLiterals.duckdbLiteral(text))
          case NodeValue.Null       => Left("the location is NULL")
          case NodeValue.TextList(_) if !location.list =>
            Left("the location is a list, and a COPY takes one file name")
          case NodeValue.TextList(Nil) => Left("the location is an empty list")
          case NodeValue.TextList(items) if items.contains(None) =>
            Left("the location list holds a NULL")
          case NodeValue.TextList(items) =>
            checked(items.flatten).map(_.map(SqlLiterals.duckdbLiteral).mkString("[", ", ", "]"))
          case NodeValue.Other(tpe) => Left(s"the location is a $tpe, not a VARCHAR")
      case List(Nil) => Left("the location expression returned no column")
      case other     => Left(s"the location expression returned ${other.size} rows")

  /** `sql` with each location replaced by its literal; `replaced` is in text order. */
  def substitute(sql: String, replaced: List[(Location, String)]): String =
    val out  = new StringBuilder
    var last = 0
    replaced.foreach { (location, literal) =>
      out.append(sql.substring(last, location.from)).append(literal)
      last = location.until
    }
    out.append(sql.substring(last)).toString

  private def text(view: String, t: SqlLexer.Token): String = view.substring(t.start, t.end)

  private final case class Reader(sql: String, view: String, toks: Vector[SqlLexer.Token]):

    private def punct(k: Int, c: Char): Boolean =
      k < toks.length && toks(k).kind == SqlLexer.Kind.Punct && view.charAt(toks(k).start) == c

    private def word(k: Int): Option[String] =
      if k < toks.length && toks(k).kind == SqlLexer.Kind.Word then
        Some(text(view, toks(k)).toLowerCase(Locale.ROOT))
      else None

    private def opens(k: Int): Boolean  = punct(k, '(') || punct(k, '[') || punct(k, '{')
    private def closes(k: Int): Boolean = punct(k, ')') || punct(k, ']') || punct(k, '}')

    /** The index of the token closing the bracket at `open`, or -1. */
    private def closing(open: Int): Int =
      var depth = 0
      var i     = open
      var close = -1
      while close < 0 && i < toks.length do
        if opens(i) then depth += 1
        else if closes(i) then
          depth -= 1
          if depth == 0 then close = i
        i += 1
      close

    /** The tokens `[from, until)` as one expression the node can evaluate alone: not empty, not a
      * named argument, no subquery, no parameter, no file reader of its own.
      */
    private def evaluable(from: Int, until: Int): Boolean =
      val span  = toks.slice(from, until)
      def named = span.length > 1 && span(0).kind == SqlLexer.Kind.Word &&
        Set(":=", "=>")(text(view, span(1)))
      span.nonEmpty && !named && span.forall { t =>
        val s         = text(view, t)
        val parameter =
          t.kind != SqlLexer.Kind.Literal && (s.startsWith("?") || s.startsWith("$"))
        val query  = t.kind == SqlLexer.Kind.Word && QueryWords(s.toLowerCase(Locale.ROOT))
        val reader = FunctionCalls.identifier(view, t).exists(FileFunctions)
        !parameter && !query && !reader
      }

    /** One VARCHAR literal, or a `[...]` of them: a location the node already reads. */
    private def literalLocation(from: Int, until: Int): Boolean =
      if until - from == 1 then FunctionCalls.varcharValue(view, toks(from)).isDefined
      else
        punct(from, '[') && punct(until - 1, ']') && until - from >= 3 && (until - from) % 2 == 1 &&
        (from + 1 until until - 1).forall { k =>
          if (k - from) % 2 == 1 then FunctionCalls.varcharValue(view, toks(k)).isDefined
          else punct(k, ',')
        }

    private def location(
        from: Int,
        until: Int,
        list: Boolean,
        replaceFrom: Int,
        replaceUntil: Int
    ) =
      Location(
        toks(replaceFrom).start,
        toks(replaceUntil - 1).end,
        sql.substring(toks(from).start, toks(until - 1).end),
        list
      )

    /** Every call of a `File` function whose first argument is computed. */
    def callLocations: List[Location] =
      (0 until toks.length - 1).toList.flatMap { i =>
        val call =
          FunctionCalls.identifier(view, toks(i)).exists(FileFunctions) && punct(i + 1, '(')
        if !call then None
        else
          val close = closing(i + 1)
          // The first argument: up to the first top-level `,`, else the call's `)`.
          var end   = i + 2
          var depth = 0
          while end < close && !(depth == 0 && punct(end, ',')) do
            if opens(end) then depth += 1 else if closes(end) then depth -= 1
            end += 1
          if close < 0 || end == i + 2 || literalLocation(i + 2, end) || !evaluable(i + 2, end)
          then None
          else Some(location(i + 2, end, list = true, i + 2, end))
      }

    /** A lone COPY whose `FROM` or `TO` location is `(<expression>)` (DuckDB 2.0 takes a computed
      * location only in parentheses); the parentheses go with the expression.
      */
    def copyLocation: Option[Location] =
      if !word(0).contains("copy") then None
      else
        val afterSource =
          if punct(1, '(') then closing(1) + 1
          else
            // A table name, its parts dot-separated, then an optional column list.
            var k = 1
            while k < toks.length && FunctionCalls.identifier(view, toks(k)).isDefined &&
              k + 1 < toks.length && text(view, toks(k + 1)) == "."
            do k += 2
            val afterName = k + 1
            if punct(afterName, '(') then closing(afterName) + 1 else afterName
        val direction = word(afterSource)
        val open      = afterSource + 1
        if afterSource <= 0 || !(direction.contains("to") || direction.contains("from")) ||
          !punct(open, '(')
        then None
        else
          val close = closing(open)
          if close < 0 || !evaluable(open + 1, close) then None
          else Some(location(open + 1, close, list = false, open, close + 1))
