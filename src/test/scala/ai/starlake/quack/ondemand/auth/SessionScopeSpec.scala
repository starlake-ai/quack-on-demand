package ai.starlake.quack.ondemand.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins [[SessionScope.tenantsFilter]], the single implementation of the telemetry tenant-scoping
  * rule shared by the audit/history/usage/active-statement handlers:
  *   - superuser or unresolvable session (static key / open mode): the requested tenant filter
  *     passes through unchanged;
  *   - tenant admin: pinned to `manageableTenants`, a requested tenant narrows WITHIN them, and a
  *     non-manageable request falls back to the full manageable set (no error, no existence leak).
  */
class SessionScopeSpec extends AnyFlatSpec with Matchers:

  private val admin = SessionScope(superuser = false, manageableTenants = Set("acme", "globex"))

  "tenantsFilter" should "pass no filter through for an unresolvable session" in {
    SessionScope.tenantsFilter(None, None) shouldBe None
  }

  it should "pass the requested tenant through for an unresolvable session" in {
    SessionScope.tenantsFilter(None, Some("acme")) shouldBe Some(Set("acme"))
  }

  it should "pass no filter through for a superuser" in {
    SessionScope.tenantsFilter(Some(SessionScope.Superuser), None) shouldBe None
  }

  it should "pass the requested tenant through for a superuser" in {
    SessionScope.tenantsFilter(Some(SessionScope.Superuser), Some("acme")) shouldBe
      Some(Set("acme"))
  }

  it should "pin a tenant admin to the manageable set when nothing is requested" in {
    SessionScope.tenantsFilter(Some(admin), None) shouldBe Some(Set("acme", "globex"))
  }

  it should "narrow within the manageable set when a manageable tenant is requested" in {
    SessionScope.tenantsFilter(Some(admin), Some("acme")) shouldBe Some(Set("acme"))
  }

  it should "fall back to the full manageable set on a non-manageable request (no leak)" in {
    SessionScope.tenantsFilter(Some(admin), Some("initech")) shouldBe Some(Set("acme", "globex"))
  }
