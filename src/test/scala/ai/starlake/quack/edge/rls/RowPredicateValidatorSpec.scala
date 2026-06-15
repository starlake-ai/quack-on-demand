package ai.starlake.quack.edge.rls

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RowPredicateValidatorSpec extends AnyFlatSpec with Matchers:
  import RowPredicateValidator._

  private def valid(p: String): String = validate(p) match
    case Valid(c)     => c
    case Invalid(why) => fail(s"expected Valid, got Invalid($why)")

  private def invalid(p: String): String = validate(p) match
    case Invalid(why) => why
    case Valid(c)     => fail(s"expected Invalid, got Valid($c)")

  "validate" should "accept a simple boolean predicate" in {
    valid("region = 'eu'") shouldBe "region = 'eu'"
  }

  it should "accept identity tokens (single and list forms)" in {
    valid("owner = ${user}") shouldBe "owner = ${user}"
    valid("dept IN (${groups})") shouldBe "dept IN (${groups})"
  }

  it should "keep the tokens intact in the canonical form" in {
    valid("tenant_id = ${tenantId} AND owner = ${user}") should include("${tenantId}")
  }

  it should "reject an empty predicate" in {
    invalid("   ") should include("empty")
  }

  it should "reject a subquery" in {
    invalid("id IN (SELECT id FROM secrets)") should include("subqueries")
  }

  it should "reject a denylisted escape function" in {
    invalid("read_csv('/etc/passwd') IS NOT NULL") should include("not allowed")
  }

  it should "reject an unparseable predicate" in {
    invalid("region = = 'eu'") should include("does not parse")
  }

  it should "reject an over-long predicate" in {
    invalid("x = " + "'" + ("a" * 1100) + "'") should include("exceeds")
  }
