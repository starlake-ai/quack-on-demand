package ai.starlake.quack.edge

import java.time.Instant

/** A single statement execution event captured by the router. Kept lean so the in-memory ring
  * buffer doesn't bloat. `sql` is truncated at 500 chars before storage to bound memory under
  * bursty workloads.
  */
final case class StatementRecord(
    ts: Instant,
    user: String,
    tenant: String,
    pool: String,
    nodeId: String,
    sql: String,
    durationMs: Long,
    status: String, // "ok" | "denied" | "transient" | "permanent" | "no-node"
    error: Option[String]
)

/** Bounded ring buffer of recent statement executions. Lock-protected append + snapshot. Default
  * capacity ~ 256 records (≈ tens of KiB on average; bounded by `sqlPreviewChars` per record).
  */
final class StatementHistoryStore(capacity: Int = 256, sqlPreviewChars: Int = 500):

  private val buf    = new Array[StatementRecord](capacity)
  private var idx    = 0
  private var filled = 0
  private val lock   = new Object

  def record(r: StatementRecord): Unit = lock.synchronized {
    val trimmed =
      if r.sql.length <= sqlPreviewChars then r
      else r.copy(sql = r.sql.take(sqlPreviewChars) + "…")
    buf(idx) = trimmed
    idx = (idx + 1) % capacity
    if filled < capacity then filled += 1
  }

  /** Newest-first snapshot, capped at `limit`. */
  def snapshot(limit: Int): List[StatementRecord] = lock.synchronized {
    if filled == 0 then Nil
    else
      // Walk backwards from the most-recently-written slot.
      val out       = scala.collection.mutable.ListBuffer.empty[StatementRecord]
      var i         = (idx - 1 + capacity) % capacity
      var remaining = math.min(limit, filled)
      while remaining > 0 do
        out += buf(i)
        i = (i - 1 + capacity) % capacity
        remaining -= 1
      out.toList
  }

  def size: Int = lock.synchronized(filled)
