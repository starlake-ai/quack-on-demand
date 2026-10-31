package ai.starlake.quack.docs

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The node-to-manager routes (`/api/internal/...`: edge.authz.CredentialsRoute, DecideRoute) are
  * raw http4s routes mounted ahead of the api-key guard. Registering one as a Tapir endpoint would
  * put it behind the guard (every node call 401) and into the OpenAPI contract, CLI parity and the
  * MCP surface.
  */
class InternalRoutesSpec extends AnyFlatSpec with Matchers:

  "the documented REST surface" should "hold no /api/internal/ route" in {
    DocEndpoints.all.map(DocEndpoints.routeKey).filter(_.contains("/api/internal/")) shouldBe empty
  }
