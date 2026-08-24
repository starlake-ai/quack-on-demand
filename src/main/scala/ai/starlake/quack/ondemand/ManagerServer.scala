package ai.starlake.quack.ondemand

import ai.starlake.quack.{FlightConfig, ManagerConfig}
import ai.starlake.quack.ondemand.api._
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
    // PAT admission on /api: a PAT presented as the bearer credential (X-API-Key
    // header) is accepted wherever its owner's session JWT would be. None (tests /
    // callers without Postgres) keeps the guard session-and-static-key only.
    patAuth: Option[ai.starlake.quack.ondemand.auth.PatAuthenticator] = None,
    // The MCP endpoint (POST /mcp), pre-built by Main when quack-on-demand.mcp.enabled.
    // Mounted OUTSIDE apiKeyGuard on purpose: /mcp does its own bearer auth (PAT or
    // static key, never sessions), and the guard's path filter ignores non-/api paths
    // anyway -- mounting it here keeps that invariant explicit.
    mcpRoutes: Option[HttpRoutes[IO]] = None
) extends LazyLogging:

  // The bearer-credential lookups, composed session-first: the JWT verify is a
  // cheap in-memory operation, and PatAuthenticator self-rejects any token
  // without the qod_pat_ prefix before touching the store, so the composition
  // costs a session caller nothing and a PAT caller one prefix check.
  private val scopeOfToken: String => Option[ai.starlake.quack.ondemand.auth.SessionScope] =
    t => sessions.scopeOf(t).orElse(patAuth.flatMap(_.scopeOf(t)))
  private val isAdminToken: String => Boolean =
    t => sessions.isAdmin(t) || patAuth.exists(_.isAdmin(t))
  private val sessionOfToken: String => Option[SessionTokenStore.Session] =
    t => sessions.get(t).orElse(patAuth.flatMap(_.sessionOf(t)))

  /** Constant-time string equality for secret comparison (static API key). `MessageDigest.isEqual`
    * does not short-circuit on the first differing byte, closing the timing side-channel that
    * `String.equals` (via `Option.contains`) opens. Length is not treated as secret.
    */
  private def constantTimeEq(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(
      a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      b.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )

  /** Best-effort host of an OIDC issuer URL, for a cosmetic provider label in the client config. */
  private def issuerHost(issuerUrl: String): String =
    try Option(java.net.URI.create(issuerUrl.trim).getHost).getOrElse("")
    catch case _: Exception => ""

  /** Path is unauthenticated - the UI needs these before login. */
  private def isPublicApi(path: String): Boolean =
    path == "/api/auth/login" || path == "/api/auth/change-password" ||
      path == "/api/auth/forgot-password" || path == "/api/auth/reset-password" ||
      path == "/api/config/client" ||
      path == "/api/auth/mode" ||
      path == "/api/auth/oidc/start" || path == "/api/auth/oidc/callback" ||
      path == "/api/auth/oidc/logout" ||
      path == "/api/auth/sql-token/start" || path == "/api/auth/sql-token/callback" ||
      // Starlake SSO redeem: the ticket is the credential (public server-to-server call).
      // Gated on the integration flag, not just on the route being unmounted when off: with
      // SL_ENABLED=false an anonymous POST here must fall through to the same 401 (with its
      // audit row) that an unrecognized path got before this feature existed, not silently
      // become "public" ahead of the route ever being wired.
      (cfg.auth.management.slIntegrationOn && path == "/api/auth/sso/redeem") ||
      modulePublicPrefixes.exists(p => path == p || path.startsWith(p + "/"))

  /** Paths a NON-ADMIN session may reach: the self-service profile surface only. Everything else on
    * `/api` answers `403 admin_required` for such sessions -- this predicate is the entire
    * authorization story for regular users, so no other handler needs to know they exist.
    *
    * Exact equality, never a prefix test: SPI modules mount their own path prefixes under `/api`
    * (and core keeps adding siblings under `/api/auth`), and a prefix match would silently hand
    * every one of them to regular users.
    */
  private def isProfileApi(path: String): Boolean =
    path == "/api/auth/whoami" || path == "/api/auth/logout" ||
      path == "/api/profile/usage" || path == "/api/profile/statements" ||
      // Personal access tokens are self-service for every principal, admin or
      // not: the handlers scope every call to the session's own user id, so
      // there is nothing here a regular user could reach beyond its own tokens.
      path == "/api/auth/pat/create" || path == "/api/auth/pat/list" ||
      path == "/api/auth/pat/revoke" || path == "/api/auth/pat/delete" ||
      // Starlake SSO ticket mint: any valid session may hand its own grant to
      // Starlake, admin or not -- the minted grant mirrors the session's own
      // admin-ness, it grants nothing new. Gated on the integration flag: with
      // SL_ENABLED=false a non-admin session must still get 403 admin_required here
      // exactly as before this feature existed, not a silent profile-allowlist grant
      // ahead of the route ever being wired.
      (cfg.auth.management.slIntegrationOn && path == "/api/auth/sso/ticket")

  /** Gate on the api namespace. Every non-public `/api/...` request must carry a credential: a
    * session token, a live personal access token (admitted with exactly its owner's scope, admin or
    * profile-only), or -- when `cfg.apiKey` is set -- the static key via `X-API-Key`.
    *
    * An unset (or empty) `cfg.apiKey` only disables the static-key arm; it never opens the
    * namespace. The former open mode is gone: a keyless dev workflow logs in through
    * `/api/auth/login` (or sets `QOD_API_KEY`).
    *
    * Sessions come in two flavors since the regular-user profile feature: admin sessions (JWT
    * `role=admin`), which reach the whole namespace, and non-admin sessions minted by a
    * tenant-scoped login for a `role=user` principal, which are demoted here to the
    * [[isProfileApi]] allowlist and get `403 admin_required` everywhere else. The 403 (rather than
    * the anonymous 401) is what tells a client its session is valid but under-privileged.
    *
    * Always-open paths: `/api/auth/login`, `/api/auth/change-password` (the current password is the
    * credential, and a user blocked by must_change_password has no session to present),
    * `/api/config/client`, `/health`, `/ready`, and everything outside `/api/...` (incl.
    * `/ui/...`).
    */
  private def apiKeyGuard(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    // Treat an empty string the same as unset. Compose / k8s configs routinely
    // pass `QOD_API_KEY=${API_KEY:-}` with `.env API_KEY=` empty; pureconfig
    // then materializes `Some("")`, which would otherwise enable the guard
    // with a key no client ever sends.
    val staticConfigured = cfg.apiKey.filter(_.nonEmpty)
    if staticConfigured.isEmpty then
      logger.info(
        "QOD_API_KEY unset: static-key auth disabled; only session and PAT " +
          "credentials are accepted on /api"
      )
    else logger.info("REST API X-API-Key enforcement enabled (static key + UI session tokens).")

    Kleisli { req =>
      // Router parity: tapir segments the path as
      // renderString.dropWhile(_ == '/').split("/").map(decode), i.e. every
      // leading slash is dropped and each segment percent-decoded. The guard
      // MUST see the same shape or an encoded/slash-prefixed spelling of an
      // /api path routes while skipping the guard (proven: ////api/tenant/list
      // and /%61pi/tenant/list both bypassed earlier derivations).
      val path = {
        val segs = req.uri.path.segments.map(_.decoded()).dropWhile(_.isEmpty)
        val raw  = segs.mkString("/", "/", "")
        if req.uri.path.endsWithSlash then raw + "/" else raw
      }
      if !path.startsWith("/api/") && path != "/api" then routes(req)
      else if isPublicApi(path) then routes(req)
      else
        // Admit on X-API-Key header OR on the qod_session cookie. The cookie
        // is the browser path (HttpOnly so JS can't read it; SameSite=Lax so
        // it doesn't leak cross-origin); the header is the CLI / static-key
        // path. A request can present either; the static key only ever
        // matches via the header.
        val headerToken = req.headers.get(CIString("X-API-Key")).map(_.head.value)
        val cookieToken = req.cookies.find(_.name == SessionTokenStore.CookieName).map(_.content)
        val provided    = headerToken.orElse(cookieToken)
        // Constant-time compare so a caller can't recover the static key byte by
        // byte from response timing. `Option.contains` uses String.equals, which
        // short-circuits on the first mismatched char.
        val staticMatch = (staticConfigured, headerToken) match
          case (Some(expected), Some(actual)) => constantTimeEq(actual, expected)
          case _                              => false
        val sessionAdmin = provided.exists(isAdminToken)
        // Resolved once: it decides both the non-admin demotion below and the
        // per-request tenant scope check further down.
        val tokenScope      = provided.flatMap(scopeOfToken)
        val nonAdminSession = tokenScope.isDefined && !sessionAdmin

        val admitted =
          staticMatch || sessionAdmin || (nonAdminSession && isProfileApi(path))
        // Strip control characters -- jsonb rejects NUL (0x00); cap length so
        // detail stays bounded. An unsanitized `%00` in the path would make
        // the insert throw and the attacker would erase their own trail.
        // Shared by both denial arms below.
        val safePath = path.filter(c => c >= ' ' && c != 0x7f).take(200)
        if !admitted then
          if nonAdminSession then
            // A real, valid session without an admin grant. Answer with the
            // same stable code the login gate uses so clients treat both alike
            // -- and not with the anonymous 401, which would send a browser
            // back to the login screen it just came from.
            //
            // Audited under the caller's REAL identity (recoverable from the
            // session), so a low-privilege insider sweeping admin endpoints
            // leaves a trail.
            val caller   = provided.flatMap(sessionOfToken).map(_.profile)
            val username = caller.map(_.username).getOrElse("unknown")
            // Rate-limited per principal, not per host: the recorder writes
            // synchronously on the request path, so one authenticated session
            // could otherwise insert an audit row per request at line rate.
            // Collapsing a flood to one row per interval is deliberate -- the
            // first row is the signal; the rest are the attacker's volume.
            if auditLimiter.allow("session:" + username) then
              audit.restAs(
                username,
                "tenant",
                "auth",
                AuditActions.AuthAdminRequired,
                "denied",
                tenant = caller.flatMap(_.tenant),
                detail = Map("path" -> safePath)
              )
            OptionT.pure[IO](
              Response[IO](Status.Forbidden)
                .withEntity(
                  """{"error":"admin_required","message":"this endpoint requires an admin session"}"""
                )
                .withContentType(
                  org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)
                )
            )
          else
            val source = req.remote.map(_.host.toString).getOrElse("unknown")
            if auditLimiter.allow(source) then
              audit.restAs(
                "anonymous",
                "system",
                "auth",
                AuditActions.AuthApiKeyFailure,
                "denied",
                detail = Map("path" -> safePath, "source" -> source)
              )
            OptionT.pure[IO](Response[IO](Status.Unauthorized))
        else
          // Per-request tenant scope check. Only applies when there is a known
          // session (not the static key). Body-tenant endpoints
          // do their own check via TenantScopeCheck.reject.
          val queryTenant = req.uri.query.params.get("tenant")
          val pathTenant  = TenantScopeGuard.extractTenant(path, queryTenant)
          (tokenScope, pathTenant) match
            case (Some(scope), Some(t))
                if !scope.superuser && !scope.manageableTenants.contains(t) =>
              OptionT.pure[IO](
                Response[IO](Status.Forbidden)
                  .withEntity(
                    s"""{"error":"tenant_forbidden","message":"session has no admin grant on tenant '$t'"}"""
                  )
                  .withContentType(
                    org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)
                  )
              )
            case _ => routes(req)
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
        CatalogEndpoints.listSchemasEndpoint.serverLogic { case (tenant, tenantDb, token) =>
          IO.blocking(h.listSchemas(tenant, tenantDb, token)(scopeOfToken))
        },
        CatalogEndpoints.listTablesEndpoint.serverLogic { case (tenant, tenantDb, schema, token) =>
          IO.blocking(h.listTables(tenant, tenantDb, schema, token)(scopeOfToken))
        },
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
        CatalogEndpoints.listSnapshotsEndpoint.serverLogic {
          case (tenant, tenantDb, limit, before, table, token) =>
            IO.blocking(
              h.listSnapshots(tenant, tenantDb, limit, before, table, token)(scopeOfToken)
            )
        }
      )
    }

    // Snapshot tags (EPIC P2 / Spec 06). Session-gated per request via
    // TenantScopeCheck inside the handlers, same shape as the browser GETs
    // above (both surfaces have been gated since Spec 00).
    val tagEndpoints: List[ServerEndpoint[Any, IO]] = tags.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        TagEndpoints.listTagsEndpoint.serverLogic { case (tenant, tenantDb, token) =>
          h.list(tenant, tenantDb, token)(scopeOfToken)
        },
        TagEndpoints.createTagEndpoint.serverLogic { case (req, token) =>
          h.create(req, token)(scopeOfToken)
        },
        TagEndpoints.deleteTagEndpoint.serverLogic { case (req, token) =>
          h.delete(req, token)(scopeOfToken)
        },
        TagEndpoints.protectTagEndpoint.serverLogic { case (req, token) =>
          h.protect(req, token)(scopeOfToken)
        }
      )
    }

    // Managed maintenance (EPIC Spec 09). Session-gated per request via
    // TenantScopeCheck inside the handlers, same shape as tagEndpoints.
    val maintenanceEndpoints: List[ServerEndpoint[Any, IO]] = maintenance.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        MaintenanceEndpoints.upsertPolicyEndpoint.serverLogic { case (req, token) =>
          h.upsertPolicy(req, token)(scopeOfToken)
        },
        MaintenanceEndpoints.deletePolicyEndpoint.serverLogic { case (req, token) =>
          h.deletePolicy(req, token)(scopeOfToken)
        },
        MaintenanceEndpoints.listPoliciesEndpoint.serverLogic { case (tenant, tenantDb, token) =>
          h.listPolicies(tenant, tenantDb, token)(scopeOfToken)
        },
        MaintenanceEndpoints.listRunsEndpoint.serverLogic {
          case (tenant, tenantDb, limit, before, token) =>
            h.listRuns(tenant, tenantDb, limit, before, token)(scopeOfToken)
        },
        MaintenanceEndpoints.triggerRunEndpoint.serverLogic { case (req, token) =>
          h.triggerRun(req, token)(scopeOfToken)
        }
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
            h.schemaDiff(tenant, tenantDb, schema, table, from, to, token)(scopeOfToken)
        },
        TimeTravelEndpoints.dataDiffEndpoint.serverLogic {
          case (tenant, tenantDb, schema, table, from, to, limit, cursor, changeType, token) =>
            h.dataDiff(tenant, tenantDb, schema, table, from, to, limit, cursor, changeType, token)(
              scopeOfToken
            )
        }
      )
    }

    // Undrop (Spec 03). Session-gated per request via TenantScopeCheck inside the handler.
    val undropEndpoints: List[ServerEndpoint[Any, IO]] = undrop.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        UndropEndpoints.recoverableEndpoint.serverLogic { case (tenant, tenantDb, limit, token) =>
          h.recoverable(tenant, tenantDb, limit, token)(scopeOfToken)
        },
        UndropEndpoints.undropEndpoint.serverLogic { case (req, token) =>
          h.undrop(req, token)(scopeOfToken)
        }
      )
    }

    // Restore (Spec 04). Session-gated per request via TenantScopeCheck inside the handler.
    val restoreEndpoints: List[ServerEndpoint[Any, IO]] = restore.toList.map { h =>
      RestoreEndpoints.restoreEndpoint.serverLogic { case (req, token) =>
        h.restore(req, token)(scopeOfToken)
      }
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
      AuthEndpoints.login.serverLogic { case (req, proto) => auth.login(req, proto) },
      AuthEndpoints.changePassword.serverLogic(req => auth.changePassword(req)),
      AuthEndpoints.logout.serverLogic { case (apiKey, cookie, proto) =>
        auth.logout(apiKey, cookie, proto)
      },
      AuthEndpoints.whoami.serverLogic(token => auth.whoami(token, None)),
      AuthEndpoints.authMode.serverLogic(tenant => auth.authMode(tenant)),
      // Self-scoped: identity comes from the token, never from a request param.
      ProfileEndpoints.usage.serverLogic { case (days, token) => profile.usage(days, token) },
      ProfileEndpoints.statements.serverLogic { case (limit, token) =>
        profile.statements(limit, token)
      },
      NodeEndpoints.statementHistory.serverLogic { case (limit, token) =>
        statementHistory.recent(limit, token)(scopeOfToken)
      },
      TelemetryEndpoints.auditList.serverLogic {
        case (family, tenant, actor, action, q, from, to, limit, before, noTenant, token) =>
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
            token
          )(scopeOfToken)
      },
      TelemetryEndpoints.auditActions.serverLogic(token => auditHandlers.actions(token)),
      TelemetryEndpoints.historyTrends.serverLogic {
        case (granularity, from, to, tenant, pool, token) =>
          history.trends(granularity, from, to, tenant, pool, token)(scopeOfToken)
      },
      TelemetryEndpoints.historyStatements.serverLogic {
        case (from, to, tenant, pool, user, status, q, limit, before, token) =>
          history.statements(from, to, tenant, pool, user, status, q, limit, before, token)(
            scopeOfToken
          )
      },
      TelemetryEndpoints.usage.serverLogic { case (from, to, groupBy, tenant, pool, token) =>
        usage.usage(from, to, groupBy, tenant, pool, token)(scopeOfToken)
      },
      AuthEndpoints.oidcStart.serverLogic { case (tenant, returnTo, proto) =>
        auth.oidcStart(tenant, returnTo, proto)
      },
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
      }
    )

    // Starlake SSO handoff. Mounted only when the integration is effectively on (SL_ENABLED=true
    // AND SL_URL non-empty) -- see ManagementAuthConfig.slIntegrationOn. With the integration off
    // (the default), these routes are absent entirely: not 404-from-router, unrouted.
    val ssoEndpoints: List[ServerEndpoint[Any, IO]] =
      if cfg.auth.management.slIntegrationOn then
        List[ServerEndpoint[Any, IO]](
          AuthEndpoints.ssoTicket.serverLogic(token => auth.ssoTicket(token)),
          AuthEndpoints.ssoRedeem.serverLogic(req => auth.ssoRedeem(req))
        )
      else Nil

    // Mounted only when a handler is wired (Main always wires one). Session-only by
    // construction: the handler refuses a PAT presented as the credential, so these
    // routes cannot be driven by the very tokens they manage.
    val patEndpoints: List[ServerEndpoint[Any, IO]] = pat.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        PatEndpoints.create.serverLogic { case (token, req) => h.create(token, req) },
        PatEndpoints.list.serverLogic(token => h.list(token)),
        PatEndpoints.revoke.serverLogic { case (token, req) => h.revoke(token, req) },
        PatEndpoints.delete.serverLogic { case (token, req) => h.delete(token, req) }
      )
    }

    // Mounted only when a handler is wired; the paths are guard-exempt regardless
    // (see isPublicApi), so a wired handler is reachable pre-session.
    val passwordResetEndpoints: List[ServerEndpoint[Any, IO]] = passwordReset.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        AuthEndpoints.forgotPassword.serverLogic(req => h.forgotPassword(req)),
        AuthEndpoints.resetPassword.serverLogic(req => h.resetPassword(req))
      )
    }

    val rbacEndpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      RbacEndpoints.createUser.serverLogic { case (req, token) =>
        users.createUser(req, token)(scopeOfToken)
      },
      RbacEndpoints.updateUser.serverLogic { case (req, token) =>
        users.updateUser(req, token)(scopeOfToken)
      },
      RbacEndpoints.deleteUser.serverLogic { case (req, token) =>
        users.deleteUser(req, token)(scopeOfToken)
      },
      RbacEndpoints.listUsers.serverLogic { case (t, key) =>
        users.listUsers(t, key)(scopeOfToken)
      },
      RbacEndpoints.effectivePermissions.serverLogic { case (id, key) =>
        users.effective(id, key)(scopeOfToken)
      },
      RbacEndpoints.createRole.serverLogic { case (req, token) =>
        roles.createRole(req, token)(scopeOfToken)
      },
      RbacEndpoints.deleteRole.serverLogic { case (req, token) =>
        roles.deleteRole(req, token)(scopeOfToken)
      },
      RbacEndpoints.listRoles.serverLogic { case (t, key) =>
        roles.listRoles(t, key)(scopeOfToken)
      },
      RbacEndpoints.grantRolePermission.serverLogic { case (req, token) =>
        roles.grantPermission(req, token)(scopeOfToken)
      },
      RbacEndpoints.revokeRolePermission.serverLogic { case (req, token) =>
        roles.revokePermission(req, token)(scopeOfToken)
      },
      RbacEndpoints.listRolePermissions.serverLogic { case (roleId, key) =>
        roles.listPermissions(roleId, key)(scopeOfToken)
      },
      RbacEndpoints.createGroup.serverLogic { case (req, token) =>
        groups.createGroup(req, token)(scopeOfToken)
      },
      RbacEndpoints.deleteGroup.serverLogic { case (req, token) =>
        groups.deleteGroup(req, token)(scopeOfToken)
      },
      RbacEndpoints.listGroups.serverLogic { case (t, key) =>
        groups.listGroups(t, key)(scopeOfToken)
      },
      RbacEndpoints.addUserRoleMembership.serverLogic { case (req, token) =>
        memberships.addUserRole(req, token)(scopeOfToken)
      },
      RbacEndpoints.removeUserRoleMembership.serverLogic { case (req, token) =>
        memberships.removeUserRole(req, token)(scopeOfToken)
      },
      RbacEndpoints.addUserGroupMembership.serverLogic { case (req, token) =>
        memberships.addUserGroup(req, token)(scopeOfToken)
      },
      RbacEndpoints.removeUserGroupMembership.serverLogic { case (req, token) =>
        memberships.removeUserGroup(req, token)(scopeOfToken)
      },
      RbacEndpoints.addGroupRoleMembership.serverLogic { case (req, token) =>
        memberships.addGroupRole(req, token)(scopeOfToken)
      },
      RbacEndpoints.removeGroupRoleMembership.serverLogic { case (req, token) =>
        memberships.removeGroupRole(req, token)(scopeOfToken)
      },
      RbacEndpoints.listGroupRoleMembership.serverLogic { case (groupId, key) =>
        memberships.listGroupRoles(groupId, key)(scopeOfToken)
      },
      RbacEndpoints.grantPoolPermission.serverLogic { case (req, token) =>
        poolPermissions.grant(req, token)(scopeOfToken)
      },
      RbacEndpoints.revokePoolPermission.serverLogic { case (req, token) =>
        poolPermissions.revoke(req, token)(scopeOfToken)
      },
      RbacEndpoints.listPoolPermissions.serverLogic { case (t, u, g, key) =>
        poolPermissions.list(t, u, g, key)(scopeOfToken)
      },
      RbacEndpoints.createColumnPolicy.serverLogic { case (req, token) =>
        columnPolicies.create(req, token)(scopeOfToken)
      },
      RbacEndpoints.updateColumnPolicy.serverLogic { case (req, token) =>
        columnPolicies.update(req, token)(scopeOfToken)
      },
      RbacEndpoints.deleteColumnPolicy.serverLogic { case (req, token) =>
        columnPolicies.delete(req, token)(scopeOfToken)
      },
      RbacEndpoints.listColumnPolicies.serverLogic { case (roleId, key) =>
        columnPolicies.list(roleId, key)(scopeOfToken)
      },
      RbacEndpoints.createRowPolicy.serverLogic { case (req, token) =>
        rowPolicies.create(req, token)(scopeOfToken)
      },
      RbacEndpoints.updateRowPolicy.serverLogic { case (req, token) =>
        rowPolicies.update(req, token)(scopeOfToken)
      },
      RbacEndpoints.deleteRowPolicy.serverLogic { case (req, token) =>
        rowPolicies.delete(req, token)(scopeOfToken)
      },
      RbacEndpoints.listRowPolicies.serverLogic { case (roleId, key) =>
        rowPolicies.list(roleId, key)(scopeOfToken)
      }
    )

    val metricsEndpoints: List[ServerEndpoint[Any, IO]] = metricsEndpoint.serverEndpoints

    val federatedSourceEndpoints: List[ServerEndpoint[Any, IO]] =
      federatedSources.toList.flatMap { h =>
        List[ServerEndpoint[Any, IO]](
          FederatedSourceEndpoints.createFederatedSource.serverLogic { case (t, td, req, token) =>
            h.createSource(t, td, req, token)
          },
          FederatedSourceEndpoints.listFederatedSources.serverLogic { case (t, td) =>
            h.listSources(t, td)
          },
          FederatedSourceEndpoints.getFederatedSource.serverLogic { case (t, td, alias) =>
            h.getSource(t, td, alias)
          },
          FederatedSourceEndpoints.deleteFederatedSource.serverLogic { case (t, td, alias, token) =>
            h.deleteSource(t, td, alias, token)
          },
          FederatedSourceEndpoints.listFederatedSecrets.serverLogic { case (t, td, alias) =>
            h.listSecrets(t, td, alias)
          },
          FederatedSourceEndpoints.upsertFederatedSecret.serverLogic {
            case (t, td, alias, req, token) =>
              h.upsertSecret(t, td, alias, req, token)
          },
          FederatedSourceEndpoints.deleteFederatedSecret.serverLogic {
            case (t, td, alias, name, token) =>
              h.deleteSecret(t, td, alias, name, token)
          }
        )
      }

    val endpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      PoolEndpoints.createPool.serverLogic { case (req, token) =>
        pools.createPool(req, token)(scopeOfToken)
      },
      PoolEndpoints.scalePool.serverLogic { case (req, token) =>
        pools.scalePool(req, token)(scopeOfToken)
      },
      PoolEndpoints.stopPool.serverLogic { case (req, token) =>
        pools.stopPool(req, token)(scopeOfToken)
      },
      PoolEndpoints.deletePool.serverLogic { case (req, token) =>
        pools.deletePool(req, token)(scopeOfToken)
      },
      PoolEndpoints.suspendPool.serverLogic { case (req, token) =>
        pools.suspendPool(req, token)(scopeOfToken)
      },
      PoolEndpoints.resumePool.serverLogic { case (req, token) =>
        pools.resumePool(req, token)(scopeOfToken)
      },
      PoolEndpoints.listPools.serverLogic(token => pools.listPools(token)(scopeOfToken)),
      PoolEndpoints.poolStatus.serverLogic((t, td, p) => pools.poolStatus(t, td, p)),
      PoolEndpoints.setPoolDisabled.serverLogic { case (req, token) =>
        pools.setPoolDisabled(req, token)(scopeOfToken)
      },
      PoolEndpoints.setPoolResources.serverLogic { case (req, token) =>
        pools.setResources(req, token)(scopeOfToken)
      },
      PoolEndpoints.setPoolTemplate.serverLogic { case (req, token) =>
        pools.setPodTemplate(req, token)(scopeOfToken)
      },
      PoolEndpoints.setPoolLockdown.serverLogic { case (req, token) =>
        pools.setLockdown(req, token)(scopeOfToken)
      },
      PoolEndpoints.setPoolAutoscale.serverLogic { case (req, token) =>
        pools.setPoolAutoscale(req, token)(scopeOfToken)
      },
      NodeEndpoints.setMaxConcurrent.serverLogic { case (req, token) =>
        nodes.setMaxConcurrent(req, token)(scopeOfToken)
      },
      NodeEndpoints.quarantineNode.serverLogic { case (req, token) =>
        nodes.quarantineNode(req, token)(scopeOfToken)
      },
      NodeEndpoints.unquarantineNode.serverLogic { case (req, token) =>
        nodes.unquarantineNode(req, token)(scopeOfToken)
      },
      NodeEndpoints.restartNode.serverLogic { case (req, token) =>
        nodes.restartNode(req, token)(scopeOfToken)
      },
      TenantEndpoints.createTenant.serverLogic { case (req, token) =>
        tenants.createTenant(req, token)(scopeOfToken)
      },
      TenantEndpoints.listTenants.serverLogic(token => tenants.listTenants(token)(scopeOfToken)),
      TenantEndpoints.deleteTenant.serverLogic { case (req, token) =>
        tenants.deleteTenant(req, token)(scopeOfToken)
      },
      TenantEndpoints.setTenantDisabled.serverLogic { case (req, token) =>
        tenants.setTenantDisabled(req, token)(scopeOfToken)
      },
      TenantEndpoints.setTenantAuth.serverLogic { case (req, token) =>
        tenants.setTenantAuth(req, token)(scopeOfToken)
      },
      TenantEndpoints.createTenantDb.serverLogic { case (req, token) =>
        tenantDbs.createTenantDb(req, token)(scopeOfToken)
      },
      TenantEndpoints.listTenantDbs.serverLogic { case (tenant, token) =>
        tenantDbs.listTenantDbs(tenant, token)(scopeOfToken)
      },
      TenantEndpoints.deleteTenantDb.serverLogic { case (req, token) =>
        tenantDbs.deleteTenantDb(req, token)(scopeOfToken)
      },
      TenantEndpoints.updateTenantDb.serverLogic { case (req, token) =>
        tenantDbs.update(req, token)(scopeOfToken)
      },
      TenantEndpoints.metastoreDefaults.serverLogic { token =>
        tenantDbs.metastoreDefaults(token)(scopeOfToken)
      },
      Endpoints.health.serverLogic(_ => health.health),
      Endpoints.ready.serverLogic(_ => health.ready),
      Endpoints.clientConfig.serverLogic(_ =>
        IO.pure(
          Right(
            ClientConfigResponse(
              flightSqlHost = edgeCfg.host,
              flightSqlPort = edgeCfg.port,
              flightSqlTls = edgeCfg.tlsEnabled,
              authEnabled = authEnabled,
              placementSupported = pools.supportsPlacement,
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
      Endpoints.serverConfig.serverLogic(token => serverConfig.list(token)(scopeOfToken)),
      Endpoints.manifestExport.serverLogic(token => manifest.exportYaml(token)(scopeOfToken)),
      Endpoints.manifestImport.serverLogic { case (body, token) =>
        manifest.importYaml(body, token)(scopeOfToken)
      },
      NodeEndpoints.activeStatements.serverLogic(token => activeStmts.list(token)(scopeOfToken)),
      NodeEndpoints.killStatement.serverLogic { case (req, token) =>
        activeStmts.kill(req, token)(scopeOfToken)
      }
    ) ++ authEndpoints ++ ssoEndpoints ++ patEndpoints ++ passwordResetEndpoints ++ catalogEndpoints ++ tagEndpoints ++ maintenanceEndpoints ++ timeTravelEndpoints ++ catalogHistoryEndpoints ++ undropEndpoints ++ restoreEndpoints ++ metricsEndpoints ++ rbacEndpoints ++ federatedSourceEndpoints ++ moduleEndpoints

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
      .withHttpApp(
        (apiKeyGuard(apiRoutes) <+> mcpRoutes.getOrElse(
          HttpRoutes.empty[IO]
        ) <+> uiRoutes <+> moduleStatic <+> rootRedirect).orNotFound
      )
      .build
