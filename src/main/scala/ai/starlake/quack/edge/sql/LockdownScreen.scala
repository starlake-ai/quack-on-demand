package ai.starlake.quack.edge.sql

import ai.starlake.quack.model.BucketKeys
import ai.starlake.sql.{ParserInput, SqlCommentStripper, SqlLexer, SqlTrivia}

import java.util.Locale

import scala.collection.mutable.ListBuffer

/** Statement screen for locked-down deployments (QOD_NODE_LOCKDOWN). Pure: the router consults it
  * only when the flag is on and the caller is not a superuser. Superuser detection is the caller's
  * job: both front doors bind the handshake's effective set whatever the ACL mode, so a superuser
  * is recognized, and exempt, with the ACL off too; a call with no effective set bound screens as
  * non-superuser (fail closed).
  *
  * Matching is token-based, deliberately not a full parse: the input is split on top-level
  * semicolons (quote- and comment-aware) and each statement is screened independently. Leading
  * trivia (whitespace, BOM, unicode spaces, line comments, nested block comments) is stripped
  * before the first-token check, and every INTERIOR comment is also stripped (replaced by a
  * separator, not deleted -- see `screenOne`) before the keyword-adjacency regexes run, so a
  * comment sitting between a keyword and its argument cannot hide that adjacency the way an
  * interior Unicode trivia character could. That holds for a nested comment too, and a comment
  * marker inside any of DuckDB's quoting forms is left alone rather than opening a comment; see
  * `ai.starlake.sql.SqlCommentStripper`, which is where both properties live and are tested. A
  * denied function called anywhere (subqueries and string literal bodies included, EVERY
  * occurrence, under any DuckDB 2.0 identifier quoting: see [[FunctionCalls]], which reads calls
  * from the lexer's tokens) is denied, EXCEPT when every path argument is a string literal with an
  * object-store scheme.
  *
  * What this screen does NOT claim: it is a deny-list of shapes, not a proof of safety. A statement
  * no rule below matches is ADMITTED. The strip passes exist so a rule cannot be dodged by hiding
  * the shape it looks for, not so that every unrecognized statement is refused.
  */
object LockdownScreen:

  private val DeniedFirstTokens = Map(
    "attach"     -> "ATTACH is disabled on this deployment",
    "detach"     -> "DETACH is disabled on this deployment",
    "install"    -> "INSTALL is disabled on this deployment",
    "load"       -> "LOAD is disabled on this deployment",
    "connect"    -> "CONNECT is disabled on this deployment",
    "disconnect" -> "DISCONNECT is disabled on this deployment"
  )

  // Lockdown-specific settings; the node-wide ones (resources, extension loading, logging,
  // allowed paths, temp and secret directories...) live in NodeSettingScreen.NodeWideSettings, the
  // one list both screens read, refused to non-superusers whatever the lockdown flag and to every
  // caller under lockdown.
  private val ProtectedSettings = Set(
    // iceberg: lets iceberg_scan guess a table's metadata version by listing its directory, which
    // reads what sits beside the table rather than the version its catalog pins.
    "unsafe_enable_version_guessing",
    // Local files read or written by path.
    "home_directory",
    "file_search_path",
    // Outbound connections: TLS trust, and SQL the node runs against the Postgres catalog with its
    // own credentials.
    "ca_cert_file",
    "enable_server_cert_verification",
    "enable_curl_server_cert_verification",
    "pg_staleness_query",
    "pg_pool_health_check_query",
    // The node's own quack server authentication and authorization callbacks (also quack_*).
    "quack_authentication_function",
    "quack_authorization_function"
  ) ++ NodeSettingScreen.Settings ++ NodeSettingScreen.NodeWideSettings

  // Read-style functions that take a path or URL as their FIRST argument. A remote URL literal is
  // admitted (UrlExempt), a local path denied; glob (directory listing) never exempts.
  // The list is every DuckDB 2.0 table function (duckdb_functions(), function_type = 'table') whose
  // first argument is a caller-named file, path list or glob. Rows judged and excluded: the
  // argument-free duckdb_* / pragma_* / ducklake_* introspection functions (no path);
  // query / query_table / json_execute_serialized_sql and the postgres_* / mysql_* / sqlite_* /
  // quack_* / *_external_resource functions (NodeEscapingFunctions); arrow_scan / arrow_scan_dumb
  // (pointers, not a path); which_secret (matches a path against secrets, opens no file);
  // ducklake_add_data_files (path is the THIRD argument, in NoPathFunctions below); the write-side
  // enable_logging / enable_profiling (NoPathFunctions). read_single_*_file are new in 2.0.
  // Derived with every extension a node installs LOADED, not just the ones a fresh CLI has: avro
  // and iceberg autoload on a node (autoinstall off, autoload on), and their readers open a
  // caller-named path like any core reader. LockdownTableFunctionsSpec pins the derivation: a table
  // function with a string first argument that no list here judges fails it.
  private[sql] val FileReadFunctions = Set(
    "read_text",
    "read_blob",
    "glob",
    "read_csv",
    "read_csv_auto",
    "read_single_csv_file",
    "sniff_csv",
    "read_parquet",
    "parquet_scan",
    "read_single_parquet_file",
    "parquet_metadata",
    "parquet_file_metadata",
    "parquet_full_metadata",
    "parquet_kv_metadata",
    "parquet_schema",
    "parquet_bloom_probe",
    "read_json",
    "read_json_auto",
    "read_json_objects",
    "read_json_objects_auto",
    "read_single_json_file",
    "read_ndjson",
    "read_ndjson_auto",
    "read_ndjson_objects",
    "read_duckdb",
    "read_postgres_binary",
    "ducklake_scan",
    "read_avro",
    "avro_metadata",
    "iceberg_scan",
    "iceberg_metadata",
    "iceberg_snapshots",
    "iceberg_column_stats",
    "iceberg_partition_stats",
    "iceberg_scan_plan",
    "iceberg_load_table_response"
  )

  // Functions that run raw SQL elsewhere, open a connection to a caller-chosen target, or read the
  // node's environment. Refused for every caller under lockdown here, and for tenant-scoped
  // principals when the ACL validates by CatalogReachingCalls, which owns the single list (postgres
  // / mysql / sqlite connection functions + getenv) so the two paths never drift.
  private val NodeEscapingFunctions = CatalogReachingCalls.LockdownNames

  // Lockdown-only denials with no first-argument path to exempt: enable_logging (storage_path) and
  // enable_profiling (save_location) write a file; ducklake_add_data_files registers a local Parquet
  // file named in its third argument; sql_auto_complete lists a local directory's contents from a
  // `FROM '<dir>/` prefix, the same capability as the denied glob. (postgres_configure_pool is in
  // NodeEscapingFunctions, refused for tenant principals under the ACL too.) The iceberg table
  // maintenance functions name an attached Iceberg table by a string and mostly rewrite it
  // (rollback, rewrite, property changes, conversion to DuckLake); quack_serve / quack_stop start
  // or stop a quack server on the node itself.
  private val NoPathFunctions = Set(
    "enable_logging",
    "enable_profiling",
    "ducklake_add_data_files",
    "sql_auto_complete",
    "iceberg_rewrite_data_files",
    "iceberg_rollback_to_snapshot",
    "iceberg_table_properties",
    "iceberg_schema_properties",
    "set_iceberg_table_properties",
    "set_iceberg_schema_properties",
    "remove_iceberg_table_properties",
    "remove_iceberg_schema_properties",
    "iceberg_to_ducklake",
    "quack_serve",
    "quack_stop"
  )

  private val DeniedFunctions = FileReadFunctions ++ NodeEscapingFunctions ++ NoPathFunctions

  // Table functions with a string-like first argument that lockdown ADMITS, each judged: none opens
  // a caller-named file or connection or runs caller SQL. Parse or tokenize text only
  // (check_peg_parser, sql_tokenize, json_each, json_tree); name a catalog object, not a file (the
  // pragma_* table introspection functions, gated by the ACL; checkpoint and force_checkpoint,
  // which CheckpointScreen and NodeSettingScreen.NodeWideCalls refuse to non-superusers in every
  // ACL mode and to every caller under lockdown; the ducklake_* catalog functions are admitted by
  // family below, except ducklake_scan and ducklake_add_data_files, denied above, and the
  // maintenance and option-setting ones CheckpointScreen.MaintenanceCalls refuses beside it;
  // duckdb_table_sample is denied through CatalogReachingCalls.LockdownNames); act on the client
  // side of an attached quack catalog (quack_cancel, quack_identify: this screen opens no file or
  // connection for them, but NodeSettingScreen.NodeWideCalls refuses both to non-superusers and to
  // every caller under lockdown); read the front door's own client stream
  // (scan_data_from_quack_client); match a path against secrets without opening it (which_secret);
  // take an arbitrary value (repeat, unnest, test_vector_types).
  private val AdmittedTableFunctions = Set(
    "check_peg_parser",
    "sql_tokenize",
    "json_each",
    "json_tree",
    "checkpoint",
    "force_checkpoint",
    "pragma_metadata_info",
    "pragma_show",
    "pragma_storage_info",
    "pragma_table_info",
    "quack_cancel",
    "quack_identify",
    "scan_data_from_quack_client",
    "which_secret",
    "repeat",
    "unnest",
    "test_vector_types"
  )

  /** Whether this screen has judged the table function `name`: denied (wholesale or over a local
    * path) or admitted on purpose. LockdownTableFunctionsSpec requires it of every table function
    * with a string-like first argument.
    */
  private[sql] def judged(name: String): Boolean =
    DeniedFunctions.contains(name) || AdmittedTableFunctions.contains(name) ||
      name.startsWith("ducklake_")

  // Only the file-read functions may exempt on a remote URL literal, and glob never does. Every
  // other denied function takes no path (or a DSN, not a path), so a remote literal must not admit
  // it.
  private val UrlExempt = FileReadFunctions - "glob"

  private val RemoteSchemes = List("s3://", "gs://", "az://", "r2://", "http://", "https://")

  private val FirstToken = "^\\s*([a-zA-Z_]+)".r

  // DuckDB 2.0 custom extension repositories: registering one is a code-loading path.
  private val ExtensionRepositoryDdl =
    "^(?:create(?:\\s+or\\s+replace)?|drop)\\s+extension\\s+repository\\b".r

  // Words after which DuckDB 2.0 reads a table name, beside FROM and JOIN, each verified on the CLI
  // to replacement-scan a literal there: SUMMARIZE / DESCRIBE / DESC / SHOW (`SHOW TABLE '<p>'`),
  // TABLE (`TABLE '<p>'`, `DESCRIBE TABLE`, `INSERT INTO t TABLE`, `CREATE TABLE .. AS TABLE`), the
  // PIVOT statements, and USING (`MERGE INTO t USING '<p>'`, `DELETE FROM t USING '<p>'`).
  private val TableKeywords = Set(
    "summarize",
    "describe",
    "desc",
    "show",
    "table",
    "pivot",
    "pivot_wider",
    "pivot_longer",
    "unpivot",
    "using"
  )

  // The PATH-position literal of a COPY statement: the token right after FROM or TO. Option literals
  // in the trailing `( ... )` (e.g. DELIMITER '|') are never in from/to position, so they are
  // ignored - a legit remote COPY with options is not over-denied.
  private val CopyPathLiteral = "(?<![a-zA-Z0-9_])(?:from|to)\\s*'([^']*)'".r

  // EXPORT DATABASE's target: the first literal after DATABASE (`EXPORT DATABASE '<dir>'`, or
  // `EXPORT DATABASE <db> TO '<dir>'`); its options come after it.
  private val ExportPathLiteral = "(?<![a-zA-Z0-9_])database\\b[^']*?'([^']*)'".r

  private val ExportLocalRefusal = "EXPORT DATABASE to a local path is disabled on this deployment"

  // The file extensions DuckDB 2.0's replacement scans recognize, used to spot a path-shaped name
  // that carries a separator: read_csv's .csv / .tsv, the json extension's .json / .jsonl /
  // .ndjson / .geojson / .geojsonl, parquet's .parquet, the core read_duckdb's .db / .ddb /
  // .duckdb, and the suffixes that autoload an extension (`EXTENSION_FILE_POSTFIXES`): .xlsx,
  // .avro, .shp, .gpkg, .fgb. Each scan also matches the extension followed by `?` anywhere in the
  // name. Every scan but read_duckdb's drops a trailing .gz or .zst first; dropping it for all of
  // them over-denies only a compressed DuckDB file name.
  private val DataExtensions =
    List(
      ".parquet",
      ".csv",
      ".tsv",
      ".json",
      ".jsonl",
      ".ndjson",
      ".geojson",
      ".geojsonl",
      ".db",
      ".ddb",
      ".duckdb",
      ".xlsx",
      ".avro",
      ".shp",
      ".gpkg",
      ".fgb"
    )

  private val CompressionSuffixes = List(".gz", ".zst")

  private val DriveLetter = "^[a-z]:[\\\\/]".r

  /** `deniedBuckets`: lowercased bucket/container keys holding DuckLake data (see
    * [[ai.starlake.quack.model.BucketKeys]]); a remote literal addressing one of them is denied
    * wherever the remote exemption would otherwise admit it (reads AND writes -- raw access
    * bypasses per-table grants and can corrupt catalog-referenced data files).
    */
  def screen(sql: String, deniedBuckets: Set[String]): Option[String] =
    screen(sql, deniedBuckets, FileAdmission.Closed)

  /** [[screen]] for a principal whose tenant authorized the locations `admission` admits: a file
    * reader call or a lone COPY naming only such locations is not denied here, whatever bucket it
    * addresses ([[FileLocations]]), since `admission` refuses every database data folder on whole
    * path segments (a DuckLake bucket is authorized, its data folders never are). Everything else
    * is judged as before.
    */
  def screen(sql: String, deniedBuckets: Set[String], admission: FileAdmission): Option[String] =
    val admittedCopy = FileLocations.copy(sql).exists(c => admission.admits(c.location))
    // First: on a DuckDB 1.x engine a backtick pair can hide a call from every check below. A
    // quoted name holding a dot is one identifier to DuckDB and a qualified name to every
    // parser-based check (DottedIdentifierScreen).
    BacktickScreen
      .find(sql)
      .orElse(DottedIdentifierScreen.find(sql))
      .orElse(
        splitStatements(sql).iterator
          .flatMap(screenOne(_, deniedBuckets, admission, admittedCopy))
          .nextOption()
      )
      .orElse(fileInTablePosition(sql, deniedBuckets, admission))

  /** A file DuckDB replacement-scans in a table position, in the tenant screen's two tiers
    * ([[FileReferences]]): when JSqlParser reads the whole batch, every file reference of the walk
    * (a string literal, or a name part holding a path separator or glob character), denied unless
    * it is a remote object-store URL outside the DuckLake buckets; a table position is then known,
    * so no path-shape heuristic is needed (`FROM 'x.csv'` reads the working directory). Otherwise
    * the conservative lexical scan: every literal right after a table word, a comma or such a `(`,
    * strictly (denied unless provably remote), and every quoted chain shaped like a URL, glob or
    * local path.
    */
  private def fileInTablePosition(
      sql: String,
      deniedBuckets: Set[String],
      admission: FileAdmission
  ): Option[String] =
    val extraction = FileReferences.extraction(FileLocations.extractable(sql, admission))
    val macroRead  = FileReferences
      .readingTableMacro(sql, extraction)
      .map(m =>
        s"$m reads the file or table its string argument names; disabled on this deployment"
      )
    if macroRead.isDefined then macroRead
    else if FileReferences.incomplete(extraction) then
      splitStatements(sql).iterator
        .flatMap { stmt =>
          conservativeLiterals(stmt).iterator
            .flatMap(
              _.flatMap(n => fromPathVerdict(n.toLowerCase(Locale.ROOT), true, deniedBuckets))
            )
            .nextOption()
            .orElse(
              quotedPathFrom(
                stmt,
                name =>
                  if FileReferences.pathShaped(name) then fromPathVerdict(name, true, deniedBuckets)
                  else None
              )
            )
        }
        .nextOption()
    else
      FileReferences
        .fromWalk(extraction)
        .iterator
        .flatMap(n => fromPathVerdict(n.toLowerCase(Locale.ROOT), true, deniedBuckets))
        .nextOption()

  private def screenOne(
      stmt: String,
      deniedBuckets: Set[String],
      admission: FileAdmission,
      admittedCopy: Boolean
  ): Option[String] =
    // Normalized ONLY for screening, in three passes, each closing a gap the previous one leaves
    // open. Every regex rule matches against `lower`; the token-based rules (function calls and
    // quoted identifiers, see the end of this comment) lex `stmt` instead. Neither rewrites what
    // is relayed: the caller sends the ORIGINAL text to the node.
    //   1. `SqlTrivia.stripLeading` DELETES leading whitespace/trivia and any leading comment. It
    //      is the only pass that deletes rather than rewrites, which is what `FirstToken`'s
    //      index-0 anchor needs: `SqlTrivia.normalize` turns a leading NBSP into an ASCII space
    //      that still sits at index 0, and `stripComments` does not touch it at all.
    //   2. `SqlCommentStripper.stripComments` then removes every remaining (INTERIOR) `--` / `/*
    //      */` comment, nesting and quoting handled the way DuckDB's own lexer handles them (see
    //      that object's scaladoc, where each property is tied to the statement that proves it).
    //      DuckDB treats a comment as a SEPARATOR, not a weld -- confirmed against a real DuckDB
    //      1.5.4, `SELECT * FROM/*x*/'/etc/passwd.parquet'` and `read_csv/*x*/('/etc/x.parquet')`
    //      both execute and return the target file's rows -- and a closed block comment is
    //      replaced by a single ASCII space rather than nothing. For `CopyPathLiteral`, which uses
    //      `\s*`, the space is not what makes it match: what matters is that the comment is GONE.
    //      (Replacement scans in FROM position are read from the lexer's tokens instead, see
    //      `conservativeLiterals`, where a comment is trivia like any space.)
    //   3. `SqlTrivia.normalize` collapses every remaining trivia character (unicode space /
    //      format, see `SqlTrivia.isTriviaSpace`) to an ASCII space across the whole statement,
    //      matching what DuckDB's own parser front end does before tokenizing. It runs LAST
    //      because pass 2 can re-expose a trivia character that sat next to a comment body.
    //
    // Case is folded LAST, after every lexing pass, never before: DuckDB compares dollar-quote tags
    // case-sensitively (`$A$` is not closed by `$a$`), so lowercasing first let the lexer end a
    // literal where DuckDB does not and read a denied call behind it as a comment.
    //
    // `Locale.ROOT` on the lowercasing, not the default locale: under `-Duser.language=tr`,
    // `"INSTALL".toLowerCase` is a dotless-i `install` (U+0131), which matches no key in
    // `DeniedFirstTokens` and admits INSTALL on a locked-down deployment.
    //
    // The function-call rule and the quoted-identifier rule do not read `lower`: they lex
    // `stmt` itself (see `FunctionCalls`), so no quoting form DuckDB 2.0 accepts for a name can hide
    // one from a regex, and only a call's argument list is normalized and folded afterwards.
    val lower = StatementHead.normalize(stmt)
    // The first-token rules judge the statement an EXPLAIN / PREPARE wrapper carries: EXPLAIN
    // ANALYZE executes it (SET, PRAGMA, RESET, COPY ... TO and LOAD verified on 2.0) and a prepared
    // COPY ... TO writes on EXECUTE, so the wrapper word itself says nothing.
    val head          = StatementHead.of(stmt)
    val headLower     = if head.start == 0 then lower else StatementHead.normalize(head.text)
    val first         = FirstToken.findFirstMatchIn(headLower).map(_.group(1))
    val repositoryHit =
      ExtensionRepositoryDdl
        .findFirstIn(headLower)
        .map(_ => "extension repositories are disabled on this deployment")
    first.flatMap(DeniedFirstTokens.get).orElse(repositoryHit) match
      case some @ Some(_) => some
      case None           =>
        val settingHit = first match
          case Some("set") | Some("reset") | Some("pragma") =>
            ProtectedSettings
              .find(s => settingTargeted(lower, s))
              .map(s => s"setting '$s' is protected on this deployment")
          case _ => None
        // A COPY statement reads/writes local files through its FROM/TO path literal; deny it
        // unless every string literal it carries is a remote object-store literal. The path is
        // judged as written and as DuckDB reads it (ParserInput: continued literals folded,
        // escape and dollar-quoted strings decoded to plain ones): `TO 's3://lakebu'<LF>'cket/x'`
        // writes to lakebucket, and `TO e'/tmp/x'` to a local file.
        val copyHit =
          if !first.contains("copy") || admittedCopy then None
          else
            copyLocalPath(lower, deniedBuckets).orElse(
              ParserInput
                .render(stmt)
                .toOption
                .flatMap(r => copyLocalPath(StatementHead.normalize(r.text), deniedBuckets))
            )
        // EXPORT DATABASE writes schema.sql, load.sql and one file per table to its target, as
        // COPY ... TO writes one (audit F23); judged the same way, as written and as DuckDB reads
        // it. A target neither reading finds is refused: nothing proves it remote.
        val exportHit =
          if !first.contains("export") then None
          else
            val verdicts = List(
              exportVerdict(lower, deniedBuckets),
              ParserInput
                .render(stmt)
                .toOption
                .flatMap(r => exportVerdict(StatementHead.normalize(r.text), deniedBuckets))
            ).flatten
            if verdicts.isEmpty then Some(ExportLocalRefusal)
            else
              verdicts.collectFirst {
                case LitVerdict.Bucket(b) => bucketMessage(b)
                case LitVerdict.Local     => ExportLocalRefusal
              }
        // Settings statements still get the function scan: a denied function inside a
        // SET value must not slip through. A file in table position (`FROM '/etc/x.parquet'`),
        // which carries no read-function call, is judged on the whole batch afterwards
        // (fileInTablePosition).
        settingHit
          .orElse(copyHit)
          .orElse(exportHit)
          .orElse(deniedFunctionIn(stmt, deniedBuckets, admission))

  /** [[StatementHead.split]], under the name this screen's callers use. */
  private[sql] def splitStatements(sql: String): List[String] = StatementHead.split(sql)

  /** The setting name as a standalone word after the SET/RESET/PRAGMA keyword. */
  private def settingTargeted(lower: String, setting: String): Boolean =
    ("\\b" + setting + "\\b").r.findFirstIn(lower).isDefined

  /** Per-literal safety verdict: Safe (remote, bucket allowed), Local (not provably a remote
    * literal), or Bucket (remote literal on a denied DuckLake bucket).
    */
  private enum LitVerdict:
    case Safe
    case Local
    case Bucket(name: String)

  /** A call of a denied function anywhere in the statement, read from the lexer's tokens by
    * [[FunctionCalls]]: the name bare, double-quoted or backtick-quoted (DuckDB 2.0 runs
    * `` `read_text`('/etc/hosts') ``), qualified or not, with any trivia before the paren, and
    * inside string literal bodies too (`query('...')` runs one). EVERY occurrence must pass the URL
    * exemption (remote literal on a non-denied bucket) or the statement is denied. The scan works
    * on the statement as lexed, never on folded text (dollar tags compare case-sensitively); each
    * argument list is then normalized and folded like the rest of this screen before it is judged.
    */
  private def deniedFunctionIn(
      stmt: String,
      deniedBuckets: Set[String],
      admission: FileAdmission
  ): Option[String] =
    val scan = FunctionCalls.scan(stmt)
    if scan.truncated then
      Some("string literals nested too deeply to screen are disabled on this deployment")
    else
      DeniedFunctions.iterator
        .flatMap { fn =>
          // A reader whose every location the tenant authorized is no local read to deny. The
          // admission reads each call from its own unfolded text: a local path compared after
          // folding could admit /SRV/QOD/ACME/IN/ for /srv/qod/acme/in/, another folder on a
          // case-sensitive filesystem.
          val occurrences =
            scan.calls.filter(c => c.name == fn && !FileLocations.admitted(c, scan, admission))
          if occurrences.isEmpty then None
          else if !UrlExempt.contains(fn) then Some(deniedCallMessage(fn))
          else
            val verdicts = occurrences
              .map(c => argsVerdict(screened(c.text.substring(c.argsFrom)), deniedBuckets))
            if verdicts.forall(_ == LitVerdict.Safe) then None
            else
              verdicts
                .collectFirst { case LitVerdict.Bucket(b) => bucketMessage(b) }
                .orElse(Some(deniedCallMessage(fn)))
        }
        .nextOption()

  /** The refusal for a denied call. A file-read function (in [[UrlExempt]]) is denied only for a
    * local path; everything else here takes no path (a connection string, a named storage path, or
    * nothing), so its message does not mention paths.
    */
  private def deniedCallMessage(fn: String): String =
    if UrlExempt.contains(fn) then s"$fn over local paths is disabled on this deployment"
    else s"$fn is disabled on this deployment"

  /** An argument list as the regex rules below read it: comments stripped, trivia normalized, case
    * folded, in that order (see `screenOne`). A suffix starting just past a `(` token lexes as it
    * did in place, since the paren sits outside every literal and comment.
    */
  private def screened(text: String): String =
    SqlTrivia.normalize(SqlCommentStripper.stripComments(text)).toLowerCase(Locale.ROOT)

  /** Verdict for the argument list `rest` (the text just past the open paren): Safe only
    * when every path-shaped argument is a string literal carrying a remote scheme on a non-denied
    * bucket. Handles a bare literal first argument (optionally followed by named args like `header
    * = true`), or a list-literal first argument (`['s3://..', 'gs://..']`) where every element must
    * be safe. Anything the scanner cannot prove safe (non-literal args, unbalanced brackets,
    * unterminated strings) answers Local (deny).
    */
  private def argsVerdict(rest: String, deniedBuckets: Set[String]): LitVerdict =
    // Boundaries come from the shared lexer's tokens, never indexOf: a `]` or `,` inside a string
    // literal is part of that literal's token, so it cannot cut a list early or split an element.
    val view = SqlLexer.engineView(rest)
    val toks = SqlLexer.tokens(view).filterNot(_.isTrivia)
    toks.headOption match
      case None                             => LitVerdict.Local
      case Some(h) if isPunct(view, h, '[') => listVerdict(view, toks, deniedBuckets)
      case Some(h)                          =>
        // Bare first argument: a single string literal, ended by a top-level comma or the closing
        // paren. Anything else as the first token (an operator, a bracket, a second token of a
        // concat) fails closed. `literalVerdict` re-checks that it is one TERMINATED literal.
        val endsArg =
          toks.lift(1).forall(n => isPunct(view, n, ',') || isPunct(view, n, ')'))
        if h.kind == SqlLexer.Kind.Literal && endsArg then
          literalVerdict(view.substring(h.start, h.end), deniedBuckets)
        else LitVerdict.Local

  /** A `[ 'lit' (, 'lit')* ]` list from the lexer tokens starting at the `[`: Safe only when it is
    * closed and every element is a single terminated remote literal on a non-denied bucket. Any
    * non-literal element, a missing close (an unterminated literal swallows the `]`), or anything
    * other than literals and top-level commas inside, is Local.
    */
  private def listVerdict(
      view: String,
      toks: Vector[SqlLexer.Token],
      deniedBuckets: Set[String]
  ): LitVerdict =
    val elems         = ListBuffer.empty[String]
    var i             = 1
    var expectLiteral = true
    var closed        = false
    var bad           = false
    while i < toks.length && !closed && !bad do
      val t = toks(i)
      if isPunct(view, t, ']') then closed = true
      else if expectLiteral then
        if t.kind == SqlLexer.Kind.Literal then
          elems += view.substring(t.start, t.end)
          expectLiteral = false
        else bad = true
      else if isPunct(view, t, ',') then expectLiteral = true
      else bad = true
      i += 1
    if bad || !closed || elems.isEmpty then LitVerdict.Local
    else
      val verdicts = elems.toList.map(e => literalVerdict(e, deniedBuckets))
      verdicts
        .collectFirst { case b: LitVerdict.Bucket => b }
        .getOrElse(
          if verdicts.forall(_ == LitVerdict.Safe) then LitVerdict.Safe else LitVerdict.Local
        )

  private def isPunct(view: String, t: SqlLexer.Token, c: Char): Boolean =
    t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == c

  private def literalVerdict(quoted: String, deniedBuckets: Set[String]): LitVerdict =
    // The argument must be exactly ONE string-literal token before the scheme test, so an
    // expression that merely starts and ends with a quote cannot pass: `'s3://x'[1:0] ||
    // '/proc/self/environ'` reads a local file on 2.0 and lexes as several tokens, so it is Local.
    if !isSingleStringLiteral(quoted) || !isRemoteLiteral(quoted) then LitVerdict.Local
    else
      deniedBucketOf(quoted, deniedBuckets) match
        case Some(b) => LitVerdict.Bucket(b)
        case None    => LitVerdict.Safe

  /** True when `s` lexes, under the shared DuckDB 2.0 lexer, as a single single-quoted string
    * literal that is TERMINATED and nothing else (`'it''s'` stays one token). Any operator,
    * bracket, concat or second token makes it false, so a computed argument never earns the remote
    * exemption; and an unterminated literal (`'http://h/f?x=''`, which the lexer runs to end of
    * input) is rejected, so a `]`-bearing remote literal cannot hide a second path after it.
    */
  private def isSingleStringLiteral(s: String): Boolean =
    val trimmed = s.trim
    if trimmed.isEmpty || trimmed.charAt(0) != '\'' then false
    else
      def nonTrivia(x: String): Vector[SqlLexer.Token] =
        SqlLexer.tokens(SqlLexer.engineView(x)).filterNot(_.isTrivia)
      val base = nonTrivia(trimmed)
      // Appending a sentinel word reveals termination: a terminated literal yields [Literal, Word]
      // ending at the same offset; an unterminated one swallows the sentinel and stays one token.
      val probed = nonTrivia(trimmed + " x")
      base.length == 1 && base.head.kind == SqlLexer.Kind.Literal &&
      probed.length == 2 && probed.head.kind == SqlLexer.Kind.Literal &&
      probed.head.end == base.head.end

  /** The denied bucket a remote literal addresses, if any. Object-store schemes match on the
    * BucketKeys authority; http(s) literals match a denied bucket appearing as the first path
    * segment (path-style `https://endpoint/B/key`) or the leading host label (virtual-host
    * `https://B.endpoint/key`) -- insurance against endpoint-form addressing, over-deny accepted.
    */
  private def deniedBucketOf(quoted: String, deniedBuckets: Set[String]): Option[String] =
    if deniedBuckets.isEmpty then None
    else
      val inner = quoted.stripPrefix("'").stripSuffix("'")
      if inner.startsWith("http://") || inner.startsWith("https://") then
        val rest      = inner.substring(inner.indexOf("://") + 3)
        val authority = rest.takeWhile(_ != '/')
        val hostLabel = authority.takeWhile(c => c != '.' && c != ':')
        val firstSeg  = rest.drop(authority.length).stripPrefix("/").takeWhile(_ != '/')
        if deniedBuckets.contains(hostLabel) then Some(hostLabel)
        else if deniedBuckets.contains(firstSeg) then Some(firstSeg)
        else None
      else BucketKeys.of(inner).filter(deniedBuckets.contains)

  private def bucketMessage(bucket: String): String =
    s"bucket '$bucket' holds DuckLake-managed data and is not directly addressable on this deployment"

  private def isRemoteLiteral(s: String): Boolean =
    s.startsWith("'") && s.endsWith("'") && s.length >= 2 &&
      RemoteSchemes.exists(sch => s.substring(1).startsWith(sch))

  /** A COPY statement is denied when its PATH-position literal (the token right after FROM or TO)
    * is NOT a remote object-store literal, or is a remote literal on a denied DuckLake bucket
    * (either direction: FROM is the raw-read bypass, TO the corruption path). Only the path
    * position is tested, so option literals in the trailing `( ... )` (e.g. DELIMITER '|') do not
    * over-deny a legit remote COPY. A COPY between tables (no path literal) is admitted. Fail
    * closed: any non-remote path-position literal denies.
    */
  private def copyLocalPath(lower: String, deniedBuckets: Set[String]): Option[String] =
    CopyPathLiteral
      .findAllMatchIn(lower)
      .map(m => "'" + m.group(1) + "'")
      .flatMap { lit =>
        literalVerdict(lit, deniedBuckets) match
          case LitVerdict.Safe      => None
          case LitVerdict.Bucket(b) => Some(bucketMessage(b))
          case LitVerdict.Local     => Some("COPY over local paths is disabled on this deployment")
      }
      .nextOption()

  /** EXPORT DATABASE's target literal judged like a COPY path, or None when there is none. */
  private def exportVerdict(lower: String, deniedBuckets: Set[String]): Option[LitVerdict] =
    ExportPathLiteral
      .findFirstMatchIn(lower)
      .map(m => literalVerdict("'" + m.group(1) + "'", deniedBuckets))

  /** The string literals of `stmt` in a conservative table position, each with the values it stands
    * for (its own body, and the value DuckDB folds it into across a newline): right after FROM,
    * JOIN or one of [[TableKeywords]] (SUMMARIZE, DESCRIBE, TABLE, PIVOT, USING, ...), after any
    * comma, or after a `(` itself in such a position, at any depth. No notion of where a FROM list
    * ends: a keyword used as a name (`FROM range(1) AS values, '/etc/x.csv'`) ended the list for
    * the scan this replaces, and the file after the next comma went unjudged. It over-approximates
    * on purpose (`ORDER BY a, 'x'`, `x IS DISTINCT FROM 'y'`), so it runs only on a statement
    * JSqlParser cannot read ([[FileReferences]]). Every quoting form counts, read from the lexer's
    * tokens; a non-plain literal (escape, dollar-quoted, prefixed) keeps its raw body.
    */
  private[sql] def conservativeLiterals(stmt: String): List[List[String]] =
    val view                                    = SqlLexer.engineView(stmt)
    val toks                                    = SqlLexer.tokens(view)
    def text(t: SqlLexer.Token): String         = view.substring(t.start, t.end)
    def word(t: SqlLexer.Token): Option[String] =
      if t.kind == SqlLexer.Kind.Word then Some(text(t).toLowerCase(Locale.ROOT)) else None
    def isString(t: SqlLexer.Token): Boolean =
      t.kind == SqlLexer.Kind.Literal && view.charAt(t.start) != '"' &&
        view.charAt(t.start) != '`'
    val found = ListBuffer.empty[List[String]]
    var opens = false // the previous significant token opens a table position
    var i     = 0
    while i < toks.length do
      val t = toks(i)
      if t.isTrivia then i += 1
      else if isString(t) && opens then
        val (parts, next) = continued(view, toks, i)
        found += (if parts.size > 1 then parts :+ parts.mkString else parts)
        opens = false
        i = next
      else
        opens = word(t).exists(w => w == "from" || w == "join" || TableKeywords(w)) ||
          isPunct(view, t, ',') || (isPunct(view, t, '(') && opens)
        i += 1
    found.toList

  /** The body of the string literal at token `i` and of every plain literal DuckDB folds into it (a
    * newline and no block comment in between), with the index after the last one. A plain body has
    * its doubled quotes undone; any other form keeps its raw body (it is judged strictly).
    */
  private def continued(
      view: String,
      toks: Vector[SqlLexer.Token],
      i: Int
  ): (List[String], Int) =
    def body(t: SqlLexer.Token): String =
      val s = view.substring(t.start, t.end)
      if s.startsWith("'") then s.stripPrefix("'").stripSuffix("'").replace("''", "'")
      else if s.startsWith("$") then
        val tag = s.substring(0, s.indexOf('$', 1) + 1)
        s.substring(tag.length, math.max(tag.length, s.length - tag.length))
      else s.drop(2).stripSuffix("'")
    val parts = ListBuffer(body(toks(i)))
    var next  = i + 1
    var more  = view.charAt(toks(i).start) != '$'
    while more do
      var k       = next
      var newline = false
      var block   = false
      while k < toks.length && toks(k).isTrivia do
        if toks(k).kind == SqlLexer.Kind.BlockComment then block = true
        else if toks(k).kind == SqlLexer.Kind.Space then
          newline = newline || view
            .substring(toks(k).start, toks(k).end)
            .exists(c => c == '\n' || c == '\r')
        k += 1
      if k < toks.length && newline && !block && toks(k).kind == SqlLexer.Kind.Literal &&
        view.charAt(toks(k).start) == '\''
      then
        parts += body(toks(k))
        next = k + 1
      else more = false
    (parts.toList, next)

  /** The verdict on a name DuckDB may replacement-scan as a file: a denied bucket, else admitted
    * when remote, else denied when `strict` or when it looks like a local path.
    */
  private def fromPathVerdict(
      inner: String,
      strict: Boolean,
      deniedBuckets: Set[String]
  ): Option[String] =
    val lit = "'" + inner + "'"
    deniedBucketOf(lit, deniedBuckets) match
      case Some(b) => Some(bucketMessage(b))
      case None    =>
        if isRemoteLiteral(lit) then None
        else if strict || looksLikePath(lit) then
          Some("reading local files in FROM position is disabled on this deployment")
        else None

  /** Denies a QUOTED IDENTIFIER replacement scan of a local path: DuckDB 2.0 replacement-scans a
    * table name it cannot resolve as a file, so `FROM "/etc/x.csv"` and `` FROM `/etc/x.csv` ``
    * read the file exactly as `FROM '/etc/x.csv'` does, and a qualified name is joined with dots
    * first (`"/etc/x"."csv"` reads `/etc/x.csv`). A table name sits in many more places than after
    * FROM or JOIN: `FROM ("/a/t.csv")`, a comma join, `SUMMARIZE`, `DESCRIBE` and `PIVOT` each read
    * the file too (each verified on the 2.0 CLI). So EVERY quoted identifier is judged, read from
    * the lexer's tokens at any depth, a qualified name (any of whose parts is quoted) joined with
    * dots: an identifier shaped like a local file path is not a shape ordinary SQL takes, unlike
    * the `trim(leading '/' from '/a')` literal that a table-position scan must tell apart. The name
    * gets the plain literal's verdict (remote exemption, denied buckets, the path-shape heuristic),
    * so `"orders"`, `"main"."orders"` and a remote name stay admitted. Identifiers inside a string
    * literal body are not judged: a literal holding a JSON document with a path value is ordinary
    * SQL. `judge` gives the verdict on each joined name ([[FileAccessScreen]] passes its own).
    */
  private[sql] def quotedPathFrom(stmt: String, judge: String => Option[String]): Option[String] =
    val view  = SqlLexer.engineView(stmt)
    val toks  = SqlLexer.tokens(view).filterNot(_.isTrivia)
    val names = ListBuffer.empty[String]
    var i     = 0
    while i < toks.length do
      // the identifier chain starting at token i: `part (. part)*`
      val parts  = ListBuffer.empty[String]
      var quoted = false
      var j      = i
      var more   = true
      while more && j < toks.length do
        FunctionCalls.identifier(view, toks(j)) match
          case Some(part) =>
            parts += part
            quoted = quoted || FunctionCalls.quotedIdentifier(view, toks(j)).isDefined
            val dot = j + 1 < toks.length && toks(j + 1).kind == SqlLexer.Kind.Number &&
              view.substring(toks(j + 1).start, toks(j + 1).end) == "."
            j += (if dot then 2 else 1)
            more = dot
          case None => more = false
      if quoted then names += parts.mkString(".")
      i = math.max(j, i + 1)
    names.iterator.flatMap(judge).nextOption()

  /** True when the (quoted) literal looks like a local filesystem path: it starts with an absolute
    * or relative path prefix or a drive letter, or it contains a path separator AND carries a data
    * file extension a replacement scan recognizes (see `DataExtensions`). Ordinary values like
    * 'a/b' (no data extension) do not match.
    */
  private[sql] def looksLikePath(quoted: String): Boolean =
    val s = quoted.stripPrefix("'").stripSuffix("'")
    s.startsWith("/") || s.startsWith("./") || s.startsWith("../") ||
    DriveLetter.findFirstIn(s).isDefined ||
    ((s.contains("/") || s.contains("\\")) && hasDataExtension(s))

  /** `s` names a file a replacement scan reads by its extension: one of `DataExtensions` at the end
    * (a trailing compression suffix dropped first) or followed by `?` anywhere, as DuckDB's
    * `ReplacementScan::CanReplace` matches it.
    */
  private def hasDataExtension(s: String): Boolean =
    val base = CompressionSuffixes.find(s.endsWith).fold(s)(c => s.dropRight(c.length))
    DataExtensions.exists(e => base.endsWith(e) || s.contains(e + "?"))
