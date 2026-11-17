package ai.starlake.quack.edge.rest

import ai.starlake.quack.model.SqlLiterals

/** The SQL text the REST edge hands to the routed executor.
  *
  * Pure, and deliberately small: the edge builds ordinary statement text and lets the RLS/CLS
  * rewriters, the router and the node's decision see it like any other client's SQL. Two rules keep
  * that text safe without a parallel quoting helper:
  *
  *   - identifiers only through [[SqlLiterals.duckdbIdent]], and only names the probe returned (a
  *     [[ResolvedQuery]] cannot hold anything else) or the already-validated path segments;
  *   - values only as `CAST(<SqlLiterals.duckdbLiteral> AS <probed type>)`, so no byte of user
  *     input reaches the text outside a single-quoted literal.
  *
  * Every statement is one SELECT. The data statement orders by column NAME, never an ordinal: a
  * positional reference is a statement shape column masking cannot bind to a column by name, and
  * the edge does not produce one at all. It reads `*` when the client named no column, as the probe
  * does, and lists the columns the client named otherwise: a quoted name holding a dot, an `@`, a
  * bracket or a quote is one name to DuckDB and more to the policy rewriters' parser, which refuses
  * it for a principal holding column or row policies, so a whole-row read must not write one.
  */
object RestSql:

  /** The object a statement reads: `"<catalog>"."<schema>"."<table>"`, plus the DuckLake snapshot
    * pin when there is one. `catalog` is the session catalog from the shared resolver; this object
    * never derives it.
    */
  final case class Target(catalog: String, schema: String, table: String, snapshot: Option[Long])

  private def ident(s: String): String = SqlLiterals.duckdbIdent(s)
  private def lit(s: String): String   = SqlLiterals.duckdbLiteral(s)

  private def from(t: Target): String =
    val name = s"${ident(t.catalog)}.${ident(t.schema)}.${ident(t.table)}"
    t.snapshot.fold(name)(id => s"$name AT (VERSION => $id)")

  /** The schema probe. Its Arrow schema is the column contract after policy; `*` is right here
    * because the point is to learn what the pipeline lets this principal see.
    */
  def probe(t: Target): String = s"SELECT * FROM ${from(t)} LIMIT 0"

  /** The schema listing. `information_schema` and `pg_catalog` are never path schemas, so they are
    * not listed either. `limit` is the listing cap; one extra row is fetched so the caller can set
    * `X-QoD-Truncated`.
    */
  def listSchemas(catalog: String, limit: Int): String =
    "SELECT schema_name FROM information_schema.schemata" +
      s" WHERE catalog_name = ${lit(catalog)}" +
      " AND schema_name NOT IN ('information_schema', 'pg_catalog')" +
      s" ORDER BY schema_name LIMIT ${limit.toLong + 1}"

  /** The table listing of one schema; `table_type` is `BASE TABLE` or `VIEW`. The schema compares
    * case-insensitively, as the other routes bind it.
    */
  def listTables(catalog: String, schema: String, limit: Int): String =
    "SELECT table_name, table_type FROM information_schema.tables" +
      s" WHERE table_catalog = ${lit(catalog)} AND lower(table_schema) = lower(${lit(schema)})" +
      s" ORDER BY table_name LIMIT ${limit.toLong + 1}"

  /** The `table_type` of one object, `BASE TABLE` or `VIEW`, for the table detail: matched on its
    * name rather than looked up in a capped listing, so any object of a large schema is found.
    * Schema and name compare case-insensitively, as DuckDB binds the probe's quoted name.
    */
  def tableType(catalog: String, schema: String, name: String): String =
    "SELECT table_type FROM information_schema.tables" +
      s" WHERE table_catalog = ${lit(catalog)} AND lower(table_schema) = lower(${lit(schema)})" +
      s" AND lower(table_name) = lower(${lit(name)}) LIMIT 1"

  /** The one data statement of `/rows`. `effectiveLimit` is `ExecCaller.effectiveMaxRows` over the
    * server cap, the token cap and the request's `limit`; one extra row is fetched so the encoder
    * can tell a full page from a truncated one.
    */
  def render(t: Target, q: ResolvedQuery, effectiveLimit: Int): String =
    val cols  = if q.wholeRow then "*" else q.select.map(c => ident(c.name)).mkString(", ")
    val where =
      if q.filters.isEmpty then ""
      else q.filters.map(predicate).mkString(" WHERE ", " AND ", "")
    val order =
      if q.order.isEmpty then ""
      else q.order.map(orderTerm).mkString(" ORDER BY ", ", ", "")
    s"SELECT $cols FROM ${from(t)}$where$order LIMIT ${effectiveLimit.toLong + 1} OFFSET ${q.offset}"

  private def cast(raw: String, c: ProbedColumn): String =
    s"CAST(${lit(raw)} AS ${c.kind.sqlType})"

  /** The column as filters and orders read it: cast to the kind's type where the export folds
    * several DuckDB types into one kind. A text column may be a UUID or JSON, which have no LIKE
    * and (UUID) no range comparison with VARCHAR, or an ENUM, which would otherwise sort by its
    * position while comparing as text; a TIME may be a TIME WITH TIME ZONE, which compares with
    * TIME on its UTC instant while the edge shows its local time. The cast is a no-op on a column
    * already of that type (DuckDB adds none).
    */
  private def subject(c: ProbedColumn): String = c.kind match
    case ColumnKind.Text         => s"CAST(${ident(c.name)} AS VARCHAR)"
    case ColumnKind.Time("TIME") => s"CAST(${ident(c.name)} AS TIME)"
    case _                       => ident(c.name)

  /** The column as `eq`, `neq` and `in` read it. A text column compares bare: DuckDB casts the
    * literal to the column's own type, so a UUID matches in any spelling DuckDB accepts and its
    * filter still reaches Parquet statistics and DuckLake file pruning, which `CAST(u AS VARCHAR)`
    * defeats (a literal that is no UUID is the node's conversion error, an `invalid_filter`). A
    * VARCHAR, JSON or ENUM compares the same either way. Other kinds are read as [[subject]].
    */
  private def equalitySubject(c: ProbedColumn): String = c.kind match
    case ColumnKind.Text => ident(c.name)
    case _               => subject(c)

  private def orderTerm(o: ResolvedOrder): String =
    val dir = if o.descending then " DESC" else " ASC"
    subject(o.column) + dir + o.nulls.fold("")(" " + _.sql)

  private def predicate(f: ResolvedFilter): String =
    val col               = subject(f.column)
    def negate(p: String) = if f.negated then s"NOT ($p)" else p
    f.predicate match
      // `is` negates inside the operator (IS NOT NULL), every other operator as NOT (...).
      case Predicate.Is(target) =>
        s"${ident(f.column.name)} IS ${if f.negated then "NOT " else ""}${target.sql}"
      case Predicate.Compare(cmp @ (Comparison.Eq | Comparison.Neq), raw) =>
        negate(s"${equalitySubject(f.column)} ${cmp.sql} ${cast(raw, f.column)}")
      case Predicate.Compare(cmp, raw) => negate(s"$col ${cmp.sql} ${cast(raw, f.column)}")
      case Predicate.Items(items)      =>
        val in = s"${equalitySubject(f.column)} IN ("
        negate(items.map(cast(_, f.column)).mkString(in, ", ", ")"))
      case Predicate.Pattern(ci, form, text) =>
        // ilike folds both sides with lower() rather than ILIKE, which always runs DuckDB's
        // backtracking matcher; PatternForm records why the result is the same.
        val subject = if ci then s"lower($col)" else col
        val needle  =
          if ci then s"lower(CAST(${lit(text)} AS VARCHAR))" else s"CAST(${lit(text)} AS VARCHAR)"
        negate(form match
          case PatternForm.Like       => s"$subject LIKE $needle"
          case PatternForm.Equals     => s"$subject = $needle"
          case PatternForm.StartsWith => s"starts_with($subject, $needle)"
          case PatternForm.EndsWith   => s"ends_with($subject, $needle)"
          case PatternForm.Contains   => s"contains($subject, $needle)")
