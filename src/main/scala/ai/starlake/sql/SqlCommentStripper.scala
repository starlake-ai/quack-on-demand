package ai.starlake.sql

/** Removes single-line (`--`) and multi-line (`/* ... */`) SQL comments from a query string,
  * mirroring DuckDB's own lexer rather than approximating it.
  *
  * Mirrors the implementation from the Starlake codebase (`ai.starlake.sql.SqlCommentStripper`) so
  * SQL-handling utilities stay consistent across projects; the lexer fidelity below is this
  * project's own, driven by what a real DuckDB 1.5.4 accepts.
  *
  * Three properties, each verified by execution against DuckDB 1.5.4 rather than assumed:
  *
  *   1. A comment is a SEPARATOR, not a weld. `INSERT/*x*/INTO t VALUES (1)` runs and writes the
  *      row, because DuckDB treats the comment as whitespace between the two keywords rather than
  *      as if it had never been there. A closed block comment is therefore replaced by a single
  *      ASCII space, so `INSERT/*x*/INTO` strips to `INSERT INTO` and not to the welded
  *      `INSERTINTO` that matches no classifier keyword bucket. A line comment contributes its own
  *      terminator (or, unterminated, runs to end of input with nothing left to weld to).
  *   2. Block comments NEST. `/* a /* b */ c */ INSERT INTO t VALUES (1)` executes, and so does
  *      three levels deep. A scanner that closes on the first closing marker it sees leaves the
  *      text between the inner and the outer close in the stream, which is not merely "less
  *      stripped": it puts a non-keyword where the next keyword should be (`EXPLAIN /* a /* b */ c
  *      */ ANALYZE INSERT ...` reads as plain EXPLAIN, and DuckDB writes the row) and it breaks the
  *      keyword-argument adjacency the lockdown screen's regexes key on (`FROM/* a /* b */ c
  *      */'/etc/passwd.parquet'` reads the file). So the block arm tracks depth.
  *   3. A comment opener inside a QUOTED region is not a comment. DuckDB has four quoting forms,
  *      all of which accept a block-comment opener and `--` verbatim: plain strings `'...'` (`''`
  *      doubles, a backslash is an ordinary character), escape strings `e'...'` / `E'...'` (`''`
  *      doubles AND a backslash escapes whatever follows it, the closing quote included),
  *      double-quoted identifiers `"..."` (`""` doubles) and dollar-quoted strings `$$...$$` /
  *      `$tag$...$tag$`. Tracking only the first of the four -- which this scanner did until the
  *      quoting forms were probed one at a time against the engine -- lets a block-comment opener
  *      in a column alias open a comment that never closes and swallows the rest of the statement.
  *      A SELECT whose alias is the four characters x, slash, star, y, reading /etc/passwd.csv
  *      through read_csv, executes on DuckDB and reached every downstream screen as
  *      `select 1 as "x` (the alias is spelled out rather than written inline because an unbalanced
  *      opener would end this scaladoc; `SqlCommentStripperSpec` carries it verbatim as a string
  *      literal).
  *
  * The result is for CLASSIFICATION and SCREENING. `PrepareStrategy` is the one caller that sends
  * derived text back to the engine, and every property above moves that path closer to the original
  * statement, never further from it: comments are what DuckDB itself discards, and quoted regions
  * are now copied through byte for byte instead of being truncated at a quoted comment marker.
  */
object SqlCommentStripper:

  def stripComments(sql: String): String =
    if sql == null || sql.isEmpty then sql
    else
      val result = new StringBuilder()
      val length = sql.length
      var i      = 0

      while i < length do
        val c = sql.charAt(i)
        if c == '/' && i + 1 < length && sql.charAt(i + 1) == '*' then
          i = blockCommentEnd(sql, i)
          // A closed block comment becomes a single ASCII space, not nothing: DuckDB treats the
          // comment as a separator, so the two tokens it sat between must not weld together
          // (`INSERT/*x*/INTO` -> `INSERT INTO`, not `INSERTINTO`). Harmless when the comment
          // already sat next to real whitespace; load-bearing when it didn't.
          result.append(' ')
        else if c == '-' && i + 1 < length && sql.charAt(i + 1) == '-' then
          i = lineCommentEnd(sql, i)
          // The terminator itself is real whitespace and is kept, so it separates the two lines.
          if i < length then
            result.append(sql.charAt(i))
            i += 1
        else if c == '\'' then
          val end = singleQuotedEnd(sql, i, isEscapeStringOpener(sql, i))
          result.append(sql.substring(i, end))
          i = end
        else if c == '"' then
          val end = doubleQuotedEnd(sql, i)
          result.append(sql.substring(i, end))
          i = end
        else
          // `$` opens a dollar-quoted string only when a well-formed tag and its matching close
          // are both there; otherwise it is an ordinary character (a positional parameter, say).
          val dollar = if c == '$' then dollarQuotedEnd(sql, i) else None
          dollar match
            case Some(end) =>
              result.append(sql.substring(i, end))
              i = end
            case None =>
              result.append(c)
              i += 1

      removeEmptyLines(result.toString())

  /** Index just past the marker closing the block comment that opens at `from`, tracking nesting
    * depth the way DuckDB's lexer does. An unterminated comment consumes the rest of the input --
    * DuckDB rejects such a statement outright, so nothing executable is being hidden.
    */
  private def blockCommentEnd(sql: String, from: Int): Int =
    var i     = from + 2
    var depth = 1
    while i < sql.length && depth > 0 do
      if i + 1 < sql.length && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*' then
        depth += 1
        i += 2
      else if i + 1 < sql.length && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/' then
        depth -= 1
        i += 2
      else i += 1
    i

  /** Index OF the terminator of the line comment opening at `from`, or `sql.length` when it is
    * unterminated. DuckDB ends a `--` comment at a line feed OR at a bare carriage return, and at
    * neither of the other line-ish characters (vertical tab, form feed, U+0085, U+2028, U+2029 all
    * leave the comment open) -- each of the seven probed individually against DuckDB 1.5.4.
    */
  private def lineCommentEnd(sql: String, from: Int): Int =
    var i = from + 2
    while i < sql.length && sql.charAt(i) != '\n' && sql.charAt(i) != '\r' do i += 1
    i

  /** True when the `'` at `at` opens an escape string (`e'...'` / `E'...'`) rather than a plain
    * one. The `e` must be a token of its own and must touch the quote: DuckDB reads `ze'a\'b'` as
    * the identifier `ze` followed by a PLAIN string, and `e 'a\'b'` as the same, so a preceding
    * letter, digit or underscore disqualifies it and so does any gap.
    */
  private def isEscapeStringOpener(sql: String, at: Int): Boolean =
    at > 0 && (sql.charAt(at - 1) == 'e' || sql.charAt(at - 1) == 'E') &&
      (at == 1 || !isIdentChar(sql.charAt(at - 2)))

  private def isIdentChar(c: Char): Boolean = c.isLetterOrDigit || c == '_'

  /** Index just past the quote closing the single-quoted literal that opens at `from`. `''` is an
    * embedded quote in both forms. In an escape string a backslash additionally escapes whatever
    * follows it, so `e'a\''` is the two-character `a'` and `e'a\\'` is `a\` with the quote closing;
    * in a plain string a backslash is an ordinary character, so `'a\'` is the complete string `a\`.
    */
  private def singleQuotedEnd(sql: String, from: Int, escape: Boolean): Int =
    var i      = from + 1
    var closed = false
    while i < sql.length && !closed do
      val c = sql.charAt(i)
      if escape && c == '\\' && i + 1 < sql.length then i += 2
      else if c == '\'' then
        if i + 1 < sql.length && sql.charAt(i + 1) == '\'' then i += 2
        else
          closed = true
          i += 1
      else i += 1
    i

  /** Index just past the quote closing the double-quoted identifier that opens at `from`, with `""`
    * doubling. An unterminated identifier runs to the end of the input, which copies the remainder
    * through verbatim -- the safe direction for a screening caller, and a statement DuckDB rejects.
    */
  private def doubleQuotedEnd(sql: String, from: Int): Int =
    var i      = from + 1
    var closed = false
    while i < sql.length && !closed do
      if sql.charAt(i) == '"' then
        if i + 1 < sql.length && sql.charAt(i + 1) == '"' then i += 2
        else
          closed = true
          i += 1
      else i += 1
    i

  /** Index just past the closing tag of the dollar-quoted string opening at `from`, or None when
    * the `$` there opens no such string. The tag follows unquoted-identifier rules and cannot start
    * with a digit, which is what keeps a positional parameter out: DuckDB reads `$1$x$1$` as the
    * parameter `$1` followed by an unterminated `$x$` string, not as `$1$...$1$`. An opener with no
    * matching close is rejected too rather than swallowing the remainder -- DuckDB rejects that
    * statement outright, and leaving the text visible is the safe direction for a screening caller.
    */
  private def dollarQuotedEnd(sql: String, from: Int): Option[Int] =
    var t = from + 1
    while t < sql.length && isTagChar(sql.charAt(t), t == from + 1) do t += 1
    if t >= sql.length || sql.charAt(t) != '$' then None
    else
      val tag   = sql.substring(from, t + 1)
      val close = sql.indexOf(tag, t + 1)
      if close < 0 then None else Some(close + tag.length)

  private def isTagChar(c: Char, first: Boolean): Boolean =
    if first then c.isLetter || c == '_' else c.isLetterOrDigit || c == '_'

  def removeEmptyLines(input: String): String =
    val res = input.linesIterator.filter(_.trim.nonEmpty).mkString("\n")
    if res.trim.endsWith(";") then res.trim.dropRight(1) else res
