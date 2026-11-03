package ai.starlake.quack.edge.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/** [[OidcDiscovery]] caches an issuer's document for good and remembers a failed discovery for a
  * short while: with an issuer down, each anonymous `GET /api/auth/oidc/start` would otherwise make
  * its own outbound GET and hold a blocking thread for up to 15 s (measured: 200 requests, 200
  * GETs).
  */
class OidcDiscoverySpec extends AnyFlatSpec with Matchers:

  private val IssuerA = "https://idp.example/a"
  private val IssuerB = "https://idp.example/b"

  private def docOf(issuer: String): String =
    s"""{"issuer":"$issuer","authorization_endpoint":"$issuer/auth",""" +
      s""""token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks"}"""

  /** A GET that answers from `answer` and counts how often it was asked, per URL. */
  private final class Gets(answer: String => Either[String, String])
      extends (String => Either[String, String]):
    val asked = new AtomicInteger

    def apply(url: String): Either[String, String] =
      asked.incrementAndGet()
      answer(url)

  "resolve" should "ask a failing issuer once for two calls in a row, and answer the same Left" in {
    val gets      = new Gets(_ => Left("connection refused"))
    val discovery = new OidcDiscovery(gets)
    val first     = discovery.resolve(IssuerA)
    val second    = discovery.resolve(IssuerA)
    first shouldBe Left("connection refused")
    second shouldBe first
    gets.asked.get shouldBe 1
  }

  // The clock below is the injected nanoTime; the default failure TTL is 10 s.
  private val Second = 1000L * 1000 * 1000

  private final class Clock:
    @volatile var nanos = 0L
    def apply(): Long   = nanos

  it should "ask again once 10 s have passed since the failed attempt" in {
    val clock     = new Clock
    val gets      = new Gets(_ => Left("connection refused"))
    val discovery = new OidcDiscovery(gets, nanoTime = clock.apply)
    discovery.resolve(IssuerA) shouldBe Left("connection refused")
    clock.nanos = 10 * Second - 1000
    discovery.resolve(IssuerA) shouldBe Left("connection refused")
    gets.asked.get shouldBe 1

    clock.nanos = 10 * Second + 1000
    discovery.resolve(IssuerA) shouldBe Left("connection refused")
    gets.asked.get shouldBe 2
  }

  it should "count the 10 s from the failed attempt, not from the last call it refused" in {
    val clock     = new Clock
    val gets      = new Gets(_ => Left("connection refused"))
    val discovery = new OidcDiscovery(gets, nanoTime = clock.apply)
    discovery.resolve(IssuerA)
    clock.nanos = 6 * Second
    discovery.resolve(IssuerA)
    gets.asked.get shouldBe 1

    // 11 s after the attempt, 5 s after the refused call: a memo extended by that call would hold.
    clock.nanos = 11 * Second
    discovery.resolve(IssuerA)
    gets.asked.get shouldBe 2
  }

  it should "cache a success after a failed attempt, as it always has" in {
    val clock     = new Clock
    var up        = false
    val gets      = new Gets(_ => if up then Right(docOf(IssuerA)) else Left("connection refused"))
    val discovery = new OidcDiscovery(gets, nanoTime = clock.apply)
    discovery.resolve(IssuerA) shouldBe Left("connection refused")

    up = true
    clock.nanos = 11 * Second
    val resolved = discovery.resolve(IssuerA)
    resolved.map(_.issuer) shouldBe Right(IssuerA)
    gets.asked.get shouldBe 2

    // The document is cached for good: no further GET, however long after.
    clock.nanos = 1000 * Second
    discovery.resolve(IssuerA) shouldBe resolved
    gets.asked.get shouldBe 2
  }

  it should "remember a failure per issuer: another issuer is still asked, and still cached" in {
    val gets = new Gets(url =>
      if url.startsWith(IssuerA) then Left("connection refused") else Right(docOf(IssuerB))
    )
    val discovery = new OidcDiscovery(gets)
    discovery.resolve(IssuerA) shouldBe Left("connection refused")
    discovery.resolve(IssuerB).map(_.issuer) shouldBe Right(IssuerB)
    discovery.resolve(IssuerA) shouldBe Left("connection refused")
    discovery.resolve(IssuerB).map(_.issuer) shouldBe Right(IssuerB)
    gets.asked.get shouldBe 2
  }

  it should "remember a document that cannot be read as a failure too" in {
    val gets      = new Gets(_ => Right("""{"issuer":"https://idp.example/a"}"""))
    val discovery = new OidcDiscovery(gets)
    val first     = discovery.resolve(IssuerA)
    first.left.toOption.exists(_.contains("authorization_endpoint")) shouldBe true
    discovery.resolve(IssuerA) shouldBe first
    gets.asked.get shouldBe 1
  }

  it should "remember a failure that has no text" in {
    val gets      = new Gets(_ => Left(null))
    val discovery = new OidcDiscovery(gets)
    discovery.resolve(IssuerA) shouldBe Left(null)
    discovery.resolve(IssuerA) shouldBe Left(null)
    gets.asked.get shouldBe 1
  }
