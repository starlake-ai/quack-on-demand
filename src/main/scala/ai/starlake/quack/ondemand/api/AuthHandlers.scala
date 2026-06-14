package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.{AuthScope, AuthenticationService}
import ai.starlake.quack.ondemand.auth.{GrantsLookup, ManagementIdentitySource, SessionScope}
import ai.starlake.quack.ondemand.state.UserGrant
import cats.effect.IO
import sttp.model.StatusCode
import sttp.model.headers.{Cookie, CookieValueWithMeta}

/** REST endpoints driving the UI's login lifecycle.
  *
  * Authentication (who you are) goes through `AuthenticationService.authenticateBasic`.
  * Authorization (what you may do) depends on `identitySource`:
  *   - `Db`: the authenticating profile IS the grant. Single-tenant or superuser.
  *   - `Oidc`: the JWT's `role` and any tenant claim are discarded; `grantsForIdentity` is
  *     consulted with the JWT's `preferred_username` (and `email` as fallback).
  *
  * Either path collapses to a [[SessionScope]] carrying `superuser` and `manageableTenants`. Empty
  * grants => `403 not_provisioned`; no admin grant => `403 admin_required` (the UI is admin-only).
  *
  * Transport: on successful login the handler sets a `qod_session` cookie carrying the same JWT
  * returned in `LoginResponse.token`. The browser auto-attaches the cookie on subsequent `/api/...`
  * calls; CLI / static-key callers stay on the X-API-Key header path. Logout always clears the
  * cookie and (process-locally) marks the jti revoked until its natural exp.
  */
final class AuthHandlers(
    authService: AuthenticationService,
    tokens: SessionTokenStore,
    identitySource: ManagementIdentitySource,
    grantsForIdentity: GrantsLookup,
    cookieSecureOverride: Option[Boolean] = None,
    cookiePath: String = "/api"
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  // sttp.model represents SameSite as Cookie.SameSite (sealed trait with
  // Lax / Strict / None case objects). Lax matches all top-level GETs but
  // blocks cross-origin POST/PUT/DELETE -- the right default for an admin
  // REST surface.
  private val sameSiteLax: Option[Cookie.SameSite] = Some(Cookie.SameSite.Lax)

  /** Decide whether the `qod_session` cookie should carry the `Secure` flag for this response. When
    * the operator has set an explicit override (env `QOD_SESSION_COOKIE_SECURE=true|false`) we
    * honor it unconditionally; otherwise we derive from the `X-Forwarded-Proto` header injected by
    * the TLS-terminating ingress, treating "no header" as plaintext HTTP. This lets `run-jar.sh` on
    * `http://localhost:20900` and a helm deploy behind a TLS ingress both work without an env var,
    * while still allowing operators to force-secure when they're behind a proxy that strips
    * `X-Forwarded-Proto`.
    */
  private def deriveSecure(forwardedProto: Option[String]): Boolean =
    cookieSecureOverride.getOrElse(
      forwardedProto.exists(_.equalsIgnoreCase("https"))
    )

  private def sessionCookie(token: String, forwardedProto: Option[String]): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = token,
      maxAge = Some(tokens.maxAgeSeconds),
      path = Some(cookiePath),
      domain = None,
      secure = deriveSecure(forwardedProto),
      httpOnly = true,
      sameSite = sameSiteLax,
      expires = None,
      otherDirectives = Map.empty
    )

  private def clearCookie(forwardedProto: Option[String]): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = "",
      maxAge = Some(0L),
      path = Some(cookiePath),
      domain = None,
      secure = deriveSecure(forwardedProto),
      httpOnly = true,
      sameSite = sameSiteLax,
      expires = None,
      otherDirectives = Map.empty
    )

  def login(
      req: LoginRequest,
      forwardedProto: Option[String] = None
  ): Out[(CookieValueWithMeta, LoginResponse)] = IO.blocking {
    if req.username.isEmpty || req.password.isEmpty then
      Left(
        (
          StatusCode.BadRequest,
          ErrorResponse("invalid_credentials", "username and password are required")
        )
      )
    else if !authService.hasProviders then
      Left(
        (
          StatusCode.ServiceUnavailable,
          ErrorResponse(
            "auth_disabled",
            "no auth backends configured; set auth.database.enabled=true (or another)"
          )
        )
      )
    else
      val scope: AuthScope = req.tenant.map(_.trim).filter(_.nonEmpty) match
        case Some(t) => AuthScope.Tenant(t)
        case None    => AuthScope.System
      authService.authenticateBasic(scope, req.username, req.password) match
        case Left(err) =>
          Left((StatusCode.Unauthorized, ErrorResponse("invalid_credentials", err)))
        case Right(profile) =>
          val grants = identitySource match
            case ManagementIdentitySource.Db =>
              // The DB authenticator already encoded the (tenant, role) the operator
              // provisioned in `qodstate_user`. Collapse it into a one-element grant
              // set so the rest of the pipeline is uniform.
              List(UserGrant(profile.tenant, profile.role))
            case ManagementIdentitySource.Oidc =>
              // Discard JWT role + tenant. The qodstate_user row is authoritative for
              // management-plane authorization.
              grantsForIdentity(profile.username, profile.claims.get("email"))

          val superuser = grants.exists(g => g.tenant.isEmpty && g.role.equalsIgnoreCase("admin"))
          val manageableTenants: Set[String] = grants.collect {
            case UserGrant(Some(t), r) if r.equalsIgnoreCase("admin") => t
          }.toSet

          if grants.isEmpty then
            Left(
              (
                StatusCode.Forbidden,
                ErrorResponse(
                  "not_provisioned",
                  s"user '${profile.username}' authenticated but has no qodstate_user grant"
                )
              )
            )
          else if !superuser && manageableTenants.isEmpty then
            Left(
              (
                StatusCode.Forbidden,
                ErrorResponse(
                  "admin_required",
                  s"user '${profile.username}' has no admin grant; manager UI is admin-only"
                )
              )
            )
          else
            val sessionScope = SessionScope(superuser, manageableTenants)
            val token        = tokens.mintWithScope(profile, sessionScope)
            val resp         = LoginResponse(
              token = token,
              username = profile.username,
              tenant = None,
              superuser = superuser,
              manageableTenants = manageableTenants.toList.sorted
            )
            Right((sessionCookie(token, forwardedProto), resp))
  }

  /** Logout. Accepts the token via X-API-Key header OR qod_session cookie -- the UI uses the cookie
    * (JS can't read HttpOnly cookies); CLI uses the header. The response always emits a
    * clear-cookie so the browser drops its copy.
    */
  def logout(
      apiKey: Option[String],
      cookie: Option[String],
      forwardedProto: Option[String] = None
  ): Out[CookieValueWithMeta] =
    IO.delay {
      apiKey.orElse(cookie).foreach(tokens.revoke)
      Right(clearCookie(forwardedProto))
    }

  /** Whoami. Same input shape as logout: header OR cookie, whichever carries the live JWT.
    *
    * The error body distinguishes WHY the lookup failed so the client (and operators reading the
    * network tab) can tell apart the four very different cases that previously all surfaced as
    * `"expired"`:
    *   - `no_session` -- no header AND no cookie. The UI's mount-time probe hits this on every
    *     fresh load before login; it's not really an error.
    *   - `invalid` -- token is malformed, has no `jti`, or its HMAC signature doesn't verify under
    *     the manager's `QOD_SESSION_JWT_SECRET`. Signature mismatch usually means the secret
    *     rotated (e.g. helm regenerated the Secret on upgrade).
    *   - `expired` -- the JWT's `exp` claim is in the past. Actually expired.
    *   - `revoked` -- the jti is on the in-process denylist (an explicit logout, not yet GC'd).
    */
  def whoami(apiKey: Option[String], cookie: Option[String]): Out[WhoamiResponse] = IO.delay {
    val token = apiKey.orElse(cookie).getOrElse("")
    tokens.lookupResult(token) match
      case SessionTokenStore.LookupResult.Ok(s) =>
        Right(
          WhoamiResponse(
            username = s.profile.username,
            role = s.profile.role,
            tenant = s.profile.tenant,
            superuser = s.scope.superuser,
            manageableTenants = s.scope.manageableTenants.toList.sorted
          )
        )
      case SessionTokenStore.LookupResult.NoSession =>
        Left(
          (StatusCode.Unauthorized, ErrorResponse("no_session", "no session token presented"))
        )
      case SessionTokenStore.LookupResult.Invalid =>
        Left(
          (StatusCode.Unauthorized, ErrorResponse("invalid", "session token is invalid"))
        )
      case SessionTokenStore.LookupResult.Expired =>
        Left(
          (StatusCode.Unauthorized, ErrorResponse("expired", "session token has expired"))
        )
      case SessionTokenStore.LookupResult.Revoked =>
        Left(
          (StatusCode.Unauthorized, ErrorResponse("revoked", "session token has been revoked"))
        )
  }
