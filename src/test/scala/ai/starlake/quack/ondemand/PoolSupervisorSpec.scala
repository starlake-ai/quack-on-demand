package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, PoolKey, Role, RoleDistribution, RunningNode, Tenant}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.StateStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.Instant
import scala.collection.concurrent.TrieMap

class PoolSupervisorSpec extends AnyFlatSpec with Matchers:

  private val key: PoolKey = PoolKey("acme", "sales")
  private val ms  = Map("pgHost" -> "localhost")

  /** Captures NodeSpecs as the backend sees them — used to assert the
    * merged metastore that PoolSupervisor passes through. */
  private final class CapturingBackend extends QuackBackend:
    private val nodes = TrieMap.empty[String, RunningNode]
    val specs = scala.collection.mutable.ListBuffer.empty[NodeSpec]
    def start(spec: NodeSpec): IO[RunningNode] = IO {
      specs += spec
      val n = RunningNode(spec.nodeId, spec.poolKey, spec.role,
        "127.0.0.1", 21000 + nodes.size, "tok-" + spec.nodeId,
        Some(1L), None, Instant.EPOCH, maxConcurrent = spec.maxConcurrent)
      nodes.put(spec.nodeId, n); n
    }
    def stop(id: String): IO[Unit] = IO { nodes.remove(id); () }
    def isAlive(id: String): Boolean = nodes.contains(id)
    def discoverExisting(): IO[List[RunningNode]] = IO.pure(nodes.values.toList)
    def cleanup(): IO[Unit] = IO { nodes.clear() }

  private def fakeBackend(): QuackBackend = new CapturingBackend

  private def freshSupervisor() =
    val stateFile = Files.createTempFile("quack-sup-", ".json")
    val sup = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, StateStore(stateFile))
    // Tests that create a pool need the tenant up-front. Pre-register the
    // default test tenant; tests that exercise tenant CRUD explicitly use
    // a different name.
    sup.createTenant(Tenant("acme", Map.empty)).unsafeRunSync()
    sup

  private def freshSupervisorWithBackend(): (PoolSupervisor, CapturingBackend) =
    val stateFile = Files.createTempFile("quack-sup-", ".json")
    val b   = new CapturingBackend
    val sup = new PoolSupervisor(b, new NodeLoadTracker, StateStore(stateFile))
    (sup, b)

  "PoolSupervisor.createPool" should "start N nodes matching the role distribution" in:
    val sup = freshSupervisor()
    val nodes = sup.createPool(key, RoleDistribution(0, 2, 1), ms, Map.empty).unsafeRunSync()
    nodes.map(_.role).sortBy(_.toString) shouldBe List(Role.Dual, Role.ReadOnly, Role.ReadOnly)
    sup.list().map(_.key) shouldBe List(key)

  it should "reject distribution that doesn't sum" in:
    val sup = freshSupervisor()
    intercept[IllegalArgumentException](
      sup.createPool(key, RoleDistribution(-1, 2, 0), ms, Map.empty).unsafeRunSync()
    )

  it should "apply pool-level maxConcurrentPerNode to every node at create" in:
    val sup = freshSupervisor()
    val nodes = sup.createPool(key, RoleDistribution(0, 1, 1), ms, Map.empty,
                                maxConcurrentPerNode = 4).unsafeRunSync()
    nodes.forall(_.maxConcurrent == 4) shouldBe true

  "PoolSupervisor.scale" should "add nodes when target > current" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 1, 0), ms, Map.empty).unsafeRunSync()
    sup.scale(key, targetSize = 3, RoleDistribution(0, 2, 1), force = false).unsafeRunSync()
    sup.get(key).get.nodes.size shouldBe 3

  it should "remove nodes when target < current (graceful by default)" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 3, 0), ms, Map.empty).unsafeRunSync()
    sup.scale(key, 1, RoleDistribution(0, 1, 0), force = false).unsafeRunSync()
    sup.get(key).get.nodes.size shouldBe 1

  "PoolSupervisor.setMaxConcurrent" should "mutate one node's cap" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 0, 2), ms, Map.empty,
                    maxConcurrentPerNode = 2).unsafeRunSync()
    val firstId = sup.get(key).get.nodes.head.nodeId
    val updated = sup.setMaxConcurrent(key, firstId, 7).unsafeRunSync()
    updated.map(_.maxConcurrent) shouldBe Some(7)
    sup.get(key).get.nodes.head.maxConcurrent shouldBe 7

  it should "return None for unknown node" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 0, 1), ms, Map.empty).unsafeRunSync()
    sup.setMaxConcurrent(key, "nope", 5).unsafeRunSync() shouldBe None

  "PoolSupervisor.stopPool" should "stop all nodes and forget the pool" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 1, 1), ms, Map.empty).unsafeRunSync()
    sup.stopPool(key, force = true).unsafeRunSync()
    sup.get(key) shouldBe None

  // ---------- Tenant CRUD ----------

  "PoolSupervisor.createTenant" should "register a new tenant" in:
    val (sup, _) = freshSupervisorWithBackend()
    val res = sup.createTenant(Tenant("foo", Map("pgHost" -> "h"))).unsafeRunSync()
    res shouldBe Right(Tenant("foo", Map("pgHost" -> "h")))
    sup.getTenant("foo") shouldBe Some(Tenant("foo", Map("pgHost" -> "h")))
    sup.listTenants().map(_.name) shouldBe List("foo")

  it should "reject a duplicate tenant name" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("foo", Map.empty)).unsafeRunSync()
    val res = sup.createTenant(Tenant("foo", Map("k" -> "v"))).unsafeRunSync()
    res.left.toOption.getOrElse("") should include("already exists")

  "PoolSupervisor.setTenantMetastore" should "overwrite the tenant's metastore" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("foo", Map("a" -> "1"))).unsafeRunSync()
    val updated = sup.setTenantMetastore("foo", Map("b" -> "2")).unsafeRunSync()
    updated shouldBe Some(Tenant("foo", Map("b" -> "2")))
    sup.getTenant("foo").map(_.metastore) shouldBe Some(Map("b" -> "2"))

  it should "return None for an unknown tenant" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.setTenantMetastore("ghost", Map("x" -> "y")).unsafeRunSync() shouldBe None

  "PoolSupervisor.deleteTenant" should "remove a tenant with no pools" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("foo", Map.empty)).unsafeRunSync()
    sup.deleteTenant("foo").unsafeRunSync() shouldBe Right(())
    sup.getTenant("foo") shouldBe None

  it should "refuse to delete a tenant that still has pools" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme", Map.empty)).unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1), ms, Map.empty).unsafeRunSync()
    val out = sup.deleteTenant("acme").unsafeRunSync()
    out.left.toOption.getOrElse("") should include("active pool")
    sup.getTenant("acme") shouldBe defined

  it should "return Left for an unknown tenant" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.deleteTenant("ghost").unsafeRunSync().isLeft shouldBe true

  // ---------- Metastore merge ----------

  "PoolSupervisor.createPool" should "merge tenant metastore into NodeSpec.metastore" in:
    val (sup, backend) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme", Map(
      "pgHost"   -> "tenant-host",
      "pgPort"   -> "5432",
      "shared"   -> "tenant-val"
    ))).unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1),
      // Per-pool wins on collision; new keys layer on top.
      metastore = Map("shared" -> "pool-val", "pgDb" -> "db1"),
      s3 = Map.empty).unsafeRunSync()
    val spec = backend.specs.head
    spec.metastore("pgHost") shouldBe "tenant-host"
    spec.metastore("pgPort") shouldBe "5432"
    spec.metastore("pgDb")   shouldBe "db1"
    spec.metastore("shared") shouldBe "pool-val"

  it should "leave the merged metastore frozen on later scale-up even if tenant metastore changes" in:
    val (sup, backend) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme", Map("pgHost" -> "v1"))).unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1), Map.empty, Map.empty).unsafeRunSync()
    // Mutate the tenant's metastore *after* pool creation.
    sup.setTenantMetastore("acme", Map("pgHost" -> "v2")).unsafeRunSync()
    sup.scale(key, targetSize = 2, RoleDistribution(0, 0, 2), force = false).unsafeRunSync()
    // The second node should still see v1, captured at pool-create time.
    backend.specs.size shouldBe 2
    backend.specs.last.metastore("pgHost") shouldBe "v1"