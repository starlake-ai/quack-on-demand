package ai.starlake.quack.edge.opa

import ai.starlake.acl.model.TableRef
import ai.starlake.acl.parser.{TableAccess, Verb}
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpaInputSpec extends AnyFlatSpec with Matchers:

  private val target = OpaTarget("acme", "tpch", "bi", Nil)
  private val user   = OpaUser("alice", List("analyst"), List("emea"), Map("dept" -> "fin"))

  "OpaInput.connect" should "match the golden document" in:
    OpaInput.connect(target, user, "flightsql") shouldBe parse(
      """{"input":{"kind":"connect","tenant":"acme","database":"tpch","pool":"bi",
        |"parentPool":null,
        |"user":{"name":"alice","roles":["analyst"],"groups":["emea"],"claims":{"dept":"fin"}},
        |"client":{"edge":"flightsql"}}}""".stripMargin
    ).toOption.get

  it should "list parent pools for a branch pool" in:
    val doc =
      OpaInput.connect(target.copy(pool = "__br_ab12cd34", parentPools = List("bi")), user, "quack")
    doc.hcursor.downField("input").downField("parentPool").as[List[String]] shouldBe Right(
      List("bi")
    )

  "OpaInput.statement" should "sort accesses, lowercase verbs and omit the text by default" in:
    val acc = Set(
      TableAccess(TableRef("tpch", "main", "orders"), Verb.Read),
      TableAccess(TableRef("tpch", "main", "lineitem"), Verb.Write)
    )
    OpaInput.statement(target, user, "mcp", "WRITE", acc, None) shouldBe parse(
      """{"input":{"kind":"statement","tenant":"acme","database":"tpch","pool":"bi",
        |"parentPool":null,
        |"user":{"name":"alice","roles":["analyst"],"groups":["emea"],"claims":{"dept":"fin"}},
        |"client":{"edge":"mcp"},
        |"statement":{"class":"WRITE"},
        |"accesses":[
        | {"catalog":"tpch","schema":"main","table":"lineitem","verb":"write"},
        | {"catalog":"tpch","schema":"main","table":"orders","verb":"read"}]}}""".stripMargin
    ).toOption.get

  it should "carry the statement text only when given" in:
    val doc = OpaInput.statement(target, user, "flightsql", "READ", Set.empty, Some("SELECT 1"))
    doc.hcursor.downField("input").downField("statement").downField("text").as[String] shouldBe
      Right("SELECT 1")

  "OpaInput.decide" should "carry kinds, columns and via per access, the locations, and edge node" in:
    val acc = List(
      OpaAccess("lake", "main", "orders", "table", Verb.Read, List("v", "id"), None),
      OpaAccess("lake", "main", "base", "table", Verb.Read, List("x"), Some("lake.main.v_secure")),
      OpaAccess("lake", "main", "sales", "table", Verb.Write, Nil, None)
    )
    val locs = List(
      OpaLocation("write", "s3://acme-etl/out/x.parquet", "s3://acme-etl/", "copy_to"),
      OpaLocation("read", "s3://acme-etl/in/x.csv", "s3://acme-etl/in/", "read_csv")
    )
    OpaInput.decide(target, user, "WRITE", acc, locs) shouldBe parse(
      """{"input":{"kind":"statement","tenant":"acme","database":"tpch","pool":"bi",
        |"parentPool":null,
        |"user":{"name":"alice","roles":["analyst"],"groups":["emea"],"claims":{"dept":"fin"}},
        |"client":{"edge":"node"},
        |"statement":{"class":"WRITE"},
        |"accesses":[
        | {"catalog":"lake","schema":"main","table":"base","verb":"read","kind":"table","columns":["x"],"via":"lake.main.v_secure"},
        | {"catalog":"lake","schema":"main","table":"orders","verb":"read","kind":"table","columns":["id","v"],"via":null},
        | {"catalog":"lake","schema":"main","table":"sales","verb":"write","kind":"table","columns":[],"via":null}],
        |"locations":[
        | {"access":"read","location":"s3://acme-etl/in/x.csv","scope":"s3://acme-etl/in/","via":"read_csv"},
        | {"access":"write","location":"s3://acme-etl/out/x.parquet","scope":"s3://acme-etl/","via":"copy_to"}]}}""".stripMargin
    ).toOption.get
