package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.{
  AuthFailure,
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
import ai.starlake.quack.ondemand.state.{UserGrant, UserStore}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import sttp.model.StatusCode
import sttp.model.headers.{Cookie, CookieValueWithMeta}

/** What the chosen login entry point requires of the authenticated principal.
  *
  *   - `System`: the caller did not specify a tenant. Any admin (superuser or tenant-admin) is
  *     accepted; the resulting session reflects exactly the grants the principal holds.
  *   - `SystemStrict`: superuser ONLY. Used by the OIDC bare `/ui/` (system IdP) login. A
  *     non-superuser tenant-admin must sign in through their tenant scope instead.
  *   - `Tenant(id)`: the caller presented a tenant. The principal must be superuser OR an admin of
  *     that specific tenant, or, for DB-mode logins only, any user with a grant on that tenant
  *     (profile-only session).
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
  * grants => `403 not_provisioned`. The admin console is admin-only, so a principal with no admin
  * grant is still refused `403 admin_required` on the system login and on any tenant it does not
  * hold a grant on; logging into a tenant it DOES hold a grant on mints a non-admin, profile-only
  * session instead (`LoginResponse.admin = false`).
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
    audit: AuditRecorder = AuditRecorder.noop,
    /** SPI module event sink. Defaults to noop so callers that don't wire module telemetry (tests,
      * legacy code) are unaffected.
      */
    events: ManagerEventSink = ManagerEventSink.noop,
    /** Backing store for the pre-session change-password endpoint. `None` (tests, callers that
      * don't wire Postgres) makes the endpoint answer 503 auth_disabled.
      */
    changePasswordStore: Option[ai.starlake.quack.ondemand.state.UserStore] = None,
    /** Single-use ticket store backing the Starlake SSO handoff (`ssoTicket` / `ssoRedeem`).
      * Defaults to a fresh in-process store so callers that don't explicitly wire one (tests,
      * legacy code) still get working -- if unreachable -- endpoints.
      */
    ssoTickets: SsoTicketStore = new SsoTicketStore(),
    /** Best-effort Starlake logout callback. Defaults to a no-op so callers that don't wire the
      * integration (tests, legacy code, `slIntegrationOn == false`) fire nothing; Main selects
      * [[HttpStarlakeNotifier]] only when the Starlake SSO integration is on.
      */
    starlakeNotifier: StarlakeNotifier = NoopStarlakeNotifier
) extends LazyLogging:

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

  /** Best-effort Starlake logout callback, started as a detached fiber so QoD's own logout never
    * blocks -- or fails -- on Starlake's availability. `starlakeNotifier` is [[NoopStarlakeNotifier]]
    * (a no-op) unless the Starlake SSO integration is on, so this only ever reaches the network when
    * `slIntegrationOn` is true. Failures are logged at warn and never surfaced to the caller.
    */
  private def dispatchStarlakeLogout(tokenOpt: Option[String]): IO[Unit] =
    tokenOpt match
      case Some(raw) =>
        IO.blocking(starlakeNotifier.notifyLogout(StarlakeNotifier.sha256Hex(raw)))
          .flatMap {
            case Left(err) => IO(logger.warn(s"starlake logout callback failed: $err"))
            case Right(_)  => IO.unit
          }
          .start
          .void
      case None => IO.unit

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
            mintSessionFor(
              result.profile,
              required,
              forwardedProto,
              ManagementAuthMode.Oidc,
              "oidc"
            ) match
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
  ): IO[(StatusCode, String, CookieValueWithMeta)] =
    IO.blocking {
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
    }.flatTap(_ => dispatchStarlakeLogout(sessionCookie))

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
          val code = err match
            case AuthFailure.PasswordChangeRequired => "password_change_required"
            case AuthFailure.AccountLocked          => "account_locked"
            case _                                  => "invalid_credentials"
          Left((StatusCode.Unauthorized, ErrorResponse(code, err.message)))
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
              mintSessionFor(profile, required, forwardedProto, mode, "rest")
  }

  /** Pre-session self-service password change. Anti-enumeration: unknown user, wrong current
    * password, disabled account, and an over-long current password all answer the same 401
    * invalid_credentials. Policy: the new password must be non-empty, at most 71 UTF-8 bytes
    * (bcrypt's limit), and differ from the current one; no complexity rules. Clears
    * `must_change_password` on success (inside [[ai.starlake.quack.ondemand.state.UserStore]]).
    */
  def changePassword(req: ChangePasswordRequest): Out[Unit] = IO.blocking {
    if req.username.isEmpty || req.currentPassword.isEmpty then
      Left((StatusCode.Unauthorized, ErrorResponse("invalid_credentials", "invalid credentials")))
    // BCrypt.withDefaults()'s strict long-password strategy throws
    // IllegalArgumentException above 71 UTF-8 bytes, both on the verify path
    // (currentPassword) and the hash path (newPassword). Nothing catches
    // that below, so an unauthenticated caller could 500 this public route
    // with an over-long value; reject both here instead. currentPassword
    // over the limit can never match a stored hash, so it gets the same
    // 401 invalid_credentials as any other bad-credential shape rather than
    // a distinguishable error.
    else if req.currentPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 71 then
      Left((StatusCode.Unauthorized, ErrorResponse("invalid_credentials", "invalid credentials")))
    else if req.newPassword.isEmpty then
      Left((StatusCode.BadRequest, ErrorResponse("invalid_password", "new password is required")))
    else if req.newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 71 then
      Left(
        (
          StatusCode.BadRequest,
          ErrorResponse("invalid_password", "new password must be at most 71 bytes")
        )
      )
    else if req.newPassword == req.currentPassword then
      Left(
        (
          StatusCode.BadRequest,
          ErrorResponse("invalid_password", "new password must differ from the current password")
        )
      )
    else
      changePasswordStore match
        case None =>
          Left(
            (
              StatusCode.ServiceUnavailable,
              ErrorResponse("auth_disabled", "password change requires the database auth backend")
            )
          )
        case Some(users) =>
          // Same tenant resolution as login: id or display name; unknown values pass
          // through so the store answers invalid_credentials without a tenant oracle.
          val scopeTenant =
            req.tenant.map(_.trim).filter(_.nonEmpty).map(t => resolveTenant(t).getOrElse(t))
          users.changePassword(
            scopeTenant,
            req.username,
            req.currentPassword,
            req.newPassword
          ) match
            case Left(err) =>
              audit.restAs(
                req.username,
                "tenant",
                "auth",
                AuditActions.AuthPasswordChange,
                "denied",
                tenant = scopeTenant,
                detail = Map("username" -> req.username)
              )
              // A locked row is surfaced distinctly (account_locked) so the caller is pointed at
              // the reset flow, mirroring the login path; every other failure collapses to the
              // anti-enumeration invalid_credentials.
              err match
                case UserStore.ChangePasswordError.Locked =>
                  Left(
                    (
                      StatusCode.Unauthorized,
                      ErrorResponse(
                        "account_locked",
                        "account locked - use forgot password to reset your credentials"
                      )
                    )
                  )
                case UserStore.ChangePasswordError.InvalidCredentials =>
                  Left(
                    (
                      StatusCode.Unauthorized,
                      ErrorResponse("invalid_credentials", "invalid credentials")
                    )
                  )
            case Right(()) =>
              audit.restAs(
                req.username,
                "tenant",
                "auth",
                AuditActions.AuthPasswordChange,
                "ok",
                tenant = scopeTenant,
                detail = Map("username" -> req.username)
              )
              Right(())
  }

  /** Shared authorization + session minting for both the password login and the OIDC callback.
    *
    * `required` expresses what the chosen login URL demands:
    *   - `System`: no tenant was specified; any admin (superuser or tenant-admin) is accepted.
    *   - `Tenant(id)`: a specific tenant was requested; the principal must be superuser, admin of
    *     that tenant, or a regular user holding a grant on it (which mints a non-admin,
    *     profile-only session -- `LoginResponse.admin = false`) -- DB-mode logins additionally
    *     admit any user with a grant on that tenant as a profile-only session.
    *
    * The grant computation and the not_provisioned / admin_required gates are shared by both entry
    * points, so they cannot drift.
    *
    * `via` identifies the calling entry point for the [[ManagerEvent.SessionOpened]] emission:
    * `"rest"` from `login`, `"oidc"` from `oidcCallback`.
    */
  private def mintSessionFor(
      profile: AuthenticatedProfile,
      required: RequiredScope,
      forwardedProto: Option[String],
      mode: ManagementAuthMode,
      via: String
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

    val isAdminPrincipal = superuser || manageableTenants.nonEmpty
    // Tenants where this principal exists at all (any role) -- what a
    // regular user may log into for the profile-only view.
    val selfTenants: Set[String] = grants.collect { case UserGrant(Some(t), _) => t }.toSet

    // `System` scope (no tenant in the login form) accepts any admin role --
    // superuser or tenant-admin. This preserves the original `login` behavior
    // where the absence of a tenant did not restrict which admins could proceed.
    // `SystemStrict` (OIDC bare /ui/ login) further restricts to superuser only.
    // `Tenant(t)` scope admits a superuser, an admin of that specific tenant,
    // or a regular user holding a grant on it (profile-only session).
    val scopeOk = required match
      case RequiredScope.System       => isAdminPrincipal
      case RequiredScope.SystemStrict => superuser
      case RequiredScope.Tenant(t)    =>
        superuser || manageableTenants.contains(t) ||
        // Profile-only sessions are a DB-login feature: in Db mode the single
        // grant IS the authenticated (tenant, role) row, so role is qodstate's
        // "user", never an IdP claim. The OIDC callback keeps its admin-only
        // gate -- its profile.role is the IdP claim and must not reach a mint.
        (mode == ManagementAuthMode.Db && selfTenants.contains(t))

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
    else if !scopeOk then
      // Preserve the pre-existing errors byte-for-byte: a non-admin on the
      // system login still gets admin_required; a scope mismatch keeps its
      // existing code/message. Only Tenant(t) logins where the user has a
      // grant on t now fall through to minting.
      if !isAdminPrincipal then
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
      else
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
      // The JWT role claim must reflect AUTHORITY, not the IdP's descriptive
      // claim: an admin principal (superuser or tenant admin) always mints
      // role=admin so isAdmin() and the guard admit them; a non-admin principal
      // whose profile claims admin is demoted to user so a profile-only session
      // never carries role=admin. In Db mode this is a no-op (profile.role
      // already equals the qodstate grant); it only bites the OIDC path, where
      // RoleExtractor defaults to "user" when the IdP emits no known role.
      val mintProfile =
        if isAdminPrincipal then profile.copy(role = "admin")
        else if profile.role.equalsIgnoreCase("admin") then profile.copy(role = "user")
        else profile
      val token = tokens.mintWithScope(mintProfile, sessionScope)
      audit.restAs(
        profile.username,
        realm,
        "auth",
        AuditActions.AuthLogin,
        "ok",
        tenant = profile.tenant,
        detail = Map("authMethod" -> profile.authMethod, "admin" -> isAdminPrincipal.toString)
      )
      // profile.tenant already mirrors the requested AuthScope.tenantId (see
      // DatabaseAuthenticator/OIDC providers): None for a system/superuser scope,
      // Some(id) for a tenant-scoped login. SessionScope itself carries only a
      // superuser flag plus a set of manageable tenants (no single tenant id to
      // read here), so profile.tenant is the reliable source for tenantOrEmpty.
      events.emit(
        ManagerEvent.SessionOpened(profile.tenant.getOrElse(""), profile.username, via)
      )
      val resp = LoginResponse(
        token = token,
        username = profile.username,
        tenant = None,
        superuser = superuser,
        manageableTenants = manageableTenants.toList.sorted,
        admin = isAdminPrincipal
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
    val tok = apiKey.orElse(cookie)
    // revoke() now always does JDBC (persist + NOTIFY the denylist), so this runs
    // on the blocking pool. `oidcLogout` already uses IO.blocking for the same call.
    IO.blocking {
      val (actor, realm) = audit.actorOf(tok)
      tok.foreach(tokens.revoke)
      audit.restAs(actor, realm, "auth", AuditActions.AuthLogout, "ok")
    }.flatTap(_ => dispatchStarlakeLogout(tok))
      .as(Right(clearCookie(forwardedProto)))

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

  /** Mint a single-use Starlake SSO ticket carrying the caller's session grant. Session-authed
    * (token via header or cookie, [[Endpoints.authToken]]); any valid session may call this,
    * admin or not -- the minted grant mirrors whatever admin-ness the QoD session already has
    * ([[SessionTokenStore.isAdmin]]'s exact `role` check), it grants nothing new. `401
    * unauthorized` on no/invalid/expired/revoked session, collapsed to one code (unlike
    * [[whoami]]) since the caller here is Starlake's redirect target, not a UI that needs to
    * distinguish failure reasons.
    */
  def ssoTicket(token: Option[String]): Out[SsoTicketResponse] = IO {
    val t = token.getOrElse("")
    tokens.lookupResult(t) match
      case SessionTokenStore.LookupResult.Ok(session) =>
        val grant = SsoGrant(
          sessionToken = t,
          username = session.profile.username,
          tenant = session.profile.tenant,
          // Same derivation as SessionTokenStore.isAdmin: role, not scope.superuser.
          admin = session.profile.role.equalsIgnoreCase("admin")
        )
        val ticket = ssoTickets.mint(grant)
        // Audited under the acting session's identity, mirroring PAT create. The
        // minted ticket value is a bearer credential (like a raw PAT) and is never
        // logged, so no `target`/`detail` carries it.
        audit.rest(
          token,
          "auth",
          AuditActions.AuthSsoTicketMint,
          "ok",
          tenant = session.profile.tenant
        )
        Right(SsoTicketResponse(ticket))
      case _ =>
        Left((StatusCode.Unauthorized, ErrorResponse("unauthorized", "session required")))
  }

  /** Redeem a Starlake SSO ticket for the QoD session grant it carries. Public: no
    * X-API-Key/cookie input, the ticket itself is the (single-use, 128-bit random, short-TTL)
    * credential. `401 invalid_ticket` on unknown / expired / already-redeemed.
    *
    * No rate limiting here on purpose: this endpoint is public and unauthenticated, so any
    * limiter keyed on caller-supplied input (the ticket string) would itself be an unbounded,
    * attacker-controlled memory-retention vector while providing no real protection -- a 128-bit
    * single-use ticket with a 60s TTL is already infeasible to brute force.
    */
  def ssoRedeem(request: SsoRedeemRequest): Out[SsoRedeemResponse] = IO {
    ssoTickets.redeem(request.ticket) match
      case Some(grant) =>
        // Identity comes from the redeemed grant, not a caller credential -- redeem
        // is public/unauthenticated. realm mirrors mintSessionFor: an empty tenant
        // means the grant was minted from a system-scope session.
        val realm = if grant.tenant.isEmpty then "system" else "tenant"
        audit.restAs(
          grant.username,
          realm,
          "auth",
          AuditActions.AuthSsoTicketRedeem,
          "ok",
          tenant = grant.tenant
        )
        Right(SsoRedeemResponse(grant.sessionToken, grant.username, grant.tenant, grant.admin))
      case None =>
        // No identity is recoverable from an unknown/expired/already-used ticket, so
        // this is audited anonymously -- deliberately unconditional (no rate limit):
        // redeem is intentionally un-rate-limited (see ssoRedeem's doc), so this row
        // is the only detection signal for ticket-guessing. The ticket value itself
        // is never logged.
        audit.restAs(
          "anonymous",
          "system",
          "auth",
          AuditActions.AuthSsoTicketRedeem,
          "denied"
        )
        Left(
          (
            StatusCode.Unauthorized,
            ErrorResponse("invalid_ticket", "ticket unknown, expired, or already used")
          )
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
