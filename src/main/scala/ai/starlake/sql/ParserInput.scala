package ai.starlake.sql

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

import SqlLexer.{Kind, Token}

/** The text JSqlParser reads, built from [[SqlLexer]] tokens so that JSqlParser and DuckDB agree on
  * where every token starts and ends.
  *
  * JSqlParser has its own lexer, and it disagrees with DuckDB's on exactly the constructs that
  * decide what is code: it does not know DuckDB's escape strings (`E'\''` is one complete literal
  * to DuckDB, an unterminated one followed by code to JSqlParser), it reads `$tag$...$tag$` and
  * `$$` on its own rules, it treats `//` (DuckDB's integer division) as a line comment and
  * `q'{...}'` as an Oracle quote. Handed the raw statement, the ACL parser can therefore see a
  * string literal or a comment where DuckDB runs a subquery, and a table it never sees is a table
  * no grant gates. So every consumer that parses SQL for an access decision parses [[render]]'s
  * output instead:
  *
  *   - comments become a single space (DuckDB treats them as separators);
  *   - every string literal (plain, escape, national, hex, bit, dollar-quoted, with DuckDB's
  *     newline-separated continuations folded in) becomes one plain `'...'` literal of the SAME
  *     value, `''` doubling its quotes (a national, hex or bit literal keeps its one-letter
  *     prefix), set off by spaces so no preceding word can turn into a quote prefix;
  *   - a word that is not plain ASCII letters, digits and `_` (it holds `$`, a control character or
  *     non-ASCII) becomes a double-quoted identifier;
  *   - an operator holding `//` has it collapsed to `/` (the one change that alters the statement,
  *     see [[Rendered.exact]]);
  *   - a backtick-quoted identifier holding no double quote becomes the double-quoted identifier
  *     DuckDB 2.0 reads it as (`` `a``b` `` is `"a`b"`, the same name: [[QuotedIdentifier]]). One
  *     holding a double quote keeps its backticks: JSqlParser does not undouble `""`, so `"a""b"`
  *     would reach the ACL as `a""b`, a name DuckDB never reads, while it reads `` `a"b` `` as
  *     `a"b`; the policy matchers unquote both delimiters anyway. JSqlParser's own
  *     `getUnquotedName` strips backticks, so the ACL authorized `` FROM `orders` `` as `orders`,
  *     while every policy match unquoted double quotes only: a backtick-quoted table or column met
  *     no row filter and no column mask. Rendered once here, the gate, the policy rewriters and the
  *     protected-write guard read the one spelling, and a rewrite printed from it is still the
  *     caller's statement on the pinned engine (DuckDB 2.0, where a backtick quotes an identifier);
  *   - double-quoted identifiers keep their quoting, everything else is copied as is.
  *
  * JSqlParser then never extends a literal past the point where DuckDB closes it: every quote it
  * meets inside one is doubled, and its backslash handling only ever ends a literal EARLIER (into
  * adjacent literals, a parse failure or an alias), never later.
  *
  * Input DuckDB rejects outright (an unterminated quote or block comment, an escape string decoding
  * to a NUL or to invalid UTF-8, a lone `$$`) is refused with a reason rather than guessed at.
  */
private[starlake] object ParserInput:

  /** `text` is what JSqlParser reads. `exact` is true when running `text` on DuckDB is the same
    * statement as running the input: the rewriters that send their output to the node must refuse
    * an inexact rendering rather than ship it.
    */
  final case class Rendered(text: String, exact: Boolean)

  /** The parser input for `sql`, time-travel clauses kept (JSqlParser then refuses them). */
  def render(sql: String): Either[String, Rendered] = renderWith(sql, stripTimeTravel = false)

  /** [[render]] with every DuckLake time-travel clause removed (see [[stripTimeTravel]]): the input
    * of the ACL parse and of every parse that must read a statement exactly as the ACL does. A
    * removed clause makes the rendering inexact.
    */
  def renderWithoutTimeTravel(sql: String): Either[String, Rendered] =
    renderWith(sql, stripTimeTravel = true)

  private def renderWith(sql: String, stripTimeTravel: Boolean): Either[String, Rendered] =
    if sql == null then Right(Rendered(sql, exact = true))
    else
      val view = SqlLexer.engineView(sql)
      // Token text is read from the pre-pass output: a unicode space DuckDB converted is a space,
      // one it kept is itself (in a value, as DuckDB reads it; in a word, as part of the word).
      val text = SqlLexer.keptSpacesRestored(view)
      new Renderer(text, view, SqlLexer.tokens(sql), stripTimeTravel).run()

  /** `sql` with every DuckLake time-travel clause (`AT (VERSION => n)` / `AT (TIMESTAMP => expr)`)
    * removed and everything else copied verbatim. JSqlParser's grammar has no such clause, and for
    * an access decision a time-travel read of a table is a read of that table. The clause is found
    * on DuckDB's own tokens, never on characters: a word `at` (not quoted), then past any trivia a
    * `(`, the word `version` or `timestamp` and an operator starting with `=>`; the group ends at
    * the `(` token's balancing `)` token. So a clause shape inside a string literal, a quoted
    * identifier or a comment is not a clause, and a parenthesis inside one does not count; an
    * unbalanced group runs to the end of the input (DuckDB rejects it).
    */
  def stripTimeTravel(sql: String): String =
    if sql == null then sql
    else
      val view = SqlLexer.engineView(sql)
      val toks = SqlLexer.tokens(sql)
      val out  = new StringBuilder
      var i    = 0
      while i < toks.length do
        timeTravelEnd(view, toks, i) match
          case Some(next) => i = next
          case None       =>
            out.append(sql.substring(toks(i).start, toks(i).end))
            i += 1
      out.toString

  /** When token `i` opens a time-travel clause, the index of the first token after it. */
  private def timeTravelEnd(view: String, toks: Vector[Token], i: Int): Option[Int] =
    def slice(k: Int): String = view.substring(toks(k).start, toks(k).end)
    def next(from: Int): Int  =
      var k = from
      while k < toks.length && toks(k).isTrivia do k += 1
      k
    def isWord(k: Int, words: String*): Boolean =
      k < toks.length && toks(k).kind == Kind.Word && words.exists(slice(k).equalsIgnoreCase)
    def isPunct(k: Int, p: String): Boolean =
      k < toks.length && toks(k).kind == Kind.Punct && slice(k) == p
    if !isWord(i, "at") then None
    else
      val open     = next(i + 1)
      val word     = next(open + 1)
      val arrow    = next(word + 1)
      val isClause = isPunct(open, "(") && isWord(word, "version", "timestamp") &&
        arrow < toks.length && toks(arrow).kind == Kind.Operator && slice(arrow).startsWith("=>")
      if !isClause then None
      else
        var depth = 0
        var k     = open
        var end   = toks.length
        while k < toks.length && end == toks.length do
          if isPunct(k, "(") then depth += 1
          else if isPunct(k, ")") then
            depth -= 1
            if depth == 0 then end = k + 1
          k += 1
        Some(end)

  private final class Renderer(
      text: String,
      view: String,
      toks: Vector[Token],
      stripTimeTravel: Boolean
  ):
    private val out                   = new StringBuilder
    private val pending               = new StringBuilder
    private var exact                 = true
    private var error: Option[String] = None
    // The previous significant token was a string literal.
    private var afterString = false

    def run(): Either[String, Rendered] =
      var i = 0
      while i < toks.length && error.isEmpty do
        val t = toks(i)
        if t.isTrivia then
          if SqlLexer.unterminated(view, t) then error = Some("unterminated block comment")
          else if t.kind == Kind.Space then pending.append(slice(t))
          else pending.append(' ')
          i += 1
        else if isString(t) then i = string(i)
        else
          val clauseEnd = if stripTimeTravel then timeTravelEnd(view, toks, i) else None
          clauseEnd match
            case Some(next) =>
              // the clause is not the caller's statement: a rewrite printed from here would drop it
              exact = false
              pending.append(' ')
              i = next
            case None =>
              emit(token(t), string = false)
              i += 1
      out.append(pending)
      error match
        case Some(reason) => Left(reason)
        case None         => Right(Rendered(out.toString, exact))

    private def slice(t: Token): String = text.substring(t.start, t.end)

    /** Appends the trivia gathered since the previous significant token, then `s`. Between two
      * string literals the trivia is a single space: a newline there would make DuckDB fold the
      * second into the first, a continuation the input did not have (its own were folded already).
      * Before a string literal there is always at least one space, so no word can prefix it.
      */
    private def emit(s: String, string: Boolean): Unit =
      if string && afterString then out.append(' ')
      else
        out.append(pending)
        if string && pending.isEmpty && out.nonEmpty then out.append(' ')
      pending.clear()
      out.append(s)
      afterString = string

    private def token(t: Token): String =
      val s = slice(t)
      t.kind match
        case Kind.Literal =>
          // a quoted identifier: string literals were taken by `string`
          if SqlLexer.unterminated(view, t) then
            error = Some("unterminated quoted identifier")
            s
          else if s.charAt(0) == '`' && !s.contains('"') then
            // the same identifier: a backtick quotes one on the pinned engine (DuckDB 2.0)
            QuotedIdentifier.doubleQuoted(QuotedIdentifier.unquote(s))
          else s
        case Kind.Word if !s.forall(isPlainWordChar) => QuotedIdentifier.doubleQuoted(s)
        case Kind.Operator if s.contains("//")       =>
          exact = false
          s.replaceAll("/{2,}", "/")
        case Kind.Punct if s == "$" && t.end < text.length && text.charAt(t.end) == '$' =>
          error = Some("unterminated dollar-quoted string")
          s
        case _ => s

    private def isPlainWordChar(c: Char): Boolean =
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'

    /** A string-literal token (not a quoted identifier). */
    private def isString(t: Token): Boolean =
      t.kind == Kind.Literal && {
        val c = text.charAt(t.start)
        c != '"' && c != '`'
      }

    /** Renders the string literal at token `i` with every continuation DuckDB folds into it, and
      * returns the index of the first token after them.
      */
    private def string(i: Int): Int =
      val t = toks(i)
      val s = slice(t)
      if s.charAt(0) == '$' then
        val tag = s.substring(0, s.indexOf('$', 1) + 1)
        emit(plain(s.substring(tag.length, s.length - tag.length)), string = true)
        i + 1
      else if SqlLexer.unterminated(view, t) then
        error = Some("unterminated string literal")
        i + 1
      else
        val prefix = if s.charAt(0) == '\'' then None else Some(s.charAt(0))
        val parts  = new StringBuilder(stripQuotes(s, if prefix.isEmpty then 1 else 2))
        var next   = i + 1
        var more   = true
        while more do
          continuation(next) match
            case Some(k) =>
              parts.append(stripQuotes(slice(toks(k)), 1))
              next = k + 1
            case None => more = false
        val body = parts.toString
        prefix match
          case None                  => emit(plain(body), string = true)
          case Some('e') | Some('E') =>
            decodeEscapes(body) match
              case Right(value) => emit(plain(value), string = true)
              case Left(reason) => error = Some(reason)
          case Some(p) => emit(p.toString + plain(body), string = true)
        next

    /** DuckDB's `IsStringLiteralContinuation`: the next significant token after `from` is a
      * terminated plain `'...'` literal, a newline sits between, and no block comment does.
      */
    private def continuation(from: Int): Option[Int] =
      var k       = from
      var newline = false
      var block   = false
      while k < toks.length && toks(k).isTrivia do
        val t = toks(k)
        if t.kind == Kind.BlockComment then block = true
        else if t.kind == Kind.Space then
          newline = newline || slice(t).exists(c => c == '\n' || c == '\r')
        k += 1
      if k < toks.length && newline && !block && toks(k).kind == Kind.Literal &&
        text.charAt(toks(k).start) == '\'' && !SqlLexer.unterminated(view, toks(k))
      then Some(k)
      else None

    /** DuckDB's `StripStringLiteral`: the body between the opening quote (after `prefixLen`
      * characters) and the closing one, every `''` replaced by `'` left to right.
      */
    private def stripQuotes(s: String, prefixLen: Int): String =
      s.substring(prefixLen, s.length - 1).replace("''", "'")

    private def plain(value: String): String = "'" + value.replace("'", "''") + "'"

  /** DuckDB's escape-string decoding (`StringLiteralParseResult::ToExpression`, ESCAPE_STRING),
    * over UTF-8 bytes as DuckDB does it: `\b \f \n \t \r \\ \'`, up to three octal digits and `\x`
    * with up to two hex digits each give one byte, any other escaped character stands for itself. A
    * NUL or invalid UTF-8 in the result is a parser error on DuckDB, so it is refused here too.
    */
  private[sql] def decodeEscapes(body: String): Either[String, String] =
    val bytes              = new ByteArrayOutputStream()
    def char(c: Int): Unit =
      val enc = new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8)
      bytes.write(enc, 0, enc.length)
    var invalid = false
    var i       = 0
    val n       = body.length
    // one code point starting at index k, written out; returns the index after it
    def codePointAt(k: Int): Int =
      val cp = body.codePointAt(k)
      if Character.isSurrogate(body.charAt(k)) && Character.charCount(cp) == 1 then invalid = true
      char(cp)
      k + Character.charCount(cp)
    def isOctal(c: Char) = c >= '0' && c <= '7'
    def isHex(c: Char)   = Character.digit(c, 16) >= 0 && c < 0x80
    while i < n do
      val c = body.charAt(i)
      if c == '\\' && i + 1 < n then
        val d = body.charAt(i + 1)
        d match
          case 'b'             => bytes.write(8); i += 2
          case 'f'             => bytes.write(12); i += 2
          case 'n'             => bytes.write(10); i += 2
          case 't'             => bytes.write(9); i += 2
          case 'r'             => bytes.write(13); i += 2
          case '\\'            => bytes.write('\\'); i += 2
          case '\''            => bytes.write('\''); i += 2
          case _ if isOctal(d) =>
            var e = i + 2
            while e < n && e - (i + 1) < 3 && isOctal(body.charAt(e)) do e += 1
            bytes.write(Integer.parseInt(body.substring(i + 1, e), 8) & 0xff)
            i = e
          case 'x' =>
            var e = i + 2
            while e < n && e - (i + 2) < 2 && isHex(body.charAt(e)) do e += 1
            if e > i + 2 then bytes.write(Integer.parseInt(body.substring(i + 2, e), 16))
            else bytes.write('x')
            i = e
          case _ => i = codePointAt(i + 1)
      else i = codePointAt(i)
    val raw = bytes.toByteArray
    if raw.contains(0.toByte) then Left("null character not permitted in escape string literal")
    else if invalid then Left("invalid UTF-8 in escape string literal")
    else
      val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      try Right(decoder.decode(ByteBuffer.wrap(raw)).toString)
      catch case _: CharacterCodingException => Left("invalid UTF-8 in escape string literal")
