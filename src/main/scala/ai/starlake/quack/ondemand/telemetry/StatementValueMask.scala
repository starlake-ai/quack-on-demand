package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.edge.admin.AdminSqlParser
import ai.starlake.quack.ondemand.federation.iceberg.AttachErrorRedactor

/** The value masking every statement gets when it leaves the control plane (the stdout audit sink),
  * applied in the control plane to statements the manager runs for itself (a module's system SQL):
  * history and audit are shown to the tenant's admins, and such a statement may carry platform
  * credentials (`CREATE SECRET ... SECRET '...'`, `SET s3_secret_access_key = ...`). The
  * statement's literals and bare option values are masked ([[AdminSqlParser.redactLiterals]]), and
  * an error is scrubbed of every value the statement carried, in whatever quoting the engine echoes
  * it.
  */
object StatementValueMask:

  def sql(statement: String): String = AdminSqlParser.redactLiterals(statement)

  def error(text: String, statement: String): String =
    AttachErrorRedactor.scrub(text, AdminSqlParser.quotedValues(statement))
