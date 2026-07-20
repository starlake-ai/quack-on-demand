package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NodeLockdownSpec extends AnyFlatSpec with Matchers:

  "sql" should "be empty when disabled" in {
    NodeLockdown.sql("s3://bucket/data", enabled = false) shouldBe ""
  }

  it should "freeze configuration and disable extension acquisition" in {
    val s = NodeLockdown.sql("/var/data", enabled = true)
    s should include("SET autoinstall_known_extensions = false;")
    s should include("SET autoload_known_extensions = false;")
    s should include("SET allow_community_extensions = false;")
    s.trim should endWith("SET lock_configuration = true;")
    s should not include "disabled_filesystems"
  }

  it should "disable the local filesystem only for object-store dataPaths" in {
    NodeLockdown.sql("s3://b/d", enabled = true) should include(
      "SET disabled_filesystems = 'LocalFileSystem';"
    )
    NodeLockdown.sql("gs://b/d", enabled = true) should include("disabled_filesystems")
    NodeLockdown.sql("az://b/d", enabled = true) should include("disabled_filesystems")
    NodeLockdown.sql("./ducklake/x", enabled = true) should not include "disabled_filesystems"
  }

  it should "order the filesystem restriction before the freeze" in {
    val s = NodeLockdown.sql("s3://b/d", enabled = true)
    s.indexOf("disabled_filesystems") should be < s.indexOf("lock_configuration")
  }
