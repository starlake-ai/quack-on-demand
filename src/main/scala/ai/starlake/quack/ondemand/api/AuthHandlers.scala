package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.{
  AuthScope,
  AuthenticatedProfile,
  AuthenticationService,
  OidcScope,
  OidcSsoService,
  SqlTokenOidcService
}
import ai.starlake.quack.ondemand.auth.{
  GrantsLookup,
  ManagementAuthMode,
  ManagementAuthModeResolver,
  ManagementIdentitySource,
  SessionScope
}
import ai.starlake.quack.ondemand.state.UserGrant
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode
import sttp.model.headers.{Cookie, CookieValueWithMeta}

/** What the chosen login entry point requires of the authenticated principal.
  *
  *   - `System`: the caller did not specify a tenant. Any admin (superuser or tenant-admin) is
  *     accepted; the resulting session reflects exactly the grants the principal holds.
  *   - `SystemStrict`: superuser ONLY. Used by the OIDC bare `/ui/` (system IdP) login. A
  *     non-superuser tenant-admin must sign in through their tenant scope instead.
  *   - `Tenant(id)`: the caller presented a tenant. The principal must be superuser OR an admin of
  *     that specific tenant.
  */
enum RequiredScope:
  case System
  case SystemStrict
  case Tenant(id: String)

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
    cookiePath: String = "/api",
    authModeResolver: ManagementAuthModeResolver =
      new ManagementAuthModeResolver(_ => None, ManagementAuthMode.Db),
    /** Resolves a login form's tenant -- entered as either the surrogate id (`t-…`) or the
      * human-readable display name -- to the surrogate id stored in `qodstate_user.tenant`, which
      * is what the authenticator's tenant-scoped query matches on. Returns `None` for an unknown
      * value so the caller can pass it through verbatim and let auth fail cleanly. Defaults to a
      * no-op (id-only) for callers that don't wire a registry (e.g. unit tests).
      */
    resolveTenant: String => Option[String] = _ => None,
    /** Optional OIDC SSO service. `None` when SSO is not configured; present when
      * `auth.management.oidc` is wired and `oidcStart`/`oidcCallback`/`oidcLogout` are active.
      */
    oidc: Option[OidcSsoService] = None,
    /** Optional data-plane SQL-token service backing the `/api/auth/sql-token` routes. `None` when
      * no edge OIDC provider is configured; the handlers gate on `.enabled`.
      */
    sqlToken: Option[SqlTokenOidcService] = None,
    /** Audit recorder for auth events (login, logout, revoke). Defaults to noop so callers that
      * don't wire telemetry (tests, legacy code) are unaffected.
      */
    audit: AuditRecorder = AuditRecorder.noop
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

  /** All qod cookies share the same hardened attributes (HttpOnly, SameSite=Lax, per-request Secure
    * derivation from X-Forwarded-Proto); only value, lifetime and path vary. The cookie NAME is
    * bound at the endpoint's setCookie output, so the two clear-variants stay distinct defs purely
    * for call-site readability.
    */
  private def cookie(
      value: String,
      maxAge: Long,
      forwardedProto: Option[String],
      path: String = cookiePath
  ): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = value,
      maxAge = Some(maxAge),
      path = Some(path),
      domain = None,
      secure = deriveSecure(forwardedProto),
      httpOnly = true,
      sameSite = sameSiteLax,
      expires = None,
      otherDirectives = Map.empty
    )

  private def sessionCookie(token: String, forwardedProto: Option[String]): CookieValueWithMeta =
    cookie(token, tokens.maxAgeSeconds, forwardedProto)

  private def clearCookie(forwardedProto: Option[String]): CookieValueWithMeta =
    cookie("", 0L, forwardedProto)

  private def stateCookie(value: String, forwardedProto: Option[String]): CookieValueWithMeta =
    cookie(value, 600L, forwardedProto)

  private def clearStateCookie(forwardedProto: Option[String]): CookieValueWithMeta =
    cookie("", 0L, forwardedProto)

  def oidcStart(
      tenant: Option[String],
      returnTo: Option[String],
      forwardedProto: Option[String] = None
  ): Out[(StatusCode, String, CookieValueWithMeta)] = IO.blocking {
    oidc match
      case None =>
        Left((StatusCode.NotFound, ErrorResponse("auth_mode_disabled", "SSO is not enabled")))
      case Some(svc) =>
        val scope = tenant.map(_.trim).filter(_.nonEmpty) match
          case Some(t) => OidcScope.Tenant(resolveTenant(t).getOrElse(t))
          case None    => OidcScope.System
        val seed = java.util.UUID.randomUUID().toString
        svc.startAuth(scope, returnTo.getOrElse("/ui/"), seed) match
          case Left(err) =>
            Left((StatusCode.BadRequest, ErrorResponse(err.code, "OIDC start failed")))
          case Right(req) =>
            Right(
              (
                StatusCode.Found,
                req.redirectLocation,
                stateCookie(req.stateCookieValue, forwardedProto)
              )
            )
  }

  def oidcCallback(
      code: Option[String],
      state: Option[String],
      stateCookie: Option[String],
      forwardedProto: Option[String]
  ): IO[(StatusCode, String, CookieValueWithMeta, CookieValueWithMeta)] = IO.blocking {
    oidc match
      case None =>
        (
          StatusCode.Found,
          "/ui/?error=auth_mode_disabled",
          clearCookie(forwardedProto),
          clearStateCookie(forwardedProto)
        )
      case Some(svc) =>
        svc.completeAuth(
          code.getOrElse(""),
          state.getOrElse(""),
          stateCookie.getOrElse(""),
          System.currentTimeMillis()
        ) match
          case Left(err) =>
            (
              StatusCode.Found,
              s"/ui/?error=${err.code}",
              clearCookie(forwardedProto),
              clearStateCookie(forwardedProto)
            )
          case Right(result) =>
            val required = result.scope match
              case OidcScope.System    => RequiredScope.SystemStrict
              case OidcScope.Tenant(t) => RequiredScope.Tenant(t)
            mintSessionFor(result.profile, required, forwardedProto, ManagementAuthMode.Oidc) match
              case Left((_, err)) =>
                (
                  StatusCode.Found,
                  s"/ui/?error=${err.error}",
                  clearCookie(forwardedProto),
                  clearStateCookie(forwardedProto)
                )
              case Right((cookie, _)) =>
                (StatusCode.Found, result.returnTo, cookie, clearStateCookie(forwardedProto))
  }

  def oidcLogout(
      sessionCookie: Option[String],
      forwardedProto: Option[String]
  ): IO[(StatusCode, String, CookieValueWithMeta)] = IO.blocking {
    sessionCookie match
      case Some(tok) =>
        val (actor, realm) = audit.actorOf(Some(tok))
        tokens.revoke(tok)
        audit.restAs(actor, realm, "auth", AuditActions.AuthRevoke, "ok")
      case None => ()
    // id_token_hint is not persisted in this iteration; RP-initiated logout still
    // clears the local cookie and (when configured) hits the IdP end-session endpoint.
    // Logout uses the system end-session endpoint; per-tenant end-session is a follow-up.
    val location = oidc.flatMap(_.endSessionUrl(OidcScope.System, None)).getOrElse("/ui/")
    (StatusCode.Found, location, clearCookie(forwardedProto))
  }

  // ---- Browser SQL-token flow (/api/auth/sql-token) ----

  private val sqlTokenStatePath = "/api/auth/sql-token"

  private def sqlTokenStateCookie(
      value: String,
      forwardedProto: Option[String],
      maxAge: Long
  ): CookieValueWithMeta =
    cookie(value, maxAge, forwardedProto, path = sqlTokenStatePath)

  def sqlTokenStart(
      forwardedProto: Option[String]
  ): IO[(StatusCode, String, CookieValueWithMeta)] = IO.blocking {
    sqlToken.filter(_.enabled).map(_.startUrl()) match
      case Some(Right((url, state))) =>
        (StatusCode.Found, url, sqlTokenStateCookie(state, forwardedProto, 600L))
      case _ =>
        (
          StatusCode.Found,
          "/api/auth/sql-token/callback?error=oauth_not_configured",
          sqlTokenStateCookie("", forwardedProto, 0L)
        )
  }

  def sqlTokenCallback(
      code: Option[String],
      state: Option[String],
      error: Option[String],
      stateCookie: Option[String],
      forwardedProto: Option[String]
  ): IO[(String, CookieValueWithMeta)] = IO.blocking {
    val clear = sqlTokenStateCookie("", forwardedProto, 0L)
    val html  =
      if error.exists(_.nonEmpty) then SqlTokenPage.error(error.get)
      else
        val result = for
          svc <- sqlToken.filter(_.enabled).toRight("OAuth is not configured")
          c   <- code.filter(_.nonEmpty).toRight("missing authorization code")
          s   <- state.filter(_.nonEmpty).toRight("missing state")
          ck  <- stateCookie.filter(_.nonEmpty).toRight("missing state cookie")
          tok <- svc.completeAuth(c, s, ck)
        yield tok
        result.fold(SqlTokenPage.error, SqlTokenPage.success)
    (html, clear)
  }

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
        // Accept either the tenant id or its display name; normalize to the id
        // the authenticator's tenant query matches. Unknown -> pass through so
        // auth fails as "user not found" rather than 500-ing.
        case Some(t) => AuthScope.Tenant(resolveTenant(t).getOrElse(t))
        case None    => AuthScope.System
      authService.authenticateBasic(scope, req.username, req.password) match
        case Left(err) =>
          audit.restAs(
            req.username,
            "tenant",
            "auth",
            AuditActions.AuthLoginFailure,
            "denied",
            tenant = req.tenant,
            detail = Map("username" -> req.username)
          )
          Left((StatusCode.Unauthorized, ErrorResponse("invalid_credentials", err)))
        case Right(profile) =>
          val tenantOpt = scope match
            case AuthScope.System    => None
            case AuthScope.Tenant(t) => Some(t)
          val required = scope match
            case AuthScope.System    => RequiredScope.System
            case AuthScope.Tenant(t) => RequiredScope.Tenant(t)
          authModeResolver.modeFor(tenantOpt) match
            case Left(err) =>
              Left((StatusCode.BadRequest, ErrorResponse(err.code, "tenant auth mode unresolved")))
            case Right(mode) =>
              mintSessionFor(profile, required, forwardedProto, mode)
  }

  /** Shared authorization + session minting for both the password login and the OIDC callback.
    *
    * `required` expresses what the chosen login URL demands:
    *   - `System`: no tenant was specified; any admin (superuser or tenant-admin) is accepted.
    *   - `Tenant(id)`: a specific tenant was requested; the principal must be superuser or admin of
    *     that tenant.
    *
    * The grant computation and the not_provisioned / admin_required gates are identical to the
    * original inline `login` logic, so the two entry points cannot drift.
    */
  private def mintSessionFor(
      profile: AuthenticatedProfile,
      required: RequiredScope,
      forwardedProto: Option[String],
      mode: ManagementAuthMode
  ): Either[(StatusCode, ErrorResponse), (CookieValueWithMeta, LoginResponse)] =
    val grants = mode match
      case ManagementAuthMode.Db =>
        // The DB authenticator already encoded the (tenant, role) from qodstate_user.
        List(UserGrant(profile.tenant, profile.role))
      case ManagementAuthMode.Oidc =>
        // Identity from the IdP; qodstate_user is authoritative for role + tenants.
        grantsForIdentity(profile.username, profile.claims.get("email"))

    val superuser = grants.exists(g => g.tenant.isEmpty && g.role.equalsIgnoreCase("admin"))
    val manageableTenants: Set[String] = grants.collect {
      case UserGrant(Some(t), r) if r.equalsIgnoreCase("admin") => t
    }.toSet

    // `System` scope (no tenant in the login form) accepts any admin role --
    // superuser or tenant-admin. This preserves the original `login` behavior
    // where the absence of a tenant did not restrict which admins could proceed.
    // `SystemStrict` (OIDC bare /ui/ login) further restricts to superuser only.
    // `Tenant(t)` scope further requires the caller to be admin of that specific
    // tenant (or a superuser).
    val scopeOk = required match
      case RequiredScope.System       => superuser || manageableTenants.nonEmpty
      case RequiredScope.SystemStrict => superuser
      case RequiredScope.Tenant(t)    => superuser || manageableTenants.contains(t)

    val realm = if superuser then "system" else "tenant"
    if grants.isEmpty then
      audit.restAs(
        profile.username,
        "tenant",
        "auth",
        AuditActions.AuthLoginFailure,
        "denied",
        tenant = profile.tenant,
        detail = Map("reason" -> "not_provisioned")
      )
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
      audit.restAs(
        profile.username,
        "tenant",
        "auth",
        AuditActions.AuthLoginFailure,
        "denied",
        tenant = profile.tenant,
        detail = Map("reason" -> "admin_required")
      )
      Left(
        (
          StatusCode.Forbidden,
          ErrorResponse(
            "admin_required",
            s"user '${profile.username}' has no admin grant; manager UI is admin-only"
          )
        )
      )
    else if !scopeOk then
      audit.restAs(
        profile.username,
        realm,
        "auth",
        AuditActions.AuthLoginFailure,
        "denied",
        tenant = profile.tenant,
        detail = Map("reason" -> "admin_required")
      )
      Left(
        (
          StatusCode.Forbidden,
          ErrorResponse(
            "admin_required",
            s"user '${profile.username}' is not authorized for the requested scope " +
              "(system login requires a superuser; sign in via your tenant instead)"
          )
        )
      )
    else
      val sessionScope = SessionScope(superuser, manageableTenants)
      val token        = tokens.mintWithScope(profile, sessionScope)
      audit.restAs(
        profile.username,
        realm,
        "auth",
        AuditActions.AuthLogin,
        "ok",
        tenant = profile.tenant,
        detail = Map("authMethod" -> profile.authMethod)
      )
      val resp = LoginResponse(
        token = token,
        username = profile.username,
        tenant = None,
        superuser = superuser,
        manageableTenants = manageableTenants.toList.sorted
      )
      Right((sessionCookie(token, forwardedProto), resp))

  /** Logout. Accepts the token via X-API-Key header OR qod_session cookie -- the UI uses the cookie
    * (JS can't read HttpOnly cookies); CLI uses the header. The response always emits a
    * clear-cookie so the browser drops its copy.
    */
  def logout(
      apiKey: Option[String],
      cookie: Option[String],
      forwardedProto: Option[String] = None
  ): Out[CookieValueWithMeta] =
    // revoke() now always does JDBC (persist + NOTIFY the denylist), so this runs
    // on the blocking pool. `oidcLogout` already uses IO.blocking for the same call.
    IO.blocking {
      val tok            = apiKey.orElse(cookie)
      val (actor, realm) = audit.actorOf(tok)
      tok.foreach(tokens.revoke)
      audit.restAs(actor, realm, "auth", AuditActions.AuthLogout, "ok")
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

  /** Resolve the admin-UI login mode for a scope. Unauthenticated: the SPA calls this before login
    * with the tenant from the URL (absent for the system scope) to decide whether to render the
    * password form (`db`) or redirect to SSO (`oidc`). An unknown or misconfigured tenant returns
    * `400` with a stable error code so the UI can surface it instead of silently falling back.
    */
  def authMode(tenant: Option[String]): Out[AuthModeResponse] = IO.blocking {
    authModeResolver.modeFor(tenant.map(_.trim).filter(_.nonEmpty)) match
      case Left(err) =>
        Left((StatusCode.BadRequest, ErrorResponse(err.code, "tenant auth mode unresolved")))
      case Right(ManagementAuthMode.Db) =>
        Right(AuthModeResponse("db", ""))
      case Right(ManagementAuthMode.Oidc) =>
        Right(AuthModeResponse("oidc", ""))
  }

/** Minimal HTML rendered by the browser SQL-token flow (`/api/auth/sql-token/callback`). The
  * success page shows the access token (copyable) and the ready-to-paste DBeaver `token=` form.
  */
object SqlTokenPage:
  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  def success(token: String): String =
    val safe = esc(token)
    s"""<!doctype html><html><head><meta charset="utf-8"><title>Quack on Demand token</title>
       |<style>body{font-family:system-ui,sans-serif;margin:3rem;max-width:760px}
       |textarea{width:100%;height:7rem;font-family:monospace}
       |button{padding:.5rem 1rem;margin-top:.5rem;cursor:pointer}</style></head><body>
       |<h2>Your access token</h2>
       |<p>Paste this into DBeaver's <code>token</code> driver property (Driver Properties tab),
       |then connect. Keep it secret; it expires.</p>
       |<textarea id="t" readonly>$safe</textarea>
       |<button onclick="navigator.clipboard.writeText(document.getElementById('t').value)">Copy token</button>
       |<h3>Or as a JDBC URL parameter</h3>
       |<textarea readonly>token=$safe</textarea>
       |</body></html>""".stripMargin

  def error(message: String): String =
    val safe = esc(message)
    s"""<!doctype html><html><head><meta charset="utf-8"><title>Login failed</title></head>
       |<body style="font-family:system-ui,sans-serif;margin:3rem"><h2>Login failed</h2>
       |<p>$safe</p></body></html>""".stripMargin
