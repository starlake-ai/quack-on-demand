package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.api.ErrorResponse
import sttp.model.{Header, StatusCode}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.model.ServerRequest

/** The REST data edge's four `GET` routes as Tapir values.
  *
  * Registered in `EndpointModules.all` so `GenOpenApi` documents them, and served ONLY by
  * [[RestEdgeServer]] on its own port: `ManagerServer` must never mount them, since the manager's
  * `apiKeyGuard` (static key, session cookie) is exactly the credential set this edge refuses.
  *
  * The query string and the headers are read RAW through one `extractFromRequest` input rather than
  * as typed `query`/`header` inputs: the form decoding behind those turns `+` into a space and
  * answers a duplicated parameter or a second `Authorization` header with a decode failure of its
  * own, whereas the edge keeps `+` literal, answers a duplicate reserved parameter with
  * `invalid_parameter` and answers two `Authorization` headers with the one 401. The parameter
  * vocabulary is therefore documented in each description instead of as OpenAPI parameters, and the
  * bearer scheme is declared on the generated document by `GenOpenApi` (documentation only: the
  * handlers read the header themselves).
  */
object RestEdgeEndpoints:

  /** The request-id header the server mints for every request and the handlers read. */
  val RequestIdHeader = "X-Request-Id"

  /** The OpenAPI tag of the four routes; `GenOpenApi` declares the bearer scheme on it. */
  val Tag = "rest-edge"

  /** The OpenAPI security scheme name of the PAT bearer. */
  val SecuritySchemeName = "restEdgePat"

  private val PortNote =
    "Served by the REST data edge on its own port (quack-rest, default 31339), never on the " +
      "manager port. Auth: `Authorization: Bearer <PAT>` only; the token's tools axis must allow " +
      "`rest`. Parameters: `pool` (default: a read-capable pool of the database), `format` " +
      "(`json` or `csv`; else `Accept`, else JSON)."

  private val TimeTravelNote =
    " DuckLake tables only: at most one of `asOf` (snapshot id), `asOfTag`, `asOfTs` (ISO-8601); " +
      "the pinned snapshot comes back in `X-QoD-Snapshot`. A view is read at the current state, " +
      "with no `X-QoD-Snapshot`, and `asOf*` on it is a 400 `invalid_selector`."

  /** The raw query string as the client sent it, before any decoding. The http4s interpreter's
    * request keeps it unparsed; any other interpreter falls back to the re-rendered URI.
    */
  private def rawQuery(req: ServerRequest): String = req.underlying match
    case r: org.http4s.Request[?] => r.uri.query.renderString
    case _                        =>
      val s = req.uri.toString
      val q = s.indexOf('?')
      if q < 0 then "" else s.substring(q + 1).takeWhile(_ != '#')

  private def values(req: ServerRequest, name: String): List[String] =
    req.headers.filter(_.is(name)).map(_.value).toList

  /** Where [[RestEdgeServer]] parks the client's `Accept` before Tapir sees the request. Tapir's
    * own content negotiation would answer `Accept: text/csv` with a 406 of its own, since the
    * endpoints' body is declared as a plain string; negotiation is the handlers' job.
    */
  private[rest] val AcceptAttribute: org.typelevel.vault.Key[String] =
    org.typelevel.vault.Key.newKey[cats.effect.SyncIO, String].unsafeRunSync()

  private def accept(req: ServerRequest): Option[String] =
    val parked = req.underlying match
      case r: org.http4s.Request[?] => r.attributes.lookup(AcceptAttribute)
      case _                        => None
    parked.orElse(Option(values(req, "Accept")).filter(_.nonEmpty).map(_.mkString(",")))

  /** Everything the handlers read from the request, raw (see the object Scaladoc). */
  val request: EndpointInput[RestRequest] = extractFromRequest { req =>
    RestRequest(
      authorization = values(req, "Authorization"),
      accept = accept(req),
      rawQuery = rawQuery(req),
      requestId = values(req, RequestIdHeader).headOption.getOrElse("")
    )
  }

  private val base = endpoint.get
    .in("api" / "v1" / "tenant" / path[String]("tenant") / "database" / path[String]("tenantDb"))
    .errorOut(statusCode.and(headers).and(jsonBody[ErrorResponse]))
    .out(headers)
    .out(
      byteBufferBody.description(
        "application/json (an array of objects) or text/csv, per `format` then `Accept`; the Content-Type header says which"
      )
    )
    .tag(Tag)

  type Error = (StatusCode, List[Header], ErrorResponse)

  /** A response body: the encoder's buffer, written to the client without a copy. */
  type Body = java.nio.ByteBuffer

  val listSchemas: PublicEndpoint[(String, String, RestRequest), Error, (List[Header], Body), Any] =
    base
      .in("schemas")
      .in(request)
      .description(
        s"""List the schemas of a database the caller can read: `[{"name"}]`. $PortNote"""
      )

  val listTables
      : PublicEndpoint[(String, String, String, RestRequest), Error, (List[Header], Body), Any] =
    base
      .in("schemas" / path[String]("schema") / "tables")
      .in(request)
      .description(
        "List the tables and views of a schema the caller can read: " +
          s"""`[{"name", "type": "table"|"view"}]`; a schema with nothing visible is a 404. $PortNote"""
      )

  val describeTable: PublicEndpoint[
    (String, String, String, String, RestRequest),
    Error,
    (List[Header], Body),
    Any
  ] =
    base
      .in("schemas" / path[String]("schema") / "tables" / path[String]("table"))
      .in(request)
      .description(
        """Describe one table or view as the caller sees it after policy: `{"name", "type", """ +
          s""""columns": [{"name", "type"}]}`. $PortNote$TimeTravelNote"""
      )

  val readRows: PublicEndpoint[
    (String, String, String, String, RestRequest),
    Error,
    (List[Header], Body),
    Any
  ] =
    base
      .in("schemas" / path[String]("schema") / "tables" / path[String]("table") / "rows")
      .in(request)
      .description(
        "Read rows as one SELECT through the caller's grants and policies. `select=a,b`; " +
          "filters `<col>=[not.]<op>.<value>` with op eq, neq, gt, gte, lt, lte, like, ilike, " +
          "in, is (repeat to AND); `order=col[.asc|.desc][.nullsfirst|.nullslast],...`; `limit`, " +
          "`offset` (needs `order`). A reserved name (select, order, limit, offset, asOf, asOfTag, " +
          "asOfTs, pool, format, branch), in any case, with a filter-shaped value is a 400 " +
          "`reserved_column`. Headers: `Content-Range`, `X-QoD-Truncated` when a server or token " +
          "row cap or the response byte cap cut the page. " +
          s"$PortNote$TimeTravelNote"
      )
