package ai.starlake.quack.edge.sql

import ai.starlake.quack.edge.authz.{FunctionClass, FunctionClasses}
import ai.starlake.quack.edge.authz.location.{LocationChecker, LocationRules}
import ai.starlake.sql.SqlLexer

import java.util.Locale
import scala.util.control.NonFatal

/** The file locations a tenant principal's statement may name at the gateway: those its tenant
  * authorized, judged by the decision pipeline's own [[LocationChecker]] (Task 5), so the two
  * layers apply one rule. [[FileAdmission.Closed]] admits nothing: every principal without a
  * tenant's rules gets it, and every parameter that carries one defaults to it.
  */
final case class FileAdmission(admits: String => Boolean)

object FileAdmission:

  val Closed: FileAdmission = FileAdmission(_ => false)

  /** `rules` judged by the decider's checker. `resolveLocal` is the node's resolution of a local
    * path (`LocalRealPath.of` on the local runtime, where the rules hold real paths; identity
    * elsewhere), applied only to an absolute path with clean segments: resolving a relative one, or
    * one with an empty, `.` or `..` segment, would hide what the node refuses. A failure while
    * judging admits nothing.
    */
  def of(rules: LocationRules, resolveLocal: String => String = identity): FileAdmission =
    FileAdmission { location =>
      try
        val judged = if resolvable(location) then resolveLocal(location) else location
        LocationChecker.check(judged, rules).isRight
      catch case NonFatal(_) => false
    }

  private def resolvable(location: String): Boolean =
    location.startsWith("/") && !location.contains('\\') && !location.contains('%') && {
      val segments = location.drop(1).split("/", -1).toList
      val inner    = if location.endsWith("/") then segments.dropRight(1) else segments
      inner.nonEmpty && inner.forall(seg => seg.nonEmpty && seg != "." && seg != "..")
    }

/** The two file-access shapes the gateway's file screens admit for a tenant principal (decisions 15
  * and 18 of docs/superpowers/specs/2026-10-23-in-engine-authorization-design.md), each read
  * completely from DuckDB 2.0's own tokens:
  *
  *   - a call of a [[Readers]] function in the statement's own text (never inside a string literal
  *     body) whose argument list [[FunctionCalls.locationArguments]] reads;
  *   - a COPY that is the whole submission, unwrapped, from or to one VARCHAR literal, its options
  *     judged by qod-node's own COPY option rule ([[copy]]).
  *
  * Both layers enforce until Phase D deletes these screens: the node judges the same statement on
  * what DuckDB's own parser read, resolving a local path's symlinks first, so the gateway admits
  * only what it reads completely. Everything else a file screen refused stays refused: a file in a
  * table position (the node turns replacement scans off for such a principal, decision 16),
  * `query()`'s literal, a COPY behind EXPLAIN or PREPARE or inside a batch, EXPORT, IMPORT, ATTACH
  * and LOAD.
  */
private[quack] object FileLocations:

  /** The readers a location admits: the [[FileAccessScreen.FileFunctions]] of class `File` (Task
    * 2), the set the decider checks locations for. The rest stay refused at the gateway as on the
    * node: `sql_auto_complete` (its argument is SQL text), `ducklake_scan` and the Iceberg path
    * readers (they read further files the location check never sees), and the readers of extensions
    * no node loads (unclassified).
    */
  val Readers: Set[String] = FileAccessScreen.FileFunctions.filter { name =>
    FunctionClasses.of("system", "main", name, "table").contains(FunctionClass.File)
  }

  /** A lone COPY: the `location` it names, whether it writes there (`TO`), and the statement the
    * access check parses in its place to judge the table side by grants: `INSERT INTO <table>
    * SELECT 1` for `FROM` (a write into the table), `SELECT * FROM <table>` for `<table> TO`, the
    * query itself for `(<query>) TO`.
    */
  final case class CopyUse(location: String, write: Boolean, tableSide: String)

  /** True when `call` (found by `scan`) is a [[Readers]] call of the statement's own text whose
    * every location `admission` admits.
    */
  def admitted(
      call: FunctionCalls.Call,
      scan: FunctionCalls.Scan,
      admission: FileAdmission
  ): Boolean =
    Readers(call.name) && scan.texts.headOption.contains(call.text) &&
      FunctionCalls.locationArguments(call).exists(_.forall(admission.admits))

  /** The reader names every call of which in `sql` [[admitted]] admits: the access check sets their
    * "table function" markers aside, since the location rule, not a grant, decides them.
    */
  def admittedReaderNames(sql: String, admission: FileAdmission): Set[String] =
    val scan           = FunctionCalls.scan(sql)
    val (yes, refused) = scan.calls.partition(c => admitted(c, scan, admission))
    yes.map(_.name).toSet -- refused.map(_.name)

  /** What the access check parses for `sql`: the table side of an admitted COPY, else `sql`. */
  def extractable(sql: String, admission: FileAdmission): String =
    copy(sql).filter(c => admission.admits(c.location)).fold(sql)(_.tableSide)

  /** The [[CopyUse]] of `sql` when it is exactly one statement `COPY <table> [(cols)] FROM|TO
    * '<loc>' [options]` or `COPY (<query>) TO '<loc>' [options]`, options being `(...)` or `WITH
    * (...)` that pass qod-node's COPY option rule ([[optionsAdmitted]]), so that no option names a
    * path of its own (a `FILENAME_PATTERN '../x'` would write beside the location checked). None
    * for any other statement or shape.
    */
  def copy(sql: String): Option[CopyUse] =
    LockdownScreen.splitStatements(sql) match
      case List(stmt) => copyOne(stmt)
      case _          => None

  // ---- qod-node's COPY option rule (native/qod-node/src/screen_rules.cpp, CopyOptions) ----
  // The three lists are the node's, copied verbatim; FileLocationsSpec fails until both sides
  // carry the same names.

  /** kCopyNonPathOptions: the COPY options DuckDB v2.0.0 never reads as a location, whose value is
    * free under a core format (the node's comment has the sources).
    */
  private[sql] val CopyNonPathOptions: Set[String] = Set(
    "allow_quoted_nulls",
    "append",
    "array",
    "auto_detect",
    "batch_size",
    "batch_size_bytes",
    "batches_per_file",
    "bbox",
    "binary_as_string",
    "bloom_filter_false_positive_ratio",
    "buffer_size",
    "can_have_nan",
    "chunk_size",
    "codec",
    "comment",
    "compression",
    "compression_level",
    "data_page_size_limit",
    "date_format",
    "dateformat",
    "debug_use_openssl",
    "decimal_separator",
    "delim",
    "delimiter",
    "dictionary_compression_ratio_threshold",
    "dictionary_size_limit",
    "encoding",
    "encryption_config",
    "escape",
    "field_ids",
    "file_row_number",
    "file_size_bytes",
    "force_not_null",
    "force_null",
    "force_quote",
    "geometry_column",
    "geoparquet_version",
    "header",
    "hive_file_pattern",
    "id_column",
    "ignore_errors",
    "int96_as",
    "kv_metadata",
    "max_line_size",
    "maximum_line_size",
    "new_line",
    "null",
    "null_padding",
    "nullstr",
    "order_by",
    "overwrite",
    "overwrite_or_ignore",
    "parallel",
    "parquet_version",
    "partition_by",
    "partition_path",
    "per_thread_output",
    "prefix",
    "preserve_order",
    "quote",
    "rejects_limit",
    "rejects_line_size_limit",
    "return_files",
    "return_stats",
    "row_group_size",
    "row_group_size_bytes",
    "row_groups_per_file",
    "sample_size",
    "sep",
    "separator",
    "shredding",
    "skip",
    "store_rejects",
    "strict_mode",
    "string_dictionary_page_size_limit",
    "suffix",
    "thousands",
    "timestamp_format",
    "timestamp_is_adjusted_to_utc",
    "timestampformat",
    "use_tmp_file",
    "write_bloom_filter",
    "write_empty_file",
    "write_partition_columns",
    "write_timestamp_as_int96"
  )

  /** kCoreCopyFormats: the formats [[CopyNonPathOptions]] was read from. Under any other format
    * every option must be literal and hold no path.
    */
  private[sql] val CoreCopyFormats: Set[String] =
    Set("blob", "csv", "geojson", "geojsonl", "json", "jsonl", "ndjson", "parquet")

  /** kCopyFileNameOptions: the options that spell the names of the files written, one string under
    * every format.
    */
  private[sql] val CopyFileNameOptions: Set[String] = Set("file_extension", "filename_pattern")

  private def copyOne(stmt: String): Option[CopyUse] =
    val head                            = StatementHead.of(stmt)
    val view                            = head.view
    val toks                            = head.toks
    def punct(k: Int, c: Char): Boolean =
      k < toks.length && toks(k).kind == SqlLexer.Kind.Punct && view.charAt(toks(k).start) == c
    if head.start != 0 || !head.word(0).contains("copy") then None
    else if punct(1, '(') then
      val close = closingParen(view, toks, 1)
      if close < 0 || !head.word(close + 1).contains("to") then None
      else
        val query = view.substring(toks(1).end, toks(close).start).trim
        located(view, toks, close + 2).map(CopyUse(_, write = true, query))
    else
      tableName(view, toks, 1).flatMap { (name, next) =>
        val afterColumns = if punct(next, '(') then closingParen(view, toks, next) + 1 else next
        if afterColumns <= 0 then None
        else
          head.word(afterColumns) match
            case Some("from") =>
              located(view, toks, afterColumns + 1)
                .map(CopyUse(_, write = false, s"INSERT INTO $name SELECT 1"))
            case Some("to") =>
              located(view, toks, afterColumns + 1)
                .map(CopyUse(_, write = true, s"SELECT * FROM $name"))
            case _ => None
      }

  /** The location literal at `k` when the statement ends after it, or after one option list
    * (`(...)` or `WITH (...)`) that [[optionsAdmitted]] admits.
    */
  private def located(view: String, toks: Vector[SqlLexer.Token], k: Int): Option[String] =
    def punct(i: Int, c: Char): Boolean =
      i < toks.length && toks(i).kind == SqlLexer.Kind.Punct && view.charAt(toks(i).start) == c
    def isWith(i: Int): Boolean =
      i < toks.length && toks(i).kind == SqlLexer.Kind.Word &&
        view.substring(toks(i).start, toks(i).end).equalsIgnoreCase("with")
    if k >= toks.length then None
    else
      FunctionCalls.varcharValue(view, toks(k)).filter { location =>
        val open = if isWith(k + 1) then k + 2 else k + 1
        if open == k + 1 && open >= toks.length then true
        else if !punct(open, '(') then false
        else
          val close = closingParen(view, toks, open)
          close == toks.length - 1 && optionsAdmitted(view, toks, open + 1, close, location)
      }

  /** One generic COPY option, `name [value]`: its lowercased name and its value's tokens. */
  private final case class CopyOption(name: String, value: Vector[SqlLexer.Token])

  /** The options between `from` and `until` (the tokens inside the list's parens) pass qod-node's
    * rule (screen_rules.cpp, `CopyOptions`, both directions):
    *
    *   - `FILENAME_PATTERN` and `FILE_EXTENSION`, which spell the names of the files written, are
    *     one VARCHAR literal under every format;
    *   - under a core format ([[CoreCopyFormats]]: FORMAT as given, else the location's extension
    *     past `.gz` / `.zst`, csv when it has none), a [[CopyNonPathOptions]] option holds any
    *     value;
    *   - every other option is a literal ([[literalTexts]]) and none of its texts, as spelled and
    *     as decoded, holds `/`, `\` or `..`.
    *
    * Stricter than the node only where this reader stops short of DuckDB's parser: a value that is
    * a cast, a call to `list_value` / `struct_pack` / `row` spelled as a call, or a file name
    * option given as a bare word is refused here. False for an option list it cannot split.
    */
  private def optionsAdmitted(
      view: String,
      toks: Vector[SqlLexer.Token],
      from: Int,
      until: Int,
      location: String
  ): Boolean =
    copyOptions(view, toks, from, until).exists { options =>
      val core = CoreCopyFormats(copyFormat(view, options, location))
      options.forall { o =>
        if CopyFileNameOptions(o.name) then
          o.value.length == 1 && FunctionCalls.varcharValue(view, o.value(0)).isDefined &&
          literalTexts(view, o.value).exists(_.forall(namesNoPath))
        else if core && CopyNonPathOptions(o.name) then true
        else literalTexts(view, o.value).exists(_.forall(namesNoPath))
      }
    }

  /** The options of a generic COPY option list, split at its top-level commas: `ORDER BY v`,
    * `PARTITION BY v` and `PARTITIONED BY v` (DuckDB's two-word forms, named `order_by` and
    * `partition_by`), else an identifier name, lowercased as DuckDB lowercases it, then the value.
    * None when an option is empty or does not start with a name, or a bracket does not close.
    */
  private def copyOptions(
      view: String,
      toks: Vector[SqlLexer.Token],
      from: Int,
      until: Int
  ): Option[List[CopyOption]] =
    def tokText(t: SqlLexer.Token): String            = view.substring(t.start, t.end)
    def isWord(t: SqlLexer.Token, w: String): Boolean =
      t.kind == SqlLexer.Kind.Word && tokText(t).equalsIgnoreCase(w)
    val parts = List.newBuilder[Vector[SqlLexer.Token]]
    val part  = Vector.newBuilder[SqlLexer.Token]
    var depth = 0
    var ok    = true
    var i     = from
    while ok && i < until do
      val t = toks(i)
      val s = if t.kind == SqlLexer.Kind.Punct then tokText(t) else ""
      if s == "," && depth == 0 then
        parts += part.result()
        part.clear()
      else
        if s == "(" || s == "[" || s == "{" then depth += 1
        else if s == ")" || s == "]" || s == "}" then
          depth -= 1
          ok = depth >= 0
        part += t
      i += 1
    parts += part.result()
    val split = parts.result()
    if !ok || depth != 0 then None
    else
      val options = split.map { p =>
        if p.length >= 2 && isWord(p(1), "by") &&
          (isWord(p(0), "order") || isWord(p(0), "partition") || isWord(p(0), "partitioned"))
        then
          Some(CopyOption(if isWord(p(0), "order") then "order_by" else "partition_by", p.drop(2)))
        else
          p.headOption
            .flatMap(t => FunctionCalls.identifier(view, t))
            .map(name => CopyOption(name, p.drop(1)))
      }
      Option.when(options.forall(_.isDefined))(options.flatten)

  /** The format DuckDB looks the COPY function up by (screen_rules.cpp `CopyFormat`, after
    * `Binder::BindCopyFormat` at v2.0.0): FORMAT's value as given, lowercased, else the location's
    * extension past a `.gz` or `.zst` suffix, csv when it has none. A FORMAT value this reader
    * cannot take as one name gives no core format.
    */
  private def copyFormat(view: String, options: List[CopyOption], location: String): String =
    options.find(_.name == "format") match
      case Some(o) =>
        if o.value.length != 1 then ""
        else
          val t = o.value(0)
          FunctionCalls
            .varcharValue(view, t)
            .orElse(FunctionCalls.identifier(view, t))
            .getOrElse("")
            .toLowerCase(Locale.ROOT)
      case None =>
        val lower = location.toLowerCase(Locale.ROOT)
        val bare  =
          if lower.endsWith(".gz") then lower.dropRight(3)
          else if lower.endsWith(".zst") then lower.dropRight(4)
          else lower
        val dot = bare.lastIndexOf('.')
        if dot < 0 || dot + 1 == bare.length then "csv" else bare.substring(dot + 1)

  /** No text of a COPY option may look like a path of its own (screen_rules.cpp `CopyOptionText`).
    */
  private def namesNoPath(text: String): Boolean =
    !text.contains('/') && !text.contains('\\') && !text.contains("..")

  private val MaxValueDepth = 16

  /** The texts of a COPY option value that is a literal, as qod-node's `LiteralOption` collects
    * them: nothing for no value (a flag); for a constant its text as spelled and its value (a hex
    * literal's digits and its bytes as DuckDB renders a BLOB, a bit literal's digits, a string's
    * body as written and decoded, a number, `-` before a number); an unqualified name or a `*` as
    * its own text; or a `[...]` list, a `{name: value, ...}` struct (the field names among the
    * texts) or a `(...)` row of those. None for anything computed: a call, a cast, a parameter, a
    * qualified name, an operator, a literal the reader cannot decode.
    */
  private def literalTexts(view: String, value: Vector[SqlLexer.Token]): Option[List[String]] =
    def tokText(i: Int): String             = view.substring(value(i).start, value(i).end)
    def isPunct(i: Int, s: String): Boolean =
      i < value.length && value(i).kind == SqlLexer.Kind.Punct && tokText(i) == s
    val texts = List.newBuilder[String]
    // One value from `i`: the index past it, or None.
    def one(i: Int, depth: Int): Option[Int] =
      if i >= value.length || depth > MaxValueDepth then None
      else if isPunct(i, "[") then items(i + 1, "]", depth + 1, keyed = false)
      else if isPunct(i, "{") then items(i + 1, "}", depth + 1, keyed = true)
      else if isPunct(i, "(") then
        // `()` is no row DuckDB parses
        items(i + 1, ")", depth + 1, keyed = false).filter(_ > i + 2)
      else
        val t = value(i)
        if t.kind == SqlLexer.Kind.Operator && tokText(i) == "-" then
          Option.when(value.lift(i + 1).exists(_.kind == SqlLexer.Kind.Number)) {
            texts += "-" + tokText(i + 1)
            i + 2
          }
        else if t.kind == SqlLexer.Kind.Operator && tokText(i) == "*" then
          texts += "*"
          Some(i + 1)
        else
          atom(view, t).map { found =>
            texts ++= found
            i + 1
          }
    // `value (, value)*` (or `key : value, ...` when keyed) up to the closer: the index past it.
    def items(from: Int, closer: String, depth: Int, keyed: Boolean): Option[Int] =
      if isPunct(from, closer) then Some(from + 1)
      else
        var i      = from
        var result = Option.empty[Int]
        var ok     = true
        while ok && result.isEmpty do
          val start =
            if !keyed then Some(i)
            else
              key(i).filter(_ => isPunct(i + 1, ":")).map { k =>
                texts += k
                i + 2
              }
          start.flatMap(one(_, depth)) match
            case Some(next) if isPunct(next, ",")    => i = next + 1
            case Some(next) if isPunct(next, closer) => result = Some(next + 1)
            case _                                   => ok = false
        result
    // A struct field name: a word, a quoted name or a VARCHAR literal.
    def key(i: Int): Option[String] =
      if i >= value.length then None
      else
        FunctionCalls.varcharValue(view, value(i)).orElse(FunctionCalls.identifier(view, value(i)))
    if value.isEmpty then Some(Nil)
    else one(0, 0).filter(_ == value.length).map(_ => texts.result())

  /** The texts of one token of a literal value, as spelled and as its value: a VARCHAR literal's
    * token and decoded body; a hex literal's digits and its bytes as DuckDB renders a BLOB
    * (`Blob::ToString`: a byte outside printable ASCII, a backslash or a quote as `\xNN`); a bit
    * literal's digits; a number; a word or a quoted name (a name is its own text to the binder).
    * None for any other token, and for a hex or bit literal DuckDB would refuse.
    */
  private def atom(view: String, t: SqlLexer.Token): Option[List[String]] =
    val spelled = view.substring(t.start, t.end)
    t.kind match
      case SqlLexer.Kind.Number  => Some(List(spelled))
      case SqlLexer.Kind.Word    => Some(List(spelled))
      case SqlLexer.Kind.Literal =>
        FunctionCalls
          .varcharValue(view, t)
          .orElse(FunctionCalls.quotedIdentifier(view, t))
          .map(v => List(spelled, v))
          .orElse {
            val prefix = spelled.headOption.map(_.toLower)
            val body   =
              Option.when(
                spelled.length >= 3 && spelled.charAt(1) == '\'' && spelled.endsWith("'")
              )(
                spelled.substring(2, spelled.length - 1)
              )
            body.flatMap { digits =>
              if prefix.contains('x') then
                Option.when(
                  digits.length % 2 == 0 && digits.forall(c => Character.digit(c, 16) >= 0)
                )(List(digits, blobText(digits)))
              else if prefix.contains('b') then
                Option.when(digits.forall(c => c == '0' || c == '1'))(List(digits))
              else None
            }
          }
      case _ => None

  /** The bytes hex `digits` spell, rendered as DuckDB renders a BLOB value. */
  private def blobText(digits: String): String =
    digits
      .grouped(2)
      .map { pair =>
        val b = Integer.parseInt(pair, 16)
        if b >= 32 && b <= 126 && b != '\\' && b != '\'' && b != '"' then b.toChar.toString
        else f"\\x$b%02X"
      }
      .mkString

  /** The table name chain at `k` (`part (. part)*`, each part a word or a quoted identifier) as
    * written, and the index after it.
    */
  private def tableName(
      view: String,
      toks: Vector[SqlLexer.Token],
      k: Int
  ): Option[(String, Int)] =
    def part(i: Int): Boolean = i < toks.length && FunctionCalls.identifier(view, toks(i)).isDefined
    def dot(i: Int): Boolean  =
      i < toks.length && view.substring(toks(i).start, toks(i).end) == "."
    if !part(k) then None
    else
      var last = k
      while dot(last + 1) && part(last + 2) do last += 2
      Some((view.substring(toks(k).start, toks(last).end), last + 1))

  /** The index of the paren token closing the one at `open`, or -1 when it never closes. */
  private def closingParen(view: String, toks: Vector[SqlLexer.Token], open: Int): Int =
    var depth = 0
    var i     = open
    var close = -1
    while close < 0 && i < toks.length do
      val t = toks(i)
      if t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == '(' then depth += 1
      else if t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == ')' then
        depth -= 1
        if depth == 0 then close = i
      i += 1
    close
