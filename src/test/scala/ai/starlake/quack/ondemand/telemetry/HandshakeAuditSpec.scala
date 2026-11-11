package ai.starlake.quack.ondemand.telemetry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class HandshakeAuditSpec extends AnyFlatSpec with Matchers:

  private final class Fixture:
    var now   = 1000000L
    val rows  = mutable.ListBuffer.empty[AuditEvent]
    val audit =
      new HandshakeAudit(rows += _, new AuditRateLimiter(60000, () => now))

  private def refusal(
      user: Option[String] = Some("alice"),
      client: Option[String] = Some("10.0.0.1")
  ) =
    HandshakeRefusal(
      user,
      Some("acme"),
      Some("bi"),
      superuser = false,
      client,
      "invalid_credentials"
    )

  "HandshakeAudit" should "write one row a minute per front door, client and user" in {
    val f = new Fixture
    f.audit.refused("flightsql", refusal())
    f.audit.refused("flightsql", refusal())
    f.audit.refused("quack", refusal())
    f.audit.refused("flightsql", refusal(user = Some("bob")))
    f.audit.refused("flightsql", refusal(client = Some("10.0.0.2")))
    f.rows.map(e => (e.origin, e.actor, e.detail("source"))) shouldBe List(
      ("flightsql", "alice", "10.0.0.1"),
      ("quack", "alice", "10.0.0.1"),
      ("flightsql", "bob", "10.0.0.1"),
      ("flightsql", "alice", "10.0.0.2")
    )
    f.now += 60000
    f.audit.refused("flightsql", refusal())
    f.rows should have size 5
  }

  it should "key a refusal that names no user by its address alone" in {
    val f = new Fixture
    f.audit.refused("flightsql", refusal(user = None))
    f.audit.refused("flightsql", refusal(user = None))
    f.rows should have size 1
    f.rows.head.actor shouldBe "anonymous"
    f.rows.head.detail.get("username") shouldBe None
  }

  it should "say unknown for a client it cannot place" in {
    val f = new Fixture
    f.audit.refused("flightsql", refusal(client = None))
    f.rows.head.detail("source") shouldBe "unknown"
  }

  it should "strip control characters and cut what a stranger typed" in {
    val f = new Fixture
    f.audit.refused("quack", refusal(user = Some("al\u0000ice\n" + "x" * 1000)))
    val e = f.rows.head
    e.actor should startWith("alice ")
    e.actor.length shouldBe AuditRecorder.MaxNameChars
    e.detail("username") shouldBe e.actor
    e.actor should not include "\u0000"
  }

  it should "record the realm the client asked for and the fixed reason" in {
    val f = new Fixture
    f.audit.refused("flightsql", refusal().copy(superuser = true, reason = "no_pool_access"))
    val e = f.rows.head
    e.actorRealm shouldBe "system"
    e.action shouldBe AuditActions.AuthLoginFailure
    e.family shouldBe "auth"
    e.outcome shouldBe "denied"
    e.tenant shouldBe Some("acme")
    e.detail("pool") shouldBe "bi"
    e.detail("reason") shouldBe "no_pool_access"
  }
