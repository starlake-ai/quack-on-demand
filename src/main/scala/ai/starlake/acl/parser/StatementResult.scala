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
    * catalog/schema. `unsupported` carries a marker for every construct the walker could not map to
    * a grantable table (table functions, file refs, unrecognized node types); a non-empty list
    * means `accesses` is INCOMPLETE and the validator must fail closed.
    */
  case Extracted(
      index: Int,
      sqlSnippet: String,
      accesses: Set[TableAccess],
      qualificationErrors: List[DenyReason] = Nil,
      unsupported: List[String] = Nil
  )

  /** Statement failed to parse, JSQLParser flagged it Unsupported, or its type is not on the
    * explicit control-flow allowlist (fail-closed default for unrecognized statement types).
    */
  case ParseError(index: Int, sqlSnippet: String, message: String)

  /** Statement parsed to a type on the explicit no-table-refs allowlist: COMMIT, ROLLBACK,
    * SAVEPOINT, SET, RESET, USE, SHOW, DESCRIBE. The validator admits these unconditionally.
    * Statement types NOT on the allowlist are reported as [[ParseError]] instead, so the validator
    * fails closed on anything the parser does not positively recognize as table-free.
    */
  case ControlFlow(index: Int, sqlSnippet: String, statementType: String)

final case class ExtractionResult(
    statements: List[StatementResult],
    allTables: Set[TableRef]
)

object ExtractionResult:
  def fromStatements(results: List[StatementResult]): ExtractionResult =
    val tables = results
      .collect { case StatementResult.Extracted(_, _, accesses, _, _) => accesses.map(_.table) }
      .foldLeft(Set.empty[TableRef])(_ ++ _)
    ExtractionResult(results, tables)

private[parser] def truncateSnippet(sql: String, maxLen: Int = 200): String =
  if sql.length <= maxLen then sql
  else sql.take(maxLen) + "..."
