package ai.starlake.quack.edge.quack

import ai.starlake.quack.QuackNativeConfig
import ai.starlake.quack.edge.CertGen
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import com.typesafe.scalalogging.LazyLogging
import fs2.io.net.Network
import fs2.io.net.tls.{TLSContext, TLSParameters}
import org.http4s.{Header, HttpRoutes, MediaType, Response, Status}
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIStringSyntax

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.{KeyFactory, KeyStore, PrivateKey}
import java.security.cert.{Certificate, CertificateFactory}
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/** The HTTP shell of the native Quack front door: `POST /quack` carries protocol messages, `GET /`
  * and `OPTIONS /quack` answer what a node answers (the Wasm client needs the CORS preamble).
  * Protocol outcomes are always HTTP 200; only an unreadable or oversized body is a 400.
  *
  * A dedicated Ember listener rather than a route on the REST port: the data plane keeps its own
  * port, TLS and lifecycle, as the Flight edge does.
  */
final class QuackFrontDoorServer(
    cfg: QuackNativeConfig,
    handle: Array[Byte] => IO[Array[Byte]],
    sweep: Instant => IO[Unit],
    closeAll: IO[Unit],
    sweepEvery: FiniteDuration = 30.seconds
) extends LazyLogging:

  private val wireType = `Content-Type`(new MediaType("application", "vnd.duckdb"))
  private val cors     = Header.Raw(ci"Access-Control-Allow-Origin", "*")

  private val banner =
    "This is a DuckDB Quack RPC endpoint served by Quack on Demand. Use ATTACH 'quack:...' " +
      "(TYPE quack, TOKEN 'tenant=...&pool=...&user=...&password=...') to connect here.\n"

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "quack" =>
      req.body.take(cfg.maxBodyBytes + 1).compile.to(Array).flatMap { body =>
        if body.length > cfg.maxBodyBytes then
          BadRequest(s"request body exceeds ${cfg.maxBodyBytes} bytes")
        else
          handle(body).map { bytes =>
            Response[IO](Status.Ok).withEntity(bytes).withContentType(wireType).putHeaders(cors)
          }
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
        .withShutdownTimeout(1.second)
      built = tls.fold(base)(ctx => base.withTLS(ctx, TLSParameters.Default))
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
