package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
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
final class UserHandlers(sup: PoolSupervisor, userStore: UserStore):

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
      enabled = true,
      roles = roles,
      groups = groups,
      poolGrants = grants
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
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.createUser(req.tenant, req.username, req.password, req.role, userStore).map {
          case Right(u) =>
            toResponseFor(u.id) match
              case Some(r) => Right(r)
              case None    =>
                Left(
                  (
                    StatusCode.InternalServerError,
                    ErrorResponse("missing", s"created user ${u.id} not found")
                  )
                )
          case Left(err) => Left((StatusCode.BadRequest, ErrorResponse("invalid_user", err)))
        }

  // ---------- /user/update ----------

  def updateUser(req: UserUpdateRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[UserResponse] =
    TenantScopeCheck.rejectForUser(apiKey, sup.tenantForUser(req.id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.updateUserPassword(req.id, req.password, req.role, userStore).map {
          case Right(u) =>
            toResponseFor(u.id) match
              case Some(r) => Right(r)
              case None    =>
                Left((StatusCode.NotFound, ErrorResponse("not_found", s"user ${u.id} not found")))
          case Left(err) =>
            val code =
              if err.startsWith("user not found") then StatusCode.NotFound
              else StatusCode.BadRequest
            Left((code, ErrorResponse("invalid_user", err)))
        }

  // ---------- /user/delete ----------

  def deleteUser(req: UserDeleteRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.rejectForUser(apiKey, sup.tenantForUser(req.id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.deleteUser(req.id).map {
          case Right(_)  => Right(())
          case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err)))
        }

  // ---------- /user/list ----------

  def listUsers(tenant: Option[String], apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[UserListResponse] = IO.blocking {
    // Superuser / static-key: honour the query param. Multi-tenant admin:
    // clamp to the requested tenant if it is in `manageableTenants`,
    // otherwise constrain the listing to the union of manageable tenants
    // (collected after the listUsers call below).
    val sessionScope                          = apiKey.flatMap(scopeOf)
    val (effectiveTenant, filterToManageable) = sessionScope match
      case None                   => (tenant, false)
      case Some(s) if s.superuser => (tenant, false)
      case Some(s)                =>
        tenant match
          case Some(t) if s.manageableTenants.contains(t) => (Some(t), false)
          // Asking for a tenant we don't manage => empty result.
          case Some(_) => (Some("__forbidden__"), false)
          // Implicit: ask for all and filter against manageable set.
          case None => (None, true)
    val users     = sup.listUsers(effectiveTenant)
    val tenantMap = tenantNameMap
    val effs      = sup.effectiveSetsForUsers(users)
    val rows0     = users.flatMap(u => effs.get(u.id).map(eff => toResponse(eff, tenantMap)))
    val rows      =
      if !filterToManageable then rows0
      else
        val allowed = sessionScope.map(_.manageableTenants).getOrElse(Set.empty)
        rows0.filter { r =>
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
