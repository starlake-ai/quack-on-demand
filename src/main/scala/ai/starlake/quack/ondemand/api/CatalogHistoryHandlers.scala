package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.TenantDbKind
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.{
  DuckLakeCatalogReader,
  HistoryOperation,
  TableHistoryFilter
}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import sttp.model.StatusCode

/** Per-table history / audit timeline handler (EPIC Spec 01). Same gate as [[CatalogHandlers]]
  * (tenant resolve -> [[TenantScopeCheck]] -> tenant-db lookup), kept in its own class so the
  * browser handlers stay at their current size. Reads audit under the same
  * `catalog.auditCatalogReads` knob as the browser GETs; unlike their gate-tracking audit, the "ok"
  * event here fires only for a materialized read and carries the returned commit count (never row
  * contents). A non-DuckLake tenant-db 404s: history is table-scoped and those tenant-dbs have no
  * browsable tables.
  */
final class CatalogHistoryHandlers(
    resolveReader: (String, String) => DuckLakeCatalogReader,
    sup: PoolSupervisor,
    kindOf: (String, String) => Option[TenantDbKind] = (_, _) => Some(TenantDbKind.DuckLake),
    audit: AuditRecorder = AuditRecorder.noop,
    auditReads: Boolean = false
):

  private type Res[T] = Either[(StatusCode, ErrorResponse), T]

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  private def isDuckLake(tenant: String, tenantDb: String): Boolean =
    kindOf(tenant, tenantDb).contains(TenantDbKind.DuckLake)

  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    TenantDbGate(sup, rawTenant, tenantDb, apiKey)(scopeOf)

  def history(
      tenant: String,
      tenantDb: String,
      schema: String,
      table: String,
      limit: Option[Int],
      before: Option[Long],
      from: Option[java.time.Instant],
      to: Option[java.time.Instant],
      operation: Option[String],
      author: Option[String],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Res[CatalogHistoryResponse] =
    // Gate first so the tenant-scope 403/404 wins over filter-validation 400s,
    // consistent with the sibling gated handlers.
    gate(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        if auditReads then
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogHistoryRead,
            "denied",
            detail = Map("endpoint" -> "history", "table" -> s"$schema.$table")
          )
        Left(e)
      case Right((tid, db)) =>
        if operation.exists(op => !HistoryOperation.isValid(op)) then
          err(
            StatusCode.BadRequest,
            "invalid_filter",
            s"operation must be one of ${HistoryOperation.Values.toList.sorted.mkString(", ")}"
          )
        else if from.zip(to).exists((f, t) => f.isAfter(t)) then
          err(StatusCode.BadRequest, "invalid_filter", "from must not be after to")
        else
          val notFound =
            err(StatusCode.NotFound, "not_found", s"table $schema.$table not found")
          if !isDuckLake(tid, db) then notFound
          else
            val effectiveLimit = limit.getOrElse(50).max(1).min(200)
            val filter         = TableHistoryFilter(from, to, operation, author)
            resolveReader(tid, db)
              .listTableHistory(schema, table, filter, effectiveLimit, before) match
              case None       => notFound
              case Some(page) =>
                if auditReads then
                  audit.rest(
                    apiKey,
                    "control-plane",
                    AuditActions.CatalogHistoryRead,
                    "ok",
                    tenant = Some(tid),
                    target = Some(db),
                    detail = Map(
                      "endpoint" -> "history",
                      "table"    -> s"$schema.$table",
                      "commits"  -> page.commits.length.toString
                    )
                  )
                Right(
                  CatalogHistoryResponse(
                    table = CatalogHistoryTableRef(schema, table, page.tableId),
                    commits = page.commits,
                    hasMore = page.hasMore
                  )
                )
