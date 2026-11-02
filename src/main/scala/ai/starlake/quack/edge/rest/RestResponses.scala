package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.api.ErrorResponse
import io.circe.Json
import org.apache.arrow.vector.ipc.ArrowReader
import sttp.model.{Header, StatusCode}

import scala.jdk.CollectionConverters.*

/** What the handlers read from one HTTP request: every `Authorization` value (two of them is a 401,
  * so they are not collapsed), `Accept`, the RAW query string (see [[RestQuery.parse]] on why the
  * edge decodes it itself) and the request id the server minted for it.
  */
final case class RestRequest(
    authorization: List[String],
    accept: Option[String],
    rawQuery: String,
    requestId: String
)

/** A 200: its headers (content type included) and its body, buffered (bounded by the row cap and
  * the response byte cap).
  */
final case class RestOk(headers: List[Header], bytes: BodyBytes):
  def body: String = bytes.text

/** How the REST edge shapes its answers: the caching and paging headers, the listing bodies in JSON
  * or CSV, the error envelope with its extra headers, and the parameter names an access log line
  * may carry. Split out of [[RestEdgeHandlers]], which decides WHAT to answer; this object only
  * decides how it looks on the wire.
  */
object RestResponses:

  /** The error side at the Tapir boundary, with headers: every error still carries the caching
    * headers, a 401 its challenge and every 503 (`pool_resuming`, `pool_unavailable`) its
    * `Retry-After`.
    */
  type Failure = (StatusCode, List[Header], ErrorResponse)

  /** Every response is private to its `Authorization`; only a DuckLake read pinned by `asOf` or
    * `asOfTag` is immutable, and even that is cached briefly so a revoked grant stops being served
    * quickly.
    */
  val NoCache: String     = "private, no-cache"
  val PinnedCache: String = "private, max-age=300"
  val RetryAfterSec: Int  = 5

  /** A 200's body depends on the credential and on the negotiated format, which `Accept` alone can
    * choose, so a cache must key on both.
    */
  val Vary: String = "Authorization, Accept"

  private[rest] def baseHeaders(fmt: RestFormat, pinned: Boolean): List[Header] =
    List(
      Header("Content-Type", fmt.contentType),
      Header("Cache-Control", if pinned then PinnedCache else NoCache),
      Header("Vary", Vary)
    )

  private[rest] def csv(columns: List[String], rows: Vector[List[Json]]): String =
    val sb = new StringBuilder
    columns.map(RestResultEncoder.csvField).addString(sb, ",").append("\r\n")
    rows.foreach(r => r.map(RestResultEncoder.csvCell).addString(sb, ",").append("\r\n"))
    sb.toString

  private[rest] def listing(
      fmt: RestFormat,
      columns: List[String],
      rows: Vector[List[Json]],
      more: Boolean
  ): RestOk =
    val body = fmt match
      case RestFormat.Json => Json.arr(rows.map(r => Json.obj(columns.zip(r)*))*).noSpaces
      case RestFormat.Csv  => csv(columns, rows)
    RestOk(
      baseHeaders(fmt, pinned = false) ++ Option.when(more)(Header("X-QoD-Truncated", "true")),
      BodyBytes.of(body)
    )

  private[rest] def failure(e: RestError, rid: String): Failure =
    val (status, body) = e.toResponse
    // The request id lets an operator find the WARN that holds the node's text.
    val message =
      if e == RestError.UpstreamError then s"${body.message} (request id $rid)"
      else body.message
    // Every 503 is retryable: a resume in progress, or a pool with no node to serve right now.
    val extra = e match
      case RestError.Unauthorized => List(Header("WWW-Authenticate", "Bearer"))
      case _ if status == StatusCode.ServiceUnavailable =>
        List(Header("Retry-After", RetryAfterSec.toString))
      case _ => Nil
    (
      status,
      List(Header("Cache-Control", NoCache), Header("Vary", "Authorization")) ++ extra,
      body.copy(message = message)
    )

  /** At most `cap` rows of a listing as strings, and whether another row existed. */
  private[rest] def readStrings(reader: ArrowReader, cap: Int): (Vector[Vector[String]], Boolean) =
    val out  = Vector.newBuilder[Vector[String]]
    var n    = 0
    var more = false
    while !more && reader.loadNextBatch() do
      val root = reader.getVectorSchemaRoot
      val vs   = root.getFieldVectors.asScala.toVector
      var i    = 0
      while i < root.getRowCount && !more do
        if n >= cap then more = true
        else
          out += vs.map(v => Option(v.getObject(i)).fold("")(_.toString))
          n += 1
          i += 1
    (out.result(), more)

  /** The sanitised parameter NAMES of a raw query string, never a value. */
  private[rest] def paramNames(raw: String): String =
    val names = RestQuery.decodeQueryString(raw) match
      case Right(pairs) => pairs.map(_._1)
      case Left(_)      => raw.split("&").toVector.filter(_.nonEmpty).map(_.takeWhile(_ != '='))
    names.map(RestError.sanitize).distinct.mkString(",")
