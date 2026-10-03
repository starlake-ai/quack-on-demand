package ai.starlake.acl.parser

import java.util.Locale
import ai.starlake.acl.model.Config
import ai.starlake.sql.{ParserInput, TooDeep}
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

  /** DuckLake time-travel clauses (`AT (VERSION => n)` / `AT (TIMESTAMP => expr)`) are not in
    * JSqlParser's grammar (any statement carrying one is a ParseException, which the validator
    * denies fail-closed). For ACL purposes a time-travel read of a table is exactly a read of that
    * table, so the clause is removed before parsing. The clause is found on DuckDB's own tokens
    * ([[ai.starlake.sql.ParserInput.stripTimeTravel ParserInput.stripTimeTravel]], the same pass
    * [[extract]] parses through), so a clause shape inside a string literal or a comment is left
    * alone.
    */
  def stripTimeTravelClauses(sql: String): String = ParserInput.stripTimeTravel(sql)

  /** One clause removed by [[timeTravelClauses]]: its verbatim text (`AT (VERSION => 3)`) and the
    * offset in the STRIPPED text where it stood. The whitespace around a clause is kept, so the
    * offset sits right after the whitespace that followed the table reference (and its alias).
    */
  final case class TimeTravelClause(at: Int, text: String)

  /** [[stripTimeTravelClauses]] plus the clauses it removed, in source order. The one scanner
    * behind both: the column and row policy rewriters strip with it and put each clause back on its
    * table reference (`TimeTravelCarrier`), so what they parse is exactly what the validator
    * parsed.
    */
  def timeTravelClauses(sql: String): (String, List[TimeTravelClause]) =
    val (stripped, clauses) = ParserInput.timeTravelClauses(sql)
    (stripped, clauses.map(TimeTravelClause(_, _)))

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
    TooDeep.orElse(ExtractionResult.fromStatements(List(tooDeep(sql)))) {
      if isClientBeginTransaction(sql) then
        ExtractionResult.fromStatements(List(clientBeginTransaction(0, sql)))
      else extractParsed(sql, config)
    }

  /** A statement nested too deeply for the walk (or JSqlParser's own printers) to follow: a parse
    * error, which every consumer denies, instead of a fatal `StackOverflowError` ([[TooDeep]]).
    */
  private def tooDeep(sql: String): StatementResult =
    StatementResult.ParseError(0, truncateSnippet(sql), TooDeep.Reason)

  /** `BEGIN TRANSACTION`, the statement a DuckDB 2.0 quack client sends when it first touches an
    * attached catalog (duckdb-quack `src/storage/quack_transaction.cpp`, `ForceStart`; its COMMIT
    * and ROLLBACK already parse). JSqlParser cannot parse it, so it is recognized here, ahead of
    * the parser, and ONLY as the whole input: case-insensitive, ASCII whitespace, one optional
    * trailing semicolon. A comment, a modifier (`READ ONLY`) or any second statement leaves the
    * input to the parser, whose fail-closed arms are unchanged.
    */
  private val ClientBeginTransaction = "(?i)\\A\\s*BEGIN\\s+TRANSACTION\\s*;?\\s*\\z".r

  private def isClientBeginTransaction(sql: String): Boolean =
    sql != null && ClientBeginTransaction.matches(sql)

  private def clientBeginTransaction(index: Int, sql: String): StatementResult =
    StatementResult.ControlFlow(index, truncateSnippet(sql.trim), "BeginTransaction")

  /** Parses `sql` as JSqlParser must read it for an access decision: from its lexer-canonical
    * rendering ([[ParserInput.renderWithoutTimeTravel]]), never from the raw text, whose escape
    * strings, dollar-quoted strings and `//` JSqlParser tokenizes differently from DuckDB. A
    * rendering refusal (input DuckDB rejects outright) is a parse error.
    */
  private def extractParsed(sql: String, config: Config): ExtractionResult =
    ParserInput.renderWithoutTimeTravel(sql) match
      case Left(reason) =>
        ExtractionResult.fromStatements(
          List(StatementResult.ParseError(0, truncateSnippet(sql), reason))
        )
      case Right(input) =>
        try
          val stmts: Statements = ai.starlake.sql.JSqlStatements.parseBatch(
            input.text,
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
            analysed(stmt, idx, config)
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
      TooDeep.orElse(tooDeep(sql)) {
        if isClientBeginTransaction(sql) then clientBeginTransaction(0, sql)
        else
          ParserInput.render(sql) match
            case Left(reason) => StatementResult.ParseError(0, truncateSnippet(sql), reason)
            case Right(input) =>
              analysed(ai.starlake.sql.JSqlStatements.parseFirst(input.text), 0, config)
      }
    catch
      case e: JSQLParserException =>
        val snippet = truncateSnippet(sql)
        val message = Option(e.getMessage).getOrElse("Unknown parse error")
        StatementResult.ParseError(0, snippet, message)

  /** [[processStatement]], with a failure of the analysis itself (a walk meeting a shape it cannot
    * follow) turned into a parse error, which the validators deny, rather than an exception that
    * escapes the gate.
    */
  private def analysed(stmt: Statement, index: Int, config: Config): StatementResult =
    try processStatement(stmt, index, config)
    catch
      case scala.util.control.NonFatal(e) =>
        StatementResult.ParseError(
          index,
          truncateSnippet(String.valueOf(stmt)),
          s"statement could not be analysed (${e.getClass.getSimpleName}); denied (fail-closed)"
        )

  /** Shared tail of every write/DDL arm: qualify the read sources and the single target table,
    * grant `targetVerb` on the target plus Read on every source, and carry the qualification errors
    * (source errors first) and unsupported constructs through. Each arm only decides WHICH clauses
    * are walked for read sources and which verb the target gets.
    */
  private def targetPlusReads(
      index: Int,
      snippet: String,
      target: Table,
      reads: TableExtraction,
      targetVerb: Verb,
      config: Config
  ): StatementResult =
    val (qSrc, e1) = TableQualifier.qualify(reads.tables, config)
    val (qTgt, e2) = TableQualifier.qualify(List(target), config)
    val accesses   = qTgt.map(t => TableAccess(t, targetVerb)).toSet ++
      qSrc.map(t => TableAccess(t, Verb.Read)).toSet
    StatementResult.Extracted(
      index,
      snippet,
      accesses,
      e1 ++ e2,
      reads.unsupported ++ fileTargets(List(target))
    )

  /** The markers of the targets DuckDB reads as a file (`INSERT INTO "s3://b/t"`), refused like a
    * file read: the name is no table a grant on its schema could stand for.
    */
  private def fileTargets(targets: List[Table]): List[String] =
    targets.filter(DuckDBDialectMapper.isFileReference).map(DuckDBDialectMapper.fileReferenceMarker)

  /** Read sources of a CTAS-style statement: the extraction of its SELECT when present, empty
    * otherwise (plain INSERT VALUES, bare CREATE TABLE).
    */
  private def selectReads(select: Option[Select]): TableExtraction =
    select.map(TableExtractor.extract).getOrElse(TableExtraction(Nil, Nil))

  /** An inner statement's result re-attributed to the statement that carries it (EXPLAIN,
    * SUMMARIZE, DESCRIBE of a query): same accesses, the outer index and snippet.
    */
  private def asOuter(inner: StatementResult, index: Int, snippet: String): StatementResult =
    inner match
      case StatementResult.Extracted(_, _, accesses, errs, unsup) =>
        StatementResult.Extracted(index, snippet, accesses, errs, unsup)
      case StatementResult.ParseError(_, _, msg) =>
        StatementResult.ParseError(index, snippet, msg)
      case StatementResult.ControlFlow(_, _, t) =>
        StatementResult.ControlFlow(index, snippet, s"EXPLAIN $t")

  /** `SELECT * FROM <table>`: the read a statement naming a bare table performs on it. */
  private def selectAllFrom(table: Table): Select =
    val select = new net.sf.jsqlparser.statement.select.PlainSelect()
    select.addSelectItem(new net.sf.jsqlparser.statement.select.AllColumns())
    select.setFromItem(table)
    select

  /** Read on the one table a DESCRIBE / SHOW names, which reveals its shape. A string literal there
    * is a file the node reads (a replacement scan), so it is a file reference with no table to
    * grant on, as in FROM.
    */
  private def namedTableRead(
      table: Table,
      index: Int,
      snippet: String,
      config: Config
  ): StatementResult =
    if DuckDBDialectMapper.isFileReference(table) then
      StatementResult.Extracted(
        index,
        snippet,
        Set.empty,
        Nil,
        List(DuckDBDialectMapper.fileReferenceMarker(table))
      )
    else
      val (qTgt, errs) = TableQualifier.qualify(List(table), config)
      StatementResult
        .Extracted(index, snippet, qTgt.map(t => TableAccess(t, Verb.Read)), errs)

  /** True when any value of a SET statement reaches a table, a table function or a construct the
    * table walker cannot resolve.
    */
  private def setValueReads(set: SetStatement): Boolean =
    val v = new TableExtractorVisitor()
    (0 until set.getCount).foreach { i =>
      Option(set.getExpressions(i))
        .foreach(_.asScala.foreach(e => Option(e).foreach(v.visitExpression)))
    }
    val r = v.result
    r.tables.nonEmpty || r.unsupported.nonEmpty

  // SHOW <keyword> forms that jsqlparser also parses as ShowStatement but that name
  // no table: they stay ControlFlow (v1 defers filtering them; see the spec).
  private val ShowKeywordForms: Set[String] = Set("DATABASES", "SCHEMAS")

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

      // parser-3: INSERT. The statement-level WITH clause lives on the Insert node,
      // not on its inner Select, so it must be walked here or a CTE body could
      // launder a read of any table behind a CTE named after a granted table.
      case ins: Insert =>
        val v = new TableExtractorVisitor()
        v.processWithItems(ins.getWithItemsList)
        Option(ins.getSelect).foreach(v.process)
        targetPlusReads(index, snippet, ins.getTable, v.result, Verb.Write, config)

      // parser-4: UPDATE
      case upd: Update =>
        targetPlusReads(
          index,
          snippet,
          upd.getTable,
          UpdateReadExtractor.extract(upd),
          Verb.Write,
          config
        )

      // parser-5: DELETE
      case del: Delete =>
        targetPlusReads(
          index,
          snippet,
          del.getTable,
          DeleteReadExtractor.extract(del),
          Verb.Write,
          config
        )

      // parser-6: MERGE
      case mrg: Merge =>
        val v = new TableExtractorVisitor()
        // Statement-level WITH first, so USING <cte> resolves as the CTE it names.
        v.processWithItems(mrg.getWithItemsList)
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
          // WHEN [NOT MATCHED BY SOURCE |] MATCHED AND (SELECT ...) THEN DELETE: the predicate
          // runs against the real rows, so its subquery is a read like any other.
          case md: net.sf.jsqlparser.statement.merge.MergeDelete =>
            Option(md.getAndPredicate).foreach(v.visitExpression)
          // A future MergeOperation subtype must not be silently dropped: fail closed.
          case other =>
            v.unsupported += s"unrecognized MERGE action ${other.getClass.getSimpleName}"
        })
        Option(mrg.getOnCondition).foreach(v.visitExpression)
        targetPlusReads(index, snippet, mrg.getTable, v.result, Verb.Write, config)

      // parser-7: CREATE TABLE
      case ct: CreateTable =>
        targetPlusReads(
          index,
          snippet,
          ct.getTable,
          selectReads(Option(ct.getSelect)),
          Verb.Ddl,
          config
        )

      // parser-7: CREATE VIEW
      case cv: CreateView =>
        targetPlusReads(
          index,
          snippet,
          cv.getView,
          selectReads(Option(cv.getSelect)),
          Verb.Ddl,
          config
        )

      // parser-8: DROP
      case dr: Drop =>
        // Drop.getName returns a Table in JSQLParser 5.x
        val targets: List[Table] = Option(dr.getName).toList
        val (qTgt, errs)         = TableQualifier.qualify(targets, config)
        val accesses             = qTgt.map(t => TableAccess(t, Verb.Ddl)).toSet
        StatementResult.Extracted(index, snippet, accesses, errs, fileTargets(targets))

      // parser-8: ALTER TABLE
      case al: Alter =>
        val target       = al.getTable
        val (qTgt, errs) = TableQualifier.qualify(List(target), config)
        val accesses     = qTgt.map(t => TableAccess(t, Verb.Ddl)).toSet
        StatementResult.Extracted(index, snippet, accesses, errs, fileTargets(List(target)))

      // parser-8: TRUNCATE
      case tr: Truncate =>
        val target       = tr.getTable
        val (qTgt, errs) = TableQualifier.qualify(List(target), config)
        // Truncate is a mass-delete; treat as Write so a WRITE grant covers it.
        val accesses = qTgt.map(t => TableAccess(t, Verb.Write)).toSet
        StatementResult.Extracted(index, snippet, accesses, errs, fileTargets(List(target)))

      // parser-9: EXPLAIN. `EXPLAIN <stmt>` is read-only, but DuckDB's
      // `EXPLAIN ANALYZE <dml>` EXECUTES the inner statement. Classify by the
      // inner statement so an EXPLAIN ANALYZE DELETE is authorized as the DELETE
      // it really runs, not admitted as a table-free control-flow verb.
      //
      // SUMMARIZE parses to the same node. `SUMMARIZE <query>` carries the query as the inner
      // statement; `SUMMARIZE <table>` carries no inner statement but the table, and returns its
      // per-column min / max / quantiles: a read of every value, gated exactly like a
      // `SELECT * FROM <table>` (a string literal there is a file the node reads, and a bare
      // system-view name the view, both handled by the same FROM-item walk).
      case ex: ExplainStatement =>
        (Option(ex.getStatement), Option(ex.getTable)) match
          case (Some(inner), _) => asOuter(processStatement(inner, index, config), index, snippet)
          case (None, Some(table)) =>
            asOuter(processStatement(selectAllFrom(table), index, config), index, snippet)
          case (None, None) =>
            // Bare EXPLAIN with no inner statement carries no table refs.
            StatementResult.ControlFlow(index, snippet, "ExplainStatement")

      case _: UnsupportedStatement =>
        StatementResult.ParseError(index, snippet, s"Unsupported or unparseable statement")

      // DESCRIBE t / SHOW t / SHOW COLUMNS FROM t reveal a table's shape: that is a
      // Read on the table, not control flow. Previously allowlisted, which let any
      // principal describe any table (enumeration bypass); now grant-checked like a
      // SELECT. Plain SHOW TABLES stays ControlFlow and is replaced or denied by the
      // edge metadata filter downstream; SHOW ALL TABLES never parses (denied
      // fail-closed).
      // `DESCRIBE <query>` (also `DESCRIBE FROM t`) carries the query instead of a table: its
      // reads are the query's. A string literal in table position is a file the node reads.
      case d: DescribeStatement =>
        Option(d.getSelect) match
          case Some(select) => asOuter(processStatement(select, index, config), index, snippet)
          case None         => namedTableRead(d.getTable, index, snippet, config)
      case sc: ShowColumnsStatement =>
        // Only the unqualified form reaches here; `SHOW COLUMNS FROM x.y` is not in
        // the grammar and already fails closed on the parse-error arm.
        val (qTgt, errs) = TableQualifier.qualify(List(new Table(sc.getTableName)), config)
        StatementResult
          .Extracted(index, snippet, qTgt.map(t => TableAccess(t, Verb.Read)), errs)
      case sh: ShowStatement
          if !ShowKeywordForms.contains(sh.getName.trim.toUpperCase(Locale.ROOT)) =>
        TableExtractor.asFromTable(sh.getName) match
          case Some(t) => namedTableRead(t, index, snippet, config)
          case None    =>
            StatementResult.ParseError(index, snippet, s"unreadable SHOW target ${sh.getName}")

      // SET VARIABLE evaluates its value as a query: DuckDB runs
      // `SET VARIABLE x = (SELECT v FROM db.other.t)` and getvariable('x') returns the row,
      // while getvariable() itself names no table. A SET whose value reaches any table, table
      // function or construct the walker cannot resolve is therefore NOT control flow. It
      // takes the parse-error arm (denied unless the principal holds the unrestricted ALL
      // grant, like any statement the walker cannot fully read) rather than having its reads
      // gated: the CLS / RLS rewriters and the metadata filter only rewrite SELECTs, so
      // admitting a granted read here would hand back unmasked, unfiltered values, and the
      // protected-write guard denies a parse error outright for a principal with policies.
      // (A plain setting cannot take a subquery at all: DuckDB refuses "SET value cannot
      // contain subqueries".)
      case set: SetStatement if setValueReads(set) =>
        StatementResult.ParseError(
          index,
          snippet,
          "SET whose value reads a table is not supported (deny, fail-closed); run the SELECT " +
            "on its own instead"
        )

      // Explicit allowlist of statement types that provably carry no grantable
      // table reference. Admitted unconditionally as ControlFlow. Anything NOT
      // on this list falls through to the fail-closed ParseError arm below, so a
      // new/unknown statement type can never be silently admitted with an empty
      // access set.
      case _: Commit | _: RollbackStatement | _: SavepointStatement | _: SetStatement |
          _: ResetStatement | _: UseStatement | _: ShowStatement | _: ShowTablesStatement |
          _: SessionStatement =>
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
