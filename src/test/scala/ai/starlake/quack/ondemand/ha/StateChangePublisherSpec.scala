package ai.starlake.quack.ondemand.ha

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
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap

class StateChangePublisherSpec extends AnyFlatSpec with Matchers:

  private final class Recording extends StateChangePublisher:
    var topology                = 0
    var rbac                    = 0
    def topologyChanged(): Unit = topology += 1
    def rbacChanged(): Unit     = rbac += 1

  private final class StubBackend extends QuackBackend:
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

  private def fresh(): (PoolSupervisor, Recording) =
    val rec = new Recording
    val sup = new PoolSupervisor(
      new StubBackend,
      new NodeLoadTracker,
      new InMemoryControlPlaneStore(),
      publish = rec
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    (sup, rec)

  "PoolSupervisor" should "publish topologyChanged on pool lifecycle mutations" in {
    val (sup, rec) = fresh()
    val before     = rec.topology // tenant + tenantDb creation already published
    before should be > 0
    // createTenantDb("acme", "default", ...) composes to "acme_default"
    val key = PoolKey("acme", "acme_default", "bi")
    sup.createPool(key, RoleDistribution(1, 0, 0)).unsafeRunSync()
    sup.scale(key, 2, RoleDistribution(2, 0, 0), force = false).unsafeRunSync()
    sup.deletePool(key, force = true).unsafeRunSync()
    rec.topology should be >= before + 3
  }

  it should "publish rbacChanged on RBAC mutations but not on restore" in {
    val (sup, rec) = fresh()
    sup.createRole("acme", "analyst").unsafeRunSync()
    rec.rbac shouldBe 1
    sup.restore()
    rec.rbac shouldBe 1 // restore invalidates locally, never broadcasts
  }
