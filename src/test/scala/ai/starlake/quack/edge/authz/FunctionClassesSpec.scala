package ai.starlake.quack.edge.authz

import ai.starlake.acl.parser.TableExtractor
import io.circe.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.util.Locale
import scala.jdk.CollectionConverters.*

/** The function classification against what qod-node resolves (native/qod-node/functions.json, from
  * scripts/dev/gen-qod-node-classification.sh): the bundled table classifies exactly those
  * functions, so a DuckDB upgrade cannot land with a function nobody classified, and the lists the
  * node embeds say what the table says. The pins are judgments a regeneration must not change
  * silently, each beside the nearest function that must stay refused.
  */
class FunctionClassesSpec extends AnyFlatSpec with Matchers:
  import FunctionClass.*

  private def lower(s: String): String = s.toLowerCase(Locale.ROOT)

  /** Every function functions.json lists, keyed as the table keys it. */
  private lazy val resolved: Set[FunctionClasses.Key] =
    val json = parser
      .parse(Files.readString(Paths.get("native/qod-node/functions.json")))
      .fold(e => fail(s"functions.json: ${e.getMessage}"), identity)
    json.asArray
      .getOrElse(fail("functions.json is not an array"))
      .map { f =>
        val c                = f.hcursor
        def field(k: String) = c.get[String](k).fold(e => fail(s"functions.json $k: $e"), lower)
        FunctionClasses.Key(field("schema"), field("name"), field("type"))
      }
      .toSet

  private def lines(path: String): List[String] =
    Files.readAllLines(Paths.get(path)).asScala.toList.filter(_.nonEmpty)

  private def tableNames(classes: Set[FunctionClass]): Set[String] =
    FunctionClasses.all.collect { case (k, c) if k.tpe == "table" && classes(c) => k.name }.toSet

  private def tableClass(name: String): Option[FunctionClass] =
    FunctionClasses.of("system", "main", name, "table")

  /** Every scalar, aggregate and window function that is not Allowed, by (name, type): a hand edit
    * of one to allowed fails, and so does a regeneration that refuses a new one, until it is
    * reviewed.
    */
  private val ExpectedRestrictedScalar = Set(
    "current_setting",
    "duckdb_aws_session_id",
    "duckdb_format_sql",
    "index_key",
    "json_serialize_plan",
    "postgres_close_pinned_connection",
    "postgres_pin_connection",
    "qod_screen",
    "quack_check_token",
    "quack_connection_id",
    "quack_nop_authorization",
    "quack_uri_parser",
    "setval",
    "write_log"
  ).map(_ -> "scalar")

  private val ExpectedFile = Set(
    "avro_metadata",
    "glob",
    "parquet_bloom_probe",
    "parquet_file_metadata",
    "parquet_full_metadata",
    "parquet_kv_metadata",
    "parquet_metadata",
    "parquet_scan",
    "parquet_schema",
    "read_avro",
    "read_blob",
    "read_csv",
    "read_csv_auto",
    "read_duckdb",
    "read_json",
    "read_json_auto",
    "read_json_objects",
    "read_json_objects_auto",
    "read_ndjson",
    "read_ndjson_auto",
    "read_ndjson_objects",
    "read_parquet",
    "read_postgres_binary",
    "read_single_csv_file",
    "read_single_json_file",
    "read_single_parquet_file",
    "read_text",
    "sniff_csv"
  )

  private val ExpectedAdmittedTable = TableExtractor.DuckDbCatalogFunctions ++ Set(
    "check_peg_parser",
    "duckdb_available_metrics",
    "duckdb_coordinate_systems",
    "duckdb_dialects",
    "duckdb_external_resource_types",
    "duckdb_grammar_extensions",
    "duckdb_keywords",
    "duckdb_optimizers",
    "duckdb_prepared_statements",
    "duckdb_secret_type_parameters",
    "duckdb_secret_types",
    "duckdb_variables",
    "ducklake_set_commit_message",
    "generate_series",
    "icu_calendar_names",
    "json_each",
    "json_tree",
    "pg_timezone_names",
    "pragma_collations",
    "pragma_platform",
    "pragma_user_agent",
    "pragma_version",
    "qod_session",
    "query",
    "query_table",
    "range",
    "repeat",
    "repeat_row",
    "scan_data_from_quack_client",
    "sql_tokenize",
    "summary",
    "test_all_types",
    "test_vector_types",
    "unnest"
  )

  private def isMacro(k: FunctionClasses.Key): Boolean = FunctionClasses.MacroTypes(k.tpe)

  /** Every built-in macro and table macro overload native/qod-node/macros.json lists (from
    * scripts/dev/gen-qod-node-classification.sh), keyed as the table keys it.
    */
  private lazy val macros: Set[FunctionClasses.Key] =
    val json = parser
      .parse(Files.readString(Paths.get("native/qod-node/macros.json")))
      .fold(e => fail(s"macros.json: ${e.getMessage}"), identity)
    json.asArray
      .getOrElse(fail("macros.json is not an array"))
      .map { m =>
        val c                = m.hcursor
        def field(k: String) = c.get[String](k).fold(e => fail(s"macros.json $k: $e"), lower)
        FunctionClasses.Key(field("schema"), field("name"), field("type"))
      }
      .toSet

  "the function classification" should "classify exactly the functions qod-node resolves" in {
    val classified = FunctionClasses.all.keySet.filterNot(isMacro)
    withClue(
      "in functions.json, not classified (run scripts/dev/gen-qod-node-classification.sh): "
    )(
      (resolved -- classified).toList.map(_.toString).sorted.take(20) shouldBe Nil
    )
    withClue("classified, not in functions.json: ")(
      (classified -- resolved).toList.map(_.toString).sorted.take(20) shouldBe Nil
    )
  }

  it should "keep the bundled table sorted, one row per function" in {
    val rows = ClassificationTables.rows(ClassificationTables.read(FunctionClasses.Resource), 4)
    val keys = rows.map { case (_, r) => (r(0), r(1), r(2)) }
    keys shouldBe keys.sorted
    rows.size shouldBe FunctionClasses.all.size
  }

  it should "embed exactly the File class in file-functions.txt" in {
    lines("native/qod-node/file-functions.txt") shouldBe tableNames(Set(File)).toList.sorted
    FunctionClasses.all.collect { case (k, File) => k.tpe }.toSet shouldBe Set("table")
  }

  it should "embed exactly the admitted table functions in admitted-table-functions.txt" in {
    lines("native/qod-node/admitted-table-functions.txt") shouldBe
      tableNames(Set(Allowed, Listing)).toList.sorted
  }

  it should "admit no table function beyond the reviewed ones, and admit each of them" in {
    withClue("File beyond the reviewed set: ")(
      (tableNames(Set(File)) -- ExpectedFile) shouldBe empty
    )
    withClue("admitted beyond the reviewed set: ")(
      (tableNames(Set(Allowed, Listing)) -- ExpectedAdmittedTable) shouldBe empty
    )
    val present = resolved.collect { case k if k.tpe == "table" => k.name }
    (ExpectedFile intersect present).foreach(n => withClue(n)(tableClass(n) shouldBe Some(File)))
    (ExpectedAdmittedTable intersect present).foreach { n =>
      withClue(n)(tableClass(n).exists(c => c == Allowed || c == Listing) shouldBe true)
    }
  }

  it should "class only macros macros.json lists, each allowed or superuser, from its body" in {
    val classified = FunctionClasses.all.filter((k, _) => isMacro(k))
    withClue("classified, not in macros.json: ")(
      (classified.keySet -- macros).toList.map(_.toString).sorted shouldBe Nil
    )
    classified.values.toSet shouldBe Set(Allowed, Superuser)
    def macroClass(schema: String, name: String): Option[FunctionClass] =
      FunctionClasses.all.get(FunctionClasses.Key(schema, name, "macro"))
    // pure: they pass in bind-time slots now
    List(
      "date_add",
      "ago",
      "if",
      "nullif",
      "split_part",
      "fdiv",
      "fmod",
      "list_sum",
      "array_append"
    )
      .foreach(n => withClue(n)(macroClass("main", n) shouldBe Some(Allowed)))
    List("pg_typeof", "has_table_privilege", "current_database")
      .foreach(n => withClue(n)(macroClass("pg_catalog", n) shouldBe Some(Allowed)))
    // their bodies call a superuser-class function
    macroClass("main", "get_block_size") shouldBe Some(Superuser)    // pragma_database_size
    macroClass("pg_catalog", "format_type") shouldBe Some(Superuser) // duckdb_types()
    macroClass("pg_catalog", "pg_get_constraintdef") shouldBe Some(Superuser)
    FunctionClasses.all.get(
      FunctionClasses.Key("main", "duckdb_logs_parsed", "table_macro")
    ) shouldBe
      Some(Superuser)
    // a listing, unnest, or a table read in the body: not classified, refused in a slot
    macroClass("pg_catalog", "pg_get_viewdef") shouldBe None
    macroClass("main", "generate_subscripts") shouldBe None
    FunctionClasses.all.get(FunctionClasses.Key("main", "histogram", "table_macro")) shouldBe None
  }

  it should "class a fixture dump's macros as the generator's rules say" in {
    val python = scala.util
      .Try(
        scala.sys.process
          .Process(Seq("python3", "--version"))
          .!(scala.sys.process.ProcessLogger(_ => ()))
      )
      .toOption
      .contains(0)
    assume(python, "python3 not on PATH")
    // src/test/resources/authz/macro-dump-fixture.json: the release CLI's duckdb_functions() rows,
    // with parse trees, of macros made for it (each body is in the expectations below).
    val out = scala.sys.process
      .Process(
        Seq(
          "python3",
          "scripts/dev/classify-qod-node.py",
          "--macro-classes",
          "src/test/resources/authz/macro-dump-fixture.json"
        )
      )
      .!!
    out.linesIterator.toList shouldBe List(
      "fx_nested\tmain\tmacro\tallowed",           // fx_pure(a) || lower(a): through another macro
      "fx_overload\tmain\tmacro\tsuperuser",       // one overload calls current_setting
      "fx_pure\tmain\tmacro\tallowed",             // upper(a) || 'x'
      "fx_qualified\tmain\tmacro\tallowed",        // system.main.lower(a)
      "fx_range\tmain\ttable_macro\tallowed",      // TABLE SELECT * FROM range(n)
      "fx_setting\tmain\tmacro\tsuperuser",        // current_setting(a)
      "fx_settings\tmain\ttable_macro\tsuperuser", // TABLE SELECT * FROM duckdb_settings()
      "fx_via\tmain\tmacro\tsuperuser",            // fx_setting(a) || 'y': through another macro
      "fx_window\tmain\tmacro\tallowed"            // row_number() OVER ()
    )
    // Not classified: fx_seq (nextval), fx_reads (a table), fx_listing (duckdb_tables()), fx_query
    // (query()), fx_other (memory.main.fx_pure, another catalog's).
  }

  it should "restrict exactly the reviewed scalar, aggregate and window functions, to superusers" in {
    val restricted = FunctionClasses.all.collect {
      case (k, c) if k.tpe != "table" && k.tpe != "pragma" && !isMacro(k) && c != Allowed =>
        (k.name, k.tpe) -> c
    }
    restricted.keySet shouldBe ExpectedRestrictedScalar
    restricted.values.toSet shouldBe Set(Superuser)
  }

  it should "list exactly the catalog functions the metadata filter narrows" in {
    tableNames(Set(Listing)) shouldBe TableExtractor.DuckDbCatalogFunctions
    FunctionClasses.all.collect { case (k, Listing) => k.tpe }.toSet shouldBe Set("table")
  }

  it should "admit what the manager runs on user connections" in {
    tableClass("ducklake_set_commit_message") shouldBe Some(Allowed)
    tableClass("scan_data_from_quack_client") shouldBe Some(Allowed)
    tableClass("qod_session") shouldBe Some(Allowed)
  }

  it should "keep the nearest relatives of admitted functions superuser" in {
    val pairs = List(
      ("read_parquet", "table", File)                   -> ("iceberg_scan", "table"),
      ("read_text", "table", File)                      -> ("sql_auto_complete", "table"),
      ("read_csv", "table", File)                       -> ("ducklake_scan", "table"),
      ("query", "table", Allowed)                       -> ("json_execute_serialized_sql", "table"),
      ("query_table", "table", Allowed)                 -> ("duckdb_table_sample", "table"),
      ("duckdb_tables", "table", Listing)               -> ("duckdb_databases", "table"),
      ("duckdb_columns", "table", Listing)              -> ("pragma_table_info", "table"),
      ("duckdb_views", "table", Listing)                -> ("duckdb_functions", "table"),
      ("ducklake_set_commit_message", "table", Allowed) -> ("ducklake_set_option", "table"),
      ("pragma_version", "table", Allowed)              -> ("pragma_storage_info", "table"),
      ("duckdb_variables", "table", Allowed)            -> ("duckdb_settings", "table"),
      ("duckdb_dialects", "table", Allowed)             -> ("duckdb_connection_count", "table"),
      ("duckdb_keywords", "table", Allowed)      -> ("duckdb_approx_database_count", "table"),
      ("postgres_hstore_get", "scalar", Allowed) -> ("postgres_pin_connection", "scalar"),
      ("qod_session", "table", Allowed)          -> ("qod_screen", "scalar"),
      ("lower", "scalar", Allowed)               -> ("current_setting", "scalar"),
      ("json_serialize_sql", "scalar", Allowed)  -> ("json_serialize_plan", "scalar"),
      ("nextval", "scalar", Allowed)             -> ("setval", "scalar"),
      ("table_info", "pragma", Allowed)          -> ("storage_info", "pragma"),
      ("show_tables", "pragma", Allowed)         -> ("import_database", "pragma")
    )
    pairs.foreach { case ((admitted, at, cls), (refused, rt)) =>
      withClue(admitted)(FunctionClasses.of("system", "main", admitted, at) shouldBe Some(cls))
      withClue(refused)(FunctionClasses.of("system", "main", refused, rt) shouldBe Some(Superuser))
    }
  }

  it should "reserve whole families to superusers" in {
    val families = FunctionClasses.all.collect {
      case (k, c)
          if k.tpe == "table" && k.name != "ducklake_set_commit_message" &&
            Seq("ducklake_", "iceberg_", "postgres_", "quack_").exists(k.name.startsWith) =>
        k.name -> c
    }
    families should not be empty
    families.filter(_._2 != Superuser) shouldBe empty
    FunctionClasses.all.collect {
      case (k, c) if k.name.startsWith("quack_") && c != Superuser => k
    } shouldBe
      empty
    FunctionClasses.all.collect {
      case (k, c) if k.tpe == "pragma" && c != Superuser => k.name
    }.toSet shouldBe
      Set(
        "database_list",
        "functions",
        "show_databases",
        "show_tables",
        "show_tables_expanded",
        "table_info",
        "version"
      )
  }

  "of" should "know no function outside the system catalog" in {
    FunctionClasses.of("memory", "main", "lower", "scalar") shouldBe None
    FunctionClasses.of("temp", "main", "lower", "scalar") shouldBe None
    FunctionClasses.of("system", "main", "lower", "scalar") shouldBe Some(Allowed)
  }

  it should "look up a function bound with no catalog entry as a system built-in, and only that" in {
    FunctionClasses.of("", "", "read_csv", "table") shouldBe Some(File)
    FunctionClasses.of("", "", "json_execute_serialized_sql", "table") shouldBe Some(Superuser)
    FunctionClasses.of("", "main", "read_csv", "table") shouldBe None
    FunctionClasses.of("memory", "", "read_csv", "table") shouldBe None
  }

  it should "match case-insensitively, and know nothing it was not given" in {
    FunctionClasses.of("SYSTEM", "Main", "Read_Parquet", "TABLE") shouldBe Some(File)
    FunctionClasses.of("system", "main", "qod_no_such_function", "scalar") shouldBe None
    FunctionClasses.of("system", "main", "lower", "macro") shouldBe None
    FunctionClasses.of("system", "main", "lower", "table") shouldBe None
  }

  "parse" should "refuse a malformed table, naming the line" in {
    List(
      "lower\tmain\tscalar"                                          -> "line 1",
      "lower\tmain\tscalar\tmaybe"                                   -> "unknown class",
      "lower\tmain\tview\tallowed"                                   -> "unknown function type",
      "lower\tmain\tmacro\tfile"                                     -> "only a table function",
      "lower\tmain\tscalar\tfile"                                    -> "only a table function",
      "Lower\tmain\tscalar\tallowed"                                 -> "lowercase",
      "lower\tmain\tscalar\tallowed\nlower\tmain\tscalar\tsuperuser" -> "line 2"
    ).foreach { case (text, needle) =>
      withClue(text)(
        intercept[IllegalArgumentException](FunctionClasses.parse(text)).getMessage should
          include(needle)
      )
    }
    FunctionClasses.parse("# comment\n\nlower\tmain\tscalar\tallowed\n") shouldBe
      Map(FunctionClasses.Key("main", "lower", "scalar") -> Allowed)
  }
