package ai.starlake.quack.cli

import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestExporter, ManifestImporter}
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import io.circe.syntax.*
import io.circe.yaml.v12.{parser, Printer}

import java.io.{InputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.time.Instant

object ManifestCli:

  private val Yaml = Printer.builder.withDropNullKeys(true).build()

  def exportTo(store: ControlPlaneStore, out: PrintStream): Int =
    ManifestExporter.build(store, Instant.now, managerVersion = "cli", hostname = "cli") match
      case Left(refused) =>
        System.err.println(s"error: ${refused.message}")
        1
      case Right(m) =>
        out.print(Yaml.pretty(m.asJson))
        0

  /** `requireEncryption` mirrors `quack-on-demand.requireEncryption`: this offline path creates
    * tenant-db rows against the very same control plane the manager serves, so it honours the same
    * policy as database/create and the REST import.
    */
  def importFrom(
      store: ControlPlaneStore,
      in: InputStream,
      requireEncryption: Boolean,
      tenantFilesRoot: Option[String] = None
  ): Int =
    val body = new String(in.readAllBytes(), StandardCharsets.UTF_8)
    parser.parse(body).flatMap(_.as[ConfigManifest]) match
      case Left(e) =>
        System.err.println(s"invalid yaml: ${e.getMessage}")
        1
      case Right(m) =>
        ManifestImporter.apply(
          m,
          store,
          requireEncryption = requireEncryption,
          tenantFilesRoot = tenantFilesRoot
        ) match
          case Left(errs) =>
            errs.foreach(e => System.err.println(s"error: $e"))
            1
          case Right(_) =>
            System.err.println(
              s"applied: ${m.tenants.size} tenants, ${m.roles.size} roles, " +
                s"${m.groups.size} groups, ${m.users.size} users"
            )
            0
