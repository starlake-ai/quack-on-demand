package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model._
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
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

  private class Fixture(stopFails: Boolean = false):
    val store   = new InMemoryControlPlaneStore()
    var stopped = List.empty[(PoolKey, String)]
    val reaper  = new MaintenanceReaper(
      store = store,
      livenessTimeoutSec = 180,
      runTimeoutSec = 3600L,
      stopNode = (k, id) =>
        IO { stopped = stopped :+ (k -> id) } *>
          (if stopFails then IO.raiseError(new RuntimeException("api server down")) else IO.unit),
      audit = AuditRecorder.noop,
      metrics = MaintenanceMetrics.noop
    )

    def running(db: String, node: Option[String]): MaintenanceRun =
      store.enqueueMaintenanceRun("acme", db, "tenantdb", "cadence", None)
      val r = store.claimQueuedMaintenanceRun(minAgeSec = 0).get
      node.foreach(store.setMaintenanceRunNode(r.id, _))
      r

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
  }

  it should "fail an over-time run without stopping its node" in {
    val f = new Fixture()
    val r = f.running("acme_db", Some("maint-n1"))
    f.store.setMaintenanceRunTimes(r.id, startedAt = Some(longAgo))
    f.reaper.reapOnce.unsafeRunSync()
    f.rowOf(r) shouldBe ("failed", Some(MaintenanceRun.ReapedTimeout))
    f.stopped shouldBe empty
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
