package ai.starlake.quack.docs

import ai.starlake.quack.edge.rest.RestEdgeEndpoints
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import scala.collection.immutable.ListMap

/** The document-only bearer declaration of the REST data edge. */
class GenOpenApiSpec extends AnyFlatSpec with Matchers:

  private val api = GenOpenApi.withRestEdgeBearer(
    OpenAPIDocsInterpreter().toOpenAPI(DocEndpoints.all, "t", "0.0.0")
  )

  private val operations =
    api.paths.pathItems.toList.flatMap { case (path, item) => item.get.map(path -> _) }

  "the REST data edge operations" should "accept the PAT bearer or the tenant-OIDC bearer" in {
    val rest = operations.filter(_._2.tags.contains(RestEdgeEndpoints.Tag))
    rest.map(_._1).sorted shouldBe List(
      "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas",
      "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables",
      "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}",
      "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}/rows"
    )
    rest.foreach { case (_, op) =>
      op.security shouldBe List(
        ListMap(RestEdgeEndpoints.SecuritySchemeName     -> Vector.empty),
        ListMap(RestEdgeEndpoints.OidcSecuritySchemeName -> Vector.empty)
      )
    }
    def scheme(name: String) = api.components
      .flatMap(_.securitySchemes.get(name))
      .flatMap(_.toOption)
      .getOrElse(fail(s"no security scheme $name"))
    val pat  = scheme(RestEdgeEndpoints.SecuritySchemeName)
    val oidc = scheme(RestEdgeEndpoints.OidcSecuritySchemeName)
    (pat.`type`, pat.scheme, pat.bearerFormat) shouldBe ("http", Some("bearer"), Some("PAT"))
    (oidc.`type`, oidc.scheme, oidc.bearerFormat) shouldBe ("http", Some("bearer"), Some("JWT"))
  }

  "every other operation" should "keep the security it had" in
    operations.filterNot(_._2.tags.contains(RestEdgeEndpoints.Tag)).foreach { case (path, op) =>
      withClue(path)(op.security shouldBe empty)
    }
