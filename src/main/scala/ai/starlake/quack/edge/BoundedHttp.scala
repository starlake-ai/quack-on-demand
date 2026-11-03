package ai.starlake.quack.edge

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import com.typesafe.scalalogging.LazyLogging

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpResponse.{BodyHandler, BodySubscriber, ResponseInfo}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
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
  * HTTP/1.1 only. The JDK's HTTP/2 reader queues the frames it receives with no bound that the body
  * cap, `jdk.http.maxHeaderSize` or the deadline limit, and the JDK offers `Upgrade: h2c` on every
  * `http://` request (and ALPN `h2` on `https://`). A server that answers 101 and floods PING
  * frames (or SETTINGS, zero-length DATA, WINDOW_UPDATE) halted the manager at -Xmx1024m in 6.2 s,
  * from one anonymous request to a tenant's issuer. HTTP/1.1 has no such frame (six floods of it
  * peaked at 20 to 42 MiB). So every client here speaks HTTP/1.1 only, and so does every other
  * client the manager builds to a URL that someone else chose.
  *
  * Every exchange has a client of its own, shut down when the exchange ends, for three reasons that
  * a client kept for longer met (each measured):
  *
  *   - The JDK's `HttpClient` keeps a connection whose response headers it refuses (an invalid
  *     status line, a bad `Content-Length`, a header too big, an HTTP/2 preface, and more): its
  *     descriptor and its buffers stay reachable from the client for as long as the client lives,
  *     and a caller of `sendAsync` has no handle on the connection to close. With one client for
  *     the life of the process, each such answer was a descriptor and its buffers for good, and
  *     anyone could make the manager read one (a 380 KB status line, 151 anonymous callbacks, the
  *     JVM dead at -Xmx256m; a 40-character one costs a descriptor a callback, so the file limit
  *     stops the manager). A client that is shut down closes everything it holds.
  *   - The pool of a kept client has no size limit and keeps each idle connection for 30 s. A key
  *     set that redirects, each hop to a new address, left a connection in the client for each hop,
  *     and every hop ended in a clean answer: 5395 anonymous callbacks took the manager to 38390
  *     descriptors in 6 s, and the JVM died at 10.6 s at -Xmx1024m.
  *   - A client shut down while other exchanges still run on it leaks their sockets for good: the
  *     JDK leaves open the channel of an exchange that is just starting. A client kept for its
  *     origin and shut down at the first failure did that, and 5196 sockets were left after 1.86
  *     million exchanges to a closed port, surviving every GC.
  *
  * The price is a TIME_WAIT on this host for each exchange, since the client closes first, and no
  * connection reuse. On Linux that caps the exchanges to one destination at about 470 a second
  * (measured between two containers), after which a connect fails with "Cannot assign requested
  * address" until the sockets age out, unless `net.ipv4.tcp_tw_reuse=1` is set. Loopback does not
  * show it.
  *
  * The shutdown is `HttpClient.shutdownNow`, which the JDK has from 21, the floor of the manager. A
  * build can still compile on 17, which has no such method: there a client goes only at a GC, its
  * selector thread with it ([[canShutdownNow]] says which, and the boot says so on stderr).
  * Measured: on Temurin 17.0.20 (Linux) the connection of a refused answer goes then too, but on
  * Zulu 17.0.4.1 (macOS) nothing ever closes it, and its descriptor leaks as it did before this was
  * done.
  *
  * A client costs a selector thread beside the blocking thread of the caller, so the number of
  * exchanges in flight is bounded: `send` takes the [[Exchanges]] its caller names,
  * [[idpExchanges]] for the identity providers (a tenant admin picks the issuer, and anyone can
  * make the manager ask it) and OpaClient's own for OPA, so that a flood of one cannot refuse the
  * other. Measured: 1000 simultaneous starts against an issuer that never answers took the manager
  * to 2132 threads with a client for each exchange and no bound, and only the REST connection
  * budget of 1024 stopped them there. A failed thread start is an `OutOfMemoryError`, which is
  * fatal, and some Kubernetes clusters set `pids.max` to 1024 or 4096.
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
    * flood against one issuer that never answers holds them for [[IdpTimeoutMs]] at most. One
    * origin may take half of them (see [[Exchanges]]).
    */
  val IdpExchangeLimit: Int = 128

  /** Whether the JDK has `HttpClient.shutdownNow` (21 and later), which closes everything a client
    * holds. Found by name: see [[shutdownNow]].
    */
  val canShutdownNow: Boolean =
    try
      classOf[HttpClient].getMethod("shutdownNow")
      true
    catch case _: NoSuchMethodException => false

  /** `HttpClient.shutdownNow`, which closes the client's connections at once. The JDK keeps a
    * connection whose response headers it refuses for as long as the client lives, so a client is
    * good for one exchange. Found by name because the JDK added it in 21 and a build can compile on
    * 17; on 17 this does nothing, and what that leaves is described with the object.
    */
  private val shutdownNow: HttpClient => Unit =
    if canShutdownNow then
      val method = classOf[HttpClient].getMethod("shutdownNow")
      (client: HttpClient) =>
        try
          method.invoke(client)
          ()
        catch case NonFatal(_) => ()
    else (_: HttpClient) => ()

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

  /** Where a request goes, as far as the limits are concerned: its scheme, its host (in lower case)
    * and its port, the authority without the user info the JDK never sends. A redirect to another
    * origin is not counted again: the exchange holds the permits of the origin it was asked for.
    */
  private[edge] final case class Origin(scheme: String, host: String, port: Int)

  private[edge] object Origin:
    def of(uri: URI): Origin =
      val scheme = uri.getScheme.toLowerCase(Locale.ROOT)
      val port   = if uri.getPort >= 0 then uri.getPort else if scheme == "https" then 443 else 80
      Origin(scheme, uri.getHost.toLowerCase(Locale.ROOT), port)

  /** How many exchanges run at once. One limit for all, and half of it for any one origin, so a
    * hanging origin cannot refuse every other one. Past either limit an exchange fails at once,
    * with nothing started: a queue would hold the blocking threads this saves. Each exchange runs
    * in a client of its own (see the object), so the permits are all that is shared.
    *
    * An origin that has used its share is refused before it touches the permits of all: a flood
    * against a hanging origin is a flood of refusals, and a refusal that held a permit of all for
    * the moment it took would starve the other origins all the same (measured: 138 of 200 calls to
    * another origin refused while 8 threads asked the spent one).
    *
    * At most [[Exchanges.MaxOrigins]] shares are kept, and one left unused for 10 minutes goes,
    * since a tenant admin chooses the origins.
    */
  final class Exchanges(limit: Int):
    private val all = new Semaphore(limit)

    // At least 1: with a limit of 1 an origin may still have its one.
    private val perOrigin = math.max(1, limit / 2)

    /** An origin's share of the limit. Expiring one with an exchange still running can only let
      * that origin run a little over its share, and `all` still holds.
      */
    private val origins: Cache[Origin, Semaphore] = Caffeine
      .newBuilder()
      .maximumSize(Exchanges.MaxOrigins.toLong)
      .expireAfterAccess(Duration.ofMinutes(10))
      .executor(task => task.run())
      .build[Origin, Semaphore]()

    /** The exchange of `request` in a client of its own: a permit of the origin's share and one of
      * all, taken with `tryAcquire` and given back when the exchange ended, however it ended; none
      * free is [[Ended.Refused]], with nothing started.
      */
    private[BoundedHttp] def run(
        request: HttpRequest,
        capBytes: Int,
        timeoutMs: Long,
        what: String,
        redirects: HttpClient.Redirect
    ): Ended =
      try
        val origin = Origin.of(request.uri)
        // An origin that has used its share is refused before it touches the permits of all. A
        // refusal that took one for the moment it needed to refuse would still starve the others
        // under a flood against that origin (measured: with 8 threads asking an origin that held
        // its share, 138 of 200 calls to another origin were refused for lack of a permit of all).
        if spent(origin) then Ended.Refused
        else underAll(origin, request, capBytes, timeoutMs, what, redirects)
      catch case NonFatal(e) => Ended.Raised(e)

    /** True when `origin` has a share with no permit free. Advisory (the permit is taken after, and
      * may be refused all the same); it only spares the permits of all a refusal.
      */
    private def spent(origin: Origin): Boolean =
      Option(origins.getIfPresent(origin)).exists(_.availablePermits <= 0)

    private def underAll(
        origin: Origin,
        request: HttpRequest,
        capBytes: Int,
        timeoutMs: Long,
        what: String,
        redirects: HttpClient.Redirect
    ): Ended =
      if !all.tryAcquire() then Ended.Refused
      else
        try
          val share = shareOf(origin)
          if !share.tryAcquire() then Ended.Refused
          else
            try BoundedHttp.inOwnClient(request, capBytes, timeoutMs, what, redirects)
            finally share.release()
        finally all.release()

    /** The share of `origin`, made if it has none. */
    private[edge] def shareOf(origin: Origin): Semaphore =
      origins.get(origin, _ => new Semaphore(perOrigin))

    // ----- what a spec looks at -----

    /** The permits of all that are free. */
    private[edge] def available: Int = all.availablePermits

    /** Takes every free permit of all, and says how many: for a case that needs none free. */
    private[edge] def drain(): Int = all.drainPermits()

    /** Gives `n` permits of all back. */
    private[edge] def give(n: Int): Unit = all.release(n)

    /** The permits of `origin`'s share that are free, if it has one. */
    private[edge] def originFree(origin: Origin): Option[Int] =
      Option(origins.getIfPresent(origin)).map(_.availablePermits)

    /** How many shares are kept, once the bound has been applied. */
    private[edge] def shares: Long =
      origins.cleanUp()
      origins.estimatedSize()

  object Exchanges:

    /** How many origins' shares are kept, whatever the number of origins asked. */
    val MaxOrigins: Int = 128

  /** The permits of the exchanges with identity providers: discovery, token exchange, key sets. */
  val idpExchanges: Exchanges = new Exchanges(IdpExchangeLimit)

  /** `request` sent and its answer read under `capBytes` and `timeoutMs` over the whole exchange:
    * `HttpRequest.timeout` alone bounds only the headers. The body is read by a subscriber that
    * never holds more than `capBytes`, and a read that passes either limit is cancelled, which
    * closes the connection, so the rest of a refused answer is never read. The exchange runs in a
    * client of its own that follows `redirects` (never, unless the caller asks), and the client is
    * shut down before this returns, however the exchange ended, so no connection of it stays open.
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
    * `exchanges` bounds the exchanges in flight, all callers that pass it together, and any one
    * origin to half. With no permit free the answer is `Left(Busy)`, at once, with nothing started;
    * the permits are back when this returns, however the exchange ended.
    *
    * `secret` is a credential the request carries in a header (a bearer token), or None: the JDK's
    * text can hold it (a server may echo it into a status line, and the JDK quotes the line), so it
    * is masked in that text BEFORE the text is cut, since a cut through the middle of a long secret
    * would leave a part of it that no later masking could find. It has no default: a caller that
    * leaves it out by mistake would send a credential unmasked. A request whose body holds a secret
    * (a token request's form) cannot be masked this way and passes no text on at all: see
    * [[tokenExchange]].
    */
  def send(
      request: HttpRequest,
      capBytes: Int,
      timeoutMs: Long,
      what: String,
      exchanges: Exchanges,
      redirects: HttpClient.Redirect = HttpClient.Redirect.NEVER,
      secret: Option[String]
  ): Either[String, (Int, String)] =
    exchanges.run(request, capBytes, timeoutMs, what, redirects) match
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
      exchanges: Exchanges = idpExchanges
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
        exchanges.run(
          request,
          MaxIdpAnswerBytes,
          IdpTimeoutMs,
          "token endpoint",
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

  /** The exchange itself, in a client built for it and shut down when it ended, however it ended
    * (see the object). A client that cannot be built is a failure like any other.
    */
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
        .version(HttpClient.Version.HTTP_1_1)
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
