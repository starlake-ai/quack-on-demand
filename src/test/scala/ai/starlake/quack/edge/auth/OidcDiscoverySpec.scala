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

  private val Day = 24L * 3600 * Second

  it should "fetch a document again once a day has passed since it was fetched" in {
    val clock     = new Clock
    val gets      = new Gets(_ => Right(docOf(IssuerA)))
    val discovery = new OidcDiscovery(gets, nanoTime = clock.apply)
    discovery.resolve(IssuerA).map(_.issuer) shouldBe Right(IssuerA)

    // A document is kept for a day, however many calls read it: not extended by being read.
    clock.nanos = Day / 2
    discovery.resolve(IssuerA).map(_.issuer) shouldBe Right(IssuerA)
    clock.nanos = Day - Second
    discovery.resolve(IssuerA).map(_.issuer) shouldBe Right(IssuerA)
    gets.asked.get shouldBe 1

    // Past the day the issuer is asked again: before, a document lasted until the manager restarted.
    clock.nanos = Day + Second
    discovery.resolve(IssuerA).map(_.issuer) shouldBe Right(IssuerA)
    gets.asked.get shouldBe 2
  }

  it should "answer a Left for an expired document whose issuer no longer answers, and remember that briefly" in {
    val clock     = new Clock
    var up        = true
    val gets      = new Gets(_ => if up then Right(docOf(IssuerA)) else Left("gone"))
    val discovery = new OidcDiscovery(gets, nanoTime = clock.apply)
    discovery.resolve(IssuerA).map(_.issuer) shouldBe Right(IssuerA)

    up = false
    clock.nanos = Day + Second
    discovery.resolve(IssuerA) shouldBe Left("gone")
    discovery.resolve(IssuerA) shouldBe Left("gone")
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

  // The issuer is a tenant admin's choice, and every setting of a new one would leave an entry for
  // the life of the process: a document can hold up to the 256 KiB that BoundedHttp lets through.
  it should "hold at most 1024 documents, however many issuers are asked, and answer every one" in {
    val gets = new Gets(url => Right(docOf(url.stripSuffix("/.well-known/openid-configuration"))))
    val discovery = new OidcDiscovery(gets)
    val issuers   = (1 to 3000).map(i => s"https://idp.example/$i")

    issuers.foreach(i => discovery.resolve(i).map(_.issuer) shouldBe Right(i))

    discovery.cachedDocuments should be <= 1024L
    // One that was let go costs one more GET, never a wrong answer.
    issuers.foreach(i => discovery.resolve(i).map(_.issuer) shouldBe Right(i))
  }
