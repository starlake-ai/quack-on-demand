package ai.starlake.acl.parser

import ai.starlake.acl.model.Config
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.parser.feature.{Feature, FeatureConfiguration}
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.{
  Commit,
  DescribeStatement,
  ExplainStatement,
  ResetStatement,
  RollbackStatement,
  SavepointStatement,
  SessionStatement,
  SetStatement,
  ShowColumnsStatement,
  ShowStatement,
  Statement,
  Statements,
  UnsupportedStatement,
  UseStatement
}
import net.sf.jsqlparser.statement.show.ShowTablesStatement
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.drop.Drop
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.merge.Merge
import net.sf.jsqlparser.statement.select.{FromItem, Select}
import net.sf.jsqlparser.statement.truncate.Truncate
import net.sf.jsqlparser.statement.update.Update

import scala.jdk.CollectionConverters.*

/** Public API for SQL parsing and table extraction.
  *
  * Parses SQL strings (single or multi-statement) and extracts fully-qualified TableRef instances
  * using Config defaults for qualification. No JSqlParser types leak through this API -- only
  * domain types in results.
  */
object SqlParser:

  /** Extract table references from a SQL string that may contain multiple statements.
    *
    * Each statement is processed independently:
    *   - SELECT statements: tables are extracted and qualified, reported as Extracted with Read
    *     accesses
    *   - DML statements (INSERT/UPDATE/DELETE/MERGE): reported as Extracted with Write/Read
    *     accesses
    *   - DDL statements (CREATE/DROP/ALTER): reported as Extracted with Ddl/Read accesses
    *   - TRUNCATE: reported as Extracted with Write access (mass-delete semantic)
    *   - Control-flow statements (COMMIT, SET, USE, etc.): reported as ControlFlow
    *   - Unparseable statements: reported as ParseError with message
    *
    * @param sql
    *   the SQL string (may contain multiple semicolon-separated statements)
    * @param config
    *   configuration with dialect and default database/schema
    * @return
    *   ExtractionResult with per-statement results and aggregated tables
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
    * Convenience method for single-statement input. Wraps parse errors as
    * StatementResult.ParseError rather than throwing exceptions.
    *
    * @param sql
    *   a single SQL statement
    * @param config
    *   configuration with dialect and default database/schema
    * @return
    *   a single StatementResult
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
        val extraction                = TableExtractor.extract(select)
        val (qualifiedTables, errors) = TableQualifier.qualify(extraction.tables, config)
        val accesses                  = qualifiedTables.map(t => TableAccess(t, Verb.Read)).toSet
        StatementResult.Extracted(index, snippet, accesses, errors, extraction.unsupported)

      // parser-3: INSERT
      case ins: Insert =>
        val target      = ins.getTable
        val srcExtract  = Option(ins.getSelect).map(TableExtractor.extract)
        val srcRaw      = srcExtract.map(_.tables).getOrElse(Nil)
        val unsupported = srcExtract.map(_.unsupported).getOrElse(Nil)
        val (qSrc, e1)  = TableQualifier.qualify(srcRaw, config)
        val (qTgt, e2)  = TableQualifier.qualify(List(target), config)
        val accesses    = qTgt.map(t => TableAccess(t, Verb.Write)).toSet ++
          qSrc.map(t => TableAccess(t, Verb.Read)).toSet
        StatementResult.Extracted(index, snippet, accesses, e1 ++ e2, unsupported)

      // parser-4: UPDATE
      case upd: Update =>
        val target      = upd.getTable
        val readExtract = UpdateReadExtractor.extract(upd)
        val (qSrc, e1)  = TableQualifier.qualify(readExtract.tables, config)
        val (qTgt, e2)  = TableQualifier.qualify(List(target), config)
        val accesses    = qTgt.map(t => TableAccess(t, Verb.Write)).toSet ++
          qSrc.map(t => TableAccess(t, Verb.Read)).toSet
        StatementResult.Extracted(index, snippet, accesses, e1 ++ e2, readExtract.unsupported)

      // parser-5: DELETE
      case del: Delete =>
        val target      = del.getTable
        val readExtract = DeleteReadExtractor.extract(del)
        val (qSrc, e1)  = TableQualifier.qualify(readExtract.tables, config)
        val (qTgt, e2)  = TableQualifier.qualify(List(target), config)
        val accesses    = qTgt.map(t => TableAccess(t, Verb.Write)).toSet ++
          qSrc.map(t => TableAccess(t, Verb.Read)).toSet
        StatementResult.Extracted(index, snippet, accesses, e1 ++ e2, readExtract.unsupported)

      // parser-6: MERGE
      case mrg: Merge =>
        val v = new TableExtractorVisitor()
        Option(mrg.getFromItem).foreach(v.visitFromItem)
        // WHEN MATCHED THEN UPDATE SET c = (SELECT ...) and WHEN NOT MATCHED
        // INSERT ... VALUES (SELECT ...) can smuggle read sources past the gate.
        Option(mrg.getOperations).foreach(_.asScala.foreach {
          case mu: net.sf.jsqlparser.statement.merge.MergeUpdate =>
            Option(mu.getUpdateSets).foreach(_.asScala.foreach { us =>
              Option(us.getValues).foreach(v.visitExpression)
            })
            Option(mu.getWhereCondition).foreach(v.visitExpression)
            Option(mu.getAndPredicate).foreach(v.visitExpression)
            Option(mu.getDeleteWhereCondition).foreach(v.visitExpression)
          case mi: net.sf.jsqlparser.statement.merge.MergeInsert =>
            Option(mi.getValues).foreach(v.visitExpression)
            Option(mi.getWhereCondition).foreach(v.visitExpression)
            Option(mi.getAndPredicate).foreach(v.visitExpression)
          case _ => ()
        })
        Option(mrg.getOnCondition).foreach(v.visitExpression)
        val target      = mrg.getTable
        val readExtract = v.result
        val (qSrc, e1)  = TableQualifier.qualify(readExtract.tables, config)
        val (qTgt, e2)  = TableQualifier.qualify(List(target), config)
        val accesses    = qTgt.map(t => TableAccess(t, Verb.Write)).toSet ++
          qSrc.map(t => TableAccess(t, Verb.Read)).toSet
        StatementResult.Extracted(index, snippet, accesses, e1 ++ e2, readExtract.unsupported)

      // parser-7: CREATE TABLE
      case ct: CreateTable =>
        val target      = ct.getTable
        val srcExtract  = Option(ct.getSelect).map(TableExtractor.extract)
        val srcRaw      = srcExtract.map(_.tables).getOrElse(Nil)
        val unsupported = srcExtract.map(_.unsupported).getOrElse(Nil)
        val (qSrc, e1)  = TableQualifier.qualify(srcRaw, config)
        val (qTgt, e2)  = TableQualifier.qualify(List(target), config)
        val accesses    = qTgt.map(t => TableAccess(t, Verb.Ddl)).toSet ++
          qSrc.map(t => TableAccess(t, Verb.Read)).toSet
        StatementResult.Extracted(index, snippet, accesses, e1 ++ e2, unsupported)

      // parser-7: CREATE VIEW
      case cv: CreateView =>
        val target      = cv.getView
        val srcExtract  = Option(cv.getSelect).map(TableExtractor.extract)
        val srcRaw      = srcExtract.map(_.tables).getOrElse(Nil)
        val unsupported = srcExtract.map(_.unsupported).getOrElse(Nil)
        val (qSrc, e1)  = TableQualifier.qualify(srcRaw, config)
        val (qTgt, e2)  = TableQualifier.qualify(List(target), config)
        val accesses    = qTgt.map(t => TableAccess(t, Verb.Ddl)).toSet ++
          qSrc.map(t => TableAccess(t, Verb.Read)).toSet
        StatementResult.Extracted(index, snippet, accesses, e1 ++ e2, unsupported)

      // parser-8: DROP
      case dr: Drop =>
        // Drop.getName returns a Table in JSQLParser 5.x
        val targets: List[Table] = Option(dr.getName).toList
        val (qTgt, errs)         = TableQualifier.qualify(targets, config)
        val accesses             = qTgt.map(t => TableAccess(t, Verb.Ddl)).toSet
        StatementResult.Extracted(index, snippet, accesses, errs)

      // parser-8: ALTER TABLE
      case al: Alter =>
        val target       = al.getTable
        val (qTgt, errs) = TableQualifier.qualify(List(target), config)
        val accesses     = qTgt.map(t => TableAccess(t, Verb.Ddl)).toSet
        StatementResult.Extracted(index, snippet, accesses, errs)

      // parser-8: TRUNCATE
      case tr: Truncate =>
        val target       = tr.getTable
        val (qTgt, errs) = TableQualifier.qualify(List(target), config)
        // Truncate is a mass-delete; treat as Write so a WRITE grant covers it.
        val accesses = qTgt.map(t => TableAccess(t, Verb.Write)).toSet
        StatementResult.Extracted(index, snippet, accesses, errs)

      // parser-9: EXPLAIN. `EXPLAIN <stmt>` is read-only, but DuckDB's
      // `EXPLAIN ANALYZE <dml>` EXECUTES the inner statement. Classify by the
      // inner statement so an EXPLAIN ANALYZE DELETE is authorized as the DELETE
      // it really runs, not admitted as a table-free control-flow verb.
      case ex: ExplainStatement =>
        Option(ex.getStatement) match
          case Some(inner) =>
            processStatement(inner, index, config) match
              case StatementResult.Extracted(_, _, accesses, errs, unsup) =>
                StatementResult.Extracted(index, snippet, accesses, errs, unsup)
              case StatementResult.ParseError(_, _, msg) =>
                StatementResult.ParseError(index, snippet, msg)
              case StatementResult.ControlFlow(_, _, t) =>
                StatementResult.ControlFlow(index, snippet, s"EXPLAIN $t")
          case None =>
            // Bare EXPLAIN with no inner statement carries no table refs.
            StatementResult.ControlFlow(index, snippet, "ExplainStatement")

      case _: UnsupportedStatement =>
        StatementResult.ParseError(index, snippet, s"Unsupported or unparseable statement")

      // Explicit allowlist of statement types that provably carry no grantable
      // table reference. Admitted unconditionally as ControlFlow. Anything NOT
      // on this list falls through to the fail-closed ParseError arm below, so a
      // new/unknown statement type can never be silently admitted with an empty
      // access set.
      case _: Commit | _: RollbackStatement | _: SavepointStatement | _: SetStatement |
          _: ResetStatement | _: UseStatement | _: ShowStatement | _: ShowColumnsStatement |
          _: ShowTablesStatement | _: DescribeStatement | _: SessionStatement =>
        StatementResult.ControlFlow(index, snippet, stmt.getClass.getSimpleName)

      case other =>
        // Fail closed: a statement type we do not positively recognize as
        // table-free is treated as a parse error, so the validator denies it
        // unless the principal holds an unrestricted ALL grant.
        StatementResult.ParseError(
          index,
          snippet,
          s"unrecognized statement type ${other.getClass.getSimpleName}; denied (fail-closed)"
        )
