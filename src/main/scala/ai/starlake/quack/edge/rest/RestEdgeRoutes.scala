package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.ondemand.api.ErrorResponse
import ai.starlake.quack.ondemand.api.Dtos.given
import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax.*
import org.http4s.{Header, HttpRoutes, MediaType, Method, Request, Response, Status}
import org.http4s.headers.{`Content-Type`, Connection}
import org.typelevel.ci.CIStringSyntax
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID

/** The REST data edge's routes on the manager's listener: every request under `/data/` and nothing
  * else, served through Tapir from [[RestEdgeEndpoints]]. `ManagerServer` mounts them before its
  * own routes; a request whose first path segment is not `data` falls through to them untouched,
  * and every request whose first segment is `data` is answered here, a 404 included, so no spelling
  * under `/data/` (`/data/../api/...`, an encoded `..`) ever reaches a manager route. The segment
  * is read as the manager's API guard and Tapir read it: leading empty segments dropped, each
  * percent-decoded.
  *
  * Exposure is the reverse proxy's: one prefix rule (`/data/`) puts this edge, and only this edge,
  * on the public side; the admin API, the console and `/mcp` stay behind it.
  *
  * What lives here, because it applies to the edge's requests and not to the manager's:
  *   - the gates: `GET` only (405 otherwise), and a `GET` carrying `Content-Length > 0` or
  *     `Transfer-Encoding` is a 400 with `Connection: close`, its body never read (request desync);
  *   - at most `maxConcurrentRequests` requests being answered at once (from the gates to the
  *     response, its body's writing excluded), past which a request is answered `503 edge_busy` at
  *     once: the edge shares the manager's listener and must not take all its work;
  *   - a fresh `X-Request-Id` per request, set on the request the handlers read (a client's own is
  *     replaced, never echoed) and on every response;
  *   - the security headers and the caching defaults (`Cache-Control: private, no-cache` and `Vary:
  *     Authorization` unless the handler set its own);
  *   - a page's share of [[RestPageBudget]], given back once its body has been written;
  *   - the body written in slices of at most [[RestEdgeRoutes.WriteChunkBytes]].
  *
  * Credentials are the handlers' ([[RestAuth]]): a PAT in `Authorization` only. The manager's
  * session cookie, static API key and Basic auth are never read here.
  */
final class RestEdgeRoutes(
    cfg: RestEdgeConfig,
    endpoints: List[ServerEndpoint[Any, IO]],
    newRequestId: () => String = () => UUID.randomUUID().toString,
    /** The handlers' page budget: a request's share is given back once its response is written. */
    pageBudget: RestPageBudget = RestPageBudget.Unbounded
) extends LazyLogging:

  import RestEdgeRoutes.*

  private val tapirRoutes: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(endpoints)

  private val inFlight: Semaphore[IO] =
    Semaphore[IO](cfg.maxConcurrentRequests.toLong).unsafeRunSync()

  private val securityHeaders: List[Header.Raw] =
    List(
      Header.Raw(ci"X-Content-Type-Options", "nosniff"),
      Header.Raw(ci"Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"),
      Header.Raw(ci"Referrer-Policy", "no-referrer")
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

  private def busy: Response[IO] =
    error(Status.ServiceUnavailable, RestError.Busy.code, RestError.Busy.message)
      .putHeaders(Header.Raw(ci"Retry-After", RestResponses.RetryAfterSec.toString))

  /** One request the edge claims: request id, gates, in-flight cap, routes, headers; an escaped
    * error is a decorated 500.
    */
  private def serve(req0: Request[IO]): IO[Response[IO]] =
    val rid    = newRequestId()
    val accept = req0.headers.get(ci"Accept").map(_.toList.map(_.value).mkString(","))
    // `putHeaders` replaces: a client's own X-Request-Id never reaches the handlers.
    val base = req0.removeHeader(ci"Accept").putHeaders(Header.Raw(ci"X-Request-Id", rid))
    val req  = accept.fold(base)(a => base.withAttribute(RestEdgeEndpoints.AcceptAttribute, a))
    gate(req) match
      case Some(refused) => IO.pure(decorate(refused, rid))
      case None          =>
        // The permit covers computing the response, never writing it: a body a broken connection
        // never pulls would otherwise hold its permit for good.
        IO.uncancelable { poll =>
          inFlight.tryAcquire.flatMap {
            case false => IO.pure(decorate(busy, rid))
            case true  => poll(respond(req, rid)).guarantee(inFlight.release)
          }
        }

  private def respond(req: Request[IO], rid: String): IO[Response[IO]] =
    (tapirRoutes <+> notFound).orNotFound
      .run(req)
      .handleError { t =>
        logger.warn(s"rest [$rid] unhandled failure: ${t.getClass.getName}")
        error(Status.InternalServerError, "upstream_error", s"upstream error (request id $rid)")
      }
      .map { resp =>
        // At most one TLS record per chunk (slices of the body, no copy): see WriteChunkBytes. A
        // page's share of the budget is given back once its body has been written, or its write
        // failed; one never pulled is given back by the budget's own deadline.
        val body = resp.body.chunkLimit(WriteChunkBytes).unchunks
        decorate(resp, rid).withBodyStream(body.onFinalize(pageBudget.release(rid)))
      }
      .guaranteeCase {
        case cats.effect.Outcome.Succeeded(_) => IO.unit
        case _                                => pageBudget.release(rid)
      }

  /** Every request whose first path segment is `data`; None for any other. */
  val routes: HttpRoutes[IO] = Kleisli { (req: Request[IO]) =>
    if claims(req) then OptionT.liftF(serve(req)) else OptionT.none[IO, Response[IO]]
  }

object RestEdgeRoutes:

  /** The first path segment the edge serves, `/data/...`. */
  val Prefix: String = "data"

  /** The largest chunk a response body reaches the socket in: one TLS record. fs2's TLS write
    * buffer grows to the largest chunk it is handed and never shrinks, for the connection's life,
    * so a page written as one chunk behind a TLS listener would leave each keep-alive connection
    * holding a page of heap that [[RestPageBudget]] does not count.
    */
  val WriteChunkBytes: Int = 16 * 1024

  /** Whether the edge answers `req`: its first path segment, read as the manager's API guard reads
    * it (leading empty segments dropped, percent-decoded), is [[Prefix]].
    */
  def claims(req: Request[IO]): Boolean =
    req.uri.path.segments.map(_.decoded()).dropWhile(_.isEmpty).headOption.contains(Prefix)

  /** The four routes bound to the handlers. */
  def serverEndpoints(h: RestEdgeHandlers): List[ServerEndpoint[Any, IO]] =
    List(
      RestEdgeEndpoints.listSchemas.serverLogic { case (t, db, req) =>
        h.schemas(t, db, req).map(toTapir)
      },
      RestEdgeEndpoints.listTables.serverLogic { case (t, db, s, req) =>
        h.tables(t, db, s, req).map(toTapir)
      },
      RestEdgeEndpoints.describeTable.serverLogic { case (t, db, s, tb, req) =>
        h.table(t, db, s, tb, req).map(toTapir)
      },
      RestEdgeEndpoints.readRows.serverLogic { case (t, db, s, tb, req) =>
        h.rows(t, db, s, tb, req).map(toTapir)
      }
    )

  /** A body goes out as the encoder's own bytes, with their `Content-Length`. */
  private[rest] def toTapir(
      out: Either[RestEdgeHandlers.Failure, RestOk]
  ): Either[RestEdgeEndpoints.Error, (List[sttp.model.Header], RestEdgeEndpoints.Body)] =
    out.map(ok =>
      (ok.headers :+ sttp.model.Header("Content-Length", ok.bytes.length.toString), ok.bytes.buffer)
    )
