package ai.starlake.acl.parser

import ai.starlake.acl.model.{DenyReason, TableRef}

/** Per-statement classification of how a table is touched. Collapsed to three values that line up
  * with `ai.starlake.quack.model.StatementKind` and with the production validator's grant check:
  *
  *   - `Read` -- SELECT and any sub-query / CTE / USING source.
  *   - `Write` -- INSERT/UPDATE/DELETE/MERGE/UPSERT/TRUNCATE target.
  *   - `Ddl` -- CREATE/DROP/ALTER target.
  *
  * Granular verbs (SELECT/INSERT/UPDATE/DELETE individually) can be added later by extending this
  * enum; the validator's verb-match helper already uses string-coalesced comparison so granular
  * role-permission rows can be mapped through.
  */
enum Verb:
  case Read, Write, Ddl

/** One (table, verb) tuple extracted from a statement. A single statement may yield multiple
  * tuples: e.g. `INSERT INTO t SELECT FROM s` produces
  * `TableAccess(t, Write) ++ TableAccess(s, Read)`.
  */
final case class TableAccess(table: TableRef, verb: Verb)

enum StatementResult:
  /** Statement was parsed AND we extracted its table access set. `qualificationErrors` carries any
    * DenyReasons accumulated while resolving unqualified refs against the dialect-default
    * catalog/schema.
    */
  case Extracted(
      index: Int,
      sqlSnippet: String,
      accesses: Set[TableAccess],
      qualificationErrors: List[DenyReason] = Nil
  )

  /** Statement failed to parse or JSQLParser flagged it Unsupported. */
  case ParseError(index: Int, sqlSnippet: String, message: String)

  /** Statement parsed but carries no table references the validator cares about: COMMIT, ROLLBACK,
    * BEGIN, SET, USE, SHOW, EXPLAIN (without an inner DML), etc. The validator admits these
    * unconditionally.
    */
  case ControlFlow(index: Int, sqlSnippet: String, statementType: String)

final case class ExtractionResult(
    statements: List[StatementResult],
    allTables: Set[TableRef]
)

object ExtractionResult:
  def fromStatements(results: List[StatementResult]): ExtractionResult =
    val tables = results
      .collect { case StatementResult.Extracted(_, _, accesses, _) => accesses.map(_.table) }
      .foldLeft(Set.empty[TableRef])(_ ++ _)
    ExtractionResult(results, tables)

private[parser] def truncateSnippet(sql: String, maxLen: Int = 200): String =
  if sql.length <= maxLen then sql
  else sql.take(maxLen) + "..."
