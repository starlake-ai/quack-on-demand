package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{CountDownLatch, Executors}
import scala.util.Try

/** Test harness over one store: `backdate` moves a server's last heartbeat into the past. */
trait FleetStoreHarness:
  def store: FleetServerStore
  def backdate(name: String, seconds: Long): Unit

trait FleetServerStoreBehaviour { this: AnyFlatSpec & Matchers =>

  def hb(
      name: String,
      host: String = "10.0.0.1",
      port: Int = 21900,
      memoryBytes: Option[Long] = Some(64L << 30),
      node: NodeReport = NodeReport(0, None, "none", None, None, None)
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
      node
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
}

class InMemoryFleetServerStoreSpec extends AnyFlatSpec with Matchers with FleetServerStoreBehaviour:
  private def withMem(test: FleetStoreHarness => Unit): Unit =
    var now = Instant.parse("2026-09-25T10:00:00Z")
    val mem = new InMemoryFleetServerStore(clock = () => now)
    test(new FleetStoreHarness:
      def store: FleetServerStore                     = mem
      def backdate(name: String, seconds: Long): Unit = mem.backdate(name, seconds))
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
