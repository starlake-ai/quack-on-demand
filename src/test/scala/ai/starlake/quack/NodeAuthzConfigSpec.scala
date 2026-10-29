package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins the nodeAuthz block: its defaults, the camelCase reader Main uses (without it the derived
  * reader looks for kebab-case keys, so QOD_NODE_KEY_SECRET would silently give way to the control
  * plane's secret), the secret's masking, and the URL nodes reach this replica on.
  */
class NodeAuthzConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  /** The bundled application.conf, with `env` standing in for the process environment (a root key
    * satisfies `${?VAR}` as an exported variable would) and the real environment left out, so a
    * developer's own exports cannot change the outcome.
    */
  private def load(env: Map[String, String] = Map.empty): ManagerConfig =
    val overlay = ConfigFactory.parseString(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))
    val conf    = overlay
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-on-demand").loadOrThrow[ManagerConfig]

  "NodeAuthzConfig" should "default to no secret and no internal URL" in {
    NodeAuthzConfig() shouldBe NodeAuthzConfig(keySecret = "", internalUrl = "")
    load().nodeAuthz shouldBe NodeAuthzConfig()
  }

  it should "take QOD_NODE_KEY_SECRET and QOD_INTERNAL_URL" in {
    val secret = "s" * 40
    load(
      Map("QOD_NODE_KEY_SECRET" -> secret, "QOD_INTERNAL_URL" -> "http://10.0.0.5:20900")
    ).nodeAuthz shouldBe NodeAuthzConfig(secret, "http://10.0.0.5:20900")
  }

  it should "read the camelCase keys of an overlay" in {
    val key = "k" * 32
    val cfg = ConfigSource
      .string(s"""quack-on-demand.nodeAuthz { keySecret = "$key", internalUrl = "http://m:1" }""")
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.nodeAuthz shouldBe NodeAuthzConfig(key, "http://m:1")
  }

  it should "flag keySecret as sensitive so ConfigHandlers masks it" in {
    val entries = ConfigRegistry.collect(List("quack-on-demand" -> classOf[ManagerConfig]))
    val secret  = entries.find(_.path == "quack-on-demand.nodeAuthz.keySecret")
    secret.map(e => (e.envVar, e.sensitive)) shouldBe Some(("QOD_NODE_KEY_SECRET", true))
    entries
      .find(_.path == "quack-on-demand.nodeAuthz.internalUrl")
      .map(e => (e.envVar, e.sensitive)) shouldBe Some(("QOD_INTERNAL_URL", false))
  }

  "replicaUrl" should "be the internal URL, trimmed" in {
    NodeAuthzConfig(internalUrl = " http://10.0.0.5:20900 ").replicaUrl(1) shouldBe
      "http://10.0.0.5:20900"
  }

  it should "fall back to this host's loopback URL on the manager port when blank" in {
    NodeAuthzConfig().replicaUrl(20900) shouldBe "http://127.0.0.1:20900"
    NodeAuthzConfig(internalUrl = "   ").replicaUrl(20901) shouldBe "http://127.0.0.1:20901"
  }

  "NodeAuthzConfig" should "take QOD_POD_IP" in {
    load(Map("QOD_POD_IP" -> "10.1.2.3")).nodeAuthz shouldBe NodeAuthzConfig(podIp = "10.1.2.3")
    ConfigRegistry
      .collect(List("quack-on-demand" -> classOf[ManagerConfig]))
      .find(_.path == "quack-on-demand.nodeAuthz.podIp")
      .map(e => (e.envVar, e.sensitive)) shouldBe Some(("QOD_POD_IP", false))
  }

  "replicaUrl" should "be this pod's address on the manager port when only the pod IP is set" in {
    NodeAuthzConfig(podIp = "10.1.2.3").replicaUrl(20900) shouldBe "http://10.1.2.3:20900"
    NodeAuthzConfig(podIp = " fd00::7 ").replicaUrl(20900) shouldBe "http://[fd00::7]:20900"
    NodeAuthzConfig(podIp = "[fd00::7]").replicaUrl(20900) shouldBe "http://[fd00::7]:20900"
  }

  it should "prefer the internal URL to the pod IP" in {
    NodeAuthzConfig(internalUrl = "http://m:1", podIp = "10.1.2.3").replicaUrl(20900) shouldBe
      "http://m:1"
  }
