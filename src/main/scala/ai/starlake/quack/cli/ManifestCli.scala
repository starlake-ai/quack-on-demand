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
    val m = ManifestExporter.build(store, Instant.now, managerVersion = "cli", hostname = "cli")
    out.print(Yaml.pretty(m.asJson))
    0

  def importFrom(store: ControlPlaneStore, in: InputStream): Int =
    val body = new String(in.readAllBytes(), StandardCharsets.UTF_8)
    parser.parse(body).flatMap(_.as[ConfigManifest]) match
      case Left(e) =>
        System.err.println(s"invalid yaml: ${e.getMessage}")
        1
      case Right(m) =>
        ManifestImporter.apply(m, store) match
          case Left(errs) =>
            errs.foreach(e => System.err.println(s"error: $e"))
            1
          case Right(_) =>
            System.err.println(
              s"applied: ${m.tenants.size} tenants, ${m.roles.size} roles, " +
                s"${m.groups.size} groups, ${m.users.size} users"
            )
            0
