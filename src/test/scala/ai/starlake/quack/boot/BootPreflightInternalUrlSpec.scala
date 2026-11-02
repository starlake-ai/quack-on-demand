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
    check("local") shouldBe Right("http://127.0.0.1:20901")
    check("local", "http://10.0.0.5:20901") shouldBe Right("http://10.0.0.5:20901")
    check("local", "http://manager.internal:20901/qod") shouldBe
      Right("http://manager.internal:20901/qod")
    check("local", "http://manager.internal") shouldBe Right("http://manager.internal")
  }

  it should "default to the bind address when the manager listens on one specific address" in {
    check("local", bindHost = "192.168.1.5") shouldBe Right("http://192.168.1.5:20901")
    check("local", bindHost = "fd00::5") shouldBe Right("http://[fd00::5]:20901")
    check("local", bindHost = "localhost") shouldBe Right("http://127.0.0.1:20901")
  }

  "every runtime" should "accept what qod-node's URL parser accepts" in {
    for
      runtime <- List("local", "kubernetes", "k8s", "fleet")
      url     <- List(
        "http://qod_manager:20901",
        "http://10.0.0.5:08080",
        "http://10.0.0.5:65535/qod/",
        "http://[fd00::7]:20901",
        "http://[fd00::7]"
      )
    do withClue(s"$runtime $url")(check(runtime, url) shouldBe Right(url))
  }

  it should "refuse a URL that is not plain http with a host" in {
    for
      runtime <- List("local", "kubernetes", "k8s", "fleet")
      url     <- List(
        "https://10.0.0.5:20901",
        "HTTP://10.0.0.5:20901",
        "ftp://10.0.0.5/",
        "http://",
        "http:///x",
        "not a url",
        "http://10.0.0.5:20901/?a=b",
        "http://10.0.0.5:20901/#f",
        "http://10.0.0.5:20901?a=b",
        "http://10.0.0.5#f",
        "http://user:pw@10.0.0.5:20901",
        "http://10.0.0.5:20901/a b",
        "http://a b:20901",
        "http://h%41:20901",
        "http://fd00::7:20901",
        "http://[fd00::7:20901",
        "http://[fd00::7]x:20901",
        "http://[not-ipv6]:20901",
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
    check("local", "http://user:s3cret@10.0.0.5:20901").left.map(_.contains("s3cret")) shouldBe
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
          Left(BootPreflight.internalUrlKubernetesMessage("http://127.0.0.1:20901"))
      )
    for
      runtime <- List("kubernetes", "k8s")
      url     <- List(
        "http://127.0.0.1:20901",
        "http://127.9.9.9:1",
        "http://127.000.000.001:20901",
        "http://127.0.0.1.:20901",
        "http://localhost:20901",
        "http://LOCALHOST:20901",
        "http://localhost.:20901",
        "http://qod.localhost:20901",
        "http://[::1]:20901",
        "http://[0:0:0:0:0:0:0:1]:20901",
        "http://[::ffff:127.0.0.1]:20901",
        "http://0.0.0.0:20901",
        "http://[::]:20901",
        // inet_aton's shorthand, numeric, octal and hex forms, which qod-node's resolver reads as
        // loopback or unspecified addresses.
        "http://127.1:20901",
        "http://127.0.1:20901",
        "http://2130706433:20901",
        "http://0x7f.1:20901",
        "http://0X7F000001:20901",
        "http://0177.0.0.1:20901",
        "http://017700000001:20901",
        "http://0:20901",
        "http://0.0:20901",
        "http://0x0:20901"
      )
    do
      withClue(s"$runtime $url")(
        check(runtime, url) shouldBe Left(BootPreflight.internalUrlKubernetesMessage(url))
      )
  }

  it should "accept the pod IP and an explicit pod address" in {
    for runtime <- List("kubernetes", "KUBERNETES", "k8s", "K8S") do
      withClue(runtime) {
        check(runtime, podIp = "10.1.2.3") shouldBe Right("http://10.1.2.3:20901")
        check(runtime, podIp = "fd00::7") shouldBe Right("http://[fd00::7]:20901")
        check(runtime, "http://qod-0.qod-headless:20901") shouldBe
          Right("http://qod-0.qod-headless:20901")
        check(runtime, "http://10.0.0.127:20901") shouldBe Right("http://10.0.0.127:20901")
        // Shorthand forms that are not loopback: qod-node reaches them as inet_aton reads them.
        check(runtime, "http://10.1:20901") shouldBe Right("http://10.1:20901")
        check(runtime, "http://1.2.3.4.5:20901") shouldBe Right("http://1.2.3.4.5:20901")
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
        check(runtime, "http://manager.example:20901") shouldBe
          Right("http://manager.example:20901")
        check(runtime, "http://127.0.0.1:20901") shouldBe Right("http://127.0.0.1:20901")
      }
  }

  "an explicit URL" should "not name the REST port" in {
    check("local", "http://10.0.0.5:20900") shouldBe
      Left(BootPreflight.internalUrlRestPortMessage(20900, 20901))
    check("fleet", "http://manager.internal:20900/qod") shouldBe
      Left(BootPreflight.internalUrlRestPortMessage(20900, 20901))
    check("local", "http://10.0.0.5:30000") shouldBe Right("http://10.0.0.5:30000")
  }

  private def port(internalPort: Int = 0, rest: Int = 20900) =
    BootPreflight.checkInternalPort(
      NodeAuthzConfig(internalPort = internalPort),
      rest,
      flightPort = 31338,
      quackPort = Some(9494),
      minNodePort = 21900,
      maxNodePort = 22500
    )

  "the internal port" should "default to the REST port + 1" in {
    port() shouldBe Right(20901)
    port(internalPort = 30000) shouldBe Right(30000)
  }

  it should "stay clear of the other listeners and the node ports" in {
    port(internalPort = 20900).left.toOption.get should include("REST")
    port(internalPort = 31338).left.toOption.get should include("FlightSQL")
    port(internalPort = 9494).left.toOption.get should include("Quack")
    port(internalPort = 22000).left.toOption.get should include("node port range")
    port(rest = 65535).left.toOption.get should include("QOD_INTERNAL_PORT")
  }

  "the bind address" should "be loopback for a loopback URL, else the REST bind address" in {
    def host(url: String = "", pod: String = "", bind: String = "0.0.0.0") =
      BootPreflight.internalBindHost(NodeAuthzConfig(internalUrl = url, podIp = pod), bind, 20900)
    host() shouldBe "127.0.0.1"
    host(url = "http://localhost:20901") shouldBe "127.0.0.1"
    host(url = "http://[::1]:20901") shouldBe "::1"
    host(pod = "10.1.2.3") shouldBe "0.0.0.0"
    host(url = "http://mgr.internal:20901") shouldBe "0.0.0.0"
    host(bind = "192.168.1.5") shouldBe "192.168.1.5"
  }

  it should "read a loopback URL in the forms qod-node's resolver reads" in {
    def host(url: String = "", bind: String = "0.0.0.0") =
      BootPreflight.internalBindHost(NodeAuthzConfig(internalUrl = url), bind, 20900)
    host(url = "http://LOCALHOST:20901") shouldBe "127.0.0.1"
    host(url = "http://qod.localhost:20901") shouldBe "127.0.0.1"
    host(url = "http://127.1:20901") shouldBe "127.0.0.1"
    host(url = "http://2130706433:20901") shouldBe "127.0.0.1"
    // The default URL of a manager bound to ::1 is that address.
    host(bind = "::1") shouldBe "::1"
    // An unusable URL is refused by checkInternalUrl; the bind address stays where REST binds.
    host(url = "not a url", bind = "10.0.0.9") shouldBe "10.0.0.9"
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
      "http://<pod IP>:<internal port>. Set QOD_POD_IP to this pod's IP (the downward API's " +
      "status.podIP, as the Helm chart does), or set QOD_INTERNAL_URL"
    BootPreflight.InternalUrlBindHostFormatMessage shouldBe
      "QOD_ON_DEMAND_HOST (quack-on-demand.host) does not make a plain http:// URL with a host: " +
      "with QOD_INTERNAL_URL and QOD_POD_IP unset, nodes call this replica's decision endpoint " +
      "on http://<bind address>:<internal port>. Set QOD_INTERNAL_URL"
    BootPreflight.internalUrlKubernetesMessage("http://127.0.0.1:20901") shouldBe
      "the node decide URL is http://127.0.0.1:20901, a loopback address no node pod can reach " +
      "on the kubernetes runtime: leave QOD_INTERNAL_URL unset and set QOD_POD_IP from the " +
      "downward API (the Helm chart does), or set QOD_INTERNAL_URL to this replica's own pod " +
      "address"
    BootPreflight.InternalUrlFleetMessage shouldBe
      "fleet mode requires QOD_INTERNAL_URL (quack-on-demand.nodeAuthz.internalUrl): this " +
      "replica's own plain http:// address on the private network the fleet servers share, on " +
      "the internal listener's port (QOD_INTERNAL_PORT, by default the manager port + 1), one " +
      "per replica (never a load balancer or an address several replicas share, which would " +
      "send a node's verdict calls to a replica that did not mint its handle); every node calls " +
      "it for its statements' verdicts"
    BootPreflight.internalUrlRestPortMessage(20900, 20901) shouldBe
      "QOD_INTERNAL_URL (quack-on-demand.nodeAuthz.internalUrl) names port 20900, the manager's " +
      "REST port: nodes call this replica's decision endpoint on the internal listener, port " +
      "20901 (QOD_INTERNAL_PORT). Name that port in the URL"
    BootPreflight.decideUrlLine("http://10.0.0.5:20901", "0.0.0.0", 20901) shouldBe
      "node decide URL: http://10.0.0.5:20901 (listening on 0.0.0.0:20901)"
  }
