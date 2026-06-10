package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, RoleDistribution, RunningNode, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant
import scala.collection.concurrent.TrieMap

class PoolHandlersSpec extends AnyFlatSpec with Matchers:

  private def stubBackend: QuackBackend = new QuackBackend:
    private val n = TrieMap.empty[String, RunningNode]
    def start(s: NodeSpec) = IO {
      val r = RunningNode(s.nodeId, s.poolKey, s.role, "127.0.0.1",
                          21000 + n.size, "tok", Some(1L), None, Instant.EPOCH,
                          maxConcurrent = s.maxConcurrent)
      n.put(s.nodeId, r); r
    }
    def stop(id: String)    = IO { n.remove(id); () }
    def isAlive(id: String) = n.contains(id)
    def discoverExisting()  = IO.pure(n.values.toList)
    def cleanup()           = IO { n.clear() }

  /** Supervisor with tenant `acme` + tenant-db `acme_default` already in
    * place, so handler tests can call `createPool` directly. */
  private def freshHandlers =
    val tracker = new NodeLoadTracker
    val sup = new PoolSupervisor(stubBackend, tracker,
                                 new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    new PoolHandlers(sup, tracker)

  /** Variant without any tenant/tenant-db -- for the missing-tenant test. */
  private def handlersWithoutTenant =
    val tracker = new NodeLoadTracker
    val sup = new PoolSupervisor(stubBackend, tracker,
                                 new InMemoryControlPlaneStore())
    new PoolHandlers(sup, tracker)

  private def req(pool: String = "sales", size: Int = 2,
                  dist: RoleDistribution = RoleDistribution(0, 1, 1),
                  maxConcurrentPerNode: Int = 0): CreatePoolRequest =
    CreatePoolRequest(
      tenant   = "acme",
      tenantDb = "acme_default",
      pool     = pool,
      size     = size,
      roleDistribution     = dist,
      maxConcurrentPerNode = maxConcurrentPerNode
    )

  "createPool" should "create a pool and return node info with maxConcurrent" in:
    val h = freshHandlers
    val out = h.createPool(req(maxConcurrentPerNode = 4)).unsafeRunSync()
    out shouldBe a [Right[?, ?]]
    val Right(resp) = out: @unchecked
    resp.nodes.size shouldBe 2
    resp.nodes.forall(_.maxConcurrent == 4) shouldBe true

  it should "reject mismatched role distribution" in:
    val h = freshHandlers
    val out = h.createPool(req(size = 3, dist = RoleDistribution(0, 1, 1))).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)

  it should "return Conflict for duplicate pool" in:
    val h = freshHandlers
    val r  = req(size = 1, dist = RoleDistribution(0, 0, 1))
    h.createPool(r).unsafeRunSync() shouldBe a [Right[?, ?]]
    val out = h.createPool(r).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.Conflict)

  it should "return 404 when the tenant is not registered" in:
    val h = handlersWithoutTenant
    val out = h.createPool(CreatePoolRequest(
      tenant   = "unknown",
      tenantDb = "unknown_default",
      pool     = "sales",
      size     = 1,
      roleDistribution = RoleDistribution(0, 0, 1)
    )).unsafeRunSync()
    out.left.toOption.map(_._1)        shouldBe Some(StatusCode.NotFound)
    out.left.toOption.map(_._2.error)  shouldBe Some("tenant_not_found")

  it should "return 404 when the tenant-db is not registered" in:
    val tracker = new NodeLoadTracker
    val sup = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    // No createTenantDb -> the handler should refuse.
    val h = new PoolHandlers(sup, tracker)
    val out = h.createPool(req()).unsafeRunSync()
    out.left.toOption.map(_._1)        shouldBe Some(StatusCode.NotFound)
    out.left.toOption.map(_._2.error)  shouldBe Some("tenant_db_not_found")

  "scalePool" should "fail when pool doesn't exist" in:
    val h = freshHandlers
    val out = h.scalePool(ScalePoolRequest(
      "acme", "acme_default", "missing", 2, RoleDistribution(0, 1, 1), force = false
    )).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  "stopPool" should "stop a known pool" in:
    val h = freshHandlers
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1))).unsafeRunSync()
    h.stopPool(StopPoolRequest("acme", "acme_default", "sales", force = true))
      .unsafeRunSync() shouldBe Right(())

  "listPools" should "return all pools" in:
    val h = freshHandlers
    h.createPool(req(pool = "sales", size = 1, dist = RoleDistribution(0, 0, 1))).unsafeRunSync()
    h.createPool(req(pool = "ops",   size = 1, dist = RoleDistribution(0, 0, 1))).unsafeRunSync()
    val out = h.listPools(None)(_ => None).unsafeRunSync()
    out.toOption.get.pools.size shouldBe 2

  "poolStatus" should "return 404 for unknown pool" in:
    val h = freshHandlers
    val out = h.poolStatus("acme", "acme_default", "missing").unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)