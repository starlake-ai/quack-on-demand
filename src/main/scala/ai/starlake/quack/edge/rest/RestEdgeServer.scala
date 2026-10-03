package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.edge.CertGen
import ai.starlake.quack.edge.quack.PemKeyStore
import ai.starlake.quack.ondemand.api.ErrorResponse
import ai.starlake.quack.ondemand.api.Dtos.given
import cats.data.{Kleisli, OptionT}
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
import com.comcast.ip4s.{Host, Port}
import com.typesafe.scalalogging.LazyLogging
import fs2.io.net.Network
import fs2.io.net.tls.{TLSContext, TLSParameters}
import io.circe.syntax.*
import org.http4s.{Header, HttpApp, HttpRoutes, MediaType, Method, Request, Response, Status}
import org.http4s.ember.core.EmberException
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.{`Content-Type`, Connection}
import org.typelevel.ci.{CIString, CIStringSyntax}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.Try

/** The HTTP shell of the REST data edge: a dedicated Ember listener serving [[RestEdgeEndpoints]]
  * through Tapir, with the same start/stop/TLS shape as the Quack front door's listener.
  *
  * What lives here and nowhere else, because only the wire sees it:
  *   - the transport gates: `GET` only (405 otherwise), and a `GET` carrying `Content-Length > 0`
  *     or `Transfer-Encoding` is a 400 with `Connection: close`, its body never read (request
  *     desync);
  *   - the Ember limits. The pinned http4s (0.23.24) has all four knobs, so there is no gap:
  *     `withMaxHeaderSize` (bounds the request line, so the URI, too), the header receive timeout
  *     (slowloris), the idle timeout and `withMaxConnections`;
  *   - the trailer fields of a streamed answer, which Tapir cannot express: the route leaves them
  *     in the request's [[RestTrailers]] slot and they are attached here, written by Ember after
  *     the last chunk;
  *   - a fresh `X-Request-Id` per request, set on the request the handlers read (a client's own is
  *     replaced, never echoed) and on every response, including the ones Ember itself generates for
  *     an unparseable or oversized request head;
  *   - the security headers and the default caching headers on every response;
  *   - the client key of the failed-auth throttle, resolved from the TCP peer and, only behind
  *     `trustedProxies`, `X-Forwarded-For`, then handed to the handlers as a request attribute; and
  *     the 429 a blocked client gets on every request, ahead of all of the above.
  */
final class RestEdgeServer(
    cfg: RestEdgeConfig,
    endpoints: List[ServerEndpoint[RestEdgeEndpoints.Caps, IO]],
    newRequestId: () => String = () => UUID.randomUUID().toString,
    /** The failed-auth throttle, the SAME instance the handlers record failures in. */
    throttle: Option[AuthThrottle] = None
) extends LazyLogging:

  import RestEdgeServer.*

  private val authThrottle: AuthThrottle = throttle.getOrElse(AuthThrottle(cfg, _ => ()))
  private val trustedProxies             = cfg.trustedProxyCidrs

  private val tapirRoutes: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(endpoints)

  private val securityHeaders: List[Header.Raw] =
    List(
      Header.Raw(ci"X-Content-Type-Options", "nosniff"),
      Header.Raw(ci"Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"),
      Header.Raw(ci"Referrer-Policy", "no-referrer")
    ) ++ Option.when(cfg.tlsEnabled)(
      Header.Raw(ci"Strict-Transport-Security", "max-age=31536000")
    )

  /** The edge headers every response carries: the request id, the security headers, and the caching
    * defaults unless the handler set its own (private, per `Authorization`).
    */
  private def decorate(resp: Response[IO], rid: String): Response[IO] =
    val withDefaults =
      List(
        Option.unless(resp.headers.get(ci"Cache-Control").isDefined)(
          Header.Raw(ci"Cache-Control", RestResponses.NoCache)
        ),
        Option.unless(resp.headers.get(ci"Vary").isDefined)(
          Header.Raw(ci"Vary", "Authorization")
        )
      ).flatten
    (Header.Raw(ci"X-Request-Id", rid) :: securityHeaders ++ withDefaults)
      .foldLeft(resp.removeHeader(ci"Server"))((r, h) => r.putHeaders(h))

  private def error(status: Status, code: String, message: String): Response[IO] =
    Response[IO](status)
      .withEntity(ErrorResponse(code, message).asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))

  private val notFound: HttpRoutes[IO] =
    Kleisli(_ => OptionT.pure[IO](error(Status.NotFound, "not_found", "not found")))

  /** Before any route: a response when the request is refused, else None. */
  private def gate(req: Request[IO]): Option[Response[IO]] =
    if req.method != Method.GET then
      Some(
        error(Status.MethodNotAllowed, "method_not_allowed", "only GET is supported")
          .putHeaders(Header.Raw(ci"Allow", "GET"))
      )
    else
      val framed = req.headers.get(ci"Transfer-Encoding").isDefined ||
        req.headers
          .get(ci"Content-Length")
          .exists(_.head.value.trim.toLongOption.forall(_ > 0))
      Option.when(framed)(
        error(Status.BadRequest, "invalid_request", "a GET request must not carry a body")
          .putHeaders(Connection.close)
      )

  /** The throttle key of a request: the TCP peer, or behind a trusted proxy the right-most
    * untrusted `X-Forwarded-For` hop ([[ClientAddress]]).
    */
  private def clientOf(req: Request[IO]): String =
    ClientAddress.keyOf(
      req.remote.map(_.host.toInetAddress),
      req.headers.get(ci"X-Forwarded-For").fold(Nil)(_.toList.map(_.value)),
      trustedProxies
    )

  /** A client blocked for failed authentication gets 429 on EVERY request, whatever its path or
    * method, before anything else is looked at. The handlers check again (they are also reachable
    * without this shell); this gate also covers what never reaches them.
    */
  private def blocked(client: String): Option[Response[IO]] =
    authThrottle.blockedFor(client).map { left =>
      val e = RestError.TooManyAuthFailures(left)
      error(Status.TooManyRequests, e.code, e.message)
        .putHeaders(Header.Raw(ci"Retry-After", left.toString))
    }

  /** The whole app: request id, gates, routes, headers; an escaped error is a decorated 500. */
  val app: HttpApp[IO] = Kleisli { (req0: Request[IO]) =>
    val rid      = newRequestId()
    val client   = clientOf(req0)
    val trailers = new RestTrailers
    val accept   = req0.headers.get(ci"Accept").map(_.toList.map(_.value).mkString(","))
    // `putHeaders` replaces: a client's own X-Request-Id never reaches the handlers.
    val base = req0
      .removeHeader(ci"Accept")
      .putHeaders(Header.Raw(ci"X-Request-Id", rid))
      .withAttribute(RestEdgeEndpoints.ClientAttribute, client)
      .withAttribute(RestEdgeEndpoints.TrailersAttribute, trailers)
    val req    = accept.fold(base)(a => base.withAttribute(RestEdgeEndpoints.AcceptAttribute, a))
    val routed = blocked(client).orElse(gate(req)) match
      case Some(refused) => IO.pure(refused)
      case None          => (tapirRoutes <+> notFound).orNotFound.run(req)
    routed
      .handleError { t =>
        logger.warn(s"rest [$rid] unhandled failure: ${t.getClass.getName}")
        error(Status.InternalServerError, "upstream_error", s"upstream error (request id $rid)")
      }
      .map(resp => withTrailers(decorate(resp, rid), trailers))
  }

  private def withTrailers(resp: Response[IO], slot: RestTrailers): Response[IO] =
    slot.get.fold(resp)(fields =>
      resp.withTrailerHeaders(
        fields.map(hs => org.http4s.Headers(hs.map(h => Header.Raw(CIString(h.name), h.value))))
      )
    )

  private def tooLarge: Response[IO] =
    error(Status.RequestHeaderFieldsTooLarge, "invalid_request", "request head exceeds the limit")

  /** Ember's own answer to a request head it could not parse: 431 past the header limit, 400
    * otherwise, decorated like every other response.
    */
  private def headParseFailure(t: Throwable): IO[Response[IO]] = IO {
    val resp = t match
      case _: EmberException.MessageTooLong => tooLarge
      case _ => error(Status.BadRequest, "invalid_request", "malformed request")
    decorate(resp.putHeaders(Connection.close), newRequestId())
  }

  /** Ember's error handler. The pinned Ember raises an oversized request head HERE rather than
    * through the request-line handler, so the 431 is mapped in both; anything else escaping the app
    * is a decorated 500.
    */
  private val emberErrors: PartialFunction[Throwable, IO[Response[IO]]] = {
    case _: EmberException.MessageTooLong =>
      IO(decorate(tooLarge.putHeaders(Connection.close), newRequestId()))
    case t =>
      IO {
        val rid = newRequestId()
        logger.warn(s"rest [$rid] server error: ${t.getClass.getName}")
        decorate(
          error(Status.InternalServerError, "upstream_error", s"upstream error (request id $rid)"),
          rid
        )
      }
  }

  private def tlsContext: IO[Option[TLSContext[IO]]] =
    if !cfg.tlsEnabled then IO.pure(None)
    else
      IO.blocking {
        CertGen.ensureCertFiles(cfg.tlsCertChain, cfg.tlsPrivateKey)
        PemKeyStore.load(cfg.tlsCertChain, cfg.tlsPrivateKey, StorePass)
      }.flatMap(ks => Network[IO].tlsContext.fromKeyStore(ks, StorePass).map(Some(_)))

  def resource: Resource[IO, org.http4s.server.Server] =
    for
      tls <- Resource.eval(tlsContext)
      base = EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString(cfg.host).getOrElse(Host.fromString("0.0.0.0").get))
        .withPort(Port.fromInt(cfg.port).getOrElse(Port.fromInt(31339).get))
        .withHttpApp(app)
        .withMaxConnections(cfg.maxConnections)
        .withMaxHeaderSize(cfg.maxHeaderBytes)
        .withRequestHeaderReceiveTimeout(cfg.headerReceiveTimeoutSec.seconds)
        .withIdleTimeout(cfg.idleTimeoutSec.seconds)
        .withRequestLineParseErrorHandler(headParseFailure)
        .withErrorHandler(emberErrors)
        .withShutdownTimeout(1.second)
      built = tls.fold(base)(ctx => base.withTLS(ctx, TLSParameters.Default))
      server <- built.build
    yield server

  private val release = new AtomicReference[Option[IO[Unit]]](None)

  /** Bind the listener and keep it up until [[stop]]. A bind failure raises (boot aborts). */
  def start(): IO[Unit] =
    resource.allocated.flatMap { case (_, rel) =>
      IO {
        release.set(Some(rel))
        if !cfg.tlsEnabled && !isLoopback(cfg.host) then
          logger.warn(
            s"REST data edge on ${cfg.host}:${cfg.port} runs WITHOUT TLS on a non-loopback " +
              "address: bearer tokens cross the network in clear text (QOD_REST_TLS_ENABLED=true)"
          )
        logger.info(
          s"REST data edge listening on ${cfg.host}:${cfg.port} (TLS=${cfg.tlsEnabled}); " +
            "GET /api/v1/tenant/<tenant>/database/<db>/schemas with Authorization: Bearer <PAT>"
        )
      }
    }

  /** Unbind. Idempotent. */
  def stop(): Unit =
    release.getAndSet(None) match
      case Some(rel) =>
        try rel.unsafeRunSync()
        catch case t: Throwable => logger.warn(s"REST data edge stop: ${t.getMessage}")
      case None => ()

object RestEdgeServer:

  private val StorePass: Array[Char] = "qod-rest-tls".toCharArray

  /** The four routes bound to the handlers. */
  def serverEndpoints(h: RestEdgeHandlers): List[ServerEndpoint[RestEdgeEndpoints.Caps, IO]] =
    List(
      RestEdgeEndpoints.listSchemas.serverLogic { case (t, db, req) =>
        h.schemas(t, db, req).map(toTapir(req))
      },
      RestEdgeEndpoints.listTables.serverLogic { case (t, db, s, req) =>
        h.tables(t, db, s, req).map(toTapir(req))
      },
      RestEdgeEndpoints.describeTable.serverLogic { case (t, db, s, tb, req) =>
        h.table(t, db, s, tb, req).map(toTapir(req))
      },
      RestEdgeEndpoints.readRows.serverLogic { case (t, db, s, tb, req) =>
        h.rows(t, db, s, tb, req).map(toTapir(req))
      }
    )

  /** A buffered body goes out with its `Content-Length`; a streamed one goes out chunked, its
    * trailers handed to the server through the request's slot.
    */
  private[rest] def toTapir(req: RestRequest)(
      out: Either[RestEdgeHandlers.Failure, RestOk]
  ): Either[RestEdgeEndpoints.Error, (List[sttp.model.Header], RestEdgeEndpoints.Body)] =
    out.map { ok =>
      ok.content match
        case RestBody.Buffered(bytes) =>
          // The encoder's own buffer, wrapped rather than copied.
          (
            ok.headers :+ sttp.model.Header("Content-Length", bytes.length.toString),
            fs2.Stream.chunk(fs2.Chunk.byteBuffer(bytes.buffer))
          )
        case RestBody.Streamed(bytes, trailers) =>
          req.trailers.set(trailers)
          (ok.headers, bytes)
    }

  private def isLoopback(host: String): Boolean =
    host == "localhost" || Try(InetAddress.getByName(host).isLoopbackAddress).getOrElse(false)
