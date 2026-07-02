package ai.starlake.quack.ondemand.ha

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HaPreconditionsSpec extends AnyFlatSpec with Matchers:

  private val dev = "dev-secret"

  "HaPreconditions" should "accept ha disabled with any runtime" in {
    HaPreconditions.validate(haEnabled = false, "local", dev, dev) shouldBe Right(())
  }

  it should "accept ha enabled with kubernetes runtime and a real secret" in {
    HaPreconditions.validate(haEnabled = true, "kubernetes", "s3cret", dev) shouldBe Right(())
    HaPreconditions.validate(haEnabled = true, "k8s", "s3cret", dev) shouldBe Right(())
  }

  it should "refuse ha enabled with the local runtime" in {
    HaPreconditions.validate(haEnabled = true, "local", "s3cret", dev).isLeft shouldBe true
  }

  it should "refuse ha enabled with the dev session JWT secret" in {
    HaPreconditions.validate(haEnabled = true, "kubernetes", dev, dev).isLeft shouldBe true
  }
