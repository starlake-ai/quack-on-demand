package ai.starlake.quack.ondemand.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the scope seam every gate reads:
  *   - [[SessionScope.resolver]] is total: no credential, or one the lookup cannot resolve, is
  *     [[SessionScope.NoAccess]], never a trusted operator;
  *   - [[SessionScope.tenantsFilter]], the telemetry tenant-scoping rule shared by the audit /
  *     history / usage / active-statement handlers: a superuser's requested tenant filter passes
  *     through unchanged; anyone else is pinned to `manageableTenants`, a requested tenant narrows
  *     WITHIN them, and a non-manageable request falls back to the full manageable set (no error,
  *     no existence leak), so NoAccess sees nothing;
  *   - [[SessionScope.staticKeyLookup]] (transitional, removed with the static key): the configured
  *     key resolves to a superuser, nothing else does.
  */
class SessionScopeSpec extends AnyFlatSpec with Matchers:

  private val admin = SessionScope(superuser = false, manageableTenants = Set("acme", "globex"))

  private val lookup: String => Option[SessionScope] = {
    case "live-admin" => Some(admin)
    case "live-root"  => Some(SessionScope.Superuser)
    case _            => None
  }

  "resolver" should "give an absent credential no privilege" in {
    SessionScope.resolver(lookup)(None) shouldBe SessionScope.NoAccess
  }

  it should "give an expired, revoked or unknown token no privilege" in {
    val scopeOf = SessionScope.resolver(lookup)
    scopeOf(Some("expired-session")) shouldBe SessionScope.NoAccess
    scopeOf(Some("")) shouldBe SessionScope.NoAccess
  }

  it should "pass resolvable tokens through unchanged" in {
    val scopeOf = SessionScope.resolver(lookup)
    scopeOf(Some("live-admin")) shouldBe admin
    scopeOf(Some("live-root")) shouldBe SessionScope.Superuser
  }

  "tenantsFilter" should "pass no filter through for a superuser" in {
    SessionScope.tenantsFilter(SessionScope.Superuser, None) shouldBe None
  }

  it should "pass the requested tenant through for a superuser" in {
    SessionScope.tenantsFilter(SessionScope.Superuser, Some("acme")) shouldBe Some(Set("acme"))
  }

  it should "pin a tenant admin to the manageable set when nothing is requested" in {
    SessionScope.tenantsFilter(admin, None) shouldBe Some(Set("acme", "globex"))
  }

  it should "narrow within the manageable set when a manageable tenant is requested" in {
    SessionScope.tenantsFilter(admin, Some("acme")) shouldBe Some(Set("acme"))
  }

  it should "fall back to the full manageable set on a non-manageable request (no leak)" in {
    SessionScope.tenantsFilter(admin, Some("initech")) shouldBe Some(Set("acme", "globex"))
  }

  it should "show a caller with no resolvable credential nothing, whatever it requests" in {
    val scopeOf = SessionScope.resolver(lookup)
    SessionScope.tenantsFilter(scopeOf(None), None) shouldBe Some(Set.empty)
    SessionScope.tenantsFilter(scopeOf(Some("expired-session")), Some("globex")) shouldBe
      Some(Set.empty)
  }

  // ---- staticKeyLookup: transitional, removed with the static key ----

  "staticKeyLookup" should "resolve the configured static key to a superuser" in {
    SessionScope.resolver(SessionScope.staticKeyLookup(Some("k1"), lookup))(Some("k1")) shouldBe
      SessionScope.Superuser
  }

  it should "leave every other token to the lookup" in {
    val scopeOf = SessionScope.resolver(SessionScope.staticKeyLookup(Some("k1"), lookup))
    scopeOf(Some("k1x")) shouldBe SessionScope.NoAccess
    scopeOf(Some("live-admin")) shouldBe admin
    scopeOf(None) shouldBe SessionScope.NoAccess
  }

  it should "treat an unset or empty static key as matching nothing" in
    List(None, Some("")).foreach { key =>
      val scopeOf = SessionScope.resolver(SessionScope.staticKeyLookup(key, lookup))
      scopeOf(Some("")) shouldBe SessionScope.NoAccess
      scopeOf(Some("anything")) shouldBe SessionScope.NoAccess
    }
