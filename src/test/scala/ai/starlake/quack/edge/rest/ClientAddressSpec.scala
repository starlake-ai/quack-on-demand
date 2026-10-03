package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.fleet.Cidr
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetAddress

/** The throttle key of the REST data edge: the TCP peer, or behind a trusted proxy the right-most
  * untrusted `X-Forwarded-For` hop, normalised so IPv4-mapped IPv6 is IPv4 and native IPv6 is keyed
  * per /64.
  */
class ClientAddressSpec extends AnyFlatSpec with Matchers:

  private def ip(s: String): InetAddress = Cidr.parseAddress(s).get
  private def cidrs(s: String)           = Cidr.parseList(s).toOption.get

  private val proxies = cidrs("10.0.0.0/8, fd00::/8")

  private def keyOf(peer: String, xff: List[String], trusted: List[Cidr] = proxies): String =
    ClientAddress.keyOf(Some(ip(peer)), xff, trusted)

  "keyOf" should "be the peer, whatever X-Forwarded-For says, when the peer is not trusted" in {
    keyOf("203.0.113.9", List("198.51.100.1")) shouldBe "203.0.113.9"
    keyOf("203.0.113.9", List("198.51.100.1"), trusted = Nil) shouldBe "203.0.113.9"
  }

  it should "take the right-most untrusted hop behind a trusted proxy" in {
    keyOf("10.0.0.1", List("198.51.100.7")) shouldBe "198.51.100.7"
    keyOf("10.0.0.1", List("192.0.2.66, 198.51.100.7, 10.0.0.2")) shouldBe "198.51.100.7"
    // Several header lines are one list, in order.
    keyOf("10.0.0.1", List("192.0.2.66", "198.51.100.7, 10.0.0.2")) shouldBe "198.51.100.7"
  }

  it should "never let the client choose its key by prepending entries" in {
    keyOf("10.0.0.1", List("1.2.3.4, 5.6.7.8, 198.51.100.7")) shouldBe "198.51.100.7"
    // Left of the first untrusted hop nothing is read, so garbage there is harmless.
    keyOf("10.0.0.1", List("garbage, 198.51.100.7")) shouldBe "198.51.100.7"
  }

  it should "take the left-most hop when every hop is a trusted proxy" in {
    keyOf("10.0.0.1", List("10.1.1.1, 10.2.2.2")) shouldBe "10.1.1.1"
  }

  it should "fall back to the peer on a missing, empty or malformed header" in {
    keyOf("10.0.0.1", Nil) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("  ")) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("not-an-address")) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("198.51.100.7, bogus")) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("198.51.100.7,,10.0.0.2")) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("example.com")) shouldBe "10.0.0.1"
  }

  it should "strip the port a proxy appended to a hop" in {
    keyOf("10.0.0.1", List("198.51.100.7:51234")) shouldBe "198.51.100.7"
    keyOf("10.0.0.1", List("198.51.100.7:51234, 10.0.0.2:443")) shouldBe "198.51.100.7"
    keyOf("10.0.0.1", List("[2001:db8:1:2::99]:443")) shouldBe "2001:db8:1:2::/64"
    keyOf("10.0.0.1", List("[2001:db8:1:2::99]")) shouldBe "2001:db8:1:2::/64"
    // A trusted hop with a port is still trusted.
    keyOf("10.0.0.1", List("198.51.100.7, 10.0.0.2:8080")) shouldBe "198.51.100.7"
    // A bare IPv6 address is not mistaken for one with a port.
    keyOf("10.0.0.1", List("2001:db8:1:2::99")) shouldBe "2001:db8:1:2::/64"
  }

  it should "never take an unknown or malformed hop, nor anything left of it, as the client" in {
    keyOf("10.0.0.1", List("unknown")) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("198.51.100.7, unknown")) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("198.51.100.7, unknown, 10.0.0.2")) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("198.51.100.7:port")) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("198.51.100.7:99999")) shouldBe "10.0.0.1"
    keyOf("10.0.0.1", List("[2001:db8::1]:")) shouldBe "10.0.0.1"
  }

  it should "be unknown without a peer" in {
    ClientAddress.keyOf(None, List("198.51.100.7"), proxies) shouldBe ClientAddress.Unknown
  }

  "key" should "normalise an IPv4-mapped IPv6 address to IPv4" in {
    val mapped = InetAddress.getByAddress(
      Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 192.toByte, 0, 2, 7)
    )
    ClientAddress.key(mapped) shouldBe "192.0.2.7"
    keyOf("10.0.0.1", List("::ffff:198.51.100.7")) shouldBe "198.51.100.7"
    // A mapped trusted peer is still trusted.
    keyOf("::ffff:10.0.0.1", List("198.51.100.7")) shouldBe "198.51.100.7"
  }

  it should "key native IPv6 by its /64" in {
    ClientAddress.key(ip("2001:db8:1:2:aaaa::1")) shouldBe "2001:db8:1:2::/64"
    ClientAddress.key(ip("2001:db8:1:2:ffff:ffff:ffff:ffff")) shouldBe "2001:db8:1:2::/64"
    ClientAddress.key(ip("2001:db8:1:3::1")) should not be ClientAddress.key(ip("2001:db8:1:2::1"))
    keyOf("fd00::1", List("2001:db8:1:2::99")) shouldBe "2001:db8:1:2::/64"
  }
