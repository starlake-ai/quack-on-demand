package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{UserS3Credential, UserS3CredentialRules}
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.RbacUser
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** Users' own S3 credentials (in-engine authorization, decision 23).
  *
  * The profile routes act on the caller's own `qodstate_user` row only: the row comes from the
  * credential ([[RestCaller.resolve]]: a session's `(tenant, username)`, a PAT's owner), never from
  * a request field, so they cannot be aimed at another user. A disabled owner is refused (`403
  * account_disabled`, a session JWT outlives a disablement). An add is validated by
  * [[UserS3CredentialRules]] (with the operator's `allowedEndpoints`), then checked against the
  * live data-folder deny set (`dataFolderOverlap`, the location rules': a scope at or under a data
  * folder is `400 scope_in_data_folder`, an unreadable deny set `503 deny_set_unavailable`), before
  * the supervisor writes.
  *
  * The user routes are an admin's: TenantScopeCheck.rejectForUser (a tenant admin only for their
  * own tenant's users, a superuser for anyone; a missing user is 404), list and remove only.
  *
  * No response carries the secret or the session token, and no audit row a key.
  */
final class UserS3CredentialHandlers(
    sup: PoolSupervisor,
    callers: RestCaller,
    allowedEndpoints: List[String],
    dataFolderOverlap: String => Either[String, Option[String]],
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def listMine(token: Option[String]): Out[S3CredentialListResponse] = IO.blocking {
    self(token).flatMap(u => listed(u.id))
  }

  def addMine(req: ProfileS3AddRequest, token: Option[String]): Out[S3CredentialDto] =
    IO.blocking(self(token)).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(u)  =>
        UserS3CredentialRules.validate(input(req), allowedEndpoints) match
          case Left((code, msg)) => IO.pure(Left(StatusCode.BadRequest -> ErrorResponse(code, msg)))
          case Right(accepted)   =>
            IO.blocking(dataFolderOverlap(accepted.scope)).flatMap {
              case Left(why) =>
                IO.pure(
                  Left(StatusCode.ServiceUnavailable -> ErrorResponse("deny_set_unavailable", why))
                )
              case Right(Some(why)) =>
                IO.pure(Left(StatusCode.BadRequest -> ErrorResponse("scope_in_data_folder", why)))
              case Right(None) =>
                sup.putUserS3Credential(u.id, accepted).map {
                  case Left(e)  => Left(UserS3CredentialHandlers.errorOf(e))
                  case Right(c) =>
                    audited(token, AuditActions.UserS3CredentialAdd, u, c)
                    Right(UserS3CredentialHandlers.toDto(c))
                }
            }
    }

  def removeMine(req: ProfileS3RemoveRequest, token: Option[String]): Out[S3CredentialDto] =
    IO.blocking(self(token)).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(u)  => removed(token, u, req.id)
    }

  def listForUser(req: UserS3ListRequest, token: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[S3CredentialListResponse] = IO.blocking {
    TenantScopeCheck.rejectForUser(token, sup.tenantForUser(req.userId))(scopeOf) match
      case Some(err) => Left(err)
      case None      => listed(req.userId)
  }

  def removeForUser(req: UserS3RemoveRequest, token: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[S3CredentialDto] =
    IO.blocking(TenantScopeCheck.rejectForUser(token, sup.tenantForUser(req.userId))(scopeOf))
      .flatMap {
        case Some(err) => IO.pure(Left(err))
        case None      =>
          sup.findUserById(req.userId) match
            case None    => IO.pure(Left(UserS3CredentialHandlers.errorOf(missingUser(req.userId))))
            case Some(u) => removed(token, u, req.id)
      }

  // ---------- internals ----------

  private def listed(
      userId: String
  ): Either[(StatusCode, ErrorResponse), S3CredentialListResponse] =
    sup
      .listUserS3Credentials(userId)
      .left
      .map(UserS3CredentialHandlers.errorOf)
      .map(cs => S3CredentialListResponse(cs.map(UserS3CredentialHandlers.toDto)))

  private def removed(token: Option[String], u: RbacUser, id: String): Out[S3CredentialDto] =
    sup.removeUserS3Credential(u.id, id).map {
      case Left(e)  => Left(UserS3CredentialHandlers.errorOf(e))
      case Right(c) =>
        audited(token, AuditActions.UserS3CredentialRemove, u, c)
        Right(UserS3CredentialHandlers.toDto(c))
    }

  private def audited(
      token: Option[String],
      action: String,
      u: RbacUser,
      c: UserS3Credential
  ): Unit =
    audit.rest(
      token,
      "control-plane",
      action,
      "ok",
      tenant = u.tenant,
      target = Some(c.scope),
      // The row's own id, under a key AuditEvent admits (it refuses any key naming a credential).
      detail = Map("user" -> u.id, "id" -> c.id)
    )

  private def missingUser(id: String): SupervisorError =
    SupervisorError.NotFound(s"user not found: $id")

  /** The caller's own user row, enabled, or the refusal every profile route answers. */
  private def self(token: Option[String]): Either[(StatusCode, ErrorResponse), RbacUser] =
    val row = callers.resolve(token) match
      case Right(RestCaller.Principal.Session(s)) =>
        sup.findUser(s.profile.tenant, s.profile.username)
      case Right(RestCaller.Principal.Pat(p)) => sup.findUserById(p.user.id)
      case Left(_)                            => None
    row match
      case None                  => Left(UserS3CredentialHandlers.NoIdentity)
      case Some(u) if !u.enabled => Left(UserS3CredentialHandlers.AccountDisabled)
      case Some(u)               => Right(u)

  private def input(req: ProfileS3AddRequest): UserS3CredentialRules.Input =
    UserS3CredentialRules.Input(
      req.scope,
      req.accessKeyId,
      req.secretAccessKey,
      req.sessionToken,
      req.region,
      req.endpoint,
      req.urlStyle
    )

object UserS3CredentialHandlers:

  def toDto(c: UserS3Credential): S3CredentialDto =
    S3CredentialDto(
      id = c.id,
      userId = c.userId,
      scope = c.scope,
      accessKeyId = c.accessKeyId,
      secretSet = c.secretAccessKey.nonEmpty,
      sessionTokenSet = c.sessionToken.nonEmpty,
      region = c.region,
      endpoint = c.endpoint,
      urlStyle = c.urlStyle,
      createdAt = c.createdAt,
      updatedAt = c.updatedAt
    )

  val NoIdentity: (StatusCode, ErrorResponse) =
    StatusCode.Unauthorized -> ErrorResponse(
      "no_session_identity",
      "S3 credentials need a logged-in session or a personal access token of their owner"
    )

  val AccountDisabled: (StatusCode, ErrorResponse) =
    StatusCode.Forbidden -> ErrorResponse("account_disabled", "this account is disabled")

  def errorOf(e: SupervisorError): (StatusCode, ErrorResponse) = e match
    case SupervisorError.NotFound(m)        => StatusCode.NotFound -> ErrorResponse("not_found", m)
    case SupervisorError.InvalidArgument(m) =>
      m.split(": ", 2) match
        case Array(code, msg) => StatusCode.BadRequest -> ErrorResponse(code, msg)
        case _                => StatusCode.BadRequest -> ErrorResponse("invalid_credential", m)
    case other => StatusCode.Conflict -> ErrorResponse("update_failed", other.message)
