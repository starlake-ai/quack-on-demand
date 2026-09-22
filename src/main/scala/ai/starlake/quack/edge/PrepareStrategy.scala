package ai.starlake.quack.edge

import java.util.Locale
import ai.starlake.quack.model.StatementKind
import ai.starlake.sql.{SqlCommentStripper, SqlTrivia}

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
        // Same composition the verb reader below goes through (`SqlTrivia.firstToken` is
        // `stripLeading -> stripComments -> normalize`), minus the normalize pass, which must NOT
        // run here: `stripped` becomes the executed probe SQL, and rewriting an interior Unicode
        // character inside a string literal would change the statement's data. Dropping the
        // leading strip is what made the two readers disagree: `kind` came back `Select` for
        // `/* a /* b */ c */ SELECT 1` (which DuckDB executes) while `stripped` still carried the
        // `c */` residue of a naively-closed nested comment, so the probe went out as
        // `SELECT * FROM (c */ SELECT 1) AS _qod_probe LIMIT 0` and DuckDB answered with a parser
        // error naming a query the caller never wrote. `isMultiStatement` reads the same string,
        // so it miscounted a `;` leaked out of a comment body the same way.
        val stripped = SqlCommentStripper.stripComments(SqlTrivia.stripLeading(sql)).trim
        // Reads its own verb through the one shared reader (`SqlTrivia.firstToken`) instead of a
        // hand-rolled takeWhile of its own -- that hand-rolled version read RAW `sql`, disagreeing
        // with `StatementClassifier`'s (normalized) verdict on `kind` the moment either a leading
        // or interior trivia character sat in front of a `NotSubquerySafe` verb: `kind` came back
        // Select (correctly), but the old reader's own first token still carried the trivia
        // character and matched no entry in `NotSubquerySafe`, so a BOM-prefixed `EXPLAIN` took
        // the `ProbeWrap` path instead of `FullExecute` and DuckDB rejected the resulting
        // `SELECT * FROM (<BOM>EXPLAIN ...) LIMIT 0` with a parser error -- a previously-working
        // prepared statement broken by this same class of gap, not fixed by it. `stripped` itself
        // (comment-stripped but NOT trivia-normalized) is still what feeds `isMultiStatement` and
        // the actual probe SQL below: only the verb reader may see a normalized copy.
        val verb = SqlTrivia
          .firstToken(sql)
          .takeWhile(_ != ';')
          .dropWhile(_ == '(')
          .toUpperCase(Locale.ROOT)
        if NotSubquerySafe.contains(verb) then FullExecute
        else if isMultiStatement(stripped) then FullExecute
        else
          val inner = stripTrailingSemicolon(stripped)
          ProbeWrap(s"SELECT * FROM ($inner) AS _qod_probe LIMIT 0")

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
