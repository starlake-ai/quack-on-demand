package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.state.{TenantIdentity, TenantIdentityStore}
import cats.effect.IO
import sttp.model.StatusCode

/** CRUD for the tenant-identity allowlist in `qodstate_tenant_identity`.
  * The verified-identity-to-tenant mapping lives in Postgres; the
  * REST endpoints exposed here are how admins add / list / remove
  * rows. The resolver rejects any identity not present in this table
  * (no auto-provisioning). */
final class IdentityHandlers(store: TenantIdentityStore, sup: PoolSupervisor):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def toResponse(t: TenantIdentity): IdentityResponse =
    IdentityResponse(
      id         = t.id,
      tenantId   = t.tenantId,
      issuer     = t.issuer,
      externalId = t.externalId,
      createdAt  = t.createdAt.toString
    )

  /** Resolve `req.tenantId` -- which the UI sends as the tenant's
    * displayName -- to the surrogate id stored in `qodstate_tenant.id`.
    * Accept the surrogate id directly too, so curl clients that already
    * know the id keep working. */
  private def resolveTenantId(raw: String): Option[String] =
    val tenants = sup.listTenants()
    tenants.find(_.id == raw).map(_.id)
      .orElse(tenants.find(_.displayName == raw).map(_.id))
      .orElse(tenants.find(_.name == raw).map(_.id))

  def createIdentity(req: IdentityRequest): Out[IdentityResponse] = IO.blocking {
    if req.tenantId.isEmpty || req.issuer.isEmpty || req.externalId.isEmpty then
      Left((StatusCode.BadRequest,
        ErrorResponse("invalid", "tenantId, issuer, externalId are required")))
    else resolveTenantId(req.tenantId) match
      case None =>
        Left((StatusCode.NotFound,
          ErrorResponse("not_found", s"tenant '${req.tenantId}' is not registered")))
      case Some(tid) =>
        try Right(toResponse(store.create(tid, req.issuer, req.externalId)))
        catch case t: Throwable =>
          // UNIQUE(issuer, external_id) violation is the most common path.
          Left((StatusCode.Conflict, ErrorResponse("exists", t.getMessage)))
  }

  def listIdentities(tenantId: Option[String]): Out[IdentityListResponse] = IO.blocking {
    // Same tenantId resolution as create: accept name OR surrogate id.
    val resolved = tenantId.flatMap(resolveTenantId)
    val filter   = if tenantId.isDefined && resolved.isEmpty then Some("__no_match__") else resolved
    Right(IdentityListResponse(store.list(filter).map(toResponse)))
  }

  def deleteIdentity(req: IdentityOpRequest): Out[Unit] = IO.blocking {
    if store.delete(req.id) then Right(())
    else Left((StatusCode.NotFound, ErrorResponse("not_found", s"identity '${req.id}' not found")))
  }
