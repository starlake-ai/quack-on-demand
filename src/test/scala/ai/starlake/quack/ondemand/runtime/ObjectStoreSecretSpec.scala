package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ObjectStoreSecretSpec extends AnyFlatSpec with Matchers:

  private val s3Map = Map(
    "s3_region" -> "us-east-1",
    "s3_access_key_id" -> "AKIA",
    "s3_secret_access_key" -> "sk",
    "s3_url_style" -> "path"
  )

  "sql" should "be empty for an empty map" in {
    ObjectStoreSecret.sql(Map.empty, "s3://b/d") shouldBe ""
  }

  it should "be empty for a local dataPath even with a map" in {
    ObjectStoreSecret.sql(s3Map, "/var/data") shouldBe ""
  }

  it should "emit a scoped s3 secret with httpfs" in {
    val s = ObjectStoreSecret.sql(s3Map, "s3://bucket-a/db")
    s should include("INSTALL httpfs; LOAD httpfs;")
    s should include("CREATE OR REPLACE SECRET qod_db_store")
    s should include("TYPE s3")
    s should include("KEY_ID 'AKIA'")
    s should include("SECRET 'sk'")
    s should include("REGION 'us-east-1'")
    s should include("URL_STYLE 'path'")
    s should include("SCOPE 's3://bucket-a/db'")
    s should not include "ENDPOINT"   // no s3_endpoint present -> omitted
  }

  it should "include ENDPOINT only when present, stripped of scheme" in {
    val s = ObjectStoreSecret.sql(
      s3Map + ("s3_endpoint" -> "https://minio.local:9000/"),
      "s3://b/d"
    )
    s should include("ENDPOINT 'minio.local:9000'")
  }

  it should "treat r2 as an s3 secret" in {
    val s = ObjectStoreSecret.sql(
      Map("s3_access_key_id" -> "k", "s3_secret_access_key" -> "s",
          "s3_endpoint" -> "https://acct.r2.cloudflarestorage.com", "s3_region" -> "auto"),
      "r2://bucket/db"
    )
    s should include("TYPE s3")
    s should include("SCOPE 'r2://bucket/db'")
  }

  it should "emit a gcs secret for gs paths" in {
    val s = ObjectStoreSecret.sql(
      Map("gcs_hmac_key_id" -> "GOOG", "gcs_hmac_secret" -> "hs"), "gs://bucket/db")
    s should include("TYPE gcs")
    s should include("KEY_ID 'GOOG'")
    s should include("SECRET 'hs'")
    s should include("SCOPE 'gs://bucket/db'")
  }

  it should "emit an azure secret with a connection string" in {
    val s = ObjectStoreSecret.sql(
      Map("azure_account" -> "acct", "azure_account_key" -> "ak"), "az://container/db")
    s should include("INSTALL azure; LOAD azure;")
    s should include("TYPE azure")
    s should include("AccountName=acct")
    s should include("AccountKey=ak")
    s should include("SCOPE 'az://container/db'")
  }

  it should "escape single quotes in values" in {
    val s = ObjectStoreSecret.sql(s3Map + ("s3_secret_access_key" -> "a'b"), "s3://b/d")
    s should include("SECRET 'a''b'")
  }

  it should "emit USE_SSL false for an http:// endpoint (e.g. a local MinIO)" in {
    val s = ObjectStoreSecret.sql(
      s3Map + ("s3_endpoint" -> "http://minio.local:9000"),
      "s3://b/d"
    )
    s should include("USE_SSL false")
    s should include("ENDPOINT 'minio.local:9000'")
  }

  it should "emit USE_SSL true for an https:// endpoint" in {
    val s = ObjectStoreSecret.sql(
      s3Map + ("s3_endpoint" -> "https://minio.local:9000"),
      "s3://b/d"
    )
    s should include("USE_SSL true")
  }

  it should "emit USE_SSL true when no s3_endpoint is present at all" in {
    val s = ObjectStoreSecret.sql(s3Map, "s3://bucket-a/db")
    s should include("USE_SSL true")
  }
