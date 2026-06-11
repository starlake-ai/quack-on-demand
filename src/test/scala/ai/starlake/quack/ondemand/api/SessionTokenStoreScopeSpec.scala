package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SessionTokenStoreScopeSpec extends AnyFlatSpec, Matchers:

  private val baseProfile = AuthenticatedProfile(
    username   = "alice",
    role       = "admin",
    groups     = Set.empty,
    claims     = Map.empty,
    authMethod = "db",
    tenant     = None
  )

  "mintWithScope" should "persist a superuser scope and expose isSuperuser=true" in {
    val s     = new SessionTokenStore
    val token = s.mintWithScope(
      baseProfile,
      SessionScope(superuser = true, manageableTenants = Set.empty)
    )
    s.isSuperuser(token)              shouldBe true
    s.canManage(token, "anything")    shouldBe true
  }

  it should "persist a multi-tenant scope and allow only listed tenants" in {
    val s     = new SessionTokenStore
    val token = s.mintWithScope(
      baseProfile.copy(tenant = None),
      SessionScope(superuser = false, manageableTenants = Set("t-a", "t-b"))
    )
    s.isSuperuser(token)        shouldBe false
    s.canManage(token, "t-a")   shouldBe true
    s.canManage(token, "t-b")   shouldBe true
    s.canManage(token, "t-c")   shouldBe false
  }

  "isAdmin" should "stay true for any minted scope (UI is admin-only)" in {
    val s = new SessionTokenStore
    val sup =
      s.mintWithScope(baseProfile, SessionScope(superuser = true, manageableTenants = Set.empty))
    val multi = s.mintWithScope(
      baseProfile.copy(tenant = None),
      SessionScope(superuser = false, manageableTenants = Set("t-a"))
    )
    s.isAdmin(sup)   shouldBe true
    s.isAdmin(multi) shouldBe true
  }