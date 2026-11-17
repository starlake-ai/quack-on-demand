package ai.starlake.quack.edge.quack

import ai.starlake.quack.QuackNativeConfig
import ai.starlake.quack.edge.{BoundedBody, CertGen, ConnectionBudget}
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port, SocketAddress}
import com.typesafe.scalalogging.LazyLogging
import fs2.{Chunk, Stream}
import fs2.io.net.{Network, Socket, SocketGroup, SocketOption}
import fs2.io.net.tls.{TLSContext, TLSParameters}
import org.http4s.{Header, HttpRoutes, MediaType, Request, Response, Status}
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.{`Content-Type`, Connection}
import org.typelevel.ci.CIStringSyntax

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.{KeyFactory, KeyStore, PrivateKey}
import java.security.cert.{Certificate, CertificateFactory}
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import scala.annotation.nowarn
import scala.concurrent.duration.*

/** The HTTP shell of the native Quack front door: `POST /quack` carries protocol messages, `GET /`
  * and `OPTIONS /quack` answer what a node answers (the Wasm client needs the CORS preamble).
  * Protocol outcomes are always HTTP 200; only an unreadable or oversized body is a 400, a body
  * that misses its deadline is a 408, and chunked framing a 411.
  *
  * A dedicated Ember listener rather than a route on the REST port: the data plane keeps its own
  * port, TLS and lifecycle, as the Flight edge does.
  */
final class QuackFrontDoorServer(
    cfg: QuackNativeConfig,
    // A protocol message and the host it came from, for the audit row of a refused handshake.
    handle: (Array[Byte], Option[String]) => IO[Array[Byte]],
    // Whether a connection id names a live session: decides how much of a message's body is read.
    isLive: String => Boolean,
    sweep: Instant => IO[Unit],
    closeAll: IO[Unit],
    sweepEvery: FiniteDuration = 30.seconds,
    // How long a read on a TLS listener's socket may wait for the client, handshake included.
    tlsReadDeadline: FiniteDuration = QuackFrontDoorServer.TlsReadDeadline
) extends LazyLogging:

  private val wireType = `Content-Type`(new MediaType("application", "vnd.duckdb"))
  private val cors     = Header.Raw(ci"Access-Control-Allow-Origin", "*")

  private val banner =
    "This is a DuckDB Quack RPC endpoint served by Quack on Demand. Use ATTACH 'quack:...' " +
      "(TYPE quack, TOKEN 'tenant=...&pool=...&user=...&password=...') to connect here.\n"

  private def answer(req: Request[IO], body: Array[Byte]): IO[Response[IO]] =
    handle(body, req.remote.map(_.host.toString)).map { bytes =>
      Response[IO](Status.Ok).withEntity(bytes).withContentType(wireType).putHeaders(cors)
    }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "quack" =>
      // No credential has been checked, and no DuckDB client sends chunked: see
      // BoundedBody.asksChunked.
      if BoundedBody.asksChunked(req) then
        IO.pure(Response[IO](Status.LengthRequired).putHeaders(Connection.close))
      else
        QuackBodyRead(req, cfg, isLive).flatMap {
          case QuackBodyRead.Outcome.Admitted(body)      => answer(req, body)
          case QuackBodyRead.Outcome.NotAdmitted(prefix) =>
            answer(req, prefix).map(_.putHeaders(Connection.close))
          case QuackBodyRead.Outcome.TooLarge(limit) =>
            BadRequest(s"request body exceeds $limit bytes").map(_.putHeaders(Connection.close))
          case QuackBodyRead.Outcome.TimedOut =>
            IO.pure(Response[IO](Status.RequestTimeout).putHeaders(Connection.close))
        }
    case OPTIONS -> Root / "quack" =>
      IO.pure(
        Response[IO](Status.NoContent).putHeaders(
          cors,
          Header.Raw(ci"Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
          Header.Raw(ci"Access-Control-Allow-Headers", "*")
        )
      )
    case GET -> Root => Ok(banner)
  }

  private def tlsContext: IO[Option[TLSContext[IO]]] =
    if !cfg.tlsEnabled then IO.pure(None)
    else
      IO.blocking {
        CertGen.ensureCertFiles(cfg.tlsCertChain, cfg.tlsPrivateKey)
        PemKeyStore.load(cfg.tlsCertChain, cfg.tlsPrivateKey, QuackFrontDoorServer.StorePass)
      }.flatMap(ks =>
        Network[IO].tlsContext.fromKeyStore(ks, QuackFrontDoorServer.StorePass).map(Some(_))
      )

  /** The listener plus its session sweeper, as one resource. */
  def resource: Resource[IO, org.http4s.server.Server] =
    for
      tls <- Resource.eval(tlsContext)
      base = EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString(cfg.host).getOrElse(Host.fromString("0.0.0.0").get))
        .withPort(Port.fromInt(cfg.port).getOrElse(Port.fromInt(9494).get))
        .withHttpApp(routes.orNotFound)
        .withMaxConnections(
          ConnectionBudget.slots(
            "The Quack front door",
            "QOD_QUACK_MAX_CONNECTIONS",
            cfg.maxConnections,
            ConnectionBudget.heldBytes(cfg.maxHeaderBytes, cfg.maxConnectBodyBytes, cfg.tlsEnabled)
          )
        )
        .withReceiveBufferSize(BoundedBody.ReadBufferBytes)
        .withMaxHeaderSize(cfg.maxHeaderBytes)
        .withErrorHandler(BoundedBody.headerLimitHandler(BoundedBody.headersTooLarge))
        .withShutdownTimeout(1.second)
      built = tls.fold(base)(ctx =>
        base
          .withTLS(ctx, TLSParameters.Default)
          .withSocketGroup(QuackFrontDoorServer.readDeadline(tlsReadDeadline))
          // Ember logs a connection error as an error with its stack. A client needs no credential
          // to cause one: it goes silent, speaks no TLS, or drops in the handshake.
          .withConnectionErrorHandler {
            case _: TimeoutException =>
              IO(logger.debug("Quack front door: closed a TLS connection that went silent"))
            case e @ (_: SSLException | _: IOException) =>
              // Text, not the exception: an exception argument would put its stack in the event.
              IO(logger.debug(s"Quack front door: a TLS connection failed: ${e.toString}"))
          }
      )
      server <- built.build
      // The clock is read per tick, and `sweep` builds its IO per tick: a sweep constructed once
      // at listener start would only ever see the sessions that existed then.
      tick = IO.sleep(sweepEvery) *> IO.realTimeInstant.flatMap(sweep).attempt.void
      _ <- tick.foreverM.background
    yield server

  private val release = new AtomicReference[Option[IO[Unit]]](None)

  /** Bind the listener and keep it up until [[stop]]. */
  def start(): IO[Unit] =
    resource.allocated.flatMap { case (_, rel) =>
      IO {
        release.set(Some(rel))
        logger.info(
          s"Quack front door listening on ${cfg.host}:${cfg.port} (TLS=${cfg.tlsEnabled}); " +
            s"DuckDB clients: ATTACH 'quack:<host>:${cfg.port}' AS qod (TYPE quack, TOKEN '...')"
        )
      }
    }

  /** Close every client session's node connection, then unbind. Idempotent. */
  def stop(): Unit =
    release.getAndSet(None) match
      case Some(rel) =>
        try (closeAll.attempt.void *> rel).unsafeRunSync()
        catch case t: Throwable => logger.warn(s"Quack front door stop: ${t.getMessage}")
      case None => ()

object QuackFrontDoorServer:
  private val StorePass: Array[Char] = "qod-quack-tls".toCharArray

  /** Ember's own idle timeout, so the deadline changes nothing for a client in use. */
  val TlsReadDeadline: FiniteDuration = 60.seconds

  /** The network's sockets, every read on an accepted connection bounded by `deadline`. Ember
    * starts its header and idle timeouts once the TLS handshake is over, and the handshake has
    * none: a connection that never speaks would hold its slot for ever, and `maxConnections` of
    * them would lock every client out with no traffic at all.
    *
    * The deadline is per read. With no `handshakeDeadline`, a client that sends one byte just
    * inside it, again and again, keeps its slot through the handshake (spec section 12), and a
    * listener needs a proxy or firewall in front that limits this. With one, the whole handshake is
    * bounded too: [[HandshakeClock]] reads the client's TLS record headers as they pass and, until
    * its first application-data record, bounds each read by what is left of `handshakeDeadline`
    * from the accept. `TLSContext` is sealed, so the record layer is the one place the end of the
    * handshake shows without fs2 internals.
    */
  @nowarn("cat=deprecation")
  private[edge] def readDeadline(
      deadline: FiniteDuration,
      handshakeDeadline: Option[FiniteDuration] = None
  ): SocketGroup[IO] =
    val network                            = Network[IO]
    def bounded(s: Socket[IO]): Socket[IO] =
      val clock = handshakeDeadline.map(new HandshakeClock(_, System.nanoTime()))
      def bound[A](read: IO[Option[Chunk[Byte]]]): IO[Option[Chunk[Byte]]] =
        clock match
          case None    => read.timeout(deadline)
          case Some(c) =>
            c.limit(deadline) match
              case None    => IO.raiseError(new TimeoutException("TLS handshake deadline passed"))
              case Some(t) => read.timeout(t).flatTap(r => IO(r.foreach(c.observe)))
      new Socket[IO]:
        def address                                          = s.address
        def peerAddress                                      = s.peerAddress
        def supportedOptions                                 = s.supportedOptions
        def getOption[A](key: SocketOption.Key[A])           = s.getOption(key)
        def setOption[A](key: SocketOption.Key[A], value: A) = s.setOption(key, value)
        def read(maxBytes: Int)                              = bound(s.read(maxBytes))
        def readN(numBytes: Int)      = bound(s.readN(numBytes).map(Some(_))).map(_.get)
        def reads                     = Stream.repeatEval(read(8192)).unNoneTerminate.unchunks
        def endOfInput                = s.endOfInput
        def endOfOutput               = s.endOfOutput
        def write(bytes: Chunk[Byte]) = s.write(bytes)
        def writes                    = s.writes
        def isOpen                    = s.isOpen
        def localAddress              = s.localAddress
        def remoteAddress             = s.remoteAddress
    new SocketGroup[IO]:
      def client(to: SocketAddress[Host], options: List[SocketOption]) =
        network.client(to, options).map(bounded)
      def server(address: Option[Host], port: Option[Port], options: List[SocketOption]) =
        network.server(address, port, options).map(bounded)
      def serverResource(address: Option[Host], port: Option[Port], options: List[SocketOption]) =
        network
          .serverResource(address, port, options)
          .map((bound, clients) => (bound, clients.map(bounded)))

/** How long a TLS connection may take over its handshake, read from the client's record layer.
  * Every TLS record starts with a 5-byte header (content type, version, 2-byte length). The
  * client's handshake is over once it has sent its Finished: in TLS 1.2 the record that follows its
  * ChangeCipherSpec (type 20), in TLS 1.3 its first application-data record (type 23), which
  * follows a ChangeCipherSpec too when the client sends one for middlebox compatibility. Until that
  * record has fully arrived, each read gets at most what is left of `budget` since `startNanos`;
  * afterwards only the per-read deadline applies. Waiting for the whole record keeps a client from
  * announcing a large one and dripping it; a forged one only reaches the TLS engine, which refuses
  * it.
  */
private[edge] final class HandshakeClock(budget: FiniteDuration, startNanos: Long):
  private val header         = new Array[Byte](5)
  private var filled         = 0
  private var skip           = 0L
  private var sawCcs         = false
  private var ending         = false
  @volatile private var done = false

  /** The deadline for the next read: `perRead`, or less while the handshake lasts; None once the
    * handshake's budget is spent.
    */
  def limit(perRead: FiniteDuration): Option[FiniteDuration] =
    if done then Some(perRead)
    else
      val left = budget.toNanos - (System.nanoTime() - startNanos)
      Option.when(left > 0L)(perRead.min(left.nanos))

  /** Follows the record headers in `bytes`, the client's next bytes on the wire. */
  def observe(bytes: Chunk[Byte]): Unit = synchronized {
    var i = 0
    while !done && i < bytes.size do
      if skip > 0L then
        val n = math.min(skip, (bytes.size - i).toLong).toInt
        skip -= n
        i += n
      else
        header(filled) = bytes(i)
        filled += 1
        i += 1
        if filled == 5 then
          filled = 0
          ending = header(0) == 23 || sawCcs
          if header(0) == 20 then sawCcs = true
          skip = ((header(3) & 0xffL) << 8) | (header(4) & 0xffL)
      if ending && filled == 0 && skip == 0L then done = true
  }

/** In-memory PKCS12 keystore from a PEM certificate chain and a PKCS8 private key: what fs2's
  * `TLSContext` wants, from the files `CertGen` writes (and what operators supply for the Flight
  * edge). A PKCS1 `RSA PRIVATE KEY` is refused with a conversion hint.
  */
object PemKeyStore:

  def load(certPath: String, keyPath: String, password: Array[Char]): KeyStore =
    val chain = certificates(Files.readString(Path.of(certPath), UTF_8))
    val key   = privateKey(Files.readString(Path.of(keyPath), UTF_8))
    val ks    = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry("qod", key, password, chain)
    ks

  private def pemBlocks(pem: String, header: String): List[Array[Byte]] =
    val marker = s"-----BEGIN $header-----"
    val end    = s"-----END $header-----"
    pem
      .split(java.util.regex.Pattern.quote(marker))
      .toList
      .drop(1)
      .map(_.split(java.util.regex.Pattern.quote(end)).head)
      .map(b64 => Base64.getMimeDecoder.decode(b64.replaceAll("\\s", "")))

  private def certificates(pem: String): Array[Certificate] =
    val cf    = CertificateFactory.getInstance("X.509")
    val certs = pemBlocks(pem, "CERTIFICATE").map(der =>
      cf.generateCertificate(new java.io.ByteArrayInputStream(der))
    )
    if certs.isEmpty then
      throw new IllegalArgumentException("no CERTIFICATE block in the PEM chain")
    else certs.toArray

  private def privateKey(pem: String): PrivateKey =
    pemBlocks(pem, "PRIVATE KEY").headOption match
      case None =>
        if pem.contains("RSA PRIVATE KEY") then
          throw new IllegalArgumentException(
            "the private key is PKCS1 (RSA PRIVATE KEY); convert it with " +
              "`openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem`"
          )
        else throw new IllegalArgumentException("no PRIVATE KEY block in the key PEM")
      case Some(der) =>
        val spec = new PKCS8EncodedKeySpec(der)
        List("RSA", "EC", "EdDSA").iterator
          .map(alg => scala.util.Try(KeyFactory.getInstance(alg).generatePrivate(spec)))
          .collectFirst { case scala.util.Success(k) => k }
          .getOrElse(throw new IllegalArgumentException("unsupported private key algorithm"))
