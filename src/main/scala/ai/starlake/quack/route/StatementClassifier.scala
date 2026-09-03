package ai.starlake.quack.route

import ai.starlake.quack.model.StatementKind
import ai.starlake.sql.SqlCommentStripper

/** Per-bucket keyword sets used to classify a statement by its first non-blank token. Sets are
  * uppercased on construction so matching is case-insensitive without per-call allocation.
  *
  * Operators tune this through `quack-on-demand.statementClassifier.*` (or the matching
  * `QOD_CLASSIFIER_*` env vars). Each conf key takes a comma-separated keyword list; whitespace is
  * trimmed. Values REPLACE the built-in defaults: to add a single keyword, copy the default list
  * and append.
  */
final case class StatementClassifierConfig(
    select: Set[String],
    dml: Set[String],
    ddl: Set[String],
    begin: Set[String],
    commit: Set[String],
    rollback: Set[String]
):
  /** Uppercased copy so the classifier matches case-insensitively without per-call allocation.
    * `normalized` is idempotent.
    */
  lazy val normalized: StatementClassifierConfig = copy(
    select = select.map(_.toUpperCase),
    dml = dml.map(_.toUpperCase),
    ddl = ddl.map(_.toUpperCase),
    begin = begin.map(_.toUpperCase),
    commit = commit.map(_.toUpperCase),
    rollback = rollback.map(_.toUpperCase)
  )

object StatementClassifierConfig:

  /** Parse a comma-separated keyword list. Whitespace around each entry is trimmed. Empty entries
    * (consecutive commas, trailing comma) are dropped silently. A null or whitespace-only input
    * yields an empty set -- which collapses the matching arm to "never matches", making
    * misconfiguration fail-closed rather than fail-open.
    */
  def parseCsv(raw: String): Set[String] =
    if raw == null || raw.trim.isEmpty then Set.empty
    else raw.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet

  /** Built-in defaults baked into source. Edited by operators via the conf keys; tests pass these
    * directly.
    *
    * Buckets:
    *   - `select` - read-side verbs. `WITH` covers CTE-prefixed selects. `FROM` covers DuckDB /
    *     BigQuery FROM-first shorthand. `EXPLAIN` is read-side by convention (an EXPLAIN of an
    *     INSERT still doesn't mutate).
    *   - `dml` - write-side verbs. `COPY` covers DuckDB's data-load / data-export. `UPSERT` /
    *     `REPLACE` are dialect variants of INSERT-with-conflict-resolution.
    *   - `ddl` - schema-mutating. `ATTACH` / `DETACH` are DuckDB catalog manipulation;
    *     `COMMENT ON ...` is metadata DDL; `GRANT` / `REVOKE` mutate ACL state.
    *   - `begin` - transaction start. `START TRANSACTION` is ANSI.
    *   - `commit` - transaction end. `END` is the Postgres alias.
    *   - `rollback` - transaction abort. `ABORT` is the Postgres alias.
    *
    * Anything not listed falls through to `StatementKind.Other`, which `RoleMatcher` routes like a
    * read.
    */
  val Defaults: StatementClassifierConfig = StatementClassifierConfig(
    select = Set("SELECT", "WITH", "VALUES", "SHOW", "DESCRIBE", "EXPLAIN", "FROM"),
    dml = Set("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "COPY"),
    ddl =
      Set("CREATE", "DROP", "ALTER", "TRUNCATE", "ATTACH", "DETACH", "COMMENT", "GRANT", "REVOKE"),
    begin = Set("BEGIN", "START"),
    commit = Set("COMMIT", "END"),
    rollback = Set("ROLLBACK", "ABORT")
  )

/** Cheap keyword-based classification of a SQL statement, used by the routing layer to pick a Quack
  * node role (READONLY / WRITEONLY / DUAL). Does NOT involve JSQLParser -- routing wants speed and
  * a coarse three-bucket answer; authorization runs `SqlParser.extract` separately and consumes its
  * own per-`TableAccess` `Verb` enum.
  *
  * SQL comments (`--`, `/* */`) are stripped before the first-token match so a leading comment
  * doesn't make a query look like `Other`.
  */
final class StatementClassifier(
    config: StatementClassifierConfig = StatementClassifierConfig.Defaults
):

  private val cfg = config.normalized

  def classify(sql: String): StatementKind =
    classifyStripped(SqlCommentStripper.stripComments(sql))

  private def classifyStripped(sql: String): StatementKind =
    firstToken(sql).map(_.toUpperCase) match
      // A WITH prefix says nothing about what the statement DOES: the verb after the
      // CTE list decides. First-token classification put WITH ... INSERT in the select
      // bucket, which routed the write to a reader node and let it skip
      // ProtectedWriteGuard, the RLS/CLS rewriters, author stamping, and the
      // data-write audit record. Only special-cased while WITH sits in the select
      // bucket, so an operator who reassigns it keeps full control.
      case Some("WITH") if cfg.select.contains("WITH") =>
        verbAfterWithClause(sql) match
          case Some(tok) =>
            kindOf(tok) match
              case StatementKind.Other => StatementKind.Select // unknown verb: legacy bucket
              case k                   => k
          case None => StatementKind.Select
      // DuckDB's EXPLAIN ANALYZE EXECUTES the inner statement; plain EXPLAIN only
      // plans it. Classify the executed statement, recursively so
      // EXPLAIN ANALYZE WITH ... INSERT resolves too.
      case Some("EXPLAIN") if cfg.select.contains("EXPLAIN") =>
        val rest = sql.trim.drop("EXPLAIN".length)
        firstToken(rest).map(_.toUpperCase) match
          case Some("ANALYZE") => classifyStripped(rest.trim.drop("ANALYZE".length))
          case _               => StatementKind.Select
      case Some(tok) => kindOf(tok)
      case None      => StatementKind.Other

  private def kindOf(tok: String): StatementKind =
    if cfg.select.contains(tok) then StatementKind.Select
    else if cfg.dml.contains(tok) then StatementKind.Dml
    else if cfg.ddl.contains(tok) then StatementKind.Ddl
    else if cfg.begin.contains(tok) then StatementKind.Begin
    else if cfg.commit.contains(tok) then StatementKind.Commit
    else if cfg.rollback.contains(tok) then StatementKind.Rollback
    else StatementKind.Other

  private def firstToken(sql: String): Option[String] =
    val trimmed = sql.trim
    if trimmed.isEmpty then None
    else Some(trimmed.takeWhile(c => !c.isWhitespace && c != ';').dropWhile(_ == '('))

  /** The first depth-0 keyword token after the leading WITH's CTE list, uppercased: the statement's
    * real verb. The scan is quote-aware (single-quoted literals with '' doubling and backslash
    * escapes in e'...' strings, double-quoted identifiers with "" doubling, dollar-quoted
    * $tag$...$tag$ strings) so parens or verbs inside a CTE body's literals cannot desync it. CTE
    * names, AS, RECURSIVE, MATERIALIZED and column lists are all either non-keyword words or
    * parenthesized groups, so the first token that matches a configured bucket IS the verb
    * (reserved words cannot be unquoted CTE names). None when the scan runs out, which the caller
    * maps to the legacy select bucket.
    */
  private def verbAfterWithClause(sql: String): Option[String] =
    val s = sql.trim
    val n = s.length
    // start just past the leading WITH token
    var i = 0
    while i < n && !s(i).isWhitespace do i += 1
    def isWordChar(c: Char) = c.isLetterOrDigit || c == '_'
    def skipSingle(from: Int, eString: Boolean): Int =
      // from points at the opening quote; returns index just past the closing quote
      var p = from + 1
      while p < n do
        if eString && s(p) == '\\' then p += 2
        else if s(p) == '\'' then
          if p + 1 < n && s(p + 1) == '\'' then p += 2
          else return p + 1
        else p += 1
      n
    def skipDouble(from: Int): Int =
      var p = from + 1
      while p < n do
        if s(p) == '"' then
          if p + 1 < n && s(p + 1) == '"' then p += 2
          else return p + 1
        else p += 1
      n
    def skipDollar(from: Int): Int =
      // from points at the opening '$'; the tag runs to the next '$'
      var t = from + 1
      while t < n && isWordChar(s(t)) do t += 1
      if t >= n || s(t) != '$' then from + 1 // lone '$': not a dollar-quote, step over it
      else
        val tag   = s.substring(from, t + 1)
        val close = s.indexOf(tag, t + 1)
        if close < 0 then n else close + tag.length
    var depth = 0
    while i < n do
      val c = s(i)
      if c == '\'' then
        val ePrefixed = i > 0 && (s(i - 1) == 'e' || s(i - 1) == 'E') &&
          (i < 2 || !isWordChar(s(i - 2)))
        i = skipSingle(i, ePrefixed)
      else if c == '"' then i = skipDouble(i)
      else if c == '$' then i = skipDollar(i)
      else if c == '(' then
        depth += 1; i += 1
      else if c == ')' then
        depth -= 1; i += 1
      else if depth == 0 && isWordChar(c) then
        var w = i
        while w < n && isWordChar(s(w)) do w += 1
        val word = s.substring(i, w).toUpperCase
        if kindOf(word) != StatementKind.Other then return Some(word)
        i = w
      else i += 1
    None

object StatementClassifier:

  /** Default classifier instance backed by [[StatementClassifierConfig.Defaults]]. Used by tests
    * and by call sites that haven't been threaded with a config-driven instance.
    */
  val default: StatementClassifier = new StatementClassifier(StatementClassifierConfig.Defaults)

  /** Convenience delegating to [[default]] so static-method call sites
    * (`StatementClassifier.classify(sql)`) keep working. Production wiring should construct a
    * config-driven instance via `Main.scala` and pass it through `FlightSqlRouter` to use the
    * operator's tuned keyword lists.
    */
  def classify(sql: String): StatementKind = default.classify(sql)
