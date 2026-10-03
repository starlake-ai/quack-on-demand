package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.adapter.TestArrow
import io.circe.Json
import io.circe.parser.parse as parseJson
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.{Random, Using}

/** JSON and CSV for each type family, exactly as DuckDB exports them, plus the row cap. Inputs come
  * from [[TestArrow.readerFor]], the same Arrow export the probe and the data statement go through.
  */
class RestResultEncoderSpec extends AnyFlatSpec with Matchers:

  private def json(sql: String, maxRows: Int = 100): RestResultEncoder.Encoded =
    Using.resource(TestArrow.readerFor(sql))(RestResultEncoder.json(_, maxRows))
  private def csv(sql: String, maxRows: Int = 100): RestResultEncoder.Encoded =
    Using.resource(TestArrow.readerFor(sql))(RestResultEncoder.csv(_, maxRows))

  /** The text of the single value of a one-row, one-column JSON result. */
  private def one(expr: String): String =
    val body = json(s"SELECT $expr AS v").body
    body should startWith("[{\"v\":")
    body.stripPrefix("[{\"v\":").stripSuffix("}]")

  "json" should "encode an array of objects with keys in select order" in {
    json("SELECT 2 AS b, 'x' AS a, 3 AS c UNION ALL SELECT 4, 'y', 5").body shouldBe
      """[{"b":2,"a":"x","c":3},{"b":4,"a":"y","c":5}]"""
    json("SELECT 1 AS a WHERE false").body shouldBe "[]"
  }

  it should "encode integers up to 64 bits as numbers, at their bounds" in {
    one("(-9223372036854775808)::BIGINT") shouldBe "-9223372036854775808"
    one("9223372036854775807::BIGINT") shouldBe "9223372036854775807"
    one("18446744073709551615::UBIGINT") shouldBe "18446744073709551615"
    one("(-128)::TINYINT") shouldBe "-128"
    one("65535::USMALLINT") shouldBe "65535"
    one("4294967295::UINTEGER") shouldBe "4294967295"
    one("255::UTINYINT") shouldBe "255"
  }

  it should "encode HUGEINT (exported as DECIMAL(38,0)) as a string" in {
    one("170141183460469231731687303715884105727::HUGEINT") shouldBe
      "\"170141183460469231731687303715884105727\""
    one("(-170141183460469231731687303715884105727)::HUGEINT") shouldBe
      "\"-170141183460469231731687303715884105727\""
  }

  it should "encode DECIMAL as an exact plain number, never through a double" in {
    one("1234567890123456789012345678.1234567891::DECIMAL(38,10)") shouldBe
      "1234567890123456789012345678.1234567891"
    one("0.0000000001::DECIMAL(38,10)") shouldBe "0.0000000001"
    one("(-1.50)::DECIMAL(4,2)") shouldBe "-1.50"
  }

  it should "encode floats, and NaN and infinities as strings" in {
    one("1.5::DOUBLE") shouldBe "1.5"
    one("1.1::FLOAT") shouldBe "1.1"
    one("'nan'::DOUBLE") shouldBe "\"NaN\""
    one("'inf'::DOUBLE") shouldBe "\"Infinity\""
    one("'-inf'::FLOAT") shouldBe "\"-Infinity\""
  }

  it should "encode dates, times and timestamps as ISO-8601, with Z only when zoned" in {
    one("DATE '2024-02-29'") shouldBe "\"2024-02-29\""
    one("DATE '1969-12-31'") shouldBe "\"1969-12-31\""
    one("TIME '10:00:00'") shouldBe "\"10:00:00\""
    one("TIME '10:00:00.123456'") shouldBe "\"10:00:00.123456\""
    one("TIMESTAMP '2024-01-01 10:00:00.5'") shouldBe "\"2024-01-01T10:00:00.5\""
    one("TIMESTAMP '1969-12-31 23:59:59.999999'") shouldBe "\"1969-12-31T23:59:59.999999\""
    one("'2024-01-01 10:00:00'::TIMESTAMP_S") shouldBe "\"2024-01-01T10:00:00\""
    one("'2024-01-01 10:00:00.123'::TIMESTAMP_MS") shouldBe "\"2024-01-01T10:00:00.123\""
    one("'2024-01-01 10:00:00.123456789'::TIMESTAMP_NS") shouldBe
      "\"2024-01-01T10:00:00.123456789\""
    one("'2024-01-01 12:00:00+02'::TIMESTAMPTZ") shouldBe "\"2024-01-01T10:00:00Z\""
  }

  it should "encode BLOB as base64, and strings, booleans, UUIDs and ENUMs as themselves" in {
    one("'\\x00\\xFF\\x10'::BLOB") shouldBe "\"AP8Q\""
    one("'hé \"q\"'") shouldBe "\"hé \\\"q\\\"\""
    one("true") shouldBe "true"
    one("'00000000-0000-0000-0000-000000000001'::UUID") shouldBe
      "\"00000000-0000-0000-0000-000000000001\""
    one("'b'::ENUM('a', 'b')") shouldBe "\"b\""
  }

  it should "encode an interval as an ISO-8601 duration string" in {
    one("INTERVAL '1 month 2 days 3 seconds'") shouldBe "\"P1M2DT3S\""
  }

  it should "encode nested types as nested JSON, with NULL at every depth" in {
    one("[1, NULL, 3]") shouldBe "[1,null,3]"
    one("[[1], NULL, []]::INTEGER[][]") shouldBe "[[1],null,[]]"
    one("{'a': 1, 'b': NULL, 'c': {'d': [NULL]}}") shouldBe
      """{"a":1,"b":null,"c":{"d":[null]}}"""
    one("[1, 2]::INTEGER[2]") shouldBe "[1,2]"
    one("MAP {'k': 1, 'z': NULL}") shouldBe
      """[{"key":"k","value":1},{"key":"z","value":null}]"""
    one("union_value(n := 7)") shouldBe "7"
    one("[1.25::DECIMAL(5,2), NULL]") shouldBe "[1.25,null]"
    one("NULL::INTEGER") shouldBe "null"
    one("NULL::INTEGER[]") shouldBe "null"
    one("NULL::STRUCT(a INTEGER)") shouldBe "null"
    one("NULL::VARCHAR") shouldBe "null"
  }

  it should "produce a document circe parses back" in {
    val body = json("SELECT 1 AS a, 'x\ny' AS b, [1] AS c").body
    parseJson(body).map(_.asArray.map(_.size)) shouldBe Right(Some(1))
    parseJson(body).toOption.get.asArray.get.head.hcursor.get[String]("b") shouldBe Right("x\ny")
  }

  "the row cap" should "stop after N rows and report whether more existed" in {
    val sql = "SELECT * FROM range(5) t(i)"
    val all = json(sql, 5); (all.rows, all.more) shouldBe (5, false)
    val cut = json(sql, 3); (cut.rows, cut.more) shouldBe (3, true)
    cut.body shouldBe """[{"i":0},{"i":1},{"i":2}]"""
    val big = json(sql, 100); (big.rows, big.more) shouldBe (5, false)
    val c   = csv(sql, 2); (c.rows, c.more) shouldBe (2, true)
    c.body shouldBe "i\r\n0\r\n1\r\n"
  }

  it should "see rows beyond a batch boundary" in {
    // TestArrow exports 1024-row batches: 2048 rows fill exactly two.
    val sql = "SELECT * FROM range(2049) t(i)"
    val a   = json(sql, 1024); (a.rows, a.more) shouldBe (1024, true)
    val b   = json(sql, 2049); (b.rows, b.more) shouldBe (2049, false)
    val c   = json("SELECT * FROM range(2048) t(i)", 2048); (c.rows, c.more) shouldBe (2048, false)
    parseJson(b.body).toOption.flatMap(_.asArray).map(_.last) shouldBe
      Some(Json.obj("i" -> Json.fromLong(2048)))
  }

  "csv" should "write a header, CRLF line endings and RFC 4180 quoting" in {
    csv(
      "SELECT 1 AS \"id\", 'a,b' AS \"x,y\", 'say \"hi\"' AS q, 'l1\nl2' AS nl, 'plain' AS p"
    ).body shouldBe
      "id,\"x,y\",q,nl,p\r\n1,\"a,b\",\"say \"\"hi\"\"\",\"l1\nl2\",plain\r\n"
  }

  it should "write NULL as an empty field and an empty string as a quoted empty field" in {
    csv("SELECT NULL::VARCHAR AS a, '' AS b, 1 AS c").body shouldBe "a,b,c\r\n,\"\",1\r\n"
  }

  it should "write every type family with the JSON scalar rules and nested values as JSON text" in {
    csv(
      "SELECT 1.50::DECIMAL(4,2) AS d, 170141183460469231731687303715884105727::HUGEINT AS h," +
        " 'nan'::DOUBLE AS f, true AS b, DATE '2024-01-01' AS dt," +
        " '2024-01-01 12:00:00+02'::TIMESTAMPTZ AS tz, '\\x01'::BLOB AS bl," +
        " {'a': [1, NULL]} AS n"
    ).body shouldBe
      "d,h,f,b,dt,tz,bl,n\r\n" +
      "1.50,170141183460469231731687303715884105727,NaN,true,2024-01-01," +
      "2024-01-01T10:00:00Z,AQ==,\"{\"\"a\"\":[1,null]}\"\r\n"
  }

  it should "write only the header for an empty result" in {
    csv("SELECT 1 AS a, 2 AS b WHERE false").body shouldBe "a,b\r\n"
  }

  it should "keep a value that looks like a spreadsheet formula exactly as stored" in {
    csv("SELECT '=1+1' AS a, '-2' AS b, '@x' AS c").body shouldBe "a,b,c\r\n=1+1,-2,@x\r\n"
  }

  "the byte cap" should "cut a JSON page at a row boundary, never past the cap" in {
    // Each row is {"i":<n>} (7 bytes for n < 10), plus a comma before every row but the first.
    val sql  = "SELECT range::INTEGER AS i FROM range(9)"
    val full = json(sql)
    full.cut shouldBe false
    val cap = 2L + 7 + 8 + 8 // the brackets and three rows
    val b   = Using.resource(TestArrow.readerFor(sql))(RestResultEncoder.json(_, 100, cap))
    b.body shouldBe """[{"i":0},{"i":1},{"i":2}]"""
    b.body.getBytes("UTF-8").length.toLong should be <= cap
    (b.rows, b.more, b.cut) shouldBe (3, true, true)
    // One byte less drops the third row whole.
    val c = Using.resource(TestArrow.readerFor(sql))(RestResultEncoder.json(_, 100, cap - 1))
    (c.body, c.rows, c.cut) shouldBe ("""[{"i":0},{"i":1}]""", 2, true)
  }

  it should "count UTF-8 bytes, not chars, and cut a CSV page after its header" in {
    // "é" is two bytes: the header "s\r\n" is 3 bytes and each row "é\r\n" 4.
    val sql = "SELECT 'é' AS s FROM range(5)"
    val b   = Using.resource(TestArrow.readerFor(sql))(RestResultEncoder.csv(_, 100, 3L + 4 * 2))
    b.body shouldBe "s\r\né\r\né\r\n"
    (b.rows, b.more, b.cut) shouldBe (2, true, true)
    val zero = Using.resource(TestArrow.readerFor(sql))(RestResultEncoder.csv(_, 100, 6L))
    (zero.body, zero.rows, zero.cut) shouldBe ("s\r\n", 0, true)
  }

  it should "hold the cap on multi-byte text, cutting at a row boundary of the full page" in {
    val seed = Random.nextLong()
    info(s"seed = $seed")
    val rnd = new Random(seed)
    // Two-, three- and four-byte characters, so the cap never lands on a character boundary.
    val sql = "SELECT range::INTEGER AS i, repeat('é漢😀', (range % 7)::INTEGER) AS s FROM range(40)"
    val pages = List(json(sql), csv(sql))
    (1 to 200).foreach { _ =>
      // At least the empty page ("[]", or the CSV header "i,s\r\n").
      val cap = 5L + rnd.nextInt(pages.map(_.bytes.length).max)
      List(RestFormat.Json, RestFormat.Csv).zip(pages).foreach { (fmt, full) =>
        val b = Using.resource(TestArrow.readerFor(sql))(RestResultEncoder.encode(fmt, _, 100, cap))
        withClue(s"seed=$seed fmt=$fmt cap=$cap: ") {
          b.bytes.length.toLong should be <= cap
          b.body.getBytes(UTF_8).length shouldBe b.bytes.length
          if b.cut then
            // Whole rows of the full page, never a partial row or character.
            fmt match
              case RestFormat.Json =>
                parseJson(b.body).toOption.flatMap(_.asArray).map(_.size) shouldBe Some(b.rows)
                full.body.startsWith(b.body.stripSuffix("]")) shouldBe true
              case _ =>
                full.body.startsWith(b.body) shouldBe true
                b.body.count(_ == '\n') shouldBe b.rows + 1
          else b.body shouldBe full.body
        }
      }
    }
  }

  it should "hand back one buffer holding exactly the body's UTF-8 bytes" in {
    val b = json("SELECT 'é漢😀' AS s")
    b.bytes.length shouldBe b.body.getBytes(UTF_8).length
    b.bytes.buffer.remaining shouldBe b.bytes.length
  }

  it should "not flag a page the row cap ended first" in {
    val b = Using.resource(TestArrow.readerFor("SELECT range AS i FROM range(5)"))(
      RestResultEncoder.json(_, 2, 1000L)
    )
    (b.rows, b.more, b.cut) shouldBe (2, true, false)
  }
