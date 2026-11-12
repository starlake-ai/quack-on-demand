package ai.starlake.quack.edge.auth

import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.ECDHDecrypter
import com.nimbusds.jose.jwk.{Curve, ECKey}
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.Base64

object DriverHandoffSpec:
  def b64url(s: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(s.getBytes(StandardCharsets.UTF_8))

  /** A driver's ephemeral key pair and the `key` parameter it would send. */
  def driverKey(curve: Curve = Curve.P_256): (ECKey, String) =
    val k = new ECKeyGenerator(curve).generate()
    (k, b64url(k.toPublicJWK.toJSONString))

  /** The query parameters of a location, decoded. */
  def queryOf(location: String): Map[String, String] =
    Option(URI.create(location).getRawQuery).toList
      .flatMap(_.split('&'))
      .map(_.split("=", 2))
      .collect { case Array(k, v) => k -> URLDecoder.decode(v, StandardCharsets.UTF_8) }
      .toMap

  def decrypt(jwe: String, k: ECKey): String =
    val o = JWEObject.parse(jwe)
    o.decrypt(new ECDHDecrypter(k))
    o.getPayload.toString

class DriverHandoffSpec extends AnyFlatSpec with Matchers:
  import DriverHandoffSpec.*

  private val (priv, pub) = driverKey()
  private val state       = "abcdefghijklmnopqrstuvwxyz012345"

  "parse" should "accept a port, a public P-256 key and a state" in:
    DriverHandoff.parse(Some("50123"), Some(pub), Some(state)) shouldBe
      Right(DriverHandoff(50123, pub, state))

  it should "refuse a port that is missing, not a number or out of range" in:
    List(None, Some("abc"), Some("80"), Some("1023"), Some("65536"), Some("-1")).foreach { p =>
      withClue(p) {
        DriverHandoff.parse(p, Some(pub), Some(state)).left.toOption.get should include("port")
      }
    }

  it should "refuse a key that is not base64url, too long, not a JWK, another curve or private" in:
    val (_, p384)  = driverKey(Curve.P_384)
    val privateJwk = b64url(priv.toJSONString)
    val notJwk     = b64url("""{"kty":"oct","k":"AAAA"}""")
    val tooLong    = "A" * (DriverHandoff.MaxKeyChars + 1)
    List(None, Some(""), Some("not+base64/url"), Some(tooLong), Some(notJwk), Some(p384))
      .foreach { k =>
        withClue(k) {
          DriverHandoff.parse(Some("50123"), k, Some(state)).left.toOption.get should include("key")
        }
      }
    // The private JWK of a P-256 pub fits the length limit and must still be refused.
    DriverHandoff.parse(Some("50123"), Some(privateJwk), Some(state)).isLeft shouldBe true

  it should "refuse a state that is missing, too short, too long or not base64url" in:
    List(None, Some("short"), Some("a" * 129), Some("has spaces in it, sixteen+")).foreach { s =>
      withClue(s) {
        DriverHandoff.parse(Some("50123"), Some(pub), s).left.toOption.get should include("state")
      }
    }

  it should "never quote the value it refuses" in:
    DriverHandoff.parse(Some("secret-port"), Some(pub), Some(state)).left.toOption.get should
      not include "secret-port"

  "decode" should "read back what encoded wrote, and nothing else" in:
    val h = DriverHandoff(50123, pub, state)
    DriverHandoff.decode(h.encoded) shouldBe Some(h)
    DriverHandoff.decode("50123.only-two") shouldBe None
    DriverHandoff.decode(s"80.$pub.$state") shouldBe None

  "deliver" should "redirect to 127.0.0.1 with the driver's state and a token only its key opens" in:
    val h   = DriverHandoff(50123, pub, state)
    val loc = DriverHandoff.deliver(h, "the-id-token")
    val uri = URI.create(loc)
    uri.getScheme shouldBe "http"
    uri.getHost shouldBe "127.0.0.1"
    uri.getPort shouldBe 50123
    uri.getPath shouldBe DriverHandoff.CallbackPath
    val q = queryOf(loc)
    q("state") shouldBe state
    q.get("error") shouldBe None
    q("result") should not include "the-id-token"
    decrypt(q("result"), priv) shouldBe "the-id-token"

  it should "give a token another key cannot open" in:
    val (other, _) = driverKey()
    val loc        = DriverHandoff.deliver(DriverHandoff(50123, pub, state), "the-id-token")
    an[Exception] should be thrownBy decrypt(queryOf(loc)("result"), other)

  "errorLocation" should "pass a code and replace anything else with idp_error" in:
    val h = DriverHandoff(50123, pub, state)
    queryOf(DriverHandoff.errorLocation(h, "access_denied"))("error") shouldBe "access_denied"
    queryOf(DriverHandoff.errorLocation(h, "<script>"))("error") shouldBe DriverHandoff.IdpError
    queryOf(DriverHandoff.errorLocation(h, "a" * 65))("error") shouldBe DriverHandoff.IdpError
    URI.create(DriverHandoff.errorLocation(h, "x")).getHost shouldBe "127.0.0.1"
