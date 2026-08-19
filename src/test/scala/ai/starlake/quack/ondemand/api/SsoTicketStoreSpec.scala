package ai.starlake.quack.ondemand.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class SsoTicketStoreSpec extends AnyFlatSpec, Matchers:

  private def fixture =
    val clock = new AtomicReference[Instant](Instant.parse("2026-08-19T10:00:00Z"))
    val store = new SsoTicketStore(now = () => clock.get(), ttlSeconds = 60)
    (clock, store)

  private val grant =
    SsoGrant("tok-abc", "alice", Some("acme"), admin = false, superuser = false)

  "mint/redeem" should "round-trip a grant exactly once" in {
    val (_, store) = fixture
    val ticket = store.mint(grant)
    ticket.length should be >= 22
    store.redeem(ticket) shouldBe Some(grant)
    store.redeem(ticket) shouldBe None
  }

  it should "reject unknown tickets" in {
    val (_, store) = fixture
    store.redeem("no-such-ticket") shouldBe None
  }

  it should "reject expired tickets" in {
    val (clock, store) = fixture
    val ticket = store.mint(grant)
    clock.set(clock.get().plusSeconds(61))
    store.redeem(ticket) shouldBe None
  }

  it should "round-trip superuser=true for a superuser grant" in {
    val (_, store) = fixture
    val su = SsoGrant("tok-root", "root", None, admin = true, superuser = true)
    val redeemed = store.redeem(store.mint(su))
    redeemed shouldBe Some(su)
    redeemed.get.superuser shouldBe true
  }

  it should "round-trip superuser=false, admin=true for a tenant-admin grant" in {
    val (_, store) = fixture
    val tenantAdmin =
      SsoGrant("tok-ta", "alice", Some("acme"), admin = true, superuser = false)
    val redeemed = store.redeem(store.mint(tenantAdmin))
    redeemed shouldBe Some(tenantAdmin)
    redeemed.get.admin shouldBe true
    redeemed.get.superuser shouldBe false
  }

  it should "sweep expired entries on mint" in {
    val (clock, store) = fixture
    store.mint(grant)
    clock.set(clock.get().plusSeconds(61))
    store.mint(grant.copy(username = "bob"))
    store.size shouldBe 1
  }
