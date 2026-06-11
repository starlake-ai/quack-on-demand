package ai.starlake.quack.ondemand

import ai.starlake.quack.{FlightConfig, ManagerConfig}
import ai.starlake.quack.ondemand.api._
import cats.data.{Kleisli, OptionT}
import cats.effect.{IO, Resource}
import cats.implicits._
import com.comcast.ip4s.{Host, Port}
import com.typesafe.scalalogging.LazyLogging
import org.http4s.{HttpRoutes, Response, StaticFile, Status}
import org.http4s.ember.server.EmberServerBuilder
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
    metricsEndpoint: ai.starlake.quack.observability.metrics.MetricsEndpoint,
    // Phase B: RBAC handlers. All mounted unconditionally -- the
    // legacy ACL endpoints (above) are still mounted alongside until
    // Phase C drops them.
    users: UserHandlers,
    roles: RoleHandlers,
    groups: GroupHandlers,
    memberships: MembershipHandlers,
    poolPermissions: PoolPermissionHandlers,
    serverConfig: ConfigHandlers,
    manifest: ManifestHandlers,
    federatedSources: Option[FederatedSourceHandlers] = None
) extends LazyLogging:

  /** Path is unauthenticated - the UI needs these before login. */
  private def isPublicApi(path: String): Boolean =
    path == "/api/auth/login" || path == "/api/config/client"

  /** Gate on the api namespace. Two modes:
    *   - **`cfg.apiKey` unset** (default zero-config): the namespace is open. A startup warning
    *     fires so operators know the manager is exposed; this is the documented dev default. To
    *     lock it down, set `QOD_API_KEY` (or use the UI which mints session tokens via
    *     /api/auth/login).
    *   - **`cfg.apiKey` set**: every `/api/...` request must carry an `X-API-Key` header matching
    *     either the static key OR a known admin UI session token.
    *
    * Always-open paths: `/api/auth/login`, `/api/config/client`, `/health`, and everything outside
    * `/api/...` (incl. `/ui/...`).
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
    else
      logger.info("REST API X-API-Key enforcement enabled (static key + UI session tokens).")

    Kleisli { req =>
      val path = req.uri.path.toString
      if !path.startsWith("/api/") && path != "/api" then routes(req)
      else if isPublicApi(path) then routes(req)
      else
        val provided = req.headers.get(CIString("X-API-Key")).map(_.head.value)
        val staticMatch  = staticConfigured.exists(expected => provided.contains(expected))
        val sessionAdmin = provided.exists(sessions.isAdmin)
        val openMode     = staticConfigured.isEmpty

        val admitted = staticMatch || sessionAdmin || openMode
        if !admitted then OptionT.pure[IO](Response[IO](Status.Unauthorized))
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
        Endpoints.listSchemasEndpoint.serverLogicSuccess { case (tenant, tenantDb) =>
          IO.blocking(h.listSchemas(tenant, tenantDb))
        },
        Endpoints.listTablesEndpoint.serverLogicSuccess { case (tenant, tenantDb, schema) =>
          IO.blocking(h.listTables(tenant, tenantDb, schema))
        },
        Endpoints.getTableEndpoint.serverLogic { case (tenant, tenantDb, schema, table) =>
          IO.blocking(h.getTable(tenant, tenantDb, schema, table)).map {
            case Some(d) => Right(d)
            case None    => Left(s"table $schema.$table not found")
          }
        }
      )
    }

    val authEndpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      Endpoints.login.serverLogic(auth.login),
      Endpoints.logout.serverLogic(auth.logout),
      Endpoints.whoami.serverLogic(auth.whoami),
      Endpoints.statementHistory.serverLogic(statementHistory.recent)
    )

    val rbacEndpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      RbacEndpoints.createUser.serverLogic(users.createUser),
      RbacEndpoints.updateUser.serverLogic(users.updateUser),
      RbacEndpoints.deleteUser.serverLogic(users.deleteUser),
      RbacEndpoints.listUsers.serverLogic { case (t, key) =>
        users.listUsers(t, key)(sessions.scopeOf)
      },
      RbacEndpoints.effectivePermissions.serverLogic(users.effective),
      RbacEndpoints.createRole.serverLogic(roles.createRole),
      RbacEndpoints.deleteRole.serverLogic(roles.deleteRole),
      RbacEndpoints.listRoles.serverLogic(roles.listRoles),
      RbacEndpoints.grantRolePermission.serverLogic(roles.grantPermission),
      RbacEndpoints.revokeRolePermission.serverLogic(roles.revokePermission),
      RbacEndpoints.listRolePermissions.serverLogic(roles.listPermissions),
      RbacEndpoints.createGroup.serverLogic(groups.createGroup),
      RbacEndpoints.deleteGroup.serverLogic(groups.deleteGroup),
      RbacEndpoints.listGroups.serverLogic(groups.listGroups),
      RbacEndpoints.addUserRoleMembership.serverLogic(memberships.addUserRole),
      RbacEndpoints.removeUserRoleMembership.serverLogic(memberships.removeUserRole),
      RbacEndpoints.addUserGroupMembership.serverLogic(memberships.addUserGroup),
      RbacEndpoints.removeUserGroupMembership.serverLogic(memberships.removeUserGroup),
      RbacEndpoints.addGroupRoleMembership.serverLogic(memberships.addGroupRole),
      RbacEndpoints.removeGroupRoleMembership.serverLogic(memberships.removeGroupRole),
      RbacEndpoints.listGroupRoleMembership.serverLogic(memberships.listGroupRoles),
      RbacEndpoints.grantPoolPermission.serverLogic(poolPermissions.grant),
      RbacEndpoints.revokePoolPermission.serverLogic(poolPermissions.revoke),
      RbacEndpoints.listPoolPermissions.serverLogic { case (t, u, g) =>
        poolPermissions.list(t, u, g)
      }
    )

    val metricsEndpoints: List[ServerEndpoint[Any, IO]] = metricsEndpoint.serverEndpoints

    val federatedSourceEndpoints: List[ServerEndpoint[Any, IO]] =
      federatedSources.toList.flatMap { h =>
        List[ServerEndpoint[Any, IO]](
          Endpoints.createFederatedSource.serverLogic { case (t, td, req) =>
            h.createSource(t, td, req)
          },
          Endpoints.listFederatedSources.serverLogic { case (t, td) => h.listSources(t, td) },
          Endpoints.getFederatedSource.serverLogic { case (t, td, alias) =>
            h.getSource(t, td, alias)
          },
          Endpoints.deleteFederatedSource.serverLogic { case (t, td, alias) =>
            h.deleteSource(t, td, alias)
          },
          Endpoints.listFederatedSecrets.serverLogic { case (t, td, alias) =>
            h.listSecrets(t, td, alias)
          },
          Endpoints.upsertFederatedSecret.serverLogic { case (t, td, alias, req) =>
            h.upsertSecret(t, td, alias, req)
          },
          Endpoints.deleteFederatedSecret.serverLogic { case (t, td, alias, name) =>
            h.deleteSecret(t, td, alias, name)
          }
        )
      }

    val endpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      Endpoints.createPool.serverLogic { case (req, key) =>
        pools.createPool(req, key)(sessions.scopeOf)
      },
      Endpoints.scalePool.serverLogic { case (req, key) =>
        pools.scalePool(req, key)(sessions.scopeOf)
      },
      Endpoints.stopPool.serverLogic { case (req, key) =>
        pools.stopPool(req, key)(sessions.scopeOf)
      },
      Endpoints.listPools.serverLogic(apiKey => pools.listPools(apiKey)(sessions.scopeOf)),
      Endpoints.poolStatus.serverLogic((t, td, p) => pools.poolStatus(t, td, p)),
      Endpoints.setPoolDisabled.serverLogic { case (req, key) =>
        pools.setPoolDisabled(req, key)(sessions.scopeOf)
      },
      Endpoints.setRole.serverLogic { case (req, key) =>
        nodes.setRole(req, key)(sessions.scopeOf)
      },
      Endpoints.setMaxConcurrent.serverLogic { case (req, key) =>
        nodes.setMaxConcurrent(req, key)(sessions.scopeOf)
      },
      Endpoints.quarantineNode.serverLogic { case (req, key) =>
        nodes.quarantineNode(req, key)(sessions.scopeOf)
      },
      Endpoints.restartNode.serverLogic { case (req, key) =>
        nodes.restartNode(req, key)(sessions.scopeOf)
      },
      Endpoints.createTenant.serverLogic { case (req, key) =>
        tenants.createTenant(req, key)(sessions.scopeOf)
      },
      Endpoints.listTenants.serverLogic(apiKey => tenants.listTenants(apiKey)(sessions.scopeOf)),
      Endpoints.deleteTenant.serverLogic { case (req, key) =>
        tenants.deleteTenant(req, key)(sessions.scopeOf)
      },
      Endpoints.setTenantDisabled.serverLogic { case (req, key) =>
        tenants.setTenantDisabled(req, key)(sessions.scopeOf)
      },
      Endpoints.setTenantAuth.serverLogic { case (req, key) =>
        tenants.setTenantAuth(req, key)(sessions.scopeOf)
      },
      Endpoints.createTenantDb.serverLogic { case (req, key) =>
        tenantDbs.createTenantDb(req, key)(sessions.scopeOf)
      },
      Endpoints.listTenantDbs.serverLogic(tenant => tenantDbs.listTenantDbs(tenant)),
      Endpoints.deleteTenantDb.serverLogic { case (req, key) =>
        tenantDbs.deleteTenantDb(req, key)(sessions.scopeOf)
      },
      Endpoints.health.serverLogic(_ => health.health),
      Endpoints.clientConfig.serverLogic(_ =>
        IO.pure(
          Right(
            ClientConfigResponse(
              flightSqlHost = edgeCfg.host,
              flightSqlPort = edgeCfg.port,
              flightSqlTls = edgeCfg.tlsEnabled,
              tenantClaim = edgeCfg.tenantClaim,
              authEnabled = authEnabled,
              placementSupported = pools.supportsPlacement
            )
          )
        )
      ),
      Endpoints.serverConfig.serverLogic(apiKey => serverConfig.list(apiKey)(sessions.scopeOf)),
      Endpoints.manifestExport.serverLogic(apiKey => manifest.exportYaml(apiKey)(sessions.scopeOf)),
      Endpoints.manifestImport.serverLogic { case (body, apiKey) =>
        manifest.importYaml(body, apiKey)(sessions.scopeOf)
      }
    ) ++ authEndpoints ++ catalogEndpoints ++ metricsEndpoints ++ rbacEndpoints ++ federatedSourceEndpoints

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

    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString(cfg.host).get)
      .withPort(Port.fromInt(cfg.port).get)
      .withHttpApp((apiKeyGuard(apiRoutes) <+> uiRoutes).orNotFound)
      .build
