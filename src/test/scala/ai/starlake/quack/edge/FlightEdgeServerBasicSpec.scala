package ai.starlake.quack.edge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.Base64

/** The FlightSQL edge's reading of a `Basic` authorization value: it comes from an unauthenticated
  * client, so anything that is not base64 or holds no `user:password` is no basic pair, never an
  * exception out of the header authenticator.
  */
class FlightEdgeServerBasicSpec extends AnyFlatSpec with Matchers:

  private def basic(s: String): Option[String] =
    Some("Basic " + Base64.getEncoder.encodeToString(s.getBytes(StandardCharsets.UTF_8)))

  "basicPairOf" should "split a well-formed value at the first colon" in {
    FlightEdgeServer.basicPairOf(basic("alice:pw:with:colons")) shouldBe
      Some(("alice", "pw:with:colons"))
  }

  it should "give no pair for a value that is not base64, never throw" in {
    FlightEdgeServer.basicPairOf(Some("Basic !!not-base64!!")) shouldBe None
  }

  it should "give no pair for a value without a colon, a bearer or no header" in {
    FlightEdgeServer.basicPairOf(basic("alice")) shouldBe None
    FlightEdgeServer.basicPairOf(Some("Bearer abc")) shouldBe None
    FlightEdgeServer.basicPairOf(None) shouldBe None
  }
