package ai.starlake.quack.ondemand.fleet

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NodeResourceSqlSpec extends AnyFlatSpec with Matchers:
  "render" should "emit nothing when neither quantity is set" in {
    NodeResourceSql.render(None, None) shouldBe ""
  }
  it should "round cpu up to whole threads with a floor of 1" in {
    NodeResourceSql.render(Some("500m"), None) shouldBe "SET threads = 1;\n"
    NodeResourceSql.render(Some("1.5"), None) shouldBe "SET threads = 2;\n"
    NodeResourceSql.render(Some("4"), None) shouldBe "SET threads = 4;\n"
  }
  it should "convert memory to whole MiB with a floor of 64" in {
    NodeResourceSql.render(None, Some("4Gi")) shouldBe "SET memory_limit = '4096MiB';\n"
    NodeResourceSql.render(None, Some("2G")) shouldBe "SET memory_limit = '1907MiB';\n"
    NodeResourceSql.render(None, Some("1Mi")) shouldBe "SET memory_limit = '64MiB';\n"
  }
  it should "emit both, threads first" in {
    NodeResourceSql.render(
      Some("2"),
      Some("512Mi")
    ) shouldBe "SET threads = 2;\nSET memory_limit = '512MiB';\n"
  }
  it should "skip an absurd quantity instead of wrapping it around" in {
    NodeResourceSql.render(Some("1e10"), None) shouldBe ""
    NodeResourceSql.render(None, Some("1e30")) shouldBe ""
    NodeResourceSql.render(Some("1e10"), Some("4Gi")) shouldBe "SET memory_limit = '4096MiB';\n"
  }
  it should "ignore an unparsable quantity rather than break the node" in {
    NodeResourceSql.render(Some("lots"), Some("4Gi")) shouldBe "SET memory_limit = '4096MiB';\n"
  }
