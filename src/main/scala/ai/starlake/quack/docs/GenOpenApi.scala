package ai.starlake.quack.docs

import ai.starlake.quack.edge.rest.RestEdgeEndpoints
import sttp.apispec.SecurityScheme
import sttp.apispec.openapi.{Components, OpenAPI}
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import scala.collection.immutable.ListMap

import java.nio.file.{Files, Paths}

/** Generates an OpenAPI 3 YAML document for the manager REST API from the Tapir endpoint
  * definitions. Pure: no server, no DB. Args: [outPath] [version].
  */
object GenOpenApi:

  /** Render the manager REST API as an OpenAPI 3 YAML document at the given version. */
  def render(version: String): String =
    val openapi = OpenAPIDocsInterpreter()
      .toOpenAPI(DocEndpoints.all, "Quack on Demand REST API", version)
    withRestEdgeBearer(openapi).toYaml

  /** Declares the REST data edge's PAT bearer on its operations (the `rest-edge` tag). The edge
    * reads `Authorization` raw rather than through a Tapir auth input (see `RestEdgeEndpoints`), so
    * the interpreter cannot infer the scheme; it is added here, to the document only.
    */
  private[docs] def withRestEdgeBearer(api: OpenAPI): OpenAPI =
    val scheme = SecurityScheme(
      `type` = "http",
      description = Some(
        "A personal access token (qod_pat_...). The only credential the REST data edge accepts."
      ),
      name = None,
      in = None,
      scheme = Some("bearer"),
      bearerFormat = Some("PAT"),
      flows = None,
      openIdConnectUrl = None
    )
    val requirement = ListMap(RestEdgeEndpoints.SecuritySchemeName -> Vector.empty[String])
    val items       = api.paths.pathItems.map { case (path, item) =>
      path -> item.copy(get = item.get.map { op =>
        if op.tags.contains(RestEdgeEndpoints.Tag) then op.security(List(requirement)) else op
      })
    }
    api
      .paths(api.paths.copy(pathItems = items))
      .components(
        api.components
          .getOrElse(Components.Empty)
          .addSecurityScheme(RestEdgeEndpoints.SecuritySchemeName, scheme)
      )

  def main(args: Array[String]): Unit =
    val out = args.headOption.getOrElse(
      sys.error(
        "usage: GenOpenApi <output path> [version] (sbt genOpenApi writes into starlake-docs)"
      )
    )
    val version = if args.length > 1 then args(1) else "0.0.0"
    val yaml    = render(version)
    val path    = Paths.get(out)
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(path, yaml)
    println(s"GenOpenApi: wrote ${DocEndpoints.all.size} endpoints to $out (version $version)")
