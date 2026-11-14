package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model._
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, InMemoryControlPlaneStore}
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.observability.metrics.MaintenanceMetrics
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The leader's reaper (issue #210), on the in-memory store: a silent run is failed and its node
  * stopped, an over-time run is failed and its node left to its live owner, and a stop that fails
  * leaves the row failed and the pass going.
  */
class MaintenanceReaperSpec extends AnyFlatSpec with Matchers:

  /** Delegates to the in-memory store, except the first reap call, which throws, and a read of a
    * run while `down` is set, which throws too.
    */
  private class ReapFailsOnceStore(inner: InMemoryControlPlaneStore) extends ControlPlaneStore:
    export inner.{findMaintenanceRun as _, reapMaintenanceRuns as _, *}
    private var failed = false
    @volatile var down = false

    override def findMaintenanceRun(id: Long): Option[MaintenanceRun] =
      if down then throw new java.sql.SQLException("control plane unreachable")
      else inner.findMaintenanceRun(id)

    override def reapMaintenanceRuns(
        livenessTimeoutSec: Int,
        runTimeoutSec: Long
    ): List[ReapedRun] =
      if !failed then
        failed = true
        throw new java.sql.SQLException("control plane unreachable")
      else inner.reapMaintenanceRuns(livenessTimeoutSec, runTimeoutSec)

  private class Fixture(stopFails: Boolean = false, failFirstReap: Boolean = false):
    val store                             = new InMemoryControlPlaneStore()
    val flaky: Option[ReapFailsOnceStore] =
      if failFirstReap then Some(new ReapFailsOnceStore(store)) else None
    val reapStore: ControlPlaneStore = flaky.getOrElse(store)
    var stopped                      = List.empty[(PoolKey, String)]
    var recorded                     = List.empty[(String, String)]
    val telemetry                    = new RecordingTelemetryStore
    val reaper                       = new MaintenanceReaper(
      store = reapStore,
      livenessTimeoutSec = 180,
      runTimeoutSec = 3600L,
      stopNode = (k, id) =>
        IO { stopped = stopped :+ (k -> id) } *>
          (if stopFails then IO.raiseError(new RuntimeException("api server down")) else IO.unit),
      audit = new AuditRecorder(telemetry, _ => None),
      metrics = new MaintenanceMetrics:
        def recordRun(
            tenant: String,
            tenantDb: String,
            result: String,
            durationSeconds: Double,
            bytesReclaimed: Long,
            filesCompacted: Int,
            snapshotsExpired: Int
        ): Unit = recorded = recorded :+ (tenantDb -> result)
    )

    def audited: List[(String, String)] =
      telemetry.events.toList.map(e => (e.outcome, e.detail.getOrElse("reason", "")))

    def running(db: String, node: Option[String]): MaintenanceRun =
      store.enqueueMaintenanceRun("acme", db, "tenantdb", "cadence", None)
      val r = store.claimQueuedMaintenanceRun(minAgeSec = 0).get
      node.foreach(store.setMaintenanceRunNode(r.id, _))
      r

    /** The row's status read from the inner store, whatever `down` says. */
    def rowOf0(r: MaintenanceRun): String = store.findMaintenanceRun(r.id).map(_.status).get

    def rowOf(r: MaintenanceRun): (String, Option[String]) =
      store.findMaintenanceRun(r.id).map(x => (x.status, x.error)).get

  private val longAgo = Instant.now().minusSeconds(7200)

  "reapOnce" should "fail a silent run and stop its node" in {
    val f = new Fixture()
    val r = f.running("acme_db", Some("maint-n1"))
    f.store.setMaintenanceRunTimes(r.id, heartbeatAt = Some(Instant.now().minusSeconds(600)))
    f.reaper.reapOnce.unsafeRunSync()
    f.rowOf(r) shouldBe ("failed", Some(MaintenanceRun.ReapedSilent))
    f.stopped shouldBe List(PoolKey("acme", "acme_db", "__maint") -> "maint-n1")
    f.recorded shouldBe List("acme_db" -> "failed")
    f.audited shouldBe List(("failed", MaintenanceRun.ReapedSilent))
  }

  it should "fail an over-time run without stopping its node" in {
    val f = new Fixture()
    val r = f.running("acme_db", Some("maint-n1"))
    f.store.setMaintenanceRunTimes(r.id, startedAt = Some(longAgo))
    f.reaper.reapOnce.unsafeRunSync()
    f.rowOf(r) shouldBe ("failed", Some(MaintenanceRun.ReapedTimeout))
    f.stopped shouldBe empty
    f.recorded shouldBe List("acme_db" -> "failed")
    f.audited shouldBe List(("failed", MaintenanceRun.ReapedTimeout))
  }

  it should "stop nothing for a silent run that recorded no node" in {
    val f = new Fixture()
    val r = f.running("acme_db", None)
    f.store.setMaintenanceRunTimes(r.id, heartbeatAt = Some(longAgo))
    f.reaper.reapOnce.unsafeRunSync()
    f.rowOf(r) shouldBe ("failed", Some(MaintenanceRun.ReapedSilent))
    f.stopped shouldBe empty
  }

  it should "leave a healthy run alone" in {
    val f = new Fixture()
    val r = f.running("acme_db", Some("maint-n1"))
    f.reaper.reapOnce.unsafeRunSync()
    f.rowOf(r) shouldBe ("running", None)
    f.stopped shouldBe empty
  }

  it should "keep the rows failed and try every stop when a stop fails" in {
    val f = new Fixture(stopFails = true)
    val a = f.running("a_db", Some("maint-a"))
    val b = f.running("b_db", Some("maint-b"))
    f.store.setMaintenanceRunTimes(a.id, heartbeatAt = Some(longAgo))
    f.store.setMaintenanceRunTimes(b.id, heartbeatAt = Some(longAgo))
    f.reaper.reapOnce.unsafeRunSync()
    f.rowOf(a)._1 shouldBe "failed"
    f.rowOf(b)._1 shouldBe "failed"
    f.stopped.map(_._2).toSet shouldBe Set("maint-a", "maint-b")
  }

  "tick" should "reap nothing for one liveness timeout after becoming leader" in {
    val f  = new Fixture()
    val t0 = Instant.now()
    val r  = f.running("acme_db", Some("maint-n1"))
    f.store.setMaintenanceRunTimes(r.id, heartbeatAt = Some(t0.minusSeconds(600)))
    f.reaper.tick(true, t0).unsafeRunSync()
    f.reaper.tick(true, t0.plusSeconds(179)).unsafeRunSync()
    f.rowOf(r)._1 shouldBe "running"
    f.stopped shouldBe empty
    f.reaper.tick(true, t0.plusSeconds(181)).unsafeRunSync()
    f.rowOf(r) shouldBe ("failed", Some(MaintenanceRun.ReapedSilent))
    f.stopped.map(_._2) shouldBe List("maint-n1")
  }

  it should "reap nothing while not leader" in {
    val f  = new Fixture()
    val t0 = Instant.now()
    val r  = f.running("acme_db", Some("maint-n1"))
    f.store.setMaintenanceRunTimes(r.id, heartbeatAt = Some(t0.minusSeconds(600)))
    f.reaper.tick(false, t0).unsafeRunSync()
    f.reaper.tick(false, t0.plusSeconds(400)).unsafeRunSync()
    f.rowOf(r)._1 shouldBe "running"
  }

  it should "after a failed pass, wait a full liveness timeout from the first successful probe" in {
    val f  = new Fixture(failFirstReap = true)
    val t0 = Instant.now()
    val r  = f.running("acme_db", Some("maint-n1"))
    f.store.setMaintenanceRunTimes(r.id, heartbeatAt = Some(t0.minusSeconds(600)))
    f.reaper.tick(true, t0).unsafeRunSync() // warm-up
    f.rowOf(r)._1 shouldBe "running"
    f.flaky.get.down = true
    an[java.sql.SQLException] should be thrownBy f.reaper
      .tick(true, t0.plusSeconds(200))
      .unsafeRunSync()
    f.rowOf0(r) shouldBe "running"
    an[java.sql.SQLException] should be thrownBy f.reaper
      .tick(true, t0.plusSeconds(260))
      .unsafeRunSync()
    f.rowOf0(r) shouldBe "running"
    f.flaky.get.down = false
    f.reaper.tick(true, t0.plusSeconds(320)).unsafeRunSync() // first successful probe
    f.rowOf(r)._1 shouldBe "running"
    f.stopped shouldBe empty
    f.reaper.tick(true, t0.plusSeconds(499)).unsafeRunSync()
    f.rowOf(r)._1 shouldBe "running"
    f.stopped shouldBe empty
    f.reaper.tick(true, t0.plusSeconds(501)).unsafeRunSync()
    f.rowOf(r) shouldBe ("failed", Some(MaintenanceRun.ReapedSilent))
    f.stopped.map(_._2) shouldBe List("maint-n1")
  }

  it should "reap nothing while recovering even long after the failed pass" in {
    val f  = new Fixture(failFirstReap = true)
    val t0 = Instant.now()
    val r  = f.running("acme_db", Some("maint-n1"))
    f.store.setMaintenanceRunTimes(r.id, heartbeatAt = Some(t0.minusSeconds(600)))
    f.reaper.tick(true, t0).unsafeRunSync() // warm-up
    f.flaky.get.down = true
    an[java.sql.SQLException] should be thrownBy f.reaper
      .tick(true, t0.plusSeconds(200))
      .unsafeRunSync()
    an[java.sql.SQLException] should be thrownBy f.reaper
      .tick(true, t0.plusSeconds(400))
      .unsafeRunSync()
    an[java.sql.SQLException] should be thrownBy f.reaper
      .tick(true, t0.plusSeconds(1000))
      .unsafeRunSync()
    f.rowOf0(r) shouldBe "running"
    f.stopped shouldBe empty
  }

  it should "start a new quiet window after leadership is lost and won back" in {
    val f  = new Fixture()
    val t0 = Instant.now()
    val a  = f.running("a_db", Some("maint-a"))
    f.store.setMaintenanceRunTimes(a.id, heartbeatAt = Some(t0.minusSeconds(600)))
    f.reaper.tick(true, t0).unsafeRunSync()
    f.reaper.tick(true, t0.plusSeconds(181)).unsafeRunSync()
    f.rowOf(a)._1 shouldBe "failed"
    f.reaper.tick(false, t0.plusSeconds(200)).unsafeRunSync()
    val b = f.running("b_db", Some("maint-b"))
    f.store.setMaintenanceRunTimes(b.id, heartbeatAt = Some(t0.minusSeconds(600)))
    f.reaper.tick(true, t0.plusSeconds(201)).unsafeRunSync()
    f.reaper.tick(true, t0.plusSeconds(300)).unsafeRunSync()
    f.rowOf(b)._1 shouldBe "running"
    f.reaper.tick(true, t0.plusSeconds(382)).unsafeRunSync()
    f.rowOf(b)._1 shouldBe "failed"
  }
