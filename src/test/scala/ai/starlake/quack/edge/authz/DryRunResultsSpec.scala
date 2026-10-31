package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.opa.Decision as PolicyDecision
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicLong

class DryRunResultsSpec extends AnyFlatSpec with Matchers:

  private val In = Json.obj("input" -> Json.obj("kind" -> Json.fromString("statement")))

  private def outcome(decision: String): DryRunOutcome =
    DryRunOutcome("statement", In, decision, None, Some("d-1"))

  "DryRunResults" should "give a recorded outcome back once, by its nonce" in {
    val r = new DryRunResults()
    r.put("n-1", outcome("allow"))
    r.take("n-2") shouldBe None
    r.take("n-1") shouldBe Some(outcome("allow"))
    r.take("n-1") shouldBe None
  }

  it should "keep the last outcome recorded under a nonce" in {
    val r = new DryRunResults()
    r.put("n-1", outcome("allow"))
    r.put("n-1", outcome("deny"))
    r.take("n-1") shouldBe Some(outcome("deny"))
  }

  it should "forget an outcome RetentionMs after it was recorded" in {
    val clock = new AtomicLong(1_000L)
    val r     = new DryRunResults(clock = () => clock.get())
    r.put("n-1", outcome("allow"))
    r.put("n-2", outcome("allow"))
    clock.set(1_000L + DryRunResults.RetentionMs)
    r.take("n-1") shouldBe Some(outcome("allow"))
    clock.set(1_000L + DryRunResults.RetentionMs + 1)
    r.take("n-2") shouldBe None
  }

  it should "hold at most maxEntries outcomes" in {
    val r = new DryRunResults(maxEntries = 2)
    (1 to 50).foreach(i => r.put(s"n-$i", outcome("allow")))
    r.size should be <= 2L
  }

  "DryRunOutcome.of" should "report OPA's answer as the dry run shows it" in {
    DryRunOutcome.of("statement", In, PolicyDecision.Allow(Some("d-1"))) shouldBe
      DryRunOutcome("statement", In, "allow", None, Some("d-1"))
    DryRunOutcome.of("statement", In, PolicyDecision.Deny("no", Set.empty, None)) shouldBe
      DryRunOutcome("statement", In, "deny", Some("no"), None)
    DryRunOutcome.of("connect", In, PolicyDecision.Error("OPA returned HTTP 500")) shouldBe
      DryRunOutcome("connect", In, "error", Some("OPA returned HTTP 500"), None)
  }
