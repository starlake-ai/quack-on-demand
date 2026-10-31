package ai.starlake.quack.edge.opa

import ai.starlake.acl.parser.TableAccess
import io.circe.Json

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpResponse.{BodyHandler, BodySubscriber, ResponseInfo}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.{
  CompletableFuture,
  CompletionStage,
  ExecutionException,
  Flow,
  TimeUnit,
  TimeoutException
}

/** HTTP transport to an OPA server. `post` is injectable for tests; the default (`jdkPost`) is a
  * JDK `HttpClient` call whose WHOLE exchange (connect, response headers AND response body) is
  * bounded by `timeoutMs`: `HttpRequest.timeout` alone only bounds the headers, so the body read is
  * driven through `sendAsync` and a hard `Future.get(timeoutMs, ...)` deadline, with the body
  * itself read by a bounded `BodySubscriber` that never buffers past the 1 MiB cap.
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
    var pending: Option[CompletableFuture[HttpResponse[Either[String, String]]]] = None
    try
      val b = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofMillis(timeoutMs.toLong))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
      token.foreach(t => b.header("Authorization", s"Bearer $t"))
      val future = client.sendAsync(b.build(), boundedBodyHandler(MaxResponseBytes))
      pending = Some(future)
      val resp = future.get(timeoutMs.toLong, TimeUnit.MILLISECONDS)
      resp.body() match
        case Left(err)       => Left(err)
        case Right(bodyText) => Right((resp.statusCode(), bodyText))
    catch
      case _: TimeoutException =>
        pending.foreach(_.cancel(true))
        Left(s"OPA request timed out after $timeoutMs ms")
      case e: ExecutionException =>
        val cause = Option(e.getCause).getOrElse(e)
        Left(s"${cause.getClass.getSimpleName}: ${Option(cause.getMessage).getOrElse("")}")
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
      case e: Exception =>
        Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")

  private def boundedBodyHandler(cap: Int): BodyHandler[Either[String, String]] =
    (_: ResponseInfo) => new BoundedBodySubscriber(cap)

  /** Accumulates the response body up to `cap` bytes; beyond that it cancels the upstream
    * subscription and completes with a `Left` instead of buffering further, so a misbehaving OPA
    * cannot force unbounded heap growth even though the body is read through the async API.
    */
  private final class BoundedBodySubscriber(cap: Int)
      extends BodySubscriber[Either[String, String]]:
    private val result                          = new CompletableFuture[Either[String, String]]()
    private val buffer                          = new ByteArrayOutputStream()
    private var subscription: Flow.Subscription = null
    private var exceeded                        = false

    override def onSubscribe(s: Flow.Subscription): Unit =
      subscription = s
      s.request(Long.MaxValue)

    override def onNext(items: java.util.List[ByteBuffer]): Unit =
      if !exceeded then
        items.forEach { bb =>
          val bytes = new Array[Byte](bb.remaining())
          bb.get(bytes)
          buffer.write(bytes)
        }
        if buffer.size() > cap then
          exceeded = true
          subscription.cancel()
          result.complete(Left("OPA response exceeds 1 MiB"))

    override def onError(t: Throwable): Unit =
      if !exceeded then result.completeExceptionally(t)

    override def onComplete(): Unit =
      if !exceeded then
        result.complete(Right(new String(buffer.toByteArray, StandardCharsets.UTF_8)))

    override def getBody: CompletionStage[Either[String, String]] = result
