package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** RBAC REST surface, split out from [[Endpoints]] so the combined static initializer of either
  * object stays below the 64KB `<clinit>` ceiling enforced by the JVM (a Scala 3 object turns each
  * `val` into a static field initializer; the RBAC + control-plane surfaces together overflow that
  * budget). Mounted by [[ai.starlake.quack.ondemand.ManagerServer]] alongside the control-plane
  * endpoints.
  *
  * Every endpoint plumbs the optional `X-API-Key` header through to the handler so the handler can
  * resolve the calling session and enforce a per-request tenant-scope check. Body-tenant endpoints
  * (e.g. `createRole` carrying `tenant: String`) gate on the body field; id-only endpoints (e.g.
  * `deleteRole {id}`) resolve the owning tenant from the resource before gating. See
  * [[TenantScopeCheck]].
  */
object RbacEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  private val apiKey = header[Option[String]]("X-API-Key")

  // ----- RBAC: users -----
  val createUser: PublicEndpoint[
    (UserCreateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    UserResponse,
    Any
  ] =
    base.post
      .in("user" / "create")
      .in(jsonBody[UserCreateRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[UserResponse])

  val updateUser: PublicEndpoint[
    (UserUpdateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    UserResponse,
    Any
  ] =
    base.post
      .in("user" / "update")
      .in(jsonBody[UserUpdateRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[UserResponse])

  val deleteUser: PublicEndpoint[
    (UserDeleteRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("user" / "delete")
      .in(jsonBody[UserDeleteRequest])
      .in(Endpoints.authToken)

  val listUsers: PublicEndpoint[
    (
        Option[
          String
        ],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    UserListResponse,
    Any
  ] =
    base.get
      .in("user" / "list")
      .in(query[Option[String]]("tenant"))
      .in(Endpoints.authToken)
      .out(jsonBody[UserListResponse])

  val effectivePermissions: PublicEndpoint[
    (String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    EffectivePermissionsResponse,
    Any
  ] =
    base.get
      .in("user" / path[String]("id") / "effective")
      .in(Endpoints.authToken)
      .out(jsonBody[EffectivePermissionsResponse])

  // ----- RBAC: roles -----
  val createRole: PublicEndpoint[
    (RoleCreateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    RoleResponse,
    Any
  ] =
    base.post
      .in("role" / "create")
      .in(jsonBody[RoleCreateRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[RoleResponse])

  val deleteRole: PublicEndpoint[
    (RoleDeleteRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("role" / "delete")
      .in(jsonBody[RoleDeleteRequest])
      .in(Endpoints.authToken)

  val listRoles: PublicEndpoint[
    (String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    RoleListResponse,
    Any
  ] =
    base.get
      .in("role" / "list")
      .in(query[String]("tenant"))
      .in(apiKey)
      .out(jsonBody[RoleListResponse])

  val grantRolePermission: PublicEndpoint[
    (RolePermissionGrantRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    RolePermissionResponse,
    Any
  ] =
    base.post
      .in("role" / "permission" / "grant")
      .in(jsonBody[RolePermissionGrantRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[RolePermissionResponse])

  val revokeRolePermission: PublicEndpoint[
    (RolePermissionRevokeRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("role" / "permission" / "revoke")
      .in(jsonBody[RolePermissionRevokeRequest])
      .in(Endpoints.authToken)

  val listRolePermissions: PublicEndpoint[
    (String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    RolePermissionListResponse,
    Any
  ] =
    base.get
      .in("role" / "permission" / "list")
      .in(query[String]("roleId"))
      .in(Endpoints.authToken)
      .out(jsonBody[RolePermissionListResponse])

  // ----- RBAC: column policies -----

  val createColumnPolicy: PublicEndpoint[
    (CreateColumnPolicyRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    ColumnPolicyDto,
    Any
  ] =
    base.post
      .in("role" / "column-policy" / "create")
      .in(jsonBody[CreateColumnPolicyRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[ColumnPolicyDto])

  val updateColumnPolicy: PublicEndpoint[
    (UpdateColumnPolicyRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("role" / "column-policy" / "update")
      .in(jsonBody[UpdateColumnPolicyRequest])
      .in(Endpoints.authToken)

  val deleteColumnPolicy: PublicEndpoint[
    (DeleteColumnPolicyRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("role" / "column-policy" / "delete")
      .in(jsonBody[DeleteColumnPolicyRequest])
      .in(Endpoints.authToken)

  val listColumnPolicies: PublicEndpoint[
    (String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    ColumnPolicyListResponse,
    Any
  ] =
    base.get
      .in("role" / "column-policy" / "list")
      .in(query[String]("roleId"))
      .in(Endpoints.authToken)
      .out(jsonBody[ColumnPolicyListResponse])

  // ----- RBAC: row policies -----

  val createRowPolicy: PublicEndpoint[
    (CreateRowPolicyRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    RowPolicyDto,
    Any
  ] =
    base.post
      .in("role" / "row-policy" / "create")
      .in(jsonBody[CreateRowPolicyRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[RowPolicyDto])

  val updateRowPolicy: PublicEndpoint[
    (UpdateRowPolicyRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("role" / "row-policy" / "update")
      .in(jsonBody[UpdateRowPolicyRequest])
      .in(Endpoints.authToken)

  val deleteRowPolicy: PublicEndpoint[
    (DeleteRowPolicyRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("role" / "row-policy" / "delete")
      .in(jsonBody[DeleteRowPolicyRequest])
      .in(Endpoints.authToken)

  val listRowPolicies: PublicEndpoint[
    (String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    RowPolicyListResponse,
    Any
  ] =
    base.get
      .in("role" / "row-policy" / "list")
      .in(query[String]("roleId"))
      .in(Endpoints.authToken)
      .out(jsonBody[RowPolicyListResponse])

  // ----- RBAC: groups -----
  val createGroup: PublicEndpoint[
    (GroupCreateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    GroupResponse,
    Any
  ] =
    base.post
      .in("group" / "create")
      .in(jsonBody[GroupCreateRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[GroupResponse])

  val deleteGroup: PublicEndpoint[
    (GroupDeleteRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("group" / "delete")
      .in(jsonBody[GroupDeleteRequest])
      .in(Endpoints.authToken)

  val listGroups: PublicEndpoint[
    (String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    GroupListResponse,
    Any
  ] =
    base.get
      .in("group" / "list")
      .in(query[String]("tenant"))
      .in(apiKey)
      .out(jsonBody[GroupListResponse])

  // ----- RBAC: memberships (204 on success) -----
  val addUserRoleMembership: PublicEndpoint[
    (UserRoleMembershipRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("membership" / "user-role" / "add")
      .in(jsonBody[UserRoleMembershipRequest])
      .in(Endpoints.authToken)
  val removeUserRoleMembership: PublicEndpoint[
    (UserRoleMembershipRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("membership" / "user-role" / "remove")
      .in(jsonBody[UserRoleMembershipRequest])
      .in(Endpoints.authToken)

  val addUserGroupMembership: PublicEndpoint[
    (UserGroupMembershipRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("membership" / "user-group" / "add")
      .in(jsonBody[UserGroupMembershipRequest])
      .in(Endpoints.authToken)
  val removeUserGroupMembership: PublicEndpoint[
    (UserGroupMembershipRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("membership" / "user-group" / "remove")
      .in(jsonBody[UserGroupMembershipRequest])
      .in(Endpoints.authToken)

  val addGroupRoleMembership: PublicEndpoint[
    (GroupRoleMembershipRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("membership" / "group-role" / "add")
      .in(jsonBody[GroupRoleMembershipRequest])
      .in(Endpoints.authToken)
  val removeGroupRoleMembership: PublicEndpoint[
    (GroupRoleMembershipRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("membership" / "group-role" / "remove")
      .in(jsonBody[GroupRoleMembershipRequest])
      .in(Endpoints.authToken)

  val listGroupRoleMembership: PublicEndpoint[
    (String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    RoleListResponse,
    Any
  ] =
    base.get
      .in("membership" / "group-role" / "list")
      .in(query[String]("groupId"))
      .in(Endpoints.authToken)
      .out(jsonBody[RoleListResponse])

  // ----- RBAC: pool permissions -----
  val grantPoolPermission: PublicEndpoint[
    (PoolPermissionGrantRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolPermissionResponse,
    Any
  ] =
    base.post
      .in("pool" / "permission" / "grant")
      .in(jsonBody[PoolPermissionGrantRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[PoolPermissionResponse])

  val revokePoolPermission: PublicEndpoint[
    (PoolPermissionRevokeRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("pool" / "permission" / "revoke")
      .in(jsonBody[PoolPermissionRevokeRequest])
      .in(Endpoints.authToken)

  val listPoolPermissions: PublicEndpoint[
    (Option[String], Option[String], Option[String], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolPermissionListResponse,
    Any
  ] =
    base.get
      .in("pool" / "permission" / "list")
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("userId"))
      .in(query[Option[String]]("groupId"))
      .in(Endpoints.authToken)
      .out(jsonBody[PoolPermissionListResponse])
