package ai.starlake.quack.edge

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse.{BodyHandler, BodySubscriber, ResponseInfo}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.{
  CompletableFuture,
  CompletionStage,
  ExecutionException,
  Flow,
  TimeUnit,
  TimeoutException
}

/** An HTTP exchange with a server the manager does not control: an OPA server, or the identity
  * provider a tenant admin names. Such a server can answer with a body of any size, or hold the
  * body back for as long as it likes, and neither is bounded by the JDK on its own:
  * `BodyHandlers.ofString` buffers whatever arrives, and `HttpRequest.timeout` bounds only the wait
  * for the headers. So a request goes through [[send]], which bounds the size of the answer and the
  * time of the WHOLE exchange (connect, headers and body).
  */
object BoundedHttp:

  /** The most an identity provider's discovery document or token answer may hold. */
  val MaxIdpAnswerBytes: Int = 256 * 1024

  /** The time an exchange with an identity provider may take, whole. */
  val IdpTimeoutMs: Long = 15000L

  /** `request` sent and its answer read under `capBytes` and `timeoutMs` over the whole exchange:
    * `HttpRequest.timeout` alone bounds only the headers. The body is read by a subscriber that
    * never holds more than `capBytes`, and a read that passes either limit is cancelled, which
    * closes the connection, so the rest of a refused answer is never read.
    *
    * A non-200 status is not a failure here: it comes back as `Right((status, body))`, for the
    * caller to judge. A `Left` says why the exchange gave nothing: an answer over the cap, a
    * deadline passed, or the exception's class and message. It names no header and no body of the
    * request, so a caller may put its text in an error. `what` names the exchange in the text
    * ("OPA", "discovery").
    */
  def send(
      client: HttpClient,
      request: HttpRequest,
      capBytes: Int,
      timeoutMs: Long,
      what: String
  ): Either[String, (Int, String)] =
    try
      val future =
        client.sendAsync(
          request,
          boundedBodyHandler(capBytes, s"$what response exceeds ${size(capBytes)}")
        )
      awaited(future, timeoutMs, what)
    catch case e: Exception => Left(describe(e))

  private def awaited(
      future: CompletableFuture[HttpResponse[Either[String, String]]],
      timeoutMs: Long,
      what: String
  ): Either[String, (Int, String)] =
    try
      val resp = future.get(timeoutMs, TimeUnit.MILLISECONDS)
      resp.body() match
        case Left(err)       => Left(err)
        case Right(bodyText) => Right((resp.statusCode(), bodyText))
    catch
      case _: TimeoutException =>
        future.cancel(true)
        Left(s"$what request timed out after $timeoutMs ms")
      case e: ExecutionException =>
        Left(describe(Option(e.getCause).getOrElse(e)))
      case e: InterruptedException =>
        future.cancel(true)
        Thread.currentThread().interrupt()
        Left(describe(e))

  private def describe(e: Throwable): String =
    s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"

  /** A cap as a person reads it: "1 MiB", "256 KiB", "1000 bytes". */
  private def size(bytes: Int): String =
    if bytes % (1024 * 1024) == 0 then s"${bytes / (1024 * 1024)} MiB"
    else if bytes % 1024 == 0 then s"${bytes / 1024} KiB"
    else s"$bytes bytes"

  private def boundedBodyHandler(
      cap: Int,
      tooLarge: String
  ): BodyHandler[Either[String, String]] =
    (_: ResponseInfo) => new BoundedBodySubscriber(cap, tooLarge)

  /** Accumulates the response body up to `cap` bytes; at the first byte beyond that it cancels the
    * upstream subscription and completes with `Left(tooLarge)` instead of buffering further, so a
    * misbehaving server cannot force unbounded heap growth even though the body is read through the
    * async API.
    */
  private final class BoundedBodySubscriber(cap: Int, tooLarge: String)
      extends BodySubscriber[Either[String, String]]:
    private val result                          = new CompletableFuture[Either[String, String]]()
    private val buffer                          = new ByteArrayOutputStream()
    private var subscription: Flow.Subscription = null
    private var exceeded                        = false

    override def onSubscribe(s: Flow.Subscription): Unit =
      subscription = s
      s.request(Long.MaxValue)

    override def onNext(items: java.util.List[ByteBuffer]): Unit =
      items.forEach { bb =>
        if !exceeded then
          if buffer.size().toLong + bb.remaining() > cap then
            exceeded = true
            subscription.cancel()
            result.complete(Left(tooLarge))
          else
            val bytes = new Array[Byte](bb.remaining())
            bb.get(bytes)
            buffer.write(bytes, 0, bytes.length)
      }

    override def onError(t: Throwable): Unit =
      if !exceeded then result.completeExceptionally(t)

    override def onComplete(): Unit =
      if !exceeded then
        result.complete(Right(new String(buffer.toByteArray, StandardCharsets.UTF_8)))

    override def getBody: CompletionStage[Either[String, String]] = result
