package ai.starlake.quack.ondemand.api

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/** Best-effort logout notification to Starlake. Never throws; failures are reported as `Left` and
  * only logged by callers -- QoD's own logout must never block or fail on Starlake availability.
  */
trait StarlakeNotifier:
  def notifyLogout(tokenSha256: String): Either[String, Unit]

/** Wired in place of [[HttpStarlakeNotifier]] when the Starlake integration is off
  * (`ManagementAuthConfig.slIntegrationOn` is false). Always succeeds without making a call.
  */
object NoopStarlakeNotifier extends StarlakeNotifier:
  def notifyLogout(tokenSha256: String): Either[String, Unit] = Right(())

object StarlakeNotifier:
  def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

/** Posts the sha256 of the revoked session token to Starlake's backchannel-logout endpoint.
  * `post` is injectable for tests; the default implementation is a plain JDK `HttpClient` call
  * with a 5s timeout.
  */
final class HttpStarlakeNotifier(
    slUrl: String,
    post: (String, String) => Either[String, Int] = HttpStarlakeNotifier.jdkPost
) extends StarlakeNotifier:
  def notifyLogout(tokenSha256: String): Either[String, Unit] =
    val url  = s"${slUrl.stripSuffix("/")}/api/v1/auth/qod/backchannel-logout"
    val body = s"""{"tokenSha256":"$tokenSha256"}"""
    post(url, body) match
      case Right(code) if code >= 200 && code <= 299 => Right(())
      case Right(code)                               => Left(s"backchannel-logout returned $code")
      case Left(err)                                 => Left(err)

object HttpStarlakeNotifier:
  private lazy val client =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  def jdkPost(url: String, body: String): Either[String, Int] =
    try
      val request = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      Right(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
    catch case e: Exception => Left(e.getMessage)
