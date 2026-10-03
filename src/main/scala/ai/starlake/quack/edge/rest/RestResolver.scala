package ai.starlake.quack.edge.rest

import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime}
import scala.util.Try

/** A filter bound to a probed column, its values type-checked against the column's kind. */
final case class ResolvedFilter(column: ProbedColumn, negated: Boolean, predicate: Predicate)

final case class ResolvedOrder(column: ProbedColumn, descending: Boolean, nulls: Option[NullsOrder])

/** A [[RestQuery]] bound to the probed schema: every column is the probe's own spelling and every
  * value has passed its column's type check. This is the only input [[RestSql.render]] accepts, so
  * no string the client typed as a column name can reach the SQL text.
  */
final case class ResolvedQuery(
    select: Vector[ProbedColumn],
    filters: Vector[ResolvedFilter],
    order: Vector[ResolvedOrder],
    limit: Option[Int],
    offset: Int
)

/** Binds a parsed [[RestQuery]] to the probed schema (column names, value types): the checks that
  * cannot run before the probe has answered. `reserved_column` is not among them: the parser
  * decides it without the schema.
  *
  * Checks run in a fixed order so the same request always gets the same error: `select`, then
  * filters, then `order`.
  */
object RestResolver:

  /** ASCII-only lower-casing: Unicode `equalsIgnoreCase` would fold the Kelvin sign (U+212A) onto
    * `k` and so let a client reach a column by a spelling DuckDB treats as distinct.
    */
  def asciiLower(s: String): String =
    val cs = s.toCharArray
    var i  = 0
    while i < cs.length do
      val c = cs(i)
      if c >= 'A' && c <= 'Z' then cs(i) = (c + 32).toChar
      i += 1
    new String(cs)

  def resolve(q: RestQuery, columns: Vector[ProbedColumn]): Either[RestError, ResolvedQuery] =
    val byKey = columns.groupBy(c => asciiLower(c.name))
    // Ambiguous (two probe columns equal under ASCII folding) resolves like unknown.
    def lookup(name: String): Option[ProbedColumn] =
      byKey.get(asciiLower(name)).collect { case Vector(only) => only }

    for
      // A probe with no column at all has nothing to serve; it answers
      // like an ungranted object, so "exists but hidden" cannot be told from "absent".
      _      <- if columns.isEmpty then Left(RestError.NotFound) else Right(())
      select <- q.select match
        case None       => Right(columns)
        case Some(cols) =>
          RestQuery.traverse(cols)(c => lookup(c).toRight(RestError.unknownColumn(c, "select")))
      filters <- RestQuery.traverse(q.filters)(f => resolveFilter(f, lookup))
      order   <- RestQuery.traverse(q.order) { t =>
        lookup(t.column) match
          case None                    => Left(RestError.unknownColumn(t.column, "order"))
          case Some(c) if !c.orderable =>
            Left(RestError.invalidParameter("order", "column type cannot be ordered"))
          case Some(c) => Right(ResolvedOrder(c, t.descending, t.nulls))
      }
    yield ResolvedQuery(select, filters, order, q.limit, q.offset)

  private def resolveFilter(
      f: Filter,
      lookup: String => Option[ProbedColumn]
  ): Either[RestError, ResolvedFilter] =
    lookup(f.param) match
      case None                     => Left(RestError.unknownColumn(f.param, "filter"))
      case Some(c) if !c.filterable =>
        Left(RestError.invalidFilter(f.param, "column type cannot be filtered"))
      case Some(c) =>
        val typeOk: Either[RestError, Unit] = f.predicate match
          case _: Predicate.Pattern if c.kind != ColumnKind.Text =>
            Left(RestError.invalidFilter(f.param, "like applies to string columns only"))
          case Predicate.Is(IsTarget.True | IsTarget.False) if c.kind != ColumnKind.Bool =>
            Left(RestError.invalidFilter(f.param, "is.true/is.false apply to boolean columns"))
          case Predicate.Compare(_, raw) => checkValue(f.param, c.kind, raw)
          case Predicate.Items(items)    =>
            RestQuery.traverse(items)(checkValue(f.param, c.kind, _)).map(_ => ())
          case _ => Right(())
        typeOk.map(_ => ResolvedFilter(c, f.negated, f.predicate))

  private val IntegerRe            = "-?[0-9]{1,40}".r
  private val DecimalRe            = "-?[0-9]{1,40}(\\.[0-9]{1,40})?".r
  private val FloatRe              = "-?[0-9]{1,40}(\\.[0-9]{1,40})?([eE][+-]?[0-9]{1,3})?".r
  private val FloatSpecial         = Set("NaN", "Infinity", "-Infinity")
  private val DateRe               = "[0-9]{4}-[0-9]{2}-[0-9]{2}".r
  private def timeRe(frac: Int)    = s"[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,$frac})?"
  private def naiveTsRe(frac: Int) = s"[0-9]{4}-[0-9]{2}-[0-9]{2}T${timeRe(frac)}".r
  private val OffsetRe             = "(Z|[+-][0-9]{2}:[0-9]{2})"
  private val TzTsRe               = s"[0-9]{4}-[0-9]{2}-[0-9]{2}T${timeRe(6)}$OffsetRe".r

  /** One value against one column kind. The value reaches SQL only as `CAST(<literal> AS <type>)`,
    * so these checks are about refusing early with a 400 naming the parameter, not about safety;
    * the one safety-relevant rule is the decimal exponent ban, which keeps a `BigDecimal` from ever
    * expanding `1e999999999` here.
    */
  def checkValue(param: String, kind: ColumnKind, raw: String): Either[RestError, Unit] =
    def fail(why: String) = Left(RestError.invalidFilter(param, why))
    kind match
      case ColumnKind.Integer(name, min, max) =>
        if IntegerRe.matches(raw) && { val b = BigInt(raw); b >= min && b <= max } then Right(())
        else fail(s"expected an integer in the range of $name")
      case ColumnKind.Decimal(p, s) =>
        if !DecimalRe.matches(raw) then fail("expected a decimal without exponent")
        else
          val bd       = BigDecimal(raw).bigDecimal.stripTrailingZeros
          val scale    = bd.scale.max(0)
          val intDigit = (bd.precision - bd.scale).max(0)
          if scale <= s && intDigit <= p - s then Right(())
          else fail(s"expected a value that fits DECIMAL($p,$s)")
      case ColumnKind.Floating(_) =>
        if FloatSpecial.contains(raw) || FloatRe.matches(raw) then Right(())
        else fail("expected a floating-point number")
      case ColumnKind.Bool =>
        if raw == "true" || raw == "false" then Right(()) else fail("expected true or false")
      case ColumnKind.Date =>
        if DateRe.matches(raw) && Try(LocalDate.parse(raw)).isSuccess then Right(())
        else fail("expected an ISO-8601 date")
      case ColumnKind.Time =>
        if timeRe(6).r.matches(raw) && Try(LocalTime.parse(raw)).isSuccess then Right(())
        else fail("expected an ISO-8601 time")
      case ColumnKind.Timestamp(t) =>
        val frac = if t == "TIMESTAMP_NS" then 9 else 6
        if naiveTsRe(frac).matches(raw) && Try(LocalDateTime.parse(raw)).isSuccess then Right(())
        else fail("expected an ISO-8601 timestamp without zone")
      case ColumnKind.TimestampTz =>
        if TzTsRe.matches(raw) && Try(OffsetDateTime.parse(raw)).isSuccess then Right(())
        else fail("expected an ISO-8601 timestamp with Z or an offset")
      case ColumnKind.Text      => Right(())
      case ColumnKind.Opaque(_) => fail("column type cannot be filtered")
