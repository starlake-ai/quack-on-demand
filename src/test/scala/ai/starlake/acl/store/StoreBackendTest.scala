package ai.starlake.acl.store

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

class StoreBackendTest extends AnyFunSuite with Matchers:

  test("local path without scheme returns Local") {
    StoreBackend.fromUri("/etc/gizmosql/acl") shouldBe
      StoreBackend.Local(Paths.get("/etc/gizmosql/acl"))
  }

  test("relative local path returns Local") {
    StoreBackend.fromUri("data/acl") shouldBe
      StoreBackend.Local(Paths.get("data/acl"))
  }

  test("s3:// URI returns S3 with bucket and prefix") {
    StoreBackend.fromUri("s3://my-bucket/acl/tenants") shouldBe
      StoreBackend.S3("my-bucket", "acl/tenants")
  }

  test("s3:// URI with bucket only returns S3 with empty prefix") {
    StoreBackend.fromUri("s3://my-bucket") shouldBe
      StoreBackend.S3("my-bucket", "")
  }

  test("s3:// URI with trailing slash strips it from prefix") {
    StoreBackend.fromUri("s3://my-bucket/acl/") shouldBe
      StoreBackend.S3("my-bucket", "acl")
  }

  test("gs:// URI returns Gcs with bucket and prefix") {
    StoreBackend.fromUri("gs://my-gcs-bucket/path/to/acl") shouldBe
      StoreBackend.Gcs("my-gcs-bucket", "path/to/acl")
  }

  test("gs:// URI with bucket only returns Gcs with empty prefix") {
    StoreBackend.fromUri("gs://my-gcs-bucket") shouldBe
      StoreBackend.Gcs("my-gcs-bucket", "")
  }

  test("az:// URI returns Azure with container and prefix") {
    StoreBackend.fromUri("az://my-container/acl-data") shouldBe
      StoreBackend.Azure("my-container", "acl-data")
  }

  test("az:// URI with container only returns Azure with empty prefix") {
    StoreBackend.fromUri("az://my-container") shouldBe
      StoreBackend.Azure("my-container", "")
  }

  test("nested prefix preserves full path") {
    StoreBackend.fromUri("s3://bucket/a/b/c/d") shouldBe
      StoreBackend.S3("bucket", "a/b/c/d")
  }

  test("empty bucket name throws IllegalArgumentException") {
    an[IllegalArgumentException] should be thrownBy StoreBackend.fromUri("s3://")
  }

  test("empty bucket name with trailing slash throws IllegalArgumentException") {
    an[IllegalArgumentException] should be thrownBy StoreBackend.fromUri("gs:///prefix")
  }
