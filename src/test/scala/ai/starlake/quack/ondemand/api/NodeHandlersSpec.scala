package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  Role,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, InMemoryControlPlaneStore}
import cats.effect.IO
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

  "restartNode" should "map a raised backend error to 502, not a bodyless 500" in:
    val inner   = new StubQuackBackend()
    val backend = new QuackBackend:
      def start(spec: NodeSpec): IO[RunningNode]   = inner.start(spec)
      def stop(key: PoolKey, id: String): IO[Unit] =
        IO.raiseError(new RuntimeException("409: object is being deleted"))
      def isAlive(id: String): Boolean              = inner.isAlive(id)
      def discoverExisting(): IO[List[RunningNode]] = inner.discoverExisting()
      def cleanup(): IO[Unit]                       = inner.cleanup()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup
      .createPool(PoolKey("acme", "acme_default", "sales"), RoleDistribution(0, 0, 1))
      .unsafeRunSync()
    val h =
      new NodeHandlers(sup, tracker, new InMemoryControlPlaneStore(), StateChangePublisher.noop)
    val nodeId = sup.list().head.nodes.head.nodeId
    val out    = h
      .restartNode(NodeOpRequest("acme", "acme_default", "sales", nodeId), None)((_: String) =>
        None
      )
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadGateway)
    out.left.toOption.map(_._2.error) shouldBe Some("backend_error")

  it should "keep NotFound as 404 for an unknown node (never the raised arm)" in:
    val (sup, _, h) = fixture
    val out         = h
      .restartNode(NodeOpRequest("acme", "acme_default", "sales", "nope"), None)((_: String) =>
        None
      )
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)
    out.left.toOption.map(_._2.error) shouldBe Some("not_found")

  "quarantineNode" should "map a raised store error to 502, not a bodyless 500" in:
    val backend = new StubQuackBackend()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup
      .createPool(PoolKey("acme", "acme_default", "sales"), RoleDistribution(0, 0, 1))
      .unsafeRunSync()
    // Handler-side store raises on the quarantine write (simulated Postgres outage).
    val failing = new ControlPlaneStore:
      val inner = new InMemoryControlPlaneStore()
      export inner.{setNodeQuarantined as _, *}
      override def setNodeQuarantined(nodeId: String, quarantined: Boolean): Unit =
        throw new RuntimeException("pg down")
    val h      = new NodeHandlers(sup, tracker, failing, StateChangePublisher.noop)
    val nodeId = sup.list().head.nodes.head.nodeId
    val out    = h
      .quarantineNode(NodeOpRequest("acme", "acme_default", "sales", nodeId), None)((_: String) =>
        None
      )
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadGateway)
    out.left.toOption.map(_._2.error) shouldBe Some("backend_error")
    // Store-first ordering: the in-memory flag must not have been flipped.
    tracker.snapshot(nodeId).quarantined shouldBe false

  "setMaxConcurrent" should "map a raised store error to 502, not a bodyless 500" in:
    val backend = new StubQuackBackend()
    val tracker = new NodeLoadTracker
    // Supervisor-side store raises on the node upsert once armed (post pool creation).
    // Named class: an anonymous instance widens to ControlPlaneStore, hiding the flag.
    final class FailingUpsertStore extends ControlPlaneStore:
      val inner = new InMemoryControlPlaneStore()
      export inner.{upsertNode as _, *}
      @volatile var failUpsertNode                         = false
      def upsertNode(n: RunningNode, poolId: String): Unit =
        if failUpsertNode then throw new RuntimeException("pg down")
        else inner.upsertNode(n, poolId)
    val failingStore = new FailingUpsertStore
    val sup          = new PoolSupervisor(backend, tracker, failingStore)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup
      .createPool(PoolKey("acme", "acme_default", "sales"), RoleDistribution(0, 0, 1))
      .unsafeRunSync()
    failingStore.failUpsertNode = true
    val h =
      new NodeHandlers(sup, tracker, new InMemoryControlPlaneStore(), StateChangePublisher.noop)
    val nodeId = sup.list().head.nodes.head.nodeId
    val out    = h
      .setMaxConcurrent(SetMaxConcurrentRequest("acme", "acme_default", "sales", nodeId, 7), None)(
        (_: String) => None
      )
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadGateway)
    out.left.toOption.map(_._2.error) shouldBe Some("backend_error")

  "health" should "report counts" in:
    val (sup, _, _) = fixture
    val handler     = new HealthHandler(sup)
    val out         = handler.health.unsafeRunSync()
    out shouldBe a[Right[_, _]]
    out.toOption.get.poolsCount shouldBe 1
    out.toOption.get.nodesCount shouldBe 2
