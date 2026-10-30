package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.Verb
import ai.starlake.quack.edge.policy.CatalogReachingCalls
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AccessGateSpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("tpch"), Some("main"), Set("tpch"))

  // `frontDoor` = the statement arrived through the native Quack front door, the only edge that
  // can feed a client data scan (SEND_DATA on the client's own connection).
  private def eval(sql: String, fm: Boolean = false, frontDoor: Boolean = false) =
    AccessGate.evaluate(sql, cfg, "tpch", fm, clientDataScans = frontDoor)

  "AccessGate" should "gate every table a join touches" in:
    eval("SELECT * FROM orders o JOIN lineitem l ON o.k = l.k") match
      case GateOutcome.Gated(g, _) => g.map(_.table.table) shouldBe Set("orders", "lineitem")
      case other                   => fail(other.toString)

  it should "mark writes" in:
    eval("INSERT INTO orders SELECT * FROM lineitem") match
      case GateOutcome.Gated(g, _) =>
        g.map(a => a.table.table -> a.verb) shouldBe Set(
          "orders"   -> Verb.Write,
          "lineitem" -> Verb.Read
        )
      case other => fail(other.toString)

  it should "gate nothing for control flow" in:
    eval("COMMIT") shouldBe a[GateOutcome.NothingGated]

  it should "refuse a parse error as wildcard-coverable" in:
    eval("SELECT FROM WHERE (") match
      case GateOutcome.Refuse(_, true, _) => succeed
      case other                          => fail(other.toString)

  it should "refuse a catalog-reaching call as not wildcard-coverable" in:
    eval("SELECT * FROM read_parquet('s3://b/x.parquet')") match
      case GateOutcome.Refuse(_, _, _) => succeed
      case other                       => fail(other.toString)

  it should "refuse a query_table call as not wildcard-coverable" in:
    eval("SELECT * FROM query_table('orders')") match
      case GateOutcome.Refuse(_, false, _) => succeed
      case other                           => fail(other.toString)

  it should "refuse duckdb_table_sample and the Iceberg catalog functions as not wildcard-coverable" in:
    Seq(
      "SELECT * FROM duckdb_table_sample('orders')",
      "SELECT * FROM iceberg_rewrite_data_files('ice.db.t')",
      "SELECT * FROM iceberg_rollback_to_snapshot('ice.db.t', 1)",
      "SELECT * FROM iceberg_to_ducklake('ice', 'lake')",
      "SELECT * FROM iceberg_table_properties('ice.db.t')",
      "SELECT * FROM iceberg_schema_properties('ice.db')",
      "SELECT * FROM set_iceberg_table_properties('ice.db.t', {'k': 'v'})",
      "SELECT * FROM remove_iceberg_table_properties('ice.db.t', ['k'])",
      "SELECT * FROM set_iceberg_schema_properties('ice.db', {'k': 'v'})",
      "SELECT * FROM remove_iceberg_schema_properties('ice.db', ['k'])"
    ).foreach { sql =>
      withClue(sql) {
        eval(sql) match
          case GateOutcome.Refuse(_, false, _) => succeed
          case other                           => fail(other.toString)
      }
    }

  it should "find a catalog-reaching call behind dollar-quote tags differing only in case" in:
    // DuckDB 2.0 compares dollar tags case-sensitively: $A$ is not closed by $a$, so the call after
    // the block-comment opener runs. Folding case before lexing read it as a comment.
    CatalogReachingCalls.find(
      "SELECT $A$x$a$ /* $A$, * FROM query_table('orders') -- */"
    ) shouldBe Some("query_table")
    // matching tags: the call sits in a comment for DuckDB too
    CatalogReachingCalls.find(
      "SELECT $a$x$a$ /* $a$, * FROM query_table('orders') -- */"
    ) shouldBe None

  it should "find a catalog-reaching call under every DuckDB 2.0 identifier quoting" in:
    // DuckDB 2.0 accepts backtick-quoted function names: `query_table`('orders') runs on the 2.0
    // CLI. A call is an identifier token, bare, double-quoted or backtick-quoted and possibly
    // qualified, followed past any trivia by an opening parenthesis token.
    CatalogReachingCalls.find("SELECT * FROM `query_table`('orders')") shouldBe Some("query_table")
    CatalogReachingCalls.find("SELECT * FROM `QUERY`('SELECT 1')") shouldBe Some("query")
    CatalogReachingCalls.find("SELECT * FROM \"query_table\"('orders')") shouldBe
      Some("query_table")
    CatalogReachingCalls.find("SELECT * FROM `system`.`main`.`query_table`('orders')") shouldBe
      Some("query_table")
    CatalogReachingCalls.find("SELECT * FROM main.`query_table` /* c */ ('orders')") shouldBe
      Some("query_table")
    CatalogReachingCalls.find("SELECT * FROM `ducklake_snapshots`('cat')") shouldBe
      Some("ducklake_snapshots")
    eval("SELECT * FROM `query_table`('orders')") match
      case GateOutcome.Refuse(_, false, _) => succeed
      case other                           => fail(other.toString)

  it should "not treat a backtick-quoted identifier with no parenthesis as a catalog-reaching call" in:
    CatalogReachingCalls.find("SELECT `query_table`, `query` FROM t") shouldBe None
    CatalogReachingCalls.find("SELECT \"query\" FROM t") shouldBe None

  it should "find the node-escaping calls (raw SQL, arbitrary connection, node env)" in:
    // postgres_* and getenv verified on the 2.0 CLI; mysql_* / sqlite_* listed from the 1.5.6 CLI
    // (the extensions are not in the 2.0 toolchain). Each must deny for a tenant-scoped principal.
    for name <- List(
        "postgres_query",
        "postgres_execute",
        "postgres_scan",
        "postgres_scan_pushdown",
        "postgres_attach",
        "mysql_query",
        "mysql_execute",
        "sqlite_query",
        "sqlite_scan",
        "sqlite_attach",
        "quack_query",
        "quack_query_by_name",
        "postgres_configure_pool",
        "create_external_resource",
        "register_external_resource",
        "register_external_resource_type",
        "deregister_external_resource",
        "destroy_external_resource",
        "getenv"
      )
    do
      withClue(name) {
        CatalogReachingCalls.find(s"SELECT * FROM $name('c', 'x')") shouldBe Some(name)
        CatalogReachingCalls.find(s"SELECT * FROM `$name`('c', 'x')") shouldBe Some(name)
        CatalogReachingCalls.denyReason(name) should include("cannot authorize")
      }
    CatalogReachingCalls.find("SELECT postgres_query, getenv FROM t") shouldBe None

  it should "refuse a SET / RESET / PRAGMA of a SQL-valued setting, not wildcard-coverable" in:
    for sql <- List(
        "SET pg_staleness_query = 'SELECT 1 ${SCHEMA}'",
        "SET GLOBAL\"pg_pool_health_check_query\" = 'SELECT 1'",
        "RESET pg_staleness_query",
        "PRAGMA quack_authentication_function = 'f'",
        "EXPLAIN ANALYZE SET quack_authorization_function = 'f'"
      )
    do
      withClue(sql) {
        eval(sql) match
          case GateOutcome.Refuse(msg, false, _) => msg should include("runs SQL")
          case other                             => fail(other.toString)
      }
    CatalogReachingCalls.sqlValuedSetting("SET schema = 'pg_staleness_query'") shouldBe None
    CatalogReachingCalls.sqlValuedSetting("SELECT current_setting('pg_staleness_query')") shouldBe
      None

  it should "fail closed when string literals nest past the scan depth" in:
    // A reaching call reachable only past FunctionCalls.MaxDepth: each level is a plain string
    // literal (not itself a reaching call), so nothing shallower denies and the scan truncates.
    def nest(n: Int): String =
      if n == 0 then "postgres_query('c', 'x')"
      else "SELECT '" + nest(n - 1).replace("'", "''") + "'"
    val deep = nest(9)
    CatalogReachingCalls.find(deep) shouldBe defined
    CatalogReachingCalls.denyReason(CatalogReachingCalls.find(deep).get) should
      include("too deeply")
    eval(deep) match
      case GateOutcome.Refuse(_, false, _) => succeed
      case other                           => fail(other.toString)

  it should "refuse an ambiguous two-part name on an attached catalog as not wildcard-coverable" in:
    val attached = Config.forDuckDB(Some("tpch"), Some("main"), Set("tpch", "fedpg"))
    AccessGate.evaluate("SELECT * FROM fedpg.orders", attached, "tpch", false, false) match
      case GateOutcome.Refuse(_, false, _) => succeed
      case other                           => fail(other.toString)

  // A subquery in any expression position is a read of the table it names. DuckDB's
  // `ARRAY(SELECT ...)` parses as a function whose argument is a bare (unparenthesized) select,
  // which the walker used to mark visited as an expression and then skip as a select, so the
  // statement came back NothingGated and was admitted with no grant at all.
  it should "gate a table read by a subquery in every expression position" in:
    val other = "tpch.secret.orders"
    List(
      "SELECT ARRAY(SELECT o FROM tpch.secret.orders)",
      "SET VARIABLE x = ARRAY(SELECT o FROM tpch.secret.orders)",
      "SELECT list_value(ARRAY(SELECT o FROM tpch.secret.orders))",
      "SELECT coalesce(ARRAY(SELECT o FROM tpch.secret.orders), [])",
      "SELECT (SELECT o FROM tpch.secret.orders)",
      "SELECT coalesce((SELECT o FROM tpch.secret.orders), 'x')",
      "SELECT EXISTS (SELECT 1 FROM tpch.secret.orders)",
      "SELECT 1 WHERE 'a' IN (SELECT o FROM tpch.secret.orders)",
      "SELECT 1 WHERE 'a' = ANY (SELECT o FROM tpch.secret.orders)",
      "SELECT [(SELECT o FROM tpch.secret.orders)]",
      "SELECT list_transform([1], x -> (SELECT o FROM tpch.secret.orders))",
      "SELECT * FROM lineitem, LATERAL (SELECT o FROM tpch.secret.orders)",
      "SELECT CASE WHEN true THEN ARRAY(SELECT o FROM tpch.secret.orders) END",
      "SELECT 1 GROUP BY ARRAY(SELECT o FROM tpch.secret.orders)"
    ).foreach { sql =>
      withClue(sql) {
        eval(sql) match
          case GateOutcome.Gated(g, _)           => g.map(_.table.canonical) should contain(other)
          case GateOutcome.Refuse(_, _, _)       => succeed
          case nothing: GateOutcome.NothingGated => fail(s"admitted ungated: $nothing")
      }
    }

  it should "admit a pure info-schema read only with filtered metadata on" in:
    eval("SELECT table_name FROM information_schema.tables", fm = true) shouldBe
      a[GateOutcome.NothingGated]
    eval("SELECT table_name FROM information_schema.tables") shouldBe a[GateOutcome.Gated]

  // What a DuckDB 2.0 quack client sends when it uses an attached catalog.

  private val ClientScan =
    "scan_data_from_quack_client('0f8e2c3a-1b2c-4d5e-8f90-0123456789ab', " +
      "NULL::STRUCT(col0 BIGINT, col1 VARCHAR), ordered := true)"

  it should "gate nothing for the quack client's transaction statements" in:
    eval("BEGIN TRANSACTION") shouldBe a[GateOutcome.NothingGated]
    eval("COMMIT") shouldBe a[GateOutcome.NothingGated]
    eval("ROLLBACK") shouldBe a[GateOutcome.NothingGated]

  it should "still refuse a batch that only starts with BEGIN TRANSACTION" in:
    eval("BEGIN TRANSACTION; DROP TABLE orders") shouldBe a[GateOutcome.Refuse]

  it should "gate a client-fed INSERT on its target only" in:
    eval(s"INSERT INTO orders SELECT * FROM $ClientScan", frontDoor = true) match
      case GateOutcome.Gated(g, _) =>
        g.map(a => a.table.table -> a.verb) shouldBe Set("orders" -> Verb.Write)
      case other => fail(other.toString)

  it should "refuse a client-fed INSERT that did not arrive through the native front door" in:
    // Only the front door relays SEND_DATA: on any other edge the scan waits for rows that can
    // never come, so it stays an unsupported construct there.
    eval(s"INSERT INTO orders SELECT * FROM $ClientScan") match
      case GateOutcome.Refuse(msg, true, _) => msg should include("scan_data_from_quack_client")
      case other                            => fail(other.toString)

  it should "keep gating every other read of a client-fed INSERT" in:
    eval(
      s"INSERT INTO orders SELECT * FROM $ClientScan WHERE col0 IN (SELECT k FROM lineitem)",
      frontDoor = true
    ) match
      case GateOutcome.Gated(g, _) =>
        g.map(a => a.table.table -> a.verb) shouldBe Set(
          "orders"   -> Verb.Write,
          "lineitem" -> Verb.Read
        )
      case other => fail(other.toString)

  it should "refuse a client data scan that is not the source of a write" in:
    eval(s"SELECT * FROM $ClientScan", frontDoor = true) shouldBe a[GateOutcome.Refuse]
    eval(s"SELECT * FROM $ClientScan") shouldBe a[GateOutcome.Refuse]

  it should "refuse a malformed client data scan" in:
    eval(
      "INSERT INTO orders SELECT * FROM scan_data_from_quack_client('x', NULL::STRUCT(a INT), " +
        "ordered := true)",
      frontDoor = true
    ) shouldBe a[GateOutcome.Refuse]
