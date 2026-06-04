package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** RBAC REST surface, split out from [[Endpoints]] so the combined
  * static initializer of either object stays below the 64KB
  * `<clinit>` ceiling enforced by the JVM (a Scala 3 object turns each
  * `val` into a static field initializer; the RBAC + control-plane
  * surfaces together overflow that budget). Mounted by
  * [[ai.starlake.quack.ondemand.ManagerServer]] alongside the
  * control-plane endpoints. */
object RbacEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  // ----- RBAC: users -----
  val createUser: PublicEndpoint[UserCreateRequest, (sttp.model.StatusCode, ErrorResponse), UserResponse, Any] =
    base.post.in("user" / "create").in(jsonBody[UserCreateRequest]).out(jsonBody[UserResponse])

  val updateUser: PublicEndpoint[UserUpdateRequest, (sttp.model.StatusCode, ErrorResponse), UserResponse, Any] =
    base.post.in("user" / "update").in(jsonBody[UserUpdateRequest]).out(jsonBody[UserResponse])

  val deleteUser: PublicEndpoint[UserDeleteRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("user" / "delete").in(jsonBody[UserDeleteRequest])

  val listUsers: PublicEndpoint[Option[String], (sttp.model.StatusCode, ErrorResponse), UserListResponse, Any] =
    base.get.in("user" / "list").in(query[Option[String]]("tenant")).out(jsonBody[UserListResponse])

  val effectivePermissions: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), EffectivePermissionsResponse, Any] =
    base.get.in("user" / path[String]("id") / "effective").out(jsonBody[EffectivePermissionsResponse])

  // ----- RBAC: roles -----
  val createRole: PublicEndpoint[RoleCreateRequest, (sttp.model.StatusCode, ErrorResponse), RoleResponse, Any] =
    base.post.in("role" / "create").in(jsonBody[RoleCreateRequest]).out(jsonBody[RoleResponse])

  val deleteRole: PublicEndpoint[RoleDeleteRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("role" / "delete").in(jsonBody[RoleDeleteRequest])

  val listRoles: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), RoleListResponse, Any] =
    base.get.in("role" / "list").in(query[String]("tenant")).out(jsonBody[RoleListResponse])

  val grantRolePermission: PublicEndpoint[RolePermissionGrantRequest, (sttp.model.StatusCode, ErrorResponse), RolePermissionResponse, Any] =
    base.post.in("role" / "permission" / "grant").in(jsonBody[RolePermissionGrantRequest]).out(jsonBody[RolePermissionResponse])

  val revokeRolePermission: PublicEndpoint[RolePermissionRevokeRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("role" / "permission" / "revoke").in(jsonBody[RolePermissionRevokeRequest])

  val listRolePermissions: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), RolePermissionListResponse, Any] =
    base.get.in("role" / "permission" / "list").in(query[String]("roleId")).out(jsonBody[RolePermissionListResponse])

  // ----- RBAC: groups -----
  val createGroup: PublicEndpoint[GroupCreateRequest, (sttp.model.StatusCode, ErrorResponse), GroupResponse, Any] =
    base.post.in("group" / "create").in(jsonBody[GroupCreateRequest]).out(jsonBody[GroupResponse])

  val deleteGroup: PublicEndpoint[GroupDeleteRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("group" / "delete").in(jsonBody[GroupDeleteRequest])

  val listGroups: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), GroupListResponse, Any] =
    base.get.in("group" / "list").in(query[String]("tenant")).out(jsonBody[GroupListResponse])

  // ----- RBAC: memberships (204 on success) -----
  val addUserRoleMembership: PublicEndpoint[UserRoleMembershipRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("membership" / "user-role" / "add").in(jsonBody[UserRoleMembershipRequest])
  val removeUserRoleMembership: PublicEndpoint[UserRoleMembershipRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("membership" / "user-role" / "remove").in(jsonBody[UserRoleMembershipRequest])

  val addUserGroupMembership: PublicEndpoint[UserGroupMembershipRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("membership" / "user-group" / "add").in(jsonBody[UserGroupMembershipRequest])
  val removeUserGroupMembership: PublicEndpoint[UserGroupMembershipRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("membership" / "user-group" / "remove").in(jsonBody[UserGroupMembershipRequest])

  val addGroupRoleMembership: PublicEndpoint[GroupRoleMembershipRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("membership" / "group-role" / "add").in(jsonBody[GroupRoleMembershipRequest])
  val removeGroupRoleMembership: PublicEndpoint[GroupRoleMembershipRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("membership" / "group-role" / "remove").in(jsonBody[GroupRoleMembershipRequest])

  // ----- RBAC: pool permissions -----
  val grantPoolPermission: PublicEndpoint[PoolPermissionGrantRequest, (sttp.model.StatusCode, ErrorResponse), PoolPermissionResponse, Any] =
    base.post.in("pool" / "permission" / "grant").in(jsonBody[PoolPermissionGrantRequest]).out(jsonBody[PoolPermissionResponse])

  val revokePoolPermission: PublicEndpoint[PoolPermissionRevokeRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("pool" / "permission" / "revoke").in(jsonBody[PoolPermissionRevokeRequest])

  val listPoolPermissions: PublicEndpoint[(Option[String], Option[String], Option[String]), (sttp.model.StatusCode, ErrorResponse), PoolPermissionListResponse, Any] =
    base.get.in("pool" / "permission" / "list")
        .in(query[Option[String]]("tenant"))
        .in(query[Option[String]]("userId"))
        .in(query[Option[String]]("groupId"))
        .out(jsonBody[PoolPermissionListResponse])
