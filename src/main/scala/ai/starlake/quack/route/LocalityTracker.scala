package ai.starlake.quack.route

import ai.starlake.quack.model.PoolKey

import scala.collection.concurrent.TrieMap

/** Aggregate of one routed statement against the tracker's memory: how many of its tables were
  * never seen (new) vs seen (repeat), and of the seen ones, how many stayed on their last node vs
  * switched. `switches` is the phase-0 "scatter" signal: under least-loaded routing a high switch
  * rate means locality is being destroyed; after the placement change it should approach zero.
  */
final case class LocalityObservation(newTables: Int, repeatTables: Int, stays: Int, switches: Int)

/** Pure observation of table-to-node locality, per pool: a bounded LRU of `table -> last node`.
  * Feeds RoutingInstruments in both milestones; never influences routing decisions.
  */
final class LocalityTracker(maxTablesPerPool: Int = 4096):

  private final class PoolMemory:
    val lastNode = new java.util.LinkedHashMap[String, String](16, 0.75f, true):
      override def removeEldestEntry(e: java.util.Map.Entry[String, String]): Boolean =
        size() > maxTablesPerPool

  private val pools = TrieMap.empty[PoolKey, PoolMemory]

  def observe(key: PoolKey, tables: Set[String], nodeId: String): LocalityObservation =
    val mem = pools.getOrElseUpdate(key, new PoolMemory)
    mem.lastNode.synchronized {
      val obs = tables.foldLeft(LocalityObservation(0, 0, 0, 0)) { (acc, t) =>
        val prev = mem.lastNode.get(t)
        mem.lastNode.put(t, nodeId)
        if prev == null then acc.copy(newTables = acc.newTables + 1)
        else if prev == nodeId then
          acc.copy(repeatTables = acc.repeatTables + 1, stays = acc.stays + 1)
        else acc.copy(repeatTables = acc.repeatTables + 1, switches = acc.switches + 1)
      }
      obs
    }

  def clear(key: PoolKey): Unit =
    pools.remove(key): Unit
