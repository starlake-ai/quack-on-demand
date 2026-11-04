package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
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

/** The naming rule at REST's three create endpoints: what each answers for a name the rule refuses,
  * and that nothing is written. The rule itself lives in `Names`; the supervisor applies it, so a
  * caller past REST (the hosted signup) meets it too.
  */
class NameRuleWritersSpec extends AnyFlatSpec with Matchers:

  private final class Fixture:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    private val audit = new AuditRecorder(new RecordingTelemetryStore, _ => None)
    val tenants       = new TenantHandlers(sup, onAuthChanged = _ => (), audit = audit)
    val tenantDbs     = new TenantDbHandlers(sup, audit = audit, requireEncryption = false)
    val pools         = new PoolHandlers(sup, new NodeLoadTracker, audit = audit)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "shop", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()

    def tenant(id: String) =
      tenants.createTenant(TenantRequest(id = id, displayName = id), None)(_ =>
        SessionScope.Superuser
      )

    def database(name: String) =
      tenantDbs.createTenantDb(
        TenantDbRequest(tenant = "acme", name = name, kind = "memory"),
        None
      )(_ => SessionScope.Superuser)

    def pool(name: String, tenantDb: String = "acme_shop") =
      pools.createPool(
        CreatePoolRequest(
          tenant = "acme",
          tenantDb = tenantDb,
          pool = name,
          size = 1,
          roleDistribution = RoleDistribution(0, 0, 1)
        ),
        None
      )(_ => SessionScope.Superuser)

  "tenant/create" should "answer 400 invalid_id for a name the rule refuses, writing nothing" in {
    val f = new Fixture
    List("a__b", "a_").foreach { id =>
      withClue(id) {
        val (status, error) = f.tenant(id).unsafeRunSync().left.toOption.get
        status shouldBe StatusCode.BadRequest
        error.error shouldBe "invalid_id"
        error.message should include(s"'$id'")
      }
    }
    // A leading `_`, which this endpoint already refused: every id starts with a letter here.
    val (status, error) = f.tenant("_a").unsafeRunSync().left.toOption.get
    status shouldBe StatusCode.BadRequest
    error.error shouldBe "invalid_id"
    f.sup.listTenants().map(_.id) shouldBe List("acme")
    f.tenant("a_b").unsafeRunSync().isRight shouldBe true
  }

  "database/create" should "answer 400 invalid_name for a name the rule refuses, writing nothing" in {
    val f = new Fixture
    List("_x", "x_", "a__b", "acme__b", "acme_a__b").foreach { name =>
      withClue(name) {
        val (status, error) = f.database(name).unsafeRunSync().left.toOption.get
        status shouldBe StatusCode.BadRequest
        error.error shouldBe "invalid_name"
        error.message should include("'--'")
      }
    }
    f.store.snapshot().tenantDbs.map(_.name) shouldBe List("acme_shop")
    f.database("a_b").unsafeRunSync().map(_.name) shouldBe Right("acme_a_b")
  }

  "pool/create" should "answer 400 invalid_name for a name the rule refuses, writing nothing" in {
    val f = new Fixture
    List(
      "__br_x",
      "__merge",
      "__maint",
      "_x",
      "x_",
      "a__b",
      "a--b",
      "my pool",
      "1a",
      "a/b",
      "p" * 64
    )
      .foreach { name =>
        withClue(name) {
          val (status, error) = f.pool(name).unsafeRunSync().left.toOption.get
          status shouldBe StatusCode.BadRequest
          error.error shouldBe "invalid_name"
          error.message should include(s"'$name'")
        }
      }
    f.store.snapshot().pools shouldBe Nil
  }

  it should "take a hyphen, and answer 409 for a pool reading like another of its database" in {
    val f = new Fixture
    f.pool("eu-west").unsafeRunSync().isRight shouldBe true
    val (status, error) = f.pool("eu_west").unsafeRunSync().left.toOption.get
    status shouldBe StatusCode.Conflict
    error.error shouldBe "pool_reads_alike"
    error.message should include("'eu-west'")
    f.sup.get(PoolKey("acme", "acme_shop", "eu_west")) shouldBe None
    // The same pool again is what it always was.
    val (sameStatus, same) = f.pool("eu-west").unsafeRunSync().left.toOption.get
    sameStatus shouldBe StatusCode.Conflict
    same.error shouldBe "exists"
    // In another database of the tenant, the same reading is another pool.
    f.database("other").unsafeRunSync().isRight shouldBe true
    f.pool("eu_west", tenantDb = "acme_other").unsafeRunSync().isRight shouldBe true
  }
