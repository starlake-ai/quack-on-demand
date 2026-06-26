package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, RunningNode, Tenant}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant
import scala.collection.concurrent.TrieMap

class RoleColumnPolicyHandlersSpec extends AnyFlatSpec with Matchers:

  private def stubBackend: QuackBackend = new QuackBackend:
    private val n = TrieMap.empty[String, RunningNode]
    def start(s: NodeSpec) = IO {
      val r = RunningNode(s.nodeId, s.poolKey, s.role, "127.0.0.1",
                          21000 + n.size, "tok", Some(1L), None, Instant.EPOCH,
                          maxConcurrent = s.maxConcurrent)
      n.put(s.nodeId, r); r
    }
    def stop(id: String)    = IO { n.remove(id); () }
    def isAlive(id: String) = n.contains(id)
    def discoverExisting()  = IO.pure(n.values.toList)
    def cleanup()           = IO { n.clear() }

  /** Build a fresh supervisor with tenant `acme`. `createTenant` automatically
    * seeds a built-in `admin` role; use that role id for policy tests.
    */
  private def freshSetup(): (RoleColumnPolicyHandlers, String) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val tenantId = sup.listTenants().find(_.id == "acme").get.id
    // createTenant seeds a built-in 'admin' role - fetch its id
    val roleId = sup.listRoles(tenantId).find(_.name == "admin").get.id
    val handler = new RoleColumnPolicyHandlers(sup)
    (handler, roleId)

  // scopeOf stub: no active session -> all scope checks pass (like static-key / open mode)
  private val noScope: String => Option[ai.starlake.quack.ondemand.auth.SessionScope] =
    (_: String) => None

  // ----- create -----

  "create" should "persist a mask policy and return its id" in {
    val (h, roleId) = freshSetup()
    val req = CreateColumnPolicyRequest(
      roleId      = roleId,
      catalogName = "*",
      schemaName  = "tpch",
      tableName   = "customer",
      columnName  = "c_email",
      action      = "mask",
      transformSql = Some("'***'")
    )
    val result = h.create(req, None)(noScope).unsafeRunSync()
    result shouldBe a[Right[?, ?]]
    val Right(dto) = result: @unchecked
    dto.id          should not be empty
    dto.roleId      shouldBe roleId
    dto.columnName  shouldBe "c_email"
    dto.action      shouldBe "mask"
    dto.transformSql shouldBe Some("'***'")
  }

  it should "400 invalid_policy when transformSql references columns outside the target column" in {
    val (h, roleId) = freshSetup()
    val req = CreateColumnPolicyRequest(
      roleId      = roleId,
      catalogName = "*",
      schemaName  = "tpch",
      tableName   = "customer",
      columnName  = "c_email",
      action      = "mask",
      // References a column other than c_email -- TransformSqlValidator should reject this
      transformSql = Some("concat(c_email, c_phone)")
    )
    val result = h.create(req, None)(noScope).unsafeRunSync()
    result shouldBe a[Left[?, ?]]
    val Left((code, err)) = result: @unchecked
    code     shouldBe StatusCode.BadRequest
    err.error shouldBe "invalid_policy"
  }

  // ----- delete -----

  "delete" should "return 200 on a known id" in {
    val (h, roleId) = freshSetup()
    val createResult = h.create(
      CreateColumnPolicyRequest(roleId, "*", "s", "t", "col", "deny"),
      None
    )(noScope).unsafeRunSync()
    val Right(dto) = createResult: @unchecked

    val delResult = h.delete(DeleteColumnPolicyRequest(dto.id), None)(noScope).unsafeRunSync()
    delResult shouldBe Right(())
  }

  it should "return 404 on an unknown id" in {
    val (h, _) = freshSetup()
    val result = h.delete(DeleteColumnPolicyRequest("cp-does-not-exist"), None)(noScope).unsafeRunSync()
    result shouldBe a[Left[?, ?]]
    val Left((code, err)) = result: @unchecked
    code     shouldBe StatusCode.NotFound
    err.error shouldBe "not_found"
  }

  // ----- list -----

  "list" should "return only the policies owned by the requested roleId" in {
    val (h, roleId) = freshSetup()

    // Create two policies on this role, one deny and one mask
    h.create(
      CreateColumnPolicyRequest(roleId, "*", "s", "t", "col_ssn",   "deny"),
      None
    )(noScope).unsafeRunSync()
    h.create(
      CreateColumnPolicyRequest(roleId, "*", "s", "t", "col_email", "mask", Some("'***'")),
      None
    )(noScope).unsafeRunSync()

    val listResult = h.list(roleId, None)(noScope).unsafeRunSync()
    listResult shouldBe a[Right[?, ?]]
    val Right(resp) = listResult: @unchecked
    resp.policies.size shouldBe 2
    resp.policies.map(_.columnName).toSet shouldBe Set("col_ssn", "col_email")
    resp.policies.forall(_.roleId == roleId) shouldBe true

    // Querying a different (non-existent) roleId yields an empty list
    val emptyResult = h.list("r-other", None)(noScope).unsafeRunSync()
    emptyResult shouldBe a[Right[?, ?]]
    val Right(emptyResp) = emptyResult: @unchecked
    emptyResp.policies shouldBe Nil
  }