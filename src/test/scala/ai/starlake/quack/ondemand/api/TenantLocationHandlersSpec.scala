package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class TenantLocationHandlersSpec extends AnyFlatSpec with Matchers:

  private final class Fixture(filesRoot: Option[String] = Some("/srv/qod")):
    val sup = new PoolSupervisor(
      StubQuackBackend.noop(),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore()
    )
    sup.restore()
    List("acme", "globex").foreach { t =>
      sup.createTenant(Tenant(t)).unsafeRunSync()
      sup
        .createTenantDb(t, "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
        .unsafeRunSync()
    }
    val h = new TenantLocationHandlers(sup, filesRoot)

    def add(tenant: String, loc: String, scope: SessionScope.Resolver) =
      h.add(AddTenantLocationRequest(tenant, loc), None)(scope).unsafeRunSync()

  private val Su: SessionScope.Resolver        = _ => SessionScope.Superuser
  private val AcmeAdmin: SessionScope.Resolver = _ => SessionScope(false, Set("acme"))
  private val NoGrant: SessionScope.Resolver   = _ => SessionScope.NoAccess

  "add" should "store a local folder under the tenant's root for a tenant admin" in {
    val f = new Fixture
    val r = f.add("acme", "/srv/qod/acme/in", AcmeAdmin).toOption.get
    r.location.location shouldBe "/srv/qod/acme/in/"
    r.location.kind shouldBe "local"
    val listed = f.h.list(TenantLocationListRequest("acme"), None)(AcmeAdmin).unsafeRunSync()
    listed.map(_.locations.map(_.location)) shouldBe Right(List("/srv/qod/acme/in/"))
  }

  it should "refuse what may not be authorized, with the rule's code" in {
    val f                                 = new Fixture
    def code(tenant: String, loc: String) =
      f.add(tenant, loc, Su).left.map((s, e) => (s, e.error))
    code("acme", "s3://acme-etl/") shouldBe Left((StatusCode.BadRequest, "invalid_location"))
    code("acme", "https://acme-etl/") shouldBe Left((StatusCode.BadRequest, "invalid_location"))
    code("acme", "/srv/qod/globex/") shouldBe
      Left((StatusCode.BadRequest, "outside_tenant_files_root"))
    code("nobody", "/srv/qod/nobody/") shouldBe Left((StatusCode.NotFound, "not_found"))
    code("acme", "/srv/qod/acme/").isRight shouldBe true
    code("acme", "/srv/qod/acme") shouldBe Left((StatusCode.Conflict, "location_exists"))
    new Fixture(filesRoot = None)
      .add("acme", "/srv/qod/acme/", Su)
      .left
      .map(_._2.error) shouldBe Left("tenant_files_root_unset")
  }

  "remove" should "answer the removed location" in {
    val f  = new Fixture
    val id = f.add("acme", "/srv/qod/acme/in/", AcmeAdmin).toOption.get.location.id
    val r  = f.h.remove(RemoveTenantLocationRequest("acme", id), None)(AcmeAdmin).unsafeRunSync()
    r.map(_.location.location) shouldBe Right("/srv/qod/acme/in/")
    f.sup.tenantLocations("acme") shouldBe Nil
  }

  it should "never remove another tenant's location through one's own tenant" in {
    val f  = new Fixture
    val id = f.add("globex", "/srv/qod/globex/in/", Su).toOption.get.location.id
    f.h
      .remove(RemoveTenantLocationRequest("acme", id), None)(AcmeAdmin)
      .unsafeRunSync()
      .left
      .map(_._1) shouldBe Left(StatusCode.NotFound)
    f.sup.tenantLocations("globex").map(_.id) shouldBe List(id)
  }

  "every endpoint" should "refuse an admin of another tenant and a session with no admin grant" in {
    val f  = new Fixture
    val id = f.add("globex", "/srv/qod/globex/in/", Su).toOption.get.location.id
    List(AcmeAdmin, NoGrant).foreach { scope =>
      val forbidden = Left((StatusCode.Forbidden, "tenant_forbidden"))
      f.h
        .list(TenantLocationListRequest("globex"), None)(scope)
        .unsafeRunSync()
        .left
        .map((s, e) => (s, e.error)) shouldBe forbidden
      f.add("globex", "/srv/qod/globex/two/", scope).left.map((s, e) => (s, e.error)) shouldBe
        forbidden
      f.h
        .remove(RemoveTenantLocationRequest("globex", id), None)(scope)
        .unsafeRunSync()
        .left
        .map((s, e) => (s, e.error)) shouldBe forbidden
    }
    f.sup.tenantLocations("globex").map(_.id) shouldBe List(id)
  }

  it should "admit a superuser on any tenant" in {
    val f = new Fixture
    f.add("globex", "/srv/qod/globex/in/", Su).isRight shouldBe true
    f.h.list(TenantLocationListRequest("globex"), None)(Su).unsafeRunSync().isRight shouldBe true
  }
