package ai.starlake.quack.edge.rest

import org.apache.arrow.vector.types.{DateUnit, FloatingPointPrecision, TimeUnit}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, Schema}

import scala.jdk.CollectionConverters.*

/** One column of the schema probe `SELECT * ... LIMIT 0`, as the edge needs it.
  *
  * The probe runs through the full pipeline, so its Arrow schema is the column contract AFTER
  * policy: masked columns look ordinary. A table holding a column under a deny policy never gets
  * here: the column policy rewriter refuses `SELECT *` over it, so the probe fails and the table
  * answers the same 404 as a missing one (while the tables listing still names it). Column names in
  * generated SQL are only ever taken from here, never from the query string.
  *
  * @param name
  *   the probe's spelling, which the SQL always uses
  * @param kind
  *   what the edge may do with the column; carries the DuckDB type name used in
  *   `CAST(<literal> AS <type>)`
  */
final case class ProbedColumn(name: String, kind: ColumnKind):
  def filterable: Boolean = kind.comparable
  def orderable: Boolean  = kind.comparable

/** The edge's view of a probed column type. A comparable kind carries the DuckDB type name that
  * `RestSql` puts in `CAST(<literal> AS <sqlType>)`; an opaque kind (nested, blob, interval, and
  * anything unrecognised) can be selected but never filtered or ordered, so its `sqlType` is only a
  * display name for the table-detail endpoint and never reaches SQL.
  */
enum ColumnKind(val sqlType: String, val comparable: Boolean):
  case Integer(override val sqlType: String, min: BigInt, max: BigInt)
      extends ColumnKind(sqlType, true)
  case Decimal(precision: Int, scale: Int) extends ColumnKind(s"DECIMAL($precision,$scale)", true)
  case Floating(override val sqlType: String)  extends ColumnKind(sqlType, true)
  case Text                                    extends ColumnKind("VARCHAR", true)
  case Bool                                    extends ColumnKind("BOOLEAN", true)
  case Date                                    extends ColumnKind("DATE", true)
  case Time                                    extends ColumnKind("TIME", true)
  case Timestamp(override val sqlType: String) extends ColumnKind(sqlType, true)
  case TimestampTz                             extends ColumnKind("TIMESTAMP WITH TIME ZONE", true)
  case Opaque(override val sqlType: String)    extends ColumnKind(sqlType, false)

object ProbedColumn:

  private def signedRange(bits: Int): (BigInt, BigInt) =
    (-(BigInt(1) << (bits - 1)), (BigInt(1) << (bits - 1)) - 1)
  private def unsignedRange(bits: Int): (BigInt, BigInt) =
    (BigInt(0), (BigInt(1) << bits) - 1)

  /** Every probed column, in probe order. */
  def fromArrow(schema: Schema): Vector[ProbedColumn] =
    schema.getFields.asScala.toVector.map(fromField)

  /** A dictionary-encoded field's Arrow type is its INDEX type, the value type living only in the
    * reader's dictionary; DuckDB dictionary-encodes exactly its ENUMs, whose values are strings, so
    * such a column is [[ColumnKind.Text]] (an ENUM compares with `CAST(<literal> AS VARCHAR)`).
    */
  def fromField(field: Field): ProbedColumn =
    val kind = if field.getDictionary != null then ColumnKind.Text else kindOf(field.getType)
    ProbedColumn(field.getName, kind)

  /** Arrow field type -> edge kind. DuckDB's default (non-lossless) Arrow export is what both the
    * embedded client and the native bridge hand the manager, so the mapping follows it; the
    * empirical table is pinned by `ProbedColumnSpec` against in-process DuckDB. Notable folds of
    * that export, all harmless for `CAST(<literal> AS <type>)` comparisons:
    *   - HUGEINT and UHUGEINT arrive as DECIMAL(38,0), so they filter as that type;
    *   - UUID and JSON arrive as VARCHAR, TIME WITH TIME ZONE as TIME, BIT and VARINT as BLOB;
    *   - ENUM arrives dictionary-encoded (handled in [[fromField]]);
    *   - an untyped `NULL` column arrives as INTEGER.
    */
  def kindOf(t: ArrowType): ColumnKind = t match
    case i: ArrowType.Int =>
      val bits     = i.getBitWidth
      val (lo, hi) = if i.getIsSigned then signedRange(bits) else unsignedRange(bits)
      val name     = (i.getIsSigned, bits) match
        case (true, 8)   => "TINYINT"
        case (true, 16)  => "SMALLINT"
        case (true, 32)  => "INTEGER"
        case (true, 64)  => "BIGINT"
        case (false, 8)  => "UTINYINT"
        case (false, 16) => "USMALLINT"
        case (false, 32) => "UINTEGER"
        case (false, 64) => "UBIGINT"
        case _           => ""
      if name.isEmpty then ColumnKind.Opaque(s"INT$bits") else ColumnKind.Integer(name, lo, hi)
    // DuckDB's DECIMAL tops out at 38 digits; anything wider cannot be CAST to.
    case d: ArrowType.Decimal if d.getPrecision <= 38 && d.getScale >= 0 =>
      ColumnKind.Decimal(d.getPrecision, d.getScale)
    case _: ArrowType.Decimal       => ColumnKind.Opaque("DECIMAL")
    case f: ArrowType.FloatingPoint =>
      f.getPrecision match
        case FloatingPointPrecision.SINGLE => ColumnKind.Floating("FLOAT")
        case FloatingPointPrecision.DOUBLE => ColumnKind.Floating("DOUBLE")
        case _                             => ColumnKind.Opaque("HALF_FLOAT")
    case _: ArrowType.Utf8 | _: ArrowType.LargeUtf8 | _: ArrowType.Utf8View => ColumnKind.Text
    case _: ArrowType.Bool                                                  => ColumnKind.Bool
    case d: ArrowType.Date if d.getUnit == DateUnit.DAY                     => ColumnKind.Date
    case _: ArrowType.Date       => ColumnKind.Timestamp("TIMESTAMP_MS")
    case _: ArrowType.Time       => ColumnKind.Time
    case ts: ArrowType.Timestamp =>
      if ts.getTimezone != null then ColumnKind.TimestampTz
      else
        ts.getUnit match
          case TimeUnit.SECOND      => ColumnKind.Timestamp("TIMESTAMP_S")
          case TimeUnit.MILLISECOND => ColumnKind.Timestamp("TIMESTAMP_MS")
          case TimeUnit.MICROSECOND => ColumnKind.Timestamp("TIMESTAMP")
          case TimeUnit.NANOSECOND  => ColumnKind.Timestamp("TIMESTAMP_NS")
    case _: ArrowType.Binary | _: ArrowType.LargeBinary | _: ArrowType.BinaryView |
        _: ArrowType.FixedSizeBinary =>
      ColumnKind.Opaque("BLOB")
    case _: ArrowType.Interval | _: ArrowType.Duration => ColumnKind.Opaque("INTERVAL")
    case _: ArrowType.List | _: ArrowType.LargeList | _: ArrowType.ListView |
        _: ArrowType.LargeListView | _: ArrowType.FixedSizeList =>
      ColumnKind.Opaque("LIST")
    case _: ArrowType.Struct => ColumnKind.Opaque("STRUCT")
    case _: ArrowType.Map    => ColumnKind.Opaque("MAP")
    case _: ArrowType.Union  => ColumnKind.Opaque("UNION")
    case _: ArrowType.Null   => ColumnKind.Opaque("NULL")
    case other               => ColumnKind.Opaque(other.getTypeID.name)
