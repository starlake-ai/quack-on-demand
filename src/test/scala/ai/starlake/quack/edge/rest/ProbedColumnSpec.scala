package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.adapter.TestArrow
import ai.starlake.quack.model.SqlLiterals
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Using

/** Pins the Arrow -> DuckDB type mapping against what the pinned DuckDB actually exports: every row
  * runs `SELECT <expr> AS c LIMIT 0` through [[TestArrow.readerFor]], the same export the schema
  * probe goes through, and every comparable kind is checked to round-trip through
  * `CAST(<literal> AS <sqlType>)` in the same DuckDB.
  */
class ProbedColumnSpec extends AnyFlatSpec with Matchers:

  private def probe(expr: String): ProbedColumn =
    val r = TestArrow.readerFor(s"SELECT $expr AS c LIMIT 0")
    try ProbedColumn.fromArrow(r.getVectorSchemaRoot.getSchema).head
    finally r.close()

  // (DuckDB expression, sqlType the edge casts to, comparable, a literal equal to the value)
  private val table: List[(String, String, Boolean, String)] = List(
    ("1::TINYINT", "TINYINT", true, "1"),
    ("1::SMALLINT", "SMALLINT", true, "1"),
    ("1::INTEGER", "INTEGER", true, "1"),
    ("1::BIGINT", "BIGINT", true, "1"),
    ("1::UTINYINT", "UTINYINT", true, "1"),
    ("1::USMALLINT", "USMALLINT", true, "1"),
    ("1::UINTEGER", "UINTEGER", true, "1"),
    ("1::UBIGINT", "UBIGINT", true, "1"),
    ("1::HUGEINT", "DECIMAL(38,0)", true, "1"),
    ("1::UHUGEINT", "DECIMAL(38,0)", true, "1"),
    ("1.5::DECIMAL(4,1)", "DECIMAL(4,1)", true, "1.5"),
    ("1.5::DECIMAL(18,2)", "DECIMAL(18,2)", true, "1.5"),
    ("1.5::DECIMAL(38,10)", "DECIMAL(38,10)", true, "1.5"),
    ("1.5::FLOAT", "FLOAT", true, "1.5"),
    ("1.5::DOUBLE", "DOUBLE", true, "1.5"),
    ("'a'::VARCHAR", "VARCHAR", true, "a"),
    ("'{}'::JSON", "VARCHAR", true, "{}"),
    (
      "'00000000-0000-0000-0000-000000000001'::UUID",
      "VARCHAR",
      true,
      "00000000-0000-0000-0000-000000000001"
    ),
    ("'b'::ENUM('a', 'b')", "VARCHAR", true, "b"),
    ("true", "BOOLEAN", true, "true"),
    ("DATE '2024-01-01'", "DATE", true, "2024-01-01"),
    ("TIME '10:00:00'", "TIME", true, "10:00:00"),
    ("TIMESTAMP '2024-01-01 10:00:00'", "TIMESTAMP", true, "2024-01-01T10:00:00"),
    ("'2024-01-01 10:00:00'::TIMESTAMP_S", "TIMESTAMP_S", true, "2024-01-01T10:00:00"),
    ("'2024-01-01 10:00:00'::TIMESTAMP_MS", "TIMESTAMP_MS", true, "2024-01-01T10:00:00"),
    ("'2024-01-01 10:00:00'::TIMESTAMP_NS", "TIMESTAMP_NS", true, "2024-01-01T10:00:00"),
    (
      "'2024-01-01 10:00:00+00'::TIMESTAMPTZ",
      "TIMESTAMP WITH TIME ZONE",
      true,
      "2024-01-01T10:00:00Z"
    ),
    ("'\\x01'::BLOB", "BLOB", false, ""),
    ("'101'::BIT", "BLOB", false, ""),
    ("INTERVAL 1 DAY", "INTERVAL", false, ""),
    ("[1, 2]", "LIST", false, ""),
    ("[1, 2]::INTEGER[2]", "LIST", false, ""),
    ("{'a': 1}", "STRUCT", false, ""),
    ("MAP {'k': 1}", "MAP", false, ""),
    ("union_value(a := 1)", "UNION", false, "")
  )

  "fromArrow" should "map each DuckDB type the way the pinned export presents it" in {
    for (expr, sqlType, comparable, _) <- table do
      withClue(expr) {
        val c = probe(expr)
        c.name shouldBe "c"
        c.kind.sqlType shouldBe sqlType
        c.filterable shouldBe comparable
        c.orderable shouldBe comparable
      }
  }

  it should "produce sqlTypes that round-trip a literal through CAST in DuckDB" in {
    Class.forName("org.duckdb.DuckDBDriver")
    Using.resource(DriverManager.getConnection("jdbc:duckdb:")) { conn =>
      for case (expr, _, true, literal) <- table do
        val c   = probe(expr)
        val sql =
          s"SELECT c = CAST(${SqlLiterals.duckdbLiteral(literal)} AS ${c.kind.sqlType}) " +
            s"FROM (SELECT $expr AS c)"
        withClue(sql) {
          Using.resource(conn.createStatement().executeQuery(sql)) { rs =>
            rs.next() shouldBe true
            rs.getBoolean(1) shouldBe true
          }
        }
    }
  }

  it should "keep probe order and the probe's spelling" in {
    val r = TestArrow.readerFor("SELECT 1 AS \"Zed\", 'x' AS \"a b\", 2 AS \"é\" LIMIT 0")
    try
      ProbedColumn.fromArrow(r.getVectorSchemaRoot.getSchema).map(_.name) shouldBe
        Vector("Zed", "a b", "é")
    finally r.close()
  }

  it should "carry integer ranges per width and signedness" in {
    probe("1::TINYINT").kind shouldBe ColumnKind.Integer("TINYINT", BigInt(-128), BigInt(127))
    probe("1::UBIGINT").kind shouldBe
      ColumnKind.Integer("UBIGINT", BigInt(0), (BigInt(1) << 64) - 1)
  }
