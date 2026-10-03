package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Column resolution, plus the probed-type half of the grammar (value checks, operator/type
  * compatibility).
  */
class RestResolverSpec extends AnyFlatSpec with Matchers:

  private val int     = ColumnKind.Integer("INTEGER", BigInt(Int.MinValue), BigInt(Int.MaxValue))
  private val tinyint = ColumnKind.Integer("TINYINT", BigInt(-128), BigInt(127))
  private val ubig    = ColumnKind.Integer("UBIGINT", BigInt(0), (BigInt(1) << 64) - 1)

  private val cols = Vector(
    ProbedColumn("Id", int),
    ProbedColumn("name", ColumnKind.Text),
    ProbedColumn("price", ColumnKind.Decimal(6, 2)),
    ProbedColumn("ratio", ColumnKind.Floating("DOUBLE")),
    ProbedColumn("flag", ColumnKind.Bool),
    ProbedColumn("day", ColumnKind.Date),
    ProbedColumn("at", ColumnKind.Time),
    ProbedColumn("ts", ColumnKind.Timestamp("TIMESTAMP")),
    ProbedColumn("tsns", ColumnKind.Timestamp("TIMESTAMP_NS")),
    ProbedColumn("tstz", ColumnKind.TimestampTz),
    ProbedColumn("tags", ColumnKind.Opaque("LIST")),
    ProbedColumn("raw", ColumnKind.Opaque("BLOB")),
    ProbedColumn("small", tinyint),
    ProbedColumn("big", ubig)
  )

  private def resolve(ps: (String, String)*)(columns: Vector[ProbedColumn] = cols) =
    RestQuery.parse(ps).flatMap(RestResolver.resolve(_, columns))
  private def code(ps: (String, String)*): String =
    resolve(ps*)().fold(_.code, r => fail(s"expected an error, got $r"))
  private def ok(ps: (String, String)*): ResolvedQuery =
    resolve(ps*)().fold(e => fail(s"unexpected ${e.code}: ${e.message}"), identity)

  "resolve" should "select every probed column in probe order by default" in {
    ok().select shouldBe cols
  }

  it should "match columns ASCII-case-insensitively and always use the probe's spelling" in {
    val r = ok("select" -> "ID,NAME", "iD" -> "eq.1", "order" -> "Name.desc")
    r.select.map(_.name) shouldBe Vector("Id", "name")
    r.filters.head.column.name shouldBe "Id"
    r.order.head.column.name shouldBe "name"
  }

  it should "not fold the Kelvin sign onto k" in {
    val kcols = Vector(ProbedColumn("k", int))
    resolve("K" -> "eq.1")(kcols).left.map(_.code) shouldBe Left("unknown_column")
    resolve("select" -> "K")(kcols).left.map(_.code) shouldBe Left("unknown_column")
    resolve("K" -> "eq.1")(kcols).isRight shouldBe true
  }

  it should "refuse unknown and ambiguous columns in select, filters and order" in {
    code("select" -> "nope") shouldBe "unknown_column"
    code("nope" -> "eq.1") shouldBe "unknown_column"
    code("order" -> "nope") shouldBe "unknown_column"
    val amb = Vector(ProbedColumn("a", int), ProbedColumn("A", int))
    resolve("a" -> "eq.1")(amb).left.map(_.code) shouldBe Left("unknown_column")
    resolve("select" -> "A")(amb).left.map(_.code) shouldBe Left("unknown_column")
  }

  it should "answer reserved_column for a filter-shaped reserved name whatever the table holds" in {
    val withLimit = cols :+ ProbedColumn("limit", int)
    for columns <- List(cols, withLimit) do
      resolve("limit" -> "eq.5")(columns).left.map(_.code) shouldBe Left("reserved_column")
      resolve("LIMIT" -> "eq.5")(columns).left.map(_.code) shouldBe Left("reserved_column")
      resolve("branch" -> "eq.1")(columns).left.map(_.code) shouldBe Left("reserved_column")
    // A plain limit stays a limit either way.
    resolve("limit" -> "5")(withLimit).map(_.limit) shouldBe Right(Some(5))
    // order=eq.asc is filter-shaped, so it is refused even where a column is named eq.
    val withEq = cols :+ ProbedColumn("eq", int)
    resolve("order" -> "eq.asc")(withEq).left.map(_.code) shouldBe Left("reserved_column")
    resolve("order" -> "eq")(withEq).map(_.order.map(_.column.name)) shouldBe Right(Vector("eq"))
  }

  it should "answer not_found when the probe shows no column at all" in {
    resolve()(Vector.empty).left.map(_.code) shouldBe Left("not_found")
  }

  it should "admit like and ilike on string columns only" in {
    ok("name" -> "like.a*").filters should have size 1
    ok("name" -> "not.ilike.a*").filters should have size 1
    code("Id" -> "like.1*") shouldBe "invalid_filter"
    code("day" -> "ilike.2024*") shouldBe "invalid_filter"
  }

  it should "refuse a filter or an order on nested and blob columns, but let them be selected" in {
    ok("select" -> "tags,raw").select.map(_.name) shouldBe Vector("tags", "raw")
    code("tags" -> "is.null") shouldBe "invalid_filter"
    code("raw" -> "eq.x") shouldBe "invalid_filter"
    code("order" -> "tags") shouldBe "invalid_parameter"
  }

  it should "admit is.true and is.false on boolean columns only, is.null on any comparable one" in {
    ok("flag" -> "is.true", "name" -> "is.null", "Id" -> "not.is.null").filters should have size 3
    code("name" -> "is.true") shouldBe "invalid_filter"
  }

  it should "range-check integers of each width" in {
    ok("small" -> "eq.127", "small" -> "eq.-128").filters should have size 2
    code("small" -> "eq.128") shouldBe "invalid_filter"
    code("Id" -> "eq.2147483648") shouldBe "invalid_filter"
    code("Id" -> "eq.1.0") shouldBe "invalid_filter"
    code("Id" -> "eq.1e3") shouldBe "invalid_filter"
    ok("big" -> "eq.18446744073709551615").filters should have size 1
    code("big" -> "eq.18446744073709551616") shouldBe "invalid_filter"
    code("big" -> "eq.-1") shouldBe "invalid_filter"
    code("Id" -> "in.(1,x)") shouldBe "invalid_filter"
  }

  it should "check decimals without exponent against precision and scale" in {
    ok("price" -> "eq.9999.99", "price" -> "eq.-1.5", "price" -> "eq.1.500").filters should
      have size 3
    code("price" -> "eq.1e3") shouldBe "invalid_filter"
    code("price" -> "eq.1e999999999") shouldBe "invalid_filter"
    code("price" -> "eq.10000") shouldBe "invalid_filter"
    code("price" -> "eq.1.234") shouldBe "invalid_filter"
    code("price" -> "eq..5") shouldBe "invalid_filter"
  }

  it should "check floats, booleans, dates, times and timestamps" in {
    ok("ratio" -> "eq.1.5e10", "ratio" -> "neq.NaN", "ratio" -> "lt.-Infinity").filters should
      have size 3
    code("ratio" -> "eq.abc") shouldBe "invalid_filter"
    ok("flag" -> "eq.true").filters should have size 1
    code("flag" -> "eq.TRUE") shouldBe "invalid_filter"
    ok("day" -> "eq.2024-02-29").filters should have size 1
    code("day" -> "eq.2023-02-29") shouldBe "invalid_filter"
    code("day" -> "eq.2024-1-1") shouldBe "invalid_filter"
    ok("at" -> "gt.10:00:00.5").filters should have size 1
    code("at" -> "gt.25:00:00") shouldBe "invalid_filter"
    ok("ts" -> "gt.2024-01-01T10:00:00.123456").filters should have size 1
    code("ts" -> "gt.2024-01-01T10:00:00.1234567") shouldBe "invalid_filter"
    ok("tsns" -> "gt.2024-01-01T10:00:00.123456789").filters should have size 1
    // A zone on a naive timestamp would be silently dropped by DuckDB's cast, so it is refused.
    code("ts" -> "gt.2024-01-01T10:00:00Z") shouldBe "invalid_filter"
    ok("tstz" -> "gt.2024-01-01T10:00:00Z", "tstz" -> "lt.2024-01-01T10:00:00+02:00").filters should
      have size 2
    code("tstz" -> "gt.2024-01-01T10:00:00") shouldBe "invalid_filter"
  }

  it should "pass strings through untouched" in {
    ok("name" -> "eq.' OR 1=1 --").filters.head.predicate shouldBe
      Predicate.Compare(Comparison.Eq, "' OR 1=1 --")
  }

  it should "never echo a value in a type error" in {
    val e = resolve("Id" -> "eq.secret")().left.toOption.get
    e.message should not include "secret"
    e.message should include("Id")
  }
