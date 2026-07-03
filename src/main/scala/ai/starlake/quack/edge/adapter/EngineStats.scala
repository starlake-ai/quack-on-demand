package ai.starlake.quack.edge.adapter

import org.apache.arrow.vector.ipc.ArrowReader

import scala.collection.concurrent.TrieMap

/** Point-in-time DuckDB engine internals for one node, scraped over the same `quack_query` wire as
  * regular statements (the node exposes no stats endpoint). All values are bytes/counts as reported
  * by the engine's own introspection functions:
  *   - `memoryUsedBytes` / `tempStorageBytes`: buffer-manager usage summed across `duckdb_memory()`
  *     tags (BASE_TABLE, HASH_TABLE, PARQUET_READER, ...).
  *   - `spillFiles` / `spillBytes`: live spill-to-disk files from `duckdb_temporary_files()` - the
  *     signal that a node is memory-bound rather than CPU-bound.
  */
final case class EngineStats(
    memoryUsedBytes: Long,
    tempStorageBytes: Long,
    spillFiles: Long,
    spillBytes: Long
)

object EngineStats:

  /** Single-round-trip scrape. The CASTs collapse DuckDB's HUGEINT `SUM` results to BIGINT so the
    * Arrow cells decode as plain integers.
    */
  val sql: String =
    """SELECT
      |  CAST(COALESCE((SELECT SUM(memory_usage_bytes) FROM duckdb_memory()), 0) AS BIGINT),
      |  CAST(COALESCE((SELECT SUM(temporary_storage_bytes) FROM duckdb_memory()), 0) AS BIGINT),
      |  CAST((SELECT COUNT(*) FROM duckdb_temporary_files()) AS BIGINT),
      |  CAST(COALESCE((SELECT SUM(size) FROM duckdb_temporary_files()), 0) AS BIGINT)""".stripMargin

  /** Decode the single-row, 4-column result of [[sql]]. None on an empty result or an unexpected
    * shape (fail-soft: a scrape must never take a node down).
    */
  def fromReader(reader: ArrowReader): Option[EngineStats] =
    try
      if reader.loadNextBatch() then
        val root = reader.getVectorSchemaRoot
        if root.getRowCount >= 1 && root.getFieldVectors.size >= 4 then
          def cell(i: Int): Option[Long] =
            root.getFieldVectors.get(i).getObject(0) match
              case n: java.lang.Number => Some(n.longValue())
              case _                   => None
          for
            mem   <- cell(0)
            tmp   <- cell(1)
            files <- cell(2)
            bytes <- cell(3)
          yield EngineStats(mem, tmp, files, bytes)
        else None
      else None
    catch case _: Throwable => None

/** Latest [[EngineStats]] sample per node, written by the HealthProbe's scrape and read by
  * MetricsBindings on scrape. A node with no successful sample yet simply has no entry - the gauges
  * skip it rather than publish a misleading zero.
  */
final class EngineStatsTracker:
  private val state = TrieMap.empty[String, EngineStats]

  def update(nodeId: String, stats: EngineStats): Unit = state.update(nodeId, stats)

  def snapshot(nodeId: String): Option[EngineStats] = state.get(nodeId)

  def remove(nodeId: String): Unit = { state.remove(nodeId); () }
