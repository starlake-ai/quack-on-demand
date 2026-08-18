package ai.starlake.quack.ondemand.api

import Dtos.given
import Endpoints.authToken
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Personal-access-token self-service surface (`/api/auth/pat/create|list|revoke`), split out of
  * [[Endpoints]] to stay below the JVM's 64KB `<clinit>` ceiling (see [[RbacEndpoints]] for the
  * rationale). Registered in [[EndpointModules.all]].
  *
  * Every route is strictly self-scoped: the acting identity comes from the session token via
  * [[Endpoints.authToken]], so there is deliberately NO tenant, user, or owner input anywhere on
  * this surface -- a caller can only ever mint, list and revoke its own tokens, and no request
  * field can widen that. [[PatHandlers]] additionally refuses a PAT presented as the credential, so
  * these are session-only routes even though the transport carries any bearer.
  */
object PatEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  /** Mint. The response is the only place the raw token is ever shown. */
  val create: PublicEndpoint[
    (Option[String], PatCreateRequest),
    (sttp.model.StatusCode, ErrorResponse),
    PatCreateResponse,
    Any
  ] =
    base.post
      .in("auth" / "pat" / "create")
      .in(authToken)
      .in(jsonBody[PatCreateRequest])
      .out(jsonBody[PatCreateResponse])

  /** The caller's own tokens, live and retired, metadata only.
    *
    * CLIENT CONTRACT: this route declares NO body input, so callers MUST post an empty body. A JSON
    * body sent here is never drained by the server, which desynchronizes the HTTP/1.1 connection --
    * the NEXT request on that same (pooled, keep-alive) connection then fails with an EOF while
    * reading the response. The same applies to any request the api-key guard rejects before decode.
    */
  val list: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    PatListResponse,
    Any
  ] =
    base.post
      .in("auth" / "pat" / "list")
      .in(authToken)
      .out(jsonBody[PatListResponse])

  /** Retire one of the caller's own tokens. An id owned by someone else, an unknown id, and an
    * already-revoked id all answer `404 not_found` -- see [[PatHandlers.revoke]].
    */
  val revoke: PublicEndpoint[
    (Option[String], PatRevokeRequest),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("auth" / "pat" / "revoke")
      .in(authToken)
      .in(jsonBody[PatRevokeRequest])
