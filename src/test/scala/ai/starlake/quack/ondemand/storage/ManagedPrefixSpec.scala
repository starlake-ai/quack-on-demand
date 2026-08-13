package ai.starlake.quack.ondemand.storage

import ai.starlake.quack.ManagedObjectStoreConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.s3.model.S3Error

class ManagedPrefixSpec extends AnyFlatSpec with Matchers:
  "id8" should "strip the td- prefix before taking 8 chars" in:
    ManagedPrefix.id8("td-0123456789abcdef0123456789abcdef") shouldBe "01234567"

  "dataPath" should "build the spec's prefix shape" in:
    ManagedPrefix.dataPath("qod-managed", "acme", "sales", "td-aabbccddeeff00112233") shouldBe
      "s3://qod-managed/acme_sales-aabbccdd/"

  "keyPrefix" should "match dataPath minus scheme and bucket" in:
    ManagedPrefix.keyPrefix("acme", "sales", "td-aabbccddeeff00112233") shouldBe
      "acme_sales-aabbccdd/"

  "objectStoreFor" should "fill the BYO key vocabulary and omit empty endpoint" in:
    val cfg = ManagedObjectStoreConfig(
      enabled = true,
      region = "eu-west-1",
      accessKeyId = "AK",
      secretAccessKey = "SK"
    )
    val m = ManagedPrefix.objectStoreFor(cfg)
    m("s3_region") shouldBe "eu-west-1"
    m("s3_url_style") shouldBe "path"
    m("s3_access_key_id") shouldBe "AK"
    m("s3_secret_access_key") shouldBe "SK"
    m.contains("s3_endpoint") shouldBe false
    ManagedPrefix
      .objectStoreFor(cfg.copy(endpoint = "http://minio:9000"))("s3_endpoint") shouldBe
      "http://minio:9000"

  // S3 answers DeleteObjects with 200 even when individual keys failed; swallowing
  // that turns the purge worker into a silent non-progress loop (the listing never
  // empties and no Left ever reaches the sweep's warn path).
  "deleteOutcome" should "be Right when the response carries no per-key errors" in:
    S3ManagedStoreClient.deleteOutcome(Nil) shouldBe Right(())

  it should "be Left naming the first failing key and its code" in:
    val errs = List(
      S3Error.builder().key("acme_sales-aabbccdd/data/f1.parquet").code("AccessDenied").build(),
      S3Error.builder().key("acme_sales-aabbccdd/data/f2.parquet").code("InternalError").build()
    )
    S3ManagedStoreClient.deleteOutcome(errs) shouldBe
      Left(
        "deleteBatch: 2 key(s) failed, first: " +
          "acme_sales-aabbccdd/data/f1.parquet (AccessDenied)"
      )
