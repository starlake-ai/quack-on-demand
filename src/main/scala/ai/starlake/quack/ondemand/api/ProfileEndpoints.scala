package ai.starlake.quack.ondemand.api

import Dtos.given
import Endpoints.authToken
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Self-service surface for the profile-only (non-admin) session, split out of [[Endpoints]] like
  * the other domain modules and registered in [[EndpointModules.all]].
  *
  * Identity comes STRICTLY from the session token -- there is no `tenant` / `user` input on either
  * route, so a caller cannot aim them at another principal. That also keeps them clear of
  * `apiKeyGuard`'s tenant-scope check, which would 403 `tenant_forbidden` on any `?tenant=` value
  * for a session whose `manageableTenants` is empty (every non-admin session). Admin sessions may
  * call these too and likewise see only themselves.
  *
  * Both routes take the token through the shared [[Endpoints.authToken]] input (X-API-Key header OR
  * `qod_session` cookie), so the browser path works without the SPA reading the HttpOnly cookie.
  */
object ProfileEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  /** Per-day usage for the calling user over the last `days` (default 30, capped at 365). */
  val usage: PublicEndpoint[
    (Option[Int], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    UsageResponse,
    Any
  ] =
    base.get
      .in("profile" / "usage")
      .in(query[Option[Int]]("days"))
      .in(authToken)
      .out(jsonBody[UsageResponse])

  /** The calling user's slice of the router's recent-statement ring (default 50, capped at 500). */
  val statements: PublicEndpoint[
    (Option[Int], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    StatementHistoryResponse,
    Any
  ] =
    base.get
      .in("profile" / "statements")
      .in(query[Option[Int]]("limit"))
      .in(authToken)
      .out(jsonBody[StatementHistoryResponse])
