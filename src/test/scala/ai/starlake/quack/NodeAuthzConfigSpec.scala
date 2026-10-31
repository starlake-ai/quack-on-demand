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
    NodeAuthzConfig(internalUrl = " http://10.0.0.5:20900 ").replicaUrl("0.0.0.0", 1) shouldBe
      "http://10.0.0.5:20900"
  }

  it should "fall back to this host's loopback URL on the manager port when blank" in {
    NodeAuthzConfig().replicaUrl("0.0.0.0", 20900) shouldBe "http://127.0.0.1:20900"
    NodeAuthzConfig(internalUrl = "   ")
      .replicaUrl("0.0.0.0", 20901) shouldBe "http://127.0.0.1:20901"
  }

  "NodeAuthzConfig" should "take QOD_POD_IP" in {
    load(Map("QOD_POD_IP" -> "10.1.2.3")).nodeAuthz shouldBe NodeAuthzConfig(podIp = "10.1.2.3")
    ConfigRegistry
      .collect(List("quack-on-demand" -> classOf[ManagerConfig]))
      .find(_.path == "quack-on-demand.nodeAuthz.podIp")
      .map(e => (e.envVar, e.sensitive)) shouldBe Some(("QOD_POD_IP", false))
  }

  "replicaUrl" should "be this pod's address on the manager port when only the pod IP is set" in {
    NodeAuthzConfig(podIp = "10.1.2.3")
      .replicaUrl("0.0.0.0", 20900) shouldBe "http://10.1.2.3:20900"
    NodeAuthzConfig(podIp = " fd00::7 ")
      .replicaUrl("0.0.0.0", 20900) shouldBe "http://[fd00::7]:20900"
    NodeAuthzConfig(podIp = "[fd00::7]")
      .replicaUrl("0.0.0.0", 20900) shouldBe "http://[fd00::7]:20900"
  }

  it should "prefer the internal URL to the pod IP" in {
    NodeAuthzConfig(internalUrl = "http://m:1", podIp = "10.1.2.3")
      .replicaUrl("0.0.0.0", 20900) shouldBe
      "http://m:1"
  }

  it should "be the bind address when the manager listens on one specific address" in {
    NodeAuthzConfig().replicaUrl("192.168.1.5", 20900) shouldBe "http://192.168.1.5:20900"
    NodeAuthzConfig().replicaUrl(" fd00::5 ", 20900) shouldBe "http://[fd00::5]:20900"
    NodeAuthzConfig().replicaUrl("[fd00::5]", 20900) shouldBe "http://[fd00::5]:20900"
    NodeAuthzConfig().replicaUrl("mgr.internal", 20900) shouldBe "http://mgr.internal:20900"
    // A loopback address other than 127.0.0.1 is not reached by the loopback default either.
    NodeAuthzConfig().replicaUrl("::1", 20900) shouldBe "http://[::1]:20900"
    // The pod IP and the internal URL still win.
    NodeAuthzConfig(podIp = "10.1.2.3").replicaUrl("192.168.1.5", 20900) shouldBe
      "http://10.1.2.3:20900"
    NodeAuthzConfig(internalUrl = "http://m:1").replicaUrl("192.168.1.5", 20900) shouldBe
      "http://m:1"
  }

  it should "keep the loopback default for a wildcard or loopback bind address" in {
    for bind <- List(
        "",
        " ",
        "0.0.0.0",
        "::",
        "[::]",
        "0:0:0:0:0:0:0:0",
        "*",
        "localhost",
        "LOCALHOST",
        "127.0.0.1"
      )
    do
      withClue(s"'$bind'")(
        NodeAuthzConfig().replicaUrl(bind, 20900) shouldBe "http://127.0.0.1:20900"
      )
  }

  // How many handle contexts (MintedHandles) and nonces (IssuedHandles) one replica keeps: each is
  // kept a handle's lifetime plus its grace (360 s) whatever the statement's duration, so the bound
  // caps a replica's sustained tenant statements at about capacity / 360 per second.
  "NodeAuthzConfig.handleContextsMax" should "default to 200000" in {
    NodeAuthzConfig().handleContextsMax shouldBe 200000
    load().nodeAuthz.handleContextsMax shouldBe 200000
  }

  it should "take QOD_AUTHZ_HANDLE_CONTEXTS_MAX" in {
    load(Map("QOD_AUTHZ_HANDLE_CONTEXTS_MAX" -> "1000000")).nodeAuthz.handleContextsMax shouldBe
      1000000
    load(Map("QOD_AUTHZ_HANDLE_CONTEXTS_MAX" -> "1000")).nodeAuthz.handleContextsMax shouldBe 1000
  }

  it should "refuse boot below its floor of 1000, naming the variable" in
    List("999", "0", "-1", "lots").foreach { v =>
      withClue(v) {
        val e = intercept[Exception](load(Map("QOD_AUTHZ_HANDLE_CONTEXTS_MAX" -> v)))
        if v != "lots" then e.getMessage should include("QOD_AUTHZ_HANDLE_CONTEXTS_MAX")
      }
    }

  it should "register under its variable, not sensitive" in {
    ConfigRegistry
      .collect(List("quack-on-demand" -> classOf[ManagerConfig]))
      .find(_.path == "quack-on-demand.nodeAuthz.handleContextsMax")
      .map(e => (e.envVar, e.sensitive)) shouldBe Some(("QOD_AUTHZ_HANDLE_CONTEXTS_MAX", false))
  }
