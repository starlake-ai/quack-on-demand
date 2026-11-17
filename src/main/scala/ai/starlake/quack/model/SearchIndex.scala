package ai.starlake.quack.model

import java.time.Instant
import java.util.UUID

/** One indexed column of a [[SearchIndex]]: its name at create, for display, and its DuckLake field
  * id (`ducklake_column.column_id`), which is what binds it, so a renamed column stays indexed.
  */
final case class SearchIndexField(column: String, fieldId: Long)

/** One full-text index of a DuckLake table (QoD Search, spec 6.1), a `qodstate_search_index` row.
  *
  * `tableId` is the DuckLake table id the index pins and its key with `tenantDbId` (review M9): a
  * renamed table keeps its index, and a dropped one disables it (a table created again under the
  * same name is another table id). `schemaName` and `tableName` are the names at create, for
  * display only. `bundleRoot` is where the bundles lie: the tenant-db's resolved data path at
  * create. `segmentsVersion` moves on every segment written. k1 and b are fixed at 1.2 and 0.75
  * through Phase 1 (OWNER RULING 2026-11-16, D10): no option is stored or read here.
  */
final case class SearchIndex(
    indexId: UUID,
    tenantDbId: String,
    schemaName: String,
    tableName: String,
    tableId: Long,
    fields: List[SearchIndexField],
    bundleRoot: String,
    state: String,
    segmentsVersion: Long,
    createdBy: String,
    createdAt: Instant
)

object SearchIndex:

  /** A live index: its table id is a live table. */
  val Ready = "ready"

  /** An index whose table was dropped (spec 6.1); never set back to [[Ready]]. */
  val Disabled = "disabled"

  val States: Set[String] = Set(Ready, Disabled)

  /** The bundle format the node writes (`QS_BUNDLE_FORMAT_VERSION` in native/qod-search/src/lib.rs,
    * pinned by `SearchIndexServiceSpec`), recorded on every segment row.
    */
  val BundleFormatVersion = 3

  /** At most this many columns per index. */
  val MaxFields = 32

/** One bundle written for an index: `pathKey` is the hex SHA-256 of the data file's path (the
  * node's own key, `qod_search_build_segment`), `path` the data file's path.
  */
final case class SearchSegment(
    indexId: UUID,
    pathKey: String,
    path: String,
    bytes: Long,
    docs: Long,
    builtAt: Instant,
    buildMs: Long,
    formatVersion: Int
)

/** A dropped index, kept for Phase 2's bundle GC (OWNER RULING 2026-11-16, D3). */
final case class SearchIndexTombstone(indexId: UUID, bundleRoot: String, droppedAt: Instant)
