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
import org.http4s.{HttpRoutes, Method, Response, StaticFile, Status, Uri}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Location
import org.http4s.server.{staticcontent, Router}
import org.typelevel.ci.CIString
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

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
    usage: UsageHandlers = new UsageHandlers(NoopTelemetryStore)
) extends LazyLogging:

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
    path == "/api/auth/login" || path == "/api/config/client" ||
      path == "/api/auth/mode" ||
      path == "/api/auth/oidc/start" || path == "/api/auth/oidc/callback" ||
      path == "/api/auth/oidc/logout" ||
      path == "/api/auth/sql-token/start" || path == "/api/auth/sql-token/callback"

  /** Gate on the api namespace. Two modes:
    *   - **`cfg.apiKey` unset** (default zero-config): the namespace is open. A startup warning
    *     fires so operators know the manager is exposed; this is the documented dev default. To
    *     lock it down, set `QOD_API_KEY` (or use the UI which mints session tokens via
    *     /api/auth/login).
    *   - **`cfg.apiKey` set**: every `/api/...` request must carry an `X-API-Key` header matching
    *     either the static key OR a known admin UI session token.
    *
    * Always-open paths: `/api/auth/login`, `/api/config/client`, `/health`, `/ready`, and
    * everything outside `/api/...` (incl. `/ui/...`).
    */
  private def apiKeyGuard(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    // Treat an empty string the same as unset. Compose / k8s configs routinely
    // pass `QOD_API_KEY=${API_KEY:-}` with `.env API_KEY=` empty; pureconfig
    // then materializes `Some("")`, which would otherwise enable the guard
    // with a key no client ever sends.
    val staticConfigured = cfg.apiKey.filter(_.nonEmpty)
    if staticConfigured.isEmpty then
      logger.warn(
        "REST API is OPEN: QOD_API_KEY is not set. Set it (or rely on the " +
          "UI login flow) before exposing the manager beyond localhost."
      )
    else logger.info("REST API X-API-Key enforcement enabled (static key + UI session tokens).")

    Kleisli { req =>
      val path = req.uri.path.toString
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
        val sessionAdmin = provided.exists(sessions.isAdmin)
        val openMode     = staticConfigured.isEmpty

        val admitted = staticMatch || sessionAdmin || openMode
        if !admitted then
          val source = req.remote.map(_.host.toString).getOrElse("unknown")
          if auditLimiter.allow(source) then
            audit.restAs(
              "anonymous",
              "system",
              "auth",
              AuditActions.AuthApiKeyFailure,
              "denied",
              detail = Map("path" -> path, "source" -> source)
            )
          OptionT.pure[IO](Response[IO](Status.Unauthorized))
        else
          // Per-request tenant scope check. Only applies when there is a known
          // session (not the static key, not open mode). Body-tenant endpoints
          // do their own check via TenantScopeCheck.reject.
          val tokenScope  = provided.flatMap(sessions.scopeOf)
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
      // Postgres metastore. JDBC calls go on `IO.blocking` since Hikari
      // semantics are synchronous.
      List[ServerEndpoint[Any, IO]](
        CatalogEndpoints.listSchemasEndpoint.serverLogicSuccess { case (tenant, tenantDb) =>
          IO.blocking(h.listSchemas(tenant, tenantDb))
        },
        CatalogEndpoints.listTablesEndpoint.serverLogicSuccess { case (tenant, tenantDb, schema) =>
          IO.blocking(h.listTables(tenant, tenantDb, schema))
        },
        CatalogEndpoints.getTableEndpoint.serverLogic {
          case (tenant, tenantDb, schema, table, asOf, asOfTag) =>
            // Spec 06: at most one of asOf / asOfTag; a tag resolves to its
            // snapshot id and reuses the AS OF read path. A dangling tag
            // (snapshot vacuumed) falls through to the table-not-found 404.
            val resolved: Either[(sttp.model.StatusCode, String), Option[Long]] = tags match
              case Some(t)                   => t.resolveAsOf(tenant, tenantDb, asOf, asOfTag)
              case None if asOfTag.isDefined =>
                Left(
                  (
                    sttp.model.StatusCode.BadRequest,
                    "asOfTag is not supported on this manager (no tag handler wired)"
                  )
                )
              case None => Right(asOf)
            resolved match
              case Left(e)     => IO.pure(Left(e))
              case Right(snap) =>
                IO.blocking(h.getTable(tenant, tenantDb, schema, table, snap)).map {
                  case Some(d) => Right(d)
                  case None    =>
                    Left(
                      (
                        sttp.model.StatusCode.NotFound,
                        s"table $schema.$table not found" +
                          snap.fold("")(n => s" at snapshot $n")
                      )
                    )
                }
        },
        CatalogEndpoints.listSnapshotsEndpoint.serverLogicSuccess {
          case (tenant, tenantDb, limit, before) =>
            IO.blocking(h.listSnapshots(tenant, tenantDb, limit, before))
        }
      )
    }

    // Snapshot tags (EPIC P2 / Spec 06). Session-gated per request via
    // TenantScopeCheck inside the handlers - not part of catalogEndpoints
    // on purpose, so the tag surface never inherits the browser GETs'
    // ungated PublicEndpoint shape.
    val tagEndpoints: List[ServerEndpoint[Any, IO]] = tags.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        TagEndpoints.listTagsEndpoint.serverLogic { case (tenant, tenantDb, token) =>
          h.list(tenant, tenantDb, token)(sessions.scopeOf)
        },
        TagEndpoints.createTagEndpoint.serverLogic { case (req, token) =>
          h.create(req, token)(sessions.scopeOf)
        },
        TagEndpoints.deleteTagEndpoint.serverLogic { case (req, token) =>
          h.delete(req, token)(sessions.scopeOf)
        },
        TagEndpoints.protectTagEndpoint.serverLogic { case (req, token) =>
          h.protect(req, token)(sessions.scopeOf)
        }
      )
    }

    // Managed maintenance (EPIC Spec 09). Session-gated per request via
    // TenantScopeCheck inside the handlers, same shape as tagEndpoints.
    val maintenanceEndpoints: List[ServerEndpoint[Any, IO]] = maintenance.toList.flatMap { h =>
      List[ServerEndpoint[Any, IO]](
        MaintenanceEndpoints.upsertPolicyEndpoint.serverLogic { case (req, token) =>
          h.upsertPolicy(req, token)(sessions.scopeOf)
        },
        MaintenanceEndpoints.deletePolicyEndpoint.serverLogic { case (req, token) =>
          h.deletePolicy(req, token)(sessions.scopeOf)
        },
        MaintenanceEndpoints.listPoliciesEndpoint.serverLogic { case (tenant, tenantDb, token) =>
          h.listPolicies(tenant, tenantDb, token)(sessions.scopeOf)
        },
        MaintenanceEndpoints.listRunsEndpoint.serverLogic {
          case (tenant, tenantDb, limit, before, token) =>
            h.listRuns(tenant, tenantDb, limit, before, token)(sessions.scopeOf)
        },
        MaintenanceEndpoints.triggerRunEndpoint.serverLogic { case (req, token) =>
          h.triggerRun(req, token)(sessions.scopeOf)
        }
      )
    }

    val authEndpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      AuthEndpoints.login.serverLogic { case (req, proto) => auth.login(req, proto) },
      AuthEndpoints.logout.serverLogic { case (apiKey, cookie, proto) =>
        auth.logout(apiKey, cookie, proto)
      },
      AuthEndpoints.whoami.serverLogic(token => auth.whoami(token, None)),
      AuthEndpoints.authMode.serverLogic(tenant => auth.authMode(tenant)),
      NodeEndpoints.statementHistory.serverLogic { case (limit, token) =>
        statementHistory.recent(limit, token)(sessions.scopeOf)
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
          )(sessions.scopeOf)
      },
      TelemetryEndpoints.auditActions.serverLogic(token => auditHandlers.actions(token)),
      TelemetryEndpoints.historyTrends.serverLogic {
        case (granularity, from, to, tenant, pool, token) =>
          history.trends(granularity, from, to, tenant, pool, token)(sessions.scopeOf)
      },
      TelemetryEndpoints.historyStatements.serverLogic {
        case (from, to, tenant, pool, user, status, q, limit, before, token) =>
          history.statements(from, to, tenant, pool, user, status, q, limit, before, token)(
            sessions.scopeOf
          )
      },
      TelemetryEndpoints.usage.serverLogic { case (from, to, groupBy, tenant, pool, token) =>
        usage.usage(from, to, groupBy, tenant, pool, token)(sessions.scopeOf)
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

    val rbacEndpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      RbacEndpoints.createUser.serverLogic { case (req, token) =>
        users.createUser(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.updateUser.serverLogic { case (req, token) =>
        users.updateUser(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.deleteUser.serverLogic { case (req, token) =>
        users.deleteUser(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.listUsers.serverLogic { case (t, key) =>
        users.listUsers(t, key)(sessions.scopeOf)
      },
      RbacEndpoints.effectivePermissions.serverLogic { case (id, key) =>
        users.effective(id, key)(sessions.scopeOf)
      },
      RbacEndpoints.createRole.serverLogic { case (req, token) =>
        roles.createRole(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.deleteRole.serverLogic { case (req, token) =>
        roles.deleteRole(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.listRoles.serverLogic { case (t, key) =>
        roles.listRoles(t, key)(sessions.scopeOf)
      },
      RbacEndpoints.grantRolePermission.serverLogic { case (req, token) =>
        roles.grantPermission(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.revokeRolePermission.serverLogic { case (req, token) =>
        roles.revokePermission(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.listRolePermissions.serverLogic { case (roleId, key) =>
        roles.listPermissions(roleId, key)(sessions.scopeOf)
      },
      RbacEndpoints.createGroup.serverLogic { case (req, token) =>
        groups.createGroup(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.deleteGroup.serverLogic { case (req, token) =>
        groups.deleteGroup(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.listGroups.serverLogic { case (t, key) =>
        groups.listGroups(t, key)(sessions.scopeOf)
      },
      RbacEndpoints.addUserRoleMembership.serverLogic { case (req, token) =>
        memberships.addUserRole(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.removeUserRoleMembership.serverLogic { case (req, token) =>
        memberships.removeUserRole(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.addUserGroupMembership.serverLogic { case (req, token) =>
        memberships.addUserGroup(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.removeUserGroupMembership.serverLogic { case (req, token) =>
        memberships.removeUserGroup(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.addGroupRoleMembership.serverLogic { case (req, token) =>
        memberships.addGroupRole(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.removeGroupRoleMembership.serverLogic { case (req, token) =>
        memberships.removeGroupRole(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.listGroupRoleMembership.serverLogic { case (groupId, key) =>
        memberships.listGroupRoles(groupId, key)(sessions.scopeOf)
      },
      RbacEndpoints.grantPoolPermission.serverLogic { case (req, token) =>
        poolPermissions.grant(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.revokePoolPermission.serverLogic { case (req, token) =>
        poolPermissions.revoke(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.listPoolPermissions.serverLogic { case (t, u, g, key) =>
        poolPermissions.list(t, u, g, key)(sessions.scopeOf)
      },
      RbacEndpoints.createColumnPolicy.serverLogic { case (req, token) =>
        columnPolicies.create(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.updateColumnPolicy.serverLogic { case (req, token) =>
        columnPolicies.update(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.deleteColumnPolicy.serverLogic { case (req, token) =>
        columnPolicies.delete(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.listColumnPolicies.serverLogic { case (roleId, key) =>
        columnPolicies.list(roleId, key)(sessions.scopeOf)
      },
      RbacEndpoints.createRowPolicy.serverLogic { case (req, token) =>
        rowPolicies.create(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.updateRowPolicy.serverLogic { case (req, token) =>
        rowPolicies.update(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.deleteRowPolicy.serverLogic { case (req, token) =>
        rowPolicies.delete(req, token)(sessions.scopeOf)
      },
      RbacEndpoints.listRowPolicies.serverLogic { case (roleId, key) =>
        rowPolicies.list(roleId, key)(sessions.scopeOf)
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
        pools.createPool(req, token)(sessions.scopeOf)
      },
      PoolEndpoints.scalePool.serverLogic { case (req, token) =>
        pools.scalePool(req, token)(sessions.scopeOf)
      },
      PoolEndpoints.stopPool.serverLogic { case (req, token) =>
        pools.stopPool(req, token)(sessions.scopeOf)
      },
      PoolEndpoints.deletePool.serverLogic { case (req, token) =>
        pools.deletePool(req, token)(sessions.scopeOf)
      },
      PoolEndpoints.listPools.serverLogic(token => pools.listPools(token)(sessions.scopeOf)),
      PoolEndpoints.poolStatus.serverLogic((t, td, p) => pools.poolStatus(t, td, p)),
      PoolEndpoints.setPoolDisabled.serverLogic { case (req, token) =>
        pools.setPoolDisabled(req, token)(sessions.scopeOf)
      },
      PoolEndpoints.setPoolResources.serverLogic { case (req, token) =>
        pools.setResources(req, token)(sessions.scopeOf)
      },
      PoolEndpoints.setPoolTemplate.serverLogic { case (req, token) =>
        pools.setPodTemplate(req, token)(sessions.scopeOf)
      },
      NodeEndpoints.setMaxConcurrent.serverLogic { case (req, token) =>
        nodes.setMaxConcurrent(req, token)(sessions.scopeOf)
      },
      NodeEndpoints.quarantineNode.serverLogic { case (req, token) =>
        nodes.quarantineNode(req, token)(sessions.scopeOf)
      },
      NodeEndpoints.unquarantineNode.serverLogic { case (req, token) =>
        nodes.unquarantineNode(req, token)(sessions.scopeOf)
      },
      NodeEndpoints.restartNode.serverLogic { case (req, token) =>
        nodes.restartNode(req, token)(sessions.scopeOf)
      },
      TenantEndpoints.createTenant.serverLogic { case (req, token) =>
        tenants.createTenant(req, token)(sessions.scopeOf)
      },
      TenantEndpoints.listTenants.serverLogic(token =>
        tenants.listTenants(token)(sessions.scopeOf)
      ),
      TenantEndpoints.deleteTenant.serverLogic { case (req, token) =>
        tenants.deleteTenant(req, token)(sessions.scopeOf)
      },
      TenantEndpoints.setTenantDisabled.serverLogic { case (req, token) =>
        tenants.setTenantDisabled(req, token)(sessions.scopeOf)
      },
      TenantEndpoints.setTenantAuth.serverLogic { case (req, token) =>
        tenants.setTenantAuth(req, token)(sessions.scopeOf)
      },
      TenantEndpoints.createTenantDb.serverLogic { case (req, token) =>
        tenantDbs.createTenantDb(req, token)(sessions.scopeOf)
      },
      TenantEndpoints.listTenantDbs.serverLogic(tenant => tenantDbs.listTenantDbs(tenant)),
      TenantEndpoints.deleteTenantDb.serverLogic { case (req, token) =>
        tenantDbs.deleteTenantDb(req, token)(sessions.scopeOf)
      },
      TenantEndpoints.updateTenantDb.serverLogic { case (req, token) =>
        tenantDbs.update(req, token)(sessions.scopeOf)
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
              telemetryEnabled = serverConfig.telemetryEnabled
            )
          )
        )
      ),
      Endpoints.serverConfig.serverLogic(token => serverConfig.list(token)(sessions.scopeOf)),
      Endpoints.manifestExport.serverLogic(token => manifest.exportYaml(token)(sessions.scopeOf)),
      Endpoints.manifestImport.serverLogic { case (body, token) =>
        manifest.importYaml(body, token)(sessions.scopeOf)
      },
      NodeEndpoints.activeStatements.serverLogic(token =>
        activeStmts.list(token)(sessions.scopeOf)
      ),
      NodeEndpoints.killStatement.serverLogic { case (req, token) =>
        activeStmts.kill(req, token)(sessions.scopeOf)
      }
    ) ++ authEndpoints ++ catalogEndpoints ++ tagEndpoints ++ maintenanceEndpoints ++ metricsEndpoints ++ rbacEndpoints ++ federatedSourceEndpoints

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
      .withHttpApp((apiKeyGuard(apiRoutes) <+> uiRoutes <+> rootRedirect).orNotFound)
      .build
