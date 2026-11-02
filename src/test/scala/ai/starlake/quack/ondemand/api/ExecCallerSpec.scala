package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.rbac.AuthzRequest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The caller value the executor takes. It exists so a restriction cannot be dropped silently:
  * adding a call site without deciding on a restriction is a compile error. The caller's realm
  * (`superuserAdmissible`) is required the same way.
  */
class ExecCallerSpec extends AnyFlatSpec with Matchers:

  "ExecCaller.unrestricted" should "carry the identity, no restriction and the realm it is given" in {
    val c = ExecCaller.unrestricted("conn-1", "alice", superuserAdmissible = false)
    c.connectionId shouldBe "conn-1"
    c.identity shouldBe "alice"
    c.restriction shouldBe TokenRestriction.Unrestricted
    c.superuserAdmissible shouldBe false
    c.system shouldBe false
    ExecCaller
      .unrestricted("conn-1", "root", superuserAdmissible = true)
      .superuserAdmissible shouldBe true
  }

  "ExecCaller.system" should "mark a trusted internal caller as system and superuser-admissible" in {
    val c = ExecCaller.system("conn-1")
    c.system shouldBe true
    c.superuserAdmissible shouldBe true
    c.identity shouldBe CatalogPreviewHandlers.SuperuserIdentity
    c.restriction shouldBe TokenRestriction.Unrestricted
  }

  it should "default to the FlightSQL source, the MCP OPA edge and no preferred node" in {
    // Every call site that predates the REST data edge keeps its audit origin, OPA tag and routing.
    val c = ExecCaller.unrestricted("conn-1", "alice", superuserAdmissible = false)
    c.source shouldBe "flightsql"
    c.edge shouldBe "mcp"
    c.preferredNode shouldBe None
  }

  it should "carry no JWT roles, groups or claims by default" in {
    // Only a caller holding a verified bearer JWT sets these; every other call site is unchanged.
    val c = ExecCaller.unrestricted("conn-1", "alice", superuserAdmissible = false)
    (c.jwtRoles, c.jwtGroups, c.jwtClaims) shouldBe (Set.empty, Set.empty, Map.empty)
  }

  it should "hand its JWT roles, groups and claims to the handshake request" in {
    val c = ExecCaller
      .unrestricted("conn-1", "alice", superuserAdmissible = false)
      .copy(jwtRoles = Set("r"), jwtGroups = Set("g"), jwtClaims = Map("sub" -> "alice"))
    val req = c.authzRequest(ai.starlake.quack.model.PoolKey("acme", "acme_db", "p"))
    (req.jwtRoles, req.jwtGroups, req.jwtClaims) shouldBe (Set("r"), Set("g"), Map("sub" -> "alice"))
    req.superuserAdmissible shouldBe false
  }

  "effectiveMaxRows" should "take the smallest of the server cap, the token cap and the request" in {
    val capped = ExecCaller
      .unrestricted("c", "u", superuserAdmissible = false)
      .copy(restriction = TokenRestriction.Unrestricted.copy(maxRows = Some(20)))
    capped.effectiveMaxRows(serverCap = 500, requested = 100) shouldBe 20
    capped.effectiveMaxRows(serverCap = 10, requested = 100) shouldBe 10
    ExecCaller
      .unrestricted("c", "u", superuserAdmissible = false)
      .effectiveMaxRows(500, 100) shouldBe 100
  }

  "authzRequest" should "ask the handshake as the caller, with its token restriction, on the mcp edge" in {
    val restriction = TokenRestriction.Unrestricted.copy(roles = Some(Set("analyst")))
    val caller      = ExecCaller(
      "profile-pools",
      "alice",
      restriction,
      superuserAdmissible = false,
      patId = Some("pat-1")
    )
    caller.authzRequest(PoolKey("acme", "acme_tpch1", "bi")) shouldBe
      AuthzRequest(
        "acme",
        "bi",
        "alice",
        superuserAdmissible = false,
        edge = "mcp",
        restriction = restriction
      )
  }
