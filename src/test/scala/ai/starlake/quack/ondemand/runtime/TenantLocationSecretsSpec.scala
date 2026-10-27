package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.TenantLocation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantLocationSecretsSpec extends AnyFlatSpec with Matchers:

  private def s3(id: String, loc: String, extra: Map[String, String] = Map.empty) =
    TenantLocation(
      id,
      "acme",
      "s3",
      loc,
      Map("s3_access_key_id" -> "AKIA", "s3_secret_access_key" -> "it's") ++ extra,
      1L,
      None
    )

  private val Etl = s3("0123abcd" + "0" * 24, "s3://acme-etl/")

  "createSql" should "be one scoped secret on one line, named after the location id" in {
    TenantLocationSecrets.createSql(Etl) shouldBe
      "CREATE OR REPLACE SECRET qod_tl_0123abcd (TYPE s3, KEY_ID 'AKIA', SECRET 'it''s', " +
      "REGION 'us-east-1', URL_STYLE 'path', USE_SSL true, SCOPE 's3://acme-etl/');"
  }

  it should "carry an endpoint, without its scheme, and turn TLS off for http" in {
    val sql = TenantLocationSecrets.createSql(
      s3("feedbeef" + "0" * 24, "s3://lake/", Map("s3_endpoint" -> "http://minio:9000/"))
    )
    sql should include("ENDPOINT 'minio:9000'")
    sql should include("USE_SSL false")
    sql should not include "\n"
  }

  "initSql" should "load httpfs, then one statement per line per s3 location, local ones skipped" in {
    val local =
      TenantLocation("feedbeef" + "1" * 24, "acme", "local", "/srv/qod/acme/", Map.empty, 1L, None)
    val init = TenantLocationSecrets.initSql(List(local, Etl))
    init.linesIterator.toList shouldBe List(
      "INSTALL httpfs;",
      "LOAD httpfs;",
      TenantLocationSecrets.createSql(Etl)
    )
    init should endWith("\n")
    TenantLocationSecrets.initSql(List(local)) shouldBe ""
    TenantLocationSecrets.initSql(Nil) shouldBe ""
  }

  "the live push" should "create on add and drop on remove, for s3 only" in {
    TenantLocationSecrets.pushAdd(Etl) shouldBe
      List("INSTALL httpfs;", "LOAD httpfs;", TenantLocationSecrets.createSql(Etl))
    TenantLocationSecrets.pushRemove(Etl) shouldBe List("DROP SECRET IF EXISTS qod_tl_0123abcd;")
    val local = Etl.copy(kind = "local", location = "/srv/qod/acme/", objectStore = Map.empty)
    TenantLocationSecrets.pushAdd(local) shouldBe Nil
    TenantLocationSecrets.pushRemove(local) shouldBe Nil
  }
