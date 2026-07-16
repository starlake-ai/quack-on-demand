// src/test/scala/ai/starlake/quack/security/SecurityHttpHelpers.scala
package ai.starlake.quack.security

import io.circe.parser.parse

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

/** Shared HTTP helpers for the e2e security specs (previously copied per spec).
  *
  * Mix into a spec and call `get` / `post` against a [[ManagerServerHarness.Harness]]'s
  * `httpClient` + `baseUrl`. The `apiKey` parameter is sent as `X-API-Key`, which the manager
  * accepts for both static keys and session tokens; the `WithCookie` variants send the session JWT
  * through the `qod_session` cookie instead (browser transport).
  */
trait SecurityHttpHelpers:

  /** Per-request timeout. Long enough that a slow cold-JVM first call still succeeds; short enough
    * that a real hang (server stuck, port collision, lost goroutine) fails fast with a readable
    * error instead of dangling the entire suite for minutes.
    */
  protected val RequestTimeout: java.time.Duration = java.time.Duration.ofSeconds(10)

  protected def get(
      client: HttpClient,
      url: String,
      apiKey: Option[String] = None
  ): HttpResponse[String] =
    val b = HttpRequest.newBuilder(URI.create(url)).GET().timeout(RequestTimeout)
    apiKey.foreach(k => b.header("X-API-Key", k))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  protected def post(
      client: HttpClient,
      url: String,
      body: String,
      apiKey: Option[String] = None,
      contentType: String = "application/json"
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(url))
      .header("Content-Type", contentType)
      .timeout(RequestTimeout)
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    apiKey.foreach(k => b.header("X-API-Key", k))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  protected def getWithCookie(
      client: HttpClient,
      url: String,
      cookieToken: String
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(url))
      .header("Cookie", s"qod_session=$cookieToken")
      .GET()
      .timeout(RequestTimeout)
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  protected def postWithCookie(
      client: HttpClient,
      url: String,
      body: String,
      cookieToken: String
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(url))
      .header("Content-Type", "application/json")
      .header("Cookie", s"qod_session=$cookieToken")
      .timeout(RequestTimeout)
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  /** Extract the `error` code from a JSON error body, if any. */
  protected def errorCode(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String]("error").toOption)
