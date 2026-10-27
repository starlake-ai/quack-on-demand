package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantLocationRulesSpec extends AnyFlatSpec with Matchers:

  private val Keys = Map("s3_access_key_id" -> "AKIAEXAMPLE", "s3_secret_access_key" -> "sekr3t")
  private val Root = Some("/srv/qod")

  private def ok(raw: String, os: Map[String, String] = Keys, root: Option[String] = Root) =
    TenantLocationRules.validate("acme", raw, os, root)

  private def code(raw: String, os: Map[String, String] = Keys, root: Option[String] = Root) =
    ok(raw, os, root).left.map(_._1)

  "an s3 location" should "be stored as s3://<bucket>/" in {
    ok("s3://acme-etl/") shouldBe Right(TenantLocationRules.Accepted("s3", "s3://acme-etl/"))
    ok("s3://acme-etl") shouldBe Right(TenantLocationRules.Accepted("s3", "s3://acme-etl/"))
    ok("  s3://acme.etl-1/ ") shouldBe Right(TenantLocationRules.Accepted("s3", "s3://acme.etl-1/"))
  }

  it should "refuse a key path, another spelling, another scheme and a malformed bucket" in
    List(
      "s3://acme-etl/in/",
      "s3://acme-etl/in",
      "s3://acme-etl//",
      "s3://Acme-etl/",
      "S3://acme-etl/",
      "s3a://acme-etl/",
      "r2://acme-etl/",
      "gs://acme-etl/",
      "az://acme-etl/",
      "https://acme-etl.s3.amazonaws.com/",
      "s3://",
      "s3://ab/",
      "s3://a..b/",
      "s3://-acme/",
      "s3://acme-/",
      "s3://acme%2fetl/",
      "s3://acme/../",
      "s3://acme etl/",
      "s3://" + "a" * 64 + "/",
      "acme-etl",
      "s3://acme\n/"
    ).foreach(raw => withClue(s"'$raw': ")(code(raw) shouldBe Left("invalid_location")))

  it should "require the bucket's own credentials" in {
    code("s3://acme-etl/", Map.empty) shouldBe Left("object_store_required")
    code("s3://acme-etl/", Keys - "s3_secret_access_key") shouldBe Left("object_store_required")
    code("s3://acme-etl/", Keys - "s3_access_key_id") shouldBe Left("object_store_required")
    code("s3://acme-etl/", Keys.updated("s3_secret_access_key", "  ")) shouldBe
      Left("object_store_required")
  }

  it should "check every objectStore key and value, and never echo a value" in {
    val bad = List(
      Keys + ("s3_session_token" -> "t"),
      Keys + ("s3_url_style"     -> "virtual"),
      Keys + ("s3_region"        -> "us east"),
      Keys + ("s3_endpoint"      -> "http://minio:9000/bucket"),
      Keys + ("s3_endpoint"      -> "minio:9000;x"),
      Keys.updated("s3_secret_access_key", "line\nbreak"),
      Keys.updated("s3_secret_access_key", "x" * 4097)
    )
    bad.foreach { os =>
      val res = ok("s3://acme-etl/", os)
      res.left.map(_._1) shouldBe Left("invalid_object_store")
      res.left.toOption.get._2 should not include "line"
    }
  }

  it should "admit the nearest valid credentials" in {
    ok(
      "s3://acme-etl/",
      Keys ++ Map(
        "s3_region"    -> "eu-west-3",
        "s3_endpoint"  -> "http://minio:9000",
        "s3_url_style" -> "path"
      )
    ).isRight shouldBe true
    ok("s3://acme-etl/", Keys + ("s3_endpoint" -> "minio.local:9000/")).isRight shouldBe true
    ok("s3://acme-etl/", Keys.updated("s3_secret_access_key", "it's/+=")).isRight shouldBe true
  }

  "a local folder" should "be stored with a trailing slash when it lies under <root>/<tenant>/" in {
    ok("/srv/qod/acme/", Map.empty) shouldBe
      Right(TenantLocationRules.Accepted("local", "/srv/qod/acme/"))
    ok("/srv/qod/acme/in", Map.empty) shouldBe
      Right(TenantLocationRules.Accepted("local", "/srv/qod/acme/in/"))
    TenantLocationRules.tenantRoot("/srv/qod", "acme") shouldBe "/srv/qod/acme/"
    TenantLocationRules.tenantRoot("/srv/qod/", "acme") shouldBe "/srv/qod/acme/"
  }

  it should "refuse a folder outside the tenant's root, another tenant's included" in {
    List("/srv/qod/acmecorp/x/", "/srv/qod/globex/", "/srv/qod/", "/srv/", "/etc/", "/")
      .foreach { raw =>
        withClue(s"'$raw': ") {
          code(raw, Map.empty) should (be(Left("outside_tenant_files_root")) or
            be(Left("invalid_location")))
          ok(raw, Map.empty).isLeft shouldBe true
        }
      }
    code("/srv/qod/acmecorp/x/", Map.empty) shouldBe Left("outside_tenant_files_root")
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
    ).foreach(raw => withClue(s"'$raw': ")(code(raw, Map.empty) shouldBe Left("invalid_location")))

  it should "be refused while the operator set no root, and take no objectStore" in {
    code("/srv/qod/acme/", Map.empty, None) shouldBe Left("tenant_files_root_unset")
    code("/srv/qod/acme/", Keys) shouldBe Left("invalid_object_store")
  }

  "newId" should "be 32 hex digits whose first 8 differ from every taken id" in {
    TenantLocationRules.newId(Nil) should fullyMatch regex "[0-9a-f]{32}"
    val draws = Iterator("0123abcd" + "0" * 24, "0123abcd" + "1" * 24, "feedbeef" + "2" * 24)
    TenantLocationRules.newId(List("0123abcd" + "f" * 24), () => draws.next()) shouldBe
      "feedbeef" + "2" * 24
  }

  "TenantLocation.toString" should "never print the secret" in {
    val l = TenantLocation("0" * 32, "acme", "s3", "s3://acme-etl/", Keys, 1L, None)
    l.toString should not include "sekr3t"
    l.toString should include(FederatedSecret.RedactedMarker)
    TenantLocation.withoutSecrets(Keys) shouldBe Map("s3_access_key_id" -> "AKIAEXAMPLE")
  }
