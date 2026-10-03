package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Format negotiation: `format` beats `Accept`, JSON is the fallback, the documents serve JSON and
  * CSV and `/rows` adds Arrow IPC.
  */
class RestFormatSpec extends AnyFlatSpec with Matchers:

  private def neg(format: Option[String], accept: Option[String]) =
    RestFormat.negotiate(format, accept).left.map(_.code)

  "negotiate" should "fall back to JSON with neither format nor Accept" in {
    neg(None, None) shouldBe Right(RestFormat.Json)
    neg(None, Some("")) shouldBe Right(RestFormat.Json)
    neg(None, Some("*/*")) shouldBe Right(RestFormat.Json)
  }

  it should "let the format parameter win over Accept" in {
    neg(Some("csv"), Some("application/json")) shouldBe Right(RestFormat.Csv)
    neg(Some("json"), Some("text/csv")) shouldBe Right(RestFormat.Json)
    // Even an Accept the edge cannot serve is ignored once format is given.
    neg(Some("csv"), Some("application/vnd.apache.arrow.stream")) shouldBe Right(RestFormat.Csv)
  }

  it should "refuse arrow on a document, and parquet and unknown formats, with 406" in {
    for f <- List("arrow", "parquet", "xml", "JSONX", "") do
      withClue(f)(neg(Some(f), None) shouldBe Left("unsupported_format"))
    RestFormat.negotiate(Some("arrow"), None).left.map(_.status.code) shouldBe Left(406)
  }

  it should "serve arrow and parquet on /rows, by name or by Accept" in {
    def rows(format: Option[String], accept: Option[String]) =
      RestFormat.negotiate(format, accept, RestFormat.Rows).left.map(_.code)
    rows(Some("arrow"), None) shouldBe Right(RestFormat.Arrow)
    rows(Some("ARROW"), Some("application/json")) shouldBe Right(RestFormat.Arrow)
    rows(None, Some("application/vnd.apache.arrow.stream")) shouldBe Right(RestFormat.Arrow)
    rows(None, Some("application/json;q=0.5, application/vnd.apache.arrow.stream")) shouldBe
      Right(RestFormat.Arrow)
    rows(Some("parquet"), None) shouldBe Right(RestFormat.Parquet)
    rows(None, Some("application/vnd.apache.parquet")) shouldBe Right(RestFormat.Parquet)
    RestFormat
      .negotiate(Some("parquet"), None, RestFormat.Rows - RestFormat.Parquet)
      .left
      .map(_.code) shouldBe Left("unsupported_format")
    rows(None, None) shouldBe Right(RestFormat.Json)
    RestFormat.Arrow.streamed shouldBe true
    RestFormat.Parquet.streamed shouldBe true
    List(RestFormat.Json, RestFormat.Csv).foreach(_.streamed shouldBe false)
  }

  it should "read Accept with q-values and wildcards" in {
    neg(None, Some("text/csv")) shouldBe Right(RestFormat.Csv)
    neg(None, Some("text/csv; charset=utf-8")) shouldBe Right(RestFormat.Csv)
    neg(None, Some("application/json")) shouldBe Right(RestFormat.Json)
    neg(None, Some("text/csv;q=0.5, application/json;q=0.9")) shouldBe Right(RestFormat.Json)
    neg(None, Some("application/json;q=0.1, text/csv")) shouldBe Right(RestFormat.Csv)
    neg(None, Some("application/vnd.apache.arrow.stream, text/*;q=0.2")) shouldBe
      Right(RestFormat.Csv)
    neg(None, Some("application/*")) shouldBe Right(RestFormat.Json)
    neg(None, Some("TEXT/CSV")) shouldBe Right(RestFormat.Csv)
  }

  it should "refuse an Accept that names only unsupported types" in {
    neg(None, Some("application/vnd.apache.arrow.stream")) shouldBe Left("unsupported_format")
    neg(None, Some("application/vnd.apache.parquet")) shouldBe Left("unsupported_format")
    neg(None, Some("application/json;q=0")) shouldBe Left("unsupported_format")
  }

  "content types" should "be JSON and UTF-8 CSV" in {
    RestFormat.Json.contentType shouldBe "application/json"
    RestFormat.Csv.contentType shouldBe "text/csv; charset=utf-8"
    RestFormat.Arrow.contentType shouldBe "application/vnd.apache.arrow.stream"
    RestFormat.Parquet.contentType shouldBe "application/vnd.apache.parquet"
  }
