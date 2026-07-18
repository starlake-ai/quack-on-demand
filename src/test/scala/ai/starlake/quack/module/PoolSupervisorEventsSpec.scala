package ai.starlake.quack.module

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

class PoolSupervisorEventsSpec extends AnyFlatSpec with Matchers:

  private val received               = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
  private val sink: ManagerEventSink = e => { received.add(e); () }

  /** Same fake backend the neighboring PoolSupervisorSpec uses: records started/stopped node ids
    * without spawning a real process.
    */
  private final class StubQuackBackend extends QuackBackend:
    private val nodes                          = TrieMap.empty[String, RunningNode]
    def start(spec: NodeSpec): IO[RunningNode] = IO {
      val n = RunningNode(
        spec.nodeId,
        spec.poolKey,
        spec.role,
        "127.0.0.1",
        21000 + nodes.size,
        "tok-" + spec.nodeId,
        Some(1L),
        None,
        Instant.EPOCH,
        maxConcurrent = spec.maxConcurrent
      )
      nodes.put(spec.nodeId, n); n
    }
    def stop(id: String): IO[Unit]                = IO { nodes.remove(id); () }
    def isAlive(id: String): Boolean              = nodes.contains(id)
    def discoverExisting(): IO[List[RunningNode]] = IO.pure(nodes.values.toList)
    def cleanup(): IO[Unit]                       = IO(nodes.clear())

  "PoolSupervisor" should "emit tenant, tenant-db, pool, and node lifecycle events" in {
    val sup = new PoolSupervisor(
      new StubQuackBackend,
      new NodeLoadTracker,
      new InMemoryControlPlaneStore(),
      events = sink
    )

    // 1. createTenant -> received contains TenantCreated(<tenant id>)
    val tenant = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    received.asScala.toList should contain(ManagerEvent.TenantCreated(tenant.id))

    // 2. createTenantDb -> received contains TenantDbCreated(tenant, tenantDbName)
    val td = sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
      .toOption
      .get
    received.asScala.toList should contain(ManagerEvent.TenantDbCreated("acme", td.name))

    // 3. createPool (size >= 1) -> received contains PoolCreated(tenant, tenantDb, pool)
    //    and at least one NodeStarted(tenant, tenantDb, pool, nodeId)
    val key   = PoolKey("acme", td.name, "sales")
    val nodes = sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    received.asScala.toList should contain(
      ManagerEvent.PoolCreated(key.tenant, key.tenantDb, key.pool)
    )
    val startedIds     = nodes.map(_.nodeId)
    val startedNodeIds = received.asScala.toList.collect { case e: ManagerEvent.NodeStarted =>
      e.nodeId
    }
    startedIds.forall(startedNodeIds.contains).shouldBe(true)

    // 4. deletePool(force=true) -> received contains NodeStopped(..., reason = "pool-delete")
    //    and PoolDeleted(tenant, tenantDb, pool)
    sup.deletePool(key, force = true).unsafeRunSync()
    val stoppedForPoolDelete = received.asScala.toList.collect {
      case e: ManagerEvent.NodeStopped if e.reason == "pool-delete" => e.nodeId
    }
    startedIds.forall(stoppedForPoolDelete.contains).shouldBe(true)
    received.asScala.toList should contain(
      ManagerEvent.PoolDeleted(key.tenant, key.tenantDb, key.pool)
    )

    // 5. deleteTenant -> received contains TenantDeleted(<tenant id>)
    sup.deleteTenantDb("acme", td.name).unsafeRunSync()
    sup.deleteTenant("acme").unsafeRunSync()
    received.asScala.toList should contain(ManagerEvent.TenantDeleted(tenant.id))
  }
