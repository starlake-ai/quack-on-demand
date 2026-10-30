package ai.starlake.sql

/** Removes single-line (`--`) and multi-line (`/* ... */`) SQL comments from a query string, and
  * splits a submission into its top-level statements, mirroring DuckDB's own lexer rather than
  * approximating it.
  *
  * `stripComments` and `removeEmptyLines` keep the names of the Starlake codebase's
  * `ai.starlake.sql.SqlCommentStripper`, and `removeEmptyLines` mirrors its implementation, so
  * SQL-handling utilities stay consistent across projects. Comment and quote boundaries come from
  * `SqlLexer`, ported from the DuckDB 2.0 source.
  *
  * Three properties, each verified by execution rather than assumed, and each holding on DuckDB
  * 2.0. Properties 1 and 2 were first probed on DuckDB 1.5.4; property 3 is 2.0's (1.5 has four
  * quoting forms, 2.0 adds backtick identifiers):
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
  *      keyword-argument adjacency a keyword screen keys on (`FROM/* a /* b */ c
  *      */'/etc/passwd.parquet'` reads the file). So the block arm tracks depth.
  *   3. A comment opener inside a QUOTED region is not a comment. DuckDB 2.0 has five quoting
  *      forms, all of which accept a block-comment opener and `--` verbatim: plain strings `'...'`,
  *      escape strings `e'...'` / `E'...'` (a backslash escapes whatever follows it), double-quoted
  *      and backtick identifiers, and dollar-quoted strings `$$...$$` / `$tag$...$tag$`. Tracking
  *      only the first of them lets a block-comment opener in a column alias open a comment that
  *      never closes and swallows the rest of the statement. A SELECT whose alias is the four
  *      characters x, slash, star, y, reading /etc/passwd.csv through read_csv, executes on DuckDB
  *      and reached every downstream screen as `select 1 as "x` (the alias is spelled out rather
  *      than written inline because an unbalanced opener would end this scaladoc;
  *      `SqlCommentStripperSpec` carries it verbatim as a string literal). WHERE each quoted region
  *      opens and ends (which `$` and which `e` start one, which characters a dollar tag holds,
  *      DuckDB's unicode-space pre-pass) is `SqlLexer`'s job, ported from the DuckDB 2.0 source and
  *      shared with the statement splitter and the classifier.
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
      // Every boundary decision comes from `SqlLexer`, the one reader shared with
      // `statementSpans`, `StatementHead.split` and `StatementClassifier`, so this
      // stripper can never disagree with them (or with DuckDB 2.0) on where a literal or a comment
      // ends.
      SqlLexer.tokens(sql).foreach { t =>
        t.kind match
          // A closed block comment becomes a single ASCII space, not nothing: DuckDB treats the
          // comment as a separator, so the two tokens it sat between must not weld together
          // (`INSERT/*x*/INTO` -> `INSERT INTO`, not `INSERTINTO`). Harmless when the comment
          // already sat next to real whitespace; load-bearing when it didn't.
          case SqlLexer.Kind.BlockComment => result.append(' ')
          // The terminator is not part of the token: it follows as whitespace and is kept, so it
          // separates the two lines.
          case SqlLexer.Kind.LineComment => ()
          case _                         => result.append(sql.substring(t.start, t.end))
      }
      removeEmptyLines(result.toString())

  /** The input split into its top-level statements, at every `;` DuckDB's lexer would treat as a
    * separator and at no other. A `;` inside any quoting form or inside a (nested) comment never
    * splits, and a quote that sits inside another quoting form never opens a string that would
    * swallow a real separator: `SELECT $$'$$; USE x; SELECT 1` and `SELECT e'\''; USE x; SELECT 1`
    * are three statements on DuckDB, and a tracker that only knows plain `'...'` strings reads each
    * as one.
    *
    * Each fragment is the ORIGINAL text, comments and quoting intact. A fragment holding nothing
    * but whitespace and comments is dropped, since DuckDB runs nothing for it, so `USE x;` and
    * `USE x; -- done` are both one statement.
    */
  def splitStatements(sql: String): List[String] =
    statementSpans(sql).map((from, until) => sql.substring(from, until))

  /** The `[from, until)` offsets in `sql` of each fragment [[splitStatements]] returns, in order.
    * `until` stops before the separating `;`, so `sql.substring(from, until)` is the fragment, and
    * a caller can splice text into the ORIGINAL submission at a statement boundary instead of
    * rebuilding it from the fragments.
    *
    * Read from `SqlLexer.tokens`, which lexes DuckDB's `SqlLexer.engineView` of the input: the text
    * after the unicode-space pre-pass, which keeps every offset (one UTF-16 unit per char), so a
    * token's offsets index the original text too.
    */
  def statementSpans(sql: String): List[(Int, Int)] =
    if sql == null || sql.isEmpty then Nil
    else
      val out         = List.newBuilder[(Int, Int)]
      var start       = 0
      var substantive = false
      SqlLexer.tokens(sql).foreach { t =>
        if t.kind == SqlLexer.Kind.Punct && sql.charAt(t.start) == ';' then
          if substantive then out += ((start, t.start))
          start = t.end
          substantive = false
        else if !t.isTrivia then substantive = true
      }
      if substantive then out += ((start, sql.length))
      out.result()

  def removeEmptyLines(input: String): String =
    val res = input.linesIterator.filter(_.trim.nonEmpty).mkString("\n")
    if res.trim.endsWith(";") then res.trim.dropRight(1) else res
