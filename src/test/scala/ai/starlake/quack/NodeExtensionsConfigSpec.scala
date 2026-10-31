package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins the nodeExtensions block (spec decision 17): every extension setting off by default, the
  * three variables that turn one on, the camelCase reader Main uses, and the spawn environment that
  * always names all three.
  */
class NodeExtensionsConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  /** The bundled application.conf, with `env` standing in for the process environment and the real
    * environment left out, so a developer's own exports cannot change the outcome.
    */
  private def load(env: Map[String, String] = Map.empty): ManagerConfig =
    loadOverlay(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))

  /** The bundled application.conf under the HOCON `overlay`, the real environment left out. */
  private def loadOverlay(overlay: String): ManagerConfig =
    val conf = ConfigFactory
      .parseString(overlay)
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-on-demand").loadOrThrow[ManagerConfig]

  "NodeExtensionsConfig" should "keep every extension setting off by default" in {
    NodeExtensionsConfig() shouldBe NodeExtensionsConfig(false, false, false)
    load().nodeExtensions shouldBe NodeExtensionsConfig()
  }

  it should "turn each one on from its own variable only" in {
    load(Map("QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS" -> "true")).nodeExtensions shouldBe
      NodeExtensionsConfig(allowCommunity = true)
    load(Map("QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS" -> "true")).nodeExtensions shouldBe
      NodeExtensionsConfig(allowUnsigned = true)
    load(Map("QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS" -> "true")).nodeExtensions shouldBe
      NodeExtensionsConfig(autoinstallKnown = true)
    load(Map("QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS" -> "false")).nodeExtensions shouldBe
      NodeExtensionsConfig()
  }

  it should "read the camelCase keys of an overlay" in {
    val cfg =
      loadOverlay(
        "quack-on-demand.nodeExtensions { allowUnsigned = true, autoinstallKnown = true }"
      )
    cfg.nodeExtensions shouldBe NodeExtensionsConfig(allowUnsigned = true, autoinstallKnown = true)
  }

  it should "register each setting under its variable, none of them sensitive" in {
    val entries = ConfigRegistry.collect(List("quack-on-demand" -> classOf[ManagerConfig]))
    List("allowCommunity", "allowUnsigned", "autoinstallKnown").map { k =>
      entries
        .find(_.path == s"quack-on-demand.nodeExtensions.$k")
        .map(e => (e.envVar, e.sensitive))
    } shouldBe List(
      Some(("QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS", false)),
      Some(("QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS", false)),
      Some(("QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS", false))
    )
  }

  "env" should "always name all three, true only for a setting that is on" in {
    NodeExtensionsConfig().env shouldBe Map(
      "QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS"   -> "false",
      "QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS"    -> "false",
      "QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS" -> "false"
    )
    NodeExtensionsConfig(allowUnsigned = true).env shouldBe Map(
      "QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS"   -> "false",
      "QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS"    -> "true",
      "QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS" -> "false"
    )
    NodeExtensionsConfig.EnvNames shouldBe NodeExtensionsConfig().env.keySet
  }
