package ai.starlake.quack.edge.adapter

import ai.starlake.quack.route.NodeLoad
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NodeLoadTrackerSpec extends AnyFlatSpec with Matchers:

  "NodeLoadTracker" should "start each node at empty load" in:
    val t = new NodeLoadTracker
    t.snapshot("n1") shouldBe NodeLoad.empty

  it should "increment in-flight on start, decrement on finish" in:
    val t = new NodeLoadTracker
    t.onStart("n1")
    t.onStart("n1")
    t.snapshot("n1").inFlight shouldBe 2
    t.onFinish("n1", latencyMs = 100)
    t.snapshot("n1").inFlight shouldBe 1

  it should "update EWMA on each finish (alpha=0.3)" in:
    val t = new NodeLoadTracker
    t.onStart("n1"); t.onFinish("n1", 100) // ewma seeded to 100
    t.onStart("n1"); t.onFinish("n1", 200) // 0.7*100 + 0.3*200 = 130
    t.snapshot("n1").ewmaMs shouldBe (130.0 +- 0.0001)

  it should "respect external healthy/draining flags" in:
    val t = new NodeLoadTracker
    t.setHealthy("n1", false)
    t.snapshot("n1").healthy shouldBe false
    t.setHealthy("n1", true); t.setDraining("n1", true)
    t.snapshot("n1").draining shouldBe true
    t.snapshot("n1").routable shouldBe false

  it should "expose a full map for PoolSnapshot construction" in:
    val t = new NodeLoadTracker
    t.onStart("n1"); t.onStart("n2"); t.onStart("n2")
    val all = t.snapshotAll
    all("n1").inFlight shouldBe 1
    all("n2").inFlight shouldBe 2