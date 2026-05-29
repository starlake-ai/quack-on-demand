package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, PoolKey, RoleDistribution, RunningNode, Tenant}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.StateStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.nio.file.Files
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

  private def freshHandlers =
    val tracker = new NodeLoadTracker
    val sup = new PoolSupervisor(stubBackend, tracker,
                                 StateStore(Files.createTempFile("h-", ".json")))
    // Pool creation now requires the tenant to be registered first.
    sup.createTenant(Tenant("acme", Map.empty)).unsafeRunSync()
    new PoolHandlers(sup, tracker)

  /** Variant without the pre-registered tenant - for the missing-tenant test. */
  private def handlersWithoutTenant =
    val tracker = new NodeLoadTracker
    val sup = new PoolSupervisor(stubBackend, tracker,
                                 StateStore(Files.createTempFile("h-", ".json")))
    new PoolHandlers(sup, tracker)

  "createPool" should "create a pool and return node info with maxConcurrent" in:
    val h = freshHandlers
    val out = h.createPool(CreatePoolRequest(
      tenant = "acme", pool = "sales", size = 2,
      roleDistribution = RoleDistribution(0, 1, 1),
      metastore = Map.empty,
      maxConcurrentPerNode = 4
    )).unsafeRunSync()
    out shouldBe a [Right[_, _]]
    val Right(resp) = out: @unchecked
    resp.nodes.size shouldBe 2
    resp.nodes.forall(_.maxConcurrent == 4) shouldBe true

  it should "reject mismatched role distribution" in:
    val h = freshHandlers
    val out = h.createPool(CreatePoolRequest(
      "acme", "sales", 3, RoleDistribution(0, 1, 1), Map.empty
    )).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)

  it should "return Conflict for duplicate pool" in:
    val h = freshHandlers
    val req = CreatePoolRequest("acme", "sales", 1, RoleDistribution(0, 0, 1), Map.empty)
    h.createPool(req).unsafeRunSync() shouldBe a [Right[_, _]]
    val out = h.createPool(req).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.Conflict)

  "scalePool" should "fail when pool doesn't exist" in:
    val h = freshHandlers
    val out = h.scalePool(ScalePoolRequest(
      "acme", "missing", 2, RoleDistribution(0, 1, 1), force = false
    )).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  "stopPool" should "stop a known pool" in:
    val h = freshHandlers
    h.createPool(CreatePoolRequest("acme", "sales", 1, RoleDistribution(0, 0, 1), Map.empty)).unsafeRunSync()
    h.stopPool(StopPoolRequest("acme", "sales", force = true)).unsafeRunSync() shouldBe Right(())

  "listPools" should "return all pools" in:
    val h = freshHandlers
    h.createPool(CreatePoolRequest("acme", "sales", 1, RoleDistribution(0, 0, 1), Map.empty)).unsafeRunSync()
    h.createPool(CreatePoolRequest("acme", "ops",   1, RoleDistribution(0, 0, 1), Map.empty)).unsafeRunSync()
    val out = h.listPools().unsafeRunSync()
    out.toOption.get.pools.size shouldBe 2

  "poolStatus" should "return 404 for unknown pool" in:
    val h = freshHandlers
    val out = h.poolStatus("acme", "missing").unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  "createPool" should "return 404 when the tenant is not registered" in:
    val h = handlersWithoutTenant
    val out = h.createPool(CreatePoolRequest(
      tenant = "unknown", pool = "sales", size = 1,
      roleDistribution = RoleDistribution(0, 0, 1),
      metastore = Map.empty
    )).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)
    out.left.toOption.map(_._2.error) shouldBe Some("tenant_not_found")