package ai.starlake.sql

/** Removes single-line (`--`) and multi-line (`/* ... */`) SQL comments from a query string.
  * Single-quoted string literals are preserved intact, including escaped quotes (`''`), so a
  * literal like `'-- not a comment'` is not stripped.
  *
  * Mirrors the implementation from the Starlake codebase (`ai.starlake.sql.SqlCommentStripper`) so
  * SQL-handling utilities stay consistent across projects.
  *
  * A comment is a SEPARATOR to DuckDB's own parser, not a weld: `INSERT/*x*/INTO t VALUES (1)`
  * still runs and writes the row (verified against DuckDB 1.5.4), because DuckDB treats the comment
  * as whitespace between the two keywords, not as if it had never been there. A block comment is
  * therefore replaced with a single ASCII space rather than deleted outright, so `INSERT/*x*/INTO`
  * strips to `INSERT INTO`, not the welded `INSERTINTO` that matches no classifier keyword bucket
  * and defeats `RoleMatcher`/`ProtectedWriteGuard`/audit stamping the same way a hidden Unicode
  * trivia character does (see `SqlTrivia`). A line (`--`) comment already appends its own
  * terminating newline (or, unterminated, runs to the end of the statement with nothing left to
  * weld to), so it needs no equivalent change.
  */
object SqlCommentStripper:

  def stripComments(sql: String): String =
    if sql == null || sql.isEmpty then sql
    else
      val result = new StringBuilder()
      val length = sql.length
      var i      = 0

      var inQuote        = false
      var inLineComment  = false
      var inBlockComment = false

      while i < length do
        val currentChar = sql.charAt(i)

        if inBlockComment then
          if currentChar == '*' && i + 1 < length && sql.charAt(i + 1) == '/' then
            inBlockComment = false
            // A closed block comment becomes a single ASCII space, not nothing: DuckDB treats
            // the comment as a separator, so the two tokens it sat between must not weld
            // together (`INSERT/*x*/INTO` -> `INSERT INTO`, not `INSERTINTO`). Harmless when the
            // comment already sat next to real whitespace; load-bearing when it didn't.
            result.append(' ')
            i += 2
          else i += 1
        else if inLineComment then
          if currentChar == '\n' || currentChar == '\r' then
            inLineComment = false
            result.append(currentChar)
          i += 1
        else if inQuote then
          result.append(currentChar)
          if currentChar == '\'' then
            if i + 1 < length && sql.charAt(i + 1) == '\'' then
              result.append('\'')
              i += 2
            else
              inQuote = false
              i += 1
          else i += 1
        else if currentChar == '/' && i + 1 < length && sql.charAt(i + 1) == '*' then
          inBlockComment = true
          i += 2
        else if currentChar == '-' && i + 1 < length && sql.charAt(i + 1) == '-' then
          inLineComment = true
          i += 2
        else if currentChar == '\'' then
          inQuote = true
          result.append(currentChar)
          i += 1
        else
          result.append(currentChar)
          i += 1

      removeEmptyLines(result.toString())

  def removeEmptyLines(input: String): String =
    val res = input.linesIterator.filter(_.trim.nonEmpty).mkString("\n")
    if res.trim.endsWith(";") then res.trim.dropRight(1) else res
