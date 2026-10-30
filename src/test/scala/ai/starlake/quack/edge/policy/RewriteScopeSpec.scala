package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.Verb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[RewriteScope]]: the router runs the statement an edge rewriter printed, not the one the caller
  * sent, so a rewrite reaching anything the original did not is refused (spec decision 29), by
  * parse alone: the node judges the rewritten statement's grants.
  */
class RewriteScopeSpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("lake"), Some("tpch1"), Set("lake"))

  private def beyond(original: String, rewritten: String): Option[String] =
    RewriteScope.reachesBeyond(original, rewritten, cfg, "lake", clientDataScans = false)

  "a rewrite" should "be refused when a CTE reference was printed schema-qualified" in {
    beyond(
      "WITH secret AS (SELECT c_id AS v FROM customer WHERE c_email LIKE '%') SELECT v FROM secret",
      "WITH secret AS (SELECT c_id AS v FROM customer WHERE '***' LIKE '%') " +
        "SELECT v FROM tpch1.secret"
    ).getOrElse(fail("admitted")) should include("lake.tpch1.secret")
  }

  it should "be refused when it reaches another table or verb, or cannot be read" in {
    beyond("SELECT a FROM t", "SELECT a FROM t, u") shouldBe defined
    beyond("SELECT a FROM t", "SELECT a FROM t WHERE a IN (SELECT b FROM u)") shouldBe defined
    beyond("SELECT a FROM t", "INSERT INTO t SELECT a FROM t") shouldBe defined
    beyond("SELECT a FROM t", "SELECT a FROM read_parquet('/x')") shouldBe defined
    beyond("SELECT a FROM t", "SELECT a FROM (") shouldBe defined
    beyond("SELECT a FROM t", "SELECT * FROM histogram('t', a)") shouldBe defined
  }

  it should "admit the masking, row filter and metadata substitutions" in {
    beyond("SELECT c_email FROM customer", "SELECT '***' AS c_email FROM customer") shouldBe None
    beyond(
      "SELECT * FROM customer c",
      "SELECT * FROM (SELECT * FROM customer WHERE c_region = 'EU') c"
    ) shouldBe None
    beyond(
      "SELECT * FROM information_schema.tables",
      "SELECT * FROM (SELECT * FROM information_schema.tables WHERE table_catalog = 'lake') t"
    ) shouldBe None
    beyond(
      "SELECT * FROM duckdb_tables()",
      "SELECT * FROM (SELECT * FROM duckdb_tables() WHERE database_name = 'lake') duckdb_tables"
    ) shouldBe None
    // SHOW TABLES is control flow; the filter substitutes a filtered metadata read for it
    beyond(
      "SHOW TABLES",
      "SELECT table_name AS name FROM information_schema.tables WHERE table_catalog = 'lake'"
    ) shouldBe None
  }

  it should "leave an original it cannot read to the node" in {
    beyond("SELECT * FROM (", "SELECT * FROM secret") shouldBe None
    beyond("SELECT * FROM histogram('customer', c)", "SELECT * FROM secret") shouldBe None
  }

  // A file reader is judged by the node on its locations; the rewrite may neither add one nor change
  // the one the caller wrote (Task 12 review M3: a statement holding a reader is still compared).
  it should "compare a rewrite of a statement holding a file reader" in {
    val original =
      "SELECT o.id FROM lake.main.orders o, read_parquet('s3://acme-etl/in/x.parquet') r"
    beyond(original, original + ", lake.main.secret s").getOrElse(
      fail("the extra table went unnoticed")
    ) should include("lake.main.secret")
    beyond(original, original) shouldBe None
  }

  it should "refuse a rewrite that changes a file reader's arguments" in {
    beyond("SELECT * FROM read_parquet('/x')", "SELECT * FROM read_parquet('/y')") shouldBe defined
    beyond("SELECT * FROM read_parquet('/x')", "SELECT * FROM read_parquet('/x')") shouldBe None
  }

  // A star expansion prints the catalog's column names, quoted when needed: `c."a@b"` is a name
  // the caller never wrote, so the quoted-identifier screen does not refuse the rewrite for it.
  // Every quoted name the caller wrote is still screened, an alias moved out of AS position too.
  it should "screen only the quoted names the caller wrote" in {
    beyond(
      "SELECT * FROM customer c",
      "SELECT c.c_id, c.\"a@b\", c.\"x]\" FROM customer c"
    ) shouldBe None
    beyond("SELECT 1 AS \"a@b\" FROM customer", "SELECT \"a@b\" FROM customer") shouldBe defined
    beyond("SELECT * FROM customer", "SELECT * FROM \"[customer]\"") shouldBe defined
  }

  "scopeOf" should "set the session catalog's filterable metadata reads aside, in a read only" in {
    RewriteScope.scopeOf(
      "SELECT table_name FROM information_schema.tables",
      cfg,
      "lake",
      clientDataScans = false
    ) shouldBe RewriteScope.Scope.Reaches(Set.empty, Set.empty)
    RewriteScope.scopeOf(
      "INSERT INTO t SELECT table_name FROM information_schema.tables",
      cfg,
      "lake",
      clientDataScans = false
    ) match
      case RewriteScope.Scope.Reaches(g, _) =>
        g.map(_.table.canonical) should contain("lake.information_schema.tables")
      case other => fail(s"expected the metadata read gated in a write, got $other")
  }

  it should "refuse a catalog function riding in a write" in {
    RewriteScope.scopeOf(
      "INSERT INTO t SELECT * FROM duckdb_tables()",
      cfg,
      "lake",
      clientDataScans = false
    ) shouldBe a[RewriteScope.Scope.Refused]
  }

  // The gate's parse rules, which the reach of a statement keeps (formerly AccessGateSpec).

  private val tpch = Config.forDuckDB(Some("tpch"), Some("main"), Set("tpch"))

  private def scope(sql: String, frontDoor: Boolean = false): RewriteScope.Scope =
    RewriteScope.scopeOf(sql, tpch, "tpch", clientDataScans = frontDoor)

  private def reaches(sql: String, frontDoor: Boolean = false): Set[(String, Verb)] =
    scope(sql, frontDoor) match
      case RewriteScope.Scope.Reaches(g, _) => g.map(a => a.table.table -> a.verb)
      case other                            => fail(s"expected a reach, got $other for: $sql")

  private val ClientScan =
    "scan_data_from_quack_client('0f8e2c3a-1b2c-4d5e-8f90-0123456789ab', " +
      "NULL::STRUCT(col0 BIGINT, col1 VARCHAR), ordered := true)"

  "the reach of a statement" should "hold every table a join touches, and mark writes" in {
    reaches("SELECT * FROM orders o JOIN lineitem l ON o.k = l.k") shouldBe
      Set("orders" -> Verb.Read, "lineitem" -> Verb.Read)
    reaches("INSERT INTO orders SELECT * FROM lineitem") shouldBe
      Set("orders" -> Verb.Write, "lineitem" -> Verb.Read)
  }

  it should "be empty for control flow, the quack client's transaction statements included" in
    List("COMMIT", "BEGIN TRANSACTION", "ROLLBACK").foreach { sql =>
      withClue(sql)(scope(sql) shouldBe RewriteScope.Scope.Reaches(Set.empty, Set.empty))
    }

  it should "refuse a parse error, an ambiguous name, and a batch that only starts with BEGIN" in {
    scope("SELECT FROM WHERE (") shouldBe a[RewriteScope.Scope.Refused]
    RewriteScope.scopeOf(
      "SELECT * FROM fedpg.orders",
      Config.forDuckDB(Some("tpch"), Some("main"), Set("tpch", "fedpg")),
      "tpch",
      clientDataScans = false
    ) match
      case RewriteScope.Scope.Refused(m) => m should include("ambiguous two-part name")
      case other                         => fail(other.toString)
    scope("BEGIN TRANSACTION; DROP TABLE orders") shouldBe a[RewriteScope.Scope.Refused]
  }

  // A subquery in any expression position is a read of the table it names.
  it should "hold a table read by a subquery in every expression position" in {
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
        scope(sql) match
          case RewriteScope.Scope.Reaches(g, _) if g.nonEmpty =>
            g.map(_.table.canonical) should contain(other)
          case RewriteScope.Scope.Refused(_) => succeed
          case nothing                       => fail(s"read nothing: $nothing")
      }
    }
  }

  it should "hold a client-fed INSERT's target only, from the native front door alone" in {
    reaches(s"INSERT INTO orders SELECT * FROM $ClientScan", frontDoor = true) shouldBe
      Set("orders" -> Verb.Write)
    scope(s"INSERT INTO orders SELECT * FROM $ClientScan") match
      case RewriteScope.Scope.Refused(m) => m should include("scan_data_from_quack_client")
      case other                         => fail(other.toString)
    reaches(
      s"INSERT INTO orders SELECT * FROM $ClientScan WHERE col0 IN (SELECT k FROM lineitem)",
      frontDoor = true
    ) shouldBe Set("orders" -> Verb.Write, "lineitem" -> Verb.Read)
  }

  it should "refuse a client data scan that is not the source of a write, or malformed" in {
    scope(s"SELECT * FROM $ClientScan", frontDoor = true) shouldBe a[RewriteScope.Scope.Refused]
    scope(s"SELECT * FROM $ClientScan") shouldBe a[RewriteScope.Scope.Refused]
    scope(
      "INSERT INTO orders SELECT * FROM scan_data_from_quack_client('x', NULL::STRUCT(a INT), " +
        "ordered := true)",
      frontDoor = true
    ) shouldBe a[RewriteScope.Scope.Refused]
  }
