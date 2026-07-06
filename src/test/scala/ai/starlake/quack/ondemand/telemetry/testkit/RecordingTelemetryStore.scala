package ai.starlake.quack.ondemand.telemetry.testkit

import ai.starlake.quack.ondemand.telemetry.{AuditEvent, AuditQuery, AuditRow, TelemetryStore}

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** In-memory [[TelemetryStore]] for use in unit tests. Collects every appended event into a flat
  * mutable list. Set `failNext = true` to make the next `appendAudit` call throw a
  * RuntimeException (simulates a storage failure) and then auto-reset to false.
  *
  * `listAudit` honours all [[AuditQuery]] filters and returns rows newest-first, mirroring the
  * contract of [[ai.starlake.quack.ondemand.telemetry.PostgresTelemetryStore]].
  */
class RecordingTelemetryStore extends TelemetryStore:
  val enabled                                                  = true
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
