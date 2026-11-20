package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** `QOD_TENANT_DELETE_ENABLED=false` (`quack-on-demand.tenantDeleteEnabled`): `tenant/delete`,
  * which REST and the MCP tool both reach, refuses every delete, a superuser's included, after the
  * tenant scope check, and answers say so through `deleteEnabled`.
  */
class TenantDeleteSwitchSpec extends AnyFlatSpec with Matchers:

  private final class Fixture(deleteEnabled: Boolean):
    val sup = new PoolSupervisor(
      new StubQuackBackend(),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore()
    )
    val telemetry = new RecordingTelemetryStore
    val handlers  = new TenantHandlers(
      sup,
      tenantDeleteEnabled = deleteEnabled,
      onAuthChanged = _ => (),
      audit = new AuditRecorder(telemetry, _ => None)
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()

    def delete(scope: SessionScope) =
      handlers.deleteTenant(TenantOpRequest("acme"), Some("tok"))(_ => scope).unsafeRunSync()

  private val acmeAdmin = SessionScope(superuser = false, manageableTenants = Set("acme"))

  "tenant/delete with the switch off" should "refuse a superuser, keeping the tenant" in {
    val f              = new Fixture(deleteEnabled = false)
    val out            = f.delete(SessionScope.Superuser)
    val (status, body) = out.left.toOption.get
    status shouldBe StatusCode.Forbidden
    body.error shouldBe "tenant_delete_disabled"
    body.message should include("QOD_TENANT_DELETE_ENABLED")
    f.sup.getTenant("acme") shouldBe defined
  }

  it should "refuse the tenant's own admin too, and audit the refusal" in {
    val f = new Fixture(deleteEnabled = false)
    f.delete(acmeAdmin).left.toOption.get._2.error shouldBe "tenant_delete_disabled"
    f.sup.getTenant("acme") shouldBe defined
    val e = f.telemetry.events.last
    e.action shouldBe "tenant.delete"
    e.outcome shouldBe "denied"
    e.detail.get("reason") shouldBe Some("tenant_delete_disabled")
  }

  it should "still answer a caller outside the tenant with tenant_forbidden" in {
    val f       = new Fixture(deleteEnabled = false)
    val outside = SessionScope(superuser = false, manageableTenants = Set("other"))
    f.delete(outside).left.toOption.get._2.error should not be "tenant_delete_disabled"
  }

  it should "say so on every tenant it lists" in {
    val f       = new Fixture(deleteEnabled = false)
    val tenants = f.handlers.listTenants(Some("tok"))(_ => SessionScope.Superuser).unsafeRunSync()
    tenants.toOption.get.tenants.map(_.deleteEnabled) shouldBe List(false)
  }

  "tenant/delete with the switch on" should "delete as before" in {
    val f = new Fixture(deleteEnabled = true)
    f.delete(SessionScope.Superuser) shouldBe Right(())
    f.sup.getTenant("acme") shouldBe None
    val tenants = f.handlers.listTenants(Some("tok"))(_ => SessionScope.Superuser).unsafeRunSync()
    tenants.toOption.get.tenants shouldBe Nil
  }
