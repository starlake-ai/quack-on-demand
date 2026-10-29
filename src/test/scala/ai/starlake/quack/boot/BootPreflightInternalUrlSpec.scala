package ai.starlake.quack.boot

import ai.starlake.quack.NodeAuthzConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The decide URL a boot accepts: nodes call it for every statement's verdict (Task 7), so it must
  * be plain http, and reachable from the runtime's nodes.
  */
class BootPreflightInternalUrlSpec extends AnyFlatSpec with Matchers:

  private def check(runtime: String, internalUrl: String = "", podIp: String = "") =
    BootPreflight.checkInternalUrl(
      runtime,
      NodeAuthzConfig(internalUrl = internalUrl, podIp = podIp),
      20900
    )

  "the local runtime" should "keep the loopback default and accept any http URL" in {
    check("local") shouldBe Right("http://127.0.0.1:20900")
    check("local", "http://10.0.0.5:20900") shouldBe Right("http://10.0.0.5:20900")
    check("local", "http://manager.internal:20900/qod") shouldBe
      Right("http://manager.internal:20900/qod")
    check("local", "http://manager.internal") shouldBe Right("http://manager.internal")
  }

  "every runtime" should "refuse a URL that is not plain http with a host" in {
    for
      runtime <- List("local", "kubernetes", "fleet")
      url     <- List(
        "https://10.0.0.5:20900",
        "HTTP://10.0.0.5:20900",
        "ftp://10.0.0.5/",
        "http://",
        "http:///x",
        "not a url",
        "http://10.0.0.5:20900/?a=b",
        "http://10.0.0.5:20900/#f",
        "http://user:pw@10.0.0.5:20900",
        // Ports qod-node's URL parser refuses.
        "http://10.0.0.5:",
        "http://10.0.0.5:0",
        "http://10.0.0.5:65536"
      )
    do
      withClue(s"$runtime $url")(
        check(runtime, url) shouldBe Left(BootPreflight.InternalUrlFormatMessage)
      )
  }

  it should "never echo the refused URL" in {
    check("local", "http://user:s3cret@10.0.0.5:20900").left.map(_.contains("s3cret")) shouldBe
      Left(false)
  }

  "the kubernetes runtime" should "refuse the loopback default and every loopback URL" in {
    check("kubernetes") shouldBe
      Left(BootPreflight.internalUrlKubernetesMessage("http://127.0.0.1:20900"))
    List(
      "http://127.0.0.1:20900",
      "http://127.9.9.9:1",
      "http://127.000.000.001:20900",
      "http://localhost:20900",
      "http://LOCALHOST:20900",
      "http://localhost.:20900",
      "http://qod.localhost:20900",
      "http://[::1]:20900",
      "http://[0:0:0:0:0:0:0:1]:20900",
      "http://[::ffff:127.0.0.1]:20900",
      "http://0.0.0.0:20900",
      "http://[::]:20900"
    ).foreach(url =>
      withClue(url)(
        check("kubernetes", url) shouldBe Left(BootPreflight.internalUrlKubernetesMessage(url))
      )
    )
  }

  it should "accept the pod IP and an explicit pod address" in {
    check("kubernetes", podIp = "10.1.2.3") shouldBe Right("http://10.1.2.3:20900")
    check("kubernetes", podIp = "fd00::7") shouldBe Right("http://[fd00::7]:20900")
    check("kubernetes", "http://qod-0.qod-headless:20900") shouldBe
      Right("http://qod-0.qod-headless:20900")
    check("kubernetes", "http://10.0.0.127:20900") shouldBe Right("http://10.0.0.127:20900")
    check("KUBERNETES", podIp = "10.1.2.3") shouldBe Right("http://10.1.2.3:20900")
  }

  "the fleet runtime" should "require an explicit internal URL, loopback allowed" in {
    check("fleet") shouldBe Left(BootPreflight.InternalUrlFleetMessage)
    check("fleet", podIp = "10.1.2.3") shouldBe Left(BootPreflight.InternalUrlFleetMessage)
    check("fleet", "   ") shouldBe Left(BootPreflight.InternalUrlFleetMessage)
    check("fleet", "http://manager.example:20900") shouldBe Right("http://manager.example:20900")
    check("fleet", "http://127.0.0.1:20900") shouldBe Right("http://127.0.0.1:20900")
    check("FLEET", "http://manager.example:20900") shouldBe Right("http://manager.example:20900")
  }

  "the refusals" should "name the settings to fix" in {
    BootPreflight.InternalUrlFormatMessage shouldBe
      "QOD_INTERNAL_URL (quack-on-demand.nodeAuthz.internalUrl) is not a plain http:// URL with " +
      "a host and no user info, query or fragment: nodes call this replica's decision endpoint " +
      "on it, and qod-node speaks plain HTTP only"
    BootPreflight.internalUrlKubernetesMessage("http://127.0.0.1:20900") shouldBe
      "the node decide URL is http://127.0.0.1:20900, a loopback address no node pod can reach " +
      "on the kubernetes runtime: leave QOD_INTERNAL_URL unset and set QOD_POD_IP from the " +
      "downward API (the Helm chart does), or set QOD_INTERNAL_URL to this replica's own pod " +
      "address"
    BootPreflight.InternalUrlFleetMessage shouldBe
      "fleet mode requires QOD_INTERNAL_URL (quack-on-demand.nodeAuthz.internalUrl): the address " +
      "fleet servers reach this replica on, typically the one they heartbeat to (QOD_MANAGER_URL " +
      "of qod fleet join); every node calls it for its statements' verdicts"
  }
