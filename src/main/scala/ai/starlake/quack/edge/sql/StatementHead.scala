package ai.starlake.quack.edge.sql

import ai.starlake.sql.{SqlCommentStripper, SqlLexer, SqlTrivia}

import java.util.Locale
import scala.collection.mutable.ListBuffer

/** The lexing primitives the statement screens share: the statement split, the screening text of
  * one statement, where the statement an EXPLAIN carries begins, and the setting a SET / RESET /
  * PRAGMA names. Every input but [[split]]'s is ONE statement, as [[split]] cuts it.
  */
private[quack] object StatementHead:

  /** Splits the input on top-level semicolons: a semicolon inside any of DuckDB's quoting forms
    * (plain `'...'`, escape `E'...'`, double-quoted and backtick identifiers, dollar-quoted
    * `$$...$$` / `$tag$...$tag$`), a line comment, or a (nested) block comment does not split.
    * Blank fragments are dropped; a fragment holding only a comment is kept (callers that judge
    * statements drop it themselves).
    *
    * Every boundary comes from `ai.starlake.sql.SqlLexer`, the one reader shared with
    * `SqlCommentStripper` and `StatementClassifier` and ported from DuckDB 2.0's own front end, so
    * the splitter, the stripper and the engine can never disagree on where a literal ends. Knowing
    * only `'...'` and `"..."` was a desync, not a cosmetic gap: DuckDB reads `$x$'$x$`,
    * `$<EURO>$'$<EURO>$` and `E'\''` as complete strings holding one quote each, and a backtick
    * identifier as an identifier, while a `'`-only splitter opened a single-quoted run at that
    * inner quote (or a comment at a backticked comment opener) and merged every following statement
    * into the leading `select` fragment. A line comment ends at a line feed OR a bare carriage
    * return, as on DuckDB.
    *
    * Each fragment is cut from `SqlLexer.engineView(sql)`, not from `sql`: verbatim except that the
    * unicode spaces DuckDB's own pre-pass turns into spaces ARE spaces, and those it keeps are
    * pinned to stand-ins. Every caller re-lexes its fragments (stripping comments, classifying),
    * and the pre-pass state at a statement boundary depends on everything before it: a fragment cut
    * from the original text would lex alone differently from how DuckDB lexed it in place.
    */
  def split(sql: String): List[String] =
    val view = SqlLexer.engineView(sql)
    val out  = ListBuffer.empty[String]
    var from = 0
    SqlLexer.tokens(view).foreach { t =>
      if t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == ';' then
        out += view.substring(from, t.start)
        from = t.end
    }
    out += view.substring(from)
    out.toList.filter(_.trim.nonEmpty)

  /** A statement behind EXPLAIN, at ANY distance: EXPLAIN takes ANALYZE / ANALYSE and an option
    * list whose options carry arbitrary expressions. For a screen that refuses a verb wherever it
    * follows EXPLAIN (the gateway's trigger screen did, until spec decision 29), accepting the
    * over-deny of a literal spelling it.
    */
  val ExplainPrefix = "(?s)^(?:explain\\b.*\\b)?"

  /** The screening text of `stmt`: leading trivia deleted, interior comments replaced by a space,
    * remaining trivia normalized, case folded LAST. The ORIGINAL statement is lexed first because
    * DuckDB compares dollar-quote tags case-sensitively (`$A$` is not closed by `$a$`): folding
    * first let the lexer end a literal where DuckDB does not and read the text behind it as a
    * comment.
    */
  def normalize(stmt: String): String =
    SqlTrivia
      .normalize(SqlCommentStripper.stripComments(SqlTrivia.stripLeading(stmt)))
      .toLowerCase(Locale.ROOT)
      .trim

  /** The non-trivia tokens of `stmt` read from DuckDB 2.0's own tokenizer, over `view` (the
    * statement's engine view, which the token offsets index), and the index of the first token of
    * the statement a wrapper carries: past a leading run of EXPLAIN / ANALYZE / ANALYSE keywords
    * and parenthesized option lists (balanced over paren TOKENS, so a paren inside a literal or a
    * comment never counts), and then past a `PREPARE <name> AS` head. 0 when the statement wraps
    * nothing; `toks.length` when nothing follows. `EXPLAIN ANALYZE <stmt>`,
    * `EXPLAIN (ANALYZE) <stmt>` and `EXPLAIN ANALYZE (FORMAT json) <stmt>` all EXECUTE the
    * statement on 2.0 (SET, PRAGMA, RESET, COPY and LOAD verified), and `PREPARE p AS <stmt>` +
    * `EXECUTE p` executes `<stmt>` (only SELECT / INSERT / UPDATE / DELETE / COPY are preparable on
    * 2.0, so `PREPARE p AS COPY (...) TO '<local>'` is the write carrier a first-token rule must
    * see through). The EXPLAIN run is wider than the grammar (any order, repeated) so an unusual
    * prefix only ever moves the head further in.
    */
  final case class Head(view: String, toks: Vector[SqlLexer.Token], start: Int):

    /** The lowercased text of the word token at `k`, None for any other token or past the end. */
    def word(k: Int): Option[String] =
      if k < toks.length && toks(k).kind == SqlLexer.Kind.Word then
        Some(view.substring(toks(k).start, toks(k).end).toLowerCase(Locale.ROOT))
      else None

    /** The unquoted, lowercased identifier at `k` (bare, double-quoted or backtick-quoted). */
    def identifier(k: Int): Option[String] =
      if k < toks.length then FunctionCalls.identifier(view, toks(k)) else None

    /** The engine view of the carried statement, from its first token (empty when none). */
    def text: String = if start < toks.length then view.substring(toks(start).start) else ""

  def of(stmt: String): Head =
    val view         = SqlLexer.engineView(Option(stmt).getOrElse(""))
    val toks         = SqlLexer.tokens(view).filterNot(_.isTrivia)
    val h            = Head(view, toks, 0)
    val afterExplain =
      if !h.word(0).contains("explain") then 0
      else
        var i = 0
        while i < toks.length && (h.word(i).exists(ExplainWords) || isPunct(view, toks(i), '(')) do
          if isPunct(view, toks(i), '(') then i = closingParen(view, toks, i) + 1
          else i += 1
        i
    Head(view, toks, math.min(skipPrepareHead(h, afterExplain), toks.length))

  /** The one reading of a setting statement every screen shares: for SET [SESSION | GLOBAL | LOCAL]
    * <name>, RESET [scope] <name> and PRAGMA <name> (assignment and call form), the lowercased,
    * unquoted name and the index of its token in `h.toks`, so a caller can read the value that
    * follows. Read from DuckDB 2.0's tokens: glued quotes, comments between tokens, the statement
    * an EXPLAIN / PREPARE carries. A scope word followed by a name is a scope.
    */
  def settingAt(h: Head): Option[(String, Int)] =
    val i = h.start
    h.word(i) match
      case Some("set" | "reset" | "pragma") =>
        val target =
          if h.word(i + 1).exists(Scopes) && h.identifier(i + 2).isDefined then i + 2 else i + 1
        h.identifier(target).map(_ -> target)
      case _ => None

  private val Scopes = Set("session", "global", "local")

  /** Past a `PREPARE <name> AS` head at `from`, to the first token of the prepared statement;
    * `from` unchanged when there is no such head or no `AS` follows the name. DuckDB 2.0 has no
    * parameter-type list form (`PREPARE p(INT) AS ...` is "TypeList ... not implemented"), so only
    * the bare `PREPARE <name> AS` shape is skipped.
    *
    * The name is any one word or literal token: DuckDB's grammar takes `ColIdOrString`, so `'p'`,
    * `E'p'`, `$$p$$` and `$t$p$t$` name a statement as `p` and `"p"` do (each prepares and runs a
    * COPY on 2.0). Accepting identifiers only left the screens judging the wrapper word `prepare`,
    * and a prepared COPY ... TO a path or bucket was admitted (audit F24).
    */
  private def skipPrepareHead(h: Head, from: Int): Int =
    val named = from + 1 < h.toks.length && {
      val k = h.toks(from + 1).kind
      k == SqlLexer.Kind.Word || k == SqlLexer.Kind.Literal
    }
    if h.word(from).contains("prepare") && named && h.word(from + 2).contains("as") then from + 3
    else from

  private val ExplainWords = Set("explain", "analyze", "analyse")

  private def isPunct(view: String, t: SqlLexer.Token, c: Char): Boolean =
    t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == c

  /** Index of the paren token closing the one at `open`, or the last index when unbalanced. */
  private def closingParen(view: String, toks: Vector[SqlLexer.Token], open: Int): Int =
    var depth = 0
    var i     = open
    var close = -1
    while i < toks.length && close < 0 do
      if isPunct(view, toks(i), '(') then depth += 1
      else if isPunct(view, toks(i), ')') then
        depth -= 1
        if depth == 0 then close = i
      i += 1
    if close < 0 then toks.length - 1 else close
