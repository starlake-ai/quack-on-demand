package ai.starlake.quack.ondemand.federation.iceberg

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** One catalog that is declared for a pool but is not attached on a node, with the DuckDB error
  * that explains why.
  */
final case class CatalogAttachFailure(
    alias: String,
    error: String,
    at: Instant,
    attempts: Int
)

/** Live attach state per `(node incarnation, alias)`, plus the retry backoff.
  *
  * `PoolSupervisor.nodeId` is a deterministic slot id (`respawnSpec` reuses it verbatim), so a node
  * id identifies a SLOT, not an incarnation: a scale-to-zero resume, a `/api/node/restart`, or an
  * autoscale cycle can all respawn a new node under the same id. Every key here therefore carries
  * `startedAt` too, so a respawned node starts with a clean slate instead of inheriting its
  * predecessor's latch -- the feature exists precisely to catch a respawn whose credentials expired
  * while it was gone.
  *
  * Deliberately NOT persisted: it describes live process state, and a manager restart re-probes
  * every node anyway. Under HA each replica verifies the nodes it tracks, so this is replica-local
  * (the same trade-off already accepted for the revoked-jti denylist).
  *
  * Bounded by construction: one entry per (live node incarnation, declared alias), and
  * [[pruneOtherIncarnations]] drops a slot's previous incarnation the first time its successor is
  * verified, so a long-lived manager cycling nodes through the same slot does not leak entries.
  * That covers a REUSED slot only; [[retainOnly]] is what covers a slot that is retired for good,
  * by reconciling the whole registry against the supervisor's live node set once per verify pass.
  */
final class AttachStatusRegistry(
    baseBackoffMs: Long = 30000L,
    maxBackoffMs: Long = 300000L
):

  /** `alias` is the alias AS DECLARED by the caller, kept alongside the normalized map key so
    * [[failuresFor]] can report a legacy mixed-case alias the way the operator wrote it instead of
    * the lowercased lookup key.
    */
  private final case class Entry(
      alias: String,
      error: String,
      at: Instant,
      attempts: Int,
      lastAttemptMs: Long
  )

  // (nodeId, startedAt-epoch-millis): identifies one node INCARNATION, not just its slot.
  private type NodeKey = (String, Long)

  private val failures = new ConcurrentHashMap[(NodeKey, String), Entry]()
  private val attached = ConcurrentHashMap.newKeySet[(NodeKey, String)]()
  private val complete = ConcurrentHashMap.newKeySet[NodeKey]()

  // Every alias key is normalized here, once, so callers can pass the alias however the source
  // declared it (manifest imports do not normalize) and every lookup -- including a caller's own
  // -- agrees on what it is looking up.
  private def normalize(alias: String): String =
    ai.starlake.quack.model.FederatedAlias.fold(alias)

  def recordAttached(nodeId: String, startedAtMs: Long, alias: String): Unit =
    val key = ((nodeId, startedAtMs), normalize(alias))
    failures.remove(key)
    attached.add(key)

  /** Returns true when this failure is NEW or its error text CHANGED, which is the only time the
    * caller should emit a WARN. A permanently broken catalog then costs one log line, not one per
    * health tick.
    */
  def recordFailure(
      nodeId: String,
      startedAtMs: Long,
      alias: String,
      error: String,
      nowMs: Long = System.currentTimeMillis()
  ): Boolean =
    val key = ((nodeId, startedAtMs), normalize(alias))
    attached.remove(key)
    val prev       = Option(failures.get(key))
    val attempts   = prev.map(_.attempts + 1).getOrElse(1)
    val noteworthy = prev.forall(_.error != error)
    failures.put(key, Entry(alias, error, Instant.ofEpochMilli(nowMs), attempts, nowMs))
    complete.remove((nodeId, startedAtMs))
    noteworthy

  /** Exponential backoff per `(node incarnation, alias)`: the health-tick interval, doubling to a
    * ceiling. A never-attempted pair always retries.
    */
  def shouldRetry(
      nodeId: String,
      startedAtMs: Long,
      alias: String,
      nowMs: Long = System.currentTimeMillis()
  ): Boolean =
    Option(failures.get(((nodeId, startedAtMs), normalize(alias)))) match
      case None    => true
      case Some(e) =>
        val shift = math.min(e.attempts - 1, 20)
        val delay = math.min(baseBackoffMs * (1L << shift), maxBackoffMs)
        nowMs - e.lastAttemptMs >= delay

  def latched(nodeId: String, startedAtMs: Long): Boolean = complete.contains((nodeId, startedAtMs))
  def markLatched(nodeId: String, startedAtMs: Long): Unit = complete.add((nodeId, startedAtMs))

  /** Drops every entry that belongs to a DIFFERENT incarnation of this node id. A caller passing
    * its own (fresh) incarnation here is proof the old one is gone -- this is what keeps the
    * registry bounded across respawns, scale-in/out, and pool resumes, without needing an explicit
    * node-stop hook (there is no such hook to wire: `PoolSupervisor`'s node ids are deterministic
    * slot ids reused by `respawnSpec`, so "this slot restarted" is the only signal a caller could
    * give, and this is that same signal observed from the read side, once per incarnation).
    */
  def pruneOtherIncarnations(nodeId: String, startedAtMs: Long): Unit =
    val current = (nodeId, startedAtMs)
    complete.asScala.filter(k => k._1 == nodeId && k != current).toList.foreach(complete.remove)
    failures
      .keySet()
      .asScala
      .filter(k => k._1._1 == nodeId && k._1 != current)
      .toList
      .foreach(failures.remove)
    attached.asScala
      .filter(k => k._1._1 == nodeId && k._1 != current)
      .toList
      .foreach(attached.remove)

  /** Drops every entry whose node incarnation is absent from `live`.
    *
    * [[pruneOtherIncarnations]] only ever reaches another incarnation of a node id that is being
    * verified AGAIN, so it cannot reclaim a SLOT that is retired for good. `PoolSupervisor.nodeId`
    * is deterministic in `(tenant, tenantDb, pool, index)`, so a pool, tenant-db or tenant that is
    * deleted and never recreated under the same names leaves its entries -- each holding an
    * operator-visible error string -- in the manager process for as long as it runs. Slow, but
    * unbounded over the process lifetime.
    *
    * Reconciling against the supervisor's live node set is what bounds this by the CURRENT fleet
    * rather than by the history of the fleet. It is deliberately a reconcile rather than a hook on
    * the three delete paths: the supervisor stays the only source of truth for which nodes exist,
    * and a leak from any cause (a delete, a crash midway through one, an HA replica that never saw
    * it, a pod adopted after a manager restart) heals on the next pass instead of only the causes
    * someone remembered to hook.
    */
  def retainOnly(live: Set[(String, Long)]): Unit =
    complete.asScala.filterNot(live.contains).toList.foreach(complete.remove)
    failures.keySet().asScala.filterNot(k => live.contains(k._1)).toList.foreach(failures.remove)
    attached.asScala.filterNot(k => live.contains(k._1)).toList.foreach(attached.remove)

  def failuresFor(nodeId: String, startedAtMs: Long): List[CatalogAttachFailure] =
    val key = (nodeId, startedAtMs)
    failures.asScala.toList
      .collect {
        case ((k, _), e) if k == key =>
          CatalogAttachFailure(e.alias, e.error, e.at, e.attempts)
      }
      // Sorted on the NORMALIZED alias, not the declared one: `Entry.alias` keeps whatever case
      // the source declared, so sorting on it would put a legacy "Sales_Lake" before "analytics"
      // in ASCII and after it once the row is normalized, i.e. the operator-visible order would
      // depend on the case of a name that is compared case-insensitively everywhere else.
      .sortBy(_.alias.toLowerCase(java.util.Locale.ROOT))

  /** Operator-facing summary of one alias across a pool's nodes: `attached`, `unknown`, or
    * `failed on N of M nodes`. Matches by node id alone -- once a node's successor incarnation has
    * been verified at least once, [[pruneOtherIncarnations]] guarantees at most one incarnation's
    * entries survive per node id, so this does not need `startedAt` from the caller.
    */
  def aliasSummary(alias: String, nodeIds: Set[String]): Option[String] =
    if nodeIds.isEmpty then Some("unknown")
    else
      val a = normalize(alias)
      // One pass over each index, projected down to the node ids that carry this alias, rather
      // than rescanning the whole index once per node id: `listSources` calls this per source, so
      // the naive form was O(sources x nodes x entries) on a single GET.
      val failingNodes =
        failures.keySet().asScala.collect { case ((n, _), al) if al == a => n }.toSet
      val okNodes = attached.asScala.collect { case ((n, _), al) if al == a => n }.toSet
      val failing = nodeIds.count(failingNodes.contains)
      val ok      = nodeIds.count(okNodes.contains)
      if failing > 0 then Some(s"failed on $failing of ${nodeIds.size} nodes")
      else if ok == nodeIds.size then Some("attached")
      else Some("unknown")
