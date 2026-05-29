package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticationService
import cats.effect.IO
import sttp.model.StatusCode

/** REST endpoints that drive the UI's login lifecycle.
  *
  * `/api/auth/login` validates `(username, password)` against the
  * configured AuthenticationService chain (DB + ROPC providers). On
  * success it mints an opaque session token, registers it with the
  * SessionTokenStore, and returns it to the browser. The browser
  * stores the token in localStorage and sends it as `X-API-Key` on
  * subsequent calls - the manager's middleware then admits the
  * request when the token resolves to a profile with role=admin.
  *
  * Only admin users can mint a token. Other roles get 403 here so
  * the UI never gets to render with insufficient privileges. */
final class AuthHandlers(authService: AuthenticationService, tokens: SessionTokenStore):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def login(req: LoginRequest): Out[LoginResponse] = IO.blocking {
    if req.username.isEmpty || req.password.isEmpty then
      Left((StatusCode.BadRequest,
        ErrorResponse("invalid_credentials", "username and password are required")))
    else if !authService.hasProviders then
      Left((StatusCode.ServiceUnavailable,
        ErrorResponse("auth_disabled",
          "no auth backends configured; set auth.database.enabled=true (or another)")))
    else
      authService.authenticateBasic(req.username, req.password) match
        case Left(err) =>
          Left((StatusCode.Unauthorized,
            ErrorResponse("invalid_credentials", err)))
        case Right(profile) if !profile.role.equalsIgnoreCase("admin") =>
          Left((StatusCode.Forbidden,
            ErrorResponse("admin_required",
              s"user '${profile.username}' has role '${profile.role}'; admin required for UI")))
        case Right(profile) =>
          val token = tokens.mint(profile)
          Right(LoginResponse(token, profile.username, profile.role))
  }

  def logout(token: String): Out[Unit] = IO.delay {
    tokens.revoke(token)
    Right(())
  }

  def whoami(token: String): Out[WhoamiResponse] = IO.delay {
    tokens.get(token) match
      case Some(s) => Right(WhoamiResponse(s.profile.username, s.profile.role))
      case None    => Left((StatusCode.Unauthorized,
                            ErrorResponse("expired", "session token unknown or expired")))
  }