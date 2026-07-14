package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.TenantDbKind
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import sttp.model.StatusCode

/** Tenant resolve -> [[TenantScopeCheck]] -> tenant-db lookup gate shared by every gated tenant-db
  * surface (catalog browser, table history, snapshot tags, managed maintenance, data preview). Left =
  * ready-made rejection; Right = (tenantId, tenantDbName).
  *
  * `requireDuckLake` carries the surface-specific `invalid_kind` message for callers that only make
  * sense against a DuckLake tenant-db (tags, maintenance, preview); `None` skips the kind check
  * (the browser and history handlers keep their own kind-tolerant handling).
  */
object TenantDbGate:

  /** Resolve a tenant request value (surrogate id OR display name) to the canonical id via the
    * supervisor cache. Distinct from [[HandlerResolvers.resolveTenantId]], which resolves against
    * `sup.listTenants()` with a lowercased display-name fallback.
    */
  def resolveTenantId(sup: PoolSupervisor, raw: String): Option[String] =
    sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id)

  def apply(
      sup: PoolSupervisor,
      rawTenant: String,
      tenantDb: String,
      apiKey: Option[String],
      requireDuckLake: Option[String] = None
  )(
      scopeOf: String => Option[SessionScope]
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    def err(code: StatusCode, error: String, msg: String) =
      Left((code, ErrorResponse(error, msg)))
    resolveTenantId(sup, rawTenant) match
      case None =>
        err(StatusCode.NotFound, "not_found", s"tenant '$rawTenant' is not registered")
      case Some(tid) =>
        TenantScopeCheck.reject(apiKey, tid)(scopeOf) match
          case Some(e) => Left(e)
          case None    =>
            sup.findTenantDb(tid, tenantDb) match
              case None =>
                err(StatusCode.NotFound, "not_found", s"tenant-db '$tenantDb' not found")
              case Some(td) =>
                requireDuckLake match
                  case Some(msg) if td.kind != TenantDbKind.DuckLake =>
                    err(StatusCode.BadRequest, "invalid_kind", msg)
                  case _ => Right((tid, td.name))
