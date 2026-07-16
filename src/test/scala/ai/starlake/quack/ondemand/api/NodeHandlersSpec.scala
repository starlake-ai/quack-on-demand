package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.nio.file.Files

class NodeHandlersSpec extends AnyFlatSpec with Matchers:

  private def fixture =
    val backend = new StubQuackBackend()

    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    // Tenants are first-class - must exist before a pool can be created.
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup
      .createPool(PoolKey("acme", "acme_default", "sales"), RoleDistribution(0, 1, 1))
      .unsafeRunSync()
    val store = new InMemoryControlPlaneStore()
    (sup, tracker, new NodeHandlers(sup, tracker, store, StateChangePublisher.noop))

  "quarantineNode" should "mark the node quarantined" in:
    val (sup, tracker, h) = fixture
    val nodeId            = sup.list().head.nodes.head.nodeId
    h.quarantineNode(NodeOpRequest("acme", "acme_default", "sales", nodeId), None)((_: String) =>
      None
    ).unsafeRunSync() shouldBe Right(())
    tracker.snapshot(nodeId).quarantined shouldBe true

  it should "return NotFound for unknown node" in:
    val (sup, _, h) = fixture
    val out         = h
      .quarantineNode(NodeOpRequest("acme", "acme_default", "sales", "nope"), None)((_: String) =>
        None
      )
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  "setMaxConcurrent" should "update an existing node" in:
    val (sup, _, h) = fixture
    val nodeId      = sup.list().head.nodes.head.nodeId
    val out         = h
      .setMaxConcurrent(SetMaxConcurrentRequest("acme", "acme_default", "sales", nodeId, 7), None)(
        (_: String) => None
      )
      .unsafeRunSync()
    out shouldBe Right(())
    sup
      .get(PoolKey("acme", "acme_default", "sales"))
      .get
      .nodes
      .find(_.nodeId == nodeId)
      .get
      .maxConcurrent shouldBe 7

  it should "reject negative max" in:
    val (sup, _, h) = fixture
    val nodeId      = sup.list().head.nodes.head.nodeId
    val out         = h
      .setMaxConcurrent(SetMaxConcurrentRequest("acme", "acme_default", "sales", nodeId, -1), None)(
        (_: String) => None
      )
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)

  it should "return NotFound for unknown node" in:
    val (sup, _, h) = fixture
    val out         = h
      .setMaxConcurrent(SetMaxConcurrentRequest("acme", "acme_default", "sales", "nope", 5), None)(
        (_: String) => None
      )
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  "health" should "report counts" in:
    val (sup, _, _) = fixture
    val handler     = new HealthHandler(sup)
    val out         = handler.health.unsafeRunSync()
    out shouldBe a[Right[_, _]]
    out.toOption.get.poolsCount shouldBe 1
    out.toOption.get.nodesCount shouldBe 2
