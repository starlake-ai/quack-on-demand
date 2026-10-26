package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class TenantScopeCheckSpec extends AnyFlatSpec, Matchers:

  private val store   = new SessionTokenStore
  private val scopeOf = SessionScope.resolver(store.scopeOf)
  private val profile = AuthenticatedProfile(
    username = "alice",
    role = "admin",
    groups = Set.empty,
    claims = Map.empty,
    authMethod = "db",
    tenant = None
  )

  private val Forbidden = Some((StatusCode.Forbidden, "tenant_forbidden"))

  private def codes(out: Option[(StatusCode, ErrorResponse)]) = out.map((c, e) => (c, e.error))

  "reject" should "refuse a token that resolves to no session (NoAccess, never trusted)" in {
    codes(TenantScopeCheck.reject(Some("not-a-session"), "t-x")(scopeOf)) shouldBe Forbidden
  }

  it should "refuse an absent credential" in {
    codes(TenantScopeCheck.reject(None, "t-x")(scopeOf)) shouldBe Forbidden
  }

  it should "pass through a superuser session for any tenant" in {
    val tok = store.mintWithScope(profile, SessionScope.Superuser)
    TenantScopeCheck.reject(Some(tok), "t-x")(scopeOf) shouldBe None
  }

  it should "pass through a multi-tenant admin for a managed tenant" in {
    val tok = store.mintWithScope(profile, SessionScope(false, Set("t-a", "t-b")))
    TenantScopeCheck.reject(Some(tok), "t-a")(scopeOf) shouldBe None
  }

  it should "reject a multi-tenant admin for a non-managed tenant" in {
    val tok = store.mintWithScope(profile, SessionScope(false, Set("t-a")))
    codes(TenantScopeCheck.reject(Some(tok), "t-c")(scopeOf)) shouldBe Forbidden
  }

  "rejectForResource" should "refuse an absent credential on a resource of a known tenant" in {
    codes(TenantScopeCheck.rejectForResource(None, Some("t-a"))(scopeOf)) shouldBe Forbidden
  }

  "rejectForUser" should "refuse an absent credential on a superuser account" in {
    codes(TenantScopeCheck.rejectForUser(None, Some(None))(scopeOf)) shouldBe Forbidden
  }
