// src/test/scala/ai/starlake/quack/security/ManifestRbacModeSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  PoolPermission,
  RbacGroup,
  RbacRole
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `mode=rbac` on the manifest routes: one tenant's custom roles and groups become exactly the
  * file, and nothing else moves.
  */
class ManifestRbacModeSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val Acme = SecurityFixtures.TenantId

  private val Bundle =
    s"""roles:
       |  - tenant: $Acme
       |    name: analyst
       |    permissions:
       |      - {catalog: "*", schema: main, table: orders, verb: RO}
       |groups:
       |  - tenant: $Acme
       |    name: bi
       |    roles: [analyst]
       |""".stripMargin

  private def withServer(
      test: (ManagerServerHarness.Harness, SecurityFixtures.Fixture, String) => Unit
  ) =
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addGlobexTenant(fix.store)
    val h = ManagerServerHarness.boot(fix.store)
    try test(h, fix, h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword))
    finally h.shutdown()

  private def importRbac(
      h: ManagerServerHarness.Harness,
      token: String,
      body: String,
      query: String = s"mode=rbac&tenant=$Acme"
  ) =
    post(h.httpClient, s"${h.baseUrl}/api/manifest/import?$query", body, Some(token), "text/plain")

  private def exportRbac(h: ManagerServerHarness.Harness, token: String) =
    get(h.httpClient, s"${h.baseUrl}/api/manifest/export?mode=rbac&tenant=$Acme", Some(token))

  private def customRoles(s: InMemoryControlPlaneStore, tenant: String = Acme) =
    s.listRoles(tenant).filterNot(_.builtin).map(_.name).toSet

  private def customGroups(s: InMemoryControlPlaneStore, tenant: String = Acme) =
    s.listGroups(tenant).filterNot(_.builtin).map(_.name).toSet

  "POST /api/manifest/import?mode=rbac" should "make the tenant's custom roles match the file" in
    withServer { (h, fix, token) =>
      fix.store.upsertRole(RbacRole("r-old", Acme, "old"))
      val resp = importRbac(h, token, Bundle)
      withClue(resp.body())(resp.statusCode() shouldBe 200)
      customRoles(fix.store) shouldBe Set("analyst")
      customGroups(fix.store) shouldBe Set("bi")
    }

  it should "remove the bindings of a removed role" in withServer { (h, fix, token) =>
    fix.store.upsertRole(RbacRole("r-old", Acme, "old"))
    fix.store.addUserRole(fix.bobUserId, "r-old")
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    fix.store.listDirectRolesForUser(fix.bobUserId) should not contain "r-old"
  }

  it should "keep the bindings of a role that stays" in withServer { (h, fix, token) =>
    fix.store.upsertRole(RbacRole("r-keep", Acme, "analyst"))
    fix.store.addUserRole(fix.bobUserId, "r-keep")
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    fix.store.listDirectRolesForUser(fix.bobUserId) should contain("r-keep")
  }

  it should "remove a custom group absent from the file" in withServer { (h, fix, token) =>
    fix.store.upsertGroup(RbacGroup("g-old", Acme, "old_group"))
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    customGroups(fix.store) shouldBe Set("bi")
  }

  it should "empty the tenant's custom roles for an empty file" in withServer { (h, fix, token) =>
    importRbac(h, token, "roles: []\ngroups: []\n").statusCode() shouldBe 200
    customRoles(fix.store) shouldBe empty
  }

  it should "leave built-in roles and groups in place" in withServer { (h, fix, token) =>
    fix.store.ensureBuiltins(Acme)
    val roles  = fix.store.listRoles(Acme).filter(_.builtin).map(_.name).toSet
    val groups = fix.store.listGroups(Acme).filter(_.builtin).map(_.name).toSet
    roles should not be empty
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    fix.store.listRoles(Acme).filter(_.builtin).map(_.name).toSet shouldBe roles
    fix.store.listGroups(Acme).filter(_.builtin).map(_.name).toSet shouldBe groups
  }

  it should "leave a group's existing pool grants alone" in withServer { (h, fix, token) =>
    fix.store.upsertGroup(RbacGroup("g-bi", Acme, "bi"))
    fix.store.insertPoolPermission(
      PoolPermission(
        "pp-bi",
        Acme,
        poolId = Some(SecurityFixtures.PoolId),
        groupId = Some("g-bi")
      )
    )
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    fix.store.listPoolPermissionsForGroup("g-bi").map(_.id) shouldBe List("pp-bi")
  }

  it should "leave a SCIM provisioned group alone" in withServer { (h, fix, token) =>
    fix.store.upsertGroup(RbacGroup("g-scim", Acme, "from_idp"))
    fix.store.setGroupExternalId("g-scim", Some("ext-1"))
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    customGroups(fix.store) should contain("from_idp")
  }

  it should "leave another tenant untouched" in withServer { (h, fix, token) =>
    val other = SecurityFixtures.GlobexTenantId
    fix.store.upsertRole(RbacRole("r-globex", other, "globex_role"))
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    customRoles(fix.store, other) should contain("globex_role")
  }

  it should "leave users and tenants untouched" in withServer { (h, fix, token) =>
    val users   = fix.store.listUsers(Some(Acme)).map(_.username).toSet
    val tenants = fix.store.listTenants().map(_.id).toSet
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    fix.store.listUsers(Some(Acme)).map(_.username).toSet shouldBe users
    fix.store.listTenants().map(_.id).toSet shouldBe tenants
  }

  private def refused(
      name: String,
      expected: String,
      body: String = Bundle,
      query: String = s"mode=rbac&tenant=$Acme"
  ): Unit =
    it should s"refuse $name and change nothing" in withServer { (h, fix, token) =>
      fix.store.upsertRole(RbacRole("r-old", Acme, "old"))
      val before = customRoles(fix.store)
      val resp   = importRbac(h, token, body, query)
      withClue(resp.body()) {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) shouldBe Some(expected)
      }
      customRoles(fix.store) shouldBe before
    }

  refused("a missing tenant", "rbac_tenant_required", query = "mode=rbac")
  refused("an unknown tenant", "unknown_tenant", query = "mode=rbac&tenant=nope")
  refused("an unknown mode", "invalid_mode", query = s"mode=everything&tenant=$Acme")
  refused("a tenants section", "rbac_section_refused", body = "tenants:\n  - name: x\n")
  refused(
    "a users section",
    "rbac_section_refused",
    body = s"users:\n  - {tenant: $Acme, username: eve}\n"
  )
  refused(
    "a role of another tenant",
    "rbac_tenant_mismatch",
    body = "roles:\n  - {tenant: t-globex01, name: x}\n"
  )
  refused(
    "a group of another tenant",
    "rbac_tenant_mismatch",
    body = "groups:\n  - {tenant: t-globex01, name: x}\n"
  )
  refused(
    "pool grants on a group",
    "rbac_pool_grants_refused",
    body = s"groups:\n  - {tenant: $Acme, name: x, poolGrants: [{pool: sales}]}\n"
  )

  it should "refuse a file naming a SCIM provisioned group" in withServer { (h, fix, token) =>
    fix.store.upsertGroup(RbacGroup("g-scim", Acme, "bi"))
    fix.store.setGroupExternalId("g-scim", Some("ext-1"))
    val resp = importRbac(h, token, Bundle)
    resp.statusCode() shouldBe 400
    errorCode(resp.body()) shouldBe Some("scim_group_conflict")
  }

  it should "stay restricted to superusers" in withServer { (h, _, _) =>
    val alice = h.mintToken(
      SecurityFixtures.AliceUsername,
      SecurityFixtures.AlicePassword,
      Some(Acme)
    )
    importRbac(h, alice, Bundle).statusCode() shouldBe 403
  }

  "GET /api/manifest/export?mode=rbac" should "return only the tenant's custom roles and groups" in
    withServer { (h, fix, token) =>
      importRbac(h, token, Bundle).statusCode() shouldBe 200
      fix.store.upsertRole(RbacRole("r-globex", SecurityFixtures.GlobexTenantId, "globex_role"))
      val resp = exportRbac(h, token)
      withClue(resp.body()) {
        resp.statusCode() shouldBe 200
        resp.body() should include("analyst")
        resp.body() should not include "globex_role"
      }
    }

  it should "carry no users, password hashes, tenants or timestamps" in withServer {
    (h, _, token) =>
      importRbac(h, token, Bundle).statusCode() shouldBe 200
      val body = exportRbac(h, token).body()
      body should not include "passwordHash"
      body should not include "users"
      body should not include "tenants"
      body should not include "exportedAt"
      body should not include "poolGrants"
  }

  it should "leave out SCIM provisioned groups" in withServer { (h, fix, token) =>
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    fix.store.upsertGroup(RbacGroup("g-scim", Acme, "from_idp"))
    fix.store.setGroupExternalId("g-scim", Some("ext-1"))
    exportRbac(h, token).body() should not include "from_idp"
  }

  it should "survive export, import, export unchanged" in withServer { (h, _, token) =>
    importRbac(h, token, Bundle).statusCode() shouldBe 200
    val first = exportRbac(h, token).body()
    importRbac(h, token, first).statusCode() shouldBe 200
    exportRbac(h, token).body() shouldBe first
  }

  it should "refuse a missing tenant" in withServer { (h, _, token) =>
    val resp = get(h.httpClient, s"${h.baseUrl}/api/manifest/export?mode=rbac", Some(token))
    resp.statusCode() shouldBe 400
    errorCode(resp.body()) shouldBe Some("rbac_tenant_required")
  }

  "GET /api/manifest/export" should "keep returning the full manifest without a mode" in
    withServer { (h, _, token) =>
      val body = get(h.httpClient, s"${h.baseUrl}/api/manifest/export", Some(token)).body()
      body should include("users")
      body should include("tenants")
    }
