package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.runtime.{QuackBackend, TenantLocationSecrets}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

class PoolSupervisorTenantLocationSpec extends AnyFlatSpec with Matchers:

  private final class RecordingBackend extends QuackBackend:
    val specs                                     = new ConcurrentLinkedQueue[NodeSpec]()
    private val inner                             = StubQuackBackend.noop()
    def start(s: NodeSpec): IO[RunningNode]       = IO(specs.add(s)) *> inner.start(s)
    def stop(key: PoolKey, id: String): IO[Unit]  = inner.stop(key, id)
    def isAlive(id: String): Boolean              = inner.isAlive(id)
    def discoverExisting(): IO[List[RunningNode]] = inner.discoverExisting()
    def cleanup(): IO[Unit]                       = inner.cleanup()

  private val Keys = Map("s3_access_key_id" -> "AKIA", "s3_secret_access_key" -> "sekr3t")

  private def fixture() =
    val backend = new RecordingBackend
    val store   = new InMemoryControlPlaneStore()
    val sup     = new PoolSupervisor(backend, new NodeLoadTracker, store)
    sup.restore()
    List("acme", "globex").foreach { t =>
      sup.createTenant(Tenant(t)).unsafeRunSync()
      sup
        .createTenantDb(t, "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
        .unsafeRunSync()
    }
    (sup, backend, store)

  "a serving node" should "boot with its tenant's location secrets, and another tenant's not" in {
    val (sup, backend, _) = fixture()
    val l                 = sup
      .addTenantLocation("acme", "s3://acme-etl/", Keys, None, Some("alice"))
      .unsafeRunSync()
      .toOption
      .get
    sup.createPool(PoolKey("acme", "acme_default", "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup
      .createPool(PoolKey("globex", "globex_default", "bi"), RoleDistribution(0, 0, 1))
      .unsafeRunSync()
    val specs = backend.specs.asScala.toList
    specs.filter(_.poolKey.tenant == "acme").map(_.tenantStoreSql) shouldBe
      List(TenantLocationSecrets.initSql(List(l)))
    specs.filter(_.poolKey.tenant == "globex").map(_.tenantStoreSql) shouldBe List("")
  }

  "a maintenance node" should "get no tenant location secret" in {
    val (sup, _, _) = fixture()
    sup.addTenantLocation("acme", "s3://acme-etl/", Keys, None, None).unsafeRunSync()
    sup.maintenanceNodeSpec("acme", "acme_default").map(_.tenantStoreSql) shouldBe Some("")
  }

  "the supervisor" should "keep the locations in step with its store, across a restore" in {
    val (sup, _, store) = fixture()
    val l               = sup
      .addTenantLocation("acme", "s3://acme-etl/", Keys, None, None)
      .unsafeRunSync()
      .toOption
      .get
    sup.tenantLocations("acme") shouldBe List(l)
    store.deleteTenantLocation("acme", l.id)
    sup.tenantLocations("acme") shouldBe List(l)
    sup.restore()
    sup.tenantLocations("acme") shouldBe Nil
  }

  it should "bump the tenant's policy version on add and remove" in {
    val (sup, _, _) = fixture()
    // Every bump takes the next value of one shared sequence: only the direction is pinned.
    val v0 = sup.policyVersions.current("acme")
    val g0 = sup.policyVersions.current("globex")
    val l  =
      sup.addTenantLocation("acme", "s3://acme-etl/", Keys, None, None).unsafeRunSync().toOption.get
    val v1 = sup.policyVersions.current("acme")
    v1 should be > v0
    sup.removeTenantLocation("acme", l.id).unsafeRunSync().isRight shouldBe true
    sup.policyVersions.current("acme") should be > v1
    sup.policyVersions.current("globex") shouldBe g0
  }

  it should "forget a deleted tenant's locations" in {
    val (sup, _, _) = fixture()
    sup.addTenantLocation("globex", "s3://globex-etl/", Keys, None, None).unsafeRunSync()
    sup.deleteTenantDb("globex", "globex_default").unsafeRunSync()
    sup.deleteTenant("globex").unsafeRunSync() shouldBe Right(())
    sup.tenantLocations("globex") shouldBe Nil
  }
