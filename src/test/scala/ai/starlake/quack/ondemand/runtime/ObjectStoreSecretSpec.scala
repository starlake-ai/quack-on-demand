package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ObjectStoreSecretSpec extends AnyFlatSpec with Matchers:

  private val s3Map = Map(
    "s3_region"            -> "us-east-1",
    "s3_access_key_id"     -> "AKIA",
    "s3_secret_access_key" -> "sk",
    "s3_url_style"         -> "path"
  )

  "sql" should "be empty for an empty map" in {
    ObjectStoreSecret.sql(Map.empty, "s3://b/d", "ducklake") shouldBe ""
  }

  it should "be empty for a local dataPath even with a map" in {
    ObjectStoreSecret.sql(s3Map, "/var/data", "ducklake") shouldBe ""
  }

  it should "emit an s3 secret scoped to the data path and its branches, naming every key" in {
    val s = ObjectStoreSecret.sql(s3Map, "s3://bucket-a/db", "ducklake")
    s should include("INSTALL httpfs;\nLOAD httpfs;")
    s should include("CREATE OR REPLACE SECRET qod_db_store")
    List(
      "TYPE s3",
      "KEY_ID 'AKIA'",
      "SECRET 'sk'",
      "SESSION_TOKEN ''",
      "REGION 'us-east-1'",
      "ENDPOINT ''",
      "URL_STYLE 'path'",
      "USE_SSL true",
      "KMS_KEY_ID ''",
      "URL_COMPATIBILITY_MODE false",
      "REQUESTER_PAYS false",
      "SCOPE ['s3://bucket-a/db/', 's3://bucket-a/db__br_']"
    ).foreach(opt => withClue(opt)(s should include(opt)))
  }

  it should "strip an endpoint of its scheme" in {
    val s = ObjectStoreSecret.sql(
      s3Map + ("s3_endpoint" -> "https://minio.local:9000/"),
      "s3://b/d",
      "ducklake"
    )
    s should include("ENDPOINT 'minio.local:9000'")
  }

  it should "emit an s3 secret for r2 paths" in {
    val s = ObjectStoreSecret.sql(
      Map(
        "s3_access_key_id"     -> "r2k",
        "s3_secret_access_key" -> "r2s",
        "s3_endpoint"          -> "https://acct.r2.cloudflarestorage.com",
        "s3_region"            -> "auto"
      ),
      "r2://bucket/db",
      "ducklake"
    )
    s should include("TYPE s3")
    s should include("SCOPE ['r2://bucket/db/', 'r2://bucket/db__br_']")
  }

  it should "emit a gcs secret for gs paths" in {
    val s = ObjectStoreSecret.sql(
      Map("gcs_hmac_key_id" -> "GOOG", "gcs_hmac_secret" -> "hs"),
      "gs://bucket/db",
      "ducklake"
    )
    s should include("TYPE gcs")
    s should include("KEY_ID 'GOOG'")
    s should include("SECRET 'hs'")
    s should include("SCOPE ['gs://bucket/db/', 'gs://bucket/db__br_']")
  }

  it should "emit an azure secret with a connection string" in {
    val s = ObjectStoreSecret.sql(
      Map("azure_account" -> "acct", "azure_account_key" -> "ak"),
      "az://container/db",
      "ducklake"
    )
    s should include("INSTALL azure;\nLOAD azure;")
    s should include("TYPE azure")
    s should include("AccountName=acct")
    s should include("AccountKey=ak")
    s should include("SCOPE ['az://container/db/', 'az://container/db__br_']")
  }

  it should "escape single quotes in values and scopes" in {
    val s =
      ObjectStoreSecret.sql(s3Map + ("s3_secret_access_key" -> "a'b"), "s3://b/it's", "ducklake")
    s should include("SECRET 'a''b'")
    s should include("SCOPE ['s3://b/it''s/', 's3://b/it''s__br_']")
  }

  it should "emit USE_SSL false for an http:// endpoint (e.g. a local MinIO)" in {
    val s = ObjectStoreSecret.sql(
      s3Map + ("s3_endpoint" -> "http://minio.local:9000"),
      "s3://b/d",
      "ducklake"
    )
    s should include("USE_SSL false")
    s should include("ENDPOINT 'minio.local:9000'")
  }

  it should "emit USE_SSL true for an https:// endpoint" in {
    val s = ObjectStoreSecret.sql(
      s3Map + ("s3_endpoint" -> "https://minio.local:9000"),
      "s3://b/d",
      "ducklake"
    )
    s should include("USE_SSL true")
  }

  "dataScopes" should "give a directory data path itself with one slash, and its branches' prefix" in {
    ObjectStoreSecret.dataScopes("s3://lake/acme", "ducklake") shouldBe
      List("s3://lake/acme/", "s3://lake/acme__br_")
    ObjectStoreSecret.dataScopes("s3://lake/acme/", "ducklake") shouldBe
      List("s3://lake/acme/", "s3://lake/acme__br_")
    ObjectStoreSecret.dataScopes("s3://lake", "ducklake") shouldBe List(
      "s3://lake/",
      "s3://lake__br_"
    )
    ObjectStoreSecret.dataScopes("s3://lake/views/", "memory") shouldBe
      List("s3://lake/views/", "s3://lake/views__br_")
    ObjectStoreSecret.dataScopes("s3://managed/acme_lake-0123abcd/", "ducklake") shouldBe
      List("s3://managed/acme_lake-0123abcd/", "s3://managed/acme_lake-0123abcd__br_")
  }

  it should "scope a duckdb-file path to the file itself" in {
    ObjectStoreSecret.dataScopes("s3://lake/acme.duckdb", "duckdb-file") shouldBe
      List("s3://lake/acme.duckdb")
    ObjectStoreSecret.sql(s3Map, "s3://lake/acme.duckdb", "duckdb-file") should include(
      "SCOPE ['s3://lake/acme.duckdb']"
    )
  }

  it should "scope a branch to its parent's prefix and its own, never to a neighbour" in {
    ObjectStoreSecret.dataScopes("s3://lake/acme__br_0123abcd/", "ducklake") shouldBe
      List("s3://lake/acme/", "s3://lake/acme__br_0123abcd/")
    ObjectStoreSecret.dataScopes("s3://lake/acme__br_0123abcd", "ducklake") shouldBe
      List("s3://lake/acme/", "s3://lake/acme__br_0123abcd/")
    ObjectStoreSecret
      .dataScopes("s3://lake/acme__br_0123abcd/", "ducklake")
      .exists("s3://lake/acme-10/x".startsWith) shouldBe false
  }
