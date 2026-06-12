package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

/** Pins the idle-TTL semantics that replaced the v1 "tokens never expire" model:
  *   - sessions older than `idleTtl` (no access during the window) drop on the next access
  *   - every successful access slides the window forward
  *   - the expiry check uses the same clock for every reader, so isAdmin /
  *     isSuperuser / canManage / scopeOf all agree about a given moment
  *
  * Clock is injected so we don't have to sleep; the spec ticks `now` forward
  * deterministically.
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

  /** Build a store + a controllable clock. */
  private def fixture(idleTtl: scala.concurrent.duration.FiniteDuration = 10.minutes) =
    val now = new AtomicReference[Instant](Instant.parse("2026-06-12T10:00:00Z"))
    val s   = new SessionTokenStore(idleTtl = idleTtl, clock = () => now.get())
    (s, now)

  // ----- expiry -----

  "get" should "return None for a session idle past the TTL" in {
    val (s, now) = fixture(idleTtl = 10.minutes)
    val token    = s.mintWithScope(baseProfile, baseScope)

    s.get(token)          shouldBe defined
    now.set(now.get().plusSeconds(11 * 60)) // 11 minutes
    s.get(token)          shouldBe None
  }

  it should "also evict the entry so liveSessionCount drops" in {
    val (s, now) = fixture(idleTtl = 10.minutes)
    val token    = s.mintWithScope(baseProfile, baseScope)
    s.liveSessionCount shouldBe 1
    now.set(now.get().plusSeconds(11 * 60))
    s.get(token)             // triggers eviction
    s.liveSessionCount shouldBe 0
  }

  it should "slide the window so a session refreshed under the TTL stays alive" in {
    val (s, now) = fixture(idleTtl = 10.minutes)
    val token    = s.mintWithScope(baseProfile, baseScope)

    // Advance 8 minutes (under TTL), touch, advance another 8 -- total 16 from
    // mint, but only 8 since the last touch, so still alive.
    now.set(now.get().plusSeconds(8 * 60))
    s.get(token) shouldBe defined
    now.set(now.get().plusSeconds(8 * 60))
    s.get(token) shouldBe defined

    // Now stop touching: another 11 minutes idle drops it.
    now.set(now.get().plusSeconds(11 * 60))
    s.get(token) shouldBe None
  }

  // ----- all readers honour expiry -----

  "isAdmin / isSuperuser / canManage / scopeOf" should "all return falsy on an expired session" in {
    val (s, now) = fixture(idleTtl = 5.minutes)
    val token    = s.mintWithScope(
      baseProfile,
      SessionScope(superuser = true, manageableTenants = Set("t-a"))
    )

    s.isAdmin(token)        shouldBe true
    s.isSuperuser(token)    shouldBe true
    s.canManage(token, "t-a") shouldBe true
    s.scopeOf(token)        shouldBe defined

    now.set(now.get().plusSeconds(10 * 60)) // double the TTL

    s.isAdmin(token)        shouldBe false
    s.isSuperuser(token)    shouldBe false
    s.canManage(token, "t-a") shouldBe false
    s.scopeOf(token)        shouldBe empty
  }

  it should "slide the window when called via isAdmin too (every reader counts as access)" in {
    val (s, now) = fixture(idleTtl = 10.minutes)
    val token    = s.mintWithScope(baseProfile, baseScope)
    now.set(now.get().plusSeconds(8 * 60))
    s.isAdmin(token) shouldBe true        // slides
    now.set(now.get().plusSeconds(8 * 60)) // total 16m since mint, 8m since touch
    s.get(token)     shouldBe defined
  }

  // ----- defaults -----

  "default constructor" should "default to 8 hours" in {
    val s = new SessionTokenStore()
    s.idleTtl shouldBe 8.hours
  }