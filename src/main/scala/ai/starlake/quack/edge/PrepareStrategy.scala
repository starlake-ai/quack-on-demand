package ai.starlake.quack.edge

import ai.starlake.quack.model.StatementKind
import ai.starlake.sql.SqlCommentStripper

/** Decides how the FlightSQL `createPreparedStatement` action should obtain a `dataset_schema` for
  * a given SQL statement. The FlightSQL spec wants the Arrow schema of the future result *before*
  * the client calls `DoGet`; today's producer gets it by running the full statement and reading the
  * schema off the materialized result. For everything except a plain SELECT this is wasted work
  * (DDL/DML) or actively harmful (an INSERT runs twice).
  *
  *   - [[SkipExecute]] - the statement has no result rows (DML / DDL / transaction control). The
  *     producer returns an empty `dataset_schema` without touching a Quack node.
  *   - [[ProbeWrap]] - the statement is a subquery-safe SELECT. Send `SELECT * FROM (<sql>) AS x
  *     LIMIT 0` instead; DuckDB plans the inner query but the executor short-circuits on LIMIT 0,
  *     so the Arrow stream comes back with the correct schema and zero rows.
  *   - [[FullExecute]] - statements that aren't subquery-safe (SHOW / EXPLAIN / DESCRIBE / PRAGMA)
  *     or that look multi-statement. Fall back to running the original SQL exactly as today.
  */
enum PrepareStrategy:
  case SkipExecute
  case ProbeWrap(probeSql: String)
  case FullExecute

object PrepareStrategy:

  /** Verbs in the classifier's `select` bucket that are NOT safe to wrap as a subquery. */
  private val NotSubquerySafe: Set[String] =
    Set("SHOW", "DESCRIBE", "EXPLAIN", "PRAGMA")

  def choose(sql: String, kind: StatementKind): PrepareStrategy =
    kind match
      case StatementKind.Dml | StatementKind.Ddl                               => SkipExecute
      case StatementKind.Begin | StatementKind.Commit | StatementKind.Rollback => SkipExecute
      case StatementKind.Other                                                 => FullExecute
      case StatementKind.Select                                                =>
        val stripped = SqlCommentStripper.stripComments(sql).trim
        val verb     = firstToken(stripped).map(_.toUpperCase).getOrElse("")
        if NotSubquerySafe.contains(verb) then FullExecute
        else if isMultiStatement(stripped) then FullExecute
        else
          val inner = stripTrailingSemicolon(stripped)
          ProbeWrap(s"SELECT * FROM ($inner) AS _qod_probe LIMIT 0")

  private def firstToken(sql: String): Option[String] =
    val trimmed = sql.trim
    if trimmed.isEmpty then None
    else Some(trimmed.takeWhile(c => !c.isWhitespace && c != ';').dropWhile(_ == '('))

  /** True when the (already-stripped) SQL contains a `;` separating two non-empty statements --
    * i.e. anything past the last trailing `;` is itself non-empty.
    */
  private def isMultiStatement(stripped: String): Boolean =
    val withoutTrailing = stripTrailingSemicolon(stripped)
    withoutTrailing.contains(';')

  private def stripTrailingSemicolon(s: String): String =
    var end = s.length
    while end > 0 && (s.charAt(end - 1) == ';' || s.charAt(end - 1).isWhitespace) do end -= 1
    s.substring(0, end)
