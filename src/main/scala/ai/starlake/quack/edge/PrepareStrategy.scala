package ai.starlake.quack.edge

import java.util.Locale
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.route.StatementClassifier
import ai.starlake.sql.{SqlCommentStripper, SqlTrivia}

/** Decides how the FlightSQL `createPreparedStatement` action should obtain a `dataset_schema` for
  * a given SQL statement. The FlightSQL spec wants the Arrow schema of the future result *before*
  * the client calls `DoGet`; today's producer gets it by running the full statement and reading the
  * schema off the materialized result. For everything except a plain SELECT this is wasted work
  * (DDL/DML) or actively harmful (an INSERT runs twice).
  *
  *   - [[ai.starlake.quack.edge.PrepareStrategy.SkipExecute SkipExecute]] - the statement has no
  *     result rows of its own (DML / DDL / transaction control, answered with DuckDB's `Count`).
  *     The producer advertises that schema without touching a Quack node.
  *   - [[ai.starlake.quack.edge.PrepareStrategy.ProbeWrap ProbeWrap]] - the statement is a
  *     subquery-safe SELECT. Send `SELECT * FROM (<sql>) AS x LIMIT 0` instead; DuckDB plans the
  *     inner query but the executor short-circuits on LIMIT 0, so the Arrow stream comes back with
  *     the correct schema and zero rows.
  *   - [[ai.starlake.quack.edge.PrepareStrategy.FullExecute FullExecute]] - a single statement that
  *     reads and isn't subquery-safe (SHOW / EXPLAIN / DESCRIBE, a lone PRAGMA). The original SQL
  *     runs to read the schema off its result.
  *   - [[ai.starlake.quack.edge.PrepareStrategy.Unprobed Unprobed]] - a statement a probe could not
  *     run without running its effects: any batch of more than one statement (the node runs every
  *     statement of the text it is sent, so `SELECT 1; INSERT ...` wrote at probe time and again at
  *     Execute) and every `Other`-kind statement but a lone PRAGMA (`CALL postgres_execute(..)`,
  *     `SET VARIABLE v = (SELECT nextval(..))`, `IMPORT DATABASE`, ...), every EXPLAIN that runs
  *     the statement it explains (`EXPLAIN ANALYZE`), and every write that answers with rows of its
  *     own rather than `Count` (`INSERT ... RETURNING`, a WITH-headed write). No node call: the
  *     schema is advertised as absent and the Execute stream carries it.
  */
enum PrepareStrategy:
  case SkipExecute
  case ProbeWrap(probeSql: String)
  case FullExecute
  case Unprobed

object PrepareStrategy:

  /** Verbs in the classifier's `select` bucket that are NOT safe to wrap as a subquery. */
  private val NotSubquerySafe: Set[String] =
    Set("SHOW", "DESCRIBE", "EXPLAIN", "PRAGMA")

  def choose(sql: String, kind: StatementKind): PrepareStrategy =
    kind match
      // A write that answers with rows of its own (RETURNING, a WITH-headed statement, EXPLAIN
      // ANALYZE of a write) does not answer the advertised Count: its schema comes from the stream.
      case StatementKind.Dml =>
        if StatementClassifier.answersWithRows(sql) then Unprobed else SkipExecute
      case StatementKind.Ddl =>
        if StatementClassifier.explainExecutes(sql) then Unprobed else SkipExecute
      case StatementKind.Begin | StatementKind.Commit | StatementKind.Rollback => SkipExecute
      case _ if isMultiStatement(sql)                                          => Unprobed
      case StatementKind.Other  => if verbOf(sql) == "PRAGMA" then FullExecute else Unprobed
      case StatementKind.Select =>
        // Same composition the verb reader below goes through (`SqlTrivia.firstToken` is
        // `stripLeading -> stripComments -> normalize`), minus the normalize pass, which must NOT
        // run here: `stripped` becomes the executed probe SQL, and rewriting an interior Unicode
        // character inside a string literal would change the statement's data. Dropping the
        // leading strip is what made the two readers disagree: `kind` came back `Select` for
        // `/* a /* b */ c */ SELECT 1` (which DuckDB executes) while `stripped` still carried the
        // `c */` residue of a naively-closed nested comment, so the probe went out as
        // `SELECT * FROM (c */ SELECT 1) AS _qod_probe LIMIT 0` and DuckDB answered with a parser
        // error naming a query the caller never wrote.
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
        // (comment-stripped but NOT trivia-normalized) is still what feeds the actual probe SQL
        // below: only the verb reader may see a normalized copy.
        // EXPLAIN ANALYZE runs the statement it explains: probing it would run it once more than
        // the client asked (twice in all, sequences and costs included).
        if StatementClassifier.explainExecutes(sql) then Unprobed
        else if NotSubquerySafe.contains(verbOf(sql)) then FullExecute
        else
          val inner = stripTrailingSemicolon(stripped)
          ProbeWrap(s"SELECT * FROM ($inner) AS _qod_probe LIMIT 0")

  private def verbOf(sql: String): String =
    SqlTrivia
      .firstToken(sql)
      .takeWhile(_ != ';')
      .dropWhile(_ == '(')
      .toUpperCase(Locale.ROOT)

  /** True when the node would run more than one statement of `sql`: split at every `;` DuckDB's
    * lexer separates on, so one inside a literal, a quoted name or a comment does not count, nor
    * does a trailing one.
    */
  private def isMultiStatement(sql: String): Boolean =
    SqlCommentStripper.splitStatements(sql).lengthCompare(1) > 0

  private def stripTrailingSemicolon(s: String): String =
    var end = s.length
    while end > 0 && (s.charAt(end - 1) == ';' || s.charAt(end - 1).isWhitespace) do end -= 1
    s.substring(0, end)
