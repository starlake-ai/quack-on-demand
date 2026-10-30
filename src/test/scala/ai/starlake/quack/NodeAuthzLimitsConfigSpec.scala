package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins the nodeAuthzLimits block: qod-node's own defaults and ranges, the two variables (qod-node
  * reads the same names), and the spawn environment that always names both.
  */
class NodeAuthzLimitsConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  /** The bundled application.conf under the HOCON `overlay`, the real environment left out. */
  private def loadOverlay(overlay: String): ManagerConfig =
    val conf = ConfigFactory
      .parseString(overlay)
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-on-demand").loadOrThrow[ManagerConfig]

  private def load(env: Map[String, String] = Map.empty): ManagerConfig =
    loadOverlay(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))

  "NodeAuthzLimitsConfig" should "default to qod-node's own limits" in {
    NodeAuthzLimitsConfig() shouldBe NodeAuthzLimitsConfig(2000, 10000)
    load().nodeAuthzLimits shouldBe NodeAuthzLimitsConfig()
  }

  it should "take each limit from the variable qod-node reads" in {
    load(Map("QOD_AUTHZ_DECIDE_TIMEOUT_MS" -> "750")).nodeAuthzLimits shouldBe
      NodeAuthzLimitsConfig(decideTimeoutMs = 750)
    load(Map("QOD_AUTHZ_CACHE_ENTRIES" -> "0")).nodeAuthzLimits shouldBe
      NodeAuthzLimitsConfig(cacheEntries = 0)
    loadOverlay(
      "quack-on-demand.nodeAuthzLimits { decideTimeoutMs = 60000 }"
    ).nodeAuthzLimits shouldBe
      NodeAuthzLimitsConfig(decideTimeoutMs = 60000)
  }

  it should "refuse a value qod-node would refuse to start on" in {
    List(
      Map("QOD_AUTHZ_DECIDE_TIMEOUT_MS" -> "0"),
      Map("QOD_AUTHZ_DECIDE_TIMEOUT_MS" -> "60001"),
      Map("QOD_AUTHZ_CACHE_ENTRIES"     -> "-1"),
      Map("QOD_AUTHZ_CACHE_ENTRIES"     -> "10000001"),
      Map("QOD_AUTHZ_CACHE_ENTRIES"     -> "lots")
    ).foreach(env => withClue(env)(an[Exception] should be thrownBy load(env)))
    load(Map("QOD_AUTHZ_CACHE_ENTRIES" -> "10000000")).nodeAuthzLimits.cacheEntries shouldBe
      10000000
    load(Map("QOD_AUTHZ_DECIDE_TIMEOUT_MS" -> "1")).nodeAuthzLimits.decideTimeoutMs shouldBe 1
  }

  it should "register each limit under its variable, neither sensitive" in {
    val entries = ConfigRegistry.collect(List("quack-on-demand" -> classOf[ManagerConfig]))
    List("decideTimeoutMs", "cacheEntries").map { k =>
      entries
        .find(_.path == s"quack-on-demand.nodeAuthzLimits.$k")
        .map(e => (e.envVar, e.sensitive))
    } shouldBe List(
      Some(("QOD_AUTHZ_DECIDE_TIMEOUT_MS", false)),
      Some(("QOD_AUTHZ_CACHE_ENTRIES", false))
    )
  }

  "env" should "always name both" in {
    NodeAuthzLimitsConfig().env shouldBe Map(
      "QOD_AUTHZ_DECIDE_TIMEOUT_MS" -> "2000",
      "QOD_AUTHZ_CACHE_ENTRIES"     -> "10000"
    )
    NodeAuthzLimitsConfig(750, 0).env shouldBe Map(
      "QOD_AUTHZ_DECIDE_TIMEOUT_MS" -> "750",
      "QOD_AUTHZ_CACHE_ENTRIES"     -> "0"
    )
    NodeAuthzLimitsConfig.EnvNames shouldBe
      Set("QOD_AUTHZ_DECIDE_TIMEOUT_MS", "QOD_AUTHZ_CACHE_ENTRIES")
  }
