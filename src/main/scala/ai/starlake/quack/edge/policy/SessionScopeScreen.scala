package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.sql.StatementHead
import ai.starlake.sql.{SqlCommentStripper, SqlLexer, SqlTrivia}

/** Refuses a multi-statement submission that changes the session's schema or search path, for a
  * principal with column or row policies (the protected-write guard, spec decision 27).
  *
  * The policy rewriters qualify every unqualified table name against the pool's default schema, and
  * the router pins that schema on the node with a `USE <db>.<schema>` prelude. A `USE`,
  * `SET search_path` or `SET schema` INSIDE the submission runs after the prelude, so DuckDB
  * resolves every later bare name against a schema (or catalog) the policies were not matched
  * against: `SET search_path='db.other'; SELECT * FROM t` reads `db.other.t` while the row filter
  * was applied to `db.def.t`. So the statement is refused when it shares a submission with anything
  * else; sent on its own it is unchanged (control flow, and the next statement gets the prelude
  * again). Every other principal is judged by the node on what it resolves.
  *
  * Pure and token-based on purpose: it is split by
  * [[ai.starlake.quack.edge.sql.StatementHead.split]] and each statement is read from DuckDB 2.0's
  * own tokens ([[StatementHead]], the setting name through
  * [[ai.starlake.quack.edge.sql.StatementHead.settingAt]], the one setting reader every screen
  * shares), so neither a comment, a string, a quoted identifier (double quotes or backticks, glued
  * to the keyword or not) nor a dollar-quoted body can hide a separator or a verb, the statement an
  * `EXPLAIN ANALYZE` carries is judged, and it never depends on whether JSqlParser can parse the
  * statement (it cannot parse `SET x TO`, `PRAGMA`, or a two-part `USE`).
  *
  * Recognized, fail closed: `USE ...` (any form); `SET` / `RESET`, optionally `SESSION` / `GLOBAL`
  * / `LOCAL`, of `search_path`, `schema` or `ALL`, in any quoting and case, and a `SET` / `RESET`
  * whose setting name cannot be read; `PRAGMA search_path` / `PRAGMA schema`. `SET VARIABLE` sets a
  * variable even when it is called `search_path`, and other settings do not move name resolution
  * (checked against DuckDB 1.5.6 and 2.0), so both stay batchable.
  */
object SessionScopeScreen:

  /** The DuckDB settings that decide where an unqualified name resolves. */
  private val ScopeSettings = Set("search_path", "schema")

  /** Some(reason) when the submission must be refused. */
  def find(sql: String): Option[String] =
    // A fragment holding only comments runs nothing on DuckDB: `USE x; -- done` is one statement.
    val statements =
      StatementHead.split(sql).filter(s => SqlLexer.tokens(s).exists(!_.isTrivia))
    if statements.lengthCompare(2) < 0 then None
    else statements.find(changesScope).map(reason)

  private def reason(statement: String): String =
    val shown = SqlTrivia.normalize(SqlCommentStripper.stripComments(statement)).trim
    val brief = if shown.length > 120 then shown.take(120) + "..." else shown
    "USE, SET search_path and SET schema must be sent as their own statement, not batched with " +
      "other statements: table names after them are authorized against the pool's default " +
      s"schema, not the one they select ($brief)"

  /** True when `statement` (one top-level statement, as
    * [[ai.starlake.quack.edge.sql.StatementHead.split]] cuts it) moves name resolution.
    */
  private[policy] def changesScope(statement: String): Boolean =
    val h = StatementHead.of(statement)
    h.word(h.start) match
      case Some("use")           => true
      case Some("set" | "reset") =>
        StatementHead.settingAt(h) match
          case None            => true
          case Some((name, _)) => name == "all" || ScopeSettings.contains(name)
      case Some("pragma") =>
        StatementHead.settingAt(h).exists((name, _) => ScopeSettings.contains(name))
      case _ => false
