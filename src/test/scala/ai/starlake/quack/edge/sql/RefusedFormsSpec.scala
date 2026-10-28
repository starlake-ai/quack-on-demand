package ai.starlake.quack.edge.sql

import ai.starlake.quack.edge.sql.RefusedForms.{Form, SessionPath, Target}
import ai.starlake.quack.ondemand.InProcessDuckDb
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}

/** Task 19 part A: the refused forms the gateway recognizes on DuckDB's own tokens, and what each
  * becomes. The rewrites are run against an in-process DuckDB 2.0 next to the statements they stand
  * for, so their columns and values are DuckDB's own (`key` and `pk` aside, which the rewrites
  * leave NULL).
  */
class RefusedFormsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val path                        = Some(SessionPath("acme_db", "tpch1"))
  private val settings: String => Boolean = Set("threads", "memory_limit", "timezone")

  private def form(sql: String, p: Option[SessionPath] = path): Option[Form] =
    RefusedForms.recognize(sql, p, settings)

  private def described(t: Target): Option[Form] = Some(Form.Rewritten(RefusedForms.describe(t)))

  private val T = Target("acme_db", "tpch1", "t")

  "DESCRIBE" should "become an information_schema.columns listing that reads the table" in {
    form("DESCRIBE t") shouldBe Some(
      Form.Rewritten(
        "SELECT column_name, data_type AS column_type, is_nullable AS \"null\", " +
          "NULL::VARCHAR AS \"key\", column_default AS \"default\", NULL::VARCHAR AS extra " +
          "FROM information_schema.columns WHERE lower(table_catalog) = lower('acme_db') " +
          "AND lower(table_schema) = lower('tpch1') AND lower(table_name) = lower('t') " +
          "AND NOT EXISTS (SELECT 1 FROM \"acme_db\".\"tpch1\".\"t\" LIMIT 0) " +
          "ORDER BY ordinal_position"
      )
    )
  }

  it should "take DESCRIBE TABLE, DESC, a trailing semicolon, comments and any case" in
    List(
      "DESCRIBE TABLE t",
      "DESC t",
      "describe t;",
      "  /* c */ DESCRIBE -- x\n t ;",
      "Describe Table t"
    ).foreach(sql => withClue(sql)(form(sql) shouldBe described(T)))

  it should "qualify only what the client left out" in {
    form("DESCRIBE s.t") shouldBe described(Target("acme_db", "s", "t"))
    form("DESCRIBE c.s.t") shouldBe described(Target("c", "s", "t"))
    form("DESCRIBE c.s.t", p = None) shouldBe described(Target("c", "s", "t"))
    form("DESCRIBE t", p = None) shouldBe None
    form("DESCRIBE s.t", p = None) shouldBe None
  }

  it should "keep quoted parts as written and quote them again" in {
    form("DESCRIBE \"a.b\".\"T \"\"q\"\"\"") shouldBe described(Target("acme_db", "a.b", "T \"q\""))
    form("DESCRIBE `x`.\"it's\"") shouldBe described(Target("acme_db", "x", "it's"))
    val sql = RefusedForms.describe(Target("acme_db", "a.b", "it's \"q\""))
    sql should include("lower(table_schema) = lower('a.b')")
    sql should include("lower(table_name) = lower('it''s \"q\"')")
    sql should include("FROM \"acme_db\".\"a.b\".\"it's \"\"q\"\"\" LIMIT 0")
  }

  "SHOW" should "describe a table, list tables, and leave settings and DuckDB's own listings" in {
    form("SHOW t") shouldBe described(T)
    form("SHOW s.t") shouldBe described(Target("acme_db", "s", "t"))
    form("SHOW \"threads\"") shouldBe described(Target("acme_db", "tpch1", "threads"))
    form("SHOW tables") shouldBe Some(Form.Rewritten(RefusedForms.showTables(path.get)))
    form("SHOW ALL TABLES;") shouldBe Some(Form.Rewritten(RefusedForms.showAllTables(path.get)))
    List(
      "SHOW threads",
      "SHOW Memory_Limit",
      "SHOW databases",
      "SHOW schemas",
      "SHOW variables",
      "SHOW TABLES FROM s",
      "SHOW ALL",
      "SHOW (SELECT 1)"
    ).foreach(sql => withClue(sql)(form(sql) shouldBe None))
    form("SHOW TABLES", p = None) shouldBe None
  }

  "PRAGMA table_info and PRAGMA show" should "take a string or a name" in {
    val info = Some(Form.Rewritten(RefusedForms.tableInfo(T)))
    form("PRAGMA table_info('t')") shouldBe info
    form("PRAGMA table_info(t)") shouldBe info
    form("PRAGMA TABLE_INFO('acme_db.tpch1.t');") shouldBe info
    form("PRAGMA table_info('s.t')") shouldBe
      Some(Form.Rewritten(RefusedForms.tableInfo(Target("acme_db", "s", "t"))))
    form("PRAGMA table_info('\"a.b\".t')") shouldBe
      Some(Form.Rewritten(RefusedForms.tableInfo(Target("acme_db", "a.b", "t"))))
    form("PRAGMA \"show\"('t')") shouldBe described(T)
    form("PRAGMA show('t')") shouldBe described(T)
    List(
      "PRAGMA table_info('t', 'x')",
      "PRAGMA table_info()",
      "PRAGMA table_info('')",
      "PRAGMA table_info('a..b')",
      "PRAGMA table_info('a\"b')",
      "PRAGMA table_info(lower('T'))",
      "PRAGMA storage_info('t')",
      "PRAGMA table_info"
    ).foreach(sql => withClue(sql)(form(sql) shouldBe None))
  }

  "SUMMARIZE" should "learn the columns first, from the DESCRIBE rewrite" in {
    form("SUMMARIZE t") shouldBe Some(Form.Summarize(RefusedForms.describe(T), T))
    form("SUMMARIZE TABLE s.t") shouldBe
      Some(
        Form.Summarize(
          RefusedForms.describe(Target("acme_db", "s", "t")),
          Target("acme_db", "s", "t")
        )
      )
  }

  "an unrecognized statement" should "be left as it is" in
    List(
      "DESCRIBE (SELECT 1)",
      "DESCRIBE SELECT 1",
      "SUMMARIZE (SELECT 1)",
      "SUMMARIZE SELECT * FROM t",
      "DESCRIBE t; SELECT 1",
      "SELECT 1; DESCRIBE t",
      "DESCRIBE read_csv('s3://b/x.csv')",
      "DESCRIBE 's3://b/x.parquet'",
      "DESCRIBE a.b.c.d",
      "DESCRIBE t LIMIT 1",
      "DESCRIBE t.",
      "DESCRIBE .t",
      "DESCRIBE",
      "EXPLAIN DESCRIBE t",
      "SELECT * FROM t",
      "WITH x AS (DESCRIBE t) SELECT * FROM x",
      "",
      ";"
    ).foreach(sql => withClue(sql)(form(sql) shouldBe None))

  "qualifiedParts" should "read a name as DuckDB's QualifiedName::Parse does" in {
    RefusedForms.qualifiedParts("t") shouldBe Some(List("t"))
    RefusedForms.qualifiedParts("a.b.c") shouldBe Some(List("a", "b", "c"))
    RefusedForms.qualifiedParts("\"a.b\".\"c\"\"d\"") shouldBe Some(List("a.b", "c\"d"))
    RefusedForms.qualifiedParts("my table") shouldBe Some(List("my table"))
    List("", ".a", "a..b", "a\"b", "\"a\"b", "\"\"", "\"a").foreach { s =>
      withClue(s)(RefusedForms.qualifiedParts(s) shouldBe None)
    }
  }

  // ---- the rewrites against DuckDB's own answers ----

  private var conn: Connection = null

  override def beforeAll(): Unit =
    Class.forName("org.duckdb.DuckDBDriver")
    conn = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    List(
      "ATTACH ':memory:' AS acme_db",
      "CREATE SCHEMA acme_db.tpch1",
      "CREATE TABLE acme_db.tpch1.t (id INTEGER PRIMARY KEY, v VARCHAR DEFAULT 'x' NOT NULL, " +
        "d DOUBLE, ts TIMESTAMP, n DECIMAL(9, 2), b BOOLEAN, l INTEGER[], dt DATE)",
      "INSERT INTO acme_db.tpch1.t VALUES " +
        "(1, 'a', 1.5, TIMESTAMP '2026-01-01', 1.25, true, [1], DATE '2026-01-02'), " +
        "(2, 'b', NULL, NULL, NULL, NULL, NULL, NULL), " +
        "(3, 'b', 3.25, TIMESTAMP '2026-02-01', 7.5, false, [2, 3], DATE '2026-03-01')",
      "CREATE VIEW acme_db.tpch1.w AS SELECT id, v FROM acme_db.tpch1.t",
      "USE acme_db.tpch1"
    ).foreach(s => conn.createStatement().execute(s))

  override def afterAll(): Unit = if conn != null then conn.close()

  private def rows(sql: String): List[List[String]] =
    val rs  = conn.createStatement().executeQuery(sql)
    val n   = rs.getMetaData.getColumnCount
    val out = List.newBuilder[List[String]]
    while rs.next() do out += (1 to n).map(i => Option(rs.getString(i)).getOrElse("NULL")).toList
    out.result()

  private def names(sql: String): List[String] =
    val md = conn.createStatement().executeQuery(sql).getMetaData
    (1 to md.getColumnCount).map(md.getColumnLabel).toList

  /** `rows` with column `i` blanked. */
  private def without(i: Int)(rs: List[List[String]]): List[List[String]] =
    rs.map(_.updated(i, "-"))

  "the DESCRIBE rewrite" should "answer DuckDB's DESCRIBE but for the key column" in {
    names(RefusedForms.describe(T)) shouldBe names("DESCRIBE t")
    without(3)(rows(RefusedForms.describe(T))) shouldBe without(3)(rows("DESCRIBE t"))
    // A view: its own columns' names and types. DuckDB traces a view's columns to the base table
    // for `null` and `default`; the listing knows the view's columns only.
    val w = Target("acme_db", "tpch1", "w")
    rows(RefusedForms.describe(w)).map(_.take(2)) shouldBe rows("DESCRIBE w").map(_.take(2))
    rows(RefusedForms.describe(w)).map(_.drop(2)).distinct shouldBe
      List(List("YES", "NULL", "NULL", "NULL"))
    rows(RefusedForms.describe(Target("acme_db", "TPCH1", "T"))) shouldBe rows(
      RefusedForms.describe(T)
    )
  }

  it should "fail on an object that does not exist" in {
    an[java.sql.SQLException] should be thrownBy rows(
      RefusedForms.describe(Target("acme_db", "tpch1", "nope"))
    )
  }

  "the PRAGMA table_info rewrite" should "answer DuckDB's but for the pk column" in {
    names(RefusedForms.tableInfo(T)) shouldBe names("PRAGMA table_info('t')")
    without(5)(rows(RefusedForms.tableInfo(T))) shouldBe without(5)(rows("PRAGMA table_info('t')"))
  }

  "the SHOW TABLES rewrites" should "answer DuckDB's" in {
    rows(RefusedForms.showTables(path.get)) shouldBe rows("SHOW TABLES")
    names(RefusedForms.showTables(path.get)) shouldBe names("SHOW TABLES")
    rows(RefusedForms.showAllTables(path.get)) shouldBe rows("SHOW ALL TABLES")
    names(RefusedForms.showAllTables(path.get)) shouldBe names("SHOW ALL TABLES")
  }

  "the SUMMARIZE query" should "answer DuckDB's SUMMARIZE" in {
    val columns = rows(RefusedForms.describe(T)).map(r => (r(0), r(1)))
    val ours    = RefusedForms.summarizeSql(T, columns)
    names(ours) shouldBe names("SUMMARIZE t")
    rows(ours) shouldBe rows("SUMMARIZE t")
  }
