package ai.starlake.quack.ondemand.module

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.admin.AdminSqlParser
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.api.{CatalogPreviewHandlers, ExecCaller}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder, StatementValueMask}
import ai.starlake.quack.spi.SystemSql
import cats.effect.IO

import scala.util.control.NonFatal

/** Builds a module's [[SystemSql]]: one statement through the routed executor as
  * [[ExecCaller.system]], labelled with the module's name and audited.
  *
  * Main builds one per module, bound to `ManagerModule.name`, over the routed executor with
  * `recordExecution = true`: the statement reaches statement history and the write audit under the
  * label `module:<name>`, and a DuckLake write carries it as its commit author. The router session
  * is `module-<name>-<tenant>-<tenantDb>-<pool>`: one per module and pool, so a module's statements
  * never share a session (and its default catalog) with another pool's.
  *
  * Every call appends one `module.sql` audit row (family `control-plane`, actor `module:<name>`,
  * realm `system`, target `<tenantDb>/<pool>`, detail `module`, `sql` and, on a failure, `reason`),
  * outcome `ok`, `denied` (an access refusal) or `error`. The statement and the reason follow the
  * router's history rule for a system statement: a claim-shaped admin statement
  * (`AdminSqlParser.claims`, which may carry a password literal) and its error are recorded as the
  * redaction placeholder; any other statement has its values masked and their echo scrubbed from
  * the error (`StatementValueMask`, the stdout audit sink's masking), both capped at 500
  * characters. History and audit are shown to the tenant's admins.
  */
object ModuleSystemSql:

  /** The label of a module's statements in logs, statement history, the commit author and the audit
    * actor. It decides nothing: the privilege is `ExecCaller.system`.
    */
  def identityOf(module: String): String = s"module:$module"

  /** The router session a module's statements on `pool` share. */
  def connectionIdOf(module: String, pool: PoolKey): String =
    s"module-$module-${pool.tenant}-${pool.tenantDb}-${pool.pool}"

  private val AuditCap = 500

  def apply(
      module: String,
      executor: CatalogPreviewHandlers.PreviewExecutor,
      audit: AuditRecorder
  ): SystemSql = (pool, sql) =>
    val caller = ExecCaller.system(connectionIdOf(module, pool), identityOf(module))
    executor(caller, pool, sql).attempt.flatMap { outcome =>
      IO.blocking {
        val (status, answer): (String, Either[String, Unit]) = outcome match
          case Left(t)                                    => ("error", Left(messageOf(t)))
          case Right(Left(RouterFailure.AccessDenied(r))) => ("denied", Left(r))
          case Right(Left(failure))                       => ("error", Left(failure.reason))
          case Right(Right(result))                       =>
            // Read to the end, so an error the node reports mid-result is the answer too.
            try
              while result.rows.loadNextBatch() do ()
              ("ok", Right(()))
            catch case NonFatal(t) => ("error", Left(messageOf(t)))
            finally
              try result.close()
              catch case NonFatal(_) => ()
        record(audit, module, pool, sql, status, answer.left.toOption)
        answer
      }
    }

  private def messageOf(t: Throwable): String =
    Option(t.getMessage).getOrElse(t.getClass.getSimpleName)

  private def record(
      audit: AuditRecorder,
      module: String,
      pool: PoolKey,
      sql: String,
      outcome: String,
      error: Option[String]
  ): Unit =
    // FlightSqlRouter.record's rule for a system statement: a claim-shaped one (it may carry a
    // password literal, and its node error quotes it back) is replaced whole; any other has its
    // values masked, and their echo scrubbed from the error (StatementValueMask), since the row is
    // shown to the tenant's admins.
    val claimed             = AdminSqlParser.claims(sql)
    def recordedSql: String =
      if claimed then AdminSqlParser.RedactedPlaceholder
      else StatementValueMask.sql(sql).take(AuditCap)
    def recordedError(e: String): String =
      if claimed then AdminSqlParser.RedactedPlaceholder
      else StatementValueMask.error(e, sql).take(AuditCap)
    audit.restAs(
      actor = identityOf(module),
      actorRealm = "system",
      family = "control-plane",
      action = AuditActions.ModuleSql,
      outcome = outcome,
      tenant = Some(pool.tenant),
      target = Some(s"${pool.tenantDb}/${pool.pool}"),
      detail = Map("module" -> module, "sql" -> recordedSql) ++
        error.map(e => "reason" -> recordedError(e))
    )
