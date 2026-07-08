package ai.starlake.quack

import com.typesafe.config.ConfigFactory
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class StampWritesConfigSpec extends AnyFunSpec with Matchers:

  it("defaults quack-on-demand.stampWrites to true"):
    ConfigFactory.load().getBoolean("quack-on-demand.stampWrites") shouldBe true
