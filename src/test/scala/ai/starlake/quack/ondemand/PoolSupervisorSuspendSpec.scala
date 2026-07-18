package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

class PoolSupervisorSuspendSpec extends AnyFlatSpec with Matchers:

  private val key: PoolKey = PoolKey("acme", "acme_default", "sales")

  private class RecordingSink extends ManagerEventSink:
    val seen                        = ListBuffer.empty[ManagerEvent]
    def emit(e: ManagerEvent): Unit = seen.synchronized(seen += e)

  /** Supervisor with tenant + tenant-db + a 2-node pool already running. */
  private def setup() =
    val sink    = new RecordingSink
    val store   = new InMemoryControlPlaneStore()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(new StubQuackBackend(), tracker, store, events = sink)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 1, 1)).unsafeRunSync()
    (sup, store, sink)

  "suspendPool" should "drain all nodes but keep the distribution" in {
    val (sup, store, _) = setup()
    sup.suspendPool(key, "rest").unsafeRunSync().isRight shouldBe true
    val st = sup.get(key).get
    st.nodes shouldBe empty
    st.suspended shouldBe true
    st.distribution.total shouldBe 2
    store.listPools(store.listTenantDbs("acme").head.id).head.suspended shouldBe true
  }

  it should "be idempotent and emit PoolSuspended with the reason" in {
    val (sup, _, sink) = setup()
    sup.suspendPool(key, "module").unsafeRunSync().isRight shouldBe true
    sup.suspendPool(key, "module").unsafeRunSync().isRight shouldBe true
    sink.seen.collect { case e: ManagerEvent.PoolSuspended => e }.head.reason shouldBe "module"
  }

  it should "stop nodes with reason suspend" in {
    val (sup, _, sink) = setup()
    sup.suspendPool(key, "rest").unsafeRunSync()
    val stops = sink.seen.collect { case e: ManagerEvent.NodeStopped => e }
    stops should have size 2
    all(stops.map(_.reason)) shouldBe "suspend"
  }

  "resumePool" should "respawn to the stored distribution and emit PoolResumed" in {
    val (sup, _, sink) = setup()
    sup.suspendPool(key, "rest").unsafeRunSync()
    sup.resumePool(key, "query").unsafeRunSync().isRight shouldBe true
    val st = sup.get(key).get
    st.suspended shouldBe false
    st.nodes should have size 2
    sink.seen.collect { case e: ManagerEvent.PoolResumed => e }.head.reason shouldBe "query"
  }

  it should "be a no-op on a pool that is not suspended" in {
    val (sup, _, _) = setup()
    sup.resumePool(key, "rest").unsafeRunSync().isRight shouldBe true
    sup.get(key).get.nodes should have size 2
  }

  it should "return NotFound for an unknown pool" in {
    val (sup, _, _) = setup()
    val bad         = PoolKey("acme", "acme_default", "nope")
    sup.resumePool(bad, "rest").unsafeRunSync().isLeft shouldBe true
    sup.suspendPool(bad, "rest").unsafeRunSync().isLeft shouldBe true
  }

  "reconcile" should "skip suspended pools" in {
    val (sup, _, _) = setup()
    sup.suspendPool(key, "rest").unsafeRunSync()
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes shouldBe empty
  }
