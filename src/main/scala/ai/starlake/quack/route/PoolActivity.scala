package ai.starlake.quack.route

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}

/** Per-pool last-activity timestamps feeding the hibernation sweep. Fed from StatementExecuted (the
  * router's sink, so probe traffic is excluded by the edge's existing recordExecution gating) AND
  * from PoolResumed (the supervisor's sink): a pool resumed over REST must get a fresh idle window,
  * not be re-suspended on its stale pre-suspend timestamp.
  *
  * Exactly ONE drainer must run against an instance (the hibernation sweep's flushLocal). Drained
  * entries land in `qodstate_pool_activity` via a GREATEST upsert, so re-flushing an old value is
  * harmless; the two-arg remove leaves a concurrently-bumped entry in place for the next flush, so
  * a newer timestamp is never lost. If no drainer runs the map grows one entry per pool, which is
  * why Main only wires `sink` when the hibernation sweep is enabled.
  */
final class PoolActivity(clock: () => Long = () => System.currentTimeMillis()):

  private val last = new java.util.concurrent.ConcurrentHashMap[PoolKey, java.lang.Long]()

  def touch(key: PoolKey): Unit =
    val now = clock()
    last.merge(key, now, (a, b) => math.max(a, b))
    ()

  /** Removes and returns the current snapshot. An entry bumped between the read and the removal
    * survives to the next flush (two-arg remove fails), carrying the newer timestamp.
    */
  def drain(): Map[PoolKey, Long] =
    val it      = last.entrySet().iterator()
    val drained = Map.newBuilder[PoolKey, Long]
    while it.hasNext do
      val e = it.next()
      if last.remove(e.getKey, e.getValue) then drained += e.getKey -> e.getValue
    drained.result()

  val sink: ManagerEventSink =
    case ManagerEvent.StatementExecuted(tenant, tenantDb, pool, _, _, _, _) =>
      touch(PoolKey(tenant, tenantDb, pool))
    case ManagerEvent.PoolResumed(tenant, tenantDb, pool, _) =>
      touch(PoolKey(tenant, tenantDb, pool))
    case _ => ()
