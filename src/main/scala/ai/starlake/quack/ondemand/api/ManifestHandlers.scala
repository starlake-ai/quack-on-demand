package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestExporter, ManifestImporter}
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, FederatedSourceStore}
import cats.effect.IO
import io.circe.syntax.*
import io.circe.yaml.v12.{parser, Printer}
import sttp.model.StatusCode

import java.time.Instant

final class ManifestHandlers(
    store: ControlPlaneStore,
    supervisor: PoolSupervisor,
    managerVersion: String,
    hostname: String,
    federatedStore: Option[FederatedSourceStore] = None
):
  private val Yaml = Printer.builder.withDropNullKeys(true).build()

  /** Manifest export / import are cross-tenant operations -- a dump or replay touches every
    * tenant's pools, roles, groups and users. Restricted to superusers; non-superuser sessions are
    * rejected with 403. Static `QOD_API_KEY` callers (no session row) are admitted.
    */
  private def superuserCheck(apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Option[(StatusCode, ErrorResponse)] =
    apiKey.flatMap(scopeOf) match
      case Some(s) if !s.superuser =>
        Some(
          StatusCode.Forbidden ->
            ErrorResponse(
              "superuser_required",
              "manifest export/import is restricted to superusers"
            )
        )
      case _ => None

  def exportYaml(apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): IO[Either[(StatusCode, ErrorResponse), String]] = IO.blocking {
    superuserCheck(apiKey)(scopeOf) match
      case Some(err) => Left(err)
      case None =>
        val m = ManifestExporter.build(store, Instant.now, managerVersion, hostname, federatedStore)
        Right(Yaml.pretty(m.asJson))
  }

  def importYaml(body: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): IO[Either[(StatusCode, ErrorResponse), ManifestImportSummary]] =
    IO.blocking {
      superuserCheck(apiKey)(scopeOf) match
        case Some(err) => Left(err)
        case None =>
          parser.parse(body).flatMap(_.as[ConfigManifest]) match
            case Left(e) =>
              Left(StatusCode.BadRequest -> ErrorResponse("invalid-yaml", e.getMessage))
            case Right(m) =>
              ManifestImporter.apply(m, store, federatedStore) match
                case Left(errs) =>
                  Left(StatusCode.BadRequest -> ErrorResponse("invalid-manifest", errs.mkString("; ")))
                case Right(_) =>
                  // Reload the supervisor's in-memory caches (tenants, tenant-dbs,
                  // pools, RBAC effective sets) from the freshly-written store
                  // snapshot. Without this the REST/UI keeps serving the old
                  // view until the manager is restarted.
                  supervisor.restore()
                  Right(
                    ManifestImportSummary(
                      tenants = m.tenants.size,
                      tenantDbs = m.tenants.map(_.tenantDbs.size).sum,
                      pools = m.tenants.map(_.pools.size).sum,
                      roles = m.roles.size,
                      groups = m.groups.size,
                      users = m.users.size
                    )
                  )
    }
