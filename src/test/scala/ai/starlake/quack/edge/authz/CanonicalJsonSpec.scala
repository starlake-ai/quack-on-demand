package ai.starlake.quack.edge.authz

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*

class CanonicalJsonSpec extends AnyFlatSpec with Matchers:

  "render" should "sort object keys by their UTF-8 bytes and drop whitespace" in {
    val j = Json.obj(
      "b" -> Json.fromInt(1),
      "a" -> Json.arr(Json.fromInt(2), Json.Null, Json.True),
      "B" -> Json.False,
      "é" -> Json.fromString("x"),
      "z" -> Json.obj("y" -> Json.fromInt(-3), "x" -> Json.obj())
    )
    CanonicalJson.render(j) shouldBe
      """{"B":false,"a":[2,null,true],"b":1,"z":{"x":{},"y":-3},"é":"x"}"""
  }

  it should "escape exactly the contract's characters, controls as lowercase \\u00XX" in {
    val s = "q\" b\\ \b\f\n\r\t \u0001\u001f /   é \u007f"
    CanonicalJson.render(Json.fromString(s)) shouldBe
      "\"q\\\" b\\\\ \\b\\f\\n\\r\\t \\u0001\\u001f /   é \u007f\""
  }

  it should "keep array order and render large integers exactly" in {
    CanonicalJson.render(Json.arr(Json.fromInt(3), Json.fromInt(1))) shouldBe "[3,1]"
    CanonicalJson.render(Json.fromLong(Long.MaxValue)) shouldBe Long.MaxValue.toString
  }

  it should "refuse a number that is not an integer" in {
    val e = intercept[IllegalArgumentException](CanonicalJson.render(Json.fromDoubleOrNull(1.5)))
    e.getMessage should include("integers only")
  }

  "sortedArray" should "order elements by their canonical bytes and drop exact duplicates" in {
    val a = Json.obj("k" -> Json.fromString("b"))
    val b = Json.obj("k" -> Json.fromString("a"))
    CanonicalJson.render(CanonicalJson.sortedArray(List(a, b, a))) shouldBe
      """[{"k":"a"},{"k":"b"}]"""
    CanonicalJson.render(
      CanonicalJson.sortedArray(List("r-2", "r-10", "r-1").map(Json.fromString))
    ) shouldBe """["r-1","r-10","r-2"]"""
  }

  "render" should "write exactly the cross-language golden qod-node's json_writer_test replays" in {
    // Task 1's native/qod-node/tests/golden/canonical-json-v1.txt: `in:` any JSON, `out:` its
    // canonical form, so the Scala and C++ writers cannot drift.
    val lines = java.nio.file.Files
      .readAllLines(
        java.nio.file.Paths.get("native/qod-node/tests/golden/canonical-json-v1.txt"),
        java.nio.charset.StandardCharsets.UTF_8
      )
      .asScala
      .toList
      .filterNot(l => l.startsWith("#") || l.isBlank)
    val pairs = lines.grouped(2).toList
    pairs.size shouldBe 7
    pairs.foreach {
      case List(in, out) if in.startsWith("in: ") && out.startsWith("out: ") =>
        val parsed = io.circe.parser.parse(in.drop(4)).fold(e => fail(e.getMessage), identity)
        withClue(s"$in: ")(CanonicalJson.render(parsed) shouldBe out.drop(5))
      case other => fail(s"malformed golden pair: $other")
    }
  }

  "sha256Hex" should "hash the canonical bytes" in {
    CanonicalJson.sha256Hex(Json.obj()) shouldBe
      "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    CanonicalJson.sha256Hex(Json.obj("b" -> Json.True, "a" -> Json.False)) shouldBe
      CanonicalJson.sha256Hex(Json.obj("a" -> Json.False, "b" -> Json.True))
  }
