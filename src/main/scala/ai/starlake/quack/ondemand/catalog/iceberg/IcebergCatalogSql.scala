package ai.starlake.quack.ondemand.catalog.iceberg

/** Every statement the Iceberg catalog views run, and nothing else. Identifiers are double-quoted,
  * literals single-quoted; snapshot ids must satisfy [[IcebergSnapshots.validId]] (enforced with
  * `require`, since a handler that passes an unvalidated id is a bug, not a user error).
  *
  * Credentials: [[snapshots]] is the only reader of `iceberg_load_table_response`, and it projects
  * `snapshots` and `current-snapshot-id` out of `metadata` ON THE NODE. `storage_credentials` and
  * `config` (vended credentials, catalog settings) never leave the node. Verified shape: DuckDB
  * 1.5.6 against `scripts/iceberg-fixture.yml`, 2026-09-29.
  */
object IcebergCatalogSql:

  private def ident(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
  private def lit(v: String): String   = "'" + v.replace("'", "''") + "'"

  private def target(alias: String, schema: String, table: String): String =
    s"${ident(alias)}.${ident(schema)}.${ident(table)}"

  private def id(raw: String): String =
    require(IcebergSnapshots.validId(raw), s"invalid snapshot id: $raw")
    raw

  def schemas(alias: String): String =
    s"SELECT schema_name FROM duckdb_schemas() WHERE database_name = ${lit(alias)} " +
      "AND NOT internal ORDER BY schema_name"

  def tables(alias: String, schema: String): String =
    s"SELECT table_name FROM duckdb_tables() WHERE database_name = ${lit(alias)} " +
      s"AND schema_name = ${lit(schema)} ORDER BY table_name"

  def columns(alias: String, schema: String, table: String): String =
    s"SELECT column_name, column_type, \"null\" FROM (DESCRIBE ${target(alias, schema, table)})"

  /** DuckDB 1.5.6's `iceberg_metadata()` reports `content = EXISTING` for a data file ("DATA" only
    * appears in `manifest_content`) and `POSITION_DELETES` / `EQUALITY_DELETES` for delete files;
    * the CASE folds the data-file case back to "DATA" so callers never see "EXISTING".
    */
  def files(alias: String, schema: String, table: String): String =
    "SELECT file_path, CASE WHEN manifest_content = 'DATA' THEN 'DATA' ELSE content END AS content, " +
      "file_format, record_count, manifest_sequence_number " +
      s"FROM iceberg_metadata(${target(alias, schema, table)}) " +
      "ORDER BY manifest_sequence_number DESC, file_path"

  enum SnapshotFilter:
    /** History page: snapshots older (by sequence number) than `beforeSeq`, optionally one op. */
    case Page(beforeSeq: Option[Long], operation: Option[String])
    case ById(ids: List[String])

    /** The newest snapshot committed at or before `epochMs`. */
    case AtOrBefore(epochMs: Long)

    /** The snapshot the catalog's `current-snapshot-id` names, regardless of its sequence number:
      * after a rollback, or with a staged/WAP snapshot on top, that is not the highest-sequence
      * row.
      */
    case Current

  def snapshots(
      alias: String,
      schema: String,
      table: String,
      filter: SnapshotFilter,
      limit: Int
  ): String =
    val where = filter match
      case SnapshotFilter.Page(before, op) =>
        op.foreach(o => require(IcebergSnapshots.Operations.contains(o), s"invalid operation: $o"))
        before.map(b => s"seq < $b").toList ++
          op.map(o => s"summary_json::JSON->>'operation' = ${lit(o)}").toList
      case SnapshotFilter.ById(ids) =>
        require(ids.nonEmpty, "ById needs at least one id")
        List(s"snapshot_id IN (${ids.map(i => lit(id(i))).mkString(", ")})")
      case SnapshotFilter.AtOrBefore(ms) => List(s"ts_ms <= $ms")
      case SnapshotFilter.Current        => List("is_current")
    val whereSql = if where.isEmpty then "" else where.mkString(" WHERE ", " AND ", "")
    s"WITH m AS (SELECT metadata::JSON AS j FROM iceberg_load_table_response(${target(alias, schema, table)})), " +
      "s AS (SELECT unnest(from_json(j->'snapshots', '[\"JSON\"]')) AS s, j->>'current-snapshot-id' AS cur FROM m), " +
      "r AS (SELECT s->>'snapshot-id' AS snapshot_id, s->>'parent-snapshot-id' AS parent_id, " +
      "(s->>'sequence-number')::BIGINT AS seq, (s->>'timestamp-ms')::BIGINT AS ts_ms, " +
      "(s->'summary')::VARCHAR AS summary_json, (s->>'snapshot-id') = cur AS is_current FROM s) " +
      s"SELECT snapshot_id, parent_id, seq, ts_ms, summary_json, is_current FROM r$whereSql " +
      s"ORDER BY seq DESC LIMIT $limit"

  def preview(
      alias: String,
      schema: String,
      table: String,
      snapshotId: Option[String],
      limit: Int
  ): String =
    val at = snapshotId.fold("")(s => s" AT (VERSION => ${id(s)})")
    s"SELECT * FROM ${target(alias, schema, table)}$at LIMIT $limit"

  val ChangeColumn = "__qod_change"

  /** Rows present at `from` but not at `to` are `removed`, the reverse `added`; an UPDATE is one of
    * each. `EXCEPT ALL` keeps duplicate rows honest. `changeType` narrows to one direction.
    */
  def diff(
      alias: String,
      schema: String,
      table: String,
      from: String,
      to: String,
      changeType: Option[String],
      limit: Int
  ): String =
    changeType.foreach(c => require(c == "added" || c == "removed", s"invalid change type: $c"))
    val t                                         = target(alias, schema, table)
    def side(label: String, a: String, b: String) =
      s"SELECT '$label' AS ${ident(ChangeColumn)}, * FROM " +
        s"(SELECT * FROM $t AT (VERSION => ${id(a)}) EXCEPT ALL SELECT * FROM $t AT (VERSION => ${id(b)}))"
    val parts = List(
      Option.when(changeType.forall(_ == "removed"))(side("removed", from, to)),
      Option.when(changeType.forall(_ == "added"))(side("added", to, from))
    ).flatten
    s"${parts.mkString(" UNION ALL ")} LIMIT $limit"
