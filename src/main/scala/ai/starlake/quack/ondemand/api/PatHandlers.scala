package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.state.{PatRecord, PatStore, RbacUser}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** Self-service personal-access-token management (`/api/auth/pat/create|list|revoke|delete`).
  *
  * Two rules shape every handler here:
  *
  *   - **Session-only.** The acting identity is read from a session JWT, and a value carrying
  *     [[PatStore.TokenPrefix]] is refused `403 session_required` BEFORE any store call. A PAT is a
  *     long-lived credential meant to be pasted into agents and scripts; letting one mint a
  *     successor or revoke its siblings would make a single leaked token unrevocable in practice,
  *     since the thief could always roll forward. Re-authenticating with a password (or SSO) is the
  *     price of touching the credential set itself.
  *   - **Self-scoped.** The owning user id comes from the session, never from a request field
  *     (there is none), and every store call is scoped by it. So `list` shows only the caller's own
  *     tokens and `revoke` can only ever retire one of them.
  *
  * A resolved but DISABLED owner is refused `403 account_disabled` before any store call. Disabling
  * a user already locks their existing tokens out at use time (`PatAuthenticator` gates on
  * `enabled`); this closes the mint-time half, so a session minted before the row was disabled
  * cannot leave a working credential behind it.
  *
  * `revoke` deliberately collapses "unknown id", "id owned by someone else" and "already revoked"
  * into one `404 not_found`: distinguishing them would turn the endpoint into an oracle for which
  * token ids exist under other accounts.
  *
  * @param userOf
  *   resolves the session's `(tenant, username)` to the `qodstate_user` ROW that owns the tokens --
  *   the whole row, because `enabled` is part of the decision. `Main` wires
  *   `PostgresControlPlaneStore.findUser`; a session whose principal has no row (an OIDC identity
  *   that was never provisioned) resolves to `None` and is refused `401`.
  */
final class PatHandlers(
    pats: PatStore,
    sessions: SessionTokenStore,
    userOf: (Option[String], String) => Option[RbacUser],
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private val sessionRequired =
    (
      StatusCode.Forbidden,
      ErrorResponse(
        "session_required",
        "personal access tokens are managed from a logged-in session, not with a PAT"
      )
    )

  private val noIdentity =
    (
      StatusCode.Unauthorized,
      ErrorResponse("no_session_identity", "log in to manage personal access tokens")
    )

  private val accountDisabled =
    (
      StatusCode.Forbidden,
      ErrorResponse("account_disabled", "this account is disabled")
    )

  private val notFound =
    (StatusCode.NotFound, ErrorResponse("not_found", "no such personal access token"))

  /** The owning user id behind the presented credential, or the error the caller gets: a PAT (`403
    * session_required`), no resolvable principal (`401 no_session_identity`), or a resolved but
    * disabled row (`403 account_disabled`). A PAT is rejected on the prefix alone (no store hit):
    * the refusal must not depend on whether the token is live, or a revoked PAT would report a
    * different code than a valid one.
    */
  private def identityOf(token: Option[String]): Either[(StatusCode, ErrorResponse), String] =
    if token.exists(_.startsWith(PatStore.TokenPrefix)) then Left(sessionRequired)
    else
      token
        .flatMap(sessions.get)
        .flatMap(s => userOf(s.profile.tenant, s.profile.username)) match
        case None                  => Left(noIdentity)
        case Some(u) if !u.enabled => Left(accountDisabled)
        case Some(u)               => Right(u.id)

  def create(token: Option[String], req: PatCreateRequest): Out[PatCreateResponse] = IO.blocking {
    identityOf(token).flatMap { uid =>
      val name = req.name.trim
      if name.isEmpty then
        Left((StatusCode.BadRequest, ErrorResponse("invalid_name", "token name must be non-empty")))
      // Deliberately `!isAfter`, not `isBefore`: an expiry of exactly now yields a
      // token that is already dead on arrival, so it is refused with the same code
      // as a past one rather than minted and immediately unusable.
      else if req.expiresAt.exists(!_.isAfter(java.time.Instant.now())) then
        Left(
          (
            StatusCode.BadRequest,
            ErrorResponse("invalid_expiry", "expiresAt must be in the future")
          )
        )
      else
        val (rec, raw) = pats.mint(uid, name, req.expiresAt)
        audit.rest(
          token,
          "auth",
          AuditActions.AuthPatCreate,
          "ok",
          target = Some(rec.id),
          detail = Map("name" -> rec.name)
        )
        Right(PatCreateResponse(rec.id, rec.name, raw, rec.expiresAt))
    }
  }

  def list(token: Option[String]): Out[PatListResponse] = IO.blocking {
    identityOf(token).map(uid => PatListResponse(pats.list(uid).map(entryOf)))
  }

  def revoke(token: Option[String], req: PatRevokeRequest): Out[Unit] = IO.blocking {
    identityOf(token).flatMap { uid =>
      if pats.revoke(uid, req.id.trim) then
        audit.rest(token, "auth", AuditActions.AuthPatRevoke, "ok", target = Some(req.id.trim))
        Right(())
      else
        audit.rest(token, "auth", AuditActions.AuthPatRevoke, "denied", target = Some(req.id.trim))
        Left(notFound)
    }
  }

  def delete(token: Option[String], req: PatDeleteRequest): Out[Unit] = IO.blocking {
    identityOf(token).flatMap { uid =>
      pats.delete(uid, req.id.trim) match
        case PatStore.DeleteOutcome.Deleted =>
          audit.rest(token, "auth", AuditActions.AuthPatDelete, "ok", target = Some(req.id.trim))
          Right(())
        case PatStore.DeleteOutcome.Live =>
          audit.rest(
            token,
            "auth",
            AuditActions.AuthPatDelete,
            "denied",
            target = Some(req.id.trim)
          )
          Left(
            (
              StatusCode.BadRequest,
              ErrorResponse("pat_live", "token is still live - revoke it before deleting it")
            )
          )
        case PatStore.DeleteOutcome.NotFound =>
          audit.rest(
            token,
            "auth",
            AuditActions.AuthPatDelete,
            "denied",
            target = Some(req.id.trim)
          )
          Left(notFound)
    }
  }

  private def entryOf(r: PatRecord): PatEntry =
    PatEntry(
      id = r.id,
      name = r.name,
      createdAt = r.createdAt,
      expiresAt = r.expiresAt,
      lastUsedAt = r.lastUsedAt,
      revoked = r.revokedAt.isDefined
    )
