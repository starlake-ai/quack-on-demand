package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The caller value the executor takes. It exists so a restriction cannot be dropped silently:
  * adding a call site without deciding on a restriction is a compile error.
  */
class ExecCallerSpec extends AnyFlatSpec with Matchers:

  "ExecCaller.unrestricted" should "carry the identity and no restriction" in {
    val c = ExecCaller.unrestricted("conn-1", "alice")
    c.connectionId shouldBe "conn-1"
    c.identity shouldBe "alice"
    c.restriction shouldBe TokenRestriction.Unrestricted
  }

  "effectiveMaxRows" should "take the smallest of the server cap, the token cap and the request" in {
    val capped = ExecCaller
      .unrestricted("c", "u")
      .copy(restriction = TokenRestriction.Unrestricted.copy(maxRows = Some(20)))
    capped.effectiveMaxRows(serverCap = 500, requested = 100) shouldBe 20
    capped.effectiveMaxRows(serverCap = 10, requested = 100) shouldBe 10
    ExecCaller.unrestricted("c", "u").effectiveMaxRows(500, 100) shouldBe 100
  }
