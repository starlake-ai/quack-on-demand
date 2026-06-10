package ai.starlake.quack.edge.adapter

/** Lock-protected ring buffer of recent statement latencies (ms). One per node. Used by
  * [[NodeLoadTracker]] to expose p50/p95/p99 to the UI without hauling a full histogram across the
  * wire.
  *
  * Capacity defaults to 256 samples - about 2 KiB per node, sorting a snapshot takes a few µs.
  * Plenty for surface metrics; if you need higher fidelity later, swap in a t-digest or HDR
  * histogram.
  */
final class LatencyRing(capacity: Int = 256):

  private val buf    = new Array[Long](capacity)
  private var idx    = 0
  private var filled = 0
  private val lock   = new Object

  def record(latencyMs: Long): Unit = lock.synchronized {
    buf(idx) = latencyMs
    idx = (idx + 1) % capacity
    if filled < capacity then filled += 1
  }

  /** Snapshot `(p50, p95, p99)` over the current window. Returns `(0, 0, 0)` while empty.
    */
  def percentiles(): (Double, Double, Double) = lock.synchronized {
    if filled == 0 then (0.0, 0.0, 0.0)
    else
      val arr = new Array[Long](filled)
      System.arraycopy(buf, 0, arr, 0, filled)
      java.util.Arrays.sort(arr)
      (pct(arr, 0.50), pct(arr, 0.95), pct(arr, 0.99))
  }

  /** Number of samples currently held (≤ capacity). */
  def size: Int = lock.synchronized(filled)

  private def pct(arr: Array[Long], p: Double): Double =
    val n = arr.length
    if n == 0 then 0.0
    else arr(math.min(n - 1, math.max(0, math.round(p * (n - 1)).toInt))).toDouble
