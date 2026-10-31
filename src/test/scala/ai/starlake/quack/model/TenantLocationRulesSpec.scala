package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantLocationRulesSpec extends AnyFlatSpec with Matchers:

  private val Root = Some("/srv/qod")

  private def ok(raw: String, root: Option[String] = Root) =
    TenantLocationRules.validate("acme", raw, root)

  private def code(raw: String, root: Option[String] = Root) = ok(raw, root).left.map(_._1)

  "an s3 location" should "be refused: S3 access is per user (decision 23)" in
    List("s3://acme-etl/", "s3://acme-etl", "S3://acme-etl/", "s3://acme-etl/in/").foreach { raw =>
      withClue(s"'$raw': ") {
        code(raw) shouldBe Left("invalid_location")
        ok(raw).left.toOption.get._2 should include("qod profile s3 add")
      }
    }

  it should "refuse every other scheme and a relative path" in
    List(
      "s3a://acme-etl/",
      "gs://acme-etl/",
      "https://acme-etl.s3.amazonaws.com/",
      "acme-etl",
      "/srv/qod/acme/x\ny/"
    ).foreach(raw => withClue(s"'$raw': ")(code(raw) shouldBe Left("invalid_location")))

  "a local folder" should "be stored with a trailing slash when it lies under <root>/<tenant>/" in {
    ok("/srv/qod/acme/") shouldBe Right(TenantLocationRules.Accepted("local", "/srv/qod/acme/"))
    ok("/srv/qod/acme/in") shouldBe Right(
      TenantLocationRules.Accepted("local", "/srv/qod/acme/in/")
    )
    TenantLocationRules.tenantRoot("/srv/qod", "acme") shouldBe "/srv/qod/acme/"
    TenantLocationRules.tenantRoot("/srv/qod/", "acme") shouldBe "/srv/qod/acme/"
  }

  it should "refuse a folder outside the tenant's root, another tenant's included" in {
    List("/srv/qod/acmecorp/x/", "/srv/qod/globex/", "/srv/qod/", "/srv/", "/etc/", "/")
      .foreach { raw =>
        withClue(s"'$raw': ") {
          code(raw) should (be(Left("outside_tenant_files_root")) or be(Left("invalid_location")))
          ok(raw).isLeft shouldBe true
        }
      }
    code("/srv/qod/acmecorp/x/") shouldBe Left("outside_tenant_files_root")
  }

  it should "refuse a relative path and an empty, . or .. segment" in
    List(
      "srv/qod/acme/",
      "./srv/qod/acme/",
      "/srv/qod/acme/../globex/",
      "/srv/qod/acme/./x/",
      "/srv/qod/acme//x/",
      "/srv/qod/acme/x//",
      "//srv/qod/acme/",
      "/srv/qod/acme/.."
    ).foreach(raw => withClue(s"'$raw': ")(code(raw) shouldBe Left("invalid_location")))

  it should "be refused while the operator set no root" in {
    code("/srv/qod/acme/", None) shouldBe Left("tenant_files_root_unset")
  }

  it should "refuse a folder the location checker could never admit: '%', a glob or a backslash" in
    List(
      "/srv/qod/acme/in%20x/",
      "/srv/qod/acme/*/",
      "/srv/qod/acme/in?/",
      "/srv/qod/acme/[a]/",
      "/srv/qod/acme/{a,b}/",
      "/srv/qod/acme/in\\x/"
    ).foreach { raw =>
      withClue(s"'$raw': ") {
        code(raw) shouldBe Left("invalid_location")
        ok(raw).left.toOption.get._2 should include("glob")
      }
    }

  "newId" should "be 32 hex digits whose first 8 differ from every taken id" in {
    TenantLocationRules.newId(Nil) should fullyMatch regex "[0-9a-f]{32}"
    val draws = Iterator("0123abcd" + "0" * 24, "0123abcd" + "1" * 24, "feedbeef" + "2" * 24)
    TenantLocationRules.newId(List("0123abcd" + "f" * 24), () => draws.next()) shouldBe
      "feedbeef" + "2" * 24
  }
