package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.Names
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

class PoolLabelValueSpec extends AnyFlatSpec with Matchers:

  /** The rule a real API server names in its 422, and the length it allows. */
  private val LabelRule = "(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?".r
  private val MaxLabel  = 63

  /** Slugs as `Names` admits them, weighted toward the ends a label refuses. */
  private def slugs(seed: Long, count: Int): List[String] =
    val random                    = new Random(seed)
    val first                     = "abzAZ___"
    val rest                      = "abz019AZ___"
    def pick(chars: String): Char = chars(random.nextInt(chars.length))
    List.fill(count) {
      val length = 1 + random.nextInt(if random.nextBoolean() then 8 else Names.MaxLength)
      (pick(first) +: List.fill(length - 1)(pick(rest))).mkString
    }

  "PoolLabelValue" should "wrap the reserved pools, and any name with an end the rule refuses" in {
    PoolLabelValue.encode("__merge") shouldBe "qod.__merge.qod"
    PoolLabelValue.encode("__maint") shouldBe "qod.__maint.qod"
    PoolLabelValue.encode("__br_abcd1234") shouldBe "qod.__br_abcd1234.qod"
    PoolLabelValue.encode("_x") shouldBe "qod._x.qod"
    PoolLabelValue.encode("x_") shouldBe "qod.x_.qod"
    PoolLabelValue.encode("_") shouldBe "qod._.qod"
  }

  it should "leave a name the rule accepts as it is" in
    List("etl", "acme_tpch", "acme_tpch__br_abcd1234", "a", "A1", "a_b").foreach { name =>
      PoolLabelValue.encode(name) shouldBe name
      PoolLabelValue.decode(name) shouldBe name
    }

  it should "read back exactly the wrapped shape" in {
    PoolLabelValue.decode("qod.__merge.qod") shouldBe "__merge"
    PoolLabelValue.decode("qod.x_.qod") shouldBe "x_"
    // None of these is the shape.
    List(
      "__merge",
      "qod",
      "qod.",
      "qod.qod",
      "qod..qod",
      ".qod",
      "qod.x",
      "x.qod",
      "aqod.x.qod",
      "qod.x.qodb"
    )
      .foreach(label => PoolLabelValue.decode(label) shouldBe label)
  }

  it should "round-trip every slug, as a value the rule accepts when it is short enough" in {
    val all = slugs(seed = 20261104L, count = 20000)
    all.count(!LabelRule.matches(_)) should be > 1000
    all.foreach { slug =>
      withClue(slug) {
        Names.isValid(slug) shouldBe true
        val encoded = PoolLabelValue.encode(slug)
        PoolLabelValue.decode(encoded) shouldBe slug
        if LabelRule.matches(slug) then encoded shouldBe slug
        if encoded.length <= MaxLabel then LabelRule.matches(encoded) shouldBe true
      }
    }
  }
