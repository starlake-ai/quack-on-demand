package ai.starlake.quack.edge.opa

import ai.starlake.acl.parser.TableAccess
import ai.starlake.quack.edge.BoundedHttp
import io.circe.Json

import java.net.URI
import java.net.http.{HttpClient, HttpRequest}
import java.time.Duration

/** HTTP transport to an OPA server. `post` is injectable for tests; the default (`jdkPost`) is a
  * JDK `HttpClient` call whose WHOLE exchange (connect, response headers AND response body) is
  * bounded by `timeoutMs` and whose body never buffers past the 1 MiB cap: both limits are
  * [[BoundedHttp.send]]'s.
  */
final class OpaClient(timeoutMs: Int, post: OpaClient.Post):

  def query(url: String, token: Option[String], input: Json, accesses: Set[TableAccess]): Decision =
    post(url, input.noSpaces, token) match
      case Left(err)                                        => Decision.Error(err)
      case Right((code, body)) if code >= 200 && code < 300 => OpaDecision.parse(body, accesses)
      case Right((code, _)) => Decision.Error(s"OPA returned HTTP $code")

object OpaClient:
  type Post = (String, String, Option[String]) => Either[String, (Int, String)]

  /** Hard cap on the response body a `Post` implementation may hand back: an OPA policy misbehaving
    * (or a compromised OPA) should not be able to force unbounded heap growth on the manager.
    */
  val MaxResponseBytes: Int = 1024 * 1024

  def endpoint(base: String, policyPath: String, rule: String): String =
    s"${base.stripSuffix("/")}/v1/data/$policyPath/$rule"

  /** `message` with every occurrence of the bearer token masked. The JDK echoes an invalid header
    * value in its IllegalArgumentException, so a token carrying a control character would otherwise
    * reach the manager log and a superuser's dry-run answer.
    */
  private def redactToken(message: String, token: Option[String]): String =
    token.filter(_.nonEmpty).fold(message)(t => message.replace(t, "<redacted>"))

  def jdkPost(timeoutMs: Int): Post =
    val client =
      HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs.toLong)).build()
    (url, body, token) => send(client, timeoutMs, url, body, token).left.map(redactToken(_, token))

  private def send(
      client: HttpClient,
      timeoutMs: Int,
      url: String,
      body: String,
      token: Option[String]
  ): Either[String, (Int, String)] =
    try
      val b = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofMillis(timeoutMs.toLong))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
      token.foreach(t => b.header("Authorization", s"Bearer $t"))
      BoundedHttp.send(client, b.build(), MaxResponseBytes, timeoutMs.toLong, "OPA")
    catch
      // The request cannot be built: a bad URL, or a header value the JDK refuses (and echoes).
      case e: Exception =>
        Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
