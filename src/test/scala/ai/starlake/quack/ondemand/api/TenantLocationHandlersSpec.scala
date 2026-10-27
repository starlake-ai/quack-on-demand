package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.TenantLocationPush
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

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
      sup.createPool(PoolKey(t, s"${t}_default", "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    }
    val pushed  = new ConcurrentLinkedQueue[(String, String)]()
    val failing = TrieMap.empty[String, Unit]
    val push    = new TenantLocationPush(
      sup.liveNodesOfTenant,
      (n, sql) =>
        IO {
          pushed.add(n.nodeId -> sql)
          if failing.contains(n.nodeId) then Left("refused") else Right(())
        }
    )
    val h = new TenantLocationHandlers(sup, filesRoot, push)

    def nodeOf(tenant: String): String = sup.liveNodesOfTenant(tenant).head.nodeId

    def add(
        tenant: String,
        loc: String,
        os: Option[Map[String, String]],
        scope: SessionScope.Resolver
    ) =
      h.add(AddTenantLocationRequest(tenant, loc, os), None)(scope).unsafeRunSync()

  private val Su: SessionScope.Resolver        = _ => SessionScope.Superuser
  private val AcmeAdmin: SessionScope.Resolver = _ => SessionScope(false, Set("acme"))
  private val NoGrant: SessionScope.Resolver   = _ => SessionScope.NoAccess
  private val Secret                           = "sekr3t-value"
  private val Keys                             =
    Some(Map("s3_access_key_id" -> "AKIAEXAMPLE", "s3_secret_access_key" -> Secret))

  "add" should "store a bucket for a tenant admin, push its secret to the tenant's nodes only, and never answer the secret" in {
    val f   = new Fixture
    val res = f.add("acme", "s3://acme-etl", Keys, AcmeAdmin)
    val r   = res.toOption.get
    r.location.location shouldBe "s3://acme-etl/"
    r.location.kind shouldBe "s3"
    r.location.objectStore shouldBe Map("s3_access_key_id" -> "AKIAEXAMPLE")
    r.location.secretSet shouldBe true
    r.nodesUpdated shouldBe 1
    r.nodesFailed shouldBe Nil
    val sent = f.pushed.asScala.toList
    sent.map(_._1).toSet shouldBe Set(f.nodeOf("acme"))
    sent.map(_._2) shouldBe List(
      "LOAD httpfs;",
      s"CREATE OR REPLACE SECRET qod_tl_${r.location.id.take(8)} (TYPE s3, KEY_ID 'AKIAEXAMPLE', " +
        s"SECRET '$Secret', REGION 'us-east-1', URL_STYLE 'path', USE_SSL true, SCOPE 's3://acme-etl/');"
    )
    r.asJson.noSpaces should not include Secret
    val listed = f.h.list(TenantLocationListRequest("acme"), None)(AcmeAdmin).unsafeRunSync()
    listed.map(_.locations.map(_.location)) shouldBe Right(List("s3://acme-etl/"))
    listed.toOption.get.asJson.noSpaces should not include Secret
  }

  it should "store a local folder under the tenant's root and push nothing" in {
    val f = new Fixture
    val r = f.add("acme", "/srv/qod/acme/in", None, AcmeAdmin).toOption.get
    r.location.location shouldBe "/srv/qod/acme/in/"
    r.location.secretSet shouldBe false
    r.nodesUpdated shouldBe 0
    f.pushed.isEmpty shouldBe true
  }

  it should "refuse what may not be authorized, with the rule's code" in {
    val f                                                                  = new Fixture
    def code(tenant: String, loc: String, os: Option[Map[String, String]]) =
      f.add(tenant, loc, os, Su).left.map((s, e) => (s, e.error))
    code("acme", "s3://acme-etl/in/", Keys) shouldBe Left(
      (StatusCode.BadRequest, "invalid_location")
    )
    code("acme", "https://acme-etl/", Keys) shouldBe Left(
      (StatusCode.BadRequest, "invalid_location")
    )
    code("acme", "s3://acme-etl/", None) shouldBe
      Left((StatusCode.BadRequest, "object_store_required"))
    code("acme", "/srv/qod/globex/", None) shouldBe
      Left((StatusCode.BadRequest, "outside_tenant_files_root"))
    code("acme", "/srv/qod/acme/", Keys) shouldBe Left(
      (StatusCode.BadRequest, "invalid_object_store")
    )
    code("nobody", "s3://x-etl/", Keys) shouldBe Left((StatusCode.NotFound, "not_found"))
    code("acme", "s3://acme-etl/", Keys).isRight shouldBe true
    code("acme", "s3://acme-etl", Keys) shouldBe Left((StatusCode.Conflict, "location_exists"))
    new Fixture(filesRoot = None)
      .add("acme", "/srv/qod/acme/", None, Su)
      .left
      .map(_._2.error) shouldBe Left("tenant_files_root_unset")
    f.pushed.asScala.map(_._1).toSet shouldBe Set(f.nodeOf("acme"))
  }

  it should "report a node that did not take the change, and still store the location" in {
    val f = new Fixture
    f.failing.put(f.nodeOf("acme"), ())
    val r = f.add("acme", "s3://acme-etl/", Keys, Su).toOption.get
    r.nodesUpdated shouldBe 0
    r.nodesFailed shouldBe List(f.nodeOf("acme"))
    f.sup.tenantLocations("acme").map(_.location) shouldBe List("s3://acme-etl/")
  }

  "remove" should "drop the secret on the tenant's nodes and answer the removed location" in {
    val f  = new Fixture
    val id = f.add("acme", "s3://acme-etl/", Keys, AcmeAdmin).toOption.get.location.id
    f.pushed.clear()
    val r = f.h.remove(RemoveTenantLocationRequest("acme", id), None)(AcmeAdmin).unsafeRunSync()
    r.map(_.location.location) shouldBe Right("s3://acme-etl/")
    f.pushed.asScala.toList shouldBe List(
      f.nodeOf("acme") -> s"DROP SECRET IF EXISTS qod_tl_${id.take(8)};"
    )
    f.sup.tenantLocations("acme") shouldBe Nil
  }

  it should "never remove another tenant's location through one's own tenant" in {
    val f  = new Fixture
    val id = f.add("globex", "s3://globex-etl/", Keys, Su).toOption.get.location.id
    f.pushed.clear()
    f.h
      .remove(RemoveTenantLocationRequest("acme", id), None)(AcmeAdmin)
      .unsafeRunSync()
      .left
      .map(_._1) shouldBe Left(StatusCode.NotFound)
    f.sup.tenantLocations("globex").map(_.id) shouldBe List(id)
    f.pushed.isEmpty shouldBe true
  }

  "every endpoint" should "refuse an admin of another tenant and a session with no admin grant" in {
    val f  = new Fixture
    val id = f.add("globex", "s3://globex-etl/", Keys, Su).toOption.get.location.id
    f.pushed.clear()
    List(AcmeAdmin, NoGrant).foreach { scope =>
      val forbidden = Left((StatusCode.Forbidden, "tenant_forbidden"))
      f.h
        .list(TenantLocationListRequest("globex"), None)(scope)
        .unsafeRunSync()
        .left
        .map((s, e) => (s, e.error)) shouldBe forbidden
      f.add("globex", "s3://globex-two/", Keys, scope).left.map((s, e) => (s, e.error)) shouldBe
        forbidden
      f.h
        .remove(RemoveTenantLocationRequest("globex", id), None)(scope)
        .unsafeRunSync()
        .left
        .map((s, e) => (s, e.error)) shouldBe forbidden
    }
    f.sup.tenantLocations("globex").map(_.id) shouldBe List(id)
    f.pushed.isEmpty shouldBe true
  }

  it should "admit a superuser on any tenant" in {
    val f = new Fixture
    f.add("globex", "s3://globex-etl/", Keys, Su).isRight shouldBe true
    f.h.list(TenantLocationListRequest("globex"), None)(Su).unsafeRunSync().isRight shouldBe true
  }

  "the add request" should "never print its secret" in {
    AddTenantLocationRequest("acme", "s3://acme-etl/", Keys).toString should not include Secret
  }
