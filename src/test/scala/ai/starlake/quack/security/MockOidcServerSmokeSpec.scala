// src/test/scala/ai/starlake/quack/security/MockOidcServerSmokeSpec.scala
package ai.starlake.quack.security

import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** Smoke test that boots the MockOidcServer and verifies the three endpoints
  * respond with correctly shaped JSON. Does not validate JWT signatures.
  */
class MockOidcServerSmokeSpec extends AnyFlatSpec with Matchers:

  "MockOidcServer" should "serve a discovery document with the correct issuer" in:
    val srv    = MockOidcServer.boot()
    val client = HttpClient.newHttpClient()
    try
      val resp = client.send(
        HttpRequest.newBuilder(URI.create(s"${srv.baseUrl}/.well-known/openid-configuration")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      resp.statusCode() shouldBe 200
      val json = parse(resp.body()).getOrElse(fail("invalid JSON"))
      json.hcursor.get[String]("issuer").toOption shouldBe Some(srv.issuer)
      json.hcursor.get[String]("jwks_uri").toOption shouldBe Some(s"${srv.baseUrl}/jwks")
      json.hcursor.get[String]("token_endpoint").toOption shouldBe Some(s"${srv.baseUrl}/token")
    finally srv.shutdown()

  it should "serve the JWKS with one key matching the test signer key id" in:
    val srv    = MockOidcServer.boot()
    val client = HttpClient.newHttpClient()
    try
      val resp = client.send(
        HttpRequest.newBuilder(URI.create(s"${srv.baseUrl}/jwks")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      resp.statusCode() shouldBe 200
      val json = parse(resp.body()).getOrElse(fail("invalid JSON"))
      val keys = json.hcursor.downField("keys").as[List[io.circe.Json]].getOrElse(Nil)
      keys should not be empty
      val kid = keys.head.hcursor.get[String]("kid").toOption
      kid shouldBe Some(JwtTestSigner.keyPair.getKeyID)
    finally srv.shutdown()

  it should "return an access_token for the seeded user" in:
    val srv    = MockOidcServer.boot(seededUser = "testuser", seededPassword = "testpw")
    val client = HttpClient.newHttpClient()
    try
      val body = "grant_type=password&username=testuser&password=testpw&client_id=test-client"
      val resp = client.send(
        HttpRequest.newBuilder(URI.create(s"${srv.baseUrl}/token"))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      resp.statusCode() shouldBe 200
      val json = parse(resp.body()).getOrElse(fail("invalid JSON"))
      json.hcursor.get[String]("token_type").toOption shouldBe Some("Bearer")
      json.hcursor.get[String]("access_token").toOption.isDefined shouldBe true
    finally srv.shutdown()

  it should "return 400 for wrong credentials" in:
    val srv    = MockOidcServer.boot()
    val client = HttpClient.newHttpClient()
    try
      val body = "grant_type=password&username=nobody&password=wrongpw&client_id=test-client"
      val resp = client.send(
        HttpRequest.newBuilder(URI.create(s"${srv.baseUrl}/token"))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      resp.statusCode() shouldBe 400
    finally srv.shutdown()