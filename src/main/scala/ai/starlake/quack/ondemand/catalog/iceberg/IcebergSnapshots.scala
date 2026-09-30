package ai.starlake.quack.ondemand.catalog.iceberg

import io.circe.{parser, Json}

/** One Iceberg snapshot as read from the table metadata (`iceberg_load_table_response`). Ids stay
  * strings: they are random 64-bit values, beyond JavaScript's 2^53, and are only ever compared for
  * equality or passed back into SQL after [[IcebergSnapshots.validId]].
  */
final case class IcebergSnapshot(
    snapshotId: String,
    parentId: Option[String],
    sequence: Long,
    timestampMs: Long,
    summary: Map[String, String],
    current: Boolean
):
  def operation: Option[String]        = summary.get("operation")
  def count(key: String): Option[Long] = summary.get(key).flatMap(_.toLongOption)

object IcebergSnapshots:

  enum ParseError:
    /** A NULL sequence number: the table is Iceberg format v1, which these views refuse. */
    case FormatV1
    case Malformed(message: String)

  val IdPattern = "-?\\d{1,19}".r

  /** Whether `raw` may be interpolated into SQL as a snapshot id. */
  def validId(raw: String): Boolean = IdPattern.matches(raw) && raw.toLongOption.isDefined

  /** The Iceberg snapshot operations; the history `operation` filter accepts only these. */
  val Operations: Set[String] = Set("append", "overwrite", "delete", "replace")

  /** Rows in the column order of [[IcebergCatalogSql.snapshots]]: `snapshot_id, parent_id, seq,
    * ts_ms, summary_json, is_current`. `summary_json` is the snapshot's summary object serialized
    * as VARCHAR on the node.
    */
  def parse(rows: List[List[Json]]): Either[ParseError, List[IcebergSnapshot]] =
    if rows.exists(r => r.size == 6 && r(2).isNull) then Left(ParseError.FormatV1)
    else
      rows.foldRight[Either[ParseError, List[IcebergSnapshot]]](Right(Nil)) { (row, acc) =>
        acc.flatMap(tail => parseRow(row).map(_ :: tail))
      }

  private def parseRow(row: List[Json]): Either[ParseError, IcebergSnapshot] =
    row match
      case List(id, parent, seq, ts, summary, cur) =>
        for
          _   <- Either.cond(!seq.isNull, (), ParseError.FormatV1)
          sid <- id.asString
            .filter(validId)
            .toRight(ParseError.Malformed(s"bad snapshot_id: $id"))
          pid = parent.asString.filter(validId)
          sq <- seq.asNumber.flatMap(_.toLong).toRight(ParseError.Malformed(s"bad seq: $seq"))
          t  <- ts.asNumber.flatMap(_.toLong).toRight(ParseError.Malformed(s"bad ts_ms: $ts"))
          sj <- summary.asString.toRight(ParseError.Malformed(s"bad summary: $summary"))
          sm <- parser
            .parse(sj)
            .left
            .map(e => ParseError.Malformed(e.getMessage))
            .flatMap(summaryMap)
          c <- cur.asBoolean.toRight(ParseError.Malformed(s"bad is_current: $cur"))
        yield IcebergSnapshot(sid, pid, sq, t, sm, c)
      case other => Left(ParseError.Malformed(s"expected 6 columns, got ${other.size}"))

  private def summaryMap(j: Json): Either[ParseError, Map[String, String]] =
    j.asObject
      .map(_.toMap.flatMap((k, v) => v.asString.orElse(v.asNumber.map(_.toString)).map(k -> _)))
      .toRight(ParseError.Malformed("summary is not an object"))

  def tooLargeForDiff(snaps: List[IcebergSnapshot], maxFiles: Int): Boolean =
    snaps.exists(_.count("total-data-files").exists(_ > maxFiles))
