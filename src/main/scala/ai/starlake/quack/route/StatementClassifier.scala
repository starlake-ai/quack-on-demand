package ai.starlake.quack.route

import ai.starlake.quack.model.StatementKind
import ai.starlake.sql.SqlCommentStripper

/** Per-bucket keyword sets used to classify a statement by its first
  * non-blank token. Sets are uppercased on construction so matching is
  * case-insensitive without per-call allocation.
  *
  * Operators tune this through `quack-on-demand.statementClassifier.*`
  * (or the matching `QOD_CLASSIFIER_*` env vars). Each conf key takes a
  * comma-separated keyword list; whitespace is trimmed. Values REPLACE
  * the built-in defaults: to add a single keyword, copy the default list
  * and append. */
final case class StatementClassifierConfig(
    select:   Set[String],
    dml:      Set[String],
    ddl:      Set[String],
    begin:    Set[String],
    commit:   Set[String],
    rollback: Set[String]
):
  /** Uppercased copy so the classifier matches case-insensitively
    * without per-call allocation. `normalized` is idempotent. */
  lazy val normalized: StatementClassifierConfig = copy(
    select   = select.map(_.toUpperCase),
    dml      = dml.map(_.toUpperCase),
    ddl      = ddl.map(_.toUpperCase),
    begin    = begin.map(_.toUpperCase),
    commit   = commit.map(_.toUpperCase),
    rollback = rollback.map(_.toUpperCase)
  )

object StatementClassifierConfig:

  /** Parse a comma-separated keyword list. Whitespace around each entry
    * is trimmed. Empty entries (consecutive commas, trailing comma) are
    * dropped silently. A null or whitespace-only input yields an empty
    * set -- which collapses the matching arm to "never matches", making
    * misconfiguration fail-closed rather than fail-open. */
  def parseCsv(raw: String): Set[String] =
    if raw == null || raw.trim.isEmpty then Set.empty
    else raw.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet

  /** Built-in defaults baked into source. Edited by operators via the
    * conf keys; tests pass these directly.
    *
    * Buckets:
    *   - `select`   - read-side verbs. `WITH` covers CTE-prefixed selects.
    *                  `FROM` covers DuckDB / BigQuery FROM-first shorthand.
    *                  `EXPLAIN` is read-side by convention (an EXPLAIN of
    *                  an INSERT still doesn't mutate).
    *   - `dml`      - write-side verbs. `COPY` covers DuckDB's data-load
    *                  / data-export. `UPSERT` / `REPLACE` are dialect
    *                  variants of INSERT-with-conflict-resolution.
    *   - `ddl`      - schema-mutating. `ATTACH` / `DETACH` are DuckDB
    *                  catalog manipulation; `COMMENT ON ...` is metadata
    *                  DDL; `GRANT` / `REVOKE` mutate ACL state.
    *   - `begin`    - transaction start. `START TRANSACTION` is ANSI.
    *   - `commit`   - transaction end. `END` is the Postgres alias.
    *   - `rollback` - transaction abort. `ABORT` is the Postgres alias.
    *
    * Anything not listed falls through to `StatementKind.Other`, which
    * `RoleMatcher` routes like a read. */
  val Defaults: StatementClassifierConfig = StatementClassifierConfig(
    select   = Set("SELECT", "WITH", "VALUES", "SHOW", "DESCRIBE", "EXPLAIN", "FROM"),
    dml      = Set("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "COPY"),
    ddl      = Set("CREATE", "DROP", "ALTER", "TRUNCATE", "ATTACH", "DETACH", "COMMENT", "GRANT", "REVOKE"),
    begin    = Set("BEGIN", "START"),
    commit   = Set("COMMIT", "END"),
    rollback = Set("ROLLBACK", "ABORT")
  )

/** Cheap keyword-based classification of a SQL statement, used by the
  * routing layer to pick a Quack node role (READONLY / WRITEONLY / DUAL).
  * Does NOT involve JSQLParser -- routing wants speed and a coarse
  * three-bucket answer; authorization runs `SqlParser.extract` separately
  * and consumes its own per-`TableAccess` `Verb` enum.
  *
  * SQL comments (`--`, `/* */`) are stripped before the first-token
  * match so a leading comment doesn't make a query look like `Other`. */
final class StatementClassifier(config: StatementClassifierConfig = StatementClassifierConfig.Defaults):

  private val cfg = config.normalized

  def classify(sql: String): StatementKind =
    firstToken(SqlCommentStripper.stripComments(sql)).map(_.toUpperCase) match
      case Some(tok) if cfg.select.contains(tok)   => StatementKind.Select
      case Some(tok) if cfg.dml.contains(tok)      => StatementKind.Dml
      case Some(tok) if cfg.ddl.contains(tok)      => StatementKind.Ddl
      case Some(tok) if cfg.begin.contains(tok)    => StatementKind.Begin
      case Some(tok) if cfg.commit.contains(tok)   => StatementKind.Commit
      case Some(tok) if cfg.rollback.contains(tok) => StatementKind.Rollback
      case _                                       => StatementKind.Other

  private def firstToken(sql: String): Option[String] =
    val trimmed = sql.trim
    if trimmed.isEmpty then None
    else Some(trimmed.takeWhile(c => !c.isWhitespace && c != ';').dropWhile(_ == '('))

object StatementClassifier:

  /** Default classifier instance backed by [[StatementClassifierConfig.Defaults]].
    * Used by tests and by call sites that haven't been threaded with a
    * config-driven instance. */
  val default: StatementClassifier = new StatementClassifier(StatementClassifierConfig.Defaults)

  /** Convenience delegating to [[default]] so static-method call sites
    * (`StatementClassifier.classify(sql)`) keep working. Production
    * wiring should construct a config-driven instance via `Main.scala`
    * and pass it through `FlightSqlRouter` to use the operator's tuned
    * keyword lists. */
  def classify(sql: String): StatementKind = default.classify(sql)