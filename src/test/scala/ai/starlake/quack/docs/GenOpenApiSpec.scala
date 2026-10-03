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

  "the REST data edge operations" should "require the PAT bearer scheme" in {
    val rest = operations.filter(_._2.tags.contains(RestEdgeEndpoints.Tag))
    rest.map(_._1).sorted shouldBe List(
      "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas",
      "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables",
      "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}",
      "/api/v1/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}/rows"
    )
    rest.foreach { case (_, op) =>
      op.security shouldBe List(ListMap(RestEdgeEndpoints.SecuritySchemeName -> Vector.empty))
    }
    val scheme = api.components
      .flatMap(_.securitySchemes.get(RestEdgeEndpoints.SecuritySchemeName))
      .flatMap(_.toOption)
      .getOrElse(fail("no security scheme"))
    (scheme.`type`, scheme.scheme) shouldBe ("http", Some("bearer"))
  }

  "every other operation" should "keep the security it had" in
    operations.filterNot(_._2.tags.contains(RestEdgeEndpoints.Tag)).foreach { case (path, op) =>
      withClue(path)(op.security shouldBe empty)
    }
