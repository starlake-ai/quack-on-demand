package ai.starlake.quack.edge.adapter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NodeLoadTrackerQuarantineSpec extends AnyFlatSpec with Matchers:

  "setHealthy(true)" should "not clear an operator quarantine" in:
    val t = new NodeLoadTracker()
    t.setQuarantined("n1", true)
    t.setHealthy("n1", true)
    t.snapshot("n1").quarantined shouldBe true
    t.snapshot("n1").routable shouldBe false

  "setQuarantined(false)" should "restore routability on a healthy node" in:
    val t = new NodeLoadTracker()
    t.setQuarantined("n1", true)
    t.setQuarantined("n1", false)
    t.snapshot("n1").routable shouldBe true

  "onFinish" should "preserve the quarantine flag through load updates" in:
    val t = new NodeLoadTracker()
    t.setQuarantined("n1", true)
    t.onStart("n1")
    t.onFinish("n1", 42L)
    t.snapshot("n1").quarantined shouldBe true
