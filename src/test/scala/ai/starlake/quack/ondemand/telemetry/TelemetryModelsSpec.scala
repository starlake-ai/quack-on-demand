package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Tests for the statement-history and rollup models introduced in Task 1.
  *
  * Covers: model defaults, NoopTelemetryStore no-ops, and RecordingTelemetryStore filter matrix for
  * searchStatements and rollup ops.
  */
class TelemetryModelsSpec extends AnyFlatSpec with Matchers:

  private def ev(
      ts: Instant = Instant.parse("2026-07-01T10:00:00Z"),
      username: String = "alice",
      tenant: String = "t-acme",
      pool: String = "bi",
      nodeId: String = "n-1",
      sql: String = "SELECT 1",
      durationMs: Long = 100L,
      prepareMs: Option[Long] = None,
      status: String = "ok",
      error: Option[String] = None
  ): StatementEvent =
    StatementEvent(ts, username, tenant, pool, nodeId, sql, durationMs, prepareMs, status, error)

  // ---- StatementQuery defaults -------------------------------------------- //

  "StatementQuery" should "have sensible defaults" in {
    val q = StatementQuery()
    q.tenants shouldBe None
    q.pool shouldBe None
    q.user shouldBe None
    q.status shouldBe None
    q.q shouldBe None
    q.from shouldBe None
    q.to shouldBe None
    q.limit shouldBe 50
    q.beforeId shouldBe None
  }

  // ---- NoopTelemetryStore no-ops ------------------------------------------ //

  "NoopTelemetryStore" should "return empty/None/0 for all statement and rollup ops" in {
    val e = ev()
    NoopTelemetryStore.appendStatements(List(e))
    NoopTelemetryStore.searchStatements(StatementQuery()) shouldBe Nil
    NoopTelemetryStore.rollupWatermark() shouldBe None
    NoopTelemetryStore.recomputeRollups(None, Instant.now())
    NoopTelemetryStore.advanceRollupWatermark(Instant.now())
    NoopTelemetryStore.queryRollups(RollupQuery("hour")) shouldBe Nil
    NoopTelemetryStore.purgeStatements(Instant.now()) shouldBe 0
    NoopTelemetryStore.purgeRollups("hour", Instant.now()) shouldBe 0
  }

  // ---- RecordingTelemetryStore: searchStatements filter matrix ------------- //

  "RecordingTelemetryStore.searchStatements" should "return all rows newest-first when no filters" in {
    val store = new RecordingTelemetryStore
    val t1    = Instant.parse("2026-07-01T10:00:00Z")
    val t2    = Instant.parse("2026-07-01T11:00:00Z")
    store.appendStatements(List(ev(ts = t1), ev(ts = t2, username = "bob")))
    val rows = store.searchStatements(StatementQuery())
    rows.size shouldBe 2
    rows.head.event.username shouldBe "bob" // highest id = newest
    rows(1).event.username shouldBe "alice"
  }

  it should "restrict to tenants in the provided set" in {
    val store = new RecordingTelemetryStore
    store.appendStatements(List(ev(tenant = "t-acme"), ev(tenant = "t-globex")))
    val rows = store.searchStatements(StatementQuery(tenants = Some(Set("t-acme"))))
    rows.map(_.event.tenant) shouldBe List("t-acme")
  }

  it should "return empty when tenants is Some(empty set)" in {
    val store = new RecordingTelemetryStore
    store.appendStatements(List(ev()))
    store.searchStatements(StatementQuery(tenants = Some(Set.empty))) shouldBe Nil
  }

  it should "filter by pool exact match" in {
    val store = new RecordingTelemetryStore
    store.appendStatements(List(ev(pool = "bi"), ev(pool = "etl")))
    store.searchStatements(StatementQuery(pool = Some("bi"))).map(_.event.pool) shouldBe List("bi")
  }

  it should "filter by user exact match" in {
    val store = new RecordingTelemetryStore
    store.appendStatements(List(ev(username = "alice"), ev(username = "bob")))
    store
      .searchStatements(StatementQuery(user = Some("alice")))
      .map(_.event.username) shouldBe List(
      "alice"
    )
  }

  it should "filter by status exact match" in {
    val store = new RecordingTelemetryStore
    store.appendStatements(List(ev(status = "ok"), ev(status = "denied")))
    store.searchStatements(StatementQuery(status = Some("ok"))).map(_.event.status) shouldBe List(
      "ok"
    )
  }

  it should "filter by sql substring (q)" in {
    val store = new RecordingTelemetryStore
    store.appendStatements(
      List(
        ev(sql = "SELECT count(*) FROM t"),
        ev(sql = "INSERT INTO t VALUES (1)")
      )
    )
    store
      .searchStatements(StatementQuery(q = Some("SELECT")))
      .map(_.event.sql) shouldBe List("SELECT count(*) FROM t")
  }

  it should "filter by from/to timestamp range (from inclusive, to exclusive)" in {
    val store = new RecordingTelemetryStore
    val t1    = Instant.parse("2026-07-01T08:00:00Z")
    val t2    = Instant.parse("2026-07-01T10:00:00Z")
    val t3    = Instant.parse("2026-07-01T12:00:00Z")
    store.appendStatements(List(ev(ts = t1), ev(ts = t2), ev(ts = t3)))
    val rows = store.searchStatements(
      StatementQuery(
        from = Some(Instant.parse("2026-07-01T09:00:00Z")),
        to = Some(Instant.parse("2026-07-01T11:00:00Z"))
      )
    )
    rows.map(_.event.ts) shouldBe List(t2)
  }

  it should "apply keyset cursor (beforeId)" in {
    val store = new RecordingTelemetryStore
    store.appendStatements(List(ev(), ev(), ev()))
    val all = store.searchStatements(StatementQuery())
    all.size shouldBe 3
    val secondPage = store.searchStatements(StatementQuery(beforeId = Some(all.head.id)))
    secondPage.size shouldBe 2
    secondPage.forall(_.id < all.head.id) shouldBe true
  }

  it should "respect the limit parameter" in {
    val store = new RecordingTelemetryStore
    (1 to 10).foreach(_ => store.appendStatements(List(ev())))
    store.searchStatements(StatementQuery(limit = 3)).size shouldBe 3
  }

  it should "clamp limit to [1, 500]" in {
    val store = new RecordingTelemetryStore
    (1 to 5).foreach(_ => store.appendStatements(List(ev())))
    store.searchStatements(StatementQuery(limit = 0)).size shouldBe 1
    store.searchStatements(StatementQuery(limit = 1000)).size shouldBe 5
  }

  // ---- RecordingTelemetryStore: rollup watermark -------------------------- //

  "RecordingTelemetryStore.rollupWatermark" should "return None initially" in {
    new RecordingTelemetryStore().rollupWatermark() shouldBe None
  }

  "RecordingTelemetryStore.advanceRollupWatermark" should "update the watermark" in {
    val store = new RecordingTelemetryStore
    val t     = Instant.parse("2026-07-01T12:00:00Z")
    store.advanceRollupWatermark(t)
    store.rollupWatermark() shouldBe Some(t)
  }

  // ---- RecordingTelemetryStore: recomputeRollups + queryRollups ----------- //

  "RecordingTelemetryStore.recomputeRollups" should "produce hourly buckets with percentiles" in {
    val store = new RecordingTelemetryStore
    val t1    = Instant.parse("2026-07-01T10:15:00Z")
    val t2    = Instant.parse("2026-07-01T10:45:00Z")
    store.appendStatements(
      List(
        ev(ts = t1, durationMs = 100L, status = "ok"),
        ev(ts = t2, durationMs = 200L, status = "denied")
      )
    )
    store.recomputeRollups(None, Instant.parse("2026-07-01T11:00:00Z"))
    val buckets = store.queryRollups(RollupQuery("hour"))
    buckets.size shouldBe 1
    val b = buckets.head
    b.stmtCount shouldBe 2L
    b.deniedCount shouldBe 1L
    b.errorCount shouldBe 0L
    b.p50Ms shouldBe defined
    b.p95Ms shouldBe defined
    b.p99Ms shouldBe defined
    b.username shouldBe ""
  }

  it should "produce daily buckets with per-user breakdown and no percentiles" in {
    val store = new RecordingTelemetryStore
    val t1    = Instant.parse("2026-07-01T10:00:00Z")
    val t2    = Instant.parse("2026-07-01T14:00:00Z")
    store.appendStatements(
      List(
        ev(ts = t1, username = "alice", durationMs = 100L),
        ev(ts = t2, username = "bob", durationMs = 200L)
      )
    )
    store.recomputeRollups(None, Instant.parse("2026-07-01T23:59:59Z"))
    val buckets = store.queryRollups(RollupQuery("day"))
    buckets.size shouldBe 2
    buckets.forall(_.p50Ms.isEmpty) shouldBe true
    buckets.map(_.username).toSet shouldBe Set("alice", "bob")
  }

  it should "return buckets in ascending bucketStart order" in {
    val store = new RecordingTelemetryStore
    store.appendStatements(
      List(
        ev(ts = Instant.parse("2026-07-01T12:00:00Z")),
        ev(ts = Instant.parse("2026-07-01T10:00:00Z")),
        ev(ts = Instant.parse("2026-07-01T11:00:00Z"))
      )
    )
    store.recomputeRollups(None, Instant.parse("2026-07-01T13:00:00Z"))
    val buckets = store.queryRollups(RollupQuery("hour"))
    buckets.size shouldBe 3
    buckets.map(_.bucketStart) shouldBe buckets
      .map(_.bucketStart)
      .sorted(
        Ordering.fromLessThan[Instant](_.isBefore(_))
      )
  }

  it should "count errorCount only for statuses not in (ok, denied)" in {
    val store = new RecordingTelemetryStore
    store.appendStatements(
      List(
        ev(status = "ok"),
        ev(status = "denied"),
        ev(status = "error"),
        ev(status = "timeout")
      )
    )
    store.recomputeRollups(None, Instant.parse("2026-07-02T00:00:00Z"))
    val buckets = store.queryRollups(RollupQuery("hour"))
    val total   = buckets.map(_.stmtCount).sum
    val denied  = buckets.map(_.deniedCount).sum
    val errors  = buckets.map(_.errorCount).sum
    total shouldBe 4L
    denied shouldBe 1L
    errors shouldBe 2L
  }

  it should "exclude statements at or before fromExclusive and produce buckets only for the later hour" in {
    val store = new RecordingTelemetryStore
    val t1    = Instant.parse("2026-07-01T10:15:00Z") // inside hour 10
    val t2    = Instant.parse("2026-07-01T11:15:00Z") // inside hour 11
    store.appendStatements(List(ev(ts = t1), ev(ts = t2)))
    // fromExclusive = boundary between hour 10 and hour 11
    val endOfHour10 = Instant.parse("2026-07-01T11:00:00Z")
    val endOfHour11 = Instant.parse("2026-07-01T12:00:00Z")
    store.recomputeRollups(fromExclusive = Some(endOfHour10), toInclusive = endOfHour11)
    val buckets = store.queryRollups(RollupQuery("hour"))
    // Only hour 11 falls in (endOfHour10, endOfHour11]; hour 10 is excluded by fromExclusive
    buckets should have size 1
    buckets.head.bucketStart shouldBe Instant.parse("2026-07-01T11:00:00Z")
    buckets.head.stmtCount shouldBe 1L
  }

  // ---- RecordingTelemetryStore: purgeStatements --------------------------- //

  "RecordingTelemetryStore.purgeStatements" should "remove statements with ts < olderThan" in {
    val store  = new RecordingTelemetryStore
    val old    = Instant.parse("2026-06-01T00:00:00Z")
    val recent = Instant.parse("2026-07-01T00:00:00Z")
    store.appendStatements(List(ev(ts = old), ev(ts = recent)))
    val cutoff = Instant.parse("2026-06-15T00:00:00Z")
    store.purgeStatements(cutoff) shouldBe 1
    val remaining = store.searchStatements(StatementQuery())
    remaining.size shouldBe 1
    remaining.head.event.ts shouldBe recent
  }

  // ---- RecordingTelemetryStore: purgeRollups ------------------------------ //

  "RecordingTelemetryStore.purgeRollups" should "remove rollup buckets with bucketStart < olderThan" in {
    val store  = new RecordingTelemetryStore
    val old    = Instant.parse("2026-06-01T10:00:00Z")
    val recent = Instant.parse("2026-07-01T10:00:00Z")
    store.appendStatements(List(ev(ts = old), ev(ts = recent)))
    store.recomputeRollups(None, Instant.parse("2026-07-02T00:00:00Z"))
    val cutoff = Instant.parse("2026-06-15T00:00:00Z")
    val purged = store.purgeRollups("hour", cutoff)
    purged should be >= 1
    val remaining = store.queryRollups(RollupQuery("hour"))
    remaining.forall(!_.bucketStart.isBefore(cutoff)) shouldBe true
  }

  it should "only purge buckets matching the given granularity" in {
    val store = new RecordingTelemetryStore
    val old   = Instant.parse("2026-06-01T10:00:00Z")
    store.appendStatements(List(ev(ts = old)))
    store.recomputeRollups(None, Instant.parse("2026-06-02T00:00:00Z"))
    val cutoff     = Instant.parse("2026-07-01T00:00:00Z")
    val hourPurged = store.purgeRollups("hour", cutoff)
    val dayPurged  = store.purgeRollups("day", cutoff)
    hourPurged should be >= 1
    dayPurged should be >= 1
    store.queryRollups(RollupQuery("hour")) shouldBe Nil
    store.queryRollups(RollupQuery("day")) shouldBe Nil
  }
