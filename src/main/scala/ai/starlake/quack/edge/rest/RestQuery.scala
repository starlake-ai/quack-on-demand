package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.api.QueryParams

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.time.Instant

/** Filter operators of the `/rows` grammar, keyed by their wire spelling. */
enum FilterOp(val wire: String):
  case Eq    extends FilterOp("eq")
  case Neq   extends FilterOp("neq")
  case Gt    extends FilterOp("gt")
  case Gte   extends FilterOp("gte")
  case Lt    extends FilterOp("lt")
  case Lte   extends FilterOp("lte")
  case Like  extends FilterOp("like")
  case ILike extends FilterOp("ilike")
  case In    extends FilterOp("in")
  case Is    extends FilterOp("is")

object FilterOp:
  private val byWire: Map[String, FilterOp] = values.map(o => o.wire -> o).toMap
  def fromWire(s: String): Option[FilterOp] = byWire.get(s)

/** How a `like`/`ilike` value is rendered. Every form keeps DuckDB on a linear matcher: a LIKE
  * pattern made of `%` and literal text only runs on DuckDB's segment matcher, while any `_` or
  * `ESCAPE` falls back to a backtracking matcher that costs length^(inner wildcards), seconds for
  * one 4 KiB value with two inner wildcards. A value holding a literal `%` or `_` therefore never
  * becomes a LIKE: with `*` only at its ends it is exactly an equality, a prefix, a suffix or a
  * substring test, and any other shape is refused.
  *
  * `ilike` is the same form over `lower(col)` and `lower(value)`, never ILIKE (backtracking too).
  * On DuckDB 1.5.4 the result equals ILIKE for ASCII and for every BMP character checked (ILIKE
  * also lowercases per code point on non-ASCII input); neither applies full case folding, so `ß`
  * does not match `ss` under either.
  */
enum PatternForm:
  /** `text` is a LIKE pattern: `*` mapped to `%`, no `_`, no escape. `\` is an ordinary character,
    * since DuckDB's LIKE has no default escape.
    */
  case Like

  /** `text` is the literal needle, the value without its edge `*`s. */
  case Equals, StartsWith, EndsWith, Contains

/** The comparison operators and their SQL spelling (`neq` is `<>`). */
enum Comparison(val sql: String):
  case Eq  extends Comparison("=")
  case Neq extends Comparison("<>")
  case Gt  extends Comparison(">")
  case Gte extends Comparison(">=")
  case Lt  extends Comparison("<")
  case Lte extends Comparison("<=")

/** What a filter tests, shaped by its operator so no operator can meet a value of the wrong form.
  */
enum Predicate:
  /** `eq neq gt gte lt lte`: the raw text, type-checked later against the probed column. */
  case Compare(cmp: Comparison, raw: String)

  /** `like ilike`: `text` is read according to `form` (see [[PatternForm]]). */
  case Pattern(caseInsensitive: Boolean, form: PatternForm, text: String)

  /** `in`: the decoded items, each type-checked later like a [[Compare]] value. */
  case Items(items: Vector[String])

  /** `is`: `null`, `true` or `false`. */
  case Is(target: IsTarget)

enum IsTarget(val sql: String):
  case Null  extends IsTarget("NULL")
  case True  extends IsTarget("TRUE")
  case False extends IsTarget("FALSE")

/** One column filter: `<param>=[not.]<op>.<value>`. `param` is the column as the client spelled it;
  * resolution against the probe happens in [[RestResolver]].
  */
final case class Filter(param: String, negated: Boolean, predicate: Predicate)

enum NullsOrder(val sql: String):
  case First extends NullsOrder("NULLS FIRST")
  case Last  extends NullsOrder("NULLS LAST")

final case class OrderTerm(column: String, descending: Boolean, nulls: Option[NullsOrder])

/** The schema-free AST of one request's query string.
  *
  * Built only by [[RestQuery.parse]], which has already applied every check that does not need the
  * probed schema: grammar, reserved names (`reserved_column` included) and their duplicates, caps,
  * NUL bytes, `limit`/`offset` syntax, `branch` refused, `asOf*` mutual exclusion and
  * `order_required`.
  */
final case class RestQuery(
    select: Option[Vector[String]],
    filters: Vector[Filter],
    order: Vector[OrderTerm],
    limit: Option[Int],
    offset: Int,
    asOf: Option[Long],
    asOfTag: Option[String],
    asOfTs: Option[Instant],
    pool: Option[String],
    format: Option[String]
):
  /** `asOf` or `asOfTag` given: the content is immutable, so the handler may cache it. */
  def pinned: Boolean = asOf.isDefined || asOfTag.isDefined

  def timeTravel: Boolean = asOf.isDefined || asOfTag.isDefined || asOfTs.isDefined

object RestQuery:

  /** Hard caps of the `/rows` grammar. `MaxWildcards` holds for LIKE patterns, which stay linear.
    */
  val MaxFilters       = 32
  val MaxInItems       = 64
  val MaxOrderTerms    = 16
  val MaxSelected      = 256
  val MaxValueBytes    = 4096
  val MaxWildcards     = 4
  private val IntLimit = Int.MaxValue.toLong

  val Reserved: Set[String] =
    Set(
      "select",
      "order",
      "limit",
      "offset",
      "asOf",
      "asOfTag",
      "asOfTs",
      "pool",
      "format",
      "branch"
    )

  private val AsOfNames = List("asOf", "asOfTag", "asOfTs")

  private val ReservedLower: Set[String] = Reserved.map(RestResolver.asciiLower)

  /** Whether `name` spells a reserved parameter in any ASCII case (`Limit`, `ASOF`). */
  def isReservedName(name: String): Boolean = ReservedLower.contains(RestResolver.asciiLower(name))

  /** `[not.]<op>.<value>` with a known operator, whatever the value: the shape of a filter. */
  def isFilterShaped(value: String): Boolean =
    val body = if value.startsWith("not.") then value.drop(4) else value
    val dot  = body.indexOf('.')
    dot > 0 && FilterOp.fromWire(body.substring(0, dot)).isDefined

  // limit := [1-9][0-9]{0,9}; offset := "0" | the same. No sign, no leading zero, no
  // exponent: `+1`, `01` and `1e3` are refused, not normalised.
  private val LimitRe    = "[1-9][0-9]{0,9}".r
  private val SnapshotRe = "0|[1-9][0-9]{0,18}".r

  /** Percent-decodes a raw query string exactly once, which is the representation the parser takes:
    * the `(name, value)` pairs in arrival order.
    *
    * Why the edge decodes the raw string itself rather than taking Tapir's `QueryParams`: the
    * form-urlencoded convention those decoders follow turns `+` into a space and tends to pass a
    * malformed `%` through, whereas the edge keeps `+` literal and refuses a bad `%` sequence. A
    * handler that does take Tapir's `QueryParams` can feed `params.toSeq` to [[parse]] directly; it
    * then inherits that decoder's `+` and `%` behaviour.
    *
    * Empty segments (`a=1&&b=2`) are skipped; a segment without `=` is a name with an empty value.
    */
  def decodeQueryString(raw: String): Either[RestError, Vector[(String, String)]] =
    val segments = if raw.isEmpty then Vector.empty else raw.split("&", -1).toVector
    segments
      .filter(_.nonEmpty)
      .foldLeft[Either[RestError, Vector[(String, String)]]](Right(Vector.empty)) {
        case (acc, seg) =>
          acc.flatMap { out =>
            val eq           = seg.indexOf('=')
            val (rawK, rawV) =
              if eq < 0 then (seg, "") else (seg.substring(0, eq), seg.substring(eq + 1))
            for
              k <- percentDecode(rawK)
              v <- percentDecode(rawV)
            yield out :+ (k -> v)
          }
      }

  private val BadEncoding =
    RestError.InvalidParameter("query string: invalid percent-encoding")

  private def percentDecode(s: String): Either[RestError, String] =
    if s.indexOf('%') < 0 then Right(s)
    else
      val out = new ByteArrayOutputStream(s.length)
      var i   = 0
      var bad = false
      while i < s.length && !bad do
        val c = s.charAt(i)
        if c == '%' then
          if i + 2 < s.length then
            val hi = Character.digit(s.charAt(i + 1), 16)
            val lo = Character.digit(s.charAt(i + 2), 16)
            if hi < 0 || lo < 0 then bad = true
            else
              out.write((hi << 4) | lo)
              i += 3
          else bad = true
        else
          // A char that needs no decoding: re-encode it so the byte stream stays UTF-8.
          val end = if Character.isHighSurrogate(c) && i + 1 < s.length then i + 2 else i + 1
          out.writeBytes(s.substring(i, end).getBytes(StandardCharsets.UTF_8))
          i = end
      if bad then Left(BadEncoding)
      else
        val dec = StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
        try Right(dec.decode(ByteBuffer.wrap(out.toByteArray)).toString)
        catch case _: CharacterCodingException => Left(BadEncoding)

  /** Parses decoded `(name, value)` pairs (in arrival order) into a [[RestQuery]]. */
  def parse(params: Seq[(String, String)]): Either[RestError, RestQuery] =
    val grouped  = params.groupBy(_._1)
    val filterPs = params.filterNot(p => Reserved.contains(p._1)).toVector
    val own      = params.filter(p => Reserved.contains(p._1)).toMap
    val twice    = Reserved.toList.sorted.find(n => grouped.get(n).exists(_.size > 1))
    // A parameter named like a reserved one (in any ASCII case) whose value has the shape of a
    // filter is refused outright: it is either a mis-aimed filter on a column of that name, which
    // the edge cannot serve, or a malformed parameter. Deciding it here, without the probed
    // schema, gives the same answer for every table and keeps the parameter's own meaning out of
    // the question. A reserved name in its exact spelling with an ordinary value keeps its meaning.
    val reservedFilter = params.collectFirst {
      case (name, value) if isReservedName(name) && isFilterShaped(value) =>
        RestError.reservedColumn(name)
    }
    for
      _       <- checkBytes(params)
      _       <- reservedFilter.toLeft(())
      _       <- twice.map(RestError.invalidParameter(_, "given more than once")).toLeft(())
      _       <- guard(AsOfNames.count(grouped.contains) <= 1, MultipleSelectors)
      _       <- guard(filterPs.size <= MaxFilters, TooManyFilters)
      filters <- traverse(filterPs) { case (name, value) => parseFilterExpr(name, value) }
      _       <- guard(!own.contains("branch"), branchRefused)
      select  <- own.get("select").fold(Right(None))(v => parseSelect(v).map(Some(_)))
      order   <- own.get("order").fold(Right(Vector.empty))(parseOrder)
      limit   <- own.get("limit").fold(Right(None))(v => parseLimit(v).map(Some(_)))
      offset  <- own.get("offset").fold(Right(0))(parseOffset)
      asOf    <- own.get("asOf").fold(Right(None))(v => parseAsOf(v).map(Some(_)))
      asOfTag <- own.get("asOfTag").fold(Right(None))(v => nonEmptySelector("asOfTag", v))
      asOfTs  <- own.get("asOfTs").fold(Right(None))(parseAsOfTs)
      pool    <- own.get("pool").fold(Right(None))(v => nonEmpty("pool", v))
      format  <- own.get("format").fold(Right(None))(v => nonEmpty("format", v))
      _       <- guard(offset == 0 || order.nonEmpty, RestError.OrderRequired)
    yield RestQuery(
      select,
      filters,
      order,
      limit,
      offset,
      asOf,
      asOfTag,
      asOfTs,
      pool,
      format
    )

  private val MultipleSelectors =
    RestError.InvalidSelector("supply only one of asOf, asOfTag, or asOfTs")
  private val TooManyFilters = RestError.InvalidParameter(s"at most $MaxFilters filters")

  private def guard(ok: Boolean, e: => RestError): Either[RestError, Unit] =
    if ok then Right(()) else Left(e)

  private val branchRefused =
    RestError.invalidParameter("branch", "branch reads are not supported")

  /** NUL anywhere, and the per-value byte cap, for every parameter. */
  private def checkBytes(params: Seq[(String, String)]): Either[RestError, Unit] =
    firstError(params.toList.flatMap { case (name, value) =>
      if name.isEmpty then Some(RestError.InvalidParameter("empty parameter name"))
      else if name.indexOf('\u0000') >= 0 || value.indexOf('\u0000') >= 0 then
        Some(RestError.invalidParameter(name, "NUL byte"))
      else if value.getBytes(StandardCharsets.UTF_8).length > MaxValueBytes then
        Some(RestError.invalidParameter(name, s"value exceeds $MaxValueBytes bytes"))
      else None
    })

  private def nonEmpty(name: String, v: String): Either[RestError, Option[String]] =
    if v.isEmpty then Left(RestError.invalidParameter(name, "empty value")) else Right(Some(v))

  private def nonEmptySelector(name: String, v: String): Either[RestError, Option[String]] =
    if v.isEmpty then Left(RestError.InvalidSelector(s"$name must not be empty"))
    else Right(Some(v))

  private def parseSelect(v: String): Either[RestError, Vector[String]] =
    val cols = v.split(",", -1).toVector
    if cols.exists(_.isEmpty) then Left(RestError.invalidParameter("select", "empty column"))
    else if cols.size > MaxSelected then
      Left(RestError.invalidParameter("select", s"at most $MaxSelected columns"))
    else if cols.map(RestResolver.asciiLower).distinct.size != cols.size then
      Left(RestError.invalidParameter("select", "duplicate column"))
    else Right(cols)

  /** `term := col ["." ("asc"|"desc")] ["." ("nullsfirst"|"nullslast")]`, suffixes read from the
    * right so a column name may itself contain dots.
    */
  private def parseOrder(v: String): Either[RestError, Vector[OrderTerm]] =
    val terms = v.split(",", -1).toVector
    if terms.size > MaxOrderTerms then
      Left(RestError.invalidParameter("order", s"at most $MaxOrderTerms terms"))
    else traverse(terms)(parseOrderTerm)

  private def parseOrderTerm(t: String): Either[RestError, OrderTerm] =
    def strip(s: String, suffixes: Map[String, Boolean]): (String, Option[Boolean]) =
      suffixes
        .collectFirst {
          case (sfx, flag) if s.endsWith("." + sfx) => (s.dropRight(sfx.length + 1), Some(flag))
        }
        .getOrElse((s, None))
    val (rest1, nullsFirst) = strip(t, Map("nullsfirst" -> true, "nullslast" -> false))
    val (col, desc)         = strip(rest1, Map("asc" -> false, "desc" -> true))
    if col.isEmpty then Left(RestError.invalidParameter("order", "empty column"))
    else
      Right(
        OrderTerm(
          col,
          desc.getOrElse(false),
          nullsFirst.map(if _ then NullsOrder.First else NullsOrder.Last)
        )
      )

  private def parseLimit(v: String): Either[RestError, Int] =
    if LimitRe.matches(v) && v.toLong <= IntLimit then Right(v.toInt)
    else Left(RestError.invalidParameter("limit", "expected an integer in 1..2147483647"))

  private def parseOffset(v: String): Either[RestError, Int] =
    if v == "0" then Right(0)
    else if LimitRe.matches(v) && v.toLong <= IntLimit then Right(v.toInt)
    else Left(RestError.invalidParameter("offset", "expected an integer in 0..2147483647"))

  private def parseAsOf(v: String): Either[RestError, Long] =
    if SnapshotRe.matches(v) && BigInt(v) <= Long.MaxValue then Right(v.toLong)
    else Left(RestError.InvalidSelector("asOf must be a snapshot id"))

  // Same parser (and wording) as the preview endpoint's asOfTs.
  private def parseAsOfTs(v: String): Either[RestError, Option[Instant]] =
    QueryParams
      .instantAs(Some(v), "asOfTs", "invalid_selector")
      .left
      .map { case (_, e) => RestError.InvalidSelector(e.message) }

  /** `[not.]<op>.<value>`. */
  def parseFilterExpr(name: String, raw: String): Either[RestError, Filter] =
    val negated = raw.startsWith("not.")
    val body    = if negated then raw.drop(4) else raw
    val dot     = body.indexOf('.')
    if dot < 0 then Left(RestError.invalidFilter(name, "expected [not.]<op>.<value>"))
    else
      FilterOp.fromWire(body.substring(0, dot)) match
        case None     => Left(RestError.invalidFilter(name, "unknown operator"))
        case Some(op) =>
          parseValue(name, op, body.substring(dot + 1)).map(Filter(name, negated, _))

  private def parseValue(name: String, op: FilterOp, v: String): Either[RestError, Predicate] =
    def compare(c: Comparison) =
      if v.isEmpty then Left(RestError.invalidFilter(name, "empty value"))
      else Right(Predicate.Compare(c, v))
    op match
      case FilterOp.Is =>
        v match
          case "null"  => Right(Predicate.Is(IsTarget.Null))
          case "true"  => Right(Predicate.Is(IsTarget.True))
          case "false" => Right(Predicate.Is(IsTarget.False))
          case _       => Left(RestError.invalidFilter(name, "is expects null, true or false"))
      case FilterOp.In    => parseItems(name, v).map(Predicate.Items(_))
      case FilterOp.Like  => likePattern(name, v, caseInsensitive = false)
      case FilterOp.ILike => likePattern(name, v, caseInsensitive = true)
      case FilterOp.Eq    => compare(Comparison.Eq)
      case FilterOp.Neq   => compare(Comparison.Neq)
      case FilterOp.Gt    => compare(Comparison.Gt)
      case FilterOp.Gte   => compare(Comparison.Gte)
      case FilterOp.Lt    => compare(Comparison.Lt)
      case FilterOp.Lte   => compare(Comparison.Lte)

  /** `*` is the only wildcard. Without a literal `%` or `_` the value is a LIKE pattern; with one,
    * `*` may only open or close it and the value becomes a needle ([[PatternForm]]).
    */
  private def likePattern(
      name: String,
      v: String,
      caseInsensitive: Boolean
  ): Either[RestError, Predicate] =
    val wildcards = v.count(_ == '*')
    if v.isEmpty then Left(RestError.invalidFilter(name, "empty value"))
    else if wildcards > MaxWildcards then
      Left(RestError.invalidFilter(name, s"at most $MaxWildcards wildcards"))
    else if !v.exists(c => c == '%' || c == '_') then
      Right(Predicate.Pattern(caseInsensitive, PatternForm.Like, v.replace('*', '%')))
    else
      val leading  = v.startsWith("*")
      val trailing = v.endsWith("*")
      val needle   = v.dropWhile(_ == '*').reverse.dropWhile(_ == '*').reverse
      if needle.contains('*') then
        Left(
          RestError.invalidFilter(name, "with a literal % or _, * may only start or end a pattern")
        )
      else
        val form = (leading, trailing) match
          case (false, false) => PatternForm.Equals
          case (false, true)  => PatternForm.StartsWith
          case (true, false)  => PatternForm.EndsWith
          case (true, true)   => PatternForm.Contains
        Right(Predicate.Pattern(caseInsensitive, form, needle))

  /** `"(" item ("," item)* ")"`; `item := bare | '"' quoted '"'`, quoted with `\"` and `\\`
    * escapes. A bare item is non-empty and holds none of `, ( ) "`.
    */
  private def parseItems(name: String, v: String): Either[RestError, Vector[String]] =
    val bad = Left(RestError.invalidFilter(name, "in expects (item,...)"))
    if v.length < 2 || v.head != '(' || v.last != ')' then bad
    else
      val s     = v.substring(1, v.length - 1)
      val items = Vector.newBuilder[String]
      var n     = 0
      var i     = 0
      var ok    = true
      var more  = true
      while ok && more do
        if i < s.length && s.charAt(i) == '"' then
          val sb     = new StringBuilder
          var j      = i + 1
          var closed = false
          while ok && !closed && j < s.length do
            s.charAt(j) match
              case '"'  => closed = true; j += 1
              case '\\' =>
                if j + 1 < s.length && (s.charAt(j + 1) == '"' || s.charAt(j + 1) == '\\') then
                  sb.append(s.charAt(j + 1)); j += 2
                else ok = false
              case c => sb.append(c); j += 1
          if !closed then ok = false
          items += sb.toString
          i = j
        else
          var j = i
          while j < s.length && ",()\"".indexOf(s.charAt(j)) < 0 do j += 1
          if j == i then ok = false
          items += s.substring(i, j)
          i = j
        n += 1
        if ok then
          if i == s.length then more = false
          else if s.charAt(i) == ',' then i += 1
          else ok = false
      if !ok then bad
      else if n > MaxInItems then Left(RestError.invalidFilter(name, s"at most $MaxInItems items"))
      else Right(items.result())

  private def firstError(errs: List[RestError]): Either[RestError, Unit] =
    errs.headOption.toLeft(())

  private[rest] def traverse[A, B](
      xs: Vector[A]
  )(f: A => Either[RestError, B]): Either[RestError, Vector[B]] =
    xs.foldLeft[Either[RestError, Vector[B]]](Right(Vector.empty)) { (acc, a) =>
      acc.flatMap(out => f(a).map(out :+ _))
    }
