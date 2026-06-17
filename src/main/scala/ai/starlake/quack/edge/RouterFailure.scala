package ai.starlake.quack.edge

/** Classified failure surface for [[FlightSqlRouter.execute]]. Each variant maps to a distinct
  * Arrow Flight `CallStatus` so clients can tell "wrong password" from "no access to this table"
  * from "unknown column" from "node crashed" without parsing the description text.
  *
  *   - [[AccessDenied]] -> `UNAUTHORIZED` (Arrow Flight's PERMISSION_DENIED): ACL gate denied,
  *     row-policy or column-policy rewriter denied.
  *   - [[NotFound]] -> `NOT_FOUND`: pool unknown, tenant-db unknown, table unknown.
  *   - [[BadRequest]] -> `INVALID_ARGUMENT`: DuckDB parser / binder / constraint / conversion
  *     errors flow through here.
  *   - [[Unavailable]] -> `UNAVAILABLE`: no compatible node, transient network failures after a
  *     retry exhausted, transaction-pinned node disappeared.
  *   - [[Internal]] -> `INTERNAL`: every "this shouldn't happen" path. Surfaces as INTERNAL to the
  *     client; the description carries the diagnostic.
  *
  * The connector team asked for distinct status codes per failure shape (R12). Adding a kind label
  * rather than encoding it in the message prefix keeps the wire status code authoritative and
  * removes a parse-the-string anti-pattern at the producer layer.
  */
enum RouterFailure(val reason: String):
  case AccessDenied(override val reason: String) extends RouterFailure(reason)
  case NotFound(override val reason: String)     extends RouterFailure(reason)
  case BadRequest(override val reason: String)   extends RouterFailure(reason)
  case Unavailable(override val reason: String)  extends RouterFailure(reason)
  case Internal(override val reason: String)     extends RouterFailure(reason)
