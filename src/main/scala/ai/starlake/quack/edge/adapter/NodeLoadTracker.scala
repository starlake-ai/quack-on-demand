package ai.starlake.quack.edge.adapter

import ai.starlake.quack.route.NodeLoad
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap

final class NodeLoadTracker(alpha: Double = 0.3, latencyWindow: Int = 256):

  private val state       = TrieMap.empty[String, AtomicReference[NodeLoad]]
  private val percentiles = TrieMap.empty[String, LatencyRing]

  private def ref(nodeId: String): AtomicReference[NodeLoad] =
    state.getOrElseUpdate(nodeId, new AtomicReference(NodeLoad.empty))

  private def ring(nodeId: String): LatencyRing =
    percentiles.getOrElseUpdate(nodeId, new LatencyRing(latencyWindow))

  def onStart(nodeId: String): Unit =
    ref(nodeId).updateAndGet(l => l.copy(inFlight = l.inFlight + 1))

  def onFinish(nodeId: String, latencyMs: Long): Unit =
    ring(nodeId).record(latencyMs)
    ref(nodeId).updateAndGet { l =>
      val nextEwma =
        if l.ewmaMs == 0.0 then latencyMs.toDouble
        else (1 - alpha) * l.ewmaMs + alpha * latencyMs
      l.copy(
        inFlight = math.max(0, l.inFlight - 1),
        ewmaMs = nextEwma,
        totalServed = l.totalServed + 1
      )
    }

  /** Snapshot of (p50, p95, p99) for the node. (0,0,0) when no samples have been recorded yet.
    */
  def latencyPercentiles(nodeId: String): (Double, Double, Double) =
    percentiles.get(nodeId).map(_.percentiles()).getOrElse((0.0, 0.0, 0.0))

  def setHealthy(nodeId: String, healthy: Boolean): Unit =
    ref(nodeId).updateAndGet(_.copy(healthy = healthy))

  def setDraining(nodeId: String, draining: Boolean): Unit =
    ref(nodeId).updateAndGet(_.copy(draining = draining))

  def remove(nodeId: String): Unit = { state.remove(nodeId); () }

  def snapshot(nodeId: String): NodeLoad = ref(nodeId).get()

  def snapshotAll: Map[String, NodeLoad] =
    state.iterator.map { case (k, v) => k -> v.get() }.toMap
