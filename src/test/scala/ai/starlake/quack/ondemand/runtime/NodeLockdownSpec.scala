package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NodeLockdownSpec extends AnyFlatSpec with Matchers:

  "sql" should "be empty when disabled" in {
    NodeLockdown.sql("s3://bucket/data", enabled = false) shouldBe ""
  }

  it should "disable extension acquisition without freezing configuration or autoload" in {
    val s = NodeLockdown.sql("/var/data", enabled = true)
    s should include("SET autoinstall_known_extensions = false;")
    s should include("SET allow_community_extensions = false;")
    s should include("SET allow_unsigned_extensions = false;")
    s should not include "autoload_known_extensions"
    s should not include "lock_configuration"
  }

  it should "disable the local filesystem only for object-store dataPaths" in {
    NodeLockdown.sql("s3://b/d", enabled = true) should include(
      "SET disabled_filesystems = 'LocalFileSystem';"
    )
    NodeLockdown.sql("gs://b/d", enabled = true) should include("disabled_filesystems")
    NodeLockdown.sql("az://b/d", enabled = true) should include("disabled_filesystems")
    NodeLockdown.sql("./ducklake/x", enabled = true) should not include "disabled_filesystems"
  }
