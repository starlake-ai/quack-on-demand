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

  it should "drop a removed node's latency ring with its load, keeping the other nodes'" in:
    val t = new NodeLoadTracker
    t.onStart("gone"); t.onFinish("gone", 100)
    t.onStart("live"); t.onFinish("live", 40)
    t.remove("gone")
    t.latencyPercentiles("gone") shouldBe ((0.0, 0.0, 0.0))
    t.trackedNodes shouldBe Set("live")
    t.latencyPercentiles("live")._1 shouldBe 40.0

  it should "create no entry when a node is only read" in:
    val t = new NodeLoadTracker
    t.snapshot("read-only") shouldBe NodeLoad.empty
    t.latencyPercentiles("read-only") shouldBe ((0.0, 0.0, 0.0))
    t.trackedNodes shouldBe empty

  // -- retain: what restore() trims on every replica -----------------------------

  it should "forget, on retain, every node neither live nor written since the mark, ring included" in:
    val t = new NodeLoadTracker
    Seq("a", "b", "c").foreach { n => t.onStart(n); t.onFinish(n, 100) }
    val m = t.mark()
    t.retain(live = Set("a"), since = m)
    t.trackedNodes shouldBe Set("a")
    t.snapshotAll.keySet shouldBe Set("a")
    t.latencyPercentiles("a") shouldBe ((100.0, 100.0, 100.0))
    t.latencyPercentiles("b") shouldBe ((0.0, 0.0, 0.0))

  it should "keep, on retain, a node not yet live whose quarantine was set after the mark" in:
    // restore() takes the mark before it reads the snapshot; a respawn re-applying a quarantine
    // after that read is absent from the snapshot, yet its quarantine must survive the trim.
    val t = new NodeLoadTracker
    t.onStart("old"); t.onFinish("old", 5)
    val m = t.mark()
    t.setQuarantined("fresh", true)
    t.retain(live = Set.empty, since = m)
    t.snapshot("fresh").quarantined shouldBe true
    t.snapshot("fresh").routable shouldBe false
    t.trackedNodes shouldBe Set("fresh")
    // The next trim, with no write since its own mark, forgets a node that never got listed.
    t.retain(live = Set.empty, since = t.mark())
    t.trackedNodes shouldBe empty

  it should "keep, on retain, a node not live while a statement on it is still in flight" in:
    val t = new NodeLoadTracker
    t.onStart("busy")
    t.onStart("idle"); t.onFinish("idle", 5)
    t.retain(live = Set.empty, since = t.mark())
    t.trackedNodes shouldBe Set("busy")
    t.snapshot("busy").inFlight shouldBe 1
    t.onFinish("busy", 7)
    t.retain(live = Set.empty, since = t.mark())
    t.trackedNodes shouldBe empty
