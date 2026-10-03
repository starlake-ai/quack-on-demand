package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Using

/** Golden SQL for every operator, the linear LIKE forms, the three-part name for each kind, `AT`,
  * `LIMIT n+1 OFFSET`, and the absence of ordinals and `*`; plus a semantic check of the same
  * statements in in-process DuckDB.
  */
class RestSqlSpec extends AnyFlatSpec with Matchers:

  private val int  = ColumnKind.Integer("INTEGER", BigInt(Int.MinValue), BigInt(Int.MaxValue))
  private val cols = Vector(
    ProbedColumn("id", int),
    ProbedColumn("name", ColumnKind.Text),
    ProbedColumn("price", ColumnKind.Decimal(6, 2)),
    ProbedColumn("ok", ColumnKind.Bool),
    ProbedColumn("tags", ColumnKind.Opaque("LIST"))
  )
  private val lake = RestSql.Target("lake", "main", "t", Some(7L))
  private val file = RestSql.Target("mydb", "sales", "orders", None)

  private def resolved(ps: (String, String)*): ResolvedQuery =
    RestQuery
      .parse(ps)
      .flatMap(RestResolver.resolve(_, cols))
      .fold(e => fail(s"${e.code}: ${e.message}"), identity)

  private def where(ps: (String, String)*): String =
    val sql = RestSql.render(file, resolved(ps*), 10)
    sql.substring(sql.indexOf(" WHERE ") + 7, sql.indexOf(" LIMIT "))

  "render" should "list every probed column explicitly when select is absent" in {
    RestSql.render(file, resolved(), 100) shouldBe
      """SELECT "id", "name", "price", "ok", "tags" FROM "mydb"."sales"."orders" LIMIT 101 OFFSET 0"""
  }

  it should "carry the snapshot pin, the select list, order and offset" in {
    RestSql.render(
      lake,
      resolved("select" -> "NAME,id", "order" -> "id.desc", "offset" -> "20"),
      5
    ) shouldBe
      """SELECT "name", "id" FROM "lake"."main"."t" AT (VERSION => 7) ORDER BY "id" DESC LIMIT 6 OFFSET 20"""
  }

  it should "qualify the memory kind's catalog as memory" in {
    val mem = RestSql.Target("memory", "main", "t", None)
    RestSql.render(mem, resolved("select" -> "id"), 1) shouldBe
      """SELECT "id" FROM "memory"."main"."t" LIMIT 2 OFFSET 0"""
  }

  it should "fetch one extra row even at the largest cap" in {
    RestSql.render(file, resolved("select" -> "id"), Int.MaxValue) should endWith(
      "LIMIT 2147483648 OFFSET 0"
    )
  }

  it should "render the comparison operators through CAST of a literal" in {
    where("id" -> "eq.1") shouldBe """"id" = CAST('1' AS INTEGER)"""
    where("id" -> "neq.1") shouldBe """"id" <> CAST('1' AS INTEGER)"""
    where("id" -> "gt.1") shouldBe """"id" > CAST('1' AS INTEGER)"""
    where("id" -> "gte.1") shouldBe """"id" >= CAST('1' AS INTEGER)"""
    where("id" -> "lt.1") shouldBe """"id" < CAST('1' AS INTEGER)"""
    where("id" -> "lte.1") shouldBe """"id" <= CAST('1' AS INTEGER)"""
    where("price" -> "not.eq.1.5") shouldBe """NOT ("price" = CAST('1.5' AS DECIMAL(6,2)))"""
    where("name" -> "eq.it's") shouldBe """"name" = CAST('it''s' AS VARCHAR)"""
  }

  it should "render in, is and their negations" in {
    where("id" -> "in.(1,2)") shouldBe
      """"id" IN (CAST('1' AS INTEGER), CAST('2' AS INTEGER))"""
    where("name" -> "not.in.(\"a,b\",c)") shouldBe
      """NOT ("name" IN (CAST('a,b' AS VARCHAR), CAST('c' AS VARCHAR)))"""
    where("name" -> "is.null") shouldBe """"name" IS NULL"""
    where("name" -> "not.is.null") shouldBe """"name" IS NOT NULL"""
    where("ok" -> "is.true") shouldBe """"ok" IS TRUE"""
    where("ok" -> "not.is.false") shouldBe """"ok" IS NOT FALSE"""
  }

  it should "render like as LIKE and ilike as lower() LIKE lower(), never ILIKE or ESCAPE" in {
    where("name" -> "like.a*b") shouldBe """"name" LIKE CAST('a%b' AS VARCHAR)"""
    where("name" -> "ilike.*x") shouldBe """lower("name") LIKE lower(CAST('%x' AS VARCHAR))"""
    where("name" -> "like.x\\y*") shouldBe """"name" LIKE CAST('x\y%' AS VARCHAR)"""
  }

  it should "render a pattern with a literal % or _ as equality, prefix, suffix or contains" in {
    where("name" -> "like.5%_\\") shouldBe """"name" = CAST('5%_\' AS VARCHAR)"""
    where("name" -> "like.5%*") shouldBe """starts_with("name", CAST('5%' AS VARCHAR))"""
    where("name" -> "like.*_c") shouldBe """ends_with("name", CAST('_c' AS VARCHAR))"""
    where("name" -> "ilike.*it's%*") shouldBe
      """contains(lower("name"), lower(CAST('it''s%' AS VARCHAR)))"""
    where("name" -> "not.ilike.a_") shouldBe
      """NOT (lower("name") = lower(CAST('a_' AS VARCHAR)))"""
  }

  it should "AND several filters in arrival order" in {
    where("id" -> "gt.1", "name" -> "eq.x", "id" -> "lt.9") shouldBe
      """"id" > CAST('1' AS INTEGER) AND "name" = CAST('x' AS VARCHAR) AND "id" < CAST('9' AS INTEGER)"""
  }

  it should "render every order suffix by column name" in {
    val sql = RestSql.render(
      file,
      resolved("order" -> "id,name.desc.nullsfirst,price.asc.nullslast,ok.nullsfirst"),
      1
    )
    sql should include(
      """ORDER BY "id" ASC, "name" DESC NULLS FIRST, "price" ASC NULLS LAST, "ok" ASC NULLS FIRST"""
    )
  }

  it should "never emit an ordinal in ORDER BY nor a * in the data statement" in {
    // Columns whose names are digits are still quoted identifiers, never positions.
    val numeric = Vector(ProbedColumn("1", int), ProbedColumn("2", ColumnKind.Text))
    val q       = RestQuery
      .parse(Seq("order" -> "2.desc,1", "1" -> "eq.1"))
      .flatMap(RestResolver.resolve(_, numeric))
      .toOption
      .get
    val sql = RestSql.render(file, q, 1)
    sql shouldBe
      """SELECT "1", "2" FROM "mydb"."sales"."orders" WHERE "1" = CAST('1' AS INTEGER) ORDER BY "2" DESC, "1" ASC LIMIT 2 OFFSET 0"""
    sql should not include "*"
    """ORDER BY [0-9]""".r.findFirstIn(sql) shouldBe None
  }

  "probe" should "select * with LIMIT 0 and the same AT pin" in {
    RestSql.probe(lake) shouldBe """SELECT * FROM "lake"."main"."t" AT (VERSION => 7) LIMIT 0"""
    RestSql.probe(file) shouldBe """SELECT * FROM "mydb"."sales"."orders" LIMIT 0"""
  }

  "listSchemas and listTables" should "interpolate catalog and schema only as literals" in {
    RestSql.listSchemas("my'db", 50) shouldBe
      "SELECT schema_name FROM information_schema.schemata WHERE catalog_name = 'my''db'" +
      " AND schema_name NOT IN ('information_schema', 'pg_catalog') ORDER BY schema_name LIMIT 51"
    RestSql.listTables("db", "s'x", 50) shouldBe
      "SELECT table_name, table_type FROM information_schema.tables" +
      " WHERE table_catalog = 'db' AND table_schema = 's''x' ORDER BY table_name LIMIT 51"
  }

  "the rendered statements" should "mean what the grammar says in DuckDB" in {
    Class.forName("org.duckdb.DuckDBDriver")
    Using.resource(DriverManager.getConnection("jdbc:duckdb:")) { conn =>
      val st = conn.createStatement()
      st.execute("CREATE SCHEMA s")
      st.execute(
        "CREATE TABLE s.t AS SELECT * FROM (VALUES (1, 'a%b', 1.50::DECIMAL(6,2), true)," +
          " (2, 'A_c', 2.00, false), (3, NULL, NULL, NULL), (4, 'x\\y', 4.25, true))" +
          " v(id, name, price, ok)"
      )
      st.execute("CREATE VIEW s.v AS SELECT id, name FROM s.t")
      val t                                     = RestSql.Target("memory", "s", "t", None)
      def ids(ps: (String, String)*): List[Int] =
        val sql = RestSql.render(t, resolved((("select" -> "id") +: ("order" -> "id") +: ps)*), 100)
        Using.resource(st.executeQuery(sql)) { rs =>
          Iterator.continually(rs.next()).takeWhile(identity).map(_ => rs.getInt(1)).toList
        }
      ids("id" -> "gt.1") shouldBe List(2, 3, 4)
      ids("id" -> "not.in.(1,2)") shouldBe List(3, 4)
      ids("name" -> "like.a%b") shouldBe List(1)
      ids("name" -> "like.a*") shouldBe List(1)
      ids("name" -> "ilike.a_c") shouldBe List(2)
      ids("name" -> "ilike.a*") shouldBe List(1, 2)
      ids("name" -> "ilike.A*") shouldBe List(1, 2)
      ids("name" -> "like.x\\y") shouldBe List(4)
      ids("name" -> "like.x\\*") shouldBe List(4)
      ids("name" -> "like.*%*") shouldBe List(1)
      ids("name" -> "like.a%*") shouldBe List(1)
      ids("name" -> "like.*_c") shouldBe List(2)
      ids("name" -> "ilike.*_C") shouldBe List(2)
      ids("name" -> "not.like.*_*") shouldBe List(1, 4)
      ids("name" -> "is.null") shouldBe List(3)
      ids("name" -> "not.is.null") shouldBe List(1, 2, 4)
      ids("ok" -> "is.true") shouldBe List(1, 4)
      ids("ok" -> "not.is.true") shouldBe List(2, 3)
      ids("price" -> "gte.2") shouldBe List(2, 4)
      ids("price" -> "eq.1.5") shouldBe List(1)
      ids("name" -> "not.eq.a%b") shouldBe List(2, 4)
      ids("offset" -> "1") shouldBe List(2, 3, 4)
      RestSql.render(t, resolved("select" -> "id"), 2) should endWith("LIMIT 3 OFFSET 0")
    }
  }
