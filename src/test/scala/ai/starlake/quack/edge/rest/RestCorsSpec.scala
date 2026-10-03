package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The `corsAllowedOrigins` setting: off when empty, `*` alone, or exact origins, and anything that
  * is not an origin refused with the env var named.
  */
class RestCorsSpec extends AnyFlatSpec with Matchers:

  "parse" should "be off for an empty or blank setting" in {
    RestCors.parse("") shouldBe Right(RestCors.Off)
    RestCors.parse(" , ") shouldBe Right(RestCors.Off)
    RestCors.Off.enabled shouldBe false
  }

  it should "read exact origins as the browser serialises them" in {
    val c = RestCors.parse("https://App.Example.com, http://localhost:5173").toOption.get
    c.origins shouldBe Set("https://app.example.com", "http://localhost:5173")
    c.allowOrigin("https://app.example.com") shouldBe Some("https://app.example.com")
    c.allowOrigin("http://localhost:5173") shouldBe Some("http://localhost:5173")
    // Exact: another port, scheme or subdomain is another origin.
    List("http://app.example.com", "https://app.example.com:8443", "https://x.app.example.com")
      .foreach(o => withClue(o)(c.allowOrigin(o) shouldBe None))
  }

  it should "drop a default port, which a browser never sends in Origin" in {
    val c = RestCors
      .parse("https://a.example:443, http://b.example:80, https://c.example:80")
      .toOption
      .get
    c.origins shouldBe Set("https://a.example", "http://b.example", "https://c.example:80")
    c.allowOrigin("https://a.example") shouldBe Some("https://a.example")
    c.allowOrigin("http://b.example") shouldBe Some("http://b.example")
  }

  it should "allow any origin with a lone star, answering *" in {
    val c = RestCors.parse(" * ").toOption.get
    c.allowAny shouldBe true
    c.allowOrigin("https://anything.example") shouldBe Some("*")
  }

  it should "refuse anything that is not an origin, naming the env var" in
    List(
      "example.com",
      "https://a.example/",
      "https://a.example/path",
      "https://a.example?x=1",
      "https://user@a.example",
      "ftp://a.example",
      "https://a.example:99999",
      "*, https://a.example",
      "https://a.example, nope"
    ).foreach { raw =>
      withClue(raw) {
        RestCors.parse(raw).left.toOption.getOrElse("") should include(
          "QOD_REST_CORS_ALLOWED_ORIGINS"
        )
      }
    }
