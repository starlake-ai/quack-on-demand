package ai.starlake.quack.edge.adapter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SchemaInitFlagsSpec extends AnyFlatSpec with Matchers:

  "SchemaInitFlags" should "remember a node whose schema init ran" in:
    val f = new SchemaInitFlags
    f.done("n1") shouldBe false
    f.markDone("n1")
    f.done("n1") shouldBe true

  it should "forget every node not listed, keeping the listed ones" in:
    val f = new SchemaInitFlags
    f.markDone("gone")
    f.markDone("live")
    f.retain(Set("live"))
    f.done("gone") shouldBe false
    f.done("live") shouldBe true
    f.size shouldBe 1
