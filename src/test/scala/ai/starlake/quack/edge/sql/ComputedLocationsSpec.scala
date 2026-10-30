package ai.starlake.quack.edge.sql

import ai.starlake.quack.edge.{NodeRows, NodeValue}
import ai.starlake.quack.edge.sql.ComputedLocations.Location
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Task 19 part D: the computed file locations the gateway finds on DuckDB's own tokens, the value
  * it accepts back from the node, and the statement with each one replaced by its literal; nothing
  * else of the statement moves.
  */
class ComputedLocationsSpec extends AnyFlatSpec with Matchers:

  private def expressions(sql: String): List[String] = ComputedLocations.find(sql).map(_.expression)

  /** `sql` with each location replaced by the matching literal of `literals`. */
  private def substituted(sql: String, literals: String*): String =
    ComputedLocations.substitute(sql, ComputedLocations.find(sql).zip(literals))

  private val Dated = "'s3://acme-etl/in/' || strftime(DATE '2026-10-28', '%Y%m%d') || '.csv'"

  "a reader's computed first argument" should "be found, up to its top-level comma or paren" in {
    expressions(s"SELECT * FROM read_csv($Dated)") shouldBe List(Dated)
    expressions(s"SELECT * FROM read_csv($Dated, header = true, sep := ',')") shouldBe List(Dated)
    expressions("FROM read_parquet(concat('s3://b/', lower('X'), '.parquet'))") shouldBe
      List("concat('s3://b/', lower('X'), '.parquet')")
    expressions("FROM read_parquet(['s3://b/a.parquet', 's3://b/' || 'c.parquet'])") shouldBe
      List("['s3://b/a.parquet', 's3://b/' || 'c.parquet']")
    expressions("SELECT * FROM system.main.READ_CSV(getvariable('p'))") shouldBe
      List("getvariable('p')")
    expressions("SELECT * FROM \"read_json\"('s3://b/' || 'j.json')") shouldBe
      List("'s3://b/' || 'j.json'")
    ComputedLocations.find(s"SELECT * FROM read_csv($Dated)").map(_.list) shouldBe List(true)
  }

  it should "be replaced by its literal and nothing else" in {
    substituted(
      s"SELECT * FROM read_csv($Dated, header = true) WHERE a = 'x'",
      "'s3://acme-etl/in/20261028.csv'"
    ) shouldBe
      "SELECT * FROM read_csv('s3://acme-etl/in/20261028.csv', header = true) WHERE a = 'x'"
    substituted(
      "SELECT * FROM read_csv(/* c */ 'a' || 'b.csv' /* d */) JOIN read_json('x' || '.json') ON true",
      "'/f/ab.csv'",
      "'/f/x.json'"
    ) shouldBe "SELECT * FROM read_csv(/* c */ '/f/ab.csv' /* d */) JOIN read_json('/f/x.json') ON true"
  }

  it should "not be found when it is a literal or a list of them" in
    List(
      "SELECT * FROM read_csv('s3://acme-etl/in/x.csv')",
      "SELECT * FROM read_csv(E's3://acme-etl/in/x.csv', header = true)",
      "FROM read_parquet(['s3://b/a.parquet', 's3://b/c.parquet'])",
      "SELECT * FROM read_csv($$s3://b/x.csv$$)"
    ).foreach(sql => withClue(sql)(ComputedLocations.find(sql) shouldBe Nil))

  it should "not be found where the node could not evaluate it alone, or the client did not write it" in
    List(
      "SELECT * FROM read_csv((SELECT max(p) FROM t))",
      "SELECT * FROM read_csv((FROM t SELECT max(p)))",
      "SELECT * FROM read_csv(? || 'x.csv')",
      "SELECT * FROM read_csv($1 || 'x.csv')",
      "SELECT * FROM read_csv($p || 'x.csv')",
      "SELECT * FROM read_csv(files := 'a' || 'b.csv')",
      "SELECT * FROM read_csv(f => 'a' || 'b.csv')",
      "SELECT * FROM read_csv()",
      "SELECT * FROM read_csv('a' || 'b.csv'); SELECT 1",
      "SELECT * FROM query('SELECT * FROM read_csv(''a'' || ''b.csv'')')",
      "SELECT * FROM read_csv(read_text('x') || 'y')",
      "SELECT * FROM read_csv('a' || 'b.csv'",
      "SELECT 'read_csv(' || x FROM t"
    ).foreach(sql => withClue(sql)(ComputedLocations.find(sql) shouldBe Nil))

  it should "give up past the most computed locations one statement may hold" in {
    val many = (1 to ComputedLocations.MaxLocations + 1)
      .map(i => s"SELECT * FROM read_csv('a' || '$i.csv')")
      .mkString(" UNION ALL ")
    ComputedLocations.find(many) shouldBe Nil
  }

  "a statement that stores its text to run later" should "never have its location computed" in
    // Evaluated once at submission, the value would be frozen into the stored definition (Phase C
    // review D-m3): the statement is left as written, and the node refuses it.
    List(
      s"CREATE VIEW v AS SELECT * FROM read_csv($Dated)",
      s"create or replace view v as select * from read_csv($Dated)",
      s"CREATE TABLE t AS SELECT * FROM read_csv($Dated)",
      s"CREATE MACRO m() AS TABLE SELECT * FROM read_csv($Dated)",
      s"PREPARE p AS SELECT * FROM read_csv($Dated)",
      s"EXPLAIN SELECT * FROM read_csv($Dated)",
      s"(CREATE VIEW v AS SELECT * FROM read_csv($Dated))"
    ).foreach(sql => withClue(sql)(ComputedLocations.find(sql) shouldBe Nil))

  "a statement that runs once" should "have its location computed" in
    List(
      s"SELECT * FROM read_csv($Dated)",
      s"((SELECT * FROM read_csv($Dated)))",
      s"WITH x AS (SELECT * FROM read_csv($Dated)) SELECT * FROM x",
      s"FROM read_csv($Dated)",
      s"INSERT INTO t SELECT * FROM read_csv($Dated)",
      s"insert into t by name from read_csv($Dated)",
      s"COPY (SELECT 1) TO ($Dated)",
      s"COPY t FROM ($Dated)"
    ).foreach(sql => withClue(sql)(expressions(sql) shouldBe List(Dated)))

  "a COPY's parenthesized location" should "be found with its parentheses, in both directions" in {
    val to = s"COPY t TO ($Dated) (FORMAT csv)"
    ComputedLocations.find(to).map(l => (l.expression, l.list)) shouldBe List((Dated, false))
    substituted(to, "'s3://acme-etl/in/20261028.csv'") shouldBe
      "COPY t TO 's3://acme-etl/in/20261028.csv' (FORMAT csv)"
    expressions("COPY (SELECT 1 AS a) TO ('s3://b/' || 'o.parquet') (FORMAT parquet)") shouldBe
      List("'s3://b/' || 'o.parquet'")
    expressions("COPY s.t (a, b) FROM (getvariable('p'))") shouldBe List("getvariable('p')")
    expressions("copy t from ('s3://b/x.csv');") shouldBe List("'s3://b/x.csv'")
  }

  it should "not be found unparenthesized (DuckDB refuses it) or literal" in
    List(
      "COPY t TO 's3://b/' || 'o.csv'",
      "COPY t TO 's3://b/o.csv' (FORMAT csv)",
      "COPY (SELECT 1) TO ((SELECT 's3://b/o.csv'))",
      "COPY FROM DATABASE a TO b",
      "EXPLAIN COPY t TO ('s3://b/' || 'o.csv')"
    ).foreach(sql => withClue(sql)(ComputedLocations.find(sql) shouldBe Nil))

  "the evaluation" should "be the expression alone" in {
    ComputedLocations.evaluation(Location(0, 0, Dated, list = true)) shouldBe
      s"SELECT ($Dated) AS location"
  }

  "the literal" should "stand only for one non-empty VARCHAR, or a list of them where a list may" in {
    val reader            = Location(0, 0, "x", list = true)
    val copy              = Location(0, 0, "x", list = false)
    def one(v: NodeValue) = NodeRows(List(List(v)))
    ComputedLocations.literal(reader, one(NodeValue.Text("s3://b/it's.csv"))) shouldBe
      Right("'s3://b/it''s.csv'")
    ComputedLocations.literal(copy, one(NodeValue.Text("s3://b/o.csv"))) shouldBe
      Right("'s3://b/o.csv'")
    ComputedLocations.literal(
      reader,
      one(NodeValue.TextList(List(Some("/a"), Some("/b"))))
    ) shouldBe
      Right("['/a', '/b']")
    // Each refusal names its reason: inside a native transaction it is the client's answer.
    List(
      (reader, one(NodeValue.Null), "the location is NULL"),
      (reader, one(NodeValue.Text("")), "the location is an empty string"),
      (reader, one(NodeValue.Text("/a" + 0.toChar + "x")), "the location holds a NUL character"),
      (
        reader,
        one(NodeValue.Other("Int(32, true)")),
        "the location is a Int(32, true), not a VARCHAR"
      ),
      (
        reader,
        one(NodeValue.Text("x" * (ComputedLocations.MaxBytes + 1))),
        s"the location is longer than ${ComputedLocations.MaxBytes} bytes"
      ),
      (reader, one(NodeValue.TextList(Nil)), "the location is an empty list"),
      (reader, one(NodeValue.TextList(List(Some("/a"), None))), "the location list holds a NULL"),
      (
        reader,
        one(NodeValue.TextList(List(Some("/a"), Some("")))),
        "the location is an empty string"
      ),
      (
        reader,
        one(NodeValue.TextList(List.fill(3)(Some("y" * 1500)))),
        s"the location is longer than ${ComputedLocations.MaxBytes} bytes"
      ),
      (
        copy,
        one(NodeValue.TextList(List(Some("/a")))),
        "the location is a list, and a COPY takes one file name"
      ),
      (reader, NodeRows(Nil), "the location expression returned 0 rows"),
      (
        reader,
        NodeRows(List(List(NodeValue.Text("/a")), List(NodeValue.Text("/b")))),
        "the location expression returned 2 rows"
      )
    ).foreach { (location, rows, why) =>
      withClue(rows)(ComputedLocations.literal(location, rows) shouldBe Left(why))
    }
    val half = "é" * (ComputedLocations.MaxBytes / 2)
    ComputedLocations.literal(reader, one(NodeValue.Text(half))) shouldBe Right(s"'$half'")
    ComputedLocations.literal(reader, one(NodeValue.Text(half + "é"))).isLeft shouldBe true
  }
