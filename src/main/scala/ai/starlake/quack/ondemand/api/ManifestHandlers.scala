package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestExporter, ManifestImporter}
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, FederatedSourceStore}
import cats.effect.IO
import io.circe.syntax.*
import io.circe.yaml.v12.{Printer, parser}
import sttp.model.StatusCode

import java.time.Instant

final class ManifestHandlers(
    store:          ControlPlaneStore,
    supervisor:     PoolSupervisor,
    managerVersion: String,
    hostname:       String,
    federatedStore: Option[FederatedSourceStore] = None
):
  private val Yaml = Printer.builder.withDropNullKeys(true).build()

  def exportYaml: IO[Either[(StatusCode, ErrorResponse), String]] = IO.blocking {
    val m = ManifestExporter.build(store, Instant.now, managerVersion, hostname, federatedStore)
    Right(Yaml.pretty(m.asJson))
  }

  def importYaml(body: String): IO[Either[(StatusCode, ErrorResponse), ManifestImportSummary]] =
    IO.blocking {
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
              Right(ManifestImportSummary(
                tenants   = m.tenants.size,
                tenantDbs = m.tenants.map(_.tenantDbs.size).sum,
                pools     = m.tenants.map(_.pools.size).sum,
                roles     = m.roles.size,
                groups    = m.groups.size,
                users     = m.users.size
              ))
    }