package ai.starlake.quack.model

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class SqlLiteralsSpec extends AnyFunSpec with Matchers:

  describe("duckdbLiteral"):
    it("wraps a plain value in single quotes") {
      SqlLiterals.duckdbLiteral("acme_db") shouldBe "'acme_db'"
    }
    it("doubles embedded single quotes so client-controlled input cannot escape the literal") {
      SqlLiterals.duckdbLiteral("o'brien") shouldBe "'o''brien'"
    }
    it("neutralizes an injection attempt") {
      SqlLiterals.duckdbLiteral("x'); DROP TABLE t; --") shouldBe "'x''); DROP TABLE t; --'"
    }
    it("handles the empty string") {
      SqlLiterals.duckdbLiteral("") shouldBe "''"
    }
