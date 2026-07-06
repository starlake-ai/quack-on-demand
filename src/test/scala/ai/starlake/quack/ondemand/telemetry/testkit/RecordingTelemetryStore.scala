package ai.starlake.quack.ondemand.telemetry.testkit

import ai.starlake.quack.ondemand.telemetry.{
  AuditEvent,
  AuditQuery,
  AuditRow,
  RollupBucket,
  RollupMath,
  RollupQuery,
  StatementEvent,
  StatementQuery,
  StatementRow,
  TelemetryStore,
  UsageMath,
  UsageQuery,
  UsageResult
}

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/** In-memory [[TelemetryStore]] for use in unit tests. Collects every appended event into a flat
  * mutable list. Set `failNext = true` to make the next `appendAudit` call throw a RuntimeException
  * (simulates a storage failure) and then auto-reset to false.
  *
  * `listAudit` honours all [[AuditQuery]] filters and returns rows newest-first, mirroring the
  * contract of [[ai.starlake.quack.ondemand.telemetry.PostgresTelemetryStore]].
  *
  * Statement and rollup ops are implemented faithfully in memory, including the [[StatementQuery]]
  * filter matrix and `percentile_cont`-style percentile computation.
  */
class RecordingTelemetryStore extends TelemetryStore:
  val enabled                                                 = true
  val events: scala.collection.mutable.ListBuffer[AuditEvent] =
    scala.collection.mutable.ListBuffer.empty

  private val idGen =
    new AtomicLong(0)
  private val rows: scala.collection.mutable.ListBuffer[(Long, AuditEvent)] =
    scala.collection.mutable.ListBuffer.empty

  var failNext: Boolean = false

  def appendAudit(es: List[AuditEvent]): Unit =
    if failNext then
      failNext = false
      throw new RuntimeException("store down")
    es.foreach { e =>
      val id = idGen.incrementAndGet()
      rows += ((id, e))
      events += e
    }

  /** Newest-first (descending id), at most `q.limit` rows, respecting all filters. */
  def listAudit(q: AuditQuery): List[AuditRow] =
    var filtered = rows.toList

    // Keyset cursor: exclude rows with id >= beforeId (PostgresTelemetryStore uses id < ?)
    q.beforeId.foreach { bid => filtered = filtered.filter(_._1 < bid) }

    // Tenant / null-tenant filter - mirrors the SQL logic in PostgresTelemetryStore
    q.tenants match
      case Some(ts) if ts.isEmpty =>
        filtered =
          if q.includeNullTenant then filtered.filter(_._2.tenant.isEmpty)
          else Nil
      case Some(ts) =>
        filtered = filtered.filter { case (_, e) =>
          e.tenant.exists(ts.contains) || (q.includeNullTenant && e.tenant.isEmpty)
        }
      case None =>
        if !q.includeNullTenant then filtered = filtered.filter(_._2.tenant.isDefined)

    // Scalar filters
    q.family.foreach { f => filtered = filtered.filter(_._2.family == f) }
    q.actor.foreach { a => filtered = filtered.filter(_._2.actor == a) }
    q.action.foreach { a => filtered = filtered.filter(_._2.action == a) }

    // Free-text search: action OR target contains the substring
    q.q.foreach { search =>
      filtered = filtered.filter { case (_, e) =>
        e.action.contains(search) || e.target.exists(_.contains(search))
      }
    }

    // Timestamp range
    q.from.foreach { f => filtered = filtered.filter(_._2.ts.compareTo(f) >= 0) }
    q.to.foreach { t => filtered = filtered.filter(_._2.ts.compareTo(t) < 0) }

    // Newest-first, capped at limit
    filtered.sortBy(-_._1).take(q.limit).map { case (id, e) => AuditRow(id, e) }

  def purgeAudit(olderThan: Instant): Int =
    val before = rows.size
    rows.filterInPlace(_._2.ts.compareTo(olderThan) >= 0)
    events.filterInPlace(_.ts.compareTo(olderThan) >= 0)
    before - rows.size

  // --- Statement history -------------------------------------------------- //

  private val stmtIdGen = new AtomicLong(0)
  private val stmtRows: scala.collection.mutable.ListBuffer[(Long, StatementEvent)] =
    scala.collection.mutable.ListBuffer.empty

  override def appendStatements(events: List[StatementEvent]): Unit =
    events.foreach { e =>
      val id = stmtIdGen.incrementAndGet()
      stmtRows += ((id, e))
    }

  override def searchStatements(q: StatementQuery): List[StatementRow] =
    var filtered = stmtRows.toList

    // Keyset cursor: exclude rows with id >= beforeId
    q.beforeId.foreach { bid => filtered = filtered.filter(_._1 < bid) }

    // Tenant filter
    q.tenants match
      case Some(ts) if ts.isEmpty => filtered = Nil
      case Some(ts)               => filtered = filtered.filter(r => ts.contains(r._2.tenant))
      case None                   => ()

    // Scalar filters
    q.pool.foreach { p => filtered = filtered.filter(_._2.pool == p) }
    q.user.foreach { u => filtered = filtered.filter(_._2.username == u) }
    q.status.foreach { s => filtered = filtered.filter(_._2.status == s) }

    // Substring match on sql
    q.q.foreach { search => filtered = filtered.filter(_._2.sql.contains(search)) }

    // Timestamp range (from inclusive, to exclusive)
    q.from.foreach { f => filtered = filtered.filter(_._2.ts.compareTo(f) >= 0) }
    q.to.foreach { t => filtered = filtered.filter(_._2.ts.compareTo(t) < 0) }

    // Newest-first, limit clamped to [1, 500]
    val lim = math.max(1, math.min(q.limit, 500))
    filtered.sortBy(-_._1).take(lim).map { case (id, e) => StatementRow(id, e) }

  override def purgeStatements(olderThan: Instant): Int =
    val before = stmtRows.size
    stmtRows.filterInPlace(_._2.ts.compareTo(olderThan) >= 0)
    before - stmtRows.size

  // --- Rollups ------------------------------------------------------------ //

  @volatile private var watermark: Option[Instant]                             = None
  private val rollupBuckets: scala.collection.mutable.ListBuffer[RollupBucket] =
    scala.collection.mutable.ListBuffer.empty

  override def rollupWatermark(): Option[Instant] = watermark

  override def advanceRollupWatermark(to: Instant): Unit = watermark = Some(to)

  override def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit =
    val oldestTs = stmtRows.toList.map(_._2.ts).minOption
    RollupMath.hourWindow(fromExclusive, toInclusive, oldestTs) match
      case None           => ()
      case Some((lo, hi)) =>
        val (dlo, dhi) = RollupMath.dayWindow((lo, hi))

        // -- Hourly rollups: [lo, hi) on raw events, username = "" ------------ //
        val hourInRange = stmtRows.toList.map(_._2).filter { e =>
          !e.ts.isBefore(lo) && e.ts.isBefore(hi)
        }
        rollupBuckets.filterInPlace { b =>
          b.granularity != "hour" || b.bucketStart.isBefore(lo) || !b.bucketStart.isBefore(hi)
        }
        val hourlyGroups = hourInRange.groupBy { e =>
          (RollupMath.hourFloor(e.ts), e.tenant, e.pool)
        }
        hourlyGroups.foreach { case ((bucketStart, tenant, pool), evts) =>
          val durations = evts.map(_.durationMs.toDouble).sorted.toIndexedSeq
          rollupBuckets += RollupBucket(
            bucketStart = bucketStart,
            granularity = "hour",
            tenant = tenant,
            pool = pool,
            username = "",
            stmtCount = evts.size.toLong,
            errorCount = evts.count(e => e.status != "ok" && e.status != "denied").toLong,
            deniedCount = evts.count(_.status == "denied").toLong,
            engineMsSum = evts.map(_.durationMs).sum,
            p50Ms = if durations.isEmpty then None else Some(percentileCont(durations, 0.50)),
            p95Ms = if durations.isEmpty then None else Some(percentileCont(durations, 0.95)),
            p99Ms = if durations.isEmpty then None else Some(percentileCont(durations, 0.99))
          )
        }

        // -- Daily rollups: [dlo, dhi) on raw events, per-user username ------- //
        val dayInRange = stmtRows.toList.map(_._2).filter { e =>
          !e.ts.isBefore(dlo) && e.ts.isBefore(dhi)
        }
        rollupBuckets.filterInPlace { b =>
          b.granularity != "day" || b.bucketStart.isBefore(dlo) || !b.bucketStart.isBefore(dhi)
        }
        val dailyGroups = dayInRange.groupBy { e =>
          (RollupMath.dayFloor(e.ts), e.tenant, e.pool, e.username)
        }
        dailyGroups.foreach { case ((bucketStart, tenant, pool, username), evts) =>
          rollupBuckets += RollupBucket(
            bucketStart = bucketStart,
            granularity = "day",
            tenant = tenant,
            pool = pool,
            username = username,
            stmtCount = evts.size.toLong,
            errorCount = evts.count(e => e.status != "ok" && e.status != "denied").toLong,
            deniedCount = evts.count(_.status == "denied").toLong,
            engineMsSum = evts.map(_.durationMs).sum,
            p50Ms = None,
            p95Ms = None,
            p99Ms = None
          )
        }

  override def queryRollups(q: RollupQuery): List[RollupBucket] =
    var filtered = rollupBuckets.toList.filter(_.granularity == q.granularity)

    q.tenants match
      case Some(ts) if ts.isEmpty => filtered = Nil
      case Some(ts)               => filtered = filtered.filter(b => ts.contains(b.tenant))
      case None                   => ()

    q.pool.foreach { p => filtered = filtered.filter(_.pool == p) }
    q.from.foreach { f => filtered = filtered.filter(!_.bucketStart.isBefore(f)) }
    q.to.foreach { t => filtered = filtered.filter(_.bucketStart.isBefore(t)) }

    filtered.sortWith((a, b) => a.bucketStart.isBefore(b.bucketStart))

  override def purgeRollups(granularity: String, olderThan: Instant): Int =
    val before = rollupBuckets.size
    rollupBuckets.filterInPlace { b =>
      b.granularity != granularity || !b.bucketStart.isBefore(olderThan)
    }
    before - rollupBuckets.size

  // --- Usage ---------------------------------------------------------------- //

  override def queryUsage(q: UsageQuery): UsageResult =
    require(
      q.groupBy == "tenant" || q.groupBy == "pool" || q.groupBy == "user",
      s"unknown groupBy: ${q.groupBy}"
    )
    val tenantScoped = q.tenants match
      case Some(ts) if ts.isEmpty => Nil
      case Some(ts)               => rollupBuckets.toList.filter(b => ts.contains(b.tenant))
      case None                   => rollupBuckets.toList
    val daily   = tenantScoped.filter(_.granularity == "day")
    val inRange = daily
      .filter(b => q.pool.forall(_ == b.pool))
      .filter(b => !b.bucketStart.isBefore(q.from) && b.bucketStart.isBefore(q.to))
    UsageResult(
      groups = UsageMath.fold(q.groupBy, rowsOf(inRange, q.groupBy)),
      dataStart = daily.map(_.bucketStart).minOption
    )

  /** Aggregate to one DayRow per (group key, day), mirroring the SQL GROUP BY. */
  private def rowsOf(buckets: List[RollupBucket], groupBy: String): List[UsageMath.DayRow] =
    buckets
      .groupBy { b =>
        val poolKey = if groupBy == "pool" then b.pool else ""
        val userKey = if groupBy == "user" then b.username else ""
        (b.tenant, poolKey, userKey, b.bucketStart)
      }
      .toList
      .map { case ((tenant, poolKey, userKey, day), bs) =>
        UsageMath.DayRow(
          tenant = tenant,
          pool = poolKey,
          username = userKey,
          day = day,
          statements = bs.map(_.stmtCount).sum,
          errors = bs.map(_.errorCount).sum,
          denied = bs.map(_.deniedCount).sum,
          engineMs = bs.map(_.engineMsSum).sum
        )
      }

  // --- Percentile helper -------------------------------------------------- //

  /** percentile_cont-style continuous interpolation on a pre-sorted IndexedSeq. */
  private def percentileCont(sortedValues: IndexedSeq[Double], p: Double): Double =
    if sortedValues.isEmpty then 0.0
    else if sortedValues.size == 1 then sortedValues(0)
    else
      val h  = p * (sortedValues.size - 1)
      val lo = h.toInt
      val hi = math.min(lo + 1, sortedValues.size - 1)
      sortedValues(lo) + (h - lo) * (sortedValues(hi) - sortedValues(lo))
