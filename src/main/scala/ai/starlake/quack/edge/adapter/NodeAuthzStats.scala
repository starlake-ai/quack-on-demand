package ai.starlake.quack.edge.adapter

import org.apache.arrow.vector.ipc.ArrowReader

import scala.collection.concurrent.TrieMap

/** One reading of a qod-node's authorization counters, each counted since the node started
  * (decision 26), from `system.main.qod_authz_stats()`: plan decisions its verdict cache answered
  * and did not, decide calls and those answered unavailable or refused, refusals the node made
  * alone, statements its parse screen judged, and verdicts its cache evicted for room (LRU, not
  * expiry); with two sizes as of the reading (Task 11 addendum 6), which are no counters: the
  * verdicts its cache holds and the S3 credential sets it keeps.
  */
final case class NodeAuthzStats(
    planCacheHits: Long,
    planCacheMisses: Long,
    decideCalls: Long,
    decideUnavailable: Long,
    decideRefused: Long,
    localRefusals: Long,
    screenCalls: Long,
    verdictCacheSize: Long,
    verdictCacheEvictions: Long,
    credentialSetsSize: Long
):

  /** The counters in [[NodeAuthzStats.Counters]]' order. */
  def values: List[Long] = List(
    planCacheHits,
    planCacheMisses,
    decideCalls,
    decideUnavailable,
    decideRefused,
    localRefusals,
    screenCalls,
    verdictCacheEvictions
  )

  /** The sizes in [[NodeAuthzStats.Sizes]]' order. */
  def sizes: List[Long] = List(verdictCacheSize, credentialSetsSize)

  /** The counters added, the sizes `o`'s (the later reading's). */
  def plus(o: NodeAuthzStats): NodeAuthzStats = NodeAuthzStats(
    planCacheHits + o.planCacheHits,
    planCacheMisses + o.planCacheMisses,
    decideCalls + o.decideCalls,
    decideUnavailable + o.decideUnavailable,
    decideRefused + o.decideRefused,
    localRefusals + o.localRefusals,
    screenCalls + o.screenCalls,
    o.verdictCacheSize,
    verdictCacheEvictions + o.verdictCacheEvictions,
    o.credentialSetsSize
  )

  /** The counters' increase since `o`, the sizes this reading's. */
  def minus(o: NodeAuthzStats): NodeAuthzStats = NodeAuthzStats(
    planCacheHits - o.planCacheHits,
    planCacheMisses - o.planCacheMisses,
    decideCalls - o.decideCalls,
    decideUnavailable - o.decideUnavailable,
    decideRefused - o.decideRefused,
    localRefusals - o.localRefusals,
    screenCalls - o.screenCalls,
    verdictCacheSize,
    verdictCacheEvictions - o.verdictCacheEvictions,
    credentialSetsSize
  )

object NodeAuthzStats:

  /** The columns of qod-node's `qod_authz_stats()` (`AuthzStatsColumns`,
    * native/qod-node/src/authz_stats.cpp), in its order: Task 8's seven counters, then the cache
    * columns appended by Task 11 addendum 6.
    */
  val Columns: List[String] = List(
    "plan_cache_hits",
    "plan_cache_misses",
    "decide_calls",
    "decide_unavailable",
    "decide_refused",
    "local_refusals",
    "screen_calls",
    "verdict_cache_size",
    "verdict_cache_evictions",
    "credential_sets_size"
  )

  /** The columns that only grow while the node runs, each exported as
    * `qod_node_authz_<counter>_total`.
    */
  val Counters: List[String] = Columns.take(7) :+ "verdict_cache_evictions"

  /** The columns that are sizes as of the reading, each exported as the gauge
    * `qod_node_authz_<size>`.
    */
  val Sizes: List[String] = List("verdict_cache_size", "credential_sets_size")

  /** The probe's read. The node answers it to the system principal only. */
  val sql: String = s"SELECT ${Columns.mkString(", ")} FROM system.main.qod_authz_stats()"

  val Zero: NodeAuthzStats = NodeAuthzStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

  /** Decode the one-row result of [[sql]], batches advanced until one carries a row (as
    * [[EngineStats.fromReader]]: the quack wire can send a schema-only first batch). None on an
    * empty result, another shape, a cell that is not a number or a negative value: fail-soft, a
    * read never takes a node down.
    */
  def fromReader(reader: ArrowReader): Option[NodeAuthzStats] =
    def firstRow(): Option[NodeAuthzStats] =
      if !reader.loadNextBatch() then None
      else
        val root = reader.getVectorSchemaRoot
        if root.getRowCount >= 1 && root.getFieldVectors.size == Columns.size then
          val cells = Columns.indices.toList.map { i =>
            root.getFieldVectors.get(i).getObject(0) match
              case n: java.lang.Number if n.longValue() >= 0 => Some(n.longValue())
              case _                                         => None
          }
          if cells.forall(_.isDefined) then fromValues(cells.flatten) else None
        else if root.getRowCount == 0 then firstRow()
        else None
    try firstRow()
    catch case _: Throwable => None

  private def fromValues(vs: List[Long]): Option[NodeAuthzStats] = vs match
    case List(a, b, c, d, e, f, g, size, evictions, sets) =>
      Some(NodeAuthzStats(a, b, c, d, e, f, g, size, evictions, sets))
    case _ => None

  /** What `current` adds to `previous`, the node's last reading: each counter's increase; or, when
    * any counter is lower than in `previous` (the node restarted, its counters from zero again),
    * `current` whole. With no previous reading (a node new to this manager), `current` whole: every
    * count in it happened. The sizes are `current`'s either way.
    */
  def delta(previous: Option[NodeAuthzStats], current: NodeAuthzStats): NodeAuthzStats =
    previous match
      case Some(p) if current.values.zip(p.values).forall((c, q) => c >= q) => current.minus(p)
      case _                                                                => current

/** Each node's authorization counters as the manager exports them
  * (`qod_node_authz_<counter>_total`, MetricsBindings): the sum of the increases between the health
  * probe's readings ([[NodeAuthzStats.delta]]), so a total only grows, across node restarts too;
  * with the latest reading's sizes (`qod_node_authz_<size>`). Written by the probe's read, read on
  * scrape. A node the probe has not read yet has no entry.
  */
final class NodeAuthzStatsTracker:
  private final case class Seen(last: NodeAuthzStats, total: NodeAuthzStats)
  private val state = TrieMap.empty[String, Seen]

  /** Adds what `reading` adds to the node's last reading. Synchronized: a reading replaces the one
    * it was compared with.
    */
  def update(nodeId: String, reading: NodeAuthzStats): Unit = synchronized {
    val seen  = state.get(nodeId)
    val added = NodeAuthzStats.delta(seen.map(_.last), reading)
    state.update(nodeId, Seen(reading, seen.fold(added)(_.total.plus(added))))
  }

  def total(nodeId: String): Option[NodeAuthzStats] = state.get(nodeId).map(_.total)

  /** Forgets every node not in `live`: its series leave the scrape with it. */
  def retain(live: Set[String]): Unit = synchronized {
    state.keySet.toList.filterNot(live).foreach(state.remove)
  }
