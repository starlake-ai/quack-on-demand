package ai.starlake.quack.ondemand.telemetry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Pure fold from day-level aggregate rows into sorted usage groups. */
class UsageMathSpec extends AnyFlatSpec with Matchers:

  private val d1 = Instant.parse("2026-07-01T00:00:00Z")
  private val d2 = Instant.parse("2026-07-02T00:00:00Z")

  private def row(
      tenant: String,
      pool: String,
      username: String,
      day: Instant,
      statements: Long,
      errors: Long = 0L,
      denied: Long = 0L,
      engineMs: Long = 0L
  ) = UsageMath.DayRow(tenant, pool, username, day, statements, errors, denied, engineMs)

  "fold" should "sum totals per group and keep per-day rows ascending" in {
    val groups = UsageMath.fold(
      "tenant",
      List(
        row("acme", "", "", d2, statements = 3L, errors = 1L, denied = 0L, engineMs = 30L),
        row("acme", "", "", d1, statements = 2L, errors = 0L, denied = 1L, engineMs = 20L)
      )
    )
    groups should have size 1
    val g = groups.head
    g.tenant shouldBe "acme"
    g.pool shouldBe None
    g.username shouldBe None
    g.statements shouldBe 5L
    g.errors shouldBe 1L
    g.denied shouldBe 1L
    g.engineMs shouldBe 50L
    g.days.map(_.day) shouldBe List(d1, d2)
    g.days.map(_.statements) shouldBe List(2L, 3L)
    g.days.map(_.errors) shouldBe List(0L, 1L)
    g.days.map(_.engineMs) shouldBe List(20L, 30L)
  }

  it should "sort groups by engineMs descending with a stable key tiebreak" in {
    val groups = UsageMath.fold(
      "pool",
      List(
        row("acme", "bi", "", d1, statements = 1L, engineMs = 10L),
        row("acme", "etl", "", d1, statements = 1L, engineMs = 90L),
        row("globex", "bi", "", d1, statements = 1L, engineMs = 10L)
      )
    )
    groups.map(g => (g.tenant, g.pool)) shouldBe List(
      ("acme", Some("etl")),
      ("acme", Some("bi")),
      ("globex", Some("bi"))
    )
  }

  it should "expose pool only for groupBy=pool and username only for groupBy=user" in {
    val byPool = UsageMath.fold("pool", List(row("acme", "bi", "", d1, 1L))).head
    byPool.pool shouldBe Some("bi")
    byPool.username shouldBe None
    val byUser = UsageMath.fold("user", List(row("acme", "", "alice", d1, 1L))).head
    byUser.pool shouldBe None
    byUser.username shouldBe Some("alice")
  }

  it should "return Nil for no rows" in {
    UsageMath.fold("tenant", Nil) shouldBe Nil
  }
