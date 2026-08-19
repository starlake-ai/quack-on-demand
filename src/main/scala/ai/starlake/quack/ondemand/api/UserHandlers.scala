package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{
  PoolPermission,
  RbacGroup,
  RbacRole,
  RbacUser,
  RolePermission,
  UserStore
}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

import java.time.format.DateTimeFormatter

/** REST handlers for the RBAC user surface (`/api/user/...`). Owns the mapping between the wire
  * DTOs and the user graph; the in-memory [[ai.starlake.quack.ondemand.rbac.RbacResolver]] only
  * caches the schema-bounded slice (roles, groups, group memberships, role permissions,
  * group-scoped pool grants), so each handler invocation resolves the user-scoped state through the
  * [[ai.starlake.quack.ondemand.state.ControlPlaneStore]] via
  * [[PoolSupervisor.effectiveSetForUser]] / `effectiveSetsForUsers`.
  */
final class UserHandlers(
    sup: PoolSupervisor,
    userStore: UserStore,
    audit: AuditRecorder = AuditRecorder.noop,
    // Composed bearer lookup (session JWT or PAT), for the self-lock guard.
    // The default resolves nothing: a static-key caller has no identity and
    // skips the self-check by construction.
    sessionOf: String => Option[SessionTokenStore.Session] = _ => None
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  // ---------- mappers ----------

  /** Build the wire response from a precomputed effective-set so the caller controls how many users
    * to resolve. The tenant column is the human-readable display name when known.
    */
  def toResponse(eff: EffectiveSet, tenantNameForId: Map[String, String]): UserResponse =
    val u      = eff.user
    val roles  = eff.roles.map(_.name)
    val groups = eff.groups.map(_.name)
    val grants = eff.poolPerms.map { pp =>
      val tn = tenantNameForId.getOrElse(pp.tenantId, pp.tenantId)
      val pn = pp.poolId.getOrElse("*")
      s"$tn/$pn"
    }
    UserResponse(
      id = u.id,
      tenant = u.tenant.map(tid => tenantNameForId.getOrElse(tid, tid)),
      username = u.username,
      role = u.role,
      enabled = u.enabled,
      roles = roles,
      groups = groups,
      poolGrants = grants,
      email = u.email
    )

  /** Convenience: build the response for one user, doing the supervisor lookup inline. Returns
    * `None` if the user no longer exists.
    */
  def toResponseFor(userId: String): Option[UserResponse] =
    sup.effectiveSetForUser(userId).map(eff => toResponse(eff, tenantNameMap))

  private def tenantNameMap: Map[String, String] =
    sup.listTenants().map(t => t.id -> t.displayName).toMap

  // ---------- /user/create ----------

  /** Tenant-scope semantics: `req.tenant = None` creates a superuser, which only an existing
    * superuser session (or a static-key caller) may do. `Some(t)` requires the session to manage
    * `t`. The `req.tenant` value here is a display name OR id (the supervisor normalizes).
    */
  def createUser(req: UserCreateRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[UserResponse] =
    val scopeGate: Option[(StatusCode, ErrorResponse)] = req.tenant match
      case None =>
        // Creating a superuser: only a superuser session may do this.
        apiKey.flatMap(scopeOf) match
          case Some(s) if !s.superuser =>
            Some(
              StatusCode.Forbidden -> ErrorResponse(
                "tenant_forbidden",
                "only a superuser session may create a superuser account"
              )
            )
          case _ => None
      case Some(raw) =>
        // Map raw (display-name or id) onto the canonical tenant id so the
        // check matches `manageableTenants`.
        val canonical = sup
          .listTenants()
          .find(t => t.id == raw || t.displayName == raw.toLowerCase)
          .map(_.id)
          .getOrElse(raw)
        TenantScopeCheck.reject(apiKey, canonical)(scopeOf)
    scopeGate match
      case Some(err) =>
        // Record denied with tenant from the resolved canonical if available.
        val deniedTenant = req.tenant.flatMap(raw =>
          sup.listTenants().find(t => t.id == raw || t.displayName == raw.toLowerCase).map(_.id)
        )
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.UserCreate,
          "denied",
          tenant = deniedTenant
        )
        IO.pure(Left(err))
      case None =>
        sup
          .createUser(
            req.tenant,
            req.username,
            req.password,
            req.role,
            userStore,
            mustChangePassword = req.mustChangePassword,
            email = req.email
          )
          .map {
            case Right(u) =>
              val tenantId = u.tenant
              // NEVER include password in detail.
              audit.rest(
                apiKey,
                "control-plane",
                AuditActions.UserCreate,
                "ok",
                tenant = tenantId,
                target = Some(u.username),
                detail = Map("username" -> u.username, "role" -> u.role)
              )
              toResponseFor(u.id) match
                case Some(r) => Right(r)
                case None    =>
                  Left(
                    (
                      StatusCode.InternalServerError,
                      ErrorResponse("missing", s"created user ${u.id} not found")
                    )
                  )
            case Left(err) =>
              err match
                case SupervisorError.InvalidEmail(m) =>
                  Left((StatusCode.BadRequest, ErrorResponse("invalid_email", m)))
                case _ =>
                  Left((StatusCode.BadRequest, ErrorResponse("invalid_user", err.message)))
          }

  // ---------- self + last-superuser guards (shared by update-lock and delete) ----------

  /** The caller's own `qodstate_user` row ids, resolved from the session identity.
    *
    * Identity is (scope, username), never `profile.tenant` alone: a superuser session's
    * `profile.tenant` mirrors the REQUESTED login scope, and an OIDC session's is always None, so
    * the row tenant must come from the scope. A non-superuser without a profile tenant may manage
    * several tenants, and usernames are unique only per tenant, so the caller can be ambiguous
    * across their scope: every candidate row counts as "self" here, because for a destructive guard
    * refusing a possible-self is the safe direction.
    */
  private def callerRowIds(apiKey: Option[String]): Set[String] =
    apiKey
      .flatMap(sessionOf)
      .map { s =>
        val tenants: Set[Option[String]] =
          if s.scope.superuser then Set(None)
          else
            s.profile.tenant match
              case Some(t) => Set(Some(t))
              case None    => s.scope.manageableTenants.map(Some(_))
        tenants.flatMap(t => sup.findUser(t, s.profile.username)).map(_.id)
      }
      .getOrElse(Set.empty)

  /** Refuses removing the caller's own account, or the last enabled superuser, from the management
    * plane. Self first (the clearer message when both would apply); the floor is
    * identity-independent, so the static key and a removed admin's still-live session are covered.
    * Both answers are 400.
    *
    * NOTE (HA): the floor's count-then-write is not atomic across replicas; two concurrent admin
    * requests can each observe two enabled superusers and both pass, reaching zero. Narrow,
    * admin-only, recoverable via the static key; a conditional UPDATE or per-guard advisory lock is
    * on the HA hardening backlog.
    */
  private def selfAndFloorGuard(
      apiKey: Option[String],
      targetId: String,
      selfError: ErrorResponse,
      floorMessage: String
  ): Option[(StatusCode, ErrorResponse)] =
    if callerRowIds(apiKey).contains(targetId) then Some((StatusCode.BadRequest, selfError))
    else
      sup.findUserById(targetId) match
        case Some(t) if t.tenant.isEmpty && t.role.equalsIgnoreCase("admin") && t.enabled =>
          val enabledSuperusers =
            sup.listSuperusers().count(x => x.role.equalsIgnoreCase("admin") && x.enabled)
          if enabledSuperusers <= 1 then
            Some((StatusCode.BadRequest, ErrorResponse("last_superuser", floorMessage)))
          else None
        case _ => None

  // ---------- /user/update ----------

  def updateUser(req: UserUpdateRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[UserResponse] =
    val userTenant = sup.tenantForUser(req.id)
    TenantScopeCheck.rejectForUser(apiKey, userTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.UserUpdate,
          "denied",
          tenant = userTenant.flatten
        )
        IO.pure(Left(err))
      case None =>
        // Lock guardrails: both fire only on an explicit lock request. Order:
        // self first (the clearer message when both would apply), then the
        // last-enabled-superuser floor, which is identity-independent so the
        // static key and a locked superuser's still-live session are covered.
        val lockGuard: Option[(StatusCode, ErrorResponse)] =
          if !req.enabled.contains(false) then None
          else
            selfAndFloorGuard(
              apiKey,
              req.id,
              ErrorResponse("cannot_lock_self", "you cannot lock your own account"),
              "cannot lock the last enabled superuser; enable another superuser first"
            )
        lockGuard match
          case Some(err) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.UserUpdate,
              "denied",
              tenant = userTenant.flatten,
              detail = Map("reason" -> err._2.error)
            )
            IO.pure(Left(err))
          case None =>
            // Map the wire DTO's single-level Option onto the store's two-level shape:
            // omit (None) = unchanged, empty string = clear to NULL, non-empty = set.
            val email: Option[Option[String]] =
              req.email.map(e => if e.isEmpty then None else Some(e))
            sup
              .updateUserPassword(
                req.id,
                req.password,
                req.role,
                userStore,
                mustChangePassword = req.mustChangePassword,
                email = email,
                enabled = req.enabled
              )
              .map {
                case Right(u) =>
                  // NEVER include password in detail.
                  audit.rest(
                    apiKey,
                    "control-plane",
                    AuditActions.UserUpdate,
                    "ok",
                    tenant = u.tenant,
                    target = Some(u.username),
                    detail = req.enabled.map(e => Map("enabled" -> e.toString)).getOrElse(Map.empty)
                  )
                  toResponseFor(u.id) match
                    case Some(r) => Right(r)
                    case None    =>
                      Left(
                        (StatusCode.NotFound, ErrorResponse("not_found", s"user ${u.id} not found"))
                      )
                case Left(err) =>
                  err match
                    case SupervisorError.InvalidEmail(m) =>
                      Left((StatusCode.BadRequest, ErrorResponse("invalid_email", m)))
                    case _ =>
                      val code = err match
                        case SupervisorError.NotFound(_) => StatusCode.NotFound
                        case _                           => StatusCode.BadRequest
                      Left((code, ErrorResponse("invalid_user", err.message)))
              }

  // ---------- /user/delete ----------

  def deleteUser(req: UserDeleteRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    val userTenant     = sup.tenantForUser(req.id)
    val usernameLookup = sup.effectiveSetForUser(req.id).map(_.user.username)
    TenantScopeCheck.rejectForUser(apiKey, userTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.UserDelete,
          "denied",
          tenant = userTenant.flatten
        )
        IO.pure(Left(err))
      case None =>
        // Same guards as the lock: delete would otherwise be the loophole that
        // empties the enabled-superuser set the lock floor protects (a deleted
        // seeded admin IS re-created at restart, unlike a locked one, but the
        // deployment still loses its management plane until then).
        selfAndFloorGuard(
          apiKey,
          req.id,
          ErrorResponse("cannot_delete_self", "you cannot delete your own account"),
          "cannot delete the last enabled superuser; enable another superuser first"
        ) match
          case Some(err) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.UserDelete,
              "denied",
              tenant = userTenant.flatten,
              detail = Map("reason" -> err._2.error)
            )
            IO.pure(Left(err))
          case None => deleteAdmitted(req, apiKey, userTenant, usernameLookup)

  private def deleteAdmitted(
      req: UserDeleteRequest,
      apiKey: Option[String],
      userTenant: Option[Option[String]],
      usernameLookup: Option[String]
  ): Out[Unit] =
    sup.deleteUser(req.id).map {
      case Right(_) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.UserDelete,
          "ok",
          tenant = userTenant.flatten,
          target = usernameLookup
        )
        Right(())
      case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
    }

  // ---------- /user/list ----------

  /** How `listUsers` resolves the requested tenant against the caller's session scope. Replaces a
    * former `"__forbidden__"` sentinel string threaded through `sup.listUsers`.
    */
  private enum UserListPlan:
    /** Query the store for this tenant (or all tenants when `None`); no further filtering. */
    case Query(tenant: Option[String])

    /** Query for all tenants, then filter rows down to `allowed` after the mapper runs. */
    case QueryAllFilterTo(allowed: Set[String])

    /** The caller asked for a specific tenant it cannot manage: always empty, no store round-trip
      * needed.
      */
    case Empty

  private def planListUsers(
      tenant: Option[String],
      sessionScope: Option[SessionScope]
  ): UserListPlan =
    sessionScope match
      case None                   => UserListPlan.Query(tenant)
      case Some(s) if s.superuser => UserListPlan.Query(tenant)
      case Some(s)                =>
        tenant match
          case Some(t) if s.manageableTenants.contains(t) => UserListPlan.Query(Some(t))
          case Some(_)                                    => UserListPlan.Empty
          case None => UserListPlan.QueryAllFilterTo(s.manageableTenants)

  def listUsers(tenant: Option[String], apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[UserListResponse] = IO.blocking {
    // Superuser / static-key: honour the query param. Multi-tenant admin:
    // clamp to the requested tenant if it is in `manageableTenants`,
    // otherwise constrain the listing to the union of manageable tenants
    // (collected after the listUsers call below).
    val sessionScope = apiKey.flatMap(scopeOf)
    val plan         = planListUsers(tenant, sessionScope)
    val tenantMap    = tenantNameMap

    def mappedRows(effectiveTenant: Option[String]): List[UserResponse] =
      val users = sup.listUsers(effectiveTenant)
      val effs  = sup.effectiveSetsForUsers(users)
      users.flatMap(u => effs.get(u.id).map(eff => toResponse(eff, tenantMap)))

    val rows = plan match
      case UserListPlan.Empty                     => Nil
      case UserListPlan.Query(effectiveTenant)    => mappedRows(effectiveTenant)
      case UserListPlan.QueryAllFilterTo(allowed) =>
        mappedRows(None).filter { r =>
          // r.tenant carries the display NAME; map back to id via tenantMap.
          r.tenant.flatMap(name => tenantMap.find(_._2 == name).map(_._1)) match
            case Some(id) => allowed.contains(id)
            case None     => false
        }
    Right(UserListResponse(rows))
  }

  // ---------- /user/{id}/effective ----------

  def effective(id: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[EffectivePermissionsResponse] =
    TenantScopeCheck.rejectForUser(apiKey, sup.tenantForUser(id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        IO.blocking {
          sup.effectiveSetForUser(id) match
            case None =>
              Left((StatusCode.NotFound, ErrorResponse("not_found", s"user not found: $id")))
            case Some(eff) =>
              Right(
                EffectivePermissionsResponse(
                  user = toResponse(eff, tenantNameMap),
                  roles = eff.roles.map(toRoleResponse),
                  groups = eff.groups.map(toGroupResponse),
                  pools = eff.poolPerms.map(toPoolPermissionResponse),
                  tablePerms = eff.permissions.map(toRolePermissionResponse)
                )
              )
        }

  // ---------- mapper helpers (shared with Role/Group/Pool handlers) ----------

  def toRoleResponse(r: RbacRole): RoleResponse =
    RoleResponse(
      id = r.id,
      tenantId = r.tenantId,
      name = r.name,
      description = r.description,
      createdAt = r.createdAt
        .map(_.toString)
        .getOrElse(DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()))
    )

  def toGroupResponse(g: RbacGroup): GroupResponse =
    GroupResponse(
      id = g.id,
      tenantId = g.tenantId,
      name = g.name,
      description = g.description
    )

  def toPoolPermissionResponse(p: PoolPermission): PoolPermissionResponse =
    PoolPermissionResponse(
      id = p.id,
      tenantId = p.tenantId,
      poolId = p.poolId,
      userId = p.userId,
      groupId = p.groupId,
      grantedAt = p.grantedAt.map(_.toString).getOrElse("")
    )

  def toRolePermissionResponse(p: RolePermission): RolePermissionResponse =
    RolePermissionResponse(
      id = p.id,
      roleId = p.roleId,
      catalogName = p.catalogName,
      schemaName = p.schemaName,
      tableName = p.tableName,
      verb = p.verb,
      grantedAt = p.grantedAt.map(_.toString).getOrElse("")
    )
