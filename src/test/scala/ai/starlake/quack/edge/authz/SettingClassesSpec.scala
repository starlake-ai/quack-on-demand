package ai.starlake.quack.edge.authz

import io.circe.JsonObject
import io.circe.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.util.Locale

/** The setting classification against what qod-node knows (native/qod-node/settings.json): exactly
  * those settings and aliases classified, `Session` only for decision 11's reviewed set and only
  * for a setting that can be changed for one session, and the nearest relatives of each session
  * setting kept superuser.
  */
class SettingClassesSpec extends AnyFlatSpec with Matchers:
  import SettingClass.*

  private def lower(s: String): String = s.toLowerCase(Locale.ROOT)

  private lazy val listed: List[JsonObject] =
    parser
      .parse(Files.readString(Paths.get("native/qod-node/settings.json")))
      .fold(e => fail(s"settings.json: ${e.getMessage}"), identity)
      .asArray
      .getOrElse(fail("settings.json is not an array"))
      .toList
      .map(_.asObject.getOrElse(fail("settings.json holds a non-object")))

  private def str(o: JsonObject, k: String): String =
    o(k).flatMap(_.asString).getOrElse(fail(s"settings.json: no string $k in $o"))

  private val ExpectedSession = Set(
    "binary_as_string",
    "calendar",
    "default_collation",
    "default_null_order",
    "default_order",
    "disable_timestamptz_casts",
    "enable_geoparquet_conversion",
    "error_on_division_by_zero",
    "ieee_floating_point_ops",
    "ignore_unknown_crs",
    "integer_division",
    "json_geometry_format",
    "lambda_syntax",
    "legacy_disable_null_type",
    "old_implicit_casting",
    "order_by_non_integer_literal",
    "regex_match_operator_semantics",
    "scalar_subquery_error_on_multiple_rows",
    "timezone",
    "schema",
    "search_path",
    "enable_progress_bar",
    "errors_as_json",
    "explain_output",
    "max_execution_time",
    "pivot_filter_threshold",
    "pivot_limit",
    "preserve_insertion_order",
    "approximate_join_order_threshold",
    "asof_loop_join_threshold",
    "delim_join_as_cte",
    "dynamic_or_filter_threshold",
    "index_scan_max_count",
    "index_scan_percentage",
    "late_materialization_max_rows",
    "merge_join_threshold",
    "nested_loop_join_threshold",
    "ordered_aggregate_threshold",
    "perfect_ht_threshold",
    "prefer_range_joins",
    "http_retries",
    "http_retry_backoff",
    "http_retry_wait_ms",
    "http_timeout",
    // the alias of default_null_order
    "null_order"
  )

  "the setting classification" should "classify exactly the settings and aliases qod-node knows" in {
    val names = listed.map(o => lower(str(o, "name"))).toSet
    withClue("in settings.json, not classified (run scripts/dev/gen-qod-node-classification.sh): ")(
      (names -- SettingClasses.all.keySet).toList.sorted.take(20) shouldBe Nil
    )
    withClue("classified, not in settings.json: ")(
      (SettingClasses.all.keySet -- names).toList.sorted.take(20) shouldBe Nil
    )
  }

  it should "give an alias the class of the setting it names" in {
    val aliases = listed.filter(o => str(o, "kind") == "alias")
    aliases should not be empty
    aliases.foreach { a =>
      withClue(str(a, "name"))(
        SettingClasses.of(str(a, "name")) shouldBe SettingClasses.of(str(a, "target"))
      )
    }
  }

  it should "allow at session scope only the settings decision 11 judged, and each of them" in {
    val session = SettingClasses.all.collect { case (n, Session) => n }.toSet
    withClue("session beyond the reviewed set: ")((session -- ExpectedSession) shouldBe empty)
    (ExpectedSession intersect SettingClasses.all.keySet).foreach { n =>
      withClue(n)(SettingClasses.of(n) shouldBe Some(Session))
    }
  }

  it should "call session only a setting that can be changed for one session" in
    listed
      .filter(o => str(o, "kind") != "alias" && SettingClasses.of(str(o, "name")).contains(Session))
      .foreach { o =>
        val (scope, setters) = (str(o, "scope"), str(o, "setters"))
        val capable          =
          if str(o, "kind") == "core" then
            Set("local_only", "local_default", "global_default")(scope) ||
            (scope == "none" && setters.endsWith("l"))
          else setters.endsWith("l")
        withClue(o.toString)(capable shouldBe true)
      }

  it should "keep the nearest relatives of session settings superuser" in
    List(
      "timezone"           -> "s3_secret_access_key",
      "search_path"        -> "file_search_path",
      "schema"             -> "secret_directory",
      "default_order"      -> "enable_optimizer",
      "http_timeout"       -> "s3_endpoint",
      "http_retries"       -> "http_proxy",
      "max_execution_time" -> "threads",
      "pivot_limit"        -> "memory_limit",
      // bounds the parser's and binder's recursion: raised, one session could overflow a stack of the
      // shared process
      "max_execution_time"       -> "max_expression_depth",
      "errors_as_json"           -> "enable_logging",
      "explain_output"           -> "profiling_output",
      "preserve_insertion_order" -> "enable_profiling"
    ).foreach { case (session, superuser) =>
      withClue(session)(SettingClasses.of(session) shouldBe Some(Session))
      withClue(superuser)(SettingClasses.of(superuser) shouldBe Some(Superuser))
    }

  it should "reserve to superusers what switches enforcement, extensions or SQL-valued settings" in
    List(
      "quack_authorization_function",
      "quack_authentication_function",
      "enable_optimizer",
      "disabled_optimizers",
      "debug_disable_optimizer",
      "allow_community_extensions",
      "allow_unsigned_extensions",
      "autoinstall_known_extensions",
      "autoload_known_extensions",
      "pg_staleness_query",
      "pg_pool_health_check_query",
      "lock_configuration",
      "enable_external_access",
      "disabled_filesystems",
      "allowed_directories"
    ).foreach(n => withClue(n)(SettingClasses.of(n) shouldBe Some(Superuser)))

  it should "never call session a setting whose name points at credentials, files or endpoints" in {
    val reaching = Seq(
      "secret",
      "password",
      "token",
      "key_id",
      "access_key",
      "credential",
      "connection_string",
      "proxy",
      "endpoint",
      "director",
      "file",
      "extension",
      "log",
      "profil",
      "quack_",
      "pg_",
      "mysql_",
      "azure_",
      "aws_",
      "s3_",
      "ducklake_",
      "iceberg_",
      "whoami_"
    )
    SettingClasses.all.collect {
      case (n, Session) if reaching.exists(n.contains) => n
    } shouldBe empty
  }

  "of" should "match case-insensitively, and know nothing it was not given" in {
    SettingClasses.of("TimeZone") shouldBe Some(Session)
    SettingClasses.of("SEARCH_PATH") shouldBe Some(Session)
    SettingClasses.of("qod_no_such_setting") shouldBe None
  }

  "parse" should "refuse a malformed table, naming the line" in {
    List(
      "timezone"                               -> "line 1",
      "timezone\tmaybe"                        -> "unknown class",
      "TimeZone\tsession"                      -> "lowercase",
      "timezone\tsession\ntimezone\tsuperuser" -> "line 2"
    ).foreach { case (text, needle) =>
      withClue(text)(
        intercept[IllegalArgumentException](SettingClasses.parse(text)).getMessage should
          include(needle)
      )
    }
    SettingClasses.parse("# comment\ntimezone\tsession\n") shouldBe Map("timezone" -> Session)
  }
