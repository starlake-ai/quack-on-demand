package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.testkit.TestPostgres.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

/** Postgres-backed contract tests for queryUsage: exact group totals and per-day arrays for each
  * groupBy, half-open [from, to) period semantics, tenant scoping arms, pool filter, engineMs
  * descending order, dataStart.
  */
class PostgresUsageSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qoduse")

  private def withStore(test: PostgresTelemetryStore => Unit): Unit =
    if !TestPostgres.reachable then cancel("local Postgres not reachable")
    val dbName = s"qoduse_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = dbUrl(dbName)
      new LiquibaseRunner(url, pgUser, pgPass).run()
      val store = new PostgresTelemetryStore(url, pgUser, pgPass)
      try test(store)
      finally store.close()
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private val d01 = Instant.parse("2026-07-01T00:00:00Z")
  private val d02 = Instant.parse("2026-07-02T00:00:00Z")
  private val d03 = Instant.parse("2026-07-03T00:00:00Z")

  private def ev(
      ts: Instant,
      durationMs: Long,
      username: String = "alice",
      tenant: String = "acme",
      pool: String = "bi",
      status: String = "ok"
  ) = StatementEvent(ts, username, tenant, pool, "n1", "SELECT 1", durationMs, None, status, None)

  /** Seed raw statements across three days / two tenants / two pools / two users, then roll up.
    *
    * Day 2026-07-01: acme/bi alice ok 10ms; acme/bi alice denied 20ms; acme/etl bob transient 30ms.
    * Day 2026-07-02: acme/bi bob ok 40ms. Day 2026-07-03: globex/bi carol ok 100ms.
    *
    * Expected daily aggregates:
    *   - acme totals: statements 4, errors 1 (transient), denied 1, engineMs 100.
    *   - acme/bi: statements 3, errors 0, denied 1, engineMs 70. acme/etl: 1 / 1 / 0 / 30.
    *   - acme users: alice 2 stmts 30ms (1 denied), bob 2 stmts 70ms (1 error).
    *   - globex: 1 stmt, 100ms, day d03 only.
    */
  private def seed(s: PostgresTelemetryStore): Unit =
    s.appendStatements(
      List(
        ev(d01.plusSeconds(3600), 10L),
        ev(d01.plusSeconds(7200), 20L, status = "denied"),
        ev(d01.plusSeconds(10800), 30L, username = "bob", pool = "etl", status = "transient"),
        ev(d02.plusSeconds(3600), 40L, username = "bob"),
        ev(d03.plusSeconds(3600), 100L, username = "carol", tenant = "globex")
      )
    )
    s.recomputeRollups(None, d03.plusSeconds(7200))

  private def q(
      groupBy: String,
      tenants: Option[Set[String]] = None,
      pool: Option[String] = None,
      from: Instant = d01,
      to: Instant = Instant.parse("2026-07-04T00:00:00Z")
  ) = UsageQuery(groupBy, tenants, pool, from, to)

  "queryUsage groupBy=tenant" should "return exact totals and ascending per-day arrays" in
    withStore { s =>
      seed(s)
      val r = s.queryUsage(q("tenant"))
      r.groups.map(_.tenant) shouldBe List("acme", "globex") // engineMs 100 vs 100: key tiebreak
      val acme = r.groups.find(_.tenant == "acme").get
      acme.pool shouldBe None
      acme.username shouldBe None
      acme.statements shouldBe 4L
      acme.errors shouldBe 1L
      acme.denied shouldBe 1L
      acme.engineMs shouldBe 100L
      acme.days.map(_.day) shouldBe List(d01, d02)
      acme.days.map(_.statements) shouldBe List(3L, 1L)
      acme.days.map(_.errors) shouldBe List(1L, 0L)
      acme.days.map(_.engineMs) shouldBe List(60L, 40L)
      val globex = r.groups.find(_.tenant == "globex").get
      globex.statements shouldBe 1L
      globex.engineMs shouldBe 100L
      globex.days.map(_.day) shouldBe List(d03)
    }

  "queryUsage groupBy=pool" should "group by (tenant, pool) with pool set" in
    withStore { s =>
      seed(s)
      val r = s.queryUsage(q("pool", tenants = Some(Set("acme"))))
      r.groups.map(g => (g.tenant, g.pool)) shouldBe List(
        ("acme", Some("bi")),
        ("acme", Some("etl"))
      ) // bi engineMs 70 > etl 30
      val bi = r.groups.head
      bi.statements shouldBe 3L
      bi.denied shouldBe 1L
      bi.errors shouldBe 0L
      bi.engineMs shouldBe 70L
      bi.username shouldBe None
    }

  "queryUsage groupBy=user" should "group by (tenant, username) with username set" in
    withStore { s =>
      seed(s)
      val r = s.queryUsage(q("user", tenants = Some(Set("acme"))))
      r.groups.map(g => (g.tenant, g.username)) shouldBe List(
        ("acme", Some("bob")),
        ("acme", Some("alice"))
      ) // bob engineMs 70 > alice 30
      val bob = r.groups.head
      bob.statements shouldBe 2L
      bob.errors shouldBe 1L
      bob.denied shouldBe 0L
      bob.engineMs shouldBe 70L
      bob.pool shouldBe None
    }

  "queryUsage period" should "be half-open [from, to)" in
    withStore { s =>
      seed(s)
      // to = d02 excludes the d02 bucket; from = d02 includes it.
      val upTo = s.queryUsage(q("tenant", tenants = Some(Set("acme")), from = d01, to = d02))
      upTo.groups.head.statements shouldBe 3L
      upTo.groups.head.days.map(_.day) shouldBe List(d01)
      val fromD2 = s.queryUsage(q("tenant", tenants = Some(Set("acme")), from = d02, to = d03))
      fromD2.groups.head.statements shouldBe 1L
      fromD2.groups.head.days.map(_.day) shouldBe List(d02)
    }

  "queryUsage tenant scoping" should "honor the None / Some(set) / Some(empty) arms" in
    withStore { s =>
      seed(s)
      s.queryUsage(q("tenant")).groups.map(_.tenant).toSet shouldBe Set("acme", "globex")
      s.queryUsage(q("tenant", tenants = Some(Set("globex"))))
        .groups
        .map(_.tenant) shouldBe List("globex")
      val empty = s.queryUsage(q("tenant", tenants = Some(Set.empty)))
      empty.groups shouldBe Nil
      empty.dataStart shouldBe None
    }

  "queryUsage pool filter" should "narrow rows but not dataStart" in
    withStore { s =>
      seed(s)
      val r = s.queryUsage(q("tenant", tenants = Some(Set("acme")), pool = Some("etl")))
      r.groups.head.statements shouldBe 1L
      r.groups.head.engineMs shouldBe 30L
      r.dataStart shouldBe Some(d01)
    }

  "queryUsage dataStart" should "be the oldest daily bucket in tenant scope, ignoring the period" in
    withStore { s =>
      seed(s)
      val r = s.queryUsage(
        q("tenant", tenants = Some(Set("globex")), from = d03, to = d03.plusSeconds(86400))
      )
      r.dataStart shouldBe Some(d03) // globex's oldest, not acme's d01
      val all = s.queryUsage(q("tenant", from = d03, to = d03.plusSeconds(86400)))
      all.dataStart shouldBe Some(d01) // unrestricted scope sees acme's older bucket
    }

  "queryUsage on an empty period" should "return no groups but still report dataStart" in
    withStore { s =>
      seed(s)
      val r = s.queryUsage(
        q(
          "tenant",
          from = Instant.parse("2025-01-01T00:00:00Z"),
          to = Instant.parse("2025-02-01T00:00:00Z")
        )
      )
      r.groups shouldBe Nil
      r.dataStart shouldBe Some(d01)
    }

  "queryUsage" should "reject an unknown groupBy" in
    withStore { s =>
      an[IllegalArgumentException] should be thrownBy s.queryUsage(q("bogus"))
    }
