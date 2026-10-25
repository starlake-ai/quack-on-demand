package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.TenantOidcRegistry
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.OpaAuthorizer
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.{SessionScope, TenantSignInWrite}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditActor, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** `onAuthChanged` is called after every successful `setTenantAuth` so the edge's
  * [[ai.starlake.quack.edge.auth.TenantOidcRegistry TenantOidcRegistry]] can drop its cached
  * per-tenant authenticator and rebuild from the updated `qodstate_tenant.authConfig` on the next
  * handshake. Optional so unit tests that don't need the registry can pass a no-op.
  *
  * `opa` is the manager's single [[ai.starlake.quack.edge.opa.OpaAuthorizer OpaAuthorizer]] (used
  * only by the `opaTest` dry run; None answers `opa_not_wired`), `opaCfg` the manager-wide OPA
  * defaults `setTenantAcl` validates against.
  */
final class TenantHandlers(
    sup: PoolSupervisor,
    onAuthChanged: String => Unit = _ => (),
    audit: AuditRecorder = AuditRecorder.noop,
    opa: Option[OpaAuthorizer] = None,
    opaCfg: OpaConfig = OpaConfig.default
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
      authConfig = t.authConfig,
      aclMode = t.acl.mode,
      opaUrl = t.acl.opaUrl,
      opaPolicyPath = t.acl.opaPolicyPath,
      opaTokenSet = t.acl.opaToken.nonEmpty,
      opaSendStatementText = t.acl.sendStatementText,
      clientSecretSet = t.authClientSecret.isDefined
    )

  /** Tenant creation is an inherently cross-tenant action (it mints a brand-new isolation scope),
    * so it is restricted to superusers. Static `QOD_API_KEY` callers (no session row) are admitted.
    */
  def createTenant(req: TenantRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantResponse] =
    createTenantAs(
      req,
      apiKey.flatMap(scopeOf).getOrElse(SessionScope.Superuser),
      AuditActor.Bearer(apiKey)
    )

  /** The one tenant create path, shared by `POST /api/tenant/create` and the boot-time
    * `ServeProvisionHook` (which passes `SessionScope.Superuser` and `AuditActor.System`): the
    * superuser gate on the caller's resolved scope, the id, display-name, auth-provider and
    * no-secret-in-authConfig checks, the create, and its audit rows.
    */
  def createTenantAs(
      req: TenantRequest,
      scope: SessionScope,
      actor: AuditActor
  ): Out[TenantResponse] =
    SuperuserCheck.forScope(scope) match
      case Some(err) =>
        actor.controlPlane(audit, AuditActions.TenantCreate, "denied")
        IO.pure(Left(err))
      case None =>
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
        else if req.authConfig.contains(TenantSignInWrite.SecretKey) then
          // The tenant/setAuth rule: a client secret only arrives through its write-only field,
          // never inside authConfig, which every response echoes.
          val refusal = TenantSignInWrite.Refusal.SecretInConfig
          IO.pure(Left((StatusCode.BadRequest, ErrorResponse(refusal.code, refusal.message))))
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
              case Right(t) =>
                actor.controlPlane(
                  audit,
                  AuditActions.TenantCreate,
                  "ok",
                  tenant = Some(t.id),
                  target = Some(t.id),
                  detail = Map("displayName" -> t.displayName, "authProvider" -> t.authProvider)
                )
                Right(toResponse(t))
              case Left(err) => Left((StatusCode.Conflict, ErrorResponse("exists", err.message)))
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
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.TenantDelete,
          "denied",
          tenant = Some(req.name)
        )
        IO.pure(Left(err))
      case None =>
        sup.deleteTenant(req.name).map {
          case Right(_) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.TenantDelete,
              "ok",
              tenant = Some(req.name),
              target = Some(req.name)
            )
            Right(())
          case Left(err: SupervisorError.NotFound) =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
          case Left(err) =>
            Left((StatusCode.Conflict, ErrorResponse("has_pools", err.message)))
        }

  def setTenantDisabled(req: SetTenantDisabledRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantResponse] =
    TenantScopeCheck.reject(apiKey, req.name)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.TenantSetDisabled,
          "denied",
          tenant = Some(req.name)
        )
        IO.pure(Left(err))
      case None =>
        sup.setTenantDisabled(req.name, req.disabled).map {
          case Right(t) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.TenantSetDisabled,
              "ok",
              tenant = Some(t.id),
              target = Some(t.id),
              detail = Map("disabled" -> req.disabled.toString)
            )
            Right(toResponse(t))
          case Left(err: SupervisorError.NotFound) =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
          case Left(err) =>
            Left((StatusCode.Conflict, ErrorResponse("update_failed", err.message)))
        }

  /** Set the tenant's sign-in provider, config and client secret. Tenant admins configure their own
    * tenant; [[TenantSignInWrite]] owns the write rules (the secret is bound to its issuer, only a
    * superuser adds or changes a `clientSecretRef`, one secret source) and a refusal stores
    * nothing. The secret is write-only: responses carry only `clientSecretSet`, and the audit row
    * records only that the stored secret changed (`clientKey -> changed`, never the value), set,
    * replaced or removed, a drop by an issuer change or a new reference included. Static-key and
    * no-key callers count as superusers, as in [[SuperuserCheck]].
    */
  def setTenantAuth(req: SetTenantAuthRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantResponse] =
    def denied(detail: Map[String, String]): Unit =
      audit.rest(
        apiKey,
        "control-plane",
        AuditActions.TenantAuthUpdate,
        "denied",
        tenant = Some(req.name),
        detail = detail
      )
    TenantScopeCheck.reject(apiKey, req.name)(scopeOf) match
      case Some(err) =>
        denied(Map.empty)
        IO.pure(Left(err))
      case None =>
        val callerIsSuperuser = SuperuserCheck.reject(apiKey)(scopeOf).isEmpty
        sup.getTenant(req.name) match
          case None =>
            IO.pure(
              Left(
                (StatusCode.NotFound, ErrorResponse("not_found", s"tenant not found: ${req.name}"))
              )
            )
          case Some(current) =>
            TenantSignInWrite(current, req.authConfig, req.clientSecret, callerIsSuperuser) match
              case Left(refusal) =>
                denied(Map("reason" -> refusal.code))
                val status = refusal match
                  case TenantSignInWrite.Refusal.SuperuserRequired => StatusCode.Forbidden
                  case _                                           => StatusCode.BadRequest
                IO.pure(Left((status, ErrorResponse(refusal.code, refusal.message))))
              case Right(next) =>
                // The row says what the write did to the stored secret, not what the request
                // sent: a drop by an issuer change or by a new reference is a change, a kept
                // value, the same value sent again and a clear of nothing are not.
                val secretChanged = next.authClientSecret != current.authClientSecret
                sup
                  .setTenantAuth(req.name, req.authProvider, next.authConfig, next.authClientSecret)
                  .map {
                    case Right(t) =>
                      onAuthChanged(t.id)
                      audit.rest(
                        apiKey,
                        "control-plane",
                        AuditActions.TenantAuthUpdate,
                        "ok",
                        tenant = Some(t.id),
                        target = Some(t.id),
                        // `clientKey`, not `clientSecret`: AuditEvent refuses a detail key
                        // containing password, secret, token, jwt or credential, and the row
                        // would be dropped.
                        detail = Map("authProvider" -> req.authProvider) ++
                          Option.when(secretChanged)("clientKey" -> "changed")
                      )
                      Right(toResponse(t))
                    case Left(err: SupervisorError.NotFound) =>
                      Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
                    case Left(err: SupervisorError.InvalidArgument) =>
                      Left(
                        (StatusCode.BadRequest, ErrorResponse("invalid_auth_provider", err.message))
                      )
                    case Left(err) =>
                      Left((StatusCode.Conflict, ErrorResponse("update_failed", err.message)))
                  }

  /** Patch the tenant's data-access authorization. The OPA token is write-only: never returned,
    * never logged, and the audit row only records that it changed.
    */
  def setTenantAcl(req: SetTenantAclRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantResponse] =
    TenantScopeCheck.reject(apiKey, req.name)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.TenantAclUpdate,
          "denied",
          tenant = Some(req.name)
        )
        IO.pure(Left(err))
      case None =>
        val patch = ai.starlake.quack.model.TenantAclPatch(
          mode = req.mode,
          opaUrl = req.opaUrl,
          opaPolicyPath = req.opaPolicyPath,
          opaToken = req.opaToken,
          sendStatementText = req.sendStatementText
        )
        sup.setTenantAcl(req.name, patch, opaCfg.url, opaCfg.defaultMode).map {
          case Right(t) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.TenantAclUpdate,
              "ok",
              tenant = Some(t.id),
              target = Some(t.id),
              detail = Map("mode" -> t.acl.effectiveMode(opaCfg.defaultMode)) ++
                req.opaToken.map(_ => "opaToken" -> "changed")
            )
            Right(toResponse(t))
          case Left(err: SupervisorError.NotFound) =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
          case Left(err: SupervisorError.InvalidArgument) =>
            val (code, msg) = err.message.split(": ", 2) match
              case Array(c, m) => (c, m)
              case _           => ("invalid_acl", err.message)
            Left((StatusCode.BadRequest, ErrorResponse(code, msg)))
          case Left(err) =>
            Left((StatusCode.Conflict, ErrorResponse("update_failed", err.message)))
        }

  /** OPA dry run (see [[OpaDryRun]]): returns the exact input and the tenant OPA's answer. */
  def opaTest(req: OpaTestRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[OpaTestResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.TenantOpaTest,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        // No resolvable scope = the static key; a session keeps the detail only as a superuser.
        val detailedErrors = apiKey.flatMap(scopeOf).forall(_.superuser)
        IO.blocking(OpaDryRun.run(sup, opa, req, detailedErrors)).map {
          case Left(("not_found", msg)) =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
          case Left((code, msg)) => Left((StatusCode.BadRequest, ErrorResponse(code, msg)))
          case Right(resp)       =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.TenantOpaTest,
              "ok",
              tenant = Some(req.tenant),
              target = Some(req.pool),
              detail = Map("rule" -> resp.rule, "outcome" -> resp.outcome)
            )
            Right(resp)
        }
