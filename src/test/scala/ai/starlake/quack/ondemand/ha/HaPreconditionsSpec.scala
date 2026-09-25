package ai.starlake.quack.ondemand.ha

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HaPreconditionsSpec extends AnyFlatSpec with Matchers:

  "HaPreconditions" should "accept ha disabled with any runtime and even an empty secret" in {
    HaPreconditions.validate(haEnabled = false, "local", "") shouldBe Right(())
  }

  it should "accept ha enabled with kubernetes runtime and an explicit secret" in {
    HaPreconditions.validate(haEnabled = true, "kubernetes", "s3cret") shouldBe Right(())
    HaPreconditions.validate(haEnabled = true, "k8s", "s3cret") shouldBe Right(())
  }

  it should "refuse ha enabled with the local runtime" in {
    HaPreconditions.validate(haEnabled = true, "local", "s3cret").isLeft shouldBe true
  }

  it should "refuse ha enabled with an empty session JWT secret" in {
    HaPreconditions.validate(haEnabled = true, "kubernetes", "").isLeft shouldBe true
    HaPreconditions.validate(haEnabled = true, "kubernetes", "   ").isLeft shouldBe true
  }

  it should "refuse HA combined with the embedded control plane" in {
    val result = HaPreconditions.validate(
      haEnabled = true,
      runtimeType = "kubernetes",
      sessionJwtSecret = "a-very-long-and-stable-secret-value",
      embeddedPostgres = true
    )
    result.isLeft shouldBe true
    result.left.toOption.get should include("QOD_PG_EMBEDDED")
  }

  it should "accept HA with an external Postgres" in {
    HaPreconditions.validate(
      haEnabled = true,
      runtimeType = "kubernetes",
      sessionJwtSecret = "a-very-long-and-stable-secret-value",
      embeddedPostgres = false
    ) shouldBe Right(())
  }

  it should "accept ha enabled with the fleet runtime" in {
    HaPreconditions.validate(haEnabled = true, "fleet", "s3cret") shouldBe Right(())
  }
