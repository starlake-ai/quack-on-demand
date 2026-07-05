package ai.starlake.quack.model
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
class QuantitySyntaxSpec extends AnyFlatSpec with Matchers:
  "validQuantity" should "accept k8s quantities and reject junk" in:
    QuantitySyntax.validQuantity("500m") shouldBe true
    QuantitySyntax.validQuantity("2") shouldBe true
    QuantitySyntax.validQuantity("2Gi") shouldBe true
    QuantitySyntax.validQuantity("") shouldBe false
    QuantitySyntax.validQuantity("2 gigs") shouldBe false
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
