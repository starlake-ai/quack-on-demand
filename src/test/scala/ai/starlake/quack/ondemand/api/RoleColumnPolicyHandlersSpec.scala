package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class RoleColumnPolicyHandlersSpec extends AnyFlatSpec with Matchers:

  private def stubBackend: QuackBackend = new StubQuackBackend()

  /** Build a fresh supervisor with tenant `acme` and a custom role for the policy tests. The
    * built-in roles `createTenant` seeds refuse policies, so they cannot serve here.
    */
  private def freshSetup(): (RoleColumnPolicyHandlers, String) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val tenantId = sup.listTenants().find(_.id == "acme").get.id
    val roleId   = sup.createRole(tenantId, "analyst").unsafeRunSync().toOption.get.id
    val handler  = new RoleColumnPolicyHandlers(sup)
    (handler, roleId)

  // scopeOf stub: every caller is a superuser, so every scope check passes
  private val superuserScope: SessionScope.Resolver = _ => SessionScope.Superuser

  // ----- create -----

  "create" should "persist a mask policy and return its id" in {
    val (h, roleId) = freshSetup()
    val req         = CreateColumnPolicyRequest(
      roleId = roleId,
      catalogName = "*",
      schemaName = "tpch",
      tableName = "customer",
      columnName = "c_email",
      action = "mask",
      transformSql = Some("'***'")
    )
    val result = h.create(req, None)(superuserScope).unsafeRunSync()
    result shouldBe a[Right[?, ?]]
    val Right(dto) = result: @unchecked
    dto.id should not be empty
    dto.roleId shouldBe roleId
    dto.columnName shouldBe "c_email"
    dto.action shouldBe "mask"
    dto.transformSql shouldBe Some("'***'")
  }

  it should "400 invalid_policy when transformSql references columns outside the target column" in {
    val (h, roleId) = freshSetup()
    val req         = CreateColumnPolicyRequest(
      roleId = roleId,
      catalogName = "*",
      schemaName = "tpch",
      tableName = "customer",
      columnName = "c_email",
      action = "mask",
      // References a column other than c_email -- TransformSqlValidator should reject this
      transformSql = Some("concat(c_email, c_phone)")
    )
    val result = h.create(req, None)(superuserScope).unsafeRunSync()
    result shouldBe a[Left[?, ?]]
    val Left((code, err)) = result: @unchecked
    code shouldBe StatusCode.BadRequest
    err.error shouldBe "invalid_policy"
  }

  // ----- delete -----

  "delete" should "return 200 on a known id" in {
    val (h, roleId)  = freshSetup()
    val createResult = h
      .create(
        CreateColumnPolicyRequest(roleId, "*", "s", "t", "col", "deny"),
        None
      )(superuserScope)
      .unsafeRunSync()
    val Right(dto) = createResult: @unchecked

    val delResult =
      h.delete(DeleteColumnPolicyRequest(dto.id), None)(superuserScope).unsafeRunSync()
    delResult shouldBe Right(())
  }

  it should "return 404 on an unknown id" in {
    val (h, _) = freshSetup()
    val result =
      h.delete(DeleteColumnPolicyRequest("cp-does-not-exist"), None)(superuserScope).unsafeRunSync()
    result shouldBe a[Left[?, ?]]
    val Left((code, err)) = result: @unchecked
    code shouldBe StatusCode.NotFound
    err.error shouldBe "not_found"
  }

  // ----- list -----

  "list" should "return only the policies owned by the requested roleId" in {
    val (h, roleId) = freshSetup()

    // Create two policies on this role, one deny and one mask
    h.create(
      CreateColumnPolicyRequest(roleId, "*", "s", "t", "col_ssn", "deny"),
      None
    )(superuserScope)
      .unsafeRunSync()
    h.create(
      CreateColumnPolicyRequest(roleId, "*", "s", "t", "col_email", "mask", Some("'***'")),
      None
    )(superuserScope)
      .unsafeRunSync()

    val listResult = h.list(roleId, None)(superuserScope).unsafeRunSync()
    listResult shouldBe a[Right[?, ?]]
    val Right(resp) = listResult: @unchecked
    resp.policies.size shouldBe 2
    resp.policies.map(_.columnName).toSet shouldBe Set("col_ssn", "col_email")
    resp.policies.forall(_.roleId == roleId) shouldBe true

    // Querying a different (non-existent) roleId yields an empty list
    val emptyResult = h.list("r-other", None)(superuserScope).unsafeRunSync()
    emptyResult shouldBe a[Right[?, ?]]
    val Right(emptyResp) = emptyResult: @unchecked
    emptyResp.policies shouldBe Nil
  }
