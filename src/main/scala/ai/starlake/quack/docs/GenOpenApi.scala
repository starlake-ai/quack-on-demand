package ai.starlake.quack.docs

import ai.starlake.quack.edge.rest.{RestEdgeEndpoints, RestFormat}
import sttp.apispec.{Schema, SchemaType, SecurityScheme}
import sttp.apispec.openapi.{Components, MediaType, OpenAPI, ResponsesCodeKey}
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
    withRestEdgeMediaTypes(withRestEdgeBearer(openapi)).toYaml

  /** Lists the media types each REST data edge operation answers with on 200. The routes declare a
    * streamed binary body (one Tapir output serves every format), which the interpreter documents
    * as `application/octet-stream`; the real types are the formats the route negotiates, so they
    * are written here, to the document only: JSON and CSV everywhere, and the streamed formats on
    * `/rows`.
    */
  private[docs] def withRestEdgeMediaTypes(api: OpenAPI): OpenAPI =
    def schemaFor(format: RestFormat, detail: Boolean): Schema = format match
      case RestFormat.Json if detail => Schema(SchemaType.Object)
      case RestFormat.Json => Schema(SchemaType.Array).copy(items = Some(Schema(SchemaType.Object)))
      case RestFormat.Csv  => Schema(SchemaType.String)
      case _               => Schema(SchemaType.String).copy(format = Some("binary"))
    val items = api.paths.pathItems.map { case (path, item) =>
      path -> item.copy(get = item.get.map { op =>
        if !op.tags.contains(RestEdgeEndpoints.Tag) then op
        else
          val formats =
            if path.endsWith("/rows") then RestFormat.Rows.toList else RestFormat.Documents.toList
          val content = ListMap.from(
            formats.sortBy(_.ordinal).map { f =>
              f.contentType.takeWhile(_ != ';') ->
                MediaType(schema = Some(schemaFor(f, path.endsWith("{table}"))))
            }
          )
          op.copy(responses = op.responses.copy(responses = op.responses.responses.map {
            case (ResponsesCodeKey(200), Right(ok)) =>
              ResponsesCodeKey(200) -> Right(ok.copy(content = content))
            case other => other
          }))
      })
    }
    api.paths(api.paths.copy(pathItems = items))

  /** Declares the REST data edge's two bearers on its operations (the `rest-edge` tag), as
    * alternatives: a PAT, or a JWT of the path tenant's own OIDC provider. The edge reads
    * `Authorization` raw rather than through a Tapir auth input (see `RestEdgeEndpoints`), so the
    * interpreter cannot infer the schemes; they are added here, to the document only.
    */
  private[docs] def withRestEdgeBearer(api: OpenAPI): OpenAPI =
    def bearer(format: String, description: String) = SecurityScheme(
      `type` = "http",
      description = Some(description),
      name = None,
      in = None,
      scheme = Some("bearer"),
      bearerFormat = Some(format),
      flows = None,
      openIdConnectUrl = None
    )
    val pat = bearer(
      "PAT",
      "A personal access token (qod_pat_...) of a user of the path tenant; a token restricted on " +
        "its tools axis must list `rest`."
    )
    val oidc = bearer(
      "JWT",
      "An ID token of the path tenant's own OIDC provider (or an access token whose audience is " +
        "the tenant's client id), verified by that provider only; it must carry `exp` and name a " +
        "user provisioned and enabled in the tenant."
    )
    // Two requirements in one list: either scheme alone satisfies the operation.
    val requirements = List(
      ListMap(RestEdgeEndpoints.SecuritySchemeName     -> Vector.empty[String]),
      ListMap(RestEdgeEndpoints.OidcSecuritySchemeName -> Vector.empty[String])
    )
    val items = api.paths.pathItems.map { case (path, item) =>
      path -> item.copy(get = item.get.map { op =>
        if op.tags.contains(RestEdgeEndpoints.Tag) then op.security(requirements) else op
      })
    }
    api
      .paths(api.paths.copy(pathItems = items))
      .components(
        api.components
          .getOrElse(Components.Empty)
          .addSecurityScheme(RestEdgeEndpoints.SecuritySchemeName, pat)
          .addSecurityScheme(RestEdgeEndpoints.OidcSecuritySchemeName, oidc)
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
