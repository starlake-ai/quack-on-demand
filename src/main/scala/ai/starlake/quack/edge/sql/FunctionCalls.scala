package ai.starlake.quack.edge.sql

import ai.starlake.sql.SqlLexer

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

import scala.collection.mutable

/** Function-call sites read from DuckDB 2.0's own tokens ([[ai.starlake.sql.SqlLexer]]), the one
  * reader shared by every screen that asks "does this statement call f?".
  *
  * A call is an identifier token immediately followed, past any trivia (whitespace, line and nested
  * block comments), by an opening parenthesis token. The identifier is bare, double-quoted or
  * backtick-quoted (DuckDB 2.0 accepts all three for a function name, and resolves each case
  * insensitively: `` `READ_TEXT`('/etc/hosts') `` reads the file on the 2.0 CLI), and may be the
  * last part of a qualified name (`` `system`.`main`.`read_text` ``), whose qualifiers do not
  * change which function runs. Its name is unquoted (the delimiter undoubled) and folded to lower
  * case AFTER lexing: DuckDB compares dollar-quote tags case-sensitively, so folding first could
  * end a literal where DuckDB does not.
  *
  * A regex over the text cannot do this: each quoting form it does not spell out is a bypass, and
  * the backtick form arrived with 2.0. Tokens also stop a name inside a comment or a quoted
  * identifier from reading as a call, and a call from hiding behind a comment before its paren.
  *
  * String literal BODIES are scanned too, as SQL in their own right, because `query('...')` runs
  * its argument: `query('SELECT * FROM `read_text`(''/etc/hosts'')')` reads the file. Each body is
  * decoded the way DuckDB decodes it (`''` undoubled; an escape string's backslash escapes,
  * including `\x72` and octal bytes; a dollar-quoted body verbatim) from the characters DuckDB's
  * unicode-space pre-pass kept, never the engine view's stand-ins, and lexed afresh: the fresh lex
  * pre-passes the body again, as DuckDB's parser does when `query()` runs it, so
  * `query('SELECT * FROM<NBSP>read_text(...)')` is a call. This is the over-deny the previous regex
  * had by accident (it never blanked literals), kept on purpose; it is NOT a proof that `query()`
  * is safe, since a string computed at run time (`'read' || '_text(...)'`) carries a call no
  * lexical scan sees. Nesting is followed [[MaxDepth]] levels deep, which bounds the work at
  * `MaxDepth` passes over the input; past it the scan reports `truncated`.
  *
  * DuckDB folds a plain `'...'` literal that follows another literal across a newline (no block
  * comment in between) into ONE value: `'__duck'<LF>'lake_metadata_x'` is
  * `'__ducklake_metadata_x'`. A text holding such a continuation is therefore also scanned in its
  * [[ai.starlake.sql.ParserInput]] rendering (the 2.0 lexer's reading, every continuation folded,
  * every literal one plain literal of its value), so the folded values reach `texts`, their calls
  * reach `calls` and their bodies are scanned too. The parts stay scanned on their own as before;
  * every consumer only refuses on what it finds, so the extra reading only adds refusals.
  */
private[quack] object FunctionCalls:

  /** One call: `name` unquoted and lowercased; `text` the text it was found in (the engine view of
    * the statement, or of a decoded literal body); `argsFrom` the index in `text` just past `(`.
    */
  final case class Call(name: String, text: String, argsFrom: Int)

  /** Every call found, top level first; `truncated` when a string literal nested deeper than
    * [[MaxDepth]] was left unscanned. `texts` holds every text scanned: the engine view of the
    * statement, then of each decoded literal body, for a screen that looks for a name anywhere.
    */
  final case class Scan(
      calls: Vector[Call],
      truncated: Boolean,
      texts: Vector[String] = Vector.empty
  )

  val MaxDepth = 8

  def scan(sql: String): Scan =
    val calls     = Vector.newBuilder[Call]
    val texts     = Vector.newBuilder[String]
    val pending   = mutable.Queue((Option(sql).getOrElse(""), 0))
    val seen      = mutable.HashSet.empty[String]
    var truncated = false
    while pending.nonEmpty do
      val (text, depth) = pending.dequeue()
      if seen.add(text) then
        val view = SqlLexer.engineView(text)
        texts += view
        // literal values are read from the characters DuckDB's pre-pass kept, not the stand-ins
        val values = SqlLexer.keptSpacesRestored(view)
        val all    = SqlLexer.tokens(view)
        val toks   = all.filterNot(_.isTrivia)
        var i      = 0
        while i < toks.length do
          val t = toks(i)
          if i + 1 < toks.length && isOpenParen(view, toks(i + 1)) then
            identifier(view, t).foreach(name => calls += Call(name, view, toks(i + 1).end))
          stringBody(values, t).foreach { body =>
            if depth < MaxDepth then pending.enqueue((body, depth + 1))
            else truncated = true
          }
          i += 1
        // The folded reading, at the same depth (a rendering never holds a continuation itself).
        if hasContinuation(view, all) then
          ai.starlake.sql.ParserInput.render(text).foreach(r => pending.enqueue((r.text, depth)))
    Scan(calls.result(), truncated, texts.result())

  /** True when some string literal in `all` (the statement's tokens, trivia included) is followed,
    * past trivia holding a newline and no block comment, by a plain `'...'` literal: DuckDB's
    * `IsStringLiteralContinuation`, the rule [[ai.starlake.sql.ParserInput]] folds by. A
    * dollar-quoted literal is never continued.
    */
  private[sql] def hasContinuation(view: String, all: Vector[SqlLexer.Token]): Boolean =
    def isString(t: SqlLexer.Token) =
      t.kind == SqlLexer.Kind.Literal && {
        val c = view.charAt(t.start)
        c != '"' && c != '`' && c != '$'
      }
    all.indices.exists { i =>
      isString(all(i)) && {
        var k       = i + 1
        var newline = false
        var block   = false
        while k < all.length && all(k).isTrivia do
          if all(k).kind == SqlLexer.Kind.BlockComment then block = true
          else if all(k).kind == SqlLexer.Kind.Space then
            newline = newline || view.substring(all(k).start, all(k).end).exists { c =>
              c == '\n' || c == '\r'
            }
          k += 1
        newline && !block && k < all.length && all(k).kind == SqlLexer.Kind.Literal &&
        view.charAt(all(k).start) == '\''
      }
    }

  /** The unquoted, lowercased name of an identifier token (bare word, double-quoted or
    * backtick-quoted), or None for any other token.
    */
  def identifier(view: String, t: SqlLexer.Token): Option[String] =
    quotedIdentifier(view, t).orElse(
      if t.kind == SqlLexer.Kind.Word then
        Some(view.substring(t.start, t.end).toLowerCase(Locale.ROOT))
      else None
    )

  /** The unquoted, lowercased body of a double-quoted or backtick-quoted identifier token. */
  def quotedIdentifier(view: String, t: SqlLexer.Token): Option[String] =
    val d = view.charAt(t.start)
    if t.kind == SqlLexer.Kind.Literal && (d == '"' || d == '`') then
      Some(undoubled(view, t.start + 1, t.end, d).toLowerCase(Locale.ROOT))
    else None

  /** True when every argument of `call` is a constant the screens can read: a literal, a number, a
    * `true` / `false` / `null` keyword, a list of those, or a named parameter (`name := value`,
    * `name => value`) with such a value. Anything else (a `||` concatenation, a cast, a nested
    * call, a column, a subquery) computes the value at run time, where no lexical screen sees it.
    */
  def constantArguments(call: Call): Boolean =
    val toks  = SqlLexer.tokens(call.text).filter(t => !t.isTrivia && t.start >= call.argsFrom)
    val text  = call.text
    var depth = 0
    var i     = 0
    var ok    = true
    var done  = false
    def punct(t: SqlLexer.Token, c: Char) =
      t.kind == SqlLexer.Kind.Punct && text.charAt(t.start) == c
    while i < toks.length && ok && !done do
      val t = toks(i)
      if punct(t, ')') && depth == 0 then done = true
      else if punct(t, '[') then depth += 1
      else if punct(t, ']') then depth -= 1
      else if punct(t, ',') then ()
      else if t.kind == SqlLexer.Kind.Number then ()
      else if t.kind == SqlLexer.Kind.Literal then
        // A quoted identifier is a column reference, not a constant.
        ok = quotedIdentifier(text, t).isEmpty
      else if t.kind == SqlLexer.Kind.Word then
        val w    = text.substring(t.start, t.end).toLowerCase(Locale.ROOT)
        val next = toks.lift(i + 1).map(n => text.substring(n.start, n.end))
        if next.exists(n => n == ":=" || n == "=>") then i += 1 // a named parameter
        else ok = ConstantWords(w)
      else if t.kind == SqlLexer.Kind.Operator && text.substring(t.start, t.end) == "-" then
        ok = toks.lift(i + 1).exists(_.kind == SqlLexer.Kind.Number)
      else ok = false
      i += 1
    ok && done

  private val ConstantWords = Set("true", "false", "null")

  /** The decoded value of `call`'s FIRST argument when that argument is exactly one VARCHAR string
    * literal (plain `'...'`, national `n'...'`, escape `e'...'` or dollar-quoted) followed by `,`
    * or `)`. None for anything else: a column, a quoted identifier, a blob or bit-string literal
    * (`x'...'`, `b'...'`, whose text is not the value a cast to VARCHAR would produce), a
    * concatenation, a cast, a nested call, a parameter, no argument at all, or a literal followed
    * by another literal (a continuation, whose folded value the [[scan]]'s folded reading reports
    * as its own call). A caller treating None as "cannot tell" therefore fails closed on every form
    * it cannot read.
    */
  def firstStringArgument(call: Call): Option[String] =
    val text   = call.text
    val values = SqlLexer.keptSpacesRestored(text)
    val toks   = SqlLexer.tokens(text).filter(t => !t.isTrivia && t.start >= call.argsFrom)
    def punct(t: SqlLexer.Token, c: Char) =
      t.kind == SqlLexer.Kind.Punct && text.charAt(t.start) == c
    def varcharLiteral(t: SqlLexer.Token) =
      t.kind == SqlLexer.Kind.Literal && "'$eEnN".indexOf(text.charAt(t.start).toInt) >= 0
    toks.headOption.filter(varcharLiteral).flatMap { first =>
      if toks.lift(1).exists(n => punct(n, ',') || punct(n, ')')) then stringBody(values, first)
      else None
    }

  /** The value of `t`, a token of `view`, when it is one VARCHAR string literal (plain `'...'`,
    * national `n'...'`, escape `e'...'` or dollar-quoted), decoded as DuckDB decodes it. None for
    * any other token: a quoted identifier, a blob or bit-string literal, a word, a parameter.
    */
  def varcharValue(view: String, t: SqlLexer.Token): Option[String] =
    if t.kind == SqlLexer.Kind.Literal && "'$eEnN".indexOf(view.charAt(t.start).toInt) >= 0 then
      stringBody(SqlLexer.keptSpacesRestored(view), t)
    else None

  /** The locations a file reader `call` names, when its argument list has the one shape the gateway
    * admits (Task 12, [[FileLocations]]): a first argument that is one VARCHAR string literal
    * ([[varcharValue]]) or a `[...]` list of them, then only named options (`name = v`,
    * `name := v`, `name => v`) whose values are constants ([[constantEnd]]). None for anything
    * else: a second positional argument, a column, a cast, a concatenation, a nested call, a
    * parameter, a blob literal, a literal continued on the next line (another literal follows it),
    * an empty list, an unterminated list. A caller treating None as "refuse" fails closed on every
    * form it cannot read.
    */
  def locationArguments(call: Call): Option[List[String]] =
    val text = call.text
    val toks = SqlLexer.tokens(text).filter(t => !t.isTrivia && t.start >= call.argsFrom)
    def punct(i: Int, c: Char): Boolean =
      i < toks.length && toks(i).kind == SqlLexer.Kind.Punct && text.charAt(toks(i).start) == c
    def value(i: Int): Option[String] =
      if i < toks.length then varcharValue(text, toks(i)) else None
    // `[ literal (, literal)* ]` from the `[` at `from`: its values and the index past its `]`.
    def list(from: Int): Option[(List[String], Int)] =
      val values = List.newBuilder[String]
      var i      = from + 1
      var ok     = true
      var closed = false
      while ok && !closed && i < toks.length do
        value(i) match
          case Some(v) =>
            values += v
            if punct(i + 1, ',') then i += 2
            else if punct(i + 1, ']') then
              closed = true
              i += 2
            else ok = false
          case None => ok = false
      val found = values.result()
      Option.when(ok && closed && found.nonEmpty)((found, i))
    val first = if punct(0, '[') then list(0) else value(0).map(v => (List(v), 1))
    first.collect { case (locations, next) if namedConstants(text, toks, next) => locations }

  private val NamedOperators = Set("=", ":=", "=>")

  /** True when the tokens from `from` are `)`, or a run of `, name <op> <constant>` ended by `)`.
    */
  private def namedConstants(text: String, toks: Vector[SqlLexer.Token], from: Int): Boolean =
    def punct(i: Int, c: Char): Boolean =
      i < toks.length && toks(i).kind == SqlLexer.Kind.Punct && text.charAt(toks(i).start) == c
    def named(i: Int): Boolean =
      i + 2 < toks.length && toks(i + 1).kind == SqlLexer.Kind.Word &&
        toks(i + 2).kind == SqlLexer.Kind.Operator &&
        NamedOperators(text.substring(toks(i + 2).start, toks(i + 2).end))
    var i    = from
    var ok   = true
    var done = false
    while ok && !done do
      if punct(i, ')') then done = true
      else if punct(i, ',') && named(i) then
        constantEnd(text, toks, i + 3) match
          case Some(end) => i = end
          case None      => ok = false
      else ok = false
    ok

  /** The index of the `,` or `)` that ends the constant starting at `from`: a VARCHAR or other
    * value literal (never a quoted identifier), a number, `-` before a number, `true` / `false` /
    * `null`, or a `[...]` list or `{...}` struct of those, a struct field name being a word right
    * before `:`. None when anything else appears, or nothing does.
    */
  private def constantEnd(text: String, toks: Vector[SqlLexer.Token], from: Int): Option[Int] =
    def tokText(i: Int): String             = text.substring(toks(i).start, toks(i).end)
    def isPunct(i: Int, s: String): Boolean =
      i < toks.length && toks(i).kind == SqlLexer.Kind.Punct && tokText(i) == s
    var i     = from
    var depth = 0
    var ok    = true
    var end   = -1
    while ok && end < 0 && i < toks.length do
      val t = toks(i)
      if depth == 0 && i > from && (isPunct(i, ",") || isPunct(i, ")")) then end = i
      else if isPunct(i, "[") || isPunct(i, "{") then depth += 1
      else if isPunct(i, "]") || isPunct(i, "}") then
        ok = depth > 0
        depth -= 1
      else if isPunct(i, ",") || isPunct(i, ":") then ok = depth > 0 && i > from
      else if t.kind == SqlLexer.Kind.Number then ()
      else if t.kind == SqlLexer.Kind.Literal then ok = quotedIdentifier(text, t).isEmpty
      else if t.kind == SqlLexer.Kind.Word then
        ok =
          ConstantWords(tokText(i).toLowerCase(Locale.ROOT)) || (depth > 0 && isPunct(i + 1, ":"))
      else if t.kind == SqlLexer.Kind.Operator && tokText(i) == "-" then
        ok = toks.lift(i + 1).exists(_.kind == SqlLexer.Kind.Number)
      else ok = false
      i += 1
    Option.when(ok && end > from)(end)

  private def isOpenParen(view: String, t: SqlLexer.Token): Boolean =
    t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == '('

  /** The decoded body of a string literal token: `'...'` and the `n` / `x` / `b` prefixed forms
    * with `''` undoubled, an escape string `e'...'` decoded, a dollar-quoted body verbatim. None
    * for a quoted identifier or any non-literal token.
    *
    * `values` is the token's text with the pre-pass's kept unicode spaces restored
    * ([[ai.starlake.sql.SqlLexer.keptSpacesRestored]]), position for position the lexed view: a
    * body is the literal's VALUE, which holds each kept space as itself, so that the body's own
    * lex, which pre-passes it afresh as DuckDB does when `query()` runs it, turns the ones outside
    * the body's literals into spaces. Every literal form, the escape string included, reads its
    * body from it.
    */
  private def stringBody(values: String, t: SqlLexer.Token): Option[String] =
    if t.kind != SqlLexer.Kind.Literal then None
    else
      val c = values.charAt(t.start)
      if c == '\'' then Some(undoubled(values, t.start + 1, t.end, '\''))
      else if c == '$' then
        val tagEnd = values.indexOf('$', t.start + 1) + 1
        Some(values.substring(tagEnd, math.max(tagEnd, t.end - (tagEnd - t.start))))
      else if c == 'e' || c == 'E' then Some(escapeDecoded(values, t.start + 2, t.end))
      else if c == '"' || c == '`' then None
      else Some(undoubled(values, t.start + 2, t.end, '\''))

  /** `s[from, end)` up to the closing delimiter `d`, a doubled `d` standing for one. */
  private def undoubled(s: String, from: Int, end: Int, d: Char): String =
    val out    = new StringBuilder
    var i      = from
    var closed = false
    while i < end && !closed do
      val c = s.charAt(i)
      if c == d then
        if i + 1 < end && s.charAt(i + 1) == d then
          out.append(d)
          i += 2
        else closed = true
      else
        out.append(c)
        i += 1
    out.toString

  /** An escape string's body as DuckDB 2.0 decodes it (each rule checked on the 2.0 CLI): `''` is a
    * quote; `\b \f \n \r \t` the usual controls; `\x` with one or two hex digits a byte (with none,
    * an `x`); one to three octal digits a byte; a backslash before anything else that character.
    * The bytes are UTF-8.
    */
  private def escapeDecoded(s: String, from: Int, end: Int): String =
    val bytes         = new ByteArrayOutputStream
    val run           = new StringBuilder
    def flush(): Unit =
      val b = run.toString.getBytes(StandardCharsets.UTF_8)
      bytes.write(b, 0, b.length)
      run.clear()
    def byte(v: Int): Unit =
      flush()
      bytes.write(v & 0xff)
    def isHex(c: Char)   = Character.digit(c, 16) >= 0
    def isOctal(c: Char) = c >= '0' && c <= '7'
    var i                = from
    var closed           = false
    while i < end && !closed do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < end then
        val e = s.charAt(i + 1)
        i += 2
        e match
          case 'b'                                  => byte(8)
          case 'f'                                  => byte(12)
          case 'n'                                  => byte(10)
          case 'r'                                  => byte(13)
          case 't'                                  => byte(9)
          case 'x' if i < end && isHex(s.charAt(i)) =>
            var v = Character.digit(s.charAt(i), 16)
            i += 1
            if i < end && isHex(s.charAt(i)) then
              v = v * 16 + Character.digit(s.charAt(i), 16)
              i += 1
            byte(v)
          case o if isOctal(o) =>
            var v = o - '0'
            var k = 0
            while k < 2 && i < end && isOctal(s.charAt(i)) do
              v = v * 8 + (s.charAt(i) - '0')
              i += 1
              k += 1
            byte(v)
          case other => run.append(other)
      else if c == '\'' then
        if i + 1 < end && s.charAt(i + 1) == '\'' then
          run.append('\'')
          i += 2
        else closed = true
      else
        run.append(c)
        i += 1
    flush()
    new String(bytes.toByteArray, StandardCharsets.UTF_8)
