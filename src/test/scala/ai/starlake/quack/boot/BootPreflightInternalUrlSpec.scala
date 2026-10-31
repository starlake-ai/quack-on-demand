package ai.starlake.quack.boot

import ai.starlake.quack.NodeAuthzConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The decide URL a boot accepts: nodes call it for every statement's verdict (Task 7), so it must
  * be a URL qod-node's parser accepts, and reachable from the runtime's nodes.
  */
class BootPreflightInternalUrlSpec extends AnyFlatSpec with Matchers:

  private def check(
      runtime: String,
      internalUrl: String = "",
      podIp: String = "",
      bindHost: String = "0.0.0.0"
  ) =
    BootPreflight.checkInternalUrl(
      runtime,
      NodeAuthzConfig(internalUrl = internalUrl, podIp = podIp),
      bindHost,
      20900
    )

  "the local runtime" should "keep the loopback default and accept any http URL" in {
    check("local") shouldBe Right("http://127.0.0.1:20900")
    check("local", "http://10.0.0.5:20900") shouldBe Right("http://10.0.0.5:20900")
    check("local", "http://manager.internal:20900/qod") shouldBe
      Right("http://manager.internal:20900/qod")
    check("local", "http://manager.internal") shouldBe Right("http://manager.internal")
  }

  it should "default to the bind address when the manager listens on one specific address" in {
    check("local", bindHost = "192.168.1.5") shouldBe Right("http://192.168.1.5:20900")
    check("local", bindHost = "fd00::5") shouldBe Right("http://[fd00::5]:20900")
    check("local", bindHost = "localhost") shouldBe Right("http://127.0.0.1:20900")
  }

  "every runtime" should "accept what qod-node's URL parser accepts" in {
    for
      runtime <- List("local", "kubernetes", "k8s", "fleet")
      url     <- List(
        "http://qod_manager:20900",
        "http://10.0.0.5:08080",
        "http://10.0.0.5:65535/qod/",
        "http://[fd00::7]:20900",
        "http://[fd00::7]"
      )
    do withClue(s"$runtime $url")(check(runtime, url) shouldBe Right(url))
  }

  it should "refuse a URL that is not plain http with a host" in {
    for
      runtime <- List("local", "kubernetes", "k8s", "fleet")
      url     <- List(
        "https://10.0.0.5:20900",
        "HTTP://10.0.0.5:20900",
        "ftp://10.0.0.5/",
        "http://",
        "http:///x",
        "not a url",
        "http://10.0.0.5:20900/?a=b",
        "http://10.0.0.5:20900/#f",
        "http://10.0.0.5:20900?a=b",
        "http://10.0.0.5#f",
        "http://user:pw@10.0.0.5:20900",
        "http://10.0.0.5:20900/a b",
        "http://a b:20900",
        "http://h%41:20900",
        "http://fd00::7:20900",
        "http://[fd00::7:20900",
        "http://[fd00::7]x:20900",
        "http://[not-ipv6]:20900",
        // Ports qod-node's URL parser refuses.
        "http://10.0.0.5:",
        "http://10.0.0.5:0",
        "http://10.0.0.5:65536",
        "http://10.0.0.5:020900",
        "http://10.0.0.5:2o900"
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

  it should "name QOD_POD_IP when the refused URL came from the pod IP" in {
    for runtime <- List("local", "kubernetes", "k8s") do
      withClue(runtime)(
        check(runtime, podIp = "$(MY_POD_IP)") shouldBe
          Left(BootPreflight.InternalUrlPodIpFormatMessage)
      )
    check("kubernetes", "http://m:1", podIp = "$(MY_POD_IP)") shouldBe Right("http://m:1")
  }

  it should "name QOD_ON_DEMAND_HOST when the refused URL came from the bind address" in {
    check("local", bindHost = "my host") shouldBe
      Left(BootPreflight.InternalUrlBindHostFormatMessage)
  }

  "the kubernetes runtime" should "refuse the loopback default and every loopback URL" in {
    for runtime <- List("kubernetes", "KUBERNETES", "k8s", "K8S") do
      withClue(runtime)(
        check(runtime) shouldBe
          Left(BootPreflight.internalUrlKubernetesMessage("http://127.0.0.1:20900"))
      )
    for
      runtime <- List("kubernetes", "k8s")
      url     <- List(
        "http://127.0.0.1:20900",
        "http://127.9.9.9:1",
        "http://127.000.000.001:20900",
        "http://127.0.0.1.:20900",
        "http://localhost:20900",
        "http://LOCALHOST:20900",
        "http://localhost.:20900",
        "http://qod.localhost:20900",
        "http://[::1]:20900",
        "http://[0:0:0:0:0:0:0:1]:20900",
        "http://[::ffff:127.0.0.1]:20900",
        "http://0.0.0.0:20900",
        "http://[::]:20900",
        // inet_aton's shorthand, numeric, octal and hex forms, which qod-node's resolver reads as
        // loopback or unspecified addresses.
        "http://127.1:20900",
        "http://127.0.1:20900",
        "http://2130706433:20900",
        "http://0x7f.1:20900",
        "http://0X7F000001:20900",
        "http://0177.0.0.1:20900",
        "http://017700000001:20900",
        "http://0:20900",
        "http://0.0:20900",
        "http://0x0:20900"
      )
    do
      withClue(s"$runtime $url")(
        check(runtime, url) shouldBe Left(BootPreflight.internalUrlKubernetesMessage(url))
      )
  }

  it should "accept the pod IP and an explicit pod address" in {
    for runtime <- List("kubernetes", "KUBERNETES", "k8s", "K8S") do
      withClue(runtime) {
        check(runtime, podIp = "10.1.2.3") shouldBe Right("http://10.1.2.3:20900")
        check(runtime, podIp = "fd00::7") shouldBe Right("http://[fd00::7]:20900")
        check(runtime, "http://qod-0.qod-headless:20900") shouldBe
          Right("http://qod-0.qod-headless:20900")
        check(runtime, "http://10.0.0.127:20900") shouldBe Right("http://10.0.0.127:20900")
        // Shorthand forms that are not loopback: qod-node reaches them as inet_aton reads them.
        check(runtime, "http://10.1:20900") shouldBe Right("http://10.1:20900")
        check(runtime, "http://1.2.3.4.5:20900") shouldBe Right("http://1.2.3.4.5:20900")
      }
  }

  "the fleet runtime" should "require an explicit internal URL, loopback allowed" in {
    for runtime <- List("fleet", "FLEET") do
      withClue(runtime) {
        check(runtime) shouldBe Left(BootPreflight.InternalUrlFleetMessage)
        check(runtime, podIp = "10.1.2.3") shouldBe Left(BootPreflight.InternalUrlFleetMessage)
        check(runtime, podIp = "$(MY_POD_IP)") shouldBe Left(BootPreflight.InternalUrlFleetMessage)
        check(runtime, bindHost = "10.0.0.9") shouldBe Left(BootPreflight.InternalUrlFleetMessage)
        check(runtime, "   ") shouldBe Left(BootPreflight.InternalUrlFleetMessage)
        check(runtime, "http://manager.example:20900") shouldBe
          Right("http://manager.example:20900")
        check(runtime, "http://127.0.0.1:20900") shouldBe Right("http://127.0.0.1:20900")
      }
  }

  "inetAton" should "read IPv4 addresses as qod-node's resolver does" in {
    BootPreflight.inetAton("127.0.0.1") shouldBe Some(0x7f000001L)
    BootPreflight.inetAton("127.1") shouldBe Some(0x7f000001L)
    BootPreflight.inetAton("10.1.2") shouldBe Some(0x0a010002L)
    BootPreflight.inetAton("4294967295") shouldBe Some(0xffffffffL)
    BootPreflight.inetAton("0x7f.0.0.1") shouldBe Some(0x7f000001L)
    BootPreflight.inetAton("0177.0.0.1") shouldBe Some(0x7f000001L)
    for bad <- List(
        "",
        "4294967296",
        "256.0.0.1",
        "127.256.0.1.",
        "127..1",
        "1.2.3.4.5",
        "08",
        "0xg",
        "a.b",
        "127.16777216"
      )
    do withClue(bad)(BootPreflight.inetAton(bad) shouldBe None)
  }

  "the refusals" should "name the settings to fix" in {
    BootPreflight.InternalUrlFormatMessage shouldBe
      "QOD_INTERNAL_URL (quack-on-demand.nodeAuthz.internalUrl) is not a plain http:// URL with " +
      "a host and no user info, query or fragment: nodes call this replica's decision endpoint " +
      "on it, and qod-node speaks plain HTTP only"
    BootPreflight.InternalUrlPodIpFormatMessage shouldBe
      "QOD_POD_IP (quack-on-demand.nodeAuthz.podIp) does not make a plain http:// URL with a " +
      "host: with QOD_INTERNAL_URL unset, nodes call this replica's decision endpoint on " +
      "http://<pod IP>:<manager port>. Set QOD_POD_IP to this pod's IP (the downward API's " +
      "status.podIP, as the Helm chart does), or set QOD_INTERNAL_URL"
    BootPreflight.InternalUrlBindHostFormatMessage shouldBe
      "QOD_ON_DEMAND_HOST (quack-on-demand.host) does not make a plain http:// URL with a host: " +
      "with QOD_INTERNAL_URL and QOD_POD_IP unset, nodes call this replica's decision endpoint " +
      "on http://<bind address>:<manager port>. Set QOD_INTERNAL_URL"
    BootPreflight.internalUrlKubernetesMessage("http://127.0.0.1:20900") shouldBe
      "the node decide URL is http://127.0.0.1:20900, a loopback address no node pod can reach " +
      "on the kubernetes runtime: leave QOD_INTERNAL_URL unset and set QOD_POD_IP from the " +
      "downward API (the Helm chart does), or set QOD_INTERNAL_URL to this replica's own pod " +
      "address"
    BootPreflight.InternalUrlFleetMessage shouldBe
      "fleet mode requires QOD_INTERNAL_URL (quack-on-demand.nodeAuthz.internalUrl): this " +
      "replica's own plain http:// address on the private network the fleet servers share, one " +
      "per replica (never a load balancer or an address several replicas share, which would " +
      "send a node's verdict calls to a replica that did not mint its handle); every node calls " +
      "it for its statements' verdicts"
    BootPreflight.decideUrlLine("http://10.1.2.3:20900") shouldBe
      "node decide URL: http://10.1.2.3:20900"
  }
