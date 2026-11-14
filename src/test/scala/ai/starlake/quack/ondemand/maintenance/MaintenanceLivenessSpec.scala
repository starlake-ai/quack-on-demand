package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model._
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, InMemoryControlPlaneStore}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

/** The liveness heartbeat a replica keeps on the run it executes (issue #210), on the in-memory
  * store: fresh while the work runs, the node stopped and the work ended once the leader reaps the
  * row, nothing stopped when the owner finished the row itself, and a failed write never cancelling
  * the work.
  */
class MaintenanceLivenessSpec extends AnyFlatSpec with Matchers:

  private val every    = 50.millis
  private val maintKey = PoolKey("acme", "acme_db", "__maint")

  private def claimed(store: InMemoryControlPlaneStore): MaintenanceRun =
    store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
    store.claimQueuedMaintenanceRun(minAgeSec = 0).get

  "guard" should "keep the heartbeat fresh while the work runs" in {
    val store = new InMemoryControlPlaneStore()
    val run   = claimed(store)
    val old   = Instant.now().minusSeconds(3600)
    store.setMaintenanceRunTimes(run.id, heartbeatAt = Some(old))
    MaintenanceLiveness
      .guard(store, run, every, (_, _) => IO.unit)(IO.sleep(300.millis))
      .timeout(5.seconds)
      .unsafeRunSync()
    store
      .findMaintenanceRun(run.id)
      .flatMap(_.heartbeatAt)
      .get
      .isAfter(old.plusSeconds(3000)) shouldBe
      true
  }

  it should "stop the node and end the work once the run is reaped" in {
    val store = new InMemoryControlPlaneStore()
    val run   = claimed(store)
    store.setMaintenanceRunNode(run.id, "maint-n1")
    var stopped = List.empty[(PoolKey, String)]
    val reap    = IO.sleep(120.millis) *> IO(
      store.finishMaintenanceRun(
        run.id,
        "failed",
        RunCounters(),
        Some(MaintenanceRun.ReapedTimeout)
      )
    )
    (reap.start *> MaintenanceLiveness.guard(
      store,
      run,
      every,
      (k, id) => IO { stopped = stopped :+ (k -> id) }
    )(IO.never)).timeout(5.seconds).unsafeRunSync()
    stopped shouldBe List(maintKey -> "maint-n1")
  }

  it should "stop nothing and let the work end when its owner finished the row" in {
    val store = new InMemoryControlPlaneStore()
    val run   = claimed(store)
    store.setMaintenanceRunNode(run.id, "maint-n1")
    var stopped  = 0
    var workDone = false
    val work     =
      IO.sleep(120.millis) *>
        IO(store.finishMaintenanceRun(run.id, "succeeded", RunCounters(), None)) *>
        IO.sleep(200.millis) *> IO { workDone = true }
    MaintenanceLiveness
      .guard(store, run, every, (_, _) => IO(stopped += 1))(work)
      .timeout(5.seconds)
      .unsafeRunSync()
    workDone shouldBe true
    stopped shouldBe 0
  }

  it should "not cancel the work when a heartbeat write fails" in {
    val inner    = new InMemoryControlPlaneStore()
    val run      = claimed(inner)
    val store    = new FailingTouchStore(inner)
    var workDone = false
    MaintenanceLiveness
      .guard(store, run, every, (_, _) => IO.unit)(IO.sleep(300.millis) *> IO { workDone = true })
      .timeout(5.seconds)
      .unsafeRunSync()
    workDone shouldBe true
  }

  it should "stop the node before cancelling, so an uninterruptible statement ends" in {
    val store = new InMemoryControlPlaneStore()
    val run   = claimed(store)
    store.setMaintenanceRunNode(run.id, "maint-n1")
    val latch    = new java.util.concurrent.CountDownLatch(1)
    var released = false
    val reap     = IO.sleep(120.millis) *> IO(
      store.finishMaintenanceRun(run.id, "failed", RunCounters(), Some(MaintenanceRun.ReapedSilent))
    )
    (reap.start *> MaintenanceLiveness.guard(
      store,
      run,
      every,
      (_, _) => IO(latch.countDown()) // stopping the node fails the in-flight statement
    )(
      // Recorded inside the blocking call: the guard cancels this work once the node is
      // stopped, which drops any continuation after it.
      IO.blocking { released = latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    )).timeout(5.seconds).unsafeRunSync()
    released shouldBe true
  }

  /** Every call delegates to the in-memory store, except the liveness write, which throws. */
  private class FailingTouchStore(inner: InMemoryControlPlaneStore) extends ControlPlaneStore:
    export inner.{touchMaintenanceRun as _, *}

    override def touchMaintenanceRun(id: Long): Boolean =
      throw new java.sql.SQLException("control plane unreachable")
