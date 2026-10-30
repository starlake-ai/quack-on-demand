package ai.starlake.quack.edge.sql

import ai.starlake.acl.parser.ExtractionResult
import ai.starlake.quack.edge.policy.CatalogReachingCalls
import ai.starlake.sql.SqlLexer

/** Direct file access, refused to every tenant-scoped principal whenever the ACL validates (QoD
  * grant mode and OPA mode alike), the wildcard `*.*.* ALL` included. Owner ruling (2026-10-11): a
  * tenant user reads and writes data through tables; a file is read or written by a superuser, or
  * reached through a federated source. These were "unsupported constructs" that a `*.*.* ALL` grant
  * covered, so a tenant admin could read any file or object the node's process and secrets reach
  * (another tenant's DuckLake data files, the node's local disk) and write files with COPY.
  *
  * Refused, in every statement of a batch and behind EXPLAIN / PREPARE, read from DuckDB 2.0's own
  * tokens whether the statement parses or not:
  *
  *   - a call to a file-reading table function ([[FileFunctions]]: `read_parquet`, `read_csv`,
  *     `read_json`, `glob`, `read_text`, `parquet_metadata`, `iceberg_scan`, ...), read by
  *     [[FunctionCalls]] under every quoting DuckDB 2.0 accepts, inside string literal bodies too;
  *   - `COPY` to or from a path, `EXPORT DATABASE` and `IMPORT DATABASE` (`COPY FROM DATABASE a TO
  *     b` copies between attached catalogs and names no file);
  *   - `ATTACH` (a DuckDB, SQLite or DuckLake file is opened by path; a connection string reaches a
  *     caller-chosen server, refused in its function form by [[CatalogReachingCalls]]), and a
  *     `LOAD` / `INSTALL` naming a file, a URL or a repository (`LOAD '/tmp/x.duckdb_extension'`
  *     runs code read from a file). A bare `LOAD httpfs` stays admitted.
  *
  * A file in table position, which DuckDB replacement-scans (`FROM 'x.parquet'`, `JOIN 's3://..'`,
  * `SUMMARIZE '/d/x.csv'`, `FROM "s3://b/x.parquet"`, `FROM "/d/x"."csv"`, `FROM "*".csv`), is
  * decided in two tiers:
  *
  *   - When JSqlParser reads every statement completely, from the access check's own walk: a
  *     `file reference` marker (`DuckDBDialectMapper.isFileReference`: a string literal, or a name
  *     part holding a path separator, `://` or a glob character) in any read, DESCRIBE / SHOW /
  *     SUMMARIZE, write or DDL position. Precise: `x IS DISTINCT FROM 'y'` is a value, and a
  *     keyword used as an alias (`FROM range(1) AS values, '/etc/x.csv'`) cannot hide the join
  *     after it.
  *   - Otherwise (a statement JSqlParser cannot parse, or a walk that met a node it cannot follow),
  *     a lexical scan that only over-denies: any string literal right after FROM, JOIN, USING,
  *     TABLE, a statement keyword that reads a table (SUMMARIZE, DESCRIBE, SHOW, PIVOT, ...), a
  *     comma, or an opening parenthesis in such a position, at any depth and with no notion of
  *     where a FROM list ends; and any quoted identifier chain holding `://`, a glob character or a
  *     local path shape.
  *
  * Not refused: the native front door's client data scan (`scan_data_from_quack_client` reads the
  * client's own stream), the DuckLake catalog functions ([[CatalogReachingCalls]]' concern), and
  * attached sources read by table name (federation, Iceberg catalogs). An unquoted relative file
  * name DuckDB falls back to reading (`FROM x.parquet`, schema x and table parquet not found) is
  * not covered: the owner ruled out a name rule.
  *
  * Never wildcard-coverable, like [[CatalogReachingCalls]], and with the same population and
  * consumers: [[PostgresAclValidator]] and the OPA path, both through [[AccessGate]] (which hands
  * over the extraction it parsed anyway), and
  * [[ai.starlake.quack.edge.policy.ProtectedWriteGuard]]. A superuser reaches none of them.
  *
  * A principal whose tenant authorized locations (decisions 15 and 18 of the in-engine
  * authorization design) passes a file reader call or a lone COPY naming only those
  * ([[FileAdmission]], [[FileLocations]]); the node judges the same statement again on what DuckDB
  * parsed, and every other form above stays refused.
  */
object FileAccessScreen:

  /** Every table function that opens a caller-named file, path list, glob or URL: the lockdown
    * screen's list (derived from the 2.0 CLI's table functions with every extension a node loads,
    * pinned by LockdownTableFunctionsSpec), `sql_auto_complete`, plus the readers of extensions a
    * node may install that the 2.0 toolchain lacks (excel, spatial, delta). FileAccessFunctionsSpec
    * fails on any 2.0 table function nobody judged.
    */
  val FileFunctions: Set[String] = LockdownScreen.FileReadFunctions ++ Set(
    // lists the entries of a node directory named in its argument (`FROM '/etc/ho` completes to
    // the file names of /etc)
    "sql_auto_complete",
    "read_xlsx",
    "st_read",
    "st_read_meta",
    "st_readosm",
    "st_readshp",
    "delta_scan"
  )

  /** Table functions that open no caller-named file or directory, each judged on the 2.0 CLI:
    * catalog and node introspection (`duckdb_*`, `pragma_*`; `pragma_table_info('<path>')` fails on
    * a path rather than reading it), value generators and test tables, text tokenizers and JSON
    * walkers, Arrow pointer scans, static TPC-H text, `iceberg_scan_tasks` (its input is a scan
    * plan, read by a refused reader), `checkpoint` (a catalog), and the front door's client stream.
    * The introspection showing node state is [[NodeStateScreen]]'s, refused there.
    */
  private[sql] val NoFileFunctions: Set[String] = Set(
    "arrow_scan",
    "arrow_scan_dumb",
    "check_peg_parser",
    "checkpoint",
    "generate_series",
    "iceberg_scan_tasks",
    "icu_calendar_names",
    "json_each",
    "json_tree",
    "pg_timezone_names",
    "range",
    "repeat",
    "repeat_row",
    "scan_data_from_quack_client",
    "seq_scan",
    "sql_tokenize",
    "summary",
    "test_all_types",
    "test_vector_types",
    "tpch_answers",
    "tpch_queries",
    "unnest"
  ) ++ Set(
    "approx_database_count",
    "available_metrics",
    "columns",
    "connection_count",
    "constraints",
    "coordinate_systems",
    "dependencies",
    "dialects",
    "eviction_queues",
    "external_resource_types",
    "functions",
    "grammar_extensions",
    "indexes",
    "keywords",
    "memory",
    "optimizers",
    "prepared_statements",
    "schemas",
    "secret_type_parameters",
    "secret_types",
    "sequences",
    "tables",
    "triggers",
    "types",
    "variables",
    "views"
  ).map("duckdb_" + _) ++ Set(
    "collations",
    "database_size",
    "metadata_info",
    "platform",
    "show",
    "table_info",
    "user_agent",
    "version"
  ).map("pragma_" + _)

  /** Whether the tenant screens have judged the table function `name` (FileAccessFunctionsSpec):
    * refused as a file reader here, as a catalog-reaching or node-escaping call
    * ([[CatalogReachingCalls]]), as a node-wide or credential-exposing call
    * ([[NodeSettingScreen]]), as node state ([[NodeStateScreen]]), or admitted on purpose.
    */
  private[sql] def judged(name: String): Boolean =
    FileFunctions(name) || NoFileFunctions(name) || CatalogReachingCalls.LockdownNames(name) ||
      name.startsWith("ducklake_") || NodeSettingScreen.NodeWideCalls(name) ||
      NodeSettingScreen.SecretCalls(name) || NodeStateScreen.Refused.contains(name) ||
      FileReferences.ReadingTableMacros.contains(name)

  /** Some(reason) when `sql` reads or writes a file directly. */
  def find(sql: String): Option[String] = find(sql, FileAdmission.Closed)

  /** [[find]] for a principal whose tenant authorized the locations `admission` admits: a file
    * reader call or a lone COPY naming only such locations is no direct file access to refuse here
    * ([[FileLocations]] has the two shapes).
    */
  def find(sql: String, admission: FileAdmission): Option[String] =
    find(sql, FileReferences.extraction(FileLocations.extractable(sql, admission)), admission)

  /** [[find]] over the access check's own `extraction` of `sql` (of an admitted COPY's table side,
    * [[FileLocations.extractable]]), evaluated only when the lexical checks find nothing (file
    * references do not depend on the qualification config).
    */
  def find(
      sql: String,
      extraction: => ExtractionResult,
      admission: FileAdmission
  ): Option[String] =
    if sql == null then None
    else
      val scan         = FunctionCalls.scan(sql)
      val admittedCopy = FileLocations.copy(sql).exists(c => admission.admits(c.location))
      scan.calls.iterator
        .filter(c => FileFunctions(c.name))
        .find(c => !FileLocations.admitted(c, scan, admission))
        .map(c => reason(s"${c.name} reads files by path"))
        .orElse(
          Option.when(scan.truncated)(reason("a statement nesting string literals too deeply"))
        )
        .orElse(
          LockdownScreen
            .splitStatements(sql)
            .iterator
            .flatMap(statementVerb(_, admittedCopy))
            .nextOption()
        )
        .orElse(
          FileReferences
            .readingTableMacro(sql, extraction)
            .map(m => reason(s"$m reads the file or table its string argument names"))
        )
        .orElse(tablePositionFile(sql, extraction))

  /** A file in table position: from the walk when it is complete, else the lexical scan
    * ([[FileReferences]]).
    */
  private def tablePositionFile(sql: String, extraction: ExtractionResult): Option[String] =
    if FileReferences.incomplete(extraction) then
      LockdownScreen.splitStatements(sql).iterator.flatMap(lexicalTableFile).nextOption()
    else
      FileReferences
        .fromWalk(extraction)
        .headOption
        .map(name => reason(s"$name in a table position reads a file"))

  /** The conservative scan of a statement the walk could not read: a string literal right after a
    * table word, a comma, or a `(` itself in such a position
    * ([[LockdownScreen.conservativeLiterals]]); or a quoted identifier chain shaped like a URL, a
    * glob or a local path.
    */
  private def lexicalTableFile(stmt: String): Option[String] =
    if LockdownScreen.conservativeLiterals(stmt).nonEmpty then
      Some(reason("a file path in a table position reads the file"))
    else
      LockdownScreen.quotedPathFrom(
        stmt,
        name =>
          Option.when(FileReferences.pathShaped(name))(
            reason("a quoted path, URL or glob as a table reads files")
          )
      )

  /** The statement verbs that read or write files, judged on the statement an EXPLAIN / PREPARE
    * wrapper carries. `admittedCopy`: the submission is one COPY whose location the tenant
    * authorized ([[FileLocations.copy]]), which is no direct file access to refuse.
    */
  private def statementVerb(stmt: String, admittedCopy: Boolean): Option[String] =
    val head = StatementHead.of(stmt)
    val k    = head.start
    head.word(k) match
      case Some("copy")
          if !(head.word(k + 1).contains("from") && head.word(k + 2).contains("database")) =>
        Option.unless(admittedCopy)(reason("COPY reads or writes a file"))
      case Some("export") | Some("import") =>
        Some(reason("EXPORT / IMPORT DATABASE reads or writes files"))
      case Some("attach") => Some(reason("ATTACH opens a database file or a connection"))
      case Some("load") | Some("install") => Option.when(namesSource(head, k + 1))(loadReason)
      case Some("force") if head.word(k + 1).contains("install") =>
        Option.when(namesSource(head, k + 2))(loadReason)
      case _ => None

  /** A LOAD / INSTALL that names its extension by a quoted path or URL, or a repository. */
  private def namesSource(head: StatementHead.Head, from: Int): Boolean =
    (from until head.toks.length).exists { i =>
      head.toks(i).kind == SqlLexer.Kind.Literal || head.word(i).contains("from")
    }

  private val loadReason = reason("LOAD / INSTALL from a file, a URL or a repository loads code")

  private def reason(what: String): String =
    s"$what; direct file access is not available to tenant-scoped principals (deny, " +
      "fail-closed): a superuser or a federated source must do it"
