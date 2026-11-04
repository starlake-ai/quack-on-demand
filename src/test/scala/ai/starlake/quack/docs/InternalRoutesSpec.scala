package ai.starlake.quack.docs

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The node-to-manager routes (`/api/internal/...`: edge.authz.CredentialsRoute, DecideRoute) are
  * raw http4s routes served by the node verdict listener (edge.authz.NodeInternalServer), not by
  * the REST port. Registering one as a Tapir endpoint would put it on the REST port, behind the
  * guard, and into the OpenAPI contract, CLI parity and the MCP surface.
  */
class InternalRoutesSpec extends AnyFlatSpec with Matchers:

  "the documented REST surface" should "hold no /api/internal/ route" in {
    DocEndpoints.all.map(DocEndpoints.routeKey).filter(_.contains("/api/internal/")) shouldBe empty
  }
