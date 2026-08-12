package ai.starlake.quack.route

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}

/** Per-pool 1-minute load buckets feeding the autoscale utilization estimate (Little's law:
  * totalDurationMs over the window / window length). Fed from StatementExecuted via `sink`, so
  * probe traffic is excluded by the edge's existing recordExecution gating. Thread-safe; the sweep
  * drains closed buckets and flushes them upsert-add to qodstate_pool_load.
  *
  * Exactly ONE drainer must run against an instance (the autoscale sweep). Two concurrent drainers
  * would each get a disjoint half of the closed buckets, so neither pass sees the pool's real load.
  * Nothing else bounds the map: if no drainer runs, it grows one entry per (pool, minute) forever.
  */
final class PoolLoadStats(clock: () => Long = () => System.currentTimeMillis()):
  import PoolLoadStats.*

  private val buckets =
    new java.util.concurrent.ConcurrentHashMap[(PoolKey, Long), Sample]()

  def record(key: PoolKey, durationMs: Long): Unit =
    val b = bucketStart(clock())
    buckets.merge(
      (key, b),
      Sample(1L, durationMs),
      (a, x) => Sample(a.statements + x.statements, a.totalDurationMs + x.totalDurationMs)
    )
    ()

  /** Removes and returns every bucket strictly older than the current minute.
    *
    * The two-arg remove is load-bearing: a `record()` that merges into the same bucket between the
    * read and the removal changes the value, the remove then fails, and the entry is left in place
    * (with the late statement's contribution) for the next sweep instead of being dropped. Only
    * buckets actually removed are returned, so the flush never double-counts; the flush being
    * upsert-add makes carrying a bucket over to a later sweep safe.
    */
  def drainClosed(): Map[(PoolKey, Long), Sample] =
    val open    = bucketStart(clock())
    val it      = buckets.entrySet().iterator()
    val drained = Map.newBuilder[(PoolKey, Long), Sample]
    while it.hasNext do
      val e = it.next()
      if e.getKey._2 < open && buckets.remove(e.getKey, e.getValue) then
        drained += e.getKey -> e.getValue
    drained.result()

  val sink: ManagerEventSink =
    case ManagerEvent.StatementExecuted(tenant, tenantDb, pool, _, _, durationMs, _) =>
      record(PoolKey(tenant, tenantDb, pool), durationMs)
    case _ => ()

object PoolLoadStats:
  final case class Sample(statements: Long, totalDurationMs: Long)
  def bucketStart(ms: Long): Long = ms - (ms % 60_000L)
