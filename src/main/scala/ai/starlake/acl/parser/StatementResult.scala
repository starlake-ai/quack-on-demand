package ai.starlake.acl.parser

import ai.starlake.acl.model.{DenyReason, TableRef}

enum StatementResult:
  case Extracted(
      index: Int,
      sqlSnippet: String,
      tables: Set[TableRef],
      qualificationErrors: List[DenyReason] = Nil
  )
  case ParseError(index: Int, sqlSnippet: String, message: String)
  case NonSelect(index: Int, sqlSnippet: String, statementType: String)

final case class ExtractionResult(
    statements: List[StatementResult],
    allTables: Set[TableRef]
)

object ExtractionResult:
  def fromStatements(results: List[StatementResult]): ExtractionResult =
    val tables = results
      .collect { case StatementResult.Extracted(_, _, ts, _) => ts }
      .foldLeft(Set.empty[TableRef])(_ ++ _)
    ExtractionResult(results, tables)

private[parser] def truncateSnippet(sql: String, maxLen: Int = 200): String =
  if sql.length <= maxLen then sql
  else sql.take(maxLen) + "..."
