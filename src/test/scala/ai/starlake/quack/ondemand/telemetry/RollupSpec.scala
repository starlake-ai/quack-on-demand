package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.testkit.TestPostgres.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

/** Postgres-backed tests for the rollup engine: watermark round-trip, recomputeRollups (exact
  * bucket values, idempotency, incremental pass), queryRollups filters, purgeRollups.
  */
class RollupSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodroll")

  private def withStore(test: PostgresTelemetryStore => Unit): Unit =
    if !TestPostgres.reachable then cancel("local Postgres not reachable")
    val dbName = s"qodroll_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = dbUrl(dbName)
      new LiquibaseRunner(url, pgUser, pgPass).run()
      val store = new PostgresTelemetryStore(url, pgUser, pgPass)
      try test(store)
      finally store.close()
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private val eps = 1e-9

  // Two adjacent hours on 2026-07-06 UTC.
  private val h10 = Instant.parse("2026-07-06T10:00:00Z")
  private val h11 = Instant.parse("2026-07-06T11:00:00Z")
  private val h12 = Instant.parse("2026-07-06T12:00:00Z")
  private val d06 = Instant.parse("2026-07-06T00:00:00Z")

  private def ev(
      ts: Instant,
      durationMs: Long,
      username: String = "alice",
      tenant: String = "acme",
      pool: String = "bi",
      status: String = "ok"
  ) = StatementEvent(ts, username, tenant, pool, "n1", "SELECT 1", durationMs, None, status, None)

  /** Fixed seed used by several tests.
    *
    * Hour 10, acme/bi: durations 10, 20, 30, 40 (alice x2, bob x2); statuses ok, ok, denied,
    * transient. percentile_cont over [10,20,30,40]: p50=25.0, p95=38.5, p99=39.7.
    *
    * Hour 11, acme/bi: alice single row duration 100, status ok. All percentiles = 100.0.
    *
    * Hour 10, globex/etl: bob durations 50, 60, both ok. p50=55.0, p95=59.5, p99=59.9.
    */
  private def seed(s: PostgresTelemetryStore): Unit =
    s.appendStatements(
      List(
        ev(h10.plusSeconds(60), 10L, username = "alice"),
        ev(h10.plusSeconds(120), 20L, username = "alice"),
        ev(h10.plusSeconds(180), 30L, username = "bob", status = "denied"),
        ev(h10.plusSeconds(240), 40L, username = "bob", status = "transient"),
        ev(h11.plusSeconds(60), 100L, username = "alice"),
        ev(h10.plusSeconds(300), 50L, username = "bob", tenant = "globex", pool = "etl"),
        ev(h10.plusSeconds(360), 60L, username = "bob", tenant = "globex", pool = "etl")
      )
    )

  // ---------------------------------------------------------------------------
  // Watermark
  // ---------------------------------------------------------------------------

  "rollupWatermark" should "round-trip None -> advance -> Some" in withStore { s =>
    s.rollupWatermark() shouldBe None
    val t = Instant.parse("2026-07-06T11:30:00Z")
    s.advanceRollupWatermark(t)
    s.rollupWatermark() shouldBe Some(t)
    val t2 = Instant.parse("2026-07-06T12:30:00Z")
    s.advanceRollupWatermark(t2)
    s.rollupWatermark() shouldBe Some(t2)
  }

  // ---------------------------------------------------------------------------
  // recomputeRollups: exact bucket values
  // ---------------------------------------------------------------------------

  "recomputeRollups" should "be a no-op when there is no watermark and no raw rows" in withStore {
    s =>
      s.recomputeRollups(None, Instant.parse("2026-07-06T12:00:00Z"))
      s.queryRollups(RollupQuery("hour")) shouldBe Nil
      s.queryRollups(RollupQuery("day")) shouldBe Nil
  }

  it should "produce exact hourly buckets (counts, splits, sum, percentiles)" in withStore { s =>
    seed(s)
    s.recomputeRollups(None, h11.plusSeconds(1800))

    val hourly = s.queryRollups(RollupQuery("hour"))
    hourly.size shouldBe 3

    val acme10 = hourly.find(b => b.tenant == "acme" && b.bucketStart == h10).get
    acme10.pool shouldBe "bi"
    acme10.username shouldBe ""
    acme10.stmtCount shouldBe 4L
    acme10.errorCount shouldBe 1L  // transient only
    acme10.deniedCount shouldBe 1L // denied only
    acme10.engineMsSum shouldBe 100L
    acme10.p50Ms.get shouldBe 25.0 +- eps
    acme10.p95Ms.get shouldBe 38.5 +- eps
    acme10.p99Ms.get shouldBe 39.7 +- eps

    val acme11 = hourly.find(b => b.tenant == "acme" && b.bucketStart == h11).get
    acme11.stmtCount shouldBe 1L
    acme11.errorCount shouldBe 0L
    acme11.deniedCount shouldBe 0L
    acme11.engineMsSum shouldBe 100L
    acme11.p50Ms.get shouldBe 100.0 +- eps
    acme11.p95Ms.get shouldBe 100.0 +- eps
    acme11.p99Ms.get shouldBe 100.0 +- eps

    val globex10 = hourly.find(b => b.tenant == "globex" && b.bucketStart == h10).get
    globex10.pool shouldBe "etl"
    globex10.stmtCount shouldBe 2L
    globex10.errorCount shouldBe 0L
    globex10.deniedCount shouldBe 0L
    globex10.engineMsSum shouldBe 110L
    globex10.p50Ms.get shouldBe 55.0 +- eps
    globex10.p95Ms.get shouldBe 59.5 +- eps
    globex10.p99Ms.get shouldBe 59.9 +- eps
  }

  it should "produce daily buckets with per-username dimension and None percentiles" in withStore {
    s =>
      seed(s)
      s.recomputeRollups(None, h11.plusSeconds(1800))

      val daily = s.queryRollups(RollupQuery("day"))
      daily.size shouldBe 3
      daily.foreach { b =>
        b.bucketStart shouldBe d06
        b.p50Ms shouldBe None
        b.p95Ms shouldBe None
        b.p99Ms shouldBe None
      }

      val alice = daily.find(b => b.tenant == "acme" && b.username == "alice").get
      alice.pool shouldBe "bi"
      alice.stmtCount shouldBe 3L // 10, 20 in hour 10 + 100 in hour 11
      alice.errorCount shouldBe 0L
      alice.deniedCount shouldBe 0L
      alice.engineMsSum shouldBe 130L

      val bobAcme = daily.find(b => b.tenant == "acme" && b.username == "bob").get
      bobAcme.stmtCount shouldBe 2L
      bobAcme.errorCount shouldBe 1L
      bobAcme.deniedCount shouldBe 1L
      bobAcme.engineMsSum shouldBe 70L

      val bobGlobex = daily.find(b => b.tenant == "globex" && b.username == "bob").get
      bobGlobex.pool shouldBe "etl"
      bobGlobex.stmtCount shouldBe 2L
      bobGlobex.errorCount shouldBe 0L
      bobGlobex.deniedCount shouldBe 0L
      bobGlobex.engineMsSum shouldBe 110L
  }

  // ---------------------------------------------------------------------------
  // Idempotency
  // ---------------------------------------------------------------------------

  it should "be idempotent: recompute twice over the same range yields identical buckets" in
    withStore { s =>
      seed(s)
      val to = h11.plusSeconds(1800)
      s.recomputeRollups(None, to)
      val hourly1 = s.queryRollups(RollupQuery("hour"))
      val daily1  = s.queryRollups(RollupQuery("day"))
      s.recomputeRollups(None, to)
      s.queryRollups(RollupQuery("hour")) shouldBe hourly1
      s.queryRollups(RollupQuery("day")) shouldBe daily1
    }

  // ---------------------------------------------------------------------------
  // Incremental pass with watermark
  // ---------------------------------------------------------------------------

  it should "update only the newest hour on an incremental pass from the watermark" in withStore {
    s =>
      seed(s)
      val firstTo = h11.plusSeconds(1800) // 11:30
      s.recomputeRollups(None, firstTo)
      s.advanceRollupWatermark(firstTo)

      val acme10Before =
        s.queryRollups(RollupQuery("hour"))
          .find(b => b.tenant == "acme" && b.bucketStart == h10)
          .get

      // New rows land in hour 11 (after the watermark).
      s.appendStatements(
        List(
          ev(h11.plusSeconds(2100), 200L, username = "alice"),
          ev(h11.plusSeconds(2400), 300L, username = "bob", status = "error")
        )
      )
      val secondTo = h12.minusSeconds(60) // 11:59
      s.recomputeRollups(s.rollupWatermark(), secondTo)
      s.advanceRollupWatermark(secondTo)

      val hourly = s.queryRollups(RollupQuery("hour"))
      // Hourly recompute window is [hourFloor(11:30)=11:00, 12:00): hour-10 buckets untouched.
      hourly.find(b => b.tenant == "acme" && b.bucketStart == h10).get shouldBe acme10Before
      hourly.find(b => b.tenant == "globex" && b.bucketStart == h10) shouldBe defined

      // Hour-11 bucket now aggregates all three rows: durations 100, 200, 300.
      val acme11 = hourly.find(b => b.tenant == "acme" && b.bucketStart == h11).get
      acme11.stmtCount shouldBe 3L
      acme11.errorCount shouldBe 1L
      acme11.deniedCount shouldBe 0L
      acme11.engineMsSum shouldBe 600L
      // percentile_cont over [100,200,300]: p50=200, p95=290, p99=298
      acme11.p50Ms.get shouldBe 200.0 +- eps
      acme11.p95Ms.get shouldBe 290.0 +- eps
      acme11.p99Ms.get shouldBe 298.0 +- eps

      // Daily bucket for alice covers the whole day again (widened window):
      // durations 10, 20, 100, 200 -> 330
      val alice = s.queryRollups(RollupQuery("day")).find(_.username == "alice").get
      alice.stmtCount shouldBe 4L
      alice.engineMsSum shouldBe 330L
  }

  // ---------------------------------------------------------------------------
  // queryRollups filters
  // ---------------------------------------------------------------------------

  "queryRollups" should "filter by granularity, tenants, pool, and bucket range" in withStore { s =>
    seed(s)
    s.recomputeRollups(None, h11.plusSeconds(1800))

    // granularity separation
    s.queryRollups(RollupQuery("hour")).foreach(_.granularity shouldBe "hour")
    s.queryRollups(RollupQuery("day")).foreach(_.granularity shouldBe "day")

    // tenant set
    val acmeOnly = s.queryRollups(RollupQuery("hour", tenants = Some(Set("acme"))))
    acmeOnly.map(_.tenant).distinct shouldBe List("acme")
    acmeOnly.size shouldBe 2
    s.queryRollups(RollupQuery("hour", tenants = Some(Set("acme", "globex")))).size shouldBe 3

    // empty tenant set -> no rows
    s.queryRollups(RollupQuery("hour", tenants = Some(Set.empty))) shouldBe Nil

    // pool filter
    val etlOnly = s.queryRollups(RollupQuery("hour", pool = Some("etl")))
    etlOnly.map(_.pool).distinct shouldBe List("etl")
    etlOnly.size shouldBe 1

    // bucket range: from inclusive, to exclusive
    s.queryRollups(RollupQuery("hour", from = Some(h11))).size shouldBe 1
    s.queryRollups(RollupQuery("hour", to = Some(h11))).size shouldBe 2
    s.queryRollups(RollupQuery("hour", from = Some(h10), to = Some(h11))).size shouldBe 2
  }

  it should "return buckets in ascending bucketStart order" in withStore { s =>
    seed(s)
    s.recomputeRollups(None, h11.plusSeconds(1800))
    val hourly = s.queryRollups(RollupQuery("hour"))
    hourly.map(_.bucketStart) shouldBe hourly
      .map(_.bucketStart)
      .sortWith(_.isBefore(_))
    hourly.head.bucketStart shouldBe h10
    hourly.last.bucketStart shouldBe h11
  }

  // ---------------------------------------------------------------------------
  // purgeRollups
  // ---------------------------------------------------------------------------

  "purgeRollups" should "delete only the given granularity below the cutoff" in withStore { s =>
    seed(s)
    s.recomputeRollups(None, h11.plusSeconds(1800))

    // Cutoff after hour 11: purging 'hour' removes all 3 hourly buckets, keeps daily.
    val purged = s.purgeRollups("hour", h12)
    purged shouldBe 3
    s.queryRollups(RollupQuery("hour")) shouldBe Nil
    s.queryRollups(RollupQuery("day")).size shouldBe 3
  }

  it should "keep hourly buckets at or after the cutoff" in withStore { s =>
    seed(s)
    s.recomputeRollups(None, h11.plusSeconds(1800))
    val purged = s.purgeRollups("hour", h11)
    purged shouldBe 2 // acme+globex hour-10 buckets
    val remaining = s.queryRollups(RollupQuery("hour"))
    remaining.size shouldBe 1
    remaining.head.bucketStart shouldBe h11
  }
