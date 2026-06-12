package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

/** Pins the differentiated failure reasons returned by [[SessionTokenStore.lookupResult]] so
  * `AuthHandlers.whoami` (and any future client) can tell apart "you sent nothing" from "your
  * token is expired" from "your token was revoked" from "your signature doesn't verify". The
  * legacy `get`/`isAdmin`/etc. callers stay shape-compatible (still `Option[Session]`/`Boolean`)
  * because they only care about the bool. */
class SessionTokenStoreLookupResultSpec extends AnyFlatSpec, Matchers:

  private val profile = AuthenticatedProfile(
    username   = "alice",
    role       = "admin",
    groups     = Set.empty,
    claims     = Map.empty,
    authMethod = "db",
    tenant     = None
  )
  private val scope = SessionScope(superuser = false, manageableTenants = Set("t-a"))

  private val TestSecret = "spec-secret-padding-padding-padding-padding="

  private def fixture(maxLifetime: scala.concurrent.duration.FiniteDuration = 10.minutes) =
    val now = new AtomicReference[Instant](Instant.parse("2026-06-12T10:00:00Z"))
    val s   = new SessionTokenStore(secret = TestSecret, maxLifetime = maxLifetime, clock = () => now.get())
    (s, now)

  "lookupResult" should "return NoSession for an empty token" in {
    val (s, _) = fixture()
    s.lookupResult("") shouldBe SessionTokenStore.LookupResult.NoSession
  }

  it should "return Invalid for an unparseable string" in {
    val (s, _) = fixture()
    s.lookupResult("definitely-not-a-jwt") shouldBe SessionTokenStore.LookupResult.Invalid
  }

  it should "return Invalid for a JWT signed with a different secret (rotated key)" in {
    val (mintStore, _) = fixture()
    val token          = mintStore.mintWithScope(profile, scope)
    val readStore = new SessionTokenStore(
      secret      = "other-secret-padding-padding-padding-padding=",
      maxLifetime = 10.minutes,
      clock       = () => Instant.parse("2026-06-12T10:00:00Z")
    )
    readStore.lookupResult(token) shouldBe SessionTokenStore.LookupResult.Invalid
  }

  it should "return Expired for a token whose exp is in the past" in {
    val (s, now) = fixture(maxLifetime = 5.minutes)
    val token    = s.mintWithScope(profile, scope)
    now.set(now.get().plusSeconds(10 * 60))
    s.lookupResult(token) shouldBe SessionTokenStore.LookupResult.Expired
  }

  it should "return Revoked for a still-valid token whose jti is on the denylist" in {
    val (s, _) = fixture()
    val token  = s.mintWithScope(profile, scope)
    s.revoke(token)
    s.lookupResult(token) shouldBe SessionTokenStore.LookupResult.Revoked
  }

  it should "return Ok with the reconstructed session for a fresh valid token" in {
    val (s, _) = fixture()
    val token  = s.mintWithScope(profile, scope)
    s.lookupResult(token) match
      case SessionTokenStore.LookupResult.Ok(session) =>
        session.profile.username shouldBe "alice"
        session.scope.manageableTenants should contain ("t-a")
      case other => fail(s"expected Ok, got $other")
  }

  // Backward compat: existing callers shouldn't see behaviour shifts.
  "get" should "still return Some for a fresh token and None for any failure reason" in {
    val (s, now) = fixture(maxLifetime = 5.minutes)
    val token    = s.mintWithScope(profile, scope)
    s.get(token) shouldBe defined

    s.get("") shouldBe empty
    s.get("garbage") shouldBe empty

    now.set(now.get().plusSeconds(10 * 60))
    s.get(token) shouldBe empty
  }