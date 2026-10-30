package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.time.Instant
import java.util.concurrent.{CountDownLatch, Executors}
import scala.util.Try

/** Test harness over one store: `backdate` moves a server's last heartbeat into the past. */
trait FleetStoreHarness:
  def store: FleetServerStore
  def backdate(name: String, seconds: Long): Unit

  /** Moves a server's claimed_at `seconds` into the past (the store clock advancing on a claim). */
  def backdateClaim(name: String, seconds: Long): Unit

  /** Clears an approved server's source binding, as the 0042 upgrade leaves pre-existing rows. */
  def unbind(name: String): Unit

trait FleetServerStoreBehaviour { this: AnyFlatSpec & Matchers =>

  def hb(
      name: String,
      host: String = "10.0.0.1",
      port: Int = 21900,
      memoryBytes: Option[Long] = Some(64L << 30),
      node: NodeReport = NodeReport(0, None, "none", None, None, None),
      autoApprove: Boolean = true,
      source: Option[String] = Some("10.0.0.1")
  ): Heartbeat =
    Heartbeat(
      name,
      host,
      port,
      Some("0.9.7"),
      Some("linux"),
      Some("1.5.5"),
      Some(16),
      memoryBytes,
      node,
      sourceAddr = source,
      autoApprove = autoApprove
    )

  def assignment(nodeId: String): FleetAssignment =
    FleetAssignment(
      0,
      nodeId,
      PoolKey("acme", "db", "bi"),
      21900,
      "tok",
      "memory",
      Map("pgHost" -> "h"),
      "",
      "",
      ""
    )

  def storeBehaviour(withStore: (FleetStoreHarness => Unit) => Unit): Unit =

    it should "insert on the first heartbeat and update afterwards, silentSeconds from the store clock" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a")) shouldBe HeartbeatOutcome.Joined
        val joinedAt = s.get("a").get.joinedAt
        s.recordHeartbeat(hb("a")) shouldBe HeartbeatOutcome.Updated
        val row = s.get("a").get
        row.joinedAt shouldBe joinedAt // an update never moves the join time
        row.nodeState shouldBe "none"
        row.silentSeconds should be < 5L
        row.cpus shouldBe Some(16)
        h.backdate("a", 120)
        s.get("a").get.silentSeconds should be >= 120L
    }

    it should "claim the oldest reachable schedulable free server exactly once under contention" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("early")); h.backdate("early", 1) // joined first
        s.recordHeartbeat(hb("late"))
        s.recordHeartbeat(hb("stale")); h.backdate("stale", 120)
        s.recordHeartbeat(hb("drained")); s.setUnschedulable("drained", true) shouldBe true
        val pool           = Executors.newFixedThreadPool(2)
        val latch          = new CountDownLatch(1)
        def go(id: String) = pool.submit { () => latch.await(); s.claim(assignment(id), 30, None) }
        val (f1, f2)       = (go("n1"), go("n2"))
        latch.countDown()
        val claimed = List(f1.get(), f2.get()).flatMap(_.toOption).map(_.name)
        pool.shutdown()
        claimed.toSet shouldBe Set("early", "late")
        s.claim(assignment("n3"), 30, None) shouldBe Left(ClaimMiss.NoneFree)
        s.get("early").get.assignedNodeId should (be(Some("n1")) or be(Some("n2")))
        s.get("early").get.assignmentEpoch shouldBe 1L
        s.get("early").get.assignment.map(_.epoch) shouldBe Some(1L)
        s.get("early").get.claimedAt shouldBe defined
    }

    it should "apply the memory-fit predicate and report NoneFits" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("small", memoryBytes = Some(16L << 30)))
      s.recordHeartbeat(hb("unknown", memoryBytes = None))
      s.claim(assignment("n1"), 30, Some(64L << 30)).map(_.name) shouldBe Right(
        "unknown"
      ) // no capacity = eligible
      s.claim(assignment("n2"), 30, Some(64L << 30)) shouldBe Left(ClaimMiss.NoneFits)
      s.claim(assignment("n2"), 30, Some(8L << 30)).map(_.name) shouldBe Right("small")
    }

    it should "report claimAgeSeconds from the store clock, None when free" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a"))
      s.get("a").get.claimAgeSeconds shouldBe None
      s.claim(assignment("n1"), 30, None).isRight shouldBe true
      s.get("a").get.claimAgeSeconds.get should be < 5L
      h.backdateClaim("a", 120)
      s.get("a").get.claimAgeSeconds.get should be >= 120L
      s.byNodeId("n1").get.claimAgeSeconds.get should be >= 120L
      s.list().head.claimAgeSeconds.get should be >= 120L
      s.release("n1") shouldBe Some("a")
      s.get("a").get.claimAgeSeconds shouldBe None
    }

    it should "stamp the server's node_port into the claimed assignment" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a", port = 21977))
      val claimed = s.claim(assignment("n1").copy(port = 0), 30, None).toOption.get
      claimed.assignment.map(_.port) shouldBe Some(21977)
      s.get("a").get.assignment.map(_.port) shouldBe Some(21977)
    }

    it should "release by node id, bump the epoch and clear claimedAt" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a"))
      s.claim(assignment("n1"), 30, None).map(_.name) shouldBe Right("a")
      s.release("n1") shouldBe Some("a")
      val row = s.get("a").get
      (row.assignedNodeId, row.assignment, row.assignmentEpoch, row.claimedAt) shouldBe (
        None,
        None,
        2L,
        None
      )
      s.release("n1") shouldBe None
    }

    it should "claimReplacing release the old holder only when a replacement exists" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("old"))
        s.claim(assignment("n1"), 30, None).map(_.name) shouldBe Right("old")
        h.backdate("old", 120) // unreachable holder, nothing else free
        s.claimReplacing(assignment("n1"), 30, None) shouldBe Left(ClaimMiss.NoneFree)
        // Rolled back: the old holder keeps its assignment, epoch and claim.
        val kept = s.get("old").get
        (kept.assignedNodeId, kept.assignmentEpoch, kept.assignment.map(_.epoch)) shouldBe (
          Some("n1"),
          1L,
          Some(1L)
        )
        kept.claimedAt shouldBe defined
        // A memory miss rolls back too.
        s.recordHeartbeat(hb("small", memoryBytes = Some(8L << 30)))
        s.claimReplacing(assignment("n1"), 30, Some(64L << 30)) shouldBe Left(ClaimMiss.NoneFits)
        s.get("old").get.assignedNodeId shouldBe Some("n1")
        // A replacement exists: the old holder is released (epoch bumped) and the new one claimed.
        s.recordHeartbeat(hb("fresh"))
        s.claimReplacing(assignment("n1"), 30, Some(16L << 30)).map(_.name) shouldBe Right("fresh")
        val released = s.get("old").get
        (released.assignedNodeId, released.assignment, released.assignmentEpoch) shouldBe (
          None,
          None,
          2L
        )
        released.claimedAt shouldBe None
        s.list().count(_.assignedNodeId.contains("n1")) shouldBe 1
        // With no current holder it is a plain claim.
        s.claimReplacing(assignment("n2"), 30, None).map(_.name) shouldBe Right("small")
    }

    it should "claimReplacing re-claim a reachable stale holder in place with a new epoch" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a")); h.backdate("a", 1) // joined first
        s.claim(assignment("n1"), 30, None).map(_.name) shouldBe Right("a")
        s.recordHeartbeat(hb("a")) // still reachable
        s.recordHeartbeat(hb("b"))
        val row = s.claimReplacing(assignment("n1").copy(token = "tok2"), 30, None).toOption.get
        row.name shouldBe "a"
        row.assignmentEpoch shouldBe 3L // claim 1, release 2, re-claim 3
        row.assignment.map(a => (a.epoch, a.token)) shouldBe Some((3L, "tok2"))
        s.get("b").get.assignedNodeId shouldBe None
        // A drained holder is never re-claimed in place, and keeps the slot with nowhere to go.
        s.setUnschedulable("a", true)
        s.claimReplacing(assignment("n1"), 30, None).map(_.name) shouldBe Right("b")
        s.get("a").get.assignedNodeId shouldBe None
        s.setUnschedulable("b", true)
        s.claimReplacing(assignment("n1"), 30, None) shouldBe Left(ClaimMiss.NoneFree)
        s.get("b").get.assignedNodeId shouldBe Some("n1")
    }

    it should "refuse an address change on any known name unless drained" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a", host = "10.0.0.1"))
      s.recordHeartbeat(hb("a", host = "10.0.0.9")) shouldBe HeartbeatOutcome.AddressChangeRefused
      s.get("a").get.advertiseHost shouldBe "10.0.0.1"
      s.setUnschedulable("a", true)
      s.recordHeartbeat(hb("a", host = "10.0.0.9")) shouldBe HeartbeatOutcome.Updated
      s.get("a").get.advertiseHost shouldBe "10.0.0.9"
    }

    it should "store the node report, find a row by node id, and mark a stale epoch" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a"))
        s.claim(assignment("n1"), 30, None)
        val t0 = Instant.parse("2026-09-25T10:00:00Z")
        s.recordHeartbeat(
          hb("a", node = NodeReport(1, Some("n1"), "running", Some(42L), None, Some(t0)))
        )
        val row = s.byNodeId("n1").get
        (row.nodeState, row.nodePid, row.nodeStartedAt) shouldBe ("running", Some(42L), Some(t0))
        s.recordHeartbeat(
          hb("a", node = NodeReport(0, Some("n1"), "running", None, None, None))
        ) shouldBe HeartbeatOutcome.Updated
        s.get("a").get.nodeState shouldBe "stale"
    }

    it should "store a failed node's error and suggested action, clearing both on recovery" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a"))
        s.claim(assignment("n1"), 30, None)
        s.recordHeartbeat(
          hb(
            "a",
            node = NodeReport(
              1,
              Some("n1"),
              "failed",
              None,
              Some("Address already in use"),
              None,
              action = Some("another process holds port 21900")
            )
          )
        )
        s.get("a").map(r => (r.nodeError, r.nodeAction)) shouldBe
          Some((Some("Address already in use"), Some("another process holds port 21900")))
        s.recordHeartbeat(
          hb("a", node = NodeReport(1, Some("n1"), "running", Some(7L), None, None))
        )
        s.get("a").map(r => (r.nodeError, r.nodeAction)) shouldBe Some((None, None))
    }

    it should "mark a report for another node id stale even when the epoch matches" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a"))
        s.claim(assignment("n1"), 30, None).map(_.assignmentEpoch) shouldBe Right(1L)
        s.recordHeartbeat(
          hb("a", node = NodeReport(1, Some("other"), "running", Some(7L), None, None))
        ) shouldBe HeartbeatOutcome.Updated
        s.get("a").get.nodeState shouldBe "stale"
    }

    it should "store a stopped report with a stale epoch as stopped" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a"))
      s.claim(assignment("n1"), 30, None)
      s.recordHeartbeat(hb("a", node = NodeReport(1, Some("n1"), "running", Some(4L), None, None)))
      s.release("n1") shouldBe Some("a") // epoch 2, no node id
      // The join process confirms the stop of the node it last ran, under that node's epoch.
      s.recordHeartbeat(hb("a", node = NodeReport(1, Some("n1"), "stopped", None, None, None)))
      s.get("a").get.nodeState shouldBe "stopped"
    }

    it should "let setAssignment rewrite the json without touching the epoch" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a"))
      val row = s.claim(assignment("n1"), 30, None).toOption.get
      s.setAssignment("a", row.assignment.get.copy(port = 22000))
      s.get("a").get.assignment.map(_.port) shouldBe Some(22000)
      s.get("a").get.assignmentEpoch shouldBe 1L
    }

    it should "delete (cascading the heartbeat) and list" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a")); s.recordHeartbeat(hb("b"))
      s.list().map(_.name).sorted shouldBe List("a", "b")
      s.delete("a") shouldBe true
      s.delete("a") shouldBe false
      s.list().map(_.name) shouldBe List("b")
      s.recordHeartbeat(hb("a")) shouldBe HeartbeatOutcome.Joined // re-join after delete
    }

    it should "keep a server joined from outside the auto-approve list pending and never claim it" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("p", autoApprove = false)) shouldBe HeartbeatOutcome.Joined
        val row = s.get("p").get
        (row.approved, row.approvedBy, row.approvedAt) shouldBe (false, None, None)
        row.sourceAddr shouldBe Some("10.0.0.1")
        s.claim(assignment("n1"), 30, None) shouldBe Left(ClaimMiss.NoneFree)
    }

    it should "count a pending server as neither free nor fitting (NoneFree, not NoneFits)" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("small", memoryBytes = Some(8L << 30), autoApprove = false))
        s.claim(assignment("n1"), 30, Some(64L << 30)) shouldBe Left(ClaimMiss.NoneFree)
    }

    it should "approve a pending server on a later heartbeat whose source is now in the list" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("p", autoApprove = false))
        s.recordHeartbeat(hb("p")) shouldBe HeartbeatOutcome.Updated
        val row = s.get("p").get
        (row.approved, row.approvedBy) shouldBe (true, Some("auto"))
        row.approvedAt should not be empty
        s.claim(assignment("n1"), 30, None).map(_.name) shouldBe Right("p")
    }

    it should "never un-approve an approved server" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a"))
      s.recordHeartbeat(hb("a", autoApprove = false))
      s.get("a").get.approved shouldBe true
    }

    it should "record the latest source address" in withStore { h =>
      val s = h.store
      // Pending throughout (autoApprove = false): an approved, bound row moving to an unknown
      // source is refused instead (see "refuse an approved server heartbeating from another
      // source" below), so a pending server is what exercises plain source tracking down to None.
      s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.1")))
      s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.2")))
      s.get("a").get.sourceAddr shouldBe Some("10.0.0.2")
      s.recordHeartbeat(hb("a", autoApprove = false, source = None))
      s.get("a").get.sourceAddr shouldBe None
    }

    it should "approve by admin once, keeping the first approver, NotFound for an unknown name" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("p", autoApprove = false))
        s.approve("p", "alice") shouldBe ApproveResult.Approved
        val first = s.get("p").get
        (first.approved, first.approvedBy) shouldBe (true, Some("alice"))
        s.approve("p", "bob") shouldBe ApproveResult.AlreadyApproved
        s.get("p").get.approvedBy shouldBe Some("alice")
        s.get("p").get.approvedAt shouldBe first.approvedAt
        s.get("p").get.approvedSource shouldBe first.approvedSource
        s.approve("ghost", "alice") shouldBe ApproveResult.NotFound
    }

    it should "refuse an admin approval while the server's source is unknown" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("p", autoApprove = false, source = None)) shouldBe
        HeartbeatOutcome.Joined
      s.approve("p", "alice") shouldBe ApproveResult.SourceUnknown
      val pending = s.get("p").get
      (pending.approved, pending.approvedBy, pending.approvedAt, pending.approvedSource) shouldBe
        (false, None, None, None)
      // Once a heartbeat carries a known source, the approval binds it.
      s.recordHeartbeat(hb("p", autoApprove = false, source = Some("10.0.0.3")))
      s.approve("p", "alice") shouldBe ApproveResult.Approved
      val row = s.get("p").get
      (row.approved, row.approvedBy, row.approvedSource) shouldBe
        (true, Some("alice"), Some("10.0.0.3"))
    }

    it should "refuse an address change for a pending server like any other" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("p", autoApprove = false))
      s.recordHeartbeat(hb("p", host = "10.0.0.9", autoApprove = false)) shouldBe
        HeartbeatOutcome.AddressChangeRefused
    }

    it should "forget approval on delete: a re-join is judged again" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a")); s.delete("a")
      s.recordHeartbeat(hb("a", autoApprove = false)) shouldBe HeartbeatOutcome.Joined
      s.get("a").get.approved shouldBe false
    }

    it should "judge again a drained server that re-addresses" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a", host = "10.0.0.1")) shouldBe HeartbeatOutcome.Joined
      s.get("a").get.approved shouldBe true
      s.setUnschedulable("a", true) shouldBe true
      s.recordHeartbeat(hb("a", host = "10.0.0.9", autoApprove = false)) shouldBe
        HeartbeatOutcome.Updated
      val moved = s.get("a").get
      moved.advertiseHost shouldBe "10.0.0.9"
      moved.approved shouldBe false
      moved.approvedBy shouldBe None
      moved.approvedAt shouldBe None
      s.recordHeartbeat(hb("a", host = "10.0.0.9", autoApprove = true)) shouldBe
        HeartbeatOutcome.Updated
      val again = s.get("a").get
      again.approved shouldBe true
      again.approvedBy shouldBe Some("auto")
    }

    it should "judge again a drained server that changes only its node port" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a"))
      s.setUnschedulable("a", true)
      s.recordHeartbeat(hb("a", port = 21901, autoApprove = false)) shouldBe
        HeartbeatOutcome.Updated
      val moved = s.get("a").get
      moved.nodePort shouldBe 21901
      moved.approved shouldBe false
      moved.approvedBy shouldBe None
      moved.approvedAt shouldBe None
    }

    it should "keep the approval (and admin approver) of a drained server at its unchanged address" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a", autoApprove = false))
        s.approve("a", "alice") shouldBe ApproveResult.Approved
        s.setUnschedulable("a", true)
        s.recordHeartbeat(hb("a", autoApprove = false)) shouldBe HeartbeatOutcome.Updated
        val row = s.get("a").get
        row.approved shouldBe true
        row.approvedBy shouldBe Some("alice")
    }

    it should "refuse a re-address of a drained server that still holds an assignment" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a", host = "10.0.0.1"))
        s.claim(assignment("n1"), 30, None).isRight shouldBe true
        // drain = setUnschedulable then release: a heartbeat landing in between must not move the
        // server (which would reset its approval while it still holds the node).
        s.setUnschedulable("a", true)
        s.recordHeartbeat(hb("a", host = "10.0.0.9", autoApprove = false)) shouldBe
          HeartbeatOutcome.AddressChangeRefused
        s.recordHeartbeat(hb("a", port = 21901, autoApprove = false)) shouldBe
          HeartbeatOutcome.AddressChangeRefused
        val kept = s.get("a").get
        (kept.advertiseHost, kept.nodePort) shouldBe ("10.0.0.1", 21900)
        (kept.approved, kept.approvedBy) shouldBe (true, Some("auto"))
        kept.assignedNodeId shouldBe Some("n1")
        s.release("n1") shouldBe Some("a")
        s.recordHeartbeat(hb("a", host = "10.0.0.9", autoApprove = false)) shouldBe
          HeartbeatOutcome.Updated
        val moved = s.get("a").get
        moved.advertiseHost shouldBe "10.0.0.9"
        (moved.approved, moved.approvedBy, moved.approvedAt) shouldBe (false, None, None)
    }

    it should "bind an approved server to the source that approved it" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a", source = Some("10.0.0.1")))
      s.get("a").get.approvedSource shouldBe Some("10.0.0.1")
      // A pending server is not bound; the heartbeat that auto-approves it binds its source.
      s.recordHeartbeat(hb("p", autoApprove = false, source = Some("10.0.0.5")))
      s.get("p").get.approvedSource shouldBe None
      s.recordHeartbeat(hb("p", source = Some("10.0.0.6"))) shouldBe HeartbeatOutcome.Updated
      val p = s.get("p").get
      (p.approved, p.approvedSource) shouldBe (true, Some("10.0.0.6"))
    }

    it should "refuse an approved server heartbeating from another source, row untouched" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a", source = Some("10.0.0.1")))
        s.claim(assignment("n1"), 30, None).isRight shouldBe true
        h.backdate("a", 60)
        val before = s.get("a").get
        s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.6.6.6"))) shouldBe
          HeartbeatOutcome.SourceChangeRefused
        // An unknown source against a bound row is another source too.
        s.recordHeartbeat(hb("a", autoApprove = false, source = None)) shouldBe
          HeartbeatOutcome.SourceChangeRefused
        val after = s.get("a").get
        (after.approved, after.approvedBy, after.approvedAt) shouldBe
          (true, before.approvedBy, before.approvedAt)
        after.approvedSource shouldBe Some("10.0.0.1")
        after.sourceAddr shouldBe Some("10.0.0.1")
        after.lastHeartbeatAt shouldBe before.lastHeartbeatAt
        after.assignedNodeId shouldBe Some("n1")
        // Drained but still assigned: refused as well.
        s.setUnschedulable("a", true)
        s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.6.6.6"))) shouldBe
          HeartbeatOutcome.SourceChangeRefused
        // The genuine source is still a normal heartbeat.
        s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.1"))) shouldBe
          HeartbeatOutcome.Updated
    }

    it should "rebind an approved server that moves to another source inside the list" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a", source = Some("10.0.0.1")))
        val first = s.get("a").get
        s.recordHeartbeat(hb("a", autoApprove = true, source = Some("10.0.0.2"))) shouldBe
          HeartbeatOutcome.Updated
        val row = s.get("a").get
        (row.approved, row.approvedBy, row.approvedAt) shouldBe
          (true, first.approvedBy, first.approvedAt)
        row.approvedSource shouldBe Some("10.0.0.2")
        s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.1"))) shouldBe
          HeartbeatOutcome.SourceChangeRefused
    }

    it should "judge again a drained, unassigned server heartbeating from another source" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a", source = Some("10.0.0.1")))
        s.setUnschedulable("a", true)
        s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.7"))) shouldBe
          HeartbeatOutcome.Updated
        val row = s.get("a").get
        (row.approved, row.approvedBy, row.approvedAt, row.approvedSource) shouldBe
          (false, None, None, None)
        row.sourceAddr shouldBe Some("10.0.0.7")
    }

    it should "bind an admin approval to the latest source address" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.7")))
      s.approve("a", "alice") shouldBe ApproveResult.Approved
      s.get("a").get.approvedSource shouldBe Some("10.0.0.7")
      s.approve("a", "bob") shouldBe ApproveResult.AlreadyApproved // keeps the first binding too
      s.get("a").get.approvedSource shouldBe Some("10.0.0.7")
      s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.7"))) shouldBe
        HeartbeatOutcome.Updated
      s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.8"))) shouldBe
        HeartbeatOutcome.SourceChangeRefused
    }

    it should "bind an unbound approval only to a source inside the list" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a", source = Some("10.0.0.1")))
      h.unbind("a")
      val first = s.get("a").get
      first.approvedSource shouldBe None
      s.recordHeartbeat(hb("a", autoApprove = true, source = Some("10.0.0.3"))) shouldBe
        HeartbeatOutcome.Updated
      val row = s.get("a").get
      (row.approved, row.approvedBy, row.approvedAt) shouldBe
        (true, first.approvedBy, first.approvedAt)
      row.approvedSource shouldBe Some("10.0.0.3")
      s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.4"))) shouldBe
        HeartbeatOutcome.SourceChangeRefused
    }

    it should "refuse an unbound approval heartbeating from outside the list, row untouched" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a", source = Some("10.0.0.1")))
        s.claim(assignment("n1"), 30, None).isRight shouldBe true
        h.unbind("a")
        h.backdate("a", 60)
        val before = s.get("a").get
        // Same name, advertised host and port, from a source outside the list.
        s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.6.6.6"))) shouldBe
          HeartbeatOutcome.ApprovalUnbound
        // An unknown source never binds. (The caller-judged autoApprove=true, source=None
        // combination is now unrepresentable: Heartbeat itself refuses it, see HeartbeatSpec.)
        s.recordHeartbeat(hb("a", autoApprove = false, source = None)) shouldBe
          HeartbeatOutcome.ApprovalUnbound
        val after = s.get("a").get
        (after.approved, after.approvedBy, after.approvedAt, after.approvedSource) shouldBe
          (true, before.approvedBy, before.approvedAt, None)
        after.sourceAddr shouldBe Some("10.0.0.1")
        after.lastHeartbeatAt shouldBe before.lastHeartbeatAt
        after.assignedNodeId shouldBe Some("n1")
        after.assignmentEpoch shouldBe before.assignmentEpoch
        // Drained but still assigned: refused as well.
        s.setUnschedulable("a", true)
        s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.6.6.6"))) shouldBe
          HeartbeatOutcome.ApprovalUnbound
        val drained = s.get("a").get
        (drained.approved, drained.approvedSource, drained.assignedNodeId) shouldBe
          (true, None, Some("n1"))
        drained.lastHeartbeatAt shouldBe before.lastHeartbeatAt
    }

    it should "judge again a drained, unassigned unbound approval heartbeating from outside the list" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a", source = Some("10.0.0.1")))
        h.unbind("a")
        // Schedulable (not drained): refused.
        s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.7"))) shouldBe
          HeartbeatOutcome.ApprovalUnbound
        s.setUnschedulable("a", true)
        s.recordHeartbeat(hb("a", autoApprove = false, source = Some("10.0.0.7"))) shouldBe
          HeartbeatOutcome.Updated
        val row = s.get("a").get
        (row.approved, row.approvedBy, row.approvedAt, row.approvedSource) shouldBe
          (false, None, None, None)
        row.sourceAddr shouldBe Some("10.0.0.7")
        // Pending now: an admin approval binds the source it is judged from.
        s.approve("a", "alice") shouldBe ApproveResult.Approved
        s.get("a").get.approvedSource shouldBe Some("10.0.0.7")
    }

    it should "clear the source binding when a drained server re-addresses" in withStore { h =>
      val s = h.store
      s.recordHeartbeat(hb("a", source = Some("10.0.0.1")))
      s.setUnschedulable("a", true)
      s.recordHeartbeat(hb("a", host = "10.0.0.9", autoApprove = false, source = Some("10.0.0.9")))
        .shouldBe(HeartbeatOutcome.Updated)
      val row = s.get("a").get
      (row.approved, row.approvedSource) shouldBe (false, None)
    }

    it should "leave approval and sourceAddr untouched on a refused address change" in withStore {
      h =>
        val s = h.store
        s.recordHeartbeat(hb("a", source = Some("10.0.0.1")))
        s.recordHeartbeat(
          hb("a", host = "10.0.0.9", autoApprove = false, source = Some("10.0.0.9"))
        ) shouldBe HeartbeatOutcome.AddressChangeRefused
        val row = s.get("a").get
        row.approved shouldBe true
        row.sourceAddr shouldBe Some("10.0.0.1")
    }
}

/** Outside the shared behaviour: pins the `Heartbeat` invariant itself, not a store's handling of
  * it.
  */
class HeartbeatSpec extends AnyFlatSpec with Matchers:
  "Heartbeat" should "refuse to be built as an auto-approval from an unknown source" in
    intercept[IllegalArgumentException] {
      Heartbeat(
        "a",
        "10.0.0.1",
        21900,
        None,
        None,
        None,
        None,
        None,
        NodeReport(0, None, "none", None, None, None),
        sourceAddr = None,
        autoApprove = true
      )
    }

class InMemoryFleetServerStoreSpec extends AnyFlatSpec with Matchers with FleetServerStoreBehaviour:
  private def withMem(test: FleetStoreHarness => Unit): Unit =
    var now = Instant.parse("2026-09-25T10:00:00Z")
    val mem = new InMemoryFleetServerStore(clock = () => now)
    test(new FleetStoreHarness:
      def store: FleetServerStore                          = mem
      def backdate(name: String, seconds: Long): Unit      = mem.backdate(name, seconds)
      def backdateClaim(name: String, seconds: Long): Unit = mem.backdateClaim(name, seconds)
      def unbind(name: String): Unit                       = mem.unbind(name))
  "InMemoryFleetServerStore" should behave like storeBehaviour(withMem)

class PostgresFleetServerStoreSpec extends AnyFlatSpec with Matchers with FleetServerStoreBehaviour:
  TestPostgres.dropStrayTestDatabases("qodfs")

  /** A fresh migrated database, its store and its JDBC url, dropped afterwards. */
  private def withDb(test: (PostgresControlPlaneStore, String, String) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodfs_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val pg = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(pg, url, dbName)
      finally pg.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def withFresh(test: FleetStoreHarness => Unit): Unit = withDb { (pg, _, dbName) =>
    test(new FleetStoreHarness:
      def store: FleetServerStore                     = pg
      def backdate(name: String, seconds: Long): Unit =
        TestPostgres.psql(
          dbName,
          s"UPDATE qodstate_fleet_heartbeat SET last_heartbeat_at = now() - interval '$seconds seconds' WHERE name = '$name'; " +
            s"UPDATE qodstate_fleet_server SET joined_at = joined_at - interval '$seconds seconds' WHERE name = '$name'"
        )
      def backdateClaim(name: String, seconds: Long): Unit =
        TestPostgres.psql(
          dbName,
          s"UPDATE qodstate_fleet_server SET claimed_at = claimed_at - interval '$seconds seconds' WHERE name = '$name'"
        )
      def unbind(name: String): Unit =
        TestPostgres.psql(
          dbName,
          s"UPDATE qodstate_fleet_server SET approved_source = NULL WHERE name = '$name'"
        ))
  }

  "PostgresControlPlaneStore as FleetServerStore" should behave like storeBehaviour(withFresh)

  it should "answer NoneFree, not NoneFits, when the only fitting server is locked by a concurrent claim" in withDb {
    (pg, url, _) =>
      pg.recordHeartbeat(hb("big", memoryBytes = Some(128L << 30)))
      pg.recordHeartbeat(hb("small", memoryBytes = Some(8L << 30)))
      // Hold "big" the way an in-flight claim does, from a second connection.
      val other =
        java.sql.DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        other.setAutoCommit(false)
        val lock = other.prepareStatement(
          "SELECT name FROM qodstate_fleet_server WHERE name = 'big' FOR UPDATE"
        )
        lock.executeQuery().next() shouldBe true
        pg.claim(assignment("n1"), 30, Some(64L << 30)) shouldBe Left(ClaimMiss.NoneFree)
        other.rollback()
        lock.close()
      finally other.close()
      pg.claim(assignment("n1"), 30, Some(64L << 30)).map(_.name) shouldBe Right("big")
      pg.claim(assignment("n2"), 30, Some(64L << 30)) shouldBe Left(ClaimMiss.NoneFits)
  }

  it should "keep servers that joined before 0041 approved (upgrade backfill)" in {
    TestPostgres.ensureReachable()
    val dbName = s"qodfs_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url    = TestPostgres.dbUrl(dbName)
      val master =
        Paths.get(getClass.getResource("/db/changelog/db.changelog-master.yaml").toURI)
      val upTo = master.resolveSibling("master-upto-0040-test.yaml")
      val text = Files.readString(master)
      val cut  = text.indexOf("  - include:\n      file: db/changelog/0041-")
      cut should be > 0
      Files.writeString(upTo, text.substring(0, cut))
      try
        new LiquibaseRunner(
          url,
          TestPostgres.pgUser,
          TestPostgres.pgPass,
          "db/changelog/master-upto-0040-test.yaml"
        ).run()
      finally Files.deleteIfExists(upTo)
      TestPostgres.psql(
        dbName,
        "INSERT INTO qodstate_fleet_server (name, advertise_host, node_port) " +
          "VALUES ('old', '10.0.0.1', 21900); " +
          "INSERT INTO qodstate_fleet_heartbeat (name) VALUES ('old')"
      )
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val pg = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        val row = pg.get("old").get
        (row.approved, row.approvedBy) shouldBe (true, Some("upgrade"))
        row.approvedAt should not be empty
        row.approvedSource shouldBe None
        // No trust on first use: a source outside the list cannot bind the backfilled row.
        pg.recordHeartbeat(hb("old", autoApprove = false, source = Some("10.0.0.2"))) shouldBe
          HeartbeatOutcome.ApprovalUnbound
        val kept = pg.get("old").get
        (kept.approved, kept.approvedBy, kept.approvedSource) shouldBe
          (true, Some("upgrade"), None)
        // One inside the list binds it; another source is then refused as usual.
        pg.recordHeartbeat(hb("old", autoApprove = true, source = Some("10.0.0.1"))) shouldBe
          HeartbeatOutcome.Updated
        pg.get("old").get.approvedSource shouldBe Some("10.0.0.1")
        pg.recordHeartbeat(hb("old", autoApprove = false, source = Some("10.0.0.2"))) shouldBe
          HeartbeatOutcome.SourceChangeRefused
        pg.recordHeartbeat(hb("new", autoApprove = false))
        pg.get("new").get.approved shouldBe false
      finally pg.close()
    finally Try(TestPostgres.dropDatabase(dbName))
  }
