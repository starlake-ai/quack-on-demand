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

  // ---- comment/semicolon splice safety (jsqlparser accepts and silently truncates at these
  // rather than failing the parse, so they must be rejected explicitly before parsing) ----

  it should "reject a trailing line comment" in {
    invalid("region = 'eu' -- x") should include("comments")
  }

  it should "reject an embedded block comment" in {
    invalid("region /* x */ = 'eu'") should include("comments")
  }

  it should "reject the reviewer's exact repro: an identity-token predicate with a trailing comment" in {
    invalid("tenant = ${tenantId} -- scope to caller") should include("comments")
  }

  it should "accept a '--' that appears inside a string literal" in {
    valid("note = '--'") shouldBe "note = '--'"
  }

  it should "accept a '/*' that appears inside a string literal" in {
    valid("note = '/* not a comment */'") shouldBe "note = '/* not a comment */'"
  }

  it should "reject a bare semicolon" in {
    invalid("region = 'eu'; DROP TABLE secrets") should include("statement separator")
  }

  it should "accept a semicolon that appears inside a string literal" in {
    valid("note = 'a;b'") shouldBe "note = 'a;b'"
  }
