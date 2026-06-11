package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class TenantScopeCheckSpec extends AnyFlatSpec, Matchers:

  private val store = new SessionTokenStore
  private val profile = AuthenticatedProfile(
    username   = "alice",
    role       = "admin",
    groups     = Set.empty,
    claims     = Map.empty,
    authMethod = "db",
    tenant     = None
  )

  "reject" should "pass through a static-key (unknown token) call" in {
    TenantScopeCheck.reject(Some("static-key"), "t-x")(store.scopeOf) shouldBe None
  }

  it should "pass through an absent api key" in {
    TenantScopeCheck.reject(None, "t-x")(store.scopeOf) shouldBe None
  }

  it should "pass through a superuser session for any tenant" in {
    val tok = store.mintWithScope(profile, SessionScope.Superuser)
    TenantScopeCheck.reject(Some(tok), "t-x")(store.scopeOf) shouldBe None
  }

  it should "pass through a multi-tenant admin for a managed tenant" in {
    val tok = store.mintWithScope(profile, SessionScope(false, Set("t-a", "t-b")))
    TenantScopeCheck.reject(Some(tok), "t-a")(store.scopeOf) shouldBe None
  }

  it should "reject a multi-tenant admin for a non-managed tenant" in {
    val tok = store.mintWithScope(profile, SessionScope(false, Set("t-a")))
    val out = TenantScopeCheck.reject(Some(tok), "t-c")(store.scopeOf)
    out shouldBe defined
    val (code, err) = out.get
    code      shouldBe StatusCode.Forbidden
    err.error shouldBe "tenant_forbidden"
  }