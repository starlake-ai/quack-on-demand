package ai.starlake.quack.edge.adapter

import ai.starlake.quack.route.NodeLoad

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/** Per-node load (in-flight, EWMA latency, served count, health, draining and quarantine flags) and
  * latency ring, per replica.
  *
  * Every write stamps the node's entry with the next value of a per-tracker write sequence, so
  * [[retain]] can tell a node written since a [[mark]] from one that was not. Writes and the trim
  * of one node are atomic with each other (`ConcurrentHashMap.compute` / `computeIfPresent` on the
  * same key), so a trim never drops a write that raced it. Reads create no entry.
  */
final class NodeLoadTracker(alpha: Double = 0.3, latencyWindow: Int = 256):

  import NodeLoadTracker.Entry

  private val writes      = new AtomicLong(0L)
  private val state       = new ConcurrentHashMap[String, Entry]()
  private val percentiles = new ConcurrentHashMap[String, LatencyRing]()

  private def update(nodeId: String)(f: NodeLoad => NodeLoad): Unit =
    state.compute(
      nodeId,
      (_, cur) =>
        Entry(f(if cur == null then NodeLoad.empty else cur.load), writes.incrementAndGet())
    )
    ()

  def onStart(nodeId: String): Unit =
    update(nodeId)(l => l.copy(inFlight = l.inFlight + 1))

  /** A request that ended without an outcome (cancelled or raised): release its in-flight slot
    * without a latency sample and without counting it as served.
    */
  def onAbort(nodeId: String): Unit =
    update(nodeId)(l => l.copy(inFlight = math.max(0, l.inFlight - 1)))

  def onFinish(nodeId: String, latencyMs: Long): Unit =
    // The load first: a trim that runs between the two writes then finds the node written since
    // its mark and keeps the ring this call records next.
    update(nodeId) { l =>
      val nextEwma =
        if l.ewmaMs == 0.0 then latencyMs.toDouble
        else (1 - alpha) * l.ewmaMs + alpha * latencyMs
      l.copy(
        inFlight = math.max(0, l.inFlight - 1),
        ewmaMs = nextEwma,
        totalServed = l.totalServed + 1
      )
    }
    percentiles.computeIfAbsent(nodeId, _ => new LatencyRing(latencyWindow)).record(latencyMs)

  /** Snapshot of (p50, p95, p99) for the node. (0,0,0) when no samples have been recorded yet.
    */
  def latencyPercentiles(nodeId: String): (Double, Double, Double) =
    Option(percentiles.get(nodeId)).map(_.percentiles()).getOrElse((0.0, 0.0, 0.0))

  def setHealthy(nodeId: String, healthy: Boolean): Unit =
    update(nodeId)(_.copy(healthy = healthy))

  def setDraining(nodeId: String, draining: Boolean): Unit =
    update(nodeId)(_.copy(draining = draining))

  def setQuarantined(nodeId: String, quarantined: Boolean): Unit =
    update(nodeId)(_.copy(quarantined = quarantined))

  /** Forget the node: its load and its latency ring. */
  def remove(nodeId: String): Unit =
    state.remove(nodeId)
    percentiles.remove(nodeId)
    ()

  /** The write sequence now: every later write stamps its node above it. Taken by `restore()`
    * BEFORE it reads the snapshot it trims to.
    */
  def mark(): Long = writes.get()

  /** Forget every node that is not in `live`, has nothing in flight and was not written after
    * `since` (a [[mark]]), with its latency ring. `restore()` calls it on every replica with the
    * node rows of the snapshot it rebuilt from, so the nodes another replica removed go here too. A
    * node written after the mark (a respawn that re-applied an operator quarantine after the
    * snapshot was read, a node whose first probe or statement came meanwhile) is kept: the snapshot
    * could not list it. The next trim, whose mark is later, judges it again.
    */
  def retain(live: Set[String], since: Long): Unit =
    state.keySet().asScala.toList.filterNot(live.contains).foreach { id =>
      state.computeIfPresent(
        id,
        (_, e) => if e.touched > since || e.load.inFlight > 0 then e else null
      )
    }
    // A ring whose node holds no entry any more (dropped above, or by a racing remove); its
    // node's next onFinish writes the load before the ring, so it never lands here mid-write.
    percentiles.keySet().asScala.toList.filterNot(live.contains).foreach { id =>
      if !state.containsKey(id) then percentiles.remove(id)
    }

  /** Visible for tests: every node id either map still holds. */
  private[adapter] def trackedNodes: Set[String] =
    state.keySet().asScala.toSet ++ percentiles.keySet().asScala

  def snapshot(nodeId: String): NodeLoad =
    Option(state.get(nodeId)).fold(NodeLoad.empty)(_.load)

  def snapshotAll: Map[String, NodeLoad] =
    state.asScala.iterator.map { case (k, v) => k -> v.load }.toMap

object NodeLoadTracker:

  /** A node's load and the write sequence value of its latest write. */
  private final case class Entry(load: NodeLoad, touched: Long)
