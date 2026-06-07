package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, PoolKey, RoleDistribution, RunningNode, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.nio.file.Files
import java.time.Instant
import scala.collection.concurrent.TrieMap

class NodeHandlersSpec extends AnyFlatSpec with Matchers:

  private def fixture =
    val backend = new QuackBackend:
      private val n = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(s.nodeId, s.poolKey, s.role, "127.0.0.1",
                            21000 + n.size, "tok", Some(1L), None, Instant.EPOCH,
                            maxConcurrent = s.maxConcurrent)
        n.put(s.nodeId, r); r
      }
      def stop(id: String) = IO { n.remove(id); () }
      def isAlive(id: String) = n.contains(id)
      def discoverExisting() = IO.pure(n.values.toList)
      def cleanup() = IO { n.clear() }

    val tracker = new NodeLoadTracker
    val sup = new PoolSupervisor(backend, tracker,
                                 new InMemoryControlPlaneStore())
    // Tenants are first-class - must exist before a pool can be created.
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup.createPool(PoolKey("acme", "acme_default", "sales"),
                   RoleDistribution(0, 1, 1)).unsafeRunSync()
    (sup, tracker, new NodeHandlers(sup, tracker, backend))

  "quarantineNode" should "mark the node unhealthy" in:
    val (sup, tracker, h) = fixture
    val nodeId = sup.list().head.nodes.head.nodeId
    h.quarantineNode(NodeOpRequest("acme", "acme_default", "sales", nodeId)).unsafeRunSync() shouldBe Right(())
    tracker.snapshot(nodeId).healthy shouldBe false

  it should "return NotFound for unknown node" in:
    val (sup, _, h) = fixture
    val out = h.quarantineNode(NodeOpRequest("acme", "acme_default", "sales", "nope")).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  "setRole" should "reject unknown role" in:
    val (sup, _, h) = fixture
    val nodeId = sup.list().head.nodes.head.nodeId
    val out = h.setRole(SetRoleRequest("acme", "acme_default", "sales", nodeId, "NOPE")).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)

  "setMaxConcurrent" should "update an existing node" in:
    val (sup, _, h) = fixture
    val nodeId = sup.list().head.nodes.head.nodeId
    val out = h.setMaxConcurrent(SetMaxConcurrentRequest("acme", "acme_default", "sales", nodeId, 7)).unsafeRunSync()
    out shouldBe Right(())
    sup.get(PoolKey("acme", "acme_default", "sales")).get.nodes.find(_.nodeId == nodeId).get.maxConcurrent shouldBe 7

  it should "reject negative max" in:
    val (sup, _, h) = fixture
    val nodeId = sup.list().head.nodes.head.nodeId
    val out = h.setMaxConcurrent(SetMaxConcurrentRequest("acme", "acme_default", "sales", nodeId, -1)).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)

  it should "return NotFound for unknown node" in:
    val (sup, _, h) = fixture
    val out = h.setMaxConcurrent(SetMaxConcurrentRequest("acme", "acme_default", "sales", "nope", 5)).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  "health" should "report counts" in:
    val (sup, _, _) = fixture
    val handler = new HealthHandler(sup)
    val out = handler.health.unsafeRunSync()
    out shouldBe a [Right[_, _]]
    out.toOption.get.poolsCount  shouldBe 1
    out.toOption.get.nodesCount  shouldBe 2