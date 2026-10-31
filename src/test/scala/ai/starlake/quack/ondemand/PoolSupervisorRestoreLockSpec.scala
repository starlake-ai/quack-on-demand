package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantAcl, TenantAclPatch}
import ai.starlake.quack.ondemand.runtime.testkit.{LogCapture, StubQuackBackend}
import ai.starlake.quack.ondemand.state.{ControlPlaneSnapshot, InMemoryControlPlaneStore}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.util.Try

/** A restore (a peer's NOTIFY) and a local mutation on the same replica (Phase C re-review I4-R1):
  * the restore must not swap the caches to a snapshot read before the local commit while that
  * commit's refresh publishes a newer policy version over them. A restore never publishes without
  * the versions lock (amendment A7): it waits for running mutations, and one it cannot run now is
  * skipped and retried, never run unlocked.
  */
class PoolSupervisorRestoreLockSpec extends AnyFlatSpec with Matchers:

  private given ExecutionContext = ExecutionContext.global

  private val Opa =
    TenantAclPatch(mode = Some("opa"), opaUrl = Some("http://opa.internal:8181"))

  /** Counts the snapshots read (a restore reads one before it swaps the caches) and, once armed,
    * holds the snapshot it read until `release`: the restore reading it is then caught between its
    * snapshot and its cache swap. Its ACL write bumps the tenant's version, as the Liquibase 0050
    * trigger does on the ACL columns, then runs `onAclWrite` (inside the supervisor's mutation).
    */
  private final class SlowSnapshotStore extends InMemoryControlPlaneStore:
    val armed      = new AtomicBoolean(false)
    val entered    = new CountDownLatch(1)
    val release    = new CountDownLatch(1)
    val snapshots  = new AtomicInteger(0)
    val onAclWrite = new AtomicReference[() => Unit](() => ())

    override def snapshot(): ControlPlaneSnapshot =
      val snap = super.snapshot()
      snapshots.incrementAndGet()
      if armed.compareAndSet(true, false) then
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
      snap

    override def updateTenantAcl(tenantId: String, acl: TenantAcl): Unit =
      super.updateTenantAcl(tenantId, acl)
      bumpPolicyVersion(tenantId)
      onAclWrite.get()()

  private def supervisor(store: SlowSnapshotStore): PoolSupervisor =
    val sup = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup

  "a local ACL change during a restore" should "leave the caches and the version in step" in {
    val store = new SlowSnapshotStore
    val sup   = supervisor(store)
    store.armed.set(true)
    val restoring = Future(sup.restore())
    store.entered.await(10, TimeUnit.SECONDS) shouldBe true
    val mutating = Future(sup.setTenantAcl("acme", Opa, "", "qod").unsafeRunSync())
    // Without the lock the mutation commits, updates the caches and publishes its version while
    // the restore still holds the snapshot from before it; with the lock it waits for the restore.
    Try(Await.ready(mutating, 2.seconds))
    store.release.countDown()
    Await.result(restoring, 10.seconds)
    Await.result(mutating, 10.seconds).isRight shouldBe true
    // The published version is the store's, and the caches hold the change it vouches for.
    sup.policyVersions.current("acme") shouldBe store.policyVersions()("acme")
    sup.getTenant("acme").flatMap(_.acl.mode) shouldBe Some("opa")
  }

  "a restore with no local mutation running" should "not wait for the lock" in {
    val store = new SlowSnapshotStore
    val sup   = supervisor(store)
    val t0    = System.nanoTime()
    sup.restore()
    (System.nanoTime() - t0) / 1000000L should be < 1000L
  }

  "a restore that cannot take the lock in time" should
    "publish nothing, say so, and run once the mutation is done" in {
      val store = new SlowSnapshotStore
      val sup   = supervisor(store)
      val held  = new CountDownLatch(1)
      val go    = new CountDownLatch(1)
      store.onAclWrite.set { () =>
        held.countDown()
        go.await(30, TimeUnit.SECONDS)
        ()
      }
      val before   = sup.policyVersions.current("acme")
      val mutating = Future(sup.setTenantAcl("acme", Opa, "", "qod").unsafeRunSync())
      held.await(10, TimeUnit.SECONDS) shouldBe true
      // The ACL write has committed (and moved the version) but its mutation still holds a permit.
      store.policyVersions()("acme") should be > before
      val snapsBefore  = store.snapshots.get()
      val t0           = System.nanoTime()
      val (_, events)  = LogCapture.logged(classOf[PoolSupervisor].getName)(sup.restore())
      val waitedMs     = (System.nanoTime() - t0) / 1000000L
      val snapsSkipped = store.snapshots.get()
      go.countDown()
      Await.result(mutating, 10.seconds).isRight shouldBe true
      waitedMs should be >= PoolSupervisor.RestoreLockWaitMs
      // Skipped: no snapshot read, no cache swap, no version published.
      snapsSkipped shouldBe snapsBefore
      events.map(_.getFormattedMessage).filter(_.startsWith("restore:")) shouldBe List(
        s"restore: a local mutation held the versions lock past ${PoolSupervisor.RestoreLockWaitMs}" +
          " ms; skipped, the next NOTIFY, snapshot refresh or local mutation runs it"
      )
      // Retried as the mutation gave its permit back: the caches and the version agree.
      store.snapshots.get() should be > snapsBefore
      sup.policyVersions.current("acme") shouldBe store.policyVersions()("acme")
      sup.getTenant("acme").flatMap(_.acl.mode) shouldBe Some("opa")
    }

  it should "publish nothing before the timeout either" in {
    val store = new SlowSnapshotStore
    val sup   = supervisor(store)
    val held  = new CountDownLatch(1)
    val go    = new CountDownLatch(1)
    store.onAclWrite.set { () =>
      held.countDown()
      go.await(30, TimeUnit.SECONDS)
      ()
    }
    val before   = sup.policyVersions.current("acme")
    val mutating = Future(sup.setTenantAcl("acme", Opa, "", "qod").unsafeRunSync())
    held.await(10, TimeUnit.SECONDS) shouldBe true
    val snapsBefore = store.snapshots.get()
    val restoring   = Future(sup.restore())
    // Half the wait: the restore is still waiting for the permit, and has published nothing.
    Try(Await.ready(restoring, (PoolSupervisor.RestoreLockWaitMs / 2).millis))
    restoring.isCompleted shouldBe false
    store.snapshots.get() shouldBe snapsBefore
    sup.policyVersions.current("acme") shouldBe before
    go.countDown()
    Await.result(mutating, 10.seconds).isRight shouldBe true
    Await.result(restoring, 10.seconds)
    sup.policyVersions.current("acme") shouldBe store.policyVersions()("acme")
  }

  "a restore asked for inside a mutation" should
    "publish nothing there, not wait, and run once the mutation is done" in {
      val store  = new SlowSnapshotStore
      val sup    = supervisor(store)
      val seen   = new AtomicReference[Option[(Long, Int, Long)]](None)
      val before = sup.policyVersions.current("acme")
      store.onAclWrite.set { () =>
        // On the mutation's own thread, holding its permit: what a same-supervisor NOTIFY handler
        // or a failing nested mutation's recovery does.
        val snaps = store.snapshots.get()
        val t0    = System.nanoTime()
        sup.restore()
        seen.set(
          Some(
            (
              (System.nanoTime() - t0) / 1000000L,
              store.snapshots.get() - snaps,
              sup.policyVersions.current("acme")
            )
          )
        )
      }
      val snapsBefore = store.snapshots.get()
      sup.setTenantAcl("acme", Opa, "", "qod").unsafeRunSync().isRight shouldBe true
      val Some((waitedMs, snapsThere, versionThere)) = seen.get(): @unchecked
      waitedMs should be < 1000L
      // Deferred: no snapshot read and no version published while the permit was held.
      snapsThere shouldBe 0
      versionThere shouldBe before
      // Run as the mutation gave its permit back.
      store.snapshots.get() should be > snapsBefore
      sup.policyVersions.current("acme") shouldBe store.policyVersions()("acme")
      sup.getTenant("acme").flatMap(_.acl.mode) shouldBe Some("opa")
    }
