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

  private val grant = SsoGrant("tok-abc", "alice", Some("acme"), admin = false)

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

  it should "sweep expired entries on mint" in {
    val (clock, store) = fixture
    store.mint(grant)
    clock.set(clock.get().plusSeconds(61))
    store.mint(grant.copy(username = "bob"))
    store.size shouldBe 1
  }
