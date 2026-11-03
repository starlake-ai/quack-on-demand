package ai.starlake.quack.edge

import com.typesafe.scalalogging.LazyLogging

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpResponse.{BodyHandler, BodySubscriber, ResponseInfo}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{
  CompletableFuture,
  CompletionStage,
  ExecutionException,
  ExecutorService,
  Executors,
  Flow,
  Semaphore,
  TimeUnit,
  TimeoutException
}
import scala.util.control.NonFatal

/** An HTTP exchange with a server the manager does not control: an OPA server, or the identity
  * provider a tenant admin names. Such a server can answer with a body of any size, or hold the
  * body back for as long as it likes, and neither is bounded by the JDK on its own:
  * `BodyHandlers.ofString` buffers whatever arrives, and `HttpRequest.timeout` bounds only the wait
  * for the headers. So a request goes through [[send]], which bounds the size of the answer and the
  * time of the WHOLE exchange (connect, headers and body).
  *
  * The cap covers the body. The response headers are bounded by the JDK's `jdk.http.maxHeaderSize`
  * (384 kB on 21.0.11, measured); on 17.0.4.1 endless headers ran the heap out.
  *
  * Every exchange has a client of its own, shut down when the exchange ends. The JDK's `HttpClient`
  * keeps a connection whose response headers it refuses (an invalid status line, a bad
  * `Content-Length`, a header too big, an HTTP/2 preface, and more): its descriptor and its buffers
  * stay reachable from the client for as long as the client lives, and a caller of `sendAsync` has
  * no handle on the connection to close. With one client for the life of the process, each such
  * answer was a descriptor and its buffers for good, and anyone could make the manager read one
  * (measured: a 380 KB status line, 151 anonymous callbacks, the JVM dead at -Xmx256m; a
  * 40-character one costs a descriptor a callback, so the file limit stops the manager). A client
  * that is shut down closes everything it holds. The price is a selector thread of the client's for
  * as long as the exchange runs, and no connection reuse.
  *
  * The shutdown is `HttpClient.shutdownNow`, which the JDK has from 21, the floor of the manager. A
  * build can still compile on 17, which has no such method: there a client goes only at a GC, its
  * selector thread with it. Measured: on Temurin 17.0.20 (Linux) the connection of a refused answer
  * goes then too, but on Zulu 17.0.4.1 (macOS) nothing ever closes it, and its descriptor leaks as
  * it did before this change.
  *
  * A selector thread beside the blocking thread of the caller is the price of that, so the number
  * of exchanges in flight is bounded: `send` takes the `Semaphore` its caller names,
  * [[idpExchanges]] for the identity providers (a tenant admin picks the issuer, and anyone can
  * make the manager ask it) and OpaClient's own for OPA, so that a flood of one cannot refuse the
  * other. Measured: 1000 simultaneous starts against an issuer that never answers took the manager
  * to 2132 threads with no bound, against 1142 with a client for the life of the process, and only
  * the REST connection budget of 1024 stopped them there. A failed thread start is an
  * `OutOfMemoryError`, which is fatal, and some Kubernetes clusters set `pids.max` to 1024 or 4096.
  * A permit is taken with `tryAcquire` and given back when the exchange ends, however it ended;
  * with none free `send` answers [[Busy]] at once and starts nothing. It never waits: a queue would
  * hold the very blocking threads the bound is meant to save.
  */
object BoundedHttp extends LazyLogging:

  /** The most an identity provider's discovery document or token answer may hold. */
  val MaxIdpAnswerBytes: Int = 256 * 1024

  /** The time an exchange with an identity provider may take, whole. */
  val IdpTimeoutMs: Long = 15000L

  /** The most a JWKS (an identity provider's signing keys) may hold: Nimbus' own limit, kept. */
  val MaxJwksBytes: Int = 50 * 1024

  /** The time the read of a JWKS may take, whole. */
  val JwksTimeoutMs: Long = 5000L

  /** What every failed token exchange says, whatever went wrong: see [[tokenExchange]]. */
  val TokenEndpointFailed: String = "token endpoint request failed"

  /** The most characters of a failure's text that [[send]] answers when the text is the JDK's,
    * which quotes what the server sent. A server picks how long its answer is, and a failure is
    * often remembered (OidcDiscovery keeps one for 10 s per issuer): the JDK quoted a 380 KB status
    * line whole, and 1024 of them pinned 390 MiB.
    */
  val MaxFailureChars: Int = 200

  /** What [[send]] answers when it started nothing for lack of a permit. It says nothing of the
    * server, so, unlike the text of a failure, a caller that remembers failures must not remember
    * it (OidcDiscovery does not): it is the caller's own load, not the issuer's state.
    */
  val Busy: String = "too many exchanges in flight"

  /** The exchanges with identity providers that may run at once, all issuers together. One lasts
    * about the latency of a login at its provider, so 128 is hundreds of logins a second, and a
    * flood against one issuer that never answers holds them for [[IdpTimeoutMs]] at most.
    */
  val IdpExchangeLimit: Int = 128

  /** The permits of the exchanges with identity providers: discovery, token exchange, key sets. */
  val idpExchanges: Semaphore = new Semaphore(IdpExchangeLimit)

  /** `request` sent and its answer read under `capBytes` and `timeoutMs` over the whole exchange:
    * `HttpRequest.timeout` alone bounds only the headers. The body is read by a subscriber that
    * never holds more than `capBytes`, and a read that passes either limit is cancelled, which
    * closes the connection, so the rest of a refused answer is never read. The exchange runs on a
    * client of its own that follows `redirects` (never, unless the caller asks), and the client is
    * shut down before this returns, however the exchange ended.
    *
    * A non-200 status is not a failure here: it comes back as `Right((status, body))`, for the
    * caller to judge. A `Left` says why the exchange gave nothing: an answer over the cap or a
    * deadline passed (a text of this object's), or what the JDK raised, as its class and message.
    * The JDK's message quotes what the server sent when it refuses a malformed answer (an invalid
    * status line, a `Content-Length` or a `Location` it cannot read), so such a text is the
    * SERVER'S: it is cut to [[MaxFailureChars]] characters, with its control characters read as
    * spaces, and a caller that shows it to anyone must not pass it on (see [[tokenExchange]], and
    * the readers of a key set and of a discovery document, which answer one fixed text and log this
    * one). `what` names the exchange in this object's own texts ("OPA", "discovery").
    *
    * `permits` bounds the exchanges in flight, all callers that pass it together: see the object.
    * With none free the answer is `Left(Busy)`, at once, with no client built and no thread
    * started; the permit is back when this returns, however the exchange ended.
    *
    * `secret` is a credential the request carries in a header (a bearer token): the JDK's text can
    * hold it (a server may echo it into a status line, and the JDK quotes the line), so it is
    * masked in that text BEFORE the text is cut, since a cut through the middle of a long secret
    * would leave a part of it that no later masking could find. A request whose body holds a secret
    * (a token request's form) cannot be masked this way and passes no text on at all: see
    * [[tokenExchange]].
    */
  def send(
      request: HttpRequest,
      capBytes: Int,
      timeoutMs: Long,
      what: String,
      permits: Semaphore,
      redirects: HttpClient.Redirect = HttpClient.Redirect.NEVER,
      secret: Option[String] = None
  ): Either[String, (Int, String)] =
    exchange(request, capBytes, timeoutMs, what, permits, redirects) match
      case Ended.Answered(status, body) => Right((status, body))
      case Ended.Limited(text)          => Left(text)
      case Ended.Raised(error)          => Left(describe(error, secret))
      case Ended.Refused                => Left(Busy)

  /** A code-to-token exchange: `form` POSTed to the token endpoint `url`, and the body of its
    * answer when the status is 200, else why not. It is read like any identity provider's answer,
    * under [[MaxIdpAnswerBytes]] and [[IdpTimeoutMs]] over the whole exchange.
    *
    * The form holds the client secret, and the server is the identity provider's own. One that
    * echoes the form into a malformed status line reaches the JDK's exception (the JDK quotes the
    * line), and a caller that shows or logs that text shows the secret. So nothing of the exchange
    * is passed on: any failure comes back as [[TokenEndpointFailed]], and a status other than 200
    * as `refused(status)`, a text the caller makes from the code alone, never from the answer. The
    * log gets the same: the class of what the JDK raised and nothing of its message, or this
    * object's own text when a limit of its was passed.
    */
  def tokenExchange(
      url: String,
      form: String,
      refused: Int => String,
      permits: Semaphore = idpExchanges
  ): Either[String, String] =
    val ended =
      try
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form))
          .timeout(Duration.ofMillis(IdpTimeoutMs))
          .build()
        exchange(
          request,
          MaxIdpAnswerBytes,
          IdpTimeoutMs,
          "token endpoint",
          permits,
          HttpClient.Redirect.NEVER
        )
      catch case NonFatal(e) => Ended.Raised(e)
    ended match
      case Ended.Answered(200, body) => Right(body)
      case Ended.Answered(code, _)   => Left(refused(code))
      case Ended.Limited(text)       =>
        logger.warn(s"token exchange failed: $text")
        Left(TokenEndpointFailed)
      case Ended.Raised(error) =>
        logger.warn(s"token exchange failed: ${error.getClass.getSimpleName}")
        Left(TokenEndpointFailed)
      case Ended.Refused =>
        // Below WARN: a refusal is the cheapest thing an anonymous caller can ask for, so a WARN
        // for each would be a log flood.
        logger.debug(s"token exchange refused: $Busy")
        Left(TokenEndpointFailed)

  /** `error` as a failure text: its class and message, with `secret` masked, then cut (see
    * [[MaxFailureChars]]). The order matters: see [[send]].
    */
  def describe(error: Throwable, secret: Option[String] = None): String =
    val text = s"${error.getClass.getSimpleName}: ${Option(error.getMessage).getOrElse("")}"
    cut(secret.filter(_.nonEmpty).fold(text)(s => text.replace(s, "<redacted>")))

  /** `text` as a failure may carry it: at most [[MaxFailureChars]] characters, and a control
    * character (a bare CR in a status line the JDK quoted, say) read as a space, so that a server
    * cannot forge the line of a log with it.
    */
  private[edge] def cut(text: String): String =
    text.take(MaxFailureChars).map(c => if Character.isISOControl(c) then ' ' else c)

  /** How an exchange ended. */
  private enum Ended:
    /** The server answered, within the limits. */
    case Answered(status: Int, body: String)

    /** A limit of this object's was passed (the cap, the deadline): the text is its own, and holds
      * nothing a server chose.
      */
    case Limited(text: String)

    /** The JDK raised this. Its message can quote what the server sent. */
    case Raised(error: Throwable)

    /** No permit was free: nothing was started. */
    case Refused

  /** The exchange itself, once a permit is taken. `tryAcquire`, never `acquire`: a caller that
    * waited would hold its blocking thread, which is what the bound is meant to save. The permit
    * goes back when the exchange ended however it ended, the client shut down first.
    */
  private def exchange(
      request: HttpRequest,
      capBytes: Int,
      timeoutMs: Long,
      what: String,
      permits: Semaphore,
      redirects: HttpClient.Redirect
  ): Ended =
    if !permits.tryAcquire() then Ended.Refused
    else
      try inOwnClient(request, capBytes, timeoutMs, what, redirects)
      finally permits.release()

  private def inOwnClient(
      request: HttpRequest,
      capBytes: Int,
      timeoutMs: Long,
      what: String,
      redirects: HttpClient.Redirect
  ): Ended =
    try
      val client = HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .followRedirects(redirects)
        .executor(exchangeThreads)
        .build()
      try
        val future =
          client.sendAsync(
            request,
            boundedBodyHandler(capBytes, s"$what response exceeds ${size(capBytes)}")
          )
        awaited(future, timeoutMs, what)
      finally shutdownNow(client)
    catch case e: Exception => Ended.Raised(e)

  private def awaited(
      future: CompletableFuture[HttpResponse[Either[String, String]]],
      timeoutMs: Long,
      what: String
  ): Ended =
    try
      val resp = future.get(timeoutMs, TimeUnit.MILLISECONDS)
      resp.body() match
        case Left(err)       => Ended.Limited(err)
        case Right(bodyText) => Ended.Answered(resp.statusCode(), bodyText)
    catch
      case _: TimeoutException =>
        future.cancel(true)
        Ended.Limited(s"$what request timed out after $timeoutMs ms")
      case e: ExecutionException =>
        Ended.Raised(Option(e.getCause).getOrElse(e))
      case e: InterruptedException =>
        future.cancel(true)
        Thread.currentThread().interrupt()
        Ended.Raised(e)

  /** Shared by every exchange: a client's selector thread is its own, but its other work needs no
    * more threads however many exchanges run. A daemon cached pool, as the JDK's default is for
    * each client.
    */
  private val exchangeThreads: ExecutorService =
    val ids = new AtomicInteger
    Executors.newCachedThreadPool { task =>
      val thread = new Thread(task, s"bounded-http-${ids.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }

  /** `HttpClient.shutdownNow`, which closes the client's connections at once. The JDK keeps a
    * connection whose response headers it refuses for as long as the client lives, so a client is
    * good for one exchange. Found by name because the JDK added it in 21 and a build can compile on
    * 17; on 17 this does nothing, and what that leaves is described with the object.
    */
  private val shutdownNow: HttpClient => Unit =
    try
      val method = classOf[HttpClient].getMethod("shutdownNow")
      client =>
        try
          method.invoke(client)
          ()
        catch case NonFatal(_) => ()
    catch case _: NoSuchMethodException => _ => ()

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
