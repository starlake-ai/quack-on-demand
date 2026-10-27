package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PoolSupervisorTenantLocationSpec extends AnyFlatSpec with Matchers:

  private val Root = Some("/srv/qod")

  private def fixture() =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    List("acme", "globex").foreach { t =>
      sup.createTenant(Tenant(t)).unsafeRunSync()
      sup
        .createTenantDb(t, "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
        .unsafeRunSync()
    }
    (sup, store)

  private def add(sup: PoolSupervisor, tenant: String, loc: String) =
    sup.addTenantLocation(tenant, loc, Root, Some("alice")).unsafeRunSync()

  "the supervisor" should "keep the locations in step with its store, across a restore" in {
    val (sup, store) = fixture()
    val l            = add(sup, "acme", "/srv/qod/acme/in").toOption.get
    l.location shouldBe "/srv/qod/acme/in/"
    sup.tenantLocations("acme") shouldBe List(l)
    store.deleteTenantLocation("acme", l.id)
    sup.tenantLocations("acme") shouldBe List(l)
    sup.restore()
    sup.tenantLocations("acme") shouldBe Nil
  }

  it should "refuse an s3 location: S3 access is per user" in {
    val (sup, _) = fixture()
    add(sup, "acme", "s3://acme-etl/").left.map(_.message) match
      case Left(m)  => m should startWith("invalid_location: ")
      case Right(l) => fail(s"stored ${l.location}")
    sup.tenantLocations("acme") shouldBe Nil
  }

  it should "bump the tenant's policy version on add and remove" in {
    val (sup, _) = fixture()
    // Every bump takes the next value of one shared sequence: only the direction is pinned.
    val v0 = sup.policyVersions.current("acme")
    val g0 = sup.policyVersions.current("globex")
    val l  = add(sup, "acme", "/srv/qod/acme/in/").toOption.get
    val v1 = sup.policyVersions.current("acme")
    v1 should be > v0
    sup.removeTenantLocation("acme", l.id).unsafeRunSync().isRight shouldBe true
    sup.policyVersions.current("acme") should be > v1
    sup.policyVersions.current("globex") shouldBe g0
  }

  it should "forget a deleted tenant's locations" in {
    val (sup, _) = fixture()
    add(sup, "globex", "/srv/qod/globex/in/")
    sup.deleteTenantDb("globex", "globex_default").unsafeRunSync()
    sup.deleteTenant("globex").unsafeRunSync() shouldBe Right(())
    sup.tenantLocations("globex") shouldBe Nil
  }
