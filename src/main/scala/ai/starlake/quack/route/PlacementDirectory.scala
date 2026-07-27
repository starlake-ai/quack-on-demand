package ai.starlake.quack.route

import ai.starlake.quack.model.PoolKey

import scala.collection.concurrent.TrieMap

/** One warm(ish) copy of a table. A home whose `warmEpoch` equals the owning assignment's
  * `currentEpoch` is fresh; behind it is stale: it still holds most of the table's unchanged files
  * (DuckLake files are immutable), so it scores 1 instead of 2 until it re-warms by serving a read.
  */
final case class HomeEntry(nodeId: String, warmEpoch: Long)

/** One table's placement: up to [[Assignment.MaxHomes]] concurrent homes, MRU-first. Multi-home is
  * what makes staleness reachable (a write served by one home leaves the others stale) and what
  * keeps load bursts from ping-ponging a table's only home between nodes.
  */
final case class Assignment(
    homes: List[HomeEntry],
    currentEpoch: Long,
    lastRoutedAt: Long
)

object Assignment:
  /** Fixed by design, not a config knob; `overflow-evict-home` firing often is the signal it is too
    * small for a pool's hottest tables (the B-full build trigger).
    */
  val MaxHomes: Int = 3

/** Immutable per-statement input to Router.pick's placement-aware arm: the statement's tables,
  * their currently valid assignments, and the load-cap factor c.
  */
final case class PlacementRequest(
    tables: Set[String],
    assignments: Map[String, Assignment],
    loadCapFactor: Double
)

/** The manager's in-memory placement directory: per pool, a bounded LRU of table -> Assignment.
  * Per-manager and lossy by design (HA replicas and restarts each rebuild from their own traffic;
  * divergence is a benign duplicate warm copy - see the 2026-07-27 design doc). All mutation goes
  * through `record`, called once per routed statement with the router's chosen node.
  */
final class PlacementDirectory(maxTablesPerPool: Int = 4096):

  private final class PoolDir:
    val map = new java.util.LinkedHashMap[String, Assignment](16, 0.75f, true):
      override def removeEldestEntry(e: java.util.Map.Entry[String, Assignment]): Boolean =
        size() > maxTablesPerPool

  private val pools = TrieMap.empty[PoolKey, PoolDir]

  private def liveView(a: Assignment, liveNodeIds: Set[String]): Option[Assignment] =
    val survivors = a.homes.filter(h => liveNodeIds.contains(h.nodeId))
    if survivors.isEmpty then None else Some(a.copy(homes = survivors))

  /** Valid assignments (homes filtered to live nodes, dropped when none survive) for the given
    * tables. Read-only; does not touch LRU order beyond the access-order effect of `get`.
    */
  def viewFor(
      key: PoolKey,
      tables: Set[String],
      liveNodeIds: Set[String]
  ): Map[String, Assignment] =
    pools.get(key) match
      case None      => Map.empty
      case Some(dir) =>
        dir.map.synchronized {
          tables.iterator.flatMap { t =>
            Option(dir.map.get(t)).flatMap(liveView(_, liveNodeIds)).map(t -> _)
          }.toMap
        }

  /** Apply one routed statement and return its outcome label:
    * `claim | sticky-fresh | sticky-stale | overflow-new-home | overflow-evict-home | pinned-sticky
    * | pinned-move`. When `pinned` is true the scorer did not choose this node (a tx pin or a
    * soft preferredNode did), so the placement-quality labels collapse to trigger-neutral
    * `pinned-sticky` / `pinned-move`: the directory still learns where the statement landed, but the
    * overflow signal the B-full build trigger reads stays free of placements the scorer never made.
    */
  def record(
      key: PoolKey,
      chosenNodeId: String,
      refs: RoutingRefs,
      liveNodeIds: Set[String],
      now: Long,
      pinned: Boolean = false
  ): String =
    val dir = pools.getOrElseUpdate(key, new PoolDir)
    dir.map.synchronized {
      val pre = refs.all.iterator.flatMap { t =>
        Option(dir.map.get(t)).flatMap(liveView(_, liveNodeIds)).map(t -> _)
      }.toMap

      def chosenEntry(a: Assignment): Option[HomeEntry] = a.homes.find(_.nodeId == chosenNodeId)

      val outcome =
        if pre.isEmpty then "claim"
        else if pinned then
          if pre.values.forall(a => chosenEntry(a).isDefined) then "pinned-sticky"
          else "pinned-move"
        else if pre.values.forall(a => chosenEntry(a).isDefined) then
          if pre.values.forall(a => chosenEntry(a).exists(_.warmEpoch == a.currentEpoch)) then
            "sticky-fresh"
          else "sticky-stale"
        else if pre.values.exists(a =>
            chosenEntry(a).isEmpty && a.homes.size >= Assignment.MaxHomes
          )
        then "overflow-evict-home"
        else "overflow-new-home"

      refs.all.foreach { t =>
        val isWrite = refs.writes.contains(t)
        val next    = pre.get(t) match
          case None    => Assignment(List(HomeEntry(chosenNodeId, 0L)), 0L, now)
          case Some(a) =>
            val epoch  = if isWrite then a.currentEpoch + 1 else a.currentEpoch
            val others = a.homes.filter(_.nodeId != chosenNodeId)
            // The serving node becomes the fresh MRU home in every arm: reads re-warm or add
            // (it is about to read current data), writes produce the new files outright.
            val homes = (HomeEntry(chosenNodeId, epoch) :: others).take(Assignment.MaxHomes)
            Assignment(homes, epoch, now)
        dir.map.put(t, next): Unit
      }
      outcome
    }

  def clear(key: PoolKey): Unit =
    pools.remove(key): Unit

object PlacementDirectory:

  private val ObjectStoreScheme = "^[a-zA-Z][a-zA-Z0-9+.-]*://".r

  /** True for remote object-store dataPaths (s3://, gs://, az://, r2://, ...), false for local
    * filesystem paths and file:// URIs. Local pools keep pure least-loaded routing: their cold
    * reads ride the OS page cache and cost neither latency nor egress worth optimizing.
    */
  def isObjectStorePath(dataPath: String): Boolean =
    ObjectStoreScheme.findPrefixOf(dataPath).isDefined &&
      !dataPath.toLowerCase.startsWith("file://")
