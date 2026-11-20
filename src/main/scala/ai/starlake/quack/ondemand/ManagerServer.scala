package ai.starlake.quack.ondemand

import ai.starlake.quack.{FleetConfig, FlightConfig, ManagerConfig}
import ai.starlake.quack.edge.{BoundedBody, ConnectionBudget}
import ai.starlake.quack.ondemand.auth.ReadOnlyCeilingRoutes
import ai.starlake.quack.ondemand.api._
import ai.starlake.quack.ondemand.state.PatStore
import ai.starlake.quack.ondemand.telemetry.{
  AuditActions,
  AuditRateLimiter,
  AuditRecorder,
  NoopTelemetryStore
}
import cats.data.{Kleisli, OptionT}
import cats.effect.{IO, Resource}
import cats.implicits._
import com.comcast.ip4s.{Host, Port}
import com.typesafe.scalalogging.LazyLogging
import org.http4s.{HttpRoutes, Method, Request, Response, StaticFile, Status, Uri}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Location
import org.http4s.server.{staticcontent, Router}
import org.http4s.server.staticcontent.FileService
import org.typelevel.ci.CIString
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import fs2.io.file.{Path => FsPath}

final class ManagerServer(
    cfg: ManagerConfig,
    edgeCfg: FlightConfig,
    pools: PoolHandlers,
    nodes: NodeHandlers,
    tenants: TenantHandlers,
    tenantDbs: TenantDbHandlers,
    health: HealthHandler,
    auth: AuthHandlers,
    sessions: SessionTokenStore,
    authEnabled: Boolean,
    statementHistory: StatementHistoryHandlers,
    catalog: Option[CatalogHandlers],
    tags: Option[TagHandlers],
    maintenance: Option[MaintenanceHandlers],
    preview: Option[CatalogPreviewHandlers],
    catalogHistory: Option[CatalogHistoryHandlers],
    undrop: Option[CatalogUndropHandlers],
    restore: Option[CatalogRestoreHandlers],
    metricsEndpoint: ai.starlake.quack.observability.metrics.MetricsEndpoint,
    users: UserHandlers,
    roles: RoleHandlers,
    groups: GroupHandlers,
    memberships: MembershipHandlers,
    poolPermissions: PoolPermissionHandlers,
    serverConfig: ConfigHandlers,
    manifest: ManifestHandlers,
    federatedSources: Option[FederatedSourceHandlers] = None,
    columnPolicies: RoleColumnPolicyHandlers,
    rowPolicies: RoleRowPolicyHandlers,
    activeStmts: ActiveStatementHandlers,
    audit: AuditRecorder = AuditRecorder.noop,
    // The superuser-only security audit (GET /api/security/audit). Required: Main and the test
    // harness pass it by name. Internal: its endpoint is not in EndpointModules.all.
    securityAudit: SecurityAuditHandlers,
    auditLimiter: AuditRateLimiter = new AuditRateLimiter(),
    auditHandlers: AuditHandlers = new AuditHandlers(NoopTelemetryStore),
    history: HistoryHandlers = new HistoryHandlers(NoopTelemetryStore),
    usage: UsageHandlers = new UsageHandlers(NoopTelemetryStore),
    // Required (no inert default on purpose): a forgotten wiring must break the
    // build, not silently answer every regular user an empty profile.
    profile: ProfileHandlers,
    moduleEndpoints: List[ServerEndpoint[Any, IO]] = Nil,
    modulePublicPrefixes: Set[String] = Set.empty,
    moduleStaticMounts: List[ai.starlake.quack.spi.StaticMount] = Nil,
    // Public pre-session password recovery. None (tests / callers that don't wire
    // Postgres) leaves both routes unmounted; the paths still bypass the api-key
    // guard so a wired handler is reachable without a session.
    passwordReset: Option[PasswordResetHandlers] = None,
    // Self-service personal access tokens. None (tests / callers that don't wire
    // Postgres) leaves the three /api/auth/pat routes unmounted; Main always wires
    // it since the store lives in the same control-plane database.
    pat: Option[PatHandlers] = None,
    // Branches (Epic 1). None (tests / branching disabled) leaves the routes unmounted.
    branches: Option[BranchHandlers] = None,
    // Fleet heartbeat (qod fleet join) + server admin. None leaves the routes unmounted; Main always wires
    // it (outside fleet mode the handler answers 400 fleet_disabled).
    fleet: Option[FleetHandlers] = None,
    // PAT admission on /api: a PAT presented as the bearer credential (X-API-Key
    // header) is accepted wherever its owner's session JWT would be. None (tests /
    // callers without Postgres) keeps the guard session-only.
    patAuth: Option[ai.starlake.quack.ondemand.auth.PatAuthenticator] = None,
    // SCIM 2.0 provisioning surface (/api/scim/v2/{tenant}). None leaves the routes
    // unmounted; Main always wires it. SCIM clients authenticate with Authorization:
    // Bearer, which the guard accepts for /api/scim/ paths only.
    scim: Option[ScimHandlers] = None,
    // Canonicalizes a URL-path tenant (id OR display name) to its id before the
    // perimeter scope check: SCIM base URLs are routinely configured with the
    // display name, and manageableTenants holds ids. None = unknown, compare raw.
    canonicalTenantIdOf: String => Option[String] = _ => None,
    // The MCP endpoint (POST /mcp), pre-built by Main when quack-on-demand.mcp.enabled.
    // Mounted OUTSIDE apiKeyGuard on purpose: /mcp does its own bearer auth (a PAT,
    // never sessions), and the guard's path filter ignores non-/api paths anyway:
    // mounting it here keeps that invariant explicit.
    mcpRoutes: Option[HttpRoutes[IO]] = None,
    /** The REST data edge (`RestEdgeRoutes`), mounted before every other route: it claims exactly
      * the requests under `/data/`, which then never reach the API guard, MCP or the UI.
      */
    dataEdgeRoutes: Option[HttpRoutes[IO]] = None,
    // The native Quack front door's listener config, surfaced to the UI's connection card.
    quackCfg: Option[ai.starlake.quack.QuackNativeConfig] = None,
    // Read-only views over an external Iceberg REST catalog (history, preview, diff, files).
    // None (no federation store) leaves the six /iceberg routes unmounted. Last, not next to
    // `preview`: Main passes it by name (`icebergCatalog = ...`), so its position doesn't matter.
    icebergCatalog: Option[IcebergCatalogHandlers] = None,
    // A tenant's authorized locations (tenant/location/*). None leaves the routes unmounted; Main
    // always wires it. Session-gated per request via TenantScopeCheck inside the handler.
    tenantLocations: Option[TenantLocationHandlers] = None,
    // Users' own S3 credentials (profile/s3/*, self-scoped, on the profile allowlist; user/s3/*,
    // TenantScopeCheck inside the handler). None leaves the routes unmounted; Main always wires it.
    s3Credentials: Option[UserS3CredentialHandlers] = None
) extends LazyLogging:

  // The bearer-credential lookups, composed session-first: the JWT verify is a
  // cheap in-memory operation, and PatAuthenticator self-rejects any token
  // without the qod_pat_ prefix before touching the store, so the composition
  // costs a session caller nothing and a PAT caller one prefix check.
  //
  // `scopeOfToken` is what every handler gate receives. It is total: no credential, or one that
  // stopped resolving between the guard and the handler, is `SessionScope.NoAccess` (see
  // SessionScope.resolver). The guard itself does not use it; it resolves once through
  // `sessionOfToken`.
  private val scopeOfToken: ai.starlake.quack.ondemand.auth.SessionScope.Resolver =
    ai.starlake.quack.ondemand.auth.SessionScope.resolver(t =>
      sessions.scopeOf(t).orElse(patAuth.flatMap(_.scopeOf(t)))
    )
  private val sessionOfToken: String => Option[SessionTokenStore.Session] =
    t => sessions.get(t).orElse(patAuth.flatMap(_.sessionOf(t)))

  extension [I, E, O](e: sttp.tapir.PublicEndpoint[I, E, O, Any])
    /** Binds `e` to a handler of the common shape `handler(inputs...)(scopeOf)`: the endpoint's
      * inputs in order, then the scope resolver every handler gate receives, `scopeOfToken`.
      */
    private def scoped(
        handler: I => ai.starlake.quack.ondemand.auth.SessionScope.Resolver => IO[Either[E, O]]
    ): ServerEndpoint[Any, IO] =
      e.serverLogic(i => handler(i)(scopeOfToken))

    /** [[scoped]] for a synchronous handler (JDBC under Hikari): runs it on `IO.blocking`. */
    private def scopedBlocking(
        handler: I => ai.starlake.quack.ondemand.auth.SessionScope.Resolver => Either[E, O]
    ): ServerEndpoint[Any, IO] =
      e.serverLogic(i => IO.blocking(handler(i)(scopeOfToken)))

  /** Best-effort host of an OIDC issuer URL, for a cosmetic provider label in the client config. */
  private def issuerHost(issuerUrl: String): String =
    try Option(java.net.URI.create(issuerUrl.trim).getHost).getOrElse("")
    catch case _: Exception => ""

  /** A public path a module declared. It takes a body limit of its own,
    * `restLimits.maxModulePublicBodyBytes`: a provider's webhook does not fit the core public
    * routes' limit, and no core route lives there.
    */
  private def isModulePublic(path: String): Boolean =
    modulePublicPrefixes.exists(p => path == p || path.startsWith(p + "/"))

  /** Path is unauthenticated - the UI needs these before login. */
  private def isPublicApi(path: String): Boolean =
    ManagerServer.PublicApiPaths.contains(path) ||
      // Starlake SSO redeem: the ticket is the credential (public server-to-server call).
      // Gated on the integration flag, not just on the route being unmounted when off: with
      // SL_ENABLED=false an anonymous POST here must fall through to the same 401 (with its
      // audit row) that an unrecognized path got before this feature existed, not silently
      // become "public" ahead of the route ever being wired.
      (cfg.auth.management.slIntegrationOn && path == "/api/auth/sso/redeem") ||
      // Fleet heartbeat (qod fleet join): the handler validates X-Fleet-Token itself. Gated on the
      // runtime so the path stays 401 at the guard everywhere else.
      (FleetConfig.isFleet(cfg.runtimeType) && path == "/api/fleet/heartbeat") ||
      isModulePublic(path)

  /** Paths a NON-ADMIN session may reach: the self-service profile surface. Everything else on
    * `/api` but [[isBranchApi]] answers `403 admin_required` for such sessions, so that no other
    * handler needs to know regular users exist.
    *
    * Exact equality, never a prefix test: SPI modules mount their own path prefixes under `/api`
    * (and core keeps adding siblings under `/api/auth`), and a prefix match would silently hand
    * every one of them to regular users.
    */
  private def isProfileApi(path: String): Boolean =
    ManagerServer.ProfileApiPaths.contains(path) ||
      // Starlake SSO ticket mint: any valid session may hand its own grant to
      // Starlake, admin or not -- the minted grant mirrors the session's own
      // admin-ness, it grants nothing new. Gated on the integration flag: with
      // SL_ENABLED=false a non-admin session must still get 403 admin_required here
      // exactly as before this feature existed, not a silent profile-allowlist grant
      // ahead of the route ever being wired.
      (cfg.auth.management.slIntegrationOn && path == "/api/auth/sso/ticket")

  /** The branch routes a NON-ADMIN session may reach, and the only paths whose tenant the guard
    * leaves to the handler: any principal who may connect may branch, in its own tenant, which
    * `BranchHandlers`' one gate decides. Everything else about a branch stays admin-only, merge
    * first of all.
    *
    * Method and whole segments, as the router reads them, an explicit list, never a prefix: a route
    * added under `/api/branch/` later stays admin-only until someone decides otherwise.
    */
  private def isBranchApi(
      method: Method,
      segments: Vector[String],
      endsWithSlash: Boolean
  ): Boolean =
    !endsWithSlash && ManagerServer.BranchApiRoutes.exists((m, template) =>
      m == method && template.length == segments.length &&
        template
          .lazyZip(segments)
          .forall((t, s) => if ManagerServer.isPlaceholder(t) then s.nonEmpty else t == s)
    )

  private val limits = cfg.restLimits

  /** The largest body a connection reads before any credential is checked: a core public route's,
    * or, when a module has public routes, the larger of that and the module's.
    */
  private val unauthenticatedBodyBytes: Long =
    if modulePublicPrefixes.nonEmpty then
      math.max(limits.maxPublicBodyBytes, limits.maxModulePublicBodyBytes)
    else limits.maxPublicBodyBytes

  private val bodyDeadline = FiniteDuration(limits.bodyTimeoutSec, SECONDS)

  /** How long a refusal waits for the small body it reads and discards. */
  private val refusalDrainDeadline = FiniteDuration(math.min(limits.bodyTimeoutSec, 5L), SECONDS)

  private def bodyRefusal(status: Status, code: String, message: String): Response[IO] =
    Response[IO](status)
      .withEntity(s"""{"error":"$code","message":"$message"}""")
      .withContentType(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))
      .putHeaders(org.http4s.headers.Connection.close)

  /** `routes` on `req`, its body read first within `maxBytes` and the deadline. Past either the
    * answer is 413 or 408 and the rest stays unread. Every request goes through the read, whatever
    * its headers say: a request with no body reads as empty at once, and the headers must not
    * decide (see [[BoundedBody.read]]).
    */
  private def withBoundedBody(req: Request[IO], maxBytes: Long)(
      routes: HttpRoutes[IO]
  ): OptionT[IO, Response[IO]] =
    OptionT.liftF(BoundedBody.read(req, maxBytes, bodyDeadline)).flatMap {
      case BoundedBody.Outcome.Read(bytes) => routes(BoundedBody.replay(req, bytes))
      case BoundedBody.Outcome.TooLarge    =>
        OptionT.pure[IO](
          bodyRefusal(
            Status.PayloadTooLarge,
            "payload_too_large",
            s"request body exceeds $maxBytes bytes"
          )
        )
      case BoundedBody.Outcome.TooDeep =>
        OptionT.pure[IO](
          bodyRefusal(
            Status.BadRequest,
            "json_too_deep",
            s"request body nests deeper than ${BoundedBody.MaxNesting} levels"
          )
        )
      case BoundedBody.Outcome.TimedOut =>
        OptionT.pure[IO](
          bodyRefusal(
            Status.RequestTimeout,
            "request_timeout",
            "request body did not arrive in time"
          )
        )
    }

  /** The largest request body a refusal reads and discards before answering. */
  private val RefusalDrainBytes: Long = 1L << 20

  /** `resp`, after reading the rest of `req`'s body. Ember answers a request whose body the app
    * left partly unread as keep-alive and then closes the connection, so a client that reuses it
    * for its next request finds it closed (the JDK client does not retry a POST: "header parser
    * received no bytes"). Only a body announced at most [[RefusalDrainBytes]] long, with no
    * `Expect: 100-continue` (whose client waits for a go-ahead before sending it), is read. Any
    * other body (chunked, longer, awaiting a go-ahead) is left unread, and the answer then says
    * `Connection: close`, so a pooling client opens a new connection instead of reusing the one
    * Ember closes (Phase C ledger T5-R4). A request with no body (no length, not chunked) needs
    * neither. The read gets [[refusalDrainDeadline]]: past it, or on a read failure, the answer
    * says `Connection: close` too.
    *
    * Only one `Content-Length` http4s can read counts as announced, as Ember frames a body by the
    * last such header and by `toLong`: `Content-Length: 1` then a huge one would be drained past
    * the limit, and `+100` would read as no body.
    */
  private def refuse(req: Request[IO], resp: Response[IO]): OptionT[IO, Response[IO]] =
    val chunked = BoundedBody.asksChunked(req)
    val lengths = req.headers.get(CIString("Content-Length"))
    val drain   =
      !chunked && lengths.exists(_.length == 1) && req.contentLength
        .exists(_ <= RefusalDrainBytes) && req.headers.get(CIString("Expect")).isEmpty
    val bodyless = !chunked && lengths.isEmpty
    val closing  = resp.putHeaders(org.http4s.headers.Connection.close)
    OptionT.liftF(
      if drain then
        req.body.compile.drain.as(resp).timeout(refusalDrainDeadline).handleError(_ => closing)
      else if bodyless then IO.pure(resp)
      else IO.pure(closing)
    )

  /** The session behind `token`. A session JWT needs only an HMAC, so it stays on the compute
    * thread; a PAT costs database lookups, so it does not.
    */
  private def resolveCredential(
      token: Option[String]
  ): IO[Option[SessionTokenStore.Session]] =
    token match
      case Some(t) if t.startsWith(PatStore.TokenPrefix) => IO.blocking(sessionOfToken(t))
      case Some(t)                                       => IO(sessionOfToken(t))
      case None                                          => IO.pure(None)

  /** Writes a denial's audit row, at most one per `key` and interval. The write blocks on the
    * database, so it runs off the compute threads; a refused one costs no thread hop.
    */
  private def auditDenial(key: String)(write: => Unit): OptionT[IO, Unit] =
    OptionT.liftF(if auditLimiter.allow(key) then IO.blocking(write) else IO.unit)

  /** Gate on the api namespace. Every non-public `/api/...` request must carry a credential: a
    * session token or a live personal access token, admitted with exactly its owner's scope (admin
    * or profile-only). There is no other credential and no anonymous access: a keyless dev workflow
    * logs in through `/api/auth/login`.
    *
    * Sessions come in two flavors since the regular-user profile feature: admin sessions (JWT
    * `role=admin`), which reach the whole namespace, and non-admin sessions minted by a
    * tenant-scoped login for a `role=user` principal, which are demoted here to the
    * [[isProfileApi]] and [[isBranchApi]] allowlists and get `403 admin_required` everywhere else.
    * The 403 (rather than the anonymous 401) is what tells a client its session is valid but
    * under-privileged.
    *
    * Always-open paths: `/api/auth/login`, `/api/auth/change-password` (the current password is the
    * credential, and a user blocked by must_change_password has no session to present),
    * `/api/config/client`, `/health`, `/ready`, and everything outside `/api/...` (incl.
    * `/ui/...`).
    *
    * A path holding a `.` or `..` segment, decoded, never reaches these routes, with or without a
    * credential: it falls through to the routes after them (`/mcp`, the UI, module mounts, each
    * with its own dot-segment handling) and else answers 404. Ember reads a target such as
    * `//data/../api/tenant/list` as the authority `data` and the path `/../api/tenant/list`, which
    * is outside `/api` here; no route resolves dot segments today, and this keeps the guard's
    * reading of a path and the routes' from ever differing on one (#239).
    *
    * Request bodies are read here, whole, before a route sees one ([[withBoundedBody]]), within
    * `restLimits.bodyTimeoutSec`. A public route takes no chunked framing, as no credential has
    * been checked: a core one reads at most `restLimits.maxPublicBodyBytes`, a module's at most
    * `restLimits.maxModulePublicBodyBytes`, and an admitted caller's at most
    * `restLimits.maxBodyBytes`. A body nesting too deep is refused there too (see
    * [[BoundedBody.MaxNesting]]). No route outside `/api` reads a body except `/mcp`, which applies
    * the limit itself, so nothing is read here for such a path.
    *
    * What blocks on the database leaves the compute threads: a PAT is resolved on `IO.blocking`
    * ([[resolveCredential]]), and so are the two denial audit rows the guard writes. A session JWT
    * needs only an HMAC and stays where it is.
    */
  private def apiKeyGuard(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli { req =>
      // Router parity: tapir segments the path as
      // renderString.dropWhile(_ == '/').split("/").map(decode), i.e. every
      // leading slash is dropped and each segment percent-decoded. The guard
      // MUST see the same shape or an encoded/slash-prefixed spelling of an
      // /api path routes while skipping the guard (proven: ////api/tenant/list
      // and /%61pi/tenant/list both bypassed earlier derivations).
      val segments = req.uri.path.segments.map(_.decoded()).dropWhile(_.isEmpty)
      val path     = {
        val raw = segments.mkString("/", "/", "")
        if req.uri.path.endsWithSlash then raw + "/" else raw
      }
      val branchApi = isBranchApi(req.method, segments, req.uri.path.endsWithSlash)
      if ManagerServer.holdsDotSegment(segments) then OptionT.none[IO, Response[IO]]
      else if !path.startsWith("/api/") && path != "/api" then routes(req)
      else if isPublicApi(path) then
        // No credential has been checked: see BoundedBody.asksChunked.
        if BoundedBody.asksChunked(req) then
          OptionT.pure[IO](
            bodyRefusal(
              Status.LengthRequired,
              "length_required",
              "this route takes a Content-Length, not chunked framing"
            )
          )
        else
          val maxBytes =
            if isModulePublic(path) then limits.maxModulePublicBodyBytes
            else limits.maxPublicBodyBytes
          withBoundedBody(req, maxBytes)(routes)
      else
        // Admit on X-API-Key header OR on the qod_session cookie. The cookie
        // is the browser path (HttpOnly so JS can't read it; SameSite=Lax so
        // it doesn't leak cross-origin); the header is the CLI path (a session
        // or a personal access token). A request can present either.
        // SCIM clients (Okta, Entra) can only send Authorization: Bearer, so for the
        // SCIM prefix the bearer value is admitted exactly like an X-API-Key header
        // (a session or a PAT). Scoped to /api/scim/ on purpose: nothing else should
        // gain a third credential transport.
        val bearerToken =
          if path.startsWith("/api/scim/") then
            req.headers
              .get(CIString("Authorization"))
              .map(_.head.value)
              .flatMap(ScimEndpoints.stripBearer)
          else None
        val headerToken =
          req.headers.get(CIString("X-API-Key")).map(_.head.value).orElse(bearerToken)
        val cookieToken = req.cookies.find(_.name == SessionTokenStore.CookieName).map(_.content)
        val provided    = headerToken.orElse(cookieToken)
        // Resolved ONCE: the admin decision, the non-admin demotion below and the
        // per-request tenant scope check further down all read this one lookup. Separate
        // lookups race a session expiring between them: admitted as admin by the first,
        // then an absent scope skipped the tenant check. A PAT's synthetic session carries
        // role "admin" exactly when PatAuthenticator.isAdmin holds.
        OptionT.liftF(resolveCredential(provided)).flatMap { resolved =>
          val sessionAdmin    = resolved.exists(_.profile.role.equalsIgnoreCase("admin"))
          val tokenScope      = resolved.map(_.scope)
          val nonAdminSession = resolved.isDefined && !sessionAdmin

          val admitted = sessionAdmin || (nonAdminSession && (isProfileApi(path) || branchApi))
          // A session of a disabled tenant's user (every tenant it holds disabled) is refused
          // everything but signing out, until a superuser enables the tenant. A PAT of one already
          // resolves to nothing (PatAuthenticator).
          val disabledTenant: Option[String] =
            resolved.filterNot(_.scope.superuser).flatMap { s =>
              val own = s.scope.manageableTenants ++ s.profile.tenant
              Option.when(own.nonEmpty && own.forall(tenants.tenantDisabled))(own.min)
            }
          // Strip control characters -- jsonb rejects NUL (0x00); cap length so
          // detail stays bounded. An unsanitized `%00` in the path would make
          // the insert throw and the attacker would erase their own trail.
          // Shared by both denial arms below.
          val safePath = path.filter(c => c >= ' ' && c != 0x7f).take(200)
          if disabledTenant.isDefined && path != "/api/auth/logout" then
            val username = resolved.map(_.profile.username).getOrElse("unknown")
            auditDenial("disabled:" + username)(
              audit.restAs(
                username,
                "tenant",
                "auth",
                AuditActions.AuthTenantDisabled,
                "denied",
                tenant = disabledTenant,
                detail = Map("path" -> safePath)
              )
            )
              .flatMap(_ =>
                refuse(
                  req,
                  Response[IO](Status.Forbidden)
                    .withEntity(
                      """{"error":"tenant_disabled","message":"this tenant is disabled"}"""
                    )
                    .withContentType(
                      org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)
                    )
                )
              )
          else if !admitted then
            if nonAdminSession then
              // A real, valid session without an admin grant. Answer with the
              // same stable code the login gate uses so clients treat both alike
              // -- and not with the anonymous 401, which would send a browser
              // back to the login screen it just came from.
              //
              // Audited under the caller's REAL identity (recoverable from the
              // session), so a low-privilege insider sweeping admin endpoints
              // leaves a trail.
              val caller   = resolved.map(_.profile)
              val username = caller.map(_.username).getOrElse("unknown")
              // Rate-limited per principal, not per host: the recorder writes
              // synchronously on the request path, so one authenticated session
              // could otherwise insert an audit row per request at line rate.
              // Collapsing a flood to one row per interval is deliberate -- the
              // first row is the signal; the rest are the attacker's volume.
              // The write blocks on the database, so it runs off the compute threads.
              auditDenial("session:" + username)(
                audit.restAs(
                  username,
                  "tenant",
                  "auth",
                  AuditActions.AuthAdminRequired,
                  "denied",
                  tenant = caller.flatMap(_.tenant),
                  detail = Map("path" -> safePath)
                )
              )
                .flatMap(_ =>
                  refuse(
                    req,
                    Response[IO](Status.Forbidden)
                      .withEntity(
                        """{"error":"admin_required","message":"this endpoint requires an admin session"}"""
                      )
                      .withContentType(
                        org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)
                      )
                  )
                )
            else
              val source = req.remote.map(_.host.toString).getOrElse("unknown")
              auditDenial(source)(
                audit.restAs(
                  "anonymous",
                  "system",
                  "auth",
                  AuditActions.AuthApiKeyFailure,
                  "denied",
                  detail = Map("path" -> safePath, "source" -> source)
                )
              )
                .flatMap(_ => refuse(req, Response[IO](Status.Unauthorized)))
          else if resolved.exists(_.readOnly) && !ReadOnlyCeilingRoutes.admits(req.method, segments)
          then
            val username = resolved.map(_.profile.username).getOrElse("unknown")
            auditDenial("readonly:" + username)(
              audit.restAs(
                username,
                "tenant",
                "auth",
                AuditActions.AuthTokenReadOnly,
                "denied",
                tenant = resolved.flatMap(_.profile.tenant),
                detail = Map("path" -> safePath)
              )
            )
              .flatMap(_ =>
                refuse(
                  req,
                  Response[IO](Status.Forbidden)
                    .withEntity(
                      """{"error":"token_read_only","message":"this token is read-only"}"""
                    )
                    .withContentType(
                      org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)
                    )
                )
              )
          else
            // Per-request tenant scope check on the admitted credential's scope.
            // Body-tenant endpoints do their own check via TenantScopeCheck.reject, and the branch
            // routes through their one gate: a plain member reaches their own tenant there, which
            // holds no admin grant for this check to find.
            val queryTenant = req.uri.query.params.get("tenant")
            val pathTenant  =
              if branchApi then None
              else
                TenantScopeGuard
                  .extractTenant(path, queryTenant)
                  .map(t => canonicalTenantIdOf(t).getOrElse(t))
            (tokenScope, pathTenant) match
              case (Some(scope), Some(t))
                  if !scope.superuser && !scope.manageableTenants.contains(t) =>
                refuse(
                  req,
                  Response[IO](Status.Forbidden)
                    .withEntity(
                      s"""{"error":"tenant_forbidden","message":"session has no admin grant on tenant '$t'"}"""
                    )
                    .withContentType(
                      org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)
                    )
                )
              case _ =>
                val answer = withBoundedBody(req, limits.maxBodyBytes)(routes)
                if tokenScope.exists(_.superuser) then answer
                else
                  answer.semiflatMap(
                    ManagerServer.withServerTextWithheld(pools.textForTenant, safePath)
                  )
        }
    }

  def serve: Resource[IO, org.http4s.server.Server] =
    val interpreter = Http4sServerInterpreter[IO]()

    val catalogEndpoints: List[ServerEndpoint[Any, IO]] = catalog.toList.flatMap { h =>
      // Same gating as ACL: DuckLake catalog reads only make sense with a
      // Postgres metastore. Session-gated per request via TenantScopeCheck
      // inside the handlers (Spec 00 closed the former ungated drift); the
      // asOf / asOfTag resolution also lives in the handler, behind the gate.
      // JDBC calls go on `IO.blocking` since Hikari semantics are synchronous.
      List[ServerEndpoint[Any, IO]](
        CatalogEndpoints.listSchemasEndpoint.scopedBlocking(h.listSchemas(_, _, _)),
        CatalogEndpoints.listTablesEndpoint.scopedBlocking(h.listTables(_, _, _, _)),
        CatalogEndpoints.getTableEndpoint.serverLogic {
          case (tenant, tenantDb, schema, table, asOf, asOfTag, asOfTsRaw, token) =>
            QueryParams.instantAs(asOfTsRaw, "asOfTs", "invalid_selector") match
              case Left(e)       => IO.pure(Left(e))
              case Right(asOfTs) =>
                IO.blocking(
                  h.getTable(tenant, tenantDb, schema, table, asOf, asOfTag, asOfTs, token)(
                    scopeOfToken
                  )
                )
        },
        CatalogEndpoints.listSnapshotsEndpoint.scopedBlocking(h.listSnapshots(_, _, _, _, _, _))
      )
    }

    // Snapshot tags (EPIC P2 / Spec 06). Session-gated per request via
    // TenantScopeCheck inside the handlers, same shape as the browser GETs
    // above (both surfaces have been gated since Spec 00).
    val tagEndpoints: List[ServerEndpoint[Any, IO]] = tags.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        TagEndpoints.listTagsEndpoint.scoped(h.list(_, _, _)),
        TagEndpoints.createTagEndpoint.scoped(h.create(_, _)),
        TagEndpoints.deleteTagEndpoint.scoped(h.delete(_, _)),
        TagEndpoints.protectTagEndpoint.scoped(h.protect(_, _))
      )
    }

    // Managed maintenance (EPIC Spec 09). Session-gated per request via
    // TenantScopeCheck inside the handlers, same shape as tagEndpoints.
    val maintenanceEndpoints: List[ServerEndpoint[Any, IO]] = maintenance.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        MaintenanceEndpoints.upsertPolicyEndpoint.scoped(h.upsertPolicy(_, _)),
        MaintenanceEndpoints.deletePolicyEndpoint.scoped(h.deletePolicy(_, _)),
        MaintenanceEndpoints.listPoliciesEndpoint.scoped(h.listPolicies(_, _, _)),
        MaintenanceEndpoints.listRunsEndpoint.scoped(h.listRuns(_, _, _, _, _)),
        MaintenanceEndpoints.triggerRunEndpoint.scoped(h.triggerRun(_, _))
      )
    }

    // Time-travel preview + schema-diff (Spec 00). Session-gated per request via
    // TenantScopeCheck inside the handler, same shape as tagEndpoints /
    // maintenanceEndpoints.
    val timeTravelEndpoints: List[ServerEndpoint[Any, IO]] = preview.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        TimeTravelEndpoints.previewEndpoint.serverLogic {
          case (tenant, tenantDb, schema, table, asOf, asOfTag, asOfTsRaw, limit, token) =>
            QueryParams.instantAs(asOfTsRaw, "asOfTs", "invalid_selector") match
              case Left(e)       => IO.pure(Left(e))
              case Right(asOfTs) =>
                h.preview(tenant, tenantDb, schema, table, asOf, asOfTag, asOfTs, limit, token)(
                  scopeOfToken
                )
        },
        TimeTravelEndpoints.schemaDiffEndpoint.serverLogic {
          case (tenant, tenantDb, schema, table, from, to, token) =>
            h.schemaDiff(
              tenant,
              tenantDb,
              schema,
              table,
              from,
              to,
              token,
              CatalogPreviewHandlers.NoneHidden
            )(scopeOfToken)
        },
        TimeTravelEndpoints.dataDiffEndpoint.serverLogic {
          case (tenant, tenantDb, schema, table, from, to, limit, cursor, changeType, token) =>
            h.dataDiff(
              tenant,
              tenantDb,
              schema,
              table,
              from,
              to,
              limit,
              cursor,
              changeType,
              token,
              CatalogPreviewHandlers.NoneHidden
            )(scopeOfToken)
        }
      )
    }

    // Iceberg catalog views (history, preview, diff, files). Admin-only via TenantDbGate inside
    // the handler; asOfTs is parsed here, like the DuckLake preview above.
    val icebergCatalogEndpoints: List[ServerEndpoint[Any, IO]] = icebergCatalog.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        IcebergCatalogEndpoints.schemasEndpoint.scoped(h.schemas(_, _, _, _)),
        IcebergCatalogEndpoints.tablesEndpoint.scoped(h.tables(_, _, _, _, _)),
        IcebergCatalogEndpoints.detailEndpoint.scoped(h.detail(_, _, _, _, _, _)),
        IcebergCatalogEndpoints.historyEndpoint.scoped(h.history(_, _, _, _, _, _, _, _, _)),
        IcebergCatalogEndpoints.previewEndpoint.serverLogic {
          case (tenant, tenantDb, alias, schema, table, asOf, asOfTag, asOfTsRaw, limit, token) =>
            QueryParams.instantAs(asOfTsRaw, "asOfTs", "invalid_selector") match
              case Left(e)       => IO.pure(Left(e))
              case Right(asOfTs) =>
                h.preview(
                  tenant,
                  tenantDb,
                  alias,
                  schema,
                  table,
                  asOf,
                  asOfTag,
                  asOfTs,
                  limit,
                  token
                )(scopeOfToken)
        },
        IcebergCatalogEndpoints.dataDiffEndpoint.scoped(h.dataDiff(_, _, _, _, _, _, _, _, _, _))
      )
    }

    // Undrop (Spec 03). Session-gated per request via TenantScopeCheck inside the handler.
    val undropEndpoints: List[ServerEndpoint[Any, IO]] = undrop.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        UndropEndpoints.recoverableEndpoint.scoped(h.recoverable(_, _, _, _)),
        UndropEndpoints.undropEndpoint.scoped(h.undrop(_, _)),
        ProfileEndpoints.recoverable.serverLogic(h.recoverableMine(_, _, _)),
        ProfileEndpoints.undrop.serverLogic(h.undropMine(_, _))
      )
    }

    // Restore (Spec 04). Session-gated per request via TenantScopeCheck inside the handler.
    val restoreEndpoints: List[ServerEndpoint[Any, IO]] = restore.toList.map { h =>
      RestoreEndpoints.restoreEndpoint.scoped(h.restore(_, _))
    }

    // Fleet heartbeat (qod fleet join): public at the guard (fleet runtime only), X-Fleet-Token checked
    // inside the handler. The server admin endpoints go through the normal guard and are
    // superuser-gated per request inside the handler.
    val fleetEndpoints: List[ServerEndpoint[Any, IO]] = fleet.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        FleetEndpoints.heartbeat.serverLogic { case (req, token, forwardedFor, remote) =>
          h.heartbeat(req, token, remote.map(_.getAddress), forwardedFor)
        },
        FleetEndpoints.listServers.scoped(h.listServers(_)),
        FleetEndpoints.drainServer.scoped(h.drain(_, _)),
        FleetEndpoints.undrainServer.scoped(h.undrain(_, _)),
        FleetEndpoints.removeServer.scoped(h.remove(_, _)),
        FleetEndpoints.approveServer.scoped(h.approve(_, _))
      )
    }

    val tenantLocationEndpoints: List[ServerEndpoint[Any, IO]] =
      tenantLocations.toList.flatMap { h =>
        List[ServerEndpoint[Any, IO]](
          TenantEndpoints.listTenantLocations.scoped(h.list(_, _)),
          TenantEndpoints.addTenantLocation.scoped(h.add(_, _)),
          TenantEndpoints.removeTenantLocation.scoped(h.remove(_, _))
        )
      }

    val s3CredentialEndpoints: List[ServerEndpoint[Any, IO]] =
      s3Credentials.toList.flatMap { h =>
        List[ServerEndpoint[Any, IO]](
          S3CredentialEndpoints.listMine.serverLogic { case (_, token) => h.listMine(token) },
          S3CredentialEndpoints.addMine.serverLogic(h.addMine(_, _)),
          S3CredentialEndpoints.removeMine.serverLogic(h.removeMine(_, _)),
          S3CredentialEndpoints.listForUser.scoped(h.listForUser(_, _)),
          S3CredentialEndpoints.removeForUser.scoped(h.removeForUser(_, _))
        )
      }

    // Branches (Epic 1). Session-gated per request via TenantScopeCheck inside the handler.
    val branchEndpoints: List[ServerEndpoint[Any, IO]] = branches.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        BranchEndpoints.createEndpoint.scoped(h.create(_, _)),
        BranchEndpoints.listEndpoint.scoped(h.list(_, _, _, _)),
        BranchEndpoints.getEndpoint.scoped(h.get(_, _, _, _)),
        BranchEndpoints.changesEndpoint.scoped(h.changes(_, _, _, _, _)),
        BranchEndpoints.diffEndpoint.scoped(h.diff(_, _, _, _, _, _, _, _, _)),
        BranchEndpoints.schemaDiffEndpoint.scoped(h.schemaDiff(_, _, _, _, _, _)),
        BranchEndpoints.proposeEndpoint.scoped(h.propose(_, _)),
        BranchEndpoints.mergeEndpoint.scoped(h.merge(_, _)),
        BranchEndpoints.discardEndpoint.scoped(h.discard(_, _))
      )
    }

    // Per-table history timeline (EPIC Spec 01). Session-gated per request via
    // TenantScopeCheck inside the handler, same shape as the browser GETs above.
    val catalogHistoryEndpoints: List[ServerEndpoint[Any, IO]] = catalogHistory.toList.map { h =>
      CatalogEndpoints.tableHistoryEndpoint.serverLogic {
        case (
              tenant,
              tenantDb,
              schema,
              table,
              limit,
              before,
              fromRaw,
              toRaw,
              operation,
              author,
              token
            ) =>
          val bounds =
            for
              from <- QueryParams.instantAs(fromRaw, "from", "invalid_filter")
              to   <- QueryParams.instantAs(toRaw, "to", "invalid_filter")
            yield (from, to)
          bounds match
            case Left(e)           => IO.pure(Left(e))
            case Right((from, to)) =>
              IO.blocking(
                h.history(
                  tenant,
                  tenantDb,
                  schema,
                  table,
                  limit,
                  before,
                  from,
                  to,
                  operation,
                  author,
                  token
                )(scopeOfToken)
              )
      }
    }

    val authEndpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      AuthEndpoints.login.serverLogic(auth.login(_, _)),
      AuthEndpoints.changePassword.serverLogic(auth.changePassword),
      AuthEndpoints.logout.serverLogic(auth.logout(_, _, _)),
      AuthEndpoints.whoami.serverLogic(token => auth.whoami(token, None)),
      AuthEndpoints.authMode.serverLogic(auth.authMode),
      // Self-scoped: identity comes from the token, never from a request param.
      ProfileEndpoints.usage.serverLogic(profile.usage(_, _)),
      ProfileEndpoints.statements.serverLogic(profile.statements(_, _)),
      ProfileEndpoints.pools.serverLogic(profile.pools),
      NodeEndpoints.statementHistory.scoped(statementHistory.recent(_, _)),
      TelemetryEndpoints.auditList.serverLogic {
        case (family, tenant, actor, action, q, from, to, limit, before, noTenant, after, token) =>
          auditHandlers.list(
            family,
            tenant,
            actor,
            action,
            q,
            from,
            to,
            limit,
            before,
            noTenant,
            token,
            after
          )(scopeOfToken)
      },
      TelemetryEndpoints.auditActions.serverLogic(auditHandlers.actions),
      TelemetryEndpoints.historyTrends.scoped(history.trends(_, _, _, _, _, _)),
      TelemetryEndpoints.historyStatements.serverLogic {
        case (from, to, tenant, pool, user, status, q, limit, before, after, token) =>
          history.statements(from, to, tenant, pool, user, status, q, limit, before, token, after)(
            scopeOfToken
          )
      },
      TelemetryEndpoints.usage.scoped(usage.usage(_, _, _, _, _, _)),
      AuthEndpoints.oidcStart.serverLogic(auth.oidcStart(_, _, _)),
      AuthEndpoints.oidcCallback.serverLogicSuccess { case (code, state, stateCookie, proto) =>
        auth.oidcCallback(code, state, stateCookie, proto)
      },
      AuthEndpoints.oidcLogout.serverLogicSuccess { case (sessionCookie, proto) =>
        auth.oidcLogout(sessionCookie, proto)
      },
      AuthEndpoints.sqlTokenStart.serverLogicSuccess(proto => auth.sqlTokenStart(proto)),
      AuthEndpoints.sqlTokenCallback.serverLogicSuccess {
        case (code, state, error, cookie, proto) =>
          auth.sqlTokenCallback(code, state, error, cookie, proto)
      },
      AuthEndpoints.driverStart.serverLogic(auth.driverStart(_, _, _, _, _))
    )

    // Starlake SSO handoff. Mounted only when the integration is effectively on (SL_ENABLED=true
    // AND SL_URL non-empty) -- see ManagementAuthConfig.slIntegrationOn. With the integration off
    // (the default), these routes are absent entirely: not 404-from-router, unrouted.
    val ssoEndpoints: List[ServerEndpoint[Any, IO]] =
      if cfg.auth.management.slIntegrationOn then
        List[ServerEndpoint[Any, IO]](
          AuthEndpoints.ssoTicket.serverLogic(auth.ssoTicket),
          AuthEndpoints.ssoRedeem.serverLogic(auth.ssoRedeem)
        )
      else Nil

    // Mounted only when a handler is wired (Main always wires one). A session reaches all
    // of its user's tokens; a PAT only the subtree it roots (it may mint a child of
    // itself, never touch a sibling, its parent or itself): see PatHandlers.
    val patEndpoints: List[ServerEndpoint[Any, IO]] = pat.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        PatEndpoints.create.serverLogic(h.create(_, _)),
        PatEndpoints.list.serverLogic(h.list),
        PatEndpoints.revoke.serverLogic(h.revoke(_, _)),
        PatEndpoints.delete.serverLogic(h.delete(_, _)),
        PatEndpoints.tools.serverLogic(h.tools)
      )
    }

    // Mounted only when a handler is wired; the paths are guard-exempt regardless
    // (see isPublicApi), so a wired handler is reachable pre-session.
    val passwordResetEndpoints: List[ServerEndpoint[Any, IO]] = passwordReset.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        AuthEndpoints.forgotPassword.serverLogic(h.forgotPassword),
        AuthEndpoints.resetPassword.serverLogic(h.resetPassword)
      )
    }

    val scimEndpoints: List[ServerEndpoint[Any, IO]] = scim.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        ScimEndpoints.listUsers.scoped(h.listUsers(_, _, _, _, _)),
        ScimEndpoints.getUser.scoped(h.getUser(_, _, _)),
        ScimEndpoints.createUser.scoped(h.createUser(_, _, _)),
        ScimEndpoints.replaceUser.scoped(h.replaceUser(_, _, _, _)),
        ScimEndpoints.patchUser.scoped(h.patchUser(_, _, _, _)),
        ScimEndpoints.deleteUser.scoped(h.deleteUser(_, _, _)),
        ScimEndpoints.listGroups.scoped(h.listGroups(_, _, _, _, _)),
        ScimEndpoints.getGroup.scoped(h.getGroup(_, _, _)),
        ScimEndpoints.createGroup.scoped(h.createGroup(_, _, _)),
        ScimEndpoints.replaceGroup.scoped(h.replaceGroup(_, _, _, _)),
        ScimEndpoints.patchGroup.scoped(h.patchGroup(_, _, _, _)),
        ScimEndpoints.deleteGroup.scoped(h.deleteGroup(_, _, _)),
        ScimEndpoints.serviceProviderConfig.scoped(h.serviceProviderConfig(_, _)),
        ScimEndpoints.resourceTypes.scoped(h.resourceTypes(_, _)),
        ScimEndpoints.schemas.scoped(h.schemas(_, _))
      )
    }

    val rbacEndpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      RbacEndpoints.createUser.scoped(users.createUser(_, _)),
      RbacEndpoints.updateUser.scoped(users.updateUser(_, _)),
      RbacEndpoints.deleteUser.scoped(users.deleteUser(_, _)),
      RbacEndpoints.listUsers.scoped(users.listUsers(_, _)),
      RbacEndpoints.effectivePermissions.scoped(users.effective(_, _)),
      RbacEndpoints.createRole.scoped(roles.createRole(_, _)),
      RbacEndpoints.deleteRole.scoped(roles.deleteRole(_, _)),
      RbacEndpoints.listRoles.scoped(roles.listRoles(_, _)),
      RbacEndpoints.grantRolePermission.scoped(roles.grantPermission(_, _)),
      RbacEndpoints.revokeRolePermission.scoped(roles.revokePermission(_, _)),
      RbacEndpoints.listRolePermissions.scoped(roles.listPermissions(_, _)),
      RbacEndpoints.createGroup.scoped(groups.createGroup(_, _)),
      RbacEndpoints.deleteGroup.scoped(groups.deleteGroup(_, _)),
      RbacEndpoints.listGroups.scoped(groups.listGroups(_, _)),
      RbacEndpoints.addUserRoleMembership.scoped(memberships.addUserRole(_, _)),
      RbacEndpoints.removeUserRoleMembership.scoped(memberships.removeUserRole(_, _)),
      RbacEndpoints.addUserGroupMembership.scoped(memberships.addUserGroup(_, _)),
      RbacEndpoints.removeUserGroupMembership.scoped(memberships.removeUserGroup(_, _)),
      RbacEndpoints.addGroupRoleMembership.scoped(memberships.addGroupRole(_, _)),
      RbacEndpoints.removeGroupRoleMembership.scoped(memberships.removeGroupRole(_, _)),
      RbacEndpoints.listGroupRoleMembership.scoped(memberships.listGroupRoles(_, _)),
      RbacEndpoints.grantPoolPermission.scoped(poolPermissions.grant(_, _)),
      RbacEndpoints.revokePoolPermission.scoped(poolPermissions.revoke(_, _)),
      RbacEndpoints.listPoolPermissions.scoped(poolPermissions.list(_, _, _, _)),
      RbacEndpoints.createColumnPolicy.scoped(columnPolicies.create(_, _)),
      RbacEndpoints.updateColumnPolicy.scoped(columnPolicies.update(_, _)),
      RbacEndpoints.deleteColumnPolicy.scoped(columnPolicies.delete(_, _)),
      RbacEndpoints.listColumnPolicies.scoped(columnPolicies.list(_, _)),
      RbacEndpoints.createRowPolicy.scoped(rowPolicies.create(_, _)),
      RbacEndpoints.updateRowPolicy.scoped(rowPolicies.update(_, _)),
      RbacEndpoints.deleteRowPolicy.scoped(rowPolicies.delete(_, _)),
      RbacEndpoints.listRowPolicies.scoped(rowPolicies.list(_, _))
    )

    val metricsEndpoints: List[ServerEndpoint[Any, IO]] = metricsEndpoint.serverEndpoints

    val federatedSourceEndpoints: List[ServerEndpoint[Any, IO]] =
      federatedSources.toList.flatMap { h =>
        List[ServerEndpoint[Any, IO]](
          FederatedSourceEndpoints.createFederatedSource.serverLogic(h.createSource(_, _, _, _)),
          FederatedSourceEndpoints.listFederatedSources.serverLogic(h.listSources(_, _)),
          FederatedSourceEndpoints.getFederatedSource.serverLogic(h.getSource(_, _, _)),
          FederatedSourceEndpoints.deleteFederatedSource.serverLogic(h.deleteSource(_, _, _, _)),
          FederatedSourceEndpoints.listFederatedSecrets.serverLogic(h.listSecrets(_, _, _)),
          FederatedSourceEndpoints.upsertFederatedSecret.serverLogic(h.upsertSecret(_, _, _, _, _)),
          FederatedSourceEndpoints.deleteFederatedSecret.serverLogic(h.deleteSecret(_, _, _, _, _))
        )
      }

    val endpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      PoolEndpoints.createPool.scoped(pools.createPool(_, _)),
      PoolEndpoints.scalePool.scoped(pools.scalePool(_, _)),
      PoolEndpoints.stopPool.scoped(pools.stopPool(_, _)),
      PoolEndpoints.deletePool.scoped(pools.deletePool(_, _)),
      PoolEndpoints.suspendPool.scoped(pools.suspendPool(_, _)),
      PoolEndpoints.resumePool.scoped(pools.resumePool(_, _)),
      PoolEndpoints.listPools.scoped(pools.listPools(_)),
      PoolEndpoints.poolStatus.scoped(pools.poolStatus(_, _, _, _)),
      PoolEndpoints.setPoolDisabled.scoped(pools.setPoolDisabled(_, _)),
      PoolEndpoints.setPoolInitSql.scoped(pools.setInitSql(_, _)),
      PoolEndpoints.setPoolResources.scoped(pools.setResources(_, _)),
      PoolEndpoints.setPoolTemplate.scoped(pools.setPodTemplate(_, _)),
      PoolEndpoints.setPoolAutoscale.scoped(pools.setPoolAutoscale(_, _)),
      NodeEndpoints.setMaxConcurrent.scoped(nodes.setMaxConcurrent(_, _)),
      NodeEndpoints.quarantineNode.scoped(nodes.quarantineNode(_, _)),
      NodeEndpoints.unquarantineNode.scoped(nodes.unquarantineNode(_, _)),
      NodeEndpoints.restartNode.scoped(nodes.restartNode(_, _)),
      TenantEndpoints.createTenant.scoped(tenants.createTenant(_, _)),
      TenantEndpoints.listTenants.scoped(tenants.listTenants(_)),
      TenantEndpoints.deleteTenant.scoped(tenants.deleteTenant(_, _)),
      TenantEndpoints.setTenantDisabled.scoped(tenants.setTenantDisabled(_, _)),
      TenantEndpoints.setTenantAuth.scoped(tenants.setTenantAuth(_, _)),
      TenantEndpoints.setTenantAcl.scoped(tenants.setTenantAcl(_, _)),
      TenantEndpoints.opaTest.scoped(tenants.opaTest(_, _)),
      TenantEndpoints.createTenantDb.scoped(tenantDbs.createTenantDb(_, _)),
      TenantEndpoints.listTenantDbs.scoped(tenantDbs.listTenantDbs(_, _)),
      TenantEndpoints.deleteTenantDb.scoped(tenantDbs.deleteTenantDb(_, _)),
      TenantEndpoints.updateTenantDb.scoped(tenantDbs.update(_, _)),
      TenantEndpoints.metastoreDefaults.scoped(tenantDbs.metastoreDefaults(_)),
      Endpoints.health.serverLogic(_ => health.health),
      Endpoints.ready.serverLogic(_ => health.ready),
      Endpoints.clientConfig.serverLogic(_ =>
        IO.pure(
          Right(
            ClientConfigResponse(
              flightSqlHost = edgeCfg.host,
              flightSqlPort = edgeCfg.port,
              flightSqlTls = edgeCfg.tlsEnabled,
              quackEnabled = quackCfg.exists(_.enabled),
              quackHost = quackCfg.map(_.host).getOrElse(""),
              quackPort = quackCfg.filter(_.enabled).map(_.port).getOrElse(0),
              quackTls = quackCfg.exists(_.tlsEnabled),
              authEnabled = authEnabled,
              placementSupported = pools.supportsPlacement,
              fleetEnabled = pools.fleetEnabled,
              identitySource =
                if cfg.auth.management.identitySource.trim.equalsIgnoreCase("oidc") then "oidc"
                else "db",
              ssoProviderName =
                if cfg.auth.management.identitySource.trim.equalsIgnoreCase("oidc") then
                  issuerHost(cfg.auth.management.oidc.issuerUrl)
                else "",
              telemetryEnabled = serverConfig.telemetryEnabled,
              starlakeUrl = Option.when(cfg.auth.management.slIntegrationOn)(
                cfg.auth.management.slUrl
              )
            )
          )
        )
      ),
      Endpoints.serverConfig.scoped(serverConfig.list(_)),
      SecurityAuditEndpoints.audit.scoped(securityAudit.run(_)),
      Endpoints.manifestExport.serverLogic { case (mode, tenant, token) =>
        manifest.exportYaml(token, mode, tenant)(scopeOfToken)
      },
      Endpoints.manifestImport.serverLogic { case (body, mode, tenant, token) =>
        manifest.importYaml(body, token, mode, tenant)(scopeOfToken)
      },
      NodeEndpoints.activeStatements.scoped(activeStmts.list(_)),
      NodeEndpoints.killStatement.scoped(activeStmts.kill(_, _))
    ) ++ authEndpoints ++ ssoEndpoints ++ patEndpoints ++ passwordResetEndpoints ++ catalogEndpoints ++ tagEndpoints ++ maintenanceEndpoints ++ timeTravelEndpoints ++ icebergCatalogEndpoints ++ catalogHistoryEndpoints ++ undropEndpoints ++ restoreEndpoints ++ branchEndpoints ++ fleetEndpoints ++ tenantLocationEndpoints ++ s3CredentialEndpoints ++ metricsEndpoints ++ rbacEndpoints ++ scimEndpoints ++ federatedSourceEndpoints ++ moduleEndpoints

    val collisions = ai.starlake.quack.ondemand.module.RouteCollisions.check(endpoints)
    if collisions.nonEmpty then
      throw new IllegalStateException(
        s"duplicate REST routes between core and modules: ${collisions.mkString("; ")}"
      )

    val apiRoutes: HttpRoutes[IO] = interpreter.toRoutes(endpoints)

    // Static UI from src/main/resources/ui, mounted at /ui/*.
    // resourceServiceBuilder("/ui") sets the classpath prefix; Router("/ui" -> ...)
    // sets the URL prefix.
    val uiAssets = staticcontent.resourceServiceBuilder[IO]("/ui").toRoutes
    // SPA fallback: any /ui/* path that doesn't match a real asset (incl. the
    // bare /ui/ directory request, and React Router routes like /ui/create) gets
    // index.html so the SPA can take over routing.
    val spaFallback: HttpRoutes[IO] = HttpRoutes.of[IO] { req =>
      StaticFile
        .fromResource[IO]("/ui/index.html", Some(req))
        .getOrElseF(IO.pure(Response[IO](Status.NotFound)))
    }
    val uiRoutes = Router("/ui" -> (uiAssets <+> spaFallback))

    // Module static mounts (SPI): same shape as the /ui mount above, one per
    // registered ai.starlake.quack.spi.StaticMount. A mount with a diskDir
    // that exists on disk is served from the filesystem (live-updatable
    // content); classpath resources are the fallback. spaFallback=false
    // mounts (marketing pages) 404 for real instead of swallowing unmatched
    // paths into index.html.
    // CDN contract for marketing (non-SPA) mounts: hashed Vite assets are
    // immutable, pages revalidate within minutes, 404s are never cached.
    // See the enrollment-website design spec (qod-hosted docs) D4.
    val hashedAsset = raw".*/assets/[^/]+-[A-Za-z0-9_]{8,}\.[a-z0-9]+".r
    def withMarketingCacheHeaders(routes: HttpRoutes[IO]): HttpRoutes[IO] =
      cats.data.Kleisli { (req: Request[IO]) =>
        routes(req).map { resp =>
          val cc =
            if resp.status == Status.NotFound then "no-store"
            else if hashedAsset.matches(req.uri.path.renderString) then
              "public, max-age=31536000, immutable"
            else "public, max-age=300, stale-while-revalidate=600"
          resp.putHeaders(
            org.http4s.Header.Raw(org.typelevel.ci.CIString("Cache-Control"), cc)
          )
        }
      }
    val moduleStatic: HttpRoutes[IO] =
      moduleStaticMounts.sortBy(m => -m.urlPrefix.length).foldLeft(HttpRoutes.empty[IO]) {
        (acc, m) =>
          val diskRoot: Option[String] =
            m.diskDir.filter(d => java.nio.file.Files.isDirectory(java.nio.file.Paths.get(d)))
          val assets = diskRoot match
            case Some(root) =>
              staticcontent.fileService[IO](FileService.Config(root))
            case None =>
              staticcontent.resourceServiceBuilder[IO](m.classpathDir).toRoutes
          def page(name: String, req: Request[IO]) = diskRoot match
            case Some(root) => StaticFile.fromPath[IO](FsPath(root) / name, Some(req))
            case None       => StaticFile.fromResource[IO](s"${m.classpathDir}/$name", Some(req))
          // Decoded path segments of the request's Router-translated remaining
          // path (`pathInfo`, see the note on directoryIndexCandidate below).
          // Decoding first means a percent-encoded dot-segment (`%2e%2e`) is
          // caught the same as a literal `..`.
          def decodedSegments(req: Request[IO]): Vector[String] =
            req.pathInfo.segments.map(_.decoded())
          // `.` or `..` anywhere in the decoded path is a directory-traversal
          // attempt: in disk mode `page()` resolves through `StaticFile.fromPath`,
          // which (unlike classpath mode's `getResource`) has no containment
          // guard, so `FsPath(root) / "../../outside/index.html"` would resolve
          // outside the mount root. Refuse it before a candidate is ever built.
          def hasDotSegment(segments: Vector[String]): Boolean =
            segments.exists(s => s == "." || s == "..")
          // Directory-index candidate for a request's Router-translated remaining
          // path (`pathInfo`, NOT `uri.path` - Router leaves the original request
          // path untouched and only adjusts the caret that `pathInfo` reads, so a
          // check against `uri.path` would only ever fire by accident for a "/"
          // mount). Built from the DECODED segments (not `renderString`) so a
          // percent-encoded separator resolves the same as a literal one, e.g.
          // "/our%20team/" -> "our team/index.html". "/" -> "index.html",
          // "/pricing/" -> "pricing/index.html", "/no/such/page" ->
          // "no/such/page/index.html" (a lookup that's expected to miss).
          // Mirrors FileService's directory auto-resolution, which
          // ResourceService does not provide for classpath-backed mounts.
          def directoryIndexCandidate(segments: Vector[String]): String =
            if segments.isEmpty then "index.html" else s"${segments.mkString("/")}/index.html"
          def notFoundPage(req: Request[IO]): IO[Response[IO]] =
            page("404.html", req)
              .map(_.withStatus(Status.NotFound))
              .getOrElseF(IO.pure(Response[IO](Status.NotFound)))
          // A request is "extensionless" when its decoded segments are empty
          // (the mount root), or the last segment carries no `.`: `/pricing`,
          // `/pricing/`, `/enroll/complete/`. Real static assets always carry a
          // file extension, so extensionless requests never legitimately belong
          // to `assets`.
          def isExtensionless(segments: Vector[String]): Boolean =
            segments.isEmpty || !segments.last.contains(".")
          val mounted =
            if m.spaFallback then
              val fallback: HttpRoutes[IO] = HttpRoutes.of[IO] { req =>
                page("index.html", req).getOrElseF(IO.pure(Response[IO](Status.NotFound)))
              }
              Router(m.urlPrefix -> (assets <+> fallback))
            else
              // Extensionless requests are routed to the page/404 lookup
              // BEFORE `assets`, so `assets` never sees them. This closes a
              // jar-packaging bug: `sbt assembly` packs zero-byte directory
              // entries (e.g. `www/pricing/`) into the classpath jar, and
              // http4s' resourceServiceBuilder matches a bare-directory GET
              // against that entry and serves it as a 200 with an empty body,
              // before the directory-index fallback below ever gets a chance
              // - `assets <+> fallback` short-circuits on the first match.
              val pages: HttpRoutes[IO] = HttpRoutes.of[IO] {
                case req if isExtensionless(decodedSegments(req)) =>
                  val segments = decodedSegments(req)
                  if hasDotSegment(segments) then notFoundPage(req)
                  else page(directoryIndexCandidate(segments), req).getOrElseF(notFoundPage(req))
              }
              val notFound: HttpRoutes[IO] = HttpRoutes.of[IO](req => notFoundPage(req))
              Router(m.urlPrefix -> (pages <+> assets <+> notFound))
          acc <+> (if m.spaFallback then mounted else withMarketingCacheHeaders(mounted))
      }

    // Redirect the bare root (`/`) to `/ui/` so visiting the manager host
    // lands on the admin UI instead of a 404. The React SPA itself lives
    // under basename="/ui" (see ui/src/App.tsx).
    val rootRedirect: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case req if req.method == Method.GET && req.uri.path == Uri.Path.Root =>
        IO.pure(
          Response[IO](Status.Found)
            .putHeaders(Location(Uri.unsafeFromString("/ui/")))
        )
    }

    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString(cfg.host).get)
      .withPort(Port.fromInt(cfg.port).get)
      .withMaxConnections(
        ConnectionBudget.slots(
          "The REST port",
          "QOD_REST_MAX_CONNECTIONS",
          limits.maxConnections,
          ConnectionBudget.heldBytes(limits.maxHeaderBytes, unauthenticatedBodyBytes, tls = false)
        )
      )
      .withReceiveBufferSize(BoundedBody.ReadBufferBytes)
      .withMaxHeaderSize(limits.maxHeaderBytes)
      .withErrorHandler(
        BoundedBody.headerLimitHandler(
          bodyRefusal(
            Status.RequestHeaderFieldsTooLarge,
            "headers_too_large",
            s"request line or headers exceed ${limits.maxHeaderBytes} bytes " +
              "(QOD_REST_MAX_HEADER_BYTES)"
          )
        )
      )
      .withHttpApp(
        (dataEdgeRoutes.getOrElse(HttpRoutes.empty[IO]) <+> apiKeyGuard(apiRoutes) <+>
          mcpRoutes.getOrElse(HttpRoutes.empty[IO]) <+> uiRoutes <+> moduleStatic <+>
          rootRedirect).orNotFound
      )
      .build

object ManagerServer extends com.typesafe.scalalogging.LazyLogging:

  /** The core `/api` paths the guard admits with no credential: the UI needs these before login.
    * Exact paths, never prefixes. The config-gated public paths (Starlake SSO redeem, the fleet
    * heartbeat) and a module's public prefixes are decided in `isPublicApi`.
    */
  private[ondemand] val PublicApiPaths: Set[String] = Set(
    "/api/auth/login",
    "/api/auth/change-password",
    "/api/auth/forgot-password",
    "/api/auth/reset-password",
    "/api/config/client",
    "/api/auth/mode",
    "/api/auth/oidc/start",
    "/api/auth/oidc/callback",
    "/api/auth/oidc/logout",
    "/api/auth/sql-token/start",
    "/api/auth/sql-token/callback",
    "/api/auth/driver/start"
  )

  /** The paths a NON-ADMIN session may reach (the self-service profile surface), besides the
    * config-gated Starlake SSO ticket mint decided in `isProfileApi`. Exact paths, never prefixes:
    * SPI modules mount their own prefixes under `/api`, and a prefix match would hand every one of
    * them to regular users.
    */
  private[ondemand] val ProfileApiPaths: Set[String] = Set(
    "/api/auth/whoami",
    "/api/auth/logout",
    "/api/profile/usage",
    "/api/profile/statements",
    "/api/profile/pools",
    // Self-service undrop: the caller's own tenant only (no tenant input), run as them, so the
    // node judges the read of the dropped table and the create.
    "/api/profile/recoverable",
    "/api/profile/undrop",
    // A user's own S3 credentials: the handlers act on the caller's own user row only.
    "/api/profile/s3/list",
    "/api/profile/s3/add",
    "/api/profile/s3/remove",
    // Personal access tokens are self-service for every principal, admin or not: the handlers
    // scope every call to the session's own user id, so there is nothing here a regular user
    // could reach beyond its own tokens.
    "/api/auth/pat/create",
    "/api/auth/pat/list",
    "/api/auth/pat/revoke",
    "/api/auth/pat/delete",
    // The names valid on a token's tools axis: the same static list for every caller.
    "/api/auth/pat/tools"
  )

  /** The branch routes a NON-ADMIN session may reach (see `isBranchApi`), as a method and a path
    * template split into segments. A `{name}` segment matches any non-empty segment; every other
    * segment matches only itself. Whole segments, never a prefix: a route added under
    * `/api/branch/` later stays admin-only until it is listed here.
    */
  private[ondemand] val BranchApiRoutes: List[(Method, Vector[String])] = List(
    Method.POST -> "/api/branch/create",
    Method.POST -> "/api/branch/propose",
    Method.POST -> "/api/branch/discard",
    Method.GET  -> "/api/branch/tenant/{tenant}/database/{database}/branches",
    Method.GET  -> "/api/branch/tenant/{tenant}/database/{database}/branches/{branch}",
    Method.GET  -> "/api/branch/tenant/{tenant}/database/{database}/branches/{branch}/changes",
    Method.GET  -> "/api/branch/tenant/{tenant}/database/{database}/branches/{branch}/diff",
    Method.GET  -> "/api/branch/tenant/{tenant}/database/{database}/branches/{branch}/schema-diff"
  ).map((m, path) => m -> path.stripPrefix("/").split('/').toVector)

  private[ondemand] def isPlaceholder(segment: String): Boolean =
    segment.length > 2 && segment.startsWith("{") && segment.endsWith("}")

  /** Whether a path's decoded segments hold `.` or `..`, which no API route takes (#239). */
  private[ondemand] def holdsDotSegment(segments: Vector[String]): Boolean =
    segments.exists(s => s == "." || s == "..")

  /** `resp` as a caller who is not a superuser reads it. A server failure or a conflict can carry a
    * client library's text on QoD's Postgres server, and `shown` keeps what it does not name. Only
    * those: a refusal of the caller's own request quotes what it sent. The code stays; the text
    * goes to the log.
    */
  private[ondemand] def withServerTextWithheld(shown: String => String, path: String)(
      resp: Response[IO]
  ): IO[Response[IO]] =
    if resp.status.code < 500 && resp.status != Status.Conflict then IO.pure(resp)
    else
      resp.as[String].map { body =>
        val json    = io.circe.parser.parse(body).toOption
        val message = json.flatMap(_.hcursor.get[String]("message").toOption).getOrElse(body)
        val read    = shown(message)
        val answer  =
          if read == message then body
          else
            logger.error(s"$path answered ${resp.status.code}: $message")
            val code = json.flatMap(_.hcursor.get[String]("error").toOption).getOrElse("internal")
            io.circe.Json
              .obj(
                "error"   -> io.circe.Json.fromString(code),
                "message" -> io.circe.Json.fromString(read)
              )
              .noSpaces
        val bytes = answer.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        resp
          .withBodyStream(fs2.Stream.emits(bytes).covary[IO])
          .putHeaders(org.http4s.headers.`Content-Length`.unsafeFromLong(bytes.length.toLong))
      }
