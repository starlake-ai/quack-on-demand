package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.{ExtractionResult, SqlParser, StatementResult, TableExtractor}

import java.util.Locale

/** The parse tier shared by the two file screens ([[FileAccessScreen]] for tenant principals,
  * [[LockdownScreen]] on lockdown pools): what the access check's own walk says about the files a
  * statement names in a table position, and whether that walk can be trusted.
  *
  * A statement JSqlParser reads completely is judged from its walk: every `file reference` marker
  * (`DuckDBDialectMapper.isFileReference`: a string literal, or a name part holding a path
  * separator, `://` or a glob character) in any read, DESCRIBE / SHOW / SUMMARIZE, write or DDL
  * position, and nothing else (`x IS DISTINCT FROM 'y'` is a value). A statement it cannot read, or
  * a walk that met a node it could not follow, falls to each screen's conservative lexical scan
  * ([[LockdownScreen.conservativeLiterals]], [[LockdownScreen.quotedPathFrom]]).
  */
private[sql] object FileReferences:

  /** The walk of `sql` under a neutral config: a file reference does not depend on qualification.
    */
  def extraction(sql: String): ExtractionResult =
    SqlParser.extract(sql, Config.forDuckDB(None, None, Set.empty))

  /** The walk did not read the whole batch: a parse error, or a node it could not follow. */
  def incomplete(extraction: ExtractionResult): Boolean =
    extraction.statements.exists {
      case _: StatementResult.ParseError                  => true
      case StatementResult.Extracted(_, _, _, _, markers) => markers.exists(isIncompleteMarker)
      case _                                              => false
    }

  private def isIncompleteMarker(marker: String): Boolean =
    List("unrecognized ", "unwalked ", "unreadable ", "data-modifying ").exists(marker.startsWith)

  /** The names of every file reference of a complete walk, as DuckDB reads them: a literal's value
    * (`'it''s.csv'` is `it's.csv`), or the parts of a quoted chain unquoted and joined with dots
    * (`"s3://b/x"."parquet"` is `s3://b/x.parquet`, the name DuckDB replacement-scans).
    */
  def fromWalk(extraction: ExtractionResult): List[String] =
    extraction.statements
      .collect { case StatementResult.Extracted(_, _, _, _, markers) => markers }
      .flatten
      .filter(_.startsWith(TableExtractor.FileReferencePrefix))
      .map(m => decode(m.stripPrefix(TableExtractor.FileReferencePrefix)))

  /** `'...'` to its value; a dotted chain of bare, double-quoted or backtick parts to its name. */
  private def decode(text: String): String =
    if text.startsWith("'") then text.drop(1).dropRight(1).replace("''", "'")
    else
      val parts = scala.collection.mutable.ListBuffer.empty[String]
      val cur   = new StringBuilder
      var quote = Option.empty[Char]
      var i     = 0
      while i < text.length do
        val c = text.charAt(i)
        quote match
          case Some(q) if c == q && i + 1 < text.length && text.charAt(i + 1) == q =>
            cur += q; i += 1
          case Some(q) if c == q            => quote = None
          case Some(_)                      => cur += c
          case None if c == '"' || c == '`' => quote = Some(c)
          case None if c == '.'             => parts += cur.toString; cur.clear()
          case None                         => cur += c
        i += 1
      parts += cur.toString
      parts.mkString(".")

  /** A joined quoted-identifier chain DuckDB would read as a file: a URL, a glob (a bare part
    * cannot hold a glob character, so a quoted one does: `"*".csv` reads every CSV of the working
    * directory, whatever the position or extension), or a local path.
    */
  def pathShaped(name: String): Boolean =
    name.contains("://") || GlobChars.exists(name.contains) ||
      LockdownScreen.looksLikePath("'" + name + "'")

  private val GlobChars = List("*", "?", "[", "{")

  /** DuckDB 2.0 table macros whose body reads a table or file named by a string argument, with the
    * function in the body that does it. `histogram_values(source, col)` runs `query_table(source)`
    * (`SELECT * FROM histogram_values('/etc/x.csv', b)` returned the CSV's values on the 2.0 CLI,
    * and `histogram_values('customer', ..)` a policy table unfiltered), and `histogram` runs
    * `histogram_values`. The edge sees only the macro's name, never its body, so it is judged by
    * its body (FileAccessFunctionsSpec pins every 2.0 macro body).
    */
  val ReadingTableMacros: Map[String, String] =
    Map("histogram" -> "histogram_values", "histogram_values" -> "query_table")

  /** A [[ReadingTableMacros]] macro called in a table position: from the walk when it is complete
    * (a FROM-item table function; the aggregate `histogram(expr)` in a select list shares the name
    * and is a value), else any call of that name, lexically.
    */
  def readingTableMacro(sql: String, extraction: ExtractionResult): Option[String] =
    if incomplete(extraction) then
      FunctionCalls.scan(sql).calls.iterator.map(_.name).find(ReadingTableMacros.contains)
    else tableFunctionCalls(extraction).map(callName).find(ReadingTableMacros.contains)

  /** Every table function call of a walk, as the parser prints it (`main."histogram"('x', b)`). */
  def tableFunctionCalls(extraction: ExtractionResult): List[String] =
    extraction.statements
      .collect { case StatementResult.Extracted(_, _, _, _, markers) => markers }
      .flatten
      .flatMap(TableExtractor.tableFunctionName)

  /** The function name of a table function call as the parser prints it: its last name part,
    * unquoted and lowercased (`main."histogram"('x', b)` is `histogram`).
    */
  private[sql] def callName(call: String): String =
    val head = call.takeWhile(_ != '(').trim
    val last = if head.contains('.') then head.substring(head.lastIndexOf('.') + 1) else head
    ai.starlake.sql.QuotedIdentifier.unquote(last).toLowerCase(Locale.ROOT)
