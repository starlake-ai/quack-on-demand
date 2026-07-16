package ai.starlake.quack.ondemand.ha

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StateChangePublisherSpec extends AnyFlatSpec with Matchers:

  private final class Recording extends StateChangePublisher:
    var topology                = 0
    var rbac                    = 0
    def topologyChanged(): Unit = topology += 1
    def rbacChanged(): Unit     = rbac += 1

  private def fresh(): (PoolSupervisor, Recording) =
    val rec = new Recording
    val sup = new PoolSupervisor(
      new StubQuackBackend(tokenFor = StubQuackBackend.PerNodeToken),
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

  it should "broadcast both channels via broadcastStateChanged for external store writers" in {
    val (sup, rec) = fresh()
    val t0         = rec.topology
    val r0         = rec.rbac
    sup.broadcastStateChanged()
    rec.topology shouldBe t0 + 1
    rec.rbac shouldBe r0 + 1
  }
