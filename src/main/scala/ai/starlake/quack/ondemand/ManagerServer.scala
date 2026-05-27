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
import org.http4s.server.{Router, staticcontent}
import org.typelevel.ci.CIString
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

final class ManagerServer(
    cfg: ManagerConfig,
    edgeCfg: FlightConfig,
    pools: PoolHandlers,
    nodes: NodeHandlers,
    tenants: TenantHandlers,
    health: HealthHandler,
    acl: Option[AclHandlers],
    auth: AuthHandlers,
    sessions: SessionTokenStore,
    authEnabled: Boolean,
    statementHistory: StatementHistoryHandlers
) extends LazyLogging:

  /** Path is unauthenticated — the UI needs these before login. */
  private def isPublicApi(path: String): Boolean =
    path == "/api/auth/login" || path == "/api/config/client"

  /** Gate on the api namespace. Two modes:
    *   - **`cfg.apiKey` unset** (default zero-config): the namespace is
    *     open. A startup warning fires so operators know the manager is
    *     exposed; this is the documented dev default. To lock it down,
    *     set `SL_QUACK_API_KEY` (or use the UI which mints session
    *     tokens via /api/auth/login).
    *   - **`cfg.apiKey` set**: every `/api/...` request must carry an
    *     `X-API-Key` header matching either the static key OR a known
    *     admin UI session token.
    *
    * Always-open paths: `/api/auth/login`, `/api/config/client`,
    * `/health`, and everything outside `/api/...` (incl. `/ui/...`). */
  private def apiKeyGuard(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    cfg.apiKey match
      case None =>
        logger.warn(
          "REST API is OPEN: SL_QUACK_API_KEY is not set. Set it (or rely on the " +
          "UI login flow) before exposing the manager beyond localhost.")
        routes
      case Some(expected) =>
        logger.info("REST API X-API-Key enforcement enabled (static key + UI session tokens).")
        Kleisli { req =>
          val path = req.uri.path.toString
          if !path.startsWith("/api/") && path != "/api" then
            routes(req)
          else if isPublicApi(path) then
            routes(req)
          else
            val provided = req.headers
              .get(CIString("X-API-Key"))
              .map(_.head.value)
            val staticMatch  = provided.contains(expected)
            val sessionAdmin = provided.exists(sessions.isAdmin)
            if staticMatch || sessionAdmin then routes(req)
            else OptionT.pure[IO](Response[IO](Status.Unauthorized))
        }

  def serve: Resource[IO, org.http4s.server.Server] =
    val interpreter = Http4sServerInterpreter[IO]()

    val aclEndpoints: List[ServerEndpoint[Any, IO]] = acl.toList.flatMap { h =>
      // Only mount ACL endpoints when the relational ACL store is available
      // (i.e. stateStorage = postgres). File-mode deploys get a 404 here.
      List[ServerEndpoint[Any, IO]](
        Endpoints.createAclGrant.serverLogic(h.createGrant),
        Endpoints.listAclGrants.serverLogic(h.listGrants),
        Endpoints.deleteAclGrant.serverLogic(h.deleteGrant),
        Endpoints.uploadAclGrants.serverLogic(h.uploadGrants)
      )
    }

    val authEndpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      Endpoints.login.serverLogic(auth.login),
      Endpoints.logout.serverLogic(auth.logout),
      Endpoints.whoami.serverLogic(auth.whoami),
      Endpoints.statementHistory.serverLogic(statementHistory.recent)
    )

    val endpoints: List[ServerEndpoint[Any, IO]] = List[ServerEndpoint[Any, IO]](
      Endpoints.createPool.serverLogic(pools.createPool),
      Endpoints.scalePool.serverLogic(pools.scalePool),
      Endpoints.stopPool.serverLogic(pools.stopPool),
      Endpoints.listPools.serverLogic(_ => pools.listPools()),
      Endpoints.poolStatus.serverLogic((t, p) => pools.poolStatus(t, p)),
      Endpoints.setRole.serverLogic(nodes.setRole),
      Endpoints.setMaxConcurrent.serverLogic(nodes.setMaxConcurrent),
      Endpoints.quarantineNode.serverLogic(nodes.quarantineNode),
      Endpoints.restartNode.serverLogic(nodes.restartNode),
      Endpoints.createTenant.serverLogic(tenants.createTenant),
      Endpoints.listTenants.serverLogic(_ => tenants.listTenants()),
      Endpoints.setTenantMetastore.serverLogic(tenants.setTenantMetastore),
      Endpoints.deleteTenant.serverLogic(tenants.deleteTenant),
      Endpoints.health.serverLogic(_ => health.health),
      Endpoints.clientConfig.serverLogic(_ => IO.pure(Right(
        ClientConfigResponse(
          flightSqlHost = edgeCfg.host,
          flightSqlPort = edgeCfg.port,
          flightSqlTls  = edgeCfg.tlsEnabled,
          tenantClaim   = edgeCfg.tenantClaim,
          authEnabled   = authEnabled
        )
      )))
    ) ++ aclEndpoints ++ authEndpoints

    val apiRoutes: HttpRoutes[IO] = interpreter.toRoutes(endpoints)

    // Static UI from src/main/resources/ui, mounted at /ui/*.
    // resourceServiceBuilder("/ui") sets the classpath prefix; Router("/ui" -> ...)
    // sets the URL prefix.
    val uiAssets = staticcontent.resourceServiceBuilder[IO]("/ui").toRoutes
    // SPA fallback: any /ui/* path that doesn't match a real asset (incl. the
    // bare /ui/ directory request, and React Router routes like /ui/create) gets
    // index.html so the SPA can take over routing.
    val spaFallback: HttpRoutes[IO] = HttpRoutes.of[IO] { req =>
      StaticFile.fromResource[IO]("/ui/index.html", Some(req))
        .getOrElseF(IO.pure(Response[IO](Status.NotFound)))
    }
    val uiRoutes = Router("/ui" -> (uiAssets <+> spaFallback))

    EmberServerBuilder.default[IO]
      .withHost(Host.fromString(cfg.host).get)
      .withPort(Port.fromInt(cfg.port).get)
      .withHttpApp((apiKeyGuard(apiRoutes) <+> uiRoutes).orNotFound)
      .build