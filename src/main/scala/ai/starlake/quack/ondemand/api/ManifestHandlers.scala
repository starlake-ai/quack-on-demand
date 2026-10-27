package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestExporter, ManifestImporter}
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, FederatedSourceStore}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
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
    federatedStore: Option[FederatedSourceStore] = None,
    audit: AuditRecorder = AuditRecorder.noop,
    /** Mirrors `quack-on-demand.requireEncryption`, the same knob `TenantDbHandlers` applies to
      * database/create. An import is the other way a tenant-db row is created, so it has to honour
      * the policy or the deployment-wide guarantee is not one.
      */
    requireEncryption: Boolean,
    /** QOD_TENANT_FILES_ROOT: an imported local location must lie under it, as on location/add. */
    tenantFilesRoot: Option[String] = None
):
  private val Yaml = Printer.builder.withDropNullKeys(true).build()

  /** Manifest export / import are cross-tenant operations: a dump or replay touches every tenant's
    * pools, roles, groups and users. Restricted to superusers; every other caller is rejected with
    * 403.
    */
  private def superuserCheck(apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Option[(StatusCode, ErrorResponse)] =
    SuperuserCheck.reject(apiKey, "manifest export/import is restricted to superusers")(scopeOf)

  def exportYaml(apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): IO[Either[(StatusCode, ErrorResponse), String]] = IO.blocking {
    superuserCheck(apiKey)(scopeOf) match
      case Some(err) => Left(err)
      case None      =>
        ManifestExporter.build(store, Instant.now, managerVersion, hostname, federatedStore) match
          // Same code and status as SupervisorError.ReservedName (SupervisorErrorHttp).
          case Left(refused) =>
            Left(StatusCode.BadRequest -> ErrorResponse("reserved_name", refused.message))
          case Right(m) => Right(Yaml.pretty(m.asJson))
  }

  def importYaml(body: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): IO[Either[(StatusCode, ErrorResponse), ManifestImportSummary]] =
    IO.blocking {
      superuserCheck(apiKey)(scopeOf) match
        case Some(err) =>
          audit.rest(apiKey, "control-plane", AuditActions.ManifestImport, "denied")
          Left(err)
        case None =>
          parser
            .parse(body)
            .flatMap(_.as[ConfigManifest])
            .map(m => (m, ManifestImporter.metastoreKeyErrors(m))) match
            case Left(e) =>
              Left(StatusCode.BadRequest -> ErrorResponse("invalid-yaml", e.getMessage))
            // The refusal database/create answers, with its code, before anything is written
            // (apply's own validation refuses it too, for the importers that call it directly).
            case Right((_, keyErrors)) if keyErrors.nonEmpty =>
              Left(
                StatusCode.BadRequest -> ErrorResponse(
                  "invalid_metastore_key",
                  keyErrors.mkString("; ")
                )
              )
            case Right((m, _)) =>
              ManifestImporter.apply(
                m,
                store,
                federatedStore,
                requireEncryption,
                tenantFilesRoot
              ) match
                case Left(errs) =>
                  Left(
                    StatusCode.BadRequest -> ErrorResponse("invalid-manifest", errs.mkString("; "))
                  )
                case Right(_) =>
                  // Reload the supervisor's in-memory caches (tenants, tenant-dbs,
                  // pools, RBAC effective sets) from the freshly-written store
                  // snapshot. Without this the REST/UI keeps serving the old
                  // view until the manager is restarted.
                  supervisor.restore()
                  // Notify peer replicas. restore() is deliberately local-only,
                  // so without this broadcast, HA peers stay stale until their
                  // 30s periodic refresh fires.
                  supervisor.broadcastStateChanged()
                  val summary = ManifestImportSummary(
                    tenants = m.tenants.size,
                    tenantDbs = m.tenants.map(_.tenantDbs.size).sum,
                    pools = m.tenants.map(_.pools.size).sum,
                    roles = m.roles.size,
                    groups = m.groups.size,
                    users = m.users.size
                  )
                  // Record counts only in detail (no YAML body, no secret values).
                  audit.rest(
                    apiKey,
                    "control-plane",
                    AuditActions.ManifestImport,
                    "ok",
                    detail = Map(
                      "tenants"   -> summary.tenants.toString,
                      "tenantDbs" -> summary.tenantDbs.toString,
                      "pools"     -> summary.pools.toString,
                      "roles"     -> summary.roles.toString,
                      "groups"    -> summary.groups.toString,
                      "users"     -> summary.users.toString
                    )
                  )
                  Right(summary)
    }
