package ai.starlake.quack.docs

import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import java.nio.file.{Files, Paths}

/** Generates an OpenAPI 3 YAML document for the manager REST API from the Tapir endpoint
  * definitions. Pure: no server, no DB. Args: [outPath] [version].
  */
object GenOpenApi:

  /** Render the manager REST API as an OpenAPI 3 YAML document at the given version. */
  def render(version: String): String =
    val openapi = OpenAPIDocsInterpreter()
      .toOpenAPI(DocEndpoints.all, "Quack on Demand REST API", version)
    openapi.toYaml

  def main(args: Array[String]): Unit =
    val out     = args.headOption.getOrElse("website/static/openapi.yaml")
    val version = if args.length > 1 then args(1) else "0.0.0"
    val yaml    = render(version)
    val path    = Paths.get(out)
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(path, yaml)
    println(s"GenOpenApi: wrote ${DocEndpoints.all.size} endpoints to $out (version $version)")
