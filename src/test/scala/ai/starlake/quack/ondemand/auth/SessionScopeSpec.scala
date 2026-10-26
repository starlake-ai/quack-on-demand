package ai.starlake.quack.ondemand.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the two scope rules every gate shares:
  *   - [[SessionScope.resolver]] is total: no credential, or one the lookup cannot resolve, is
  *     [[SessionScope.NoAccess]]; no token string is a superuser unless the lookup says so;
  *   - [[SessionScope.tenantsFilter]], the telemetry tenant-scoping rule shared by the
  *     audit/history/usage/active-statement handlers: a superuser's requested tenant filter passes
  *     through unchanged; a tenant admin is pinned to `manageableTenants`, a requested tenant
  *     narrows WITHIN them, and a non-manageable request falls back to the full manageable set (no
  *     error, no existence leak); NoAccess sees nothing.
  */
class SessionScopeSpec extends AnyFlatSpec with Matchers:

  private val admin = SessionScope(superuser = false, manageableTenants = Set("acme", "globex"))

  private val lookup: String => Option[SessionScope] = {
    case "live-admin" => Some(admin)
    case "live-root"  => Some(SessionScope.Superuser)
    case _            => None
  }

  "resolver" should "give no credential no privilege" in {
    SessionScope.resolver(lookup)(None) shouldBe SessionScope.NoAccess
  }

  it should "pass resolvable tokens through unchanged" in {
    val scopeOf = SessionScope.resolver(lookup)
    scopeOf(Some("live-admin")) shouldBe admin
    scopeOf(Some("live-root")) shouldBe SessionScope.Superuser
  }

  it should "give an expired, revoked, unknown or empty token no privilege" in {
    val scopeOf = SessionScope.resolver(lookup)
    List("expired-session", "", "k1", "qod_pat_unknown").foreach { t =>
      scopeOf(Some(t)) shouldBe SessionScope.NoAccess
    }
  }

  it should "never answer a superuser scope the lookup did not resolve" in {
    val scopeOf = SessionScope.resolver(_ => None)
    List(None, Some(""), Some("static-key-1"), Some("live-root")).foreach { k =>
      scopeOf(k).superuser shouldBe false
    }
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

  it should "give NoAccess an empty filter, whatever is requested" in {
    SessionScope.tenantsFilter(SessionScope.NoAccess, None) shouldBe Some(Set.empty)
    SessionScope.tenantsFilter(SessionScope.NoAccess, Some("globex")) shouldBe Some(Set.empty)
  }
