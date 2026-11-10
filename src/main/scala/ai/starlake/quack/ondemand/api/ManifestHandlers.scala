package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.manifest.{
  ConfigManifest,
  ManifestExporter,
  ManifestImporter,
  RbacManifest
}
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

  /** Keeps key order, so equal RBAC prints byte for byte equal. */
  private val RbacYaml = Printer.builder.withDropNullKeys(true).withPreserveOrder(true).build()

  /** Manifest export / import are cross-tenant operations: a dump or replay touches every tenant's
    * pools, roles, groups and users. Restricted to superusers; every other caller is rejected with
    * 403.
    */
  private def superuserCheck(apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Option[(StatusCode, ErrorResponse)] =
    SuperuserCheck.reject(apiKey, "manifest export/import is restricted to superusers")(scopeOf)

  def exportYaml(
      apiKey: Option[String],
      mode: Option[String] = None,
      tenant: Option[String] = None
  )(
      scopeOf: SessionScope.Resolver
  ): IO[Either[(StatusCode, ErrorResponse), String]] = IO.blocking {
    superuserCheck(apiKey)(scopeOf) match
      case Some(err) => Left(err)
      case None      =>
        rbacTarget(mode, tenant).flatMap { target =>
          ManifestExporter.build(store, Instant.now, managerVersion, hostname, federatedStore) match
            // Same code and status as SupervisorError.ReservedName (SupervisorErrorHttp).
            case Left(refused) =>
              Left(StatusCode.BadRequest -> ErrorResponse("reserved_name", refused.message))
            case Right(m) =>
              Right(target match
                case None    => Yaml.pretty(m.asJson)
                case Some(t) => RbacYaml.pretty(RbacManifest.toJson(RbacManifest.of(m, t, store))))
        }
  }

  /** The tenant of a `mode=rbac` request, `None` for a full manifest request. */
  private def rbacTarget(
      mode: Option[String],
      tenant: Option[String]
  ): Either[(StatusCode, ErrorResponse), Option[Tenant]] =
    def refuse(code: String, message: String) =
      Left(StatusCode.BadRequest -> ErrorResponse(code, message))
    mode match
      case None                            => Right(None)
      case Some(ManifestHandlers.RbacMode) =>
        tenant match
          case None       => refuse("rbac_tenant_required", "mode=rbac needs a tenant")
          case Some(name) =>
            store.listTenants().find(t => t.id == name || t.displayName == name) match
              case Some(t) => Right(Some(t))
              case None    => refuse("unknown_tenant", s"no tenant '$name'")
      case Some(other) => refuse("invalid_mode", s"unknown mode '$other'")

  private def refusalOf(r: RbacManifest.Refusal): (StatusCode, ErrorResponse) =
    StatusCode.BadRequest -> ErrorResponse(r.code, r.message)

  def importYaml(
      body: String,
      apiKey: Option[String],
      mode: Option[String] = None,
      tenant: Option[String] = None
  )(
      scopeOf: SessionScope.Resolver
  ): IO[Either[(StatusCode, ErrorResponse), ManifestImportSummary]] =
    IO.blocking {
      superuserCheck(apiKey)(scopeOf) match
        case Some(err) =>
          audit.rest(apiKey, "control-plane", AuditActions.ManifestImport, "denied")
          Left(err)
        case None =>
          rbacTarget(mode, tenant).flatMap {
            case Some(target) => importRbac(body, target, apiKey)
            case None         => importFull(body, apiKey)
          }
    }

  private def importRbac(
      body: String,
      target: Tenant,
      apiKey: Option[String]
  ): Either[(StatusCode, ErrorResponse), ManifestImportSummary] =
    for
      json <- parser.parse(body).left.map { e =>
        StatusCode.BadRequest -> ErrorResponse("invalid-yaml", e.getMessage)
      }
      bundle <- RbacManifest.parse(json).left.map(refusalOf)
      _      <- RbacManifest.refusal(bundle, target, store).map(refusalOf).toLeft(())
      _      <- ManifestImporter.applyRbac(bundle, target.id, store).left.map { errs =>
        StatusCode.BadRequest -> ErrorResponse("invalid-manifest", errs.mkString("; "))
      }
    yield importDone(bundle.toManifest(target.id), apiKey, Map("mode" -> ManifestHandlers.RbacMode))

  private def importFull(
      body: String,
      apiKey: Option[String]
  ): Either[(StatusCode, ErrorResponse), ManifestImportSummary] =
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
          case Right(_) => Right(importDone(m, apiKey))

  /** After a committed import: reload caches, tell the replicas, record it. */
  private def importDone(
      m: ConfigManifest,
      apiKey: Option[String],
      extraDetail: Map[String, String] = Map.empty
  ): ManifestImportSummary =
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
      ) ++ extraDetail
    )
    summary

object ManifestHandlers:
  /** `mode` of the manifest routes selecting one tenant's RBAC subset. */
  val RbacMode: String = "rbac"
