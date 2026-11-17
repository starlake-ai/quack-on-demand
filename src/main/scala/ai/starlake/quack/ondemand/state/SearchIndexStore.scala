package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{SearchIndex, SearchIndexField, SearchIndexTombstone, SearchSegment}
import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse

import java.sql.{Connection, ResultSet, Timestamp}
import java.util.UUID
import scala.collection.mutable.ListBuffer

/** The QoD Search index registry (spec 6.1, 6.2; Liquibase 0059): `qodstate_search_index`, its
  * `qodstate_search_segment` rows and the tombstones a dropped index leaves for Phase 2's bundle GC
  * (OWNER RULING 2026-11-16, D3).
  *
  * The rows live in the control-plane database, written through the [[ControlPlaneStore]] the
  * manager already holds ([[SearchIndexStore.over]]), so a manifest import and a tenant-db delete
  * write them in the same transaction as the rows they go with, and `qodstate_search_index`'s
  * non-cascading key on `qodstate_tenant_db` never refuses a delete half-way. The tables are not
  * attached to the policy-version trigger: an index changes no principal's permissions.
  */
trait SearchIndexStore:

  /** Inserts `ix`; `Left("exists")` when its table (or its id) already has an index. */
  def create(ix: SearchIndex): IO[Either[String, SearchIndex]]

  /** The indexes of these tenant-dbs, by tenant-db then schema and table at create. */
  def list(tenantDbIds: Set[String]): IO[List[SearchIndex]]

  /** The index of a tenant-db's table, by its DuckLake table id (the key, review M9). */
  def find(tenantDbId: String, tableId: Long): IO[Option[SearchIndex]]

  def findByIndexId(indexId: UUID): IO[Option[SearchIndex]]

  /** Removes the index and its segment rows and writes its tombstone, in one transaction. */
  def delete(indexId: UUID): IO[Boolean]

  def setState(indexId: UUID, state: String): IO[Unit]

  /** Upserts one segment row, then moves `segments_version` up by one, in one transaction. */
  def recordSegment(
      indexId: UUID,
      pathKey: String,
      path: String,
      bytes: Long,
      docs: Long,
      buildMs: Long,
      formatVersion: Int
  ): IO[Unit]

  /** Tombstones and removes every index of a tenant-db; the count removed. */
  def deleteForTenantDb(tenantDbId: String): IO[Int]

object SearchIndexStore:

  /** The registry over the control-plane store, its rows' own home. */
  def over(store: ControlPlaneStore): SearchIndexStore = new SearchIndexStore:
    def create(ix: SearchIndex): IO[Either[String, SearchIndex]] =
      IO.blocking(if store.insertSearchIndex(ix) then Right(ix) else Left("exists"))
    def list(tenantDbIds: Set[String]): IO[List[SearchIndex]] =
      IO.blocking(store.listSearchIndexes(tenantDbIds))
    def find(tenantDbId: String, tableId: Long): IO[Option[SearchIndex]] =
      IO.blocking(store.findSearchIndex(tenantDbId, tableId))
    def findByIndexId(indexId: UUID): IO[Option[SearchIndex]] =
      IO.blocking(store.findSearchIndexById(indexId))
    def delete(indexId: UUID): IO[Boolean] =
      IO.blocking(store.deleteSearchIndex(indexId))
    def setState(indexId: UUID, state: String): IO[Unit] =
      IO.blocking(store.setSearchIndexState(indexId, state))
    def recordSegment(
        indexId: UUID,
        pathKey: String,
        path: String,
        bytes: Long,
        docs: Long,
        buildMs: Long,
        formatVersion: Int
    ): IO[Unit] =
      IO.blocking(
        store.recordSearchSegment(
          SearchSegment(
            indexId,
            pathKey,
            path,
            bytes,
            docs,
            java.time.Instant.now(),
            buildMs,
            formatVersion
          )
        )
      )
    def deleteForTenantDb(tenantDbId: String): IO[Int] =
      IO.blocking(store.deleteSearchIndexesForTenantDb(tenantDbId))

  /** Whether two rows of one index id are one index: same tenant-db, table id, fields and bundle
    * root (spec 6.2).
    */
  def sameIdentity(a: SearchIndex, b: SearchIndex): Boolean =
    a.tenantDbId == b.tenantDbId && a.tableId == b.tableId && a.fields == b.fields &&
      a.bundleRoot == b.bundleRoot

  /** The `fields` column's JSON: `[{"column": .., "fieldId": ..}, ...]`, in index order. */
  def fieldsJson(fields: List[SearchIndexField]): String =
    Json
      .arr(fields.map { f =>
        Json.obj("column" -> Json.fromString(f.column), "fieldId" -> Json.fromLong(f.fieldId))
      }*)
      .noSpaces

  def fieldsOf(json: String): List[SearchIndexField] =
    parse(json).toOption
      .flatMap(_.asArray)
      .map(_.toList.flatMap { j =>
        val c = j.hcursor
        for
          col <- c.get[String]("column").toOption
          fid <- c.get[Long]("fieldId").toOption
        yield SearchIndexField(col, fid)
      })
      .getOrElse(Nil)

/** The Postgres half of [[SearchIndexStore]]: statements on a connection
  * [[PostgresControlPlaneStore]] hands out (the bound control-plane transaction's when there is
  * one). Every multi-statement write runs inside the caller's `atomically`.
  */
private[state] object PostgresSearchIndexRows:

  private val Columns =
    "index_id, tenant_db_id, schema_name, table_name, table_id, fields::text AS fields, " +
      "bundle_root, state, segments_version, created_by, created_at"

  private def rowOf(rs: ResultSet): SearchIndex =
    SearchIndex(
      indexId = rs.getObject("index_id", classOf[UUID]),
      tenantDbId = rs.getString("tenant_db_id"),
      schemaName = rs.getString("schema_name"),
      tableName = rs.getString("table_name"),
      tableId = rs.getLong("table_id"),
      fields = SearchIndexStore.fieldsOf(rs.getString("fields")),
      bundleRoot = rs.getString("bundle_root"),
      state = rs.getString("state"),
      segmentsVersion = rs.getLong("segments_version"),
      createdBy = rs.getString("created_by"),
      createdAt = rs.getTimestamp("created_at").toInstant
    )

  private def query[A](c: Connection, sql: String, params: Any*)(f: ResultSet => A): List[A] =
    val ps = c.prepareStatement(sql)
    try
      params.zipWithIndex.foreach((p, i) => ps.setObject(i + 1, p.asInstanceOf[AnyRef]))
      val rs = ps.executeQuery()
      try
        val acc = ListBuffer.empty[A]
        while rs.next() do acc += f(rs)
        acc.toList
      finally rs.close()
    finally ps.close()

  private def update(c: Connection, sql: String, params: Any*): Int =
    val ps = c.prepareStatement(sql)
    try
      params.zipWithIndex.foreach((p, i) => ps.setObject(i + 1, p.asInstanceOf[AnyRef]))
      ps.executeUpdate()
    finally ps.close()

  /** False when the row's table or id already has an index (nothing written). An index id that is
    * written takes back its tombstone, if it had one (an index restored after its drop): Phase 2's
    * GC must not collect the bundles of a live index. Runs inside the caller's `atomically`.
    */
  def insert(c: Connection, ix: SearchIndex): Boolean =
    val written = update(
      c,
      """INSERT INTO qodstate_search_index
        |  (index_id, tenant_db_id, schema_name, table_name, table_id, fields, bundle_root, state,
        |   segments_version, created_by, created_at)
        |VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
        |ON CONFLICT DO NOTHING""".stripMargin,
      ix.indexId,
      ix.tenantDbId,
      ix.schemaName,
      ix.tableName,
      ix.tableId,
      SearchIndexStore.fieldsJson(ix.fields),
      ix.bundleRoot,
      ix.state,
      ix.segmentsVersion,
      ix.createdBy,
      Timestamp.from(ix.createdAt)
    ) == 1
    if written then
      update(c, "DELETE FROM qodstate_search_index_tombstone WHERE index_id = ?", ix.indexId): Unit
    written

  /** Writes `ix` as given (a manifest import), by its index id. An index's identity (its tenant-db,
    * table id, fields and bundle root) never changes in place (spec 6.2: a field change is a new
    * index id), so a stored row takes only `ix`'s names and state and keeps its creator, creation
    * time and segments; the importer's `validate()` refuses an identity change, and this throws on
    * one rather than write it. An insert that writes nothing (its table already indexed under
    * another id) throws too.
    */
  def upsert(c: Connection, ix: SearchIndex): Unit =
    byId(c, ix.indexId) match
      case None =>
        if !insert(c, ix) then
          throw new java.sql.SQLException(
            s"search index ${ix.indexId} not written: its table already has another index"
          )
      case Some(stored) =>
        if !SearchIndexStore.sameIdentity(stored, ix) then
          throw new java.sql.SQLException(
            s"search index ${ix.indexId} would change its identity in place"
          )
        update(
          c,
          "UPDATE qodstate_search_index SET schema_name = ?, table_name = ?, state = ? " +
            "WHERE index_id = ?",
          ix.schemaName,
          ix.tableName,
          ix.state,
          ix.indexId
        ): Unit

  def list(c: Connection, tenantDbIds: Set[String]): List[SearchIndex] =
    if tenantDbIds.isEmpty then Nil
    else
      val marks = tenantDbIds.toList.map(_ => "?").mkString(", ")
      query(
        c,
        s"SELECT $Columns FROM qodstate_search_index WHERE tenant_db_id IN ($marks) " +
          "ORDER BY tenant_db_id, schema_name, table_name, index_id",
        tenantDbIds.toList*
      )(rowOf)

  def listAll(c: Connection): List[SearchIndex] =
    query(
      c,
      s"SELECT $Columns FROM qodstate_search_index " +
        "ORDER BY tenant_db_id, schema_name, table_name, index_id"
    )(rowOf)

  def byTable(c: Connection, tenantDbId: String, tableId: Long): Option[SearchIndex] =
    query(
      c,
      s"SELECT $Columns FROM qodstate_search_index WHERE tenant_db_id = ? AND table_id = ?",
      tenantDbId,
      tableId
    )(rowOf).headOption

  def byId(c: Connection, indexId: UUID): Option[SearchIndex] =
    query(c, s"SELECT $Columns FROM qodstate_search_index WHERE index_id = ?", indexId)(
      rowOf
    ).headOption

  /** The tombstone first, then the row (its segments cascade); `afterTombstone` is a test's seam
    * between the two.
    */
  def delete(c: Connection, indexId: UUID, afterTombstone: () => Unit = () => ()): Boolean =
    val tombstoned = update(
      c,
      """INSERT INTO qodstate_search_index_tombstone (index_id, bundle_root)
        |SELECT index_id, bundle_root FROM qodstate_search_index WHERE index_id = ?
        |ON CONFLICT (index_id) DO UPDATE
        |  SET bundle_root = EXCLUDED.bundle_root, dropped_at = now()""".stripMargin,
      indexId
    )
    afterTombstone()
    tombstoned == 1 &&
    update(c, "DELETE FROM qodstate_search_index WHERE index_id = ?", indexId) == 1

  def setState(c: Connection, indexId: UUID, state: String): Unit =
    update(c, "UPDATE qodstate_search_index SET state = ? WHERE index_id = ?", state, indexId): Unit

  def recordSegment(c: Connection, s: SearchSegment): Unit =
    val written = update(
      c,
      """INSERT INTO qodstate_search_segment
        |  (index_id, path_key, path, bytes, docs, built_at, build_ms, format_version)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (index_id, path_key) DO UPDATE
        |  SET path = EXCLUDED.path, bytes = EXCLUDED.bytes, docs = EXCLUDED.docs,
        |      built_at = EXCLUDED.built_at, build_ms = EXCLUDED.build_ms,
        |      format_version = EXCLUDED.format_version""".stripMargin,
      s.indexId,
      s.pathKey,
      s.path,
      s.bytes,
      s.docs,
      Timestamp.from(s.builtAt),
      s.buildMs,
      s.formatVersion
    )
    if written != 1 then throw new java.sql.SQLException(s"no segment row written for ${s.indexId}")
    update(
      c,
      "UPDATE qodstate_search_index SET segments_version = segments_version + 1 WHERE index_id = ?",
      s.indexId
    ): Unit

  def segments(c: Connection, indexId: UUID): List[SearchSegment] =
    query(
      c,
      """SELECT index_id, path_key, path, bytes, docs, built_at, build_ms, format_version
        |  FROM qodstate_search_segment WHERE index_id = ? ORDER BY path_key""".stripMargin,
      indexId
    ) { rs =>
      SearchSegment(
        indexId = rs.getObject("index_id", classOf[UUID]),
        pathKey = rs.getString("path_key"),
        path = rs.getString("path"),
        bytes = rs.getLong("bytes"),
        docs = rs.getLong("docs"),
        builtAt = rs.getTimestamp("built_at").toInstant,
        buildMs = rs.getLong("build_ms"),
        formatVersion = rs.getInt("format_version")
      )
    }

  def deleteForTenantDb(c: Connection, tenantDbId: String): Int =
    update(
      c,
      """INSERT INTO qodstate_search_index_tombstone (index_id, bundle_root)
        |SELECT index_id, bundle_root FROM qodstate_search_index WHERE tenant_db_id = ?
        |ON CONFLICT (index_id) DO UPDATE
        |  SET bundle_root = EXCLUDED.bundle_root, dropped_at = now()""".stripMargin,
      tenantDbId
    )
    update(c, "DELETE FROM qodstate_search_index WHERE tenant_db_id = ?", tenantDbId)

  def tombstones(c: Connection): List[SearchIndexTombstone] =
    query(
      c,
      "SELECT index_id, bundle_root, dropped_at FROM qodstate_search_index_tombstone " +
        "ORDER BY index_id"
    ) { rs =>
      SearchIndexTombstone(
        rs.getObject("index_id", classOf[UUID]),
        rs.getString("bundle_root"),
        rs.getTimestamp("dropped_at").toInstant
      )
    }
