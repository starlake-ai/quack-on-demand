package ai.starlake.acl.parser

import ai.starlake.acl.model.Config
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.parser.feature.{Feature, FeatureConfiguration}
import net.sf.jsqlparser.statement.{Statement, Statements, UnsupportedStatement}
import net.sf.jsqlparser.statement.select.Select

import scala.jdk.CollectionConverters.*

/** Public API for SQL parsing and table extraction.
  *
  * Parses SQL strings (single or multi-statement) and extracts fully-qualified
  * TableRef instances using Config defaults for qualification. No JSqlParser types
  * leak through this API -- only domain types in results.
  */
object SqlParser:

  /** Extract table references from a SQL string that may contain multiple statements.
    *
    * Each statement is processed independently:
    *   - SELECT statements: tables are extracted and qualified
    *   - Non-SELECT statements: reported as NonSelect with statement type
    *   - Unparseable statements: reported as ParseError with message
    *
    * @param sql the SQL string (may contain multiple semicolon-separated statements)
    * @param config configuration with dialect and default database/schema
    * @return ExtractionResult with per-statement results and aggregated tables
    */
  def extract(sql: String, config: Config): ExtractionResult =
    try
      val stmts: Statements = CCJSqlParserUtil.parseStatements(
        sql,
        (parser: net.sf.jsqlparser.parser.CCJSqlParser) => {
          val featureConfig = new FeatureConfiguration()
          featureConfig.setValue(Feature.allowUnsupportedStatements, true): Unit
          parser.withConfiguration(featureConfig): Unit
        }
      )

      val statements =
        try stmts.asScala.toList
        catch case _: NullPointerException => Nil
      val results = statements.zipWithIndex.map { case (stmt, idx) =>
        processStatement(stmt, idx, config)
      }

      ExtractionResult.fromStatements(results)
    catch
      case e: JSQLParserException =>
        // Entire input is unparseable
        val snippet = truncateSnippet(sql)
        val message = Option(e.getMessage).getOrElse("Unknown parse error")
        ExtractionResult.fromStatements(
          List(StatementResult.ParseError(0, snippet, message))
        )

  /** Extract table references from a single SQL statement.
    *
    * Convenience method for single-statement input. Wraps parse errors
    * as StatementResult.ParseError rather than throwing exceptions.
    *
    * @param sql a single SQL statement
    * @param config configuration with dialect and default database/schema
    * @return a single StatementResult
    */
  def extractSingle(sql: String, config: Config): StatementResult =
    try
      val stmt = CCJSqlParserUtil.parse(sql)
      processStatement(stmt, 0, config)
    catch
      case e: JSQLParserException =>
        val snippet = truncateSnippet(sql)
        val message = Option(e.getMessage).getOrElse("Unknown parse error")
        StatementResult.ParseError(0, snippet, message)

  private def processStatement(
      stmt: Statement,
      index: Int,
      config: Config
  ): StatementResult =
    val snippet = truncateSnippet(stmt.toString)
    stmt match
      case select: Select =>
        val rawTables = TableExtractor.extract(select)
        val (qualifiedTables, errors) = TableQualifier.qualify(rawTables, config)
        StatementResult.Extracted(index, snippet, qualifiedTables, errors)

      case _: UnsupportedStatement =>
        StatementResult.ParseError(index, snippet, s"Unsupported or unparseable statement")

      case other =>
        StatementResult.NonSelect(index, snippet, other.getClass.getSimpleName)
