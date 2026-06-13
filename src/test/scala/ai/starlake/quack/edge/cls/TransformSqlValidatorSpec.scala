package ai.starlake.quack.edge.cls

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TransformSqlValidatorSpec extends AnyFlatSpec with Matchers:
  import TransformSqlValidator._

  "validate" should "accept a constant string" in {
    validate("'***'", "c_email") shouldBe a[Valid]
  }

  it should "accept a built-in function over the protected column" in {
    validate("md5(c_email)",                          "c_email") shouldBe a[Valid]
    validate("concat('user_', md5(c_email))",         "c_email") shouldBe a[Valid]
    validate("regexp_replace(c_phone, '\\d', 'X')",   "c_phone") shouldBe a[Valid]
  }

  it should "canonicalise whitespace" in {
    val r = validate("  '***'   ", "c_email")
    r match
      case Valid(canon) => canon shouldBe "'***'"
      case other        => fail(s"$other")
  }

  it should "reject when the expression references a different column" in {
    val r = validate("concat(c_email, c_phone)", "c_email")
    r shouldBe a[Invalid]
    r.asInstanceOf[Invalid].reason should include("c_phone")
  }

  it should "reject subqueries" in {
    validate("(SELECT c_email FROM customer WHERE c_id = 1)", "c_email") shouldBe a[Invalid]
  }

  it should "reject denylisted functions" in {
    validate("attach('s3://bucket/x.parquet')",       "c_email") shouldBe a[Invalid]
    validate("read_parquet('s3://x.parquet')",        "c_email") shouldBe a[Invalid]
    validate("pragma_database_size('main')",          "c_email") shouldBe a[Invalid]
    validate("pg_read_server_files('/etc/passwd')",   "c_email") shouldBe a[Invalid]
  }

  it should "reject when the expression fails to parse" in {
    validate(") not sql (", "c_email") shouldBe a[Invalid]
  }

  it should "reject when the canonicalised form exceeds 1024 chars" in {
    val long = "concat(" + List.fill(200)("c_email").mkString(", '_', ") + ")"
    validate(long, "c_email") shouldBe a[Invalid]
  }
