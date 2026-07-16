package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{DbAdmin, InMemoryControlPlaneStore}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class TagHandlersSpec extends AnyFlatSpec with Matchers:

  // build sup + store + handlers; snapshot 42 exists, everything else does not.
  private var tenantDbName: String                              = ""
  private def setup(): (TagHandlers, InMemoryControlPlaneStore) =
    val store = new InMemoryControlPlaneStore()
    val admin = new DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
    val backend = new StubQuackBackend()
    val sup = new PoolSupervisor(
      backend,
      new NodeLoadTracker,
      store,
      dbAdmin = admin
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val td = sup
      .createTenantDb(
        "acme",
        "db1",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-tag-test"
      )
      .unsafeRunSync()
      .toOption
      .get
    tenantDbName = td.name
    val handlers = new TagHandlers(
      sup,
      store,
      snapshotExists = (_, _, id) => id == 42L,
      snapshotsExist = (_, _, ids) => ids.filter(_ == 42L)
    )
    (handlers, store)

  private def create(h: TagHandlers, name: String, snap: Long = 42L, prot: Boolean = false) =
    h.create(TagCreateRequest("acme", tenantDbName, name, snap, prot), None)(_ => None)
      .unsafeRunSync()

  "create" should "persist a valid tag and return it with exists=true" in {
    val (h, _) = setup()
    val out    = create(h, "pre-migration")
    out.toOption.get.name shouldBe "pre-migration"
    out.toOption.get.exists shouldBe true
  }

  it should "reject an all-digit name with 400" in {
    val (h, _) = setup()
    create(h, "12345").left.toOption.get._1 shouldBe StatusCode.BadRequest
  }

  it should "reject an unknown snapshot with 404" in {
    val (h, _) = setup()
    create(h, "ghost", snap = 999L).left.toOption.get._1 shouldBe StatusCode.NotFound
  }

  it should "reject a duplicate with 409" in {
    val (h, _) = setup()
    create(h, "dup").isRight shouldBe true
    create(h, "dup").left.toOption.get._1 shouldBe StatusCode.Conflict
  }

  "delete" should "404 on a missing tag and succeed on an existing one" in {
    val (h, _) = setup()
    h.delete(TagDeleteRequest("acme", tenantDbName, "nope"), None)(_ => None)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.NotFound
    create(h, "gone").isRight shouldBe true
    h.delete(TagDeleteRequest("acme", tenantDbName, "gone"), None)(_ => None)
      .unsafeRunSync()
      .isRight shouldBe true
  }

  "protect" should "toggle and list reflects it" in {
    val (h, _) = setup()
    create(h, "hold").isRight shouldBe true
    h.protect(TagProtectRequest("acme", tenantDbName, "hold", true), None)(_ => None)
      .unsafeRunSync()
      .toOption
      .get
      .isProtected shouldBe true
    h.list("acme", tenantDbName, None)(_ => None)
      .unsafeRunSync()
      .toOption
      .get
      .head
      .isProtected shouldBe true
  }

  "resolveAsOf" should "resolve a tag, 404 unknown, 400 on both params" in {
    val (h, _) = setup()
    create(h, "v1").isRight shouldBe true
    h.resolveAsOf("acme", tenantDbName, None, Some("v1")) shouldBe Right(Some(42L))
    h.resolveAsOf("acme", tenantDbName, None, Some("nope")).left.toOption.get._1 shouldBe
      StatusCode.NotFound
    h.resolveAsOf("acme", tenantDbName, Some(42L), Some("v1")).left.toOption.get._1 shouldBe
      StatusCode.BadRequest
    h.resolveAsOf("acme", tenantDbName, Some(42L), None) shouldBe Right(Some(42L))
    h.resolveAsOf("acme", tenantDbName, None, None) shouldBe Right(None)
  }

  it should "resolve a tag when the tenant-db name is passed in a mixed-case form" in {
    // createTenantDb("acme", "db1") composes and lowercases the name to "acme_db1".
    // Before the fix, resolveAsOf passed the raw caller string to the store; after the fix
    // it uses td.name (the canonical lowercase form) returned by findTenantDb.
    // Passing "ACME_DB1" exercises that normalization path.
    val (h, _) = setup()
    create(h, "v2").isRight shouldBe true
    h.resolveAsOf("acme", "ACME_DB1", None, Some("v2")) shouldBe Right(Some(42L))
  }

  it should "return 404 for an unknown tenant-db in resolveAsOf" in {
    val (h, _) = setup()
    h.resolveAsOf("acme", "no-such-db", None, Some("v1")).left.toOption.get._1 shouldBe
      StatusCode.NotFound
  }
