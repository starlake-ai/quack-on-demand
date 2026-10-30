package ai.starlake.quack.edge.sql

import ai.starlake.quack.edge.policy.CatalogReachingCalls
import ai.starlake.quack.ondemand.InProcessDuckDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.io.Source

/** Pins the derivation of [[FileAccessScreen.FileFunctions]]: every DuckDB 2.0 table function, with
  * every extension a node loads, must be judged for tenant principals, either refused (a file
  * reader, a catalog-reaching or node-escaping call, a node-wide or credential-exposing call) or
  * admitted on purpose ([[FileAccessScreen.NoFileFunctions]]). A function nobody judged is how
  * `sql_auto_complete` (a directory listing) and `shell_history` (the node's init SQL) went
  * unscreened: a new 2.0 table function fails this spec until someone decides.
  */
class FileAccessFunctionsSpec extends AnyFlatSpec with Matchers:

  private def unjudged(names: Iterable[String]): List[String] =
    names.filterNot(FileAccessScreen.judged).toList.distinct.sorted

  "the 2.0 CLI's table functions, every node extension loaded" should "all be judged" in {
    val src   = Source.fromResource("lockdown/duckdb-2.0-table-functions.csv")
    val names =
      try src.getLines().filterNot(l => l.startsWith("#") || l.isBlank).map(_.split(',')(0)).toList
      finally src.close()
    names should contain allOf ("sql_auto_complete", "shell_history", "read_csv")
    unjudged(names) shouldBe Nil
  }

  "the bundled DuckDB's own table functions" should "all be judged" in {
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    try
      val st = conn.createStatement()
      val rs = st.executeQuery(
        "SELECT DISTINCT function_name FROM duckdb_functions() " +
          "WHERE function_type IN ('table', 'table_macro')"
      )
      val names =
        Iterator.continually(rs.next()).takeWhile(identity).map(_ => rs.getString(1)).toList
      rs.close()
      st.close()
      names should not be empty
      unjudged(names) shouldBe Nil
    finally conn.close()
  }

  "sql_auto_complete" should "be refused: it lists node directories" in {
    FileAccessScreen.FileFunctions should contain("sql_auto_complete")
    FileAccessScreen.find("SELECT * FROM sql_auto_complete('SELECT * FROM ''/etc/ho')") shouldBe
      defined
  }

/** The node-state judgment of every DuckDB 2.0 introspection table function and system view: what a
  * tenant principal learns from it, and the verdict. "refused" entries must be exactly
  * [[NodeStateScreen.Refused]]; every introspection function the file screen admits
  * ([[FileAccessScreen.NoFileFunctions]]) and every default system view
  * (`TableExtractor.DuckDbDefaultViews`) must be judged here, so the list stays exhaustive. Read
  * off the 2.0 CLI with every node extension loaded (DESCRIBE of each, a secret and an attached
  * file present).
  */
class NodeStateJudgmentSpec extends AnyFlatSpec with Matchers:

  private def refused(why: String)  = ("refused", why)
  private def admitted(why: String) = ("admitted", why)

  private val Judgments: Map[String, (String, String)] = Map(
    // credentials, their names, node identity
    "duckdb_secrets" -> refused("name, scope, key_id, Postgres host / user / db, DuckLake paths"),
    "which_secret"   -> refused("names the secret serving a path"),
    "whoami"         -> refused("node cloud identity, hostname, region, uptime"),
    // setting values (s3_secret_access_key, http_proxy_password, directories: unredacted on 2.0)
    "duckdb_settings" -> refused("every setting value, credentials and directories included"),
    "pg_settings"     -> refused("view over duckdb_settings"),
    // other sessions and node files
    "duckdb_logs"                   -> refused("other sessions' logged statements"),
    "duckdb_logs_parsed"            -> refused("other sessions' logged statements"),
    "duckdb_log_contexts"           -> refused("connection, transaction and query ids"),
    "duckdb_external_file_cache"    -> refused("path of every file any session read"),
    "duckdb_temporary_files"        -> refused("spill file paths"),
    "duckdb_databases"              -> refused("attached database paths and options"),
    "pragma_database_list"          -> refused("attached database paths"),
    "duckdb_extensions"             -> refused("install paths, origins, key fingerprints"),
    "duckdb_extension_repositories" -> refused("repository locations and public keys"),
    "duckdb_external_resources"     -> refused("resource URIs, references and handles"),
    "pragma_storage_info" -> refused("segment statistics (min / max) of a string-named table"),
    // the caller's own session
    "duckdb_variables"           -> admitted("the caller's own session variables"),
    "duckdb_prepared_statements" -> admitted("the caller's own prepared statements"),
    "pg_prepared_statements"     -> admitted("view over duckdb_prepared_statements"),
    "duckdb_profiling_settings" -> refused("table macro over duckdb_settings (judged by its body)"),
    // aggregates naming nothing
    "duckdb_memory"                -> admitted("byte counts per buffer tag"),
    "duckdb_connection_count"      -> admitted("a count of connections, no ids"),
    "duckdb_approx_database_count" -> admitted("a count"),
    "duckdb_eviction_queues"       -> admitted("buffer eviction counters"),
    "pragma_database_size"         -> admitted("sizes and block counts of the attached databases"),
    "pragma_metadata_info"         -> admitted("block ids of the default database"),
    // static catalogs of DuckDB itself
    "duckdb_available_metrics"       -> admitted("static metric list"),
    "duckdb_dialects"                -> admitted("static dialect list"),
    "duckdb_external_resource_types" -> admitted("static resource type list"),
    "duckdb_functions"          -> admitted("function catalog (macros of attached catalogs too)"),
    "duckdb_grammar_extensions" -> admitted("static grammar extension list"),
    "duckdb_keywords"           -> admitted("static keyword list"),
    "duckdb_optimizers"         -> admitted("static optimizer list"),
    "duckdb_secret_type_parameters" -> admitted("static parameter list of secret types"),
    "duckdb_secret_types"           -> admitted("static secret type list"),
    "duckdb_types"                  -> admitted("type catalog"),
    "pragma_collations"             -> admitted("static collation list"),
    "pragma_platform"               -> admitted("platform string"),
    "pragma_user_agent"             -> admitted("DuckDB user agent"),
    "pragma_version"                -> admitted("DuckDB version"),
    "icu_calendar_names"            -> admitted("static calendar list"),
    "pg_timezone_names"             -> admitted("static time zone list"),
    "tpch_answers"                  -> admitted("static TPC-H text"),
    "tpch_queries"                  -> admitted("static TPC-H text"),
    // catalog metadata: gated as before by the ACL and the metadata filter
    "duckdb_columns"            -> admitted("catalog metadata, metadata filter"),
    "duckdb_tables"             -> admitted("catalog metadata, metadata filter"),
    "duckdb_views"              -> admitted("catalog metadata, metadata filter"),
    "duckdb_schemas"            -> admitted("catalog metadata, metadata filter"),
    "duckdb_constraints"        -> admitted("catalog metadata, ACL-gated as a system view"),
    "duckdb_dependencies"       -> admitted("catalog metadata, ACL-gated"),
    "duckdb_indexes"            -> admitted("catalog metadata, ACL-gated"),
    "duckdb_sequences"          -> admitted("catalog metadata, ACL-gated"),
    "duckdb_triggers"           -> admitted("catalog metadata, ACL-gated"),
    "duckdb_coordinate_systems" -> admitted("catalog metadata (CRS per schema), ACL-gated"),
    "pragma_table_info"         -> admitted("columns of a named table, ACL-gated table function"),
    "pragma_show"               -> admitted("columns of a named table, ACL-gated table function"),
    // generators, tokenizers, scans of an argument, test tables
    "arrow_scan"       -> admitted("Arrow pointer"),
    "arrow_scan_dumb"  -> admitted("Arrow pointer"),
    "check_peg_parser" -> admitted("parses its argument"),
    "sql_tokenize"     -> admitted("tokenizes its argument"),
    "json_each"        -> admitted("walks its JSON argument"),
    "json_tree"        -> admitted("walks its JSON argument"),
    "generate_series"  -> admitted("generator"),
    "range"            -> admitted("generator"),
    "repeat"           -> admitted("generator"),
    "repeat_row"       -> admitted("generator"),
    "unnest"           -> admitted("unnests its argument"),
    "summary"  -> admitted("summarizes its TABLE input; a string argument is a constant (2.0 CLI)"),
    "seq_scan" -> admitted("test scan"),
    "test_all_types"              -> admitted("static test table"),
    "test_vector_types"           -> admitted("test table of its argument"),
    "checkpoint"                  -> admitted("checkpoints a catalog, no output state"),
    "iceberg_scan_tasks"          -> admitted("tasks of a scan plan read by a refused reader"),
    "scan_data_from_quack_client" -> admitted("the front door client's own stream")
  )

  private val views = ai.starlake.acl.parser.TableExtractor.DuckDbDefaultViews

  "the node-state judgments" should "refuse exactly what NodeStateScreen refuses" in {
    Judgments.collect { case (n, ("refused", _)) =>
      n
    }.toSet shouldBe NodeStateScreen.Refused.keySet
  }

  it should "cover every introspection function the file screen admits" in {
    (FileAccessScreen.NoFileFunctions -- Judgments.keySet) shouldBe empty
  }

  it should "cover every node-level system view" in {
    // the remaining default views (sqlite_*, pg_* catalog views) are catalog metadata, gated as a
    // bare system view by the ACL
    (views.filter(v =>
      v.startsWith("duckdb_") || v.startsWith("pragma_") || v == "pg_settings" ||
        v == "pg_prepared_statements"
    ) -- Judgments.keySet) shouldBe empty
  }

  it should "hold for every spelling NodeStateScreen reads" in
    Judgments.foreach { case (name, (verdict, _)) =>
      val forms =
        List(s"SELECT * FROM $name()", s"SELECT * FROM $name", s"SELECT * FROM \"$name\"()")
      forms.foreach { sql =>
        withClue(sql)(NodeStateScreen.find(sql).isDefined shouldBe (verdict == "refused"))
      }
    }

  "NodeStateScreen" should "refuse the PRAGMA forms, any quoting, and literal bodies" in {
    List(
      "PRAGMA database_list",
      "PRAGMA storage_info('customer')",
      "PRAGMA extension_versions",
      "SELECT * FROM system.main.duckdb_secrets()",
      "SELECT * FROM `duckdb_settings`()",
      "SELECT name, value FROM duckdb_settings WHERE name LIKE 's3%'",
      "SELECT * FROM query('SELECT * FROM duckdb_secrets()')",
      "SELECT 1; SELECT * FROM whoami()",
      "SELECT current_setting('s3_secret_access_key')",
      "SELECT current_setting('s3_' || 'secret_access_key')",
      "SELECT current_setting(name) FROM t",
      "SELECT current_setting('TimeZone', 'x')"
    ).foreach(sql => withClue(sql)(NodeStateScreen.find(sql) shouldBe defined))
    List(
      "SELECT current_setting('TimeZone')",
      "SELECT current_setting( 'search_path' )",
      "SELECT * FROM duckdb_memory()",
      "PRAGMA table_info('orders')",
      "PRAGMA version",
      "SELECT * FROM orders"
    ).foreach(sql => withClue(sql)(NodeStateScreen.find(sql) shouldBe None))
  }

/** Every DuckDB 2.0 macro is judged by its body, not its name: the edge sees `histogram('x', b)`,
  * never the `query_table(source)` the macro runs. The pinned derivation
  * (`lockdown/duckdb-2.0-macros.csv`: each macro of the 2.0 CLI with every node extension loaded,
  * and the functions its body calls) is checked against the bundled 2.0 DuckDB, so a new or changed
  * core macro fails here until someone judges it. A table macro whose body reaches a refused
  * function, directly or through another macro, must itself be refused; a scalar macro may reach
  * none (none of 2.0's opens a file or runs a query: their bodies read the catalog introspection
  * functions or compute values).
  */
class MacroJudgmentSpec extends AnyFlatSpec with Matchers:
  import PinnedMacros.{callsOf, pinned, reachable, refusedByScreens}

  "every 2.0 table macro" should "be refused when its body reaches a refused function" in {
    val tableMacros = pinned.keySet.collect { case ("table_macro", n) => n }
    tableMacros should contain allOf ("histogram", "histogram_values", "whoami")
    tableMacros.foreach { m =>
      withClue(m) {
        refusedByScreens(m) shouldBe reachable(m).exists(c => c != m && refusedByScreens(c))
      }
    }
  }

  // Through every pinned macro, scalar and table: a scalar macro calling `histogram_values` in a
  // subquery, or `pg_get_viewdef`, reaches a refused function and must be refused itself.
  "every 2.0 scalar macro" should "be refused when its body reaches a refused function" in
    pinned.keySet.collect { case ("macro", n) => n }.foreach { n =>
      withClue(n) {
        val reached = reachable(n).filter(c => c != n && refusedByScreens(c))
        if reached.nonEmpty then refusedByScreens(n) shouldBe true
      }
    }

  "the refused-name derivation" should "read the screens' own verdicts" in {
    List(
      "query_table",
      "read_csv",
      "histogram",
      "histogram_values",
      "pg_get_viewdef",
      "pg_get_constraintdef",
      "duckdb_settings",
      "current_setting",
      "shell_history",
      "dbgen",
      "ducklake_table_insertions"
    ).foreach(n => withClue(n)(refusedByScreens(n) shouldBe true))
    List("range", "unnest", "format_type", "lower", "duckdb_tables", "when")
      .foreach(n => withClue(n)(refusedByScreens(n) shouldBe false))
  }

  it should "read a quoted call and a space before the paren" in {
    callsOf("SELECT \"nullif\"(a, '') FROM query_table (x)") shouldBe Set("nullif", "query_table")
  }

  "the bundled 2.0 DuckDB's macros" should "all be pinned, bodies unchanged" in {
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    try
      val st = conn.createStatement()
      val rs = st.executeQuery(
        "SELECT function_type, function_name, macro_definition FROM duckdb_functions() " +
          "WHERE function_type IN ('macro', 'table_macro')"
      )
      val live = Iterator
        .continually(rs.next())
        .takeWhile(identity)
        .map(_ => (rs.getString(1), rs.getString(2)) -> callsOf(rs.getString(3)))
        .toList
        .groupMapReduce(_._1)(_._2)(_ ++ _)
      rs.close()
      st.close()
      live should not be empty
      live.foreach { (key, calls) =>
        withClue(key)(pinned.get(key) shouldBe Some(calls))
      }
    finally conn.close()
  }

/** The pinned 2.0 macro bodies (`lockdown/duckdb-2.0-macros.csv`) and the screens' verdict on a
  * function name, shared by the macro judgments.
  */
private[sql] object PinnedMacros:

  /** The functions a macro body calls, read by [[FunctionCalls]], the lexer the screens use: a
    * quoted name (`"nullif"(`) and a space before the paren count.
    */
  def callsOf(body: String): Set[String] =
    FunctionCalls.scan(Option(body).getOrElse("")).calls.map(_.name).toSet

  val pinned: Map[(String, String), Set[String]] =
    val src = Source.fromResource("lockdown/duckdb-2.0-macros.csv")
    try
      src
        .getLines()
        .filterNot(l => l.startsWith("#") || l.isBlank)
        .map { line =>
          val cols = line.split(',')
          (cols(0), cols(1)) -> (if cols(2) == "-" then Set.empty[String]
                                 else cols(2).split('|').toSet)
        }
        .toMap
    finally src.close()

  private val bodies: Map[String, Set[String]] =
    pinned.toList.groupMapReduce(_._1._2)(_._2)(_ ++ _)

  /** Every function reachable from `name`'s body, through every pinned macro, scalar or table. */
  def reachable(name: String): Set[String] =
    @annotation.tailrec
    def loop(todo: List[String], seen: Set[String]): Set[String] =
      todo match
        case Nil       => seen
        case n :: rest =>
          val next = bodies.getOrElse(n, Set.empty) -- seen
          loop(next.toList ::: rest, seen ++ next)
    loop(List(name), Set.empty)

  /** Whether the tenant screens refuse a call of `n`, in a table position or a select list: read
    * off the screens themselves, so a name they add is refused here too.
    */
  def refusedByScreens(n: String): Boolean =
    val forms = List(s"SELECT * FROM $n()", s"SELECT $n()")
    forms.exists { sql =>
      FileAccessScreen.find(sql).isDefined || CatalogReachingCalls.find(sql).isDefined ||
      NodeStateScreen.find(sql).isDefined || NodeSettingScreen.screen(sql).isDefined ||
      NodeSettingScreen.screenNodeWide(sql).isDefined ||
      CatalogDefinitionCalls.markers(sql).nonEmpty
    }
