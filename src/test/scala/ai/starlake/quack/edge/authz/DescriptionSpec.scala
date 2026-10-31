package ai.starlake.quack.edge.authz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8

class DescriptionSpec extends AnyFlatSpec with Matchers:

  private def bytes(s: String) = s.getBytes(UTF_8)

  private val Example =
    """{"control":[{"op":"copy_to","target":"s3://b/out/x.parquet","type":"parquet"}],
      |"functions":[{"args":[],"catalog":"system","name":"lower","schema":"main","type":"scalar"},
      |{"args":["s3://b/in/x.csv",null],"catalog":"system","name":"read_csv","schema":"main","type":"table","via":"lake.main.v"}],
      |"objects":[{"access":"read","catalog":"lake","catalogType":"ducklake","columns":["id","v"],
      |"kind":"table","name":"t","schema":"main","via":null}],
      |"settings":[{"name":"timezone","scope":"session","value":"UTC"},{"name":"schema","scope":"session","value":null}],
      |"statement":"PROJECTION","v":1}""".stripMargin

  "Description.decode" should "read the contract's description v1" in {
    Description.decode(bytes(Example)) shouldBe Right(
      Description(
        "PROJECTION",
        List(
          DescribedObject("table", "read", "lake", "ducklake", "main", "t", List("id", "v"), None)
        ),
        List(
          DescribedFunction("system", "main", "lower", "scalar", Nil, None),
          DescribedFunction(
            "system",
            "main",
            "read_csv",
            "table",
            List(Some("s3://b/in/x.csv"), None),
            Some("lake.main.v")
          )
        ),
        List(
          DescribedSetting("timezone", "session", Some("UTC")),
          DescribedSetting("schema", "session", None)
        ),
        List(DescribedControl("copy_to", Some("s3://b/out/x.parquet"), Some("parquet")))
      )
    )
  }

  it should "read a DuckLake object's renamed flag, absent as false" in {
    val renamed = Example.replace("\"name\":\"t\",", "\"name\":\"t\",\"renamed\":true,")
    Description.decode(bytes(renamed)).map(_.objects.map(_.renamed)) shouldBe Right(List(true))
    Description.decode(bytes(Example)).map(_.objects.map(_.renamed)) shouldBe Right(List(false))
    Description
      .decode(bytes(Example.replace("\"name\":\"t\",", "\"name\":\"t\",\"renamed\":1,")))
      .isLeft shouldBe true
  }

  it should "read a DuckLake object's dropped and replaced flags, absent as false" in {
    def flagged(f: String) = Example.replace("\"name\":\"t\",", s"\"$f\":true,\"name\":\"t\",")
    Description
      .decode(bytes(flagged("dropped")))
      .map(_.objects.map(o => (o.dropped, o.replaced))) shouldBe
      Right(List((true, false)))
    Description
      .decode(bytes(flagged("replaced")))
      .map(_.objects.map(o => (o.dropped, o.replaced))) shouldBe
      Right(List((false, true)))
    Description.decode(bytes(Example)).map(_.objects.map(o => (o.dropped, o.replaced))) shouldBe
      Right(List((false, false)))
  }

  it should "refuse another version, a missing field, and what is not JSON" in {
    Description.decode(bytes(Example.replace("\"v\":1", "\"v\":2"))).isLeft shouldBe true
    Description.decode(bytes(Example.replace(",\"v\":1", ""))).isLeft shouldBe true
    Description
      .decode(bytes(Example.replace("\"statement\":\"PROJECTION\",", "")))
      .isLeft shouldBe true
    Description.decode(bytes("not json")).isLeft shouldBe true
    Description.decode(bytes("[]")).isLeft shouldBe true
  }

  "ScreenDescription.decode" should "read the contract's screen description v1" in {
    val screen =
      """{"locations":[{"access":"read","given":"s3://acme-etl/in/*.csv","resolved":"s3://acme-etl/in/*.csv","via":"read_csv"}],"v":1}"""
    ScreenDescription.decode(bytes(screen)) shouldBe Right(
      ScreenDescription(
        List(
          DescribedLocation("read", "s3://acme-etl/in/*.csv", "s3://acme-etl/in/*.csv", "read_csv")
        )
      )
    )
    ScreenDescription.decode(bytes(screen.replace("\"v\":1", "\"v\":0"))).isLeft shouldBe true
    ScreenDescription.decode(bytes("""{"v":1}""")).isLeft shouldBe true
  }
