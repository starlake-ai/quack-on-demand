package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.state.{PatRecord, PatStore, RbacUser}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** Self-service personal-access-token management (`/api/auth/pat/create|list|revoke|delete`).
  *
  * Two credentials reach this class: a session JWT, and -- since this design's whole point is
  * letting an agent hold a credential it cannot exceed -- a PAT itself. That reverses what this
  * class used to do: it refused every PAT-presented call outright, and its scaladoc explained why:
  * "letting one mint a successor or revoke its siblings would make a single leaked token
  * unrevocable in practice, since the thief could always roll forward." That objection is about
  * REVOCABILITY, not privilege escalation, so "a child can only narrow, never widen" does not
  * answer it by itself.
  *
  * What answers the "mint a successor" half is the revocation cascade: a PAT-minted child always
  * carries its minting token as `parentId` ([[PatStore.mint]]), and [[PatStore.revoke]] cascades
  * over the whole subtree in one statement. Revoking a stolen token therefore kills every successor
  * it ever minted in the same call -- roll-forward stops working. The cascade is not an operational
  * convenience here, it is the precondition that makes admitting a PAT at all safe; removing it
  * would silently reopen the hole the original refusal closed.
  *
  * The cascade does not answer the "revoke its siblings" half on its own -- a live sibling would
  * survive a revoke of a DIFFERENT token. That half is answered by restricting the surface instead:
  * a PAT caller may only ever reach the subtree rooted at the token it presented ([[Caller.Pat]]
  * below), so it can create a child of itself, list its own descendants, and revoke/delete a token
  * in that subtree -- never a sibling, never its own parent, never itself. A session caller is
  * unrestricted across its own tokens, exactly as before:
  *
  * | Operation           | Session                        | PAT                                     |
  * |:--------------------|:-------------------------------|:----------------------------------------|
  * | `create` a child    | yes                            | yes, child of the presenting token      |
  * | `list`              | all of the caller's own tokens | the presenting token's own subtree only |
  * | `revoke` / `delete` | any of the caller's own tokens | own subtree only, never itself/upward   |
  *
  *   - **Self-scoped.** The owning user id always comes from the credential, never from a request
  *     field (there is none), and every store call is scoped by it. So `list` shows only the
  *     caller's own tokens (or subtree) and `revoke` can only ever retire one of them.
  *
  * A resolved but DISABLED owner is refused `403 account_disabled` before any store call -- for a
  * SESSION caller. Disabling a user already locks their existing tokens out at use time
  * (`PatAuthenticator` gates on `enabled`), and the guard in front of this class re-resolves a PAT
  * credential (and its owner's `enabled` bit) fresh on every request via `PatAuthenticator`, so a
  * PAT caller reaching this class has already been proven live and enabled moments ago; a session
  * JWT is stateless and can outlive a disablement for its whole (up to 8h) lifetime, which is what
  * the explicit check here closes.
  *
  * `revoke` deliberately collapses "unknown id", "id owned by someone else", "id outside the
  * presenting PAT's subtree" and "already revoked" into one `404 not_found`: distinguishing any of
  * them would turn the endpoint into an oracle for which token ids exist, under this account or
  * another.
  *
  * @param userOf
  *   resolves the session's `(tenant, username)` to the `qodstate_user` ROW that owns the tokens --
  *   the whole row, because `enabled` is part of the decision. `Main` wires
  *   `PostgresControlPlaneStore.findUser`; a session whose principal has no row (an OIDC identity
  *   that was never provisioned) resolves to `None` and is refused `401`.
  * @param maxDepth
  *   [[ai.starlake.quack.PatConfig.maxDepth]] (env `QOD_PAT_MAX_DEPTH`, default 8). An operational
  *   backstop on the delegation chain, not a security boundary -- see [[TokenRestriction.narrow]],
  *   which already guarantees a child cannot exceed its parent regardless of depth.
  */
final class PatHandlers(
    pats: PatStore,
    sessions: SessionTokenStore,
    userOf: (Option[String], String) => Option[RbacUser],
    audit: AuditRecorder = AuditRecorder.noop,
    maxDepth: Int = 8
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  /** Who is calling, and what they may touch. A session may reach all of its own tokens; a PAT may
    * reach only the subtree rooted at the token it presented. Deliberately carries only identity
    * (`userId`, and for a PAT its own `patId`) -- NOT depth or restriction: those are read fresh
    * from [[PatStore.findById]] at the point they are needed (mint time), never trusted from a
    * principal resolved earlier in the request, so a mint decision can never be made against a
    * stale read.
    */
  private enum Caller:
    case Session(userId: String)
    case Pat(userId: String, patId: String)

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

  private val depthExceeded =
    (
      StatusCode.BadRequest,
      ErrorResponse(
        "pat_depth_exceeded",
        s"delegation chain would exceed the configured max depth ($maxDepth)"
      )
    )

  /** The caller behind the presented credential, or the error it gets.
    *
    *   - A value carrying [[PatStore.TokenPrefix]] is authenticated against the store directly
    *     ([[PatStore.verify]]): unknown, revoked or expired all collapse to the same
    *     `401 no_session_identity` a missing credential gets, so a stolen-then-revoked token cannot
    *     be distinguished from one that never existed. This is also why a mint attempted with an
    *     already-revoked bearer never reaches the depth/scope checks below: the credential fails
    *     right here.
    *   - Anything else is looked up as a session JWT; a resolved but disabled owner is refused
    *     `403 account_disabled` (see the class scaladoc for why this check lives here only for
    *     sessions, not PATs).
    */
  private def callerOf(token: Option[String]): Either[(StatusCode, ErrorResponse), Caller] =
    token match
      case Some(t) if t.startsWith(PatStore.TokenPrefix) =>
        pats.verify(t) match
          case Some(rec) => Right(Caller.Pat(rec.userId, rec.id))
          case None      => Left(noIdentity)
      case _ =>
        token
          .flatMap(sessions.get)
          .flatMap(s => userOf(s.profile.tenant, s.profile.username)) match
          case None                  => Left(noIdentity)
          case Some(u) if !u.enabled => Left(accountDisabled)
          case Some(u)               => Right(Caller.Session(u.id))

  /** A row this instant is neither revoked nor past its expiry. Used only at mint time, on a row
    * read fresh via [[PatStore.findById]] -- never on data carried from an earlier resolution.
    */
  private def isLive(rec: PatRecord): Boolean =
    rec.revokedAt.isEmpty && rec.expiresAt.forall(_.isAfter(java.time.Instant.now()))

  /** The restriction requested on the wire, normalized here: this is where untrusted input first
    * arrives. `verbCeiling` is upcased to its canonical form for storage and display -- NOT because
    * `narrow`'s ceiling check needs it case-insensitive (`TokenRestriction.covers` already upcases
    * internally, so widen detection works either way) and NOT because of any downstream
    * `RolePermission.ValidVerbs` comparison (that constant is consulted only in `PoolSupervisor`,
    * for unrelated grant validation, never here). This is cheap insurance against a future strict
    * comparison being added, and it keeps every stored/listed value in one consistent case.
    *
    * `expiresAt` is passed through unnormalized: `PatStore.mint` truncates to the column's
    * microsecond precision itself, so every caller of `mint` gets that guarantee, not only this
    * one.
    */
  private def requestedRestriction(req: PatCreateRequest): TokenRestriction =
    TokenRestriction(
      roles = req.roles,
      databases = req.databases,
      pools = req.pools,
      tools = req.tools,
      verbCeiling = req.verbCeiling.map(_.toUpperCase),
      dropAdmin = req.dropAdmin,
      stmtTimeoutMs = req.stmtTimeoutMs,
      maxRows = req.maxRows,
      expiresAt = req.expiresAt
    )

  /** Narrow `requested` against `(parentRestriction, parentDepth)`, enforce the depth cap, and
    * mint. Shared by both callers: a session supplies `TokenRestriction.Unrestricted` / depth `-1`
    * (so the minted root lands at depth 0) as its "parent"; a PAT supplies its own freshly-read
    * row.
    *
    * [[PatStore.mint]] re-checks the parent's liveness atomically as part of the insert and throws
    * [[PatStore.ParentNotLiveException]] on zero rows written; that is a RACE BACKSTOP for a parent
    * that dies between the friendly pre-check (in `create`, via `isLive`) and this call, not the
    * primary error path, so it maps to the same `404 not_found` the pre-check produces -- the two
    * must be indistinguishable to a caller.
    */
  private def mintChild(
      token: Option[String],
      uid: String,
      name: String,
      parentRestriction: TokenRestriction,
      parentId: Option[String],
      parentDepth: Int,
      requested: TokenRestriction
  ): Either[(StatusCode, ErrorResponse), PatCreateResponse] =
    val childDepth = parentDepth + 1
    if childDepth > maxDepth then Left(depthExceeded)
    else
      TokenRestriction.narrow(parentRestriction, requested) match
        case Left(axis) =>
          Left((StatusCode.BadRequest, ErrorResponse("pat_scope_widens", axis)))
        case Right(effective) =>
          try
            val (rec, raw) = pats.mint(uid, name, effective, parentId, childDepth)
            audit.rest(
              token,
              "auth",
              AuditActions.AuthPatCreate,
              "ok",
              target = Some(rec.id),
              detail = Map("name" -> rec.name)
            )
            Right(PatCreateResponse(rec.id, rec.name, raw, rec.expiresAt))
          catch case _: PatStore.ParentNotLiveException => Left(notFound)

  def create(token: Option[String], req: PatCreateRequest): Out[PatCreateResponse] = IO.blocking {
    callerOf(token).flatMap { caller =>
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
      // A non-positive stmtTimeoutMs is not "no timeout requested" (that is the field being
      // absent), it is a value that would compare `<= parent` under `narrow`'s tightening rule
      // and so pass as a legal "tightening", while `Main.routedExecutor`'s
      // `case Some(ms) if ms > 0 => ... case _ => run` treats zero (and negative) exactly like
      // absent: no timeout applied at all. A child of a 5000ms parent requesting `0` would
      // therefore read as tighter while actually being UNBOUNDED. Refused here, at the REST
      // boundary, before it ever reaches `narrow`.
      else if req.stmtTimeoutMs.exists(_ <= 0) then
        Left(
          (
            StatusCode.BadRequest,
            ErrorResponse("invalid_stmt_timeout_ms", "stmtTimeoutMs must be positive")
          )
        )
      else
        val requested = requestedRestriction(req)
        caller match
          case Caller.Session(uid) =>
            // Root mint from a session: no parent, "parent depth" -1 so the minted
            // row lands at depth 0, and the caller's own grants (checked elsewhere,
            // at use time) are the only ceiling -- Unrestricted here.
            mintChild(token, uid, name, TokenRestriction.Unrestricted, None, -1, requested)
          case Caller.Pat(uid, parentId) =>
            // Read fresh, not trusted from callerOf: depth, restriction and liveness
            // must all come from the same read at the moment they are used.
            pats.findById(uid, parentId) match
              case Some(parentRec) if isLive(parentRec) =>
                mintChild(
                  token,
                  uid,
                  name,
                  parentRec.restriction,
                  Some(parentId),
                  parentRec.depth,
                  requested
                )
              // Unreachable in practice (the presenting token was just verified live
              // moments ago in callerOf, and findById is owner-scoped to that same
              // token's own row) except for a concurrent revoke landing in between;
              // answered with the same non-leak 404 as any other unreachable parent.
              case _ => Left(notFound)
    }
  }

  def list(token: Option[String]): Out[PatListResponse] = IO.blocking {
    callerOf(token).map {
      case Caller.Session(uid)    => PatListResponse(pats.list(uid).map(entryOf))
      case Caller.Pat(uid, patId) => PatListResponse(pats.listSubtree(uid, patId).map(entryOf))
    }
  }

  /** `true` when `caller` may act on `targetId`: unconditionally for a session (ownership is still
    * enforced by the store call itself), only when it is a strict descendant of the presenting
    * PAT's own row for a PAT -- see [[PatStore.isInSubtree]], which is never `true` for the root id
    * itself, so a PAT can never touch its own row through this path either.
    */
  private def reachable(caller: Caller, targetId: String): (String, Boolean) = caller match
    case Caller.Session(uid)    => (uid, true)
    case Caller.Pat(uid, patId) => (uid, pats.isInSubtree(uid, patId, targetId))

  def revoke(token: Option[String], req: PatRevokeRequest): Out[Unit] = IO.blocking {
    callerOf(token).flatMap { caller =>
      val id                 = req.id.trim
      val (uid, isReachable) = reachable(caller, id)
      if !isReachable then
        audit.rest(token, "auth", AuditActions.AuthPatRevoke, "denied", target = Some(id))
        Left(notFound)
      else if pats.revoke(uid, id) then
        audit.rest(token, "auth", AuditActions.AuthPatRevoke, "ok", target = Some(id))
        Right(())
      else
        audit.rest(token, "auth", AuditActions.AuthPatRevoke, "denied", target = Some(id))
        Left(notFound)
    }
  }

  def delete(token: Option[String], req: PatDeleteRequest): Out[Unit] = IO.blocking {
    callerOf(token).flatMap { caller =>
      val id                 = req.id.trim
      val (uid, isReachable) = reachable(caller, id)
      if !isReachable then
        audit.rest(token, "auth", AuditActions.AuthPatDelete, "denied", target = Some(id))
        Left(notFound)
      else
        pats.delete(uid, id) match
          case PatStore.DeleteOutcome.Deleted =>
            audit.rest(token, "auth", AuditActions.AuthPatDelete, "ok", target = Some(id))
            Right(())
          case PatStore.DeleteOutcome.Live =>
            audit.rest(token, "auth", AuditActions.AuthPatDelete, "denied", target = Some(id))
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("pat_live", "token is still live - revoke it before deleting it")
              )
            )
          case PatStore.DeleteOutcome.NotFound =>
            audit.rest(token, "auth", AuditActions.AuthPatDelete, "denied", target = Some(id))
            Left(notFound)
    }
  }

  private def scopeOf(r: PatRecord): PatScope =
    PatScope(
      roles = r.restriction.roles,
      databases = r.restriction.databases,
      pools = r.restriction.pools,
      tools = r.restriction.tools,
      verbCeiling = r.restriction.verbCeiling,
      dropAdmin = r.restriction.dropAdmin,
      stmtTimeoutMs = r.restriction.stmtTimeoutMs,
      maxRows = r.restriction.maxRows
    )

  private def entryOf(r: PatRecord): PatEntry =
    PatEntry(
      id = r.id,
      name = r.name,
      createdAt = r.createdAt,
      expiresAt = r.expiresAt,
      lastUsedAt = r.lastUsedAt,
      revoked = r.revokedAt.isDefined,
      parentId = r.parentId,
      depth = r.depth,
      scope = Some(scopeOf(r))
    )
