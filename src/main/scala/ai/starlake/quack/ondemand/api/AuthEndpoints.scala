package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.OidcSsoService
import Dtos.given
import Endpoints.authToken
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Login/session + OIDC SSO surface, split out of [[Endpoints]] to stay below the JVM's 64KB
  * `<clinit>` ceiling (see [[RbacEndpoints]] for the full rationale). Registered in
  * [[EndpointModules.all]].
  */
object AuthEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  // ----- UI login -----
  // Login also sets the qod_session cookie (HttpOnly, SameSite=Lax) so the
  // browser auto-attaches the JWT on subsequent `/api/...` calls without the
  // UI having to stash a token in localStorage. CLI / static-key callers
  // still get `LoginResponse.token` in the JSON body and can send it via
  // X-API-Key as before.
  //
  // `X-Forwarded-Proto` (injected by any TLS-terminating ingress) feeds the
  // handler's auto-derive for the cookie's `Secure` flag: present + `https`
  // -> Secure cookie; absent or `http` -> non-Secure (so plaintext local-jar
  // runs don't silently drop the Set-Cookie). The operator can still force
  // either value via `QOD_SESSION_COOKIE_SECURE`.
  val login: PublicEndpoint[
    (LoginRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    (sttp.model.headers.CookieValueWithMeta, LoginResponse),
    Any
  ] =
    base.post
      .in("auth" / "login")
      .in(jsonBody[LoginRequest])
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(setCookie(SessionTokenStore.CookieName))
      .out(jsonBody[LoginResponse])

  // Logout accepts the token via cookie OR header so the browser path works
  // even though JS can't read the HttpOnly cookie. The response always
  // clears the cookie (Max-Age=0); the handler also adds the jti to the
  // denylist while the manager process stays up. Same `X-Forwarded-Proto`
  // gate as `login` so the clear-cookie response carries the same `Secure`
  // attribute the browser saw at set time (mismatched attributes prevent
  // the clear from landing on some browsers).
  val logout: PublicEndpoint[
    (Option[String], Option[String], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    sttp.model.headers.CookieValueWithMeta,
    Any
  ] =
    base.post
      .in("auth" / "logout")
      .in(header[Option[String]]("X-API-Key"))
      .in(cookie[Option[String]](SessionTokenStore.CookieName))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(setCookie(SessionTokenStore.CookieName))

  val whoami: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    WhoamiResponse,
    Any
  ] =
    base.get
      .in("auth" / "whoami")
      .in(authToken)
      .out(jsonBody[WhoamiResponse])

  // Unauthenticated: the SPA calls this before login (tenant from the URL) to decide whether to
  // render the password form ("db") or redirect to /api/auth/oidc/start ("oidc"). A db tenant or a
  // fully-configured OIDC tenant returns 200; an unknown / misconfigured tenant returns 400 with a
  // stable error code.
  val authMode: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    AuthModeResponse,
    Any
  ] =
    base.get
      .in("auth" / "mode")
      .in(query[Option[String]]("tenant"))
      .out(jsonBody[AuthModeResponse])

  // OIDC SSO redirect endpoints. start returns Either (Left = JSON error, Right = 302 redirect),
  // so it uses `base` (which carries the JSON errorOut). callback/logout always 302 (no error
  // channel) so they use bare `endpoint`; failures are encoded in the Location query string.
  val oidcStart: PublicEndpoint[
    (Option[String], Option[String], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    (sttp.model.StatusCode, String, sttp.model.headers.CookieValueWithMeta),
    Any
  ] =
    base.get
      .in("auth" / "oidc" / "start")
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("returnTo"))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(statusCode)
      .out(header[String]("Location"))
      .out(setCookie(OidcSsoService.STATE_COOKIE))

  val oidcCallback: PublicEndpoint[
    (Option[String], Option[String], Option[String], Option[String]),
    Unit,
    (
        sttp.model.StatusCode,
        String,
        sttp.model.headers.CookieValueWithMeta,
        sttp.model.headers.CookieValueWithMeta
    ),
    Any
  ] =
    endpoint.get
      .in("api" / "auth" / "oidc" / "callback")
      .in(query[Option[String]]("code"))
      .in(query[Option[String]]("state"))
      .in(cookie[Option[String]](OidcSsoService.STATE_COOKIE))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(statusCode)
      .out(header[String]("Location"))
      .out(setCookie(SessionTokenStore.CookieName))
      .out(setCookie(OidcSsoService.STATE_COOKIE))

  val oidcLogout: PublicEndpoint[
    (Option[String], Option[String]),
    Unit,
    (sttp.model.StatusCode, String, sttp.model.headers.CookieValueWithMeta),
    Any
  ] =
    endpoint.get
      .in("api" / "auth" / "oidc" / "logout")
      .in(cookie[Option[String]](SessionTokenStore.CookieName))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(statusCode)
      .out(header[String]("Location"))
      .out(setCookie(SessionTokenStore.CookieName))

  // Browser SQL-token flow on the manager port: log in via the data-plane OIDC provider and render
  // the access token for pasting into a JDBC `token` property. start = 302 to the IdP (+ state
  // cookie); callback = an HTML page showing the token (+ clears the state cookie). Both are
  // guard-exempt (see ManagerServer.apiKeyGuard). The state cookie is `qod_sqltoken_state`.
  val sqlTokenStart: PublicEndpoint[
    Option[String],
    Unit,
    (sttp.model.StatusCode, String, sttp.model.headers.CookieValueWithMeta),
    Any
  ] =
    endpoint.get
      .in("api" / "auth" / "sql-token" / "start")
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(statusCode)
      .out(header[String]("Location"))
      .out(setCookie("qod_sqltoken_state"))

  val sqlTokenCallback: PublicEndpoint[
    (Option[String], Option[String], Option[String], Option[String], Option[String]),
    Unit,
    (String, sttp.model.headers.CookieValueWithMeta),
    Any
  ] =
    endpoint.get
      .in("api" / "auth" / "sql-token" / "callback")
      .in(query[Option[String]]("code"))
      .in(query[Option[String]]("state"))
      .in(query[Option[String]]("error"))
      .in(cookie[Option[String]]("qod_sqltoken_state"))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(htmlBodyUtf8)
      .out(setCookie("qod_sqltoken_state"))
