package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestExporter, ManifestImporter}
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import cats.effect.IO
import io.circe.syntax.*
import io.circe.yaml.v12.{Printer, parser}
import sttp.model.StatusCode

import java.time.Instant

final class ManifestHandlers(
    store:          ControlPlaneStore,
    managerVersion: String,
    hostname:       String
):
  private val Yaml = Printer.builder.withDropNullKeys(true).build()

  def exportYaml: IO[Either[(StatusCode, ErrorResponse), String]] = IO.blocking {
    val m = ManifestExporter.build(store, Instant.now, managerVersion, hostname)
    Right(Yaml.pretty(m.asJson))
  }

  def importYaml(body: String): IO[Either[(StatusCode, ErrorResponse), ManifestImportSummary]] =
    IO.blocking {
      parser.parse(body).flatMap(_.as[ConfigManifest]) match
        case Left(e) =>
          Left(StatusCode.BadRequest -> ErrorResponse("invalid-yaml", e.getMessage))
        case Right(m) =>
          ManifestImporter.apply(m, store) match
            case Left(errs) =>
              Left(StatusCode.BadRequest -> ErrorResponse("invalid-manifest", errs.mkString("; ")))
            case Right(_) =>
              Right(ManifestImportSummary(
                tenants   = m.tenants.size,
                tenantDbs = m.tenants.map(_.tenantDbs.size).sum,
                pools     = m.tenants.map(_.pools.size).sum,
                roles     = m.roles.size,
                groups    = m.groups.size,
                users     = m.users.size
              ))
    }