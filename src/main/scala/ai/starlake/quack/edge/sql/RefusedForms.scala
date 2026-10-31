package ai.starlake.quack.edge.sql

import ai.starlake.quack.model.SqlLiterals
import ai.starlake.sql.SqlLexer

import java.util.Locale

/** Statements qod-node refuses to a tenant principal whose answer the gateway can get from it
  * another way (Task 19 part A): DESCRIBE, `SHOW <table>`, `PRAGMA table_info` and `PRAGMA show`,
  * which DuckDB v2.0.0 binds to the superuser-class `pragma_show` / `pragma_table_info`; SUMMARIZE,
  * whose source the node's parse screen cannot see in a plan (bind_showref.cpp); and SHOW TABLES /
  * SHOW ALL TABLES, which bind to listings the edge metadata filter cannot narrow.
  *
  * The gateway decides nothing here. It recognizes a small fixed set of shapes on DuckDB's own
  * tokens ([[SqlLexer]], never JSqlParser, so a parser bump cannot change what is recognized) and
  * turns each into an ordinary SELECT the client could have written itself: the listings the
  * metadata filter narrows (`information_schema.columns`, `duckdb_tables()`, `duckdb_views()`),
  * and, for every form that describes one object, `NOT EXISTS (SELECT 1 FROM <c>.<s>.<t> LIMIT 0)`,
  * a read of that object in the plan, so the node requires a read grant on it (a secure view: its
  * own grant). The result then goes through the gateway's policy screens and the node like any
  * client statement. A misread name answers wrongly or is refused, never reaches more: whatever the
  * rewrite names, the client could have named. Anything else, `DESCRIBE (<query>)` and
  * `SUMMARIZE (<query>)` included (their rows would need a result schema the manager cannot encode
  * on the native front door), is None and reaches the node unchanged.
  *
  * Names are qualified with the session's catalog and schema (the router's USE prelude, `path`)
  * only where the client left them out: `t` is `<catalog>.<schema>.t` and a two-part `s.t` is
  * `<catalog>.s.t`, so the EXISTS read and the listing's WHERE always name the same object; a
  * two-part name whose head names a catalog (the session's or an attached one, which DuckDB may
  * bind catalog-first) is left unchanged rather than guessed. SHOW TABLES and SHOW ALL TABLES are
  * rewritten only when the metadata filter narrows the principal's listings (`listingsNarrowed`),
  * the reason they are rewritten at all; otherwise the node's own SHOW TABLES runs as before. The
  * WHERE compares names in lower case, as DuckDB's catalog does (and as its own SHOW TABLES query
  * does, pragma_queries.cpp).
  */
private[quack] object RefusedForms:

  /** The session's current catalog and schema: the target of the router's USE prelude. `catalogs`:
    * the catalog names a two-part name's head may name instead of a schema.
    */
  final case class SessionPath(catalog: String, schema: String, catalogs: Set[String] = Set.empty)

  /** One object, fully qualified, each part as the client wrote it (unquoted). */
  final case class Target(catalog: String, schema: String, name: String):
    def sql: String = List(catalog, schema, name).map(SqlLiterals.duckdbIdent).mkString(".")

  enum Form:
    /** Send `sql` in place of the client's statement. */
    case Rewritten(sql: String)

    /** SUMMARIZE `target`: run `columns` (the DESCRIBE rewrite) on the node to learn the columns as
      * `(column_name, column_type)` rows, then send [[summarizeSql]] of them.
      */
    case Summarize(columns: String, target: Target)

  /** The form of `sql`, or None to send it unchanged. `isSetting` names a setting (`SHOW <name>` of
    * a setting is not rewritten: `duckdb_settings()` and `current_setting()` are superuser-class).
    */
  def recognize(
      sql: String,
      session: Option[SessionPath],
      isSetting: String => Boolean,
      listingsNarrowed: Boolean = true,
      attachedCatalogs: Set[String] = Set.empty
  ): Option[Form] =
    val path = session.map(p => p.copy(catalogs = (attachedCatalogs + p.catalog).map(lower)))
    val view = SqlLexer.engineView(Option(sql).getOrElse(""))
    val all  = SqlLexer.tokens(view).filterNot(_.isTrivia)
    // One statement: a single trailing `;` at most.
    val toks =
      if all.nonEmpty && text(view, all.last) == ";" then all.dropRight(1) else all
    if toks.isEmpty || toks.exists(t => text(view, t) == ";") then None
    else
      val r = Reader(view, toks)
      r.word(0) match
        case Some("describe") | Some("desc") =>
          r.target(r.skipWord(1, "table"), path).map(t => Form.Rewritten(describe(t)))
        case Some("summarize") =>
          r.target(r.skipWord(1, "table"), path).map(t => Form.Summarize(describe(t), t))
        case Some("show") =>
          r.word(1) match
            case Some("tables") if toks.length == 2 =>
              path.filter(_ => listingsNarrowed).map(p => Form.Rewritten(showTables(p)))
            case Some("all") if toks.length == 3 && r.word(2).contains("tables") =>
              path.filter(_ => listingsNarrowed).map(p => Form.Rewritten(showAllTables(p)))
            case _ =>
              // SHOW <name> of a table (DuckDB's AUTO show_behavior describes a table that
              // exists, else shows a setting); DuckDB's own listing names are left alone.
              val oneWord = toks.length == 2 && r.word(1).isDefined
              val special = r.word(1).exists(w => ShowListings(w) || (oneWord && isSetting(w)))
              if special then None
              else r.target(1, path).map(t => Form.Rewritten(describe(t)))
        case Some("pragma") =>
          pragma(view, toks, r, path)
        case _ => None

  /** `PRAGMA table_info(<name>)` and `PRAGMA "show"(<name>)` (`show` is reserved in DuckDB 2.0, so
    * it must be quoted): one argument, a string literal holding a qualified name (parsed as
    * DuckDB's `QualifiedName::Parse` does) or an identifier chain.
    */
  private def pragma(
      view: String,
      toks: Vector[SqlLexer.Token],
      r: Reader,
      path: Option[SessionPath]
  ): Option[Form] =
    val name = r.part(1).map(_.toLowerCase(Locale.ROOT))
    if !(name.contains("table_info") || name.contains("show")) || !r.punct(2, '(') ||
      !r.punct(toks.length - 1, ')')
    then None
    else
      val inner  = toks.slice(3, toks.length - 1)
      val string = inner.headOption.filter(_ => inner.length == 1).flatMap { t =>
        FunctionCalls.varcharValue(view, t)
      }
      val target = string match
        case Some(name) => qualifiedParts(name).flatMap(parts => qualify(parts, path))
        case None       => Reader(view, inner).target(0, path)
      target.map { t =>
        if name.contains("table_info") then Form.Rewritten(tableInfo(t))
        else Form.Rewritten(describe(t))
      }

  /** The one-word SHOW forms DuckDB answers with a listing of its own (bind_showref.cpp). */
  private val ShowListings = Set("databases", "schemas", "variables", "tables", "all")

  private def text(view: String, t: SqlLexer.Token): String = view.substring(t.start, t.end)

  /** Reads tokens of one statement: words, parts of a name, punctuation. */
  private final case class Reader(view: String, toks: Vector[SqlLexer.Token]):

    def word(k: Int): Option[String] =
      if k < toks.length && toks(k).kind == SqlLexer.Kind.Word then
        Some(text(view, toks(k)).toLowerCase(Locale.ROOT))
      else None

    def punct(k: Int, c: Char): Boolean =
      k < toks.length && toks(k).kind == SqlLexer.Kind.Punct && view.charAt(toks(k).start) == c

    /** Past an optional `w` at `k`. */
    def skipWord(k: Int, w: String): Int =
      if word(k).contains(w) && k + 1 < toks.length then k + 1 else k

    /** One part of a name at `k`, as written: a word, or a double-quoted or backtick-quoted
      * identifier with its delimiter undoubled.
      */
    def part(k: Int): Option[String] =
      if k >= toks.length then None
      else
        val t = toks(k)
        t.kind match
          case SqlLexer.Kind.Word    => Some(text(view, t))
          case SqlLexer.Kind.Literal =>
            val d    = view.charAt(t.start)
            val body = text(view, t)
            if (d == '"' || d == '`') && body.length >= 2 && body.last == d then
              Some(body.substring(1, body.length - 1).replace(s"$d$d", d.toString))
                .filter(_.nonEmpty)
            else None
          case _ => None

    private def dot(k: Int): Boolean = k < toks.length && text(view, toks(k)) == "."

    /** The name from `k` to the end of the statement: one to three dot-separated parts. */
    def target(k: Int, path: Option[SessionPath]): Option[Target] =
      val parts = List.newBuilder[String]
      var i     = k
      var ok    = part(i).isDefined
      while ok && i < toks.length do
        parts += part(i).get
        if i + 1 == toks.length then i += 1
        else if dot(i + 1) && part(i + 2).isDefined then i += 2
        else ok = false
      if ok && i == toks.length then qualify(parts.result(), path) else None

  /** `parts` (one to three) qualified from `path` where the client left a part out. */
  private def qualify(parts: List[String], path: Option[SessionPath]): Option[Target] =
    parts match
      case List(c, s, t) => Some(Target(c, s, t))
      case List(s, t)    =>
        path.filter(p => !p.catalogs(lower(s))).map(p => Target(p.catalog, s, t))
      case List(t) => path.map(p => Target(p.catalog, p.schema, t))
      case _       => None

  /** A qualified name in a string, as DuckDB's `QualifiedName::Parse` reads it: `.` separates the
    * parts; a part is either wholly double-quoted (`""` for a quote, and it may hold dots) or holds
    * no quote. None for an empty part, a stray quote or text after a closing quote.
    */
  private[sql] def qualifiedParts(input: String): Option[List[String]] =
    val parts = List.newBuilder[String]
    var i     = 0
    var ok    = input.nonEmpty
    while ok && i < input.length do
      val entry = new StringBuilder
      if input.charAt(i) == '"' then
        i += 1
        var closed = false
        while !closed && i < input.length do
          if input.charAt(i) != '"' then entry.append(input.charAt(i))
          else if i + 1 < input.length && input.charAt(i + 1) == '"' then
            entry.append('"')
            i += 1
          else closed = true
          i += 1
        ok = closed && (i == input.length || input.charAt(i) == '.')
      else
        while i < input.length && input.charAt(i) != '.' do
          ok = ok && input.charAt(i) != '"'
          entry.append(input.charAt(i))
          i += 1
      ok = ok && entry.nonEmpty
      parts += entry.result()
      i += 1 // past the dot
    if ok then Some(parts.result()) else None

  private def lit(s: String): String = SqlLiterals.duckdbLiteral(s)

  private def lower(s: String): String = s.toLowerCase(Locale.ROOT)

  /** The rows of `t`'s columns in `information_schema.columns`, and the read of `t` that puts it in
    * the plan: `NOT EXISTS` over an empty read is always true, and binding it requires `t` to
    * exist. information_schema rather than `duckdb_columns()`: the metadata filter narrows both,
    * but the column policy rewriter resolves information_schema's columns and cannot model a table
    * function next to a table, so beside the read of `t` a listing through `duckdb_columns()` was
    * refused to every principal holding a column policy.
    */
  private def columnsOf(t: Target): String =
    s"FROM information_schema.columns WHERE lower(table_catalog) = lower(${lit(t.catalog)}) " +
      s"AND lower(table_schema) = lower(${lit(t.schema)}) " +
      s"AND lower(table_name) = lower(${lit(t.name)}) " +
      s"AND NOT EXISTS (SELECT 1 FROM ${t.sql} LIMIT 0) ORDER BY ordinal_position"

  /** DESCRIBE's columns (`column_name`, `column_type`, `null`, `key`, `default`, `extra`). `key` is
    * always NULL: the primary and unique keys are in `duckdb_constraints()`, which is
    * superuser-class.
    */
  def describe(t: Target): String =
    "SELECT column_name, data_type AS column_type, is_nullable AS \"null\", " +
      "NULL::VARCHAR AS \"key\", column_default AS \"default\", NULL::VARCHAR AS extra " +
      columnsOf(t)

  /** `PRAGMA table_info`'s columns (`cid`, `name`, `type`, `notnull`, `dflt_value`, `pk`); `pk` is
    * NULL for the same reason as DESCRIBE's `key`.
    */
  def tableInfo(t: Target): String =
    "SELECT CAST(ordinal_position - 1 AS INTEGER) AS cid, column_name AS name, data_type AS type, " +
      "is_nullable = 'NO' AS notnull, column_default AS dflt_value, NULL::BOOLEAN AS pk " +
      columnsOf(t)

  private def inSchema(p: SessionPath): String =
    s"lower(database_name) = lower(${lit(p.catalog)}) AND lower(schema_name) = lower(${lit(p.schema)})"

  /** SHOW TABLES: the tables and views of the session schema, as one `name` column. */
  def showTables(p: SessionPath): String =
    s"SELECT table_name AS name FROM duckdb_tables() WHERE ${inSchema(p)} UNION ALL " +
      s"SELECT view_name AS name FROM duckdb_views() WHERE ${inSchema(p)} ORDER BY name"

  /** SHOW ALL TABLES: DuckDB's own expanded listing (pragma_queries.cpp,
    * `PragmaShowTablesExpanded`), on the catalog functions the filter narrows; `NOT internal` is
    * what DuckDB's parenless `duckdb_tables` / `duckdb_views` views add (default_views.cpp).
    */
  def showAllTables(p: SessionPath): String =
    "SELECT t.database_name AS \"database\", t.schema_name AS \"schema\", t.table_name AS name, " +
      "list(c.column_name ORDER BY c.column_index) AS column_names, " +
      "list(c.data_type ORDER BY c.column_index) AS column_types, " +
      "first(t.temporary) AS temporary " +
      "FROM duckdb_tables() t JOIN duckdb_columns() c USING (table_oid) WHERE NOT t.internal " +
      "GROUP BY \"database\", \"schema\", name UNION ALL " +
      "SELECT v.database_name AS \"database\", v.schema_name AS \"schema\", v.view_name AS name, " +
      "list(c.column_name ORDER BY c.column_index) AS column_names, " +
      "list(c.data_type ORDER BY c.column_index) AS column_types, " +
      "first(v.temporary) AS temporary " +
      "FROM duckdb_views() v JOIN duckdb_columns() c ON (v.view_oid = c.table_oid) " +
      "WHERE NOT v.internal GROUP BY \"database\", \"schema\", name ORDER BY \"database\", \"schema\", name"

  /** DuckDB's `LogicalType::IsNumeric`, by the type's name in `duckdb_columns().data_type`. */
  private val NumericTypes = Set(
    "TINYINT",
    "SMALLINT",
    "INTEGER",
    "BIGINT",
    "HUGEINT",
    "FLOAT",
    "DOUBLE",
    "UTINYINT",
    "USMALLINT",
    "UINTEGER",
    "UBIGINT",
    "UHUGEINT"
  )

  /** DuckDB's `LogicalType::IsTemporal`, by name. */
  private val TemporalTypes = Set(
    "DATE",
    "TIME",
    "TIME_NS",
    "TIMESTAMP",
    "TIME WITH TIME ZONE",
    "TIMESTAMP WITH TIME ZONE",
    "TIMESTAMP_S",
    "TIMESTAMP_MS",
    "TIMESTAMP_NS"
  )

  private def numeric(tpe: String): Boolean = NumericTypes(tpe) || tpe.startsWith("DECIMAL(")

  /** SUMMARIZE of `t`'s `columns` (`(name, type)` in column order): the query DuckDB v2.0.0 builds
    * itself (bind_summarize.cpp), one scan of `SELECT * FROM t` with one `unnest(list_value(...))`
    * per output column, so its values and row order are DuckDB's own. Every function in it is
    * classed allowed.
    */
  def summarizeSql(t: Target, columns: List[(String, String)]): String =
    def unnest(items: List[String], alias: String): String =
      s"unnest(list_value(${items.mkString(", ")})) AS ${SqlLiterals.duckdbIdent(alias)}"
    def agg(fn: String, c: String, modifier: String = ""): String =
      s"CAST($fn(${SqlLiterals.duckdbIdent(c)}$modifier) AS VARCHAR)"
    def when(cond: Boolean, expr: => String): String = if cond then expr else "NULL"
    val names                                        = columns.map((c, _) => lit(c))
    val types                                        = columns.map((_, tpe) => lit(tpe))
    val mins                                         = columns.map((c, _) => agg("min", c))
    val maxs                                         = columns.map((c, _) => agg("max", c))
    val uniques = columns.map((c, _) => s"CAST(${agg("approx_count_distinct", c)} AS BIGINT)")
    val avgs    = columns.map((c, tpe) => when(numeric(tpe) || TemporalTypes(tpe), agg("avg", c)))
    val stds    = columns.map((c, tpe) => when(numeric(tpe), agg("stddev", c)))
    def quantile(q: String) = columns.map((c, tpe) =>
      when(numeric(tpe) || TemporalTypes(tpe), agg("approx_quantile", c, s", CAST($q AS FLOAT)"))
    )
    val counts = columns.map(_ => "count_star()")
    val nulls  = columns.map((c, _) =>
      "CAST(CASE WHEN count_star() > CAST(0 AS BIGINT) THEN (CAST(1 AS DOUBLE) - " +
        s"CAST(${agg("count", c)} AS DOUBLE) / CAST(count_star() AS DOUBLE)) * " +
        "CAST(100 AS DOUBLE) ELSE NULL END AS DECIMAL(9, 2))"
    )
    List(
      unnest(names, "column_name"),
      unnest(types, "column_type"),
      unnest(mins, "min"),
      unnest(maxs, "max"),
      unnest(uniques, "approx_unique"),
      unnest(avgs, "avg"),
      unnest(stds, "std"),
      unnest(quantile("0.25"), "q25"),
      unnest(quantile("0.5"), "q50"),
      unnest(quantile("0.75"), "q75"),
      unnest(counts, "count"),
      unnest(nulls, "null_percentage")
    ).mkString("SELECT ", ", ", s" FROM (SELECT * FROM ${t.sql}) AS summarize_tbl")
