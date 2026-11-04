package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.api.{TenantHandlers, TenantRequest}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.manifest.{
  ConfigManifest,
  ExportedFrom,
  ManifestImporter,
  ManifestTenant
}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditActor
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant

/** A new tenant's name holds letters and digits only. The rule sits in the supervisor's create, the
  * one function every creating path but a manifest import reaches, so a direct call gets it too;
  * the REST handler only maps the refusal.
  */
class TenantNameRuleSpec extends AnyFlatSpec with Matchers:

  private final class Fixture:
    val store    = new InMemoryControlPlaneStore()
    val sup      = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    val handlers = new TenantHandlers(sup, onAuthChanged = _ => ())

  "PoolSupervisor.createTenant" should "refuse an underscore, writing nothing" in {
    val f   = new Fixture
    val out = f.sup.createTenant(Tenant("acme_corp")).unsafeRunSync()
    out.left.toOption.get shouldBe a[SupervisorError.InvalidName]
    out.left.toOption.get.message should include("letters and digits only")
    f.sup.getTenant("acme_corp") shouldBe None
    f.store.snapshot().tenants shouldBe empty
  }

  it should "still create a name of letters and digits, lowercased" in {
    val f = new Fixture
    f.sup.createTenant(Tenant("Acme2")).unsafeRunSync().map(_.id) shouldBe Right("acme2")
    f.store.snapshot().tenants.map(_.id) shouldBe List("acme2")
  }

  "tenant/create" should "answer 400 invalid_id to a superuser, on the REST path" in {
    val f   = new Fixture
    val out = f.handlers
      .createTenant(TenantRequest(id = "acme_corp", displayName = "Acme"), None)(_ =>
        SessionScope.Superuser
      )
      .unsafeRunSync()
    val (status, err) = out.left.toOption.get
    status shouldBe StatusCode.BadRequest
    err.error shouldBe "invalid_id"
    err.message should include("letters and digits only")
    f.store.snapshot().tenants shouldBe empty
  }

  it should "answer the same as the manager, the path of qod serve's boot provisioning" in {
    val f   = new Fixture
    val out = f.handlers
      .createTenantAs(
        TenantRequest(id = "acme_corp", displayName = "Acme"),
        SessionScope.Superuser,
        AuditActor.System
      )
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_id"
    f.store.snapshot().tenants shouldBe empty
  }

  it should "answer 400 invalid_id for every other malformed name too" in {
    val f = new Fixture
    List("1acme", "acme-corp", "acme corp", "a" * 64).foreach { id =>
      val out = f.handlers
        .createTenant(TenantRequest(id = id, displayName = "x"), None)(_ => SessionScope.Superuser)
        .unsafeRunSync()
      withClue(id)(out.left.toOption.get._2.error shouldBe "invalid_id")
    }
    f.store.snapshot().tenants shouldBe empty
  }

  it should "still answer 409 exists to a name already taken" in {
    val f = new Fixture
    f.sup.createTenant(Tenant("acme")).unsafeRunSync()
    val out = f.handlers
      .createTenant(TenantRequest(id = "acme", displayName = "x"), None)(_ =>
        SessionScope.Superuser
      )
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "exists"
  }

  "A manifest import" should "keep a tenant holding an underscore: a backup must restore" in {
    val store    = new InMemoryControlPlaneStore()
    val manifest = ConfigManifest(
      apiVersion = ConfigManifest.ApiVersion,
      kind = ConfigManifest.Kind,
      exportedAt = Instant.EPOCH,
      exportedFrom = ExportedFrom("0.2.0", "test"),
      tenants = List(ManifestTenant(name = "acme_corp", displayName = "Acme"))
    )
    ManifestImporter.apply(manifest, store, requireEncryption = false) shouldBe Right(())
    store.snapshot().tenants.map(_.id) shouldBe List("acme_corp")
  }

  "A tenant imported with an underscore" should "keep creating databases under its name" in {
    val store    = new InMemoryControlPlaneStore()
    val manifest = ConfigManifest(
      apiVersion = ConfigManifest.ApiVersion,
      kind = ConfigManifest.Kind,
      exportedAt = Instant.EPOCH,
      exportedFrom = ExportedFrom("0.2.0", "test"),
      tenants = List(ManifestTenant(name = "acme_corp", displayName = "Acme"))
    )
    ManifestImporter.apply(manifest, store, requireEncryption = false) shouldBe Right(())
    val sup = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    sup.restore()
    val out = sup
      .createTenantDb("acme_corp", "mem", TenantDbKind.InMemory, Map.empty, "", writerTrusted = true)
      .unsafeRunSync()
    out.map(_.name) shouldBe Right("acme_corp_mem")
  }
