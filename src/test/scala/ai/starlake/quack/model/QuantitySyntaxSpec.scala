package ai.starlake.quack.model
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
class QuantitySyntaxSpec extends AnyFlatSpec with Matchers:
  "validCpu" should "accept CPU quantities and reject memory-only or junk" in:
    QuantitySyntax.validCpu("500m") shouldBe true
    QuantitySyntax.validCpu("2") shouldBe true
    QuantitySyntax.validCpu("1.5") shouldBe true
    QuantitySyntax.validCpu("") shouldBe false
    QuantitySyntax.validCpu("2 gigs") shouldBe false
    // Memory quantity must not pass the CPU validator
    QuantitySyntax.validCpu("2Gi") shouldBe false
    QuantitySyntax.validCpu("512Mi") shouldBe false

  "validMemory" should "accept memory quantities and reject CPU-only or junk" in:
    QuantitySyntax.validMemory("2Gi") shouldBe true
    QuantitySyntax.validMemory("512Mi") shouldBe true
    QuantitySyntax.validMemory("1024") shouldBe true
    QuantitySyntax.validMemory("") shouldBe false
    QuantitySyntax.validMemory("2 gigs") shouldBe false
    // Millicore CPU notation must not pass the memory validator
    QuantitySyntax.validMemory("500m") shouldBe false

  "validPodTemplate" should "require a parseable Pod with a quack container" in:
    QuantitySyntax.validPodTemplate(
      "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    ) shouldBe Right(())
    QuantitySyntax.validPodTemplate("not: [valid").isLeft shouldBe true
    QuantitySyntax
      .validPodTemplate(
        "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: other\n      image: x"
      )
      .isLeft shouldBe true
