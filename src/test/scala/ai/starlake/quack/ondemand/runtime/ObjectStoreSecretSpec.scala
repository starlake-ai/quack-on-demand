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

  it should "emit a scoped s3 secret with httpfs" in {
    val s = ObjectStoreSecret.sql(s3Map, "s3://bucket-a/db", "ducklake")
    s should include("INSTALL httpfs;\nLOAD httpfs;")
    s should include("CREATE OR REPLACE SECRET qod_db_store")
    s should include("TYPE s3")
    s should include("KEY_ID 'AKIA'")
    s should include("SECRET 'sk'")
    s should include("REGION 'us-east-1'")
    s should include("URL_STYLE 'path'")
    s should include("SCOPE 's3://bucket-a/db/'")
    s should not include "ENDPOINT" // no s3_endpoint present -> omitted
  }

  it should "include ENDPOINT only when present, stripped of scheme" in {
    val s = ObjectStoreSecret.sql(
      s3Map + ("s3_endpoint" -> "https://minio.local:9000/"),
      "s3://b/d",
      "ducklake"
    )
    s should include("ENDPOINT 'minio.local:9000'")
  }

  it should "treat r2 as an s3 secret" in {
    val s = ObjectStoreSecret.sql(
      Map(
        "s3_access_key_id"     -> "k",
        "s3_secret_access_key" -> "s",
        "s3_endpoint"          -> "https://acct.r2.cloudflarestorage.com",
        "s3_region"            -> "auto"
      ),
      "r2://bucket/db",
      "ducklake"
    )
    s should include("TYPE s3")
    s should include("SCOPE 'r2://bucket/db/'")
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
    s should include("SCOPE 'gs://bucket/db/'")
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
    s should include("SCOPE 'az://container/db/'")
  }

  it should "escape single quotes in values" in {
    val s = ObjectStoreSecret.sql(s3Map + ("s3_secret_access_key" -> "a'b"), "s3://b/d", "ducklake")
    s should include("SECRET 'a''b'")
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

  it should "emit USE_SSL true when no s3_endpoint is present at all" in {
    val s = ObjectStoreSecret.sql(s3Map, "s3://bucket-a/db", "ducklake")
    s should include("USE_SSL true")
  }

  "dataScope" should "give a directory data path one trailing slash, a bucket root included" in {
    ObjectStoreSecret.dataScope("s3://lake/acme", "ducklake") shouldBe "s3://lake/acme/"
    ObjectStoreSecret.dataScope("s3://lake/acme/", "ducklake") shouldBe "s3://lake/acme/"
    ObjectStoreSecret.dataScope("s3://lake", "ducklake") shouldBe "s3://lake/"
    ObjectStoreSecret.dataScope("s3://lake/", "ducklake") shouldBe "s3://lake/"
    ObjectStoreSecret.dataScope("s3://lake/views/", "memory") shouldBe "s3://lake/views/"
    ObjectStoreSecret.dataScope("s3://managed/acme_lake-0123abcd/", "ducklake") shouldBe
      "s3://managed/acme_lake-0123abcd/"
  }

  it should "scope a duckdb-file path to the file itself" in {
    ObjectStoreSecret.dataScope("s3://lake/acme.duckdb", "duckdb-file") shouldBe
      "s3://lake/acme.duckdb"
    ObjectStoreSecret.sql(s3Map, "s3://lake/acme.duckdb", "duckdb-file") should include(
      "SCOPE 's3://lake/acme.duckdb'"
    )
  }

  it should "scope a branch to its parent's prefix without the separator" in {
    ObjectStoreSecret.dataScope(
      "s3://lake/acme__br_0123abcd/",
      "ducklake"
    ) shouldBe "s3://lake/acme"
    // A branch of a bucket-root database: the one shape left exposed (see dataScope).
    ObjectStoreSecret.dataScope("s3://lake__br_0123abcd/", "ducklake") shouldBe "s3://lake"
  }

  "the secret name" should "sort before every tenant location secret, so it wins a bucket-root tie" in {
    // DuckDB breaks a scope tie on the secret name, first in alphabetical order. A bucket-root
    // data path scopes to s3://<bucket>/, the scope of the tenant's qod_tl_<id8> for that bucket.
    ObjectStoreSecret.SecretName shouldBe "qod_db_store"
    (ObjectStoreSecret.SecretName < "qod_tl_00000000") shouldBe true
    (ObjectStoreSecret.SecretName < "qod_tl_") shouldBe true
  }
