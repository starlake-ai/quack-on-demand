package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

/** Pins the absolute-expiry semantics of the JWT-backed SessionTokenStore.
  *
  * Before the JWT migration the store used an idle-TTL with a sliding window
  * (each access pushed `lastAccessedAt` forward). With JWT the `exp` claim is
  * baked into the token at mint time and never moves; an access either
  * succeeds or fails against the same fixed deadline. That's a deliberate
  * trade-off for going stateless -- documented in [[SessionTokenStore]].
  *
  * `clock` is injected so the spec ticks `now` forward deterministically.
  */
class SessionTokenStoreExpirySpec extends AnyFlatSpec, Matchers:

  private val baseProfile = AuthenticatedProfile(
    username   = "alice",
    role       = "admin",
    groups     = Set.empty,
    claims     = Map.empty,
    authMethod = "db",
    tenant     = None
  )

  private val baseScope = SessionScope(superuser = false, manageableTenants = Set("t-a"))

  /** 44-char base64 = 32 raw bytes -> HS256 min key length. */
  private val TestSecret = "spec-secret-padding-padding-padding-padding="

  /** Build a store + a controllable clock. */
  private def fixture(maxLifetime: scala.concurrent.duration.FiniteDuration = 10.minutes) =
    val now = new AtomicReference[Instant](Instant.parse("2026-06-12T10:00:00Z"))
    val s   = new SessionTokenStore(
      secret      = TestSecret,
      maxLifetime = maxLifetime,
      clock       = () => now.get()
    )
    (s, now)

  // ----- expiry -----

  "get" should "return None for a token past its absolute exp" in {
    val (s, now) = fixture(maxLifetime = 10.minutes)
    val token    = s.mintWithScope(baseProfile, baseScope)

    s.get(token)          shouldBe defined
    now.set(now.get().plusSeconds(11 * 60)) // 11 minutes
    s.get(token)          shouldBe None
  }

  it should "still admit a token mid-lifetime even after several reads" in {
    val (s, now) = fixture(maxLifetime = 10.minutes)
    val token    = s.mintWithScope(baseProfile, baseScope)

    // Multiple reads under the deadline - all admit. There's no sliding-
    // window any more, but the JWT keeps verifying until exp.
    now.set(now.get().plusSeconds(3 * 60))
    s.get(token) shouldBe defined
    now.set(now.get().plusSeconds(3 * 60))
    s.get(token) shouldBe defined
    now.set(now.get().plusSeconds(3 * 60)) // total 9m, still under 10m
    s.get(token) shouldBe defined

    // One more minute past exp drops it.
    now.set(now.get().plusSeconds(2 * 60))
    s.get(token) shouldBe None
  }

  // ----- all readers honour expiry -----

  "isAdmin / isSuperuser / canManage / scopeOf" should "all return falsy past exp" in {
    val (s, now) = fixture(maxLifetime = 5.minutes)
    val token    = s.mintWithScope(
      baseProfile,
      SessionScope(superuser = true, manageableTenants = Set("t-a"))
    )

    s.isAdmin(token)          shouldBe true
    s.isSuperuser(token)      shouldBe true
    s.canManage(token, "t-a") shouldBe true
    s.scopeOf(token)          shouldBe defined

    now.set(now.get().plusSeconds(10 * 60)) // double the lifetime

    s.isAdmin(token)          shouldBe false
    s.isSuperuser(token)      shouldBe false
    s.canManage(token, "t-a") shouldBe false
    s.scopeOf(token)          shouldBe empty
  }

  // ----- revocation -----

  "revoke" should "make a still-valid token unrecognised by every reader" in {
    val (s, _) = fixture()
    val token  = s.mintWithScope(baseProfile, baseScope)
    s.isAdmin(token) shouldBe true

    s.revoke(token)

    s.isAdmin(token)          shouldBe false
    s.scopeOf(token)          shouldBe empty
    s.canManage(token, "t-a") shouldBe false
  }

  it should "sweep its own denylist entries past their exp" in {
    val (s, now) = fixture(maxLifetime = 5.minutes)
    val token    = s.mintWithScope(baseProfile, baseScope)
    s.revoke(token)
    s.revokedJtiCount shouldBe 1

    // Advance past the JWT's exp and revoke a second token -- the sweep
    // inside revoke() should drop the first entry.
    now.set(now.get().plusSeconds(10 * 60))
    val later = s.mintWithScope(baseProfile, baseScope)
    s.revoke(later)
    s.revokedJtiCount shouldBe 1
  }

  // ----- tamper resistance -----

  "get" should "reject a JWT signed with a different secret" in {
    val (mintStore, _) = fixture()
    val token          = mintStore.mintWithScope(baseProfile, baseScope)

    // Same lifetime, same clock, DIFFERENT secret.
    val readStore = new SessionTokenStore(
      secret      = "other-secret-padding-padding-padding-padding=",
      maxLifetime = 10.minutes,
      clock       = () => Instant.parse("2026-06-12T10:00:00Z")
    )
    readStore.get(token) shouldBe None
  }

  // ----- defaults -----

  "default constructor" should "use an 8-hour max lifetime" in {
    val s = new SessionTokenStore()
    s.idleTtl shouldBe 8.hours
  }