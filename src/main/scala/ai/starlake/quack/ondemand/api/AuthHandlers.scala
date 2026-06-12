package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.{AuthenticationService, AuthScope}
import ai.starlake.quack.ondemand.auth.{GrantsLookup, ManagementIdentitySource, SessionScope}
import ai.starlake.quack.ondemand.state.UserGrant
import cats.effect.IO
import sttp.model.StatusCode

/** REST endpoints driving the UI's login lifecycle.
  *
  * Authentication (who you are) goes through `AuthenticationService.authenticateBasic`.
  * Authorization (what you may do) depends on `identitySource`:
  *   - `Db`: the authenticating profile IS the grant. Single-tenant or superuser.
  *   - `Oidc`: the JWT's `role` and any tenant claim are discarded; `grantsForIdentity` is consulted
  *     with the JWT's `preferred_username` (and `email` as fallback).
  *
  * Either path collapses to a [[SessionScope]] carrying `superuser` and `manageableTenants`. Empty
  * grants => `403 not_provisioned`; no admin grant => `403 admin_required` (the UI is admin-only).
  */
final class AuthHandlers(
    authService: AuthenticationService,
    tokens: SessionTokenStore,
    identitySource: ManagementIdentitySource,
    grantsForIdentity: GrantsLookup
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def login(req: LoginRequest): Out[LoginResponse] = IO.blocking {
    if req.username.isEmpty || req.password.isEmpty then
      Left(
        (
          StatusCode.BadRequest,
          ErrorResponse("invalid_credentials", "username and password are required")
        )
      )
    else if !authService.hasProviders then
      Left(
        (
          StatusCode.ServiceUnavailable,
          ErrorResponse(
            "auth_disabled",
            "no auth backends configured; set auth.database.enabled=true (or another)"
          )
        )
      )
    else
      val scope: AuthScope = req.tenant.map(_.trim).filter(_.nonEmpty) match
        case Some(t) => AuthScope.Tenant(t)
        case None    => AuthScope.System
      authService.authenticateBasic(scope, req.username, req.password) match
        case Left(err) =>
          Left((StatusCode.Unauthorized, ErrorResponse("invalid_credentials", err)))
        case Right(profile) =>
          val grants = identitySource match
            case ManagementIdentitySource.Db =>
              // The DB authenticator already encoded the (tenant, role) the operator
              // provisioned in `qodstate_user`. Collapse it into a one-element grant
              // set so the rest of the pipeline is uniform.
              List(UserGrant(profile.tenant, profile.role))
            case ManagementIdentitySource.Oidc =>
              // Discard JWT role + tenant. The qodstate_user row is authoritative for
              // management-plane authorization.
              grantsForIdentity(profile.username, profile.claims.get("email"))

          val superuser = grants.exists(g =>
            g.tenant.isEmpty && g.role.equalsIgnoreCase("admin")
          )
          val manageableTenants: Set[String] = grants.collect {
            case UserGrant(Some(t), r) if r.equalsIgnoreCase("admin") => t
          }.toSet

          if grants.isEmpty then
            Left(
              (
                StatusCode.Forbidden,
                ErrorResponse(
                  "not_provisioned",
                  s"user '${profile.username}' authenticated but has no qodstate_user grant"
                )
              )
            )
          else if !superuser && manageableTenants.isEmpty then
            Left(
              (
                StatusCode.Forbidden,
                ErrorResponse(
                  "admin_required",
                  s"user '${profile.username}' has no admin grant; manager UI is admin-only"
                )
              )
            )
          else
            val sessionScope = SessionScope(superuser, manageableTenants)
            val token        = tokens.mintWithScope(profile, sessionScope)
            Right(
              LoginResponse(
                token             = token,
                username          = profile.username,
                tenant            = None,
                superuser         = superuser,
                manageableTenants = manageableTenants.toList.sorted
              )
            )
  }

  def logout(token: String): Out[Unit] = IO.delay {
    tokens.revoke(token)
    Right(())
  }

  def whoami(token: String): Out[WhoamiResponse] = IO.delay {
    tokens.get(token) match
      case Some(s) =>
        Right(
          WhoamiResponse(
            username          = s.profile.username,
            role              = s.profile.role,
            tenant            = s.profile.tenant,
            superuser         = s.scope.superuser,
            manageableTenants = s.scope.manageableTenants.toList.sorted
          )
        )
      case None =>
        Left(
          (StatusCode.Unauthorized, ErrorResponse("expired", "session token unknown or expired"))
        )
  }