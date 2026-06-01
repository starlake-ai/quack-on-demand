package ai.starlake.quack.edge.adapter

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class QuackNativeBridgeSpec extends AnyFunSpec with Matchers:
  describe("QuackNativeBridge"):
    it("loads libquackwire and returns 42") {
      QuackNativeBridge.smokeAnswer() shouldBe 42
    }
