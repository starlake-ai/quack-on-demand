package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.api.{ErrorResponse, SnapshotSelector}
import sttp.model.StatusCode

/** The REST edge's own failures, one case per wire code, shaped like
  * [[ai.starlake.quack.edge.RouterFailure]]: the status and the snake_case code live on the case,
  * so no caller can pair a code with the wrong status.
  *
  * Messages are fixed text plus, at most, a parameter NAME passed through [[RestError.sanitize]].
  * No case carries a parameter VALUE: a filter such as `email=eq.secret@x.io` must be refusable
  * without the secret reaching the body or a log line. Cases that take a `param` sanitise it in the
  * constructor helpers on the companion, which are the only way the parser builds them.
  */
enum RestError(val status: StatusCode, val code: String, val message: String):
  case InvalidFilter(override val message: String)
      extends RestError(StatusCode.BadRequest, "invalid_filter", message)
  case UnknownColumn(override val message: String)
      extends RestError(StatusCode.BadRequest, "unknown_column", message)
  case ReservedColumn(override val message: String)
      extends RestError(StatusCode.BadRequest, "reserved_column", message)
  case OrderRequired
      extends RestError(StatusCode.BadRequest, "order_required", "offset > 0 requires order")
  case InvalidSelector(override val message: String)
      extends RestError(StatusCode.BadRequest, "invalid_selector", message)
  case InvalidKind
      extends RestError(
        StatusCode.BadRequest,
        "invalid_kind",
        "time travel requires a DuckLake database"
      )
  case InvalidParameter(override val message: String)
      extends RestError(StatusCode.BadRequest, "invalid_parameter", message)
  // One body for every credential failure: the cause is never distinguishable.
  case Unauthorized extends RestError(StatusCode.Unauthorized, "unauthorized", "unauthorized")
  case Forbidden(override val message: String)
      extends RestError(StatusCode.Forbidden, "forbidden", message)
  case AclDenied(override val message: String)
      extends RestError(StatusCode.Forbidden, "acl_denied", message)
  // One body for missing and ungranted objects alike.
  case NotFound extends RestError(StatusCode.NotFound, "not_found", "not found")
  case UnsupportedFormat
      extends RestError(
        StatusCode.NotAcceptable,
        "unsupported_format",
        "supported formats: json and csv, plus arrow and parquet on /rows"
      )
  // 410 / 422 / tag-404 from SnapshotSelector; see [[RestError.snapshot]].
  case Snapshot(override val status: StatusCode, override val code: String, msg: String)
      extends RestError(status, code, msg)
  case PoolResuming
      extends RestError(
        StatusCode.ServiceUnavailable,
        "pool_resuming",
        "pool is resuming, retry shortly"
      )
  case PoolUnavailable
      extends RestError(StatusCode.ServiceUnavailable, "pool_unavailable", "pool unavailable")
  case StatementTimeout
      extends RestError(StatusCode.GatewayTimeout, "statement_timeout", "statement timed out")
  // A client key blocked for failed authentication. `retryAfterSec` becomes `Retry-After`.
  case TooManyAuthFailures(retryAfterSec: Int)
      extends RestError(
        StatusCode.TooManyRequests,
        "too_many_auth_failures",
        "too many failed authentication attempts, retry later"
      )
  // The per-user or edge-wide in-flight cap; always `Retry-After: 1`.
  case TooManyRequests
      extends RestError(
        StatusCode.TooManyRequests,
        "too_many_requests",
        "too many concurrent requests, retry shortly"
      )
  // Node exception text is never passed through; the handler appends the request id.
  case UpstreamError extends RestError(StatusCode.BadGateway, "upstream_error", "upstream error")

  /** The Tapir-boundary shape every manager endpoint uses. */
  def toResponse: (StatusCode, ErrorResponse) = (status, ErrorResponse(code, message))

object RestError:

  /** Longest parameter name echoed in a message or a log line. */
  val MaxEchoedNameLength = 64

  /** A parameter name made safe to echo: every char outside printable ASCII (0x20-0x7E) becomes
    * `?`, then the result is cut to [[MaxEchoedNameLength]] chars. Replacing rather than dropping
    * keeps two hostile names that differ only in control bytes distinguishable by length.
    */
  def sanitize(name: String): String =
    name
      .take(MaxEchoedNameLength)
      .map(c => if c >= 0x20 && c <= 0x7e then c else '?')

  def invalidFilter(param: String, why: String): RestError =
    InvalidFilter(s"filter '${sanitize(param)}': $why")

  def unknownColumn(param: String, where: String): RestError =
    UnknownColumn(s"$where: unknown column '${sanitize(param)}'")

  def reservedColumn(param: String): RestError =
    ReservedColumn(
      s"'${sanitize(param)}' is a reserved parameter and cannot filter the column of that name"
    )

  def invalidParameter(param: String, why: String): RestError =
    InvalidParameter(s"parameter '${sanitize(param)}': $why")

  /** Maps a snapshot-selector failure through the shared [[SnapshotSelector.httpError]], except
    * that a missing tag never echoes the tag (the one deliberate difference from the preview
    * endpoint).
    */
  def snapshot(e: SnapshotSelector.SelectorError): RestError = e match
    case SnapshotSelector.SelectorError.TagNotFound(_) => NotFound
    case other                                         =>
      val (status, code, msg) = SnapshotSelector.httpError(other)
      Snapshot(status, code, msg)
