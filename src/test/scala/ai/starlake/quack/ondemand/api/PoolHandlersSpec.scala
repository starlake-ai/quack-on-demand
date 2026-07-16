package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class PoolHandlersSpec extends AnyFlatSpec with Matchers:

  private def stubBackend: QuackBackend = new StubQuackBackend()

  /** Supervisor with tenant `acme` + tenant-db `acme_default` already in place, so handler tests
    * can call `createPool` directly.
    */
  private def freshHandlers =
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    new PoolHandlers(sup, tracker)

  /** Variant without any tenant/tenant-db -- for the missing-tenant test. */
  private def handlersWithoutTenant =
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    new PoolHandlers(sup, tracker)

  private def req(
      pool: String = "sales",
      size: Int = 2,
      dist: RoleDistribution = RoleDistribution(0, 1, 1),
      maxConcurrentPerNode: Int = 0
  ): CreatePoolRequest =
    CreatePoolRequest(
      tenant = "acme",
      tenantDb = "acme_default",
      pool = pool,
      size = size,
      roleDistribution = dist,
      maxConcurrentPerNode = maxConcurrentPerNode
    )

  "createPool" should "create a pool and return node info with maxConcurrent" in:
    val h   = freshHandlers
    val out = h.createPool(req(maxConcurrentPerNode = 4), None)((_: String) => None).unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    val Right(resp) = out: @unchecked
    resp.nodes.size shouldBe 2
    resp.nodes.forall(_.maxConcurrent == 4) shouldBe true

  it should "reject mismatched role distribution" in:
    val h   = freshHandlers
    val out = h
      .createPool(req(size = 3, dist = RoleDistribution(0, 1, 1)), None)((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)

  it should "return Conflict for duplicate pool" in:
    val h = freshHandlers
    val r = req(size = 1, dist = RoleDistribution(0, 0, 1))
    h.createPool(r, None)((_: String) => None).unsafeRunSync() shouldBe a[Right[?, ?]]
    val out = h.createPool(r, None)((_: String) => None).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.Conflict)

  it should "return 404 when the tenant is not registered" in:
    val h   = handlersWithoutTenant
    val out = h
      .createPool(
        CreatePoolRequest(
          tenant = "unknown",
          tenantDb = "unknown_default",
          pool = "sales",
          size = 1,
          roleDistribution = RoleDistribution(0, 0, 1)
        ),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)
    out.left.toOption.map(_._2.error) shouldBe Some("tenant_not_found")

  it should "return 404 when the tenant-db is not registered" in:
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    // No createTenantDb -> the handler should refuse.
    val h   = new PoolHandlers(sup, tracker)
    val out = h.createPool(req(), None)((_: String) => None).unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)
    out.left.toOption.map(_._2.error) shouldBe Some("tenant_db_not_found")

  "scalePool" should "fail when pool doesn't exist" in:
    val h   = freshHandlers
    val out = h
      .scalePool(
        ScalePoolRequest(
          "acme",
          "acme_default",
          "missing",
          2,
          RoleDistribution(0, 1, 1),
          force = false
        ),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  "stopPool" should "stop a known pool but keep it (scaled to 0)" in:
    val h = freshHandlers
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    h.stopPool(StopPoolRequest("acme", "acme_default", "sales", force = true), None)((_: String) =>
      None
    ).unsafeRunSync() shouldBe Right(())
    // Pool still exists, just with no nodes.
    h.poolStatus("acme", "acme_default", "sales")
      .unsafeRunSync()
      .toOption
      .map(_.nodes) shouldBe Some(Nil)

  "deletePool" should "remove a known pool" in:
    val h = freshHandlers
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    h.deletePool(DeletePoolRequest("acme", "acme_default", "sales", force = true), None)(
      (_: String) => None
    ).unsafeRunSync() shouldBe Right(())
    h.poolStatus("acme", "acme_default", "sales")
      .unsafeRunSync()
      .left
      .toOption
      .map(_._1) shouldBe Some(StatusCode.NotFound)

  "listPools" should "return all pools" in:
    val h = freshHandlers
    h.createPool(req(pool = "sales", size = 1, dist = RoleDistribution(0, 0, 1)), None)(
      (_: String) => None
    ).unsafeRunSync()
    h.createPool(req(pool = "ops", size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) =>
      None
    ).unsafeRunSync()
    val out = h.listPools(None)((_: String) => None).unsafeRunSync()
    out.toOption.get.pools.size shouldBe 2

  "poolStatus" should "return 404 for unknown pool" in:
    val h   = freshHandlers
    val out = h.poolStatus("acme", "acme_default", "missing").unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  "setResources" should "update cpu/memory and return the pool" in:
    val h = freshHandlers
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    val out = h
      .setResources(
        SetPoolResourcesRequest("acme", "acme_default", "sales", "500m", "2Gi"),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    val Right(resp) = out: @unchecked
    resp.cpu shouldBe "500m"
    resp.memory shouldBe "2Gi"

  it should "reject invalid cpu quantity with 400" in:
    val h = freshHandlers
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    val out = h
      .setResources(
        SetPoolResourcesRequest("acme", "acme_default", "sales", "2 gigs", "2Gi"),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)

  "setPodTemplate" should "be rejected when podTemplateEnabled is false" in:
    val h = freshHandlers // default podTemplateEnabled=false
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    val y   = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    val out = h
      .setPodTemplate(
        SetPoolTemplateRequest("acme", "acme_default", "sales", y),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._2.error) shouldBe Some("feature_disabled")

  it should "succeed for superuser when podTemplateEnabled is true" in:
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val h = new PoolHandlers(sup, tracker, podTemplateEnabled = true)
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    val y   = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    val out = h
      .setPodTemplate(
        SetPoolTemplateRequest("acme", "acme_default", "sales", y),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]

  // E1: auth-before-gate ordering tests

  it should "return 403 for a tenant-admin session even when podTemplateEnabled is false" in:
    // Auth check (SuperuserCheck) must fire before the feature-gate check so
    // a non-superuser can never infer whether the gate is on or off.
    val h = freshHandlers // podTemplateEnabled = false
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    val y   = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    val out = h
      .setPodTemplate(
        SetPoolTemplateRequest("acme", "acme_default", "sales", y),
        Some("tok")
      )(_ => Some(SessionScope(superuser = false, manageableTenants = Set("acme"))))
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.Forbidden)

  it should "return 400 feature_disabled for a superuser when podTemplateEnabled is false" in:
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val h = new PoolHandlers(sup, tracker, podTemplateEnabled = false)
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    val y   = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    val out = h
      .setPodTemplate(
        SetPoolTemplateRequest("acme", "acme_default", "sales", y),
        Some("tok")
      )(_ => Some(SessionScope.Superuser))
      .unsafeRunSync()
    out.left.toOption.map(_._2.error) shouldBe Some("feature_disabled")

  it should "allow a superuser to clear the template with an empty string when podTemplateEnabled is true" in:
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val h = new PoolHandlers(sup, tracker, podTemplateEnabled = true)
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    // Set a template first, then clear it with an empty string.
    val y = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    h.setPodTemplate(
      SetPoolTemplateRequest("acme", "acme_default", "sales", y),
      Some("tok")
    )(_ => Some(SessionScope.Superuser))
      .unsafeRunSync()
    val out = h
      .setPodTemplate(
        SetPoolTemplateRequest("acme", "acme_default", "sales", ""),
        Some("tok")
      )(_ => Some(SessionScope.Superuser))
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]

  // E2: setResources authZ and error-code tests

  "setResources" should "return 403 for a foreign-tenant admin" in:
    val h = freshHandlers
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    val out = h
      .setResources(
        SetPoolResourcesRequest("acme", "acme_default", "sales", "500m", "2Gi"),
        Some("tok")
      )(_ => Some(SessionScope(superuser = false, manageableTenants = Set("globex"))))
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.Forbidden)

  it should "return 400 with error code 'invalid' for an invalid cpu quantity" in:
    val h = freshHandlers
    h.createPool(req(size = 1, dist = RoleDistribution(0, 0, 1)), None)((_: String) => None)
      .unsafeRunSync()
    val out = h
      .setResources(
        SetPoolResourcesRequest("acme", "acme_default", "sales", "not-a-cpu", "2Gi"),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)
    out.left.toOption.map(_._2.error) shouldBe Some("invalid")

  // CRITICAL 1: createPool must guard podTemplateYaml

  "createPool with podTemplateYaml" should "return 403 for a tenant-admin session (gate on)" in:
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val h   = new PoolHandlers(sup, tracker, podTemplateEnabled = true)
    val y   = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    val out = h
      .createPool(
        req().copy(podTemplateYaml = y),
        Some("tok")
      )(_ => Some(SessionScope(superuser = false, manageableTenants = Set("acme"))))
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.Forbidden)
    out.left.toOption.map(_._2.error) shouldBe Some("superuser_required")

  it should "return 400 feature_disabled for a superuser when gate is off" in:
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val h   = new PoolHandlers(sup, tracker, podTemplateEnabled = false)
    val y   = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    val out = h
      .createPool(
        req().copy(podTemplateYaml = y),
        Some("tok")
      )(_ => Some(SessionScope.Superuser))
      .unsafeRunSync()
    out.left.toOption.map(_._2.error) shouldBe Some("feature_disabled")

  it should "succeed for a superuser when gate is on and template is valid" in:
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val h   = new PoolHandlers(sup, tracker, podTemplateEnabled = true)
    val y   = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    val out = h
      .createPool(
        req(size = 1, dist = RoleDistribution(0, 0, 1)).copy(podTemplateYaml = y),
        Some("tok")
      )(_ => Some(SessionScope.Superuser))
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]

  it should "return 403 even when gate is off (auth check precedes feature check)" in:
    // SuperuserCheck must fire before the feature-gate check so a non-superuser
    // cannot infer whether the gate is on or off via differential error codes.
    val h   = freshHandlers // podTemplateEnabled = false
    val y   = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    val out = h
      .createPool(
        req().copy(podTemplateYaml = y),
        Some("tok")
      )(_ => Some(SessionScope(superuser = false, manageableTenants = Set("acme"))))
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.Forbidden)

  it should "return 400 invalid for a junk cpu quantity on createPool" in:
    val h   = freshHandlers
    val out = h
      .createPool(
        req(size = 1, dist = RoleDistribution(0, 0, 1)).copy(cpu = "not-a-cpu"),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)
    out.left.toOption.map(_._2.error) shouldBe Some("invalid")
