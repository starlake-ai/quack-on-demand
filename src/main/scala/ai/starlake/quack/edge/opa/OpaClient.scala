package ai.starlake.quack.edge.opa

import ai.starlake.acl.parser.TableAccess
import ai.starlake.quack.edge.BoundedHttp
import io.circe.Json

import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/** HTTP transport to an OPA server. `post` is injectable for tests; the default (`jdkPost`) is a
  * JDK `HttpClient` call whose WHOLE exchange (connect, response headers AND response body) is
  * bounded by `timeoutMs` and whose body never buffers past the 1 MiB cap: both limits are
  * [[BoundedHttp.send]]'s. A tenant admin picks the OPA URL, so the calls go through
  * [[OpaClient.exchanges]]: one client for each OPA server, kept while its exchanges end in a clean
  * answer (a client for each call left a TIME_WAIT for each, and OPA failed closed past about 470
  * uncached decisions a second) and shut down after any other ending, and at most
  * [[OpaClient.ExchangeLimit]] calls in flight, half of them for any one server. A call past that
  * is an error at once (fail closed, and an error is never cached) and asks nobody.
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

  /** The OPA exchanges that may run at once. */
  val ExchangeLimit: Int = 256

  /** The clients and permits of the OPA exchanges: its own, so that a flood of exchanges with
    * identity providers cannot refuse OPA decisions, and a hanging OPA server of one tenant cannot
    * refuse the decisions of another's (half of the limit for any one server).
    */
  val exchanges: BoundedHttp.Exchanges = new BoundedHttp.Exchanges(ExchangeLimit)

  def endpoint(base: String, policyPath: String, rule: String): String =
    s"${base.stripSuffix("/")}/v1/data/$policyPath/$rule"

  /** The default transport. A failure's text never carries the bearer token: the JDK echoes an
    * invalid header value in its IllegalArgumentException, so a token holding a control character
    * would otherwise reach the manager log and a superuser's dry-run answer, and a server can echo
    * the token into a malformed status line, which the JDK quotes. [[BoundedHttp]] masks it in the
    * text before it cuts the text.
    */
  def jdkPost(timeoutMs: Int, through: BoundedHttp.Exchanges = exchanges): Post =
    (url, body, token) => send(timeoutMs, through, url, body, token)

  private def send(
      timeoutMs: Int,
      through: BoundedHttp.Exchanges,
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
      BoundedHttp.send(
        b.build(),
        MaxResponseBytes,
        timeoutMs.toLong,
        "OPA",
        through,
        secret = token
      )
    catch
      // The request cannot be built: a bad URL, or a header value the JDK refuses (and echoes).
      case e: Exception => Left(BoundedHttp.describe(e, token))
