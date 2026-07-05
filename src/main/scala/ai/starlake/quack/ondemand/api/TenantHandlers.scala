package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.TenantOidcRegistry
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import sttp.model.StatusCode

/** `onAuthChanged` is called after every successful `setTenantAuth` so the edge's
  * [[TenantOidcRegistry]] can drop its cached per-tenant authenticator and rebuild from the updated
  * `qodstate_tenant.authConfig` on the next handshake. Optional so unit tests that don't need the
  * registry can pass a no-op.
  */
final class TenantHandlers(
    sup: PoolSupervisor,
    onAuthChanged: String => Unit = _ => ()
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def toResponse(t: Tenant): TenantResponse =
    TenantResponse(
      name = t.id,
      id = t.id,
      displayName = t.displayName,
      pools = sup.listPoolsOfTenant(t.id),
      disabled = t.disabled,
      authProvider = t.authProvider,
      authConfig = t.authConfig
    )

  /** Tenant creation is an inherently cross-tenant action (it mints a brand-new isolation scope),
    * so it is restricted to superusers. Static `QOD_API_KEY` callers (no session row) are admitted.
    */
  def createTenant(req: TenantRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantResponse] =
    SuperuserCheck.reject(apiKey)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        if req.id.isEmpty then
          IO.pure(
            Left(
              (StatusCode.BadRequest, ErrorResponse("invalid_id", "tenant id must be non-empty"))
            )
          )
        else if !ai.starlake.quack.model.Names.isValid(req.id) || !req.id.headOption.exists(
            _.isLetter
          )
        then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse(
                  "invalid_id",
                  "tenant id must be a slug: 1..63 chars, start with a letter, " +
                    "and contain only letters, digits and underscore"
                )
              )
            )
          )
        else if req.displayName.trim.isEmpty then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("invalid_display_name", "display name is required")
              )
            )
          )
        else if !Tenant.ValidAuthProviders.contains(req.authProvider) then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse(
                  "invalid_auth_provider",
                  s"authProvider must be one of ${Tenant.ValidAuthProviders.toList.sorted.mkString(", ")}"
                )
              )
            )
          )
        else
          sup
            .createTenant(
              Tenant(
                id = req.id,
                displayName = req.displayName.trim,
                authProvider = req.authProvider,
                authConfig = req.authConfig
              )
            )
            .map {
              case Right(t)  => Right(toResponse(t))
              case Left(msg) => Left((StatusCode.Conflict, ErrorResponse("exists", msg)))
            }

  def listTenants(apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantListResponse] = IO.delay {
    val all      = sup.listTenants().map(toResponse)
    val filtered = apiKey.flatMap(scopeOf) match
      case None                   => all
      case Some(s) if s.superuser => all
      case Some(s)                => all.filter(r => s.manageableTenants.contains(r.id))
    Right(TenantListResponse(filtered))
  }

  /** Lifecycle mutations on an EXISTING tenant. The per-request body-tenant guard in
    * `TenantScopeCheck.reject` is what protects against a foreign tenant being targeted; tenant
    * admins can manage their own tenant. Static `QOD_API_KEY` callers (no session row) are
    * admitted.
    */
  def deleteTenant(req: TenantOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.reject(apiKey, req.name)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.deleteTenant(req.name).map {
          case Right(_)  => Right(())
          case Left(msg) =>
            if msg.startsWith("tenant not found") then
              Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
            else Left((StatusCode.Conflict, ErrorResponse("has_pools", msg)))
        }

  def setTenantDisabled(req: SetTenantDisabledRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantResponse] =
    TenantScopeCheck.reject(apiKey, req.name)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.setTenantDisabled(req.name, req.disabled).map {
          case Right(t)  => Right(toResponse(t))
          case Left(msg) =>
            if msg.startsWith("tenant not found") then
              Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
            else Left((StatusCode.Conflict, ErrorResponse("update_failed", msg)))
        }

  def setTenantAuth(req: SetTenantAuthRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantResponse] =
    TenantScopeCheck.reject(apiKey, req.name)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.setTenantAuth(req.name, req.authProvider, req.authConfig).map {
          case Right(t) =>
            onAuthChanged(t.id)
            Right(toResponse(t))
          case Left(msg) =>
            if msg.startsWith("tenant not found") then
              Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
            else if msg.startsWith("authProvider must be") then
              Left((StatusCode.BadRequest, ErrorResponse("invalid_auth_provider", msg)))
            else Left((StatusCode.Conflict, ErrorResponse("update_failed", msg)))
        }
