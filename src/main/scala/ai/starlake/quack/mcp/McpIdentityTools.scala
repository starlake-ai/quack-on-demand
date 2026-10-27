package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api.{
  AddTenantLocationRequest,
  GroupCreateRequest,
  GroupDeleteRequest,
  GroupHandlers,
  GroupRoleMembershipRequest,
  MembershipHandlers,
  RemoveTenantLocationRequest,
  RoleCreateRequest,
  RoleDeleteRequest,
  RoleHandlers,
  SetTenantAclRequest,
  SetTenantAuthRequest,
  SetTenantDisabledRequest,
  TenantHandlers,
  TenantLocationHandlers,
  TenantLocationListRequest,
  TenantOpRequest,
  TenantRequest,
  UserCreateRequest,
  UserDeleteRequest,
  UserGroupMembershipRequest,
  UserHandlers,
  UserRoleMembershipRequest,
  UserUpdateRequest
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax._

/** The MCP identity tier: tenants, users, groups, roles, memberships. Full-surface per spec
  * 2026-09-10 (supersedes the 2026-08-18 deny-list): every tool delegates to the SAME REST handlers
  * the admin UI uses, with the principal's raw bearer as `apiKey`, so superuser gates, tenant-scope
  * checks, self/floor guards, and the audit trail behave identically to REST.
  */
final class McpIdentityTools(
    tenants: TenantHandlers,
    users: UserHandlers,
    groups: GroupHandlers,
    roles: RoleHandlers,
    memberships: MembershipHandlers,
    scopeOf: SessionScope.Resolver,
    locations: TenantLocationHandlers
):

  import McpToolArgs._

  def tools: List[McpToolDef] = List(
    listTenantsTool,
    createTenantTool,
    deleteTenantTool,
    setTenantAclTool,
    listTenantLocationsTool,
    addTenantLocationTool,
    removeTenantLocationTool,
    setTenantAuthTool,
    setTenantDisabledTool,
    listUsersTool,
    createUserTool,
    updateUserTool,
    deleteUserTool,
    userEffectivePermissionsTool,
    listGroupsTool,
    createGroupTool,
    deleteGroupTool,
    listRolesTool,
    createRoleTool,
    deleteRoleTool,
    addMembershipTool,
    removeMembershipTool,
    listGroupRoleMembershipsTool
  )

  private def keyOf(principal: McpPrincipal): Option[String] = principal.rawToken

  // ---------- tenants ----------

  private val listTenantsTool = McpToolDef(
    name = "list_tenants",
    description = "List tenants visible to the caller (superusers see all; a tenant-scoped " +
      "credential sees only its own).",
    inputSchema = objectSchema(required = Nil),
    adminOnly = true,
    run = (principal, _) =>
      tenants.listTenants(keyOf(principal))(scopeOf).map(res => bridge(res).map(_.asJson))
  )

  private val createTenantTool = McpToolDef(
    name = "create_tenant",
    description = "Create a tenant. Superuser credentials only. `id` is the lowercase slug key " +
      "used in URLs and scope checks (e.g. 'acme').",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("Tenant slug id (lowercase identifier starting with a letter)."),
      "display_name"  -> strProp("Human label; defaults to the id."),
      "auth_provider" -> strProp("One of db|keycloak|google|azure|aws (default db)."),
      "auth_config"   -> objProp("Provider config object; e.g. {\"issuer\": \"...\"}.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          tenants
            .createTenant(
              TenantRequest(
                id = id,
                // The handler requires a non-empty display name; default to the id itself,
                // matching TenantRequest's own doc comment ("Defaults to id when empty").
                displayName = str(args, "display_name").getOrElse(id),
                authProvider = str(args, "auth_provider").getOrElse("db"),
                authConfig = mapArg(args, "auth_config")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val deleteTenantTool = McpToolDef(
    name = "delete_tenant",
    description = "Delete a tenant. Refused while the tenant still has pools (has_pools).",
    inputSchema = objectSchema(
      required = List("name"),
      props = "name" -> strProp("Tenant id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "name") match
        case Left(err)   => IO.pure(Left(err))
        case Right(name) =>
          tenants
            .deleteTenant(TenantOpRequest(name), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(name))))
  )

  /** `str` trims and drops a blank string to None, so it cannot express "clear this field" for an
    * Option[String] patch field where "" is meaningful (see [[SetTenantAclRequest]] and
    * `SetTenantAuthRequest.clientSecret`). Read the raw JSON value instead: absent -> None (keep),
    * "" -> Some("") (clear), non-empty -> Some(value).
    */
  private def rawStr(args: JsonObject, name: String): Option[String] =
    args(name).flatMap(_.asString)

  private val setTenantAclTool = McpToolDef(
    name = "set_tenant_acl",
    description =
      "Set a tenant's data-access authorization: mode qod (QoD grants) or opa (the tenant's " +
        "Open Policy Agent decides). Omitted fields keep their value; empty string clears.",
    inputSchema = objectSchema(
      required = List("name"),
      props = "name" -> strProp("Tenant id."),
      "mode"                -> strProp("qod | opa | empty for the manager default."),
      "opa_url"             -> strProp("OPA base URL."),
      "opa_policy_path"     -> strProp("Policy package path, default qod/authz."),
      "opa_token"           -> strProp("Bearer token for OPA (write-only)."),
      "send_statement_text" -> boolProp("Include SQL text in the OPA input.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "name") match
        case Left(err)   => IO.pure(Left(err))
        case Right(name) =>
          tenants
            .setTenantAcl(
              SetTenantAclRequest(
                name,
                rawStr(args, "mode"),
                rawStr(args, "opa_url"),
                rawStr(args, "opa_policy_path"),
                rawStr(args, "opa_token"),
                bool(args, "send_statement_text")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  // ---------- tenant authorized locations ----------

  private val listTenantLocationsTool = McpToolDef(
    name = "list_tenant_locations",
    description = "List the S3 buckets and local folders a tenant authorized for its users' file " +
      "access. A bucket's secret access key is never returned (secretSet says one is stored).",
    inputSchema =
      objectSchema(required = List("tenant"), props = "tenant" -> strProp("Tenant id.")),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "tenant") match
        case Left(err)     => IO.pure(Left(err))
        case Right(tenant) =>
          locations
            .list(TenantLocationListRequest(tenant), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val addTenantLocationTool = McpToolDef(
    name = "add_tenant_location",
    description =
      "Authorize a location for a tenant's non-superuser users: s3://<bucket>/ (a bucket only) " +
        "with that bucket's own credentials in object_store (s3_access_key_id and " +
        "s3_secret_access_key required; s3_region, s3_endpoint, s3_url_style optional), or an " +
        "absolute folder under <QOD_TENANT_FILES_ROOT>/<tenant>/. A database's data folder stays " +
        "unreachable whatever is authorized. The tenant's running nodes receive the change at once.",
    inputSchema = objectSchema(
      required = List("tenant", "location"),
      props = "tenant" -> strProp("Tenant id."),
      "location"     -> strProp("s3://<bucket>/ or an absolute local folder."),
      "object_store" -> objProp("The bucket's credentials (s3 only; the secret is write-only).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- required(args, "tenant")
        location <- required(args, "location")
      yield (tenant, location)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, location)) =>
          locations
            .add(
              AddTenantLocationRequest(tenant, location, mapArgOpt(args, "object_store")),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val removeTenantLocationTool = McpToolDef(
    name = "remove_tenant_location",
    description = "Remove an authorized location of a tenant by id (from list_tenant_locations). " +
      "The tenant's running nodes drop the bucket's secret at once.",
    inputSchema = objectSchema(
      required = List("tenant", "id"),
      props = "tenant" -> strProp("Tenant id."),
      "id" -> strProp("Location id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant <- required(args, "tenant")
        id     <- required(args, "id")
      yield (tenant, id)) match
        case Left(err)           => IO.pure(Left(err))
        case Right((tenant, id)) =>
          locations
            .remove(RemoveTenantLocationRequest(tenant, id), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val setTenantAuthTool = McpToolDef(
    name = "set_tenant_auth",
    description = "Set a tenant's auth provider, provider config and OIDC client secret. " +
      "auth_config replaces the stored config. client_secret is write-only: omitted keeps the " +
      "stored secret while issuerUrl is unchanged, empty string removes it. Adding or changing " +
      "clientSecretRef (a secret of the manager's environment) needs a superuser credential.",
    inputSchema = objectSchema(
      required = List("name", "auth_provider"),
      props = "name" -> strProp("Tenant id."),
      "auth_provider" -> strProp("One of db|keycloak|google|azure|aws."),
      "auth_config"   -> objProp("Provider config object; replaces the stored one."),
      "client_secret" -> strProp(
        "OIDC client secret (write-only). Omit to keep the stored one (dropped when issuerUrl " +
          "changes); empty string removes it."
      )
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        name     <- required(args, "name")
        provider <- required(args, "auth_provider")
      yield (name, provider)) match
        case Left(err)               => IO.pure(Left(err))
        case Right((name, provider)) =>
          tenants
            .setTenantAuth(
              SetTenantAuthRequest(
                name,
                provider,
                mapArg(args, "auth_config"),
                rawStr(args, "client_secret")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val setTenantDisabledTool = McpToolDef(
    name = "set_tenant_disabled",
    description = "Disable (true) or re-enable (false) a tenant.",
    inputSchema = objectSchema(
      required = List("name", "disabled"),
      props = "name" -> strProp("Tenant id."),
      "disabled" -> boolProp("true to disable, false to enable.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        name     <- required(args, "name")
        disabled <- bool(args, "disabled").toRight("the 'disabled' argument is required")
      yield (name, disabled)) match
        case Left(err)               => IO.pure(Left(err))
        case Right((name, disabled)) =>
          tenants
            .setTenantDisabled(SetTenantDisabledRequest(name, disabled), keyOf(principal))(
              scopeOf
            )
            .map(res => bridge(res).map(_.asJson))
  )

  // ---------- users ----------

  private val listUsersTool = McpToolDef(
    name = "list_users",
    description = "List users. Superusers may filter by tenant or omit it for all; " +
      "tenant-scoped credentials see only their own tenant.",
    inputSchema = objectSchema(
      required = Nil,
      props = tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      users
        .listUsers(str(args, "tenant"), keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  private val createUserTool = McpToolDef(
    name = "create_user",
    description = "Create a user in a tenant. Omitting 'tenant' creates a SUPERUSER, which " +
      "only a superuser credential may do.",
    inputSchema = objectSchema(
      required = List("username", "password"),
      props = "username" -> strProp("Login name."),
      "password"             -> strProp("Initial password."),
      "kind"                 -> strProp("user or admin (default user)."),
      "email"                -> strProp("Optional contact email."),
      "must_change_password" -> boolProp(
        "Mark the password temporary: login refused until changed."
      ),
      "roles" -> strListProp(
        "RBAC role names in the tenant. Omit for the default qod_all_tables; must be non-empty."
      ),
      "groups" -> strListProp(
        "Group names in the tenant. Omit for the default qod_all_pools; must be non-empty."
      ),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        username <- required(args, "username")
        password <- required(args, "password")
        roles    <- strList(args, "roles", "role names")
        groups   <- strList(args, "groups", "group names")
      yield (username, password, roles, groups)) match
        case Left(err)                                  => IO.pure(Left(err))
        case Right((username, password, roles, groups)) =>
          users
            .createUser(
              UserCreateRequest(
                tenant = str(args, "tenant"),
                username = username,
                password = password,
                kind = str(args, "kind").getOrElse("user"),
                mustChangePassword = bool(args, "must_change_password").getOrElse(false),
                email = str(args, "email"),
                roles = roles,
                groups = groups
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val updateUserTool = McpToolDef(
    name = "update_user",
    description = "Update a user by id: rotate password, change kind/email, enable/disable. " +
      "Omitted fields stay unchanged; empty email clears it. Cannot lock yourself or the " +
      "last enabled superuser.",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("User id."),
      "password"             -> strProp("New password (omit = no rotation)."),
      "kind"                 -> strProp("user or admin."),
      "email"                -> strProp("New email; empty string clears it."),
      "must_change_password" -> boolProp("Mark the password temporary."),
      "enabled"              -> boolProp("false locks the account, true unlocks.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          users
            .updateUser(
              UserUpdateRequest(
                id = id,
                password = str(args, "password"),
                kind = str(args, "kind"),
                mustChangePassword = bool(args, "must_change_password"),
                email = args("email").flatMap(_.asString), // preserve "" = clear
                enabled = bool(args, "enabled")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val deleteUserTool = McpToolDef(
    name = "delete_user",
    description = "Delete a user by id. Cannot delete yourself or the last enabled superuser.",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("User id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          users
            .deleteUser(UserDeleteRequest(id), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(id))))
  )

  private val userEffectivePermissionsTool = McpToolDef(
    name = "user_effective_permissions",
    description = "Everything a user can do: direct role, roles via groups, pool grants, and " +
      "effective table permissions.",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("User id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          users.effective(id, keyOf(principal))(scopeOf).map(res => bridge(res).map(_.asJson))
  )

  // ---------- groups & roles ----------

  private val listGroupsTool = McpToolDef(
    name = "list_groups",
    description = "List a tenant's groups. PATs infer their tenant; superusers pass 'tenant'.",
    inputSchema = objectSchema(required = Nil, props = tenantProp),
    adminOnly = true,
    run = (principal, args) =>
      tenantOf(principal, args) match
        case Left(err)     => IO.pure(Left(err))
        case Right(tenant) =>
          groups
            .listGroups(tenant, keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val createGroupTool = McpToolDef(
    name = "create_group",
    description = "Create a group in a tenant.",
    inputSchema = objectSchema(
      required = List("name"),
      props = "name" -> strProp("Group name."),
      "description" -> strProp("Optional description."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant <- tenantOf(principal, args)
        name   <- required(args, "name")
      yield (tenant, name)) match
        case Left(err)             => IO.pure(Left(err))
        case Right((tenant, name)) =>
          groups
            .createGroup(
              GroupCreateRequest(tenant, name, str(args, "description")),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val deleteGroupTool = McpToolDef(
    name = "delete_group",
    description = "Delete a group by id (memberships are detached).",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("Group id (see list_groups).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          groups
            .deleteGroup(GroupDeleteRequest(id), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(id))))
  )

  private val listRolesTool = McpToolDef(
    name = "list_roles",
    description = "List a tenant's roles. PATs infer their tenant; superusers pass 'tenant'.",
    inputSchema = objectSchema(required = Nil, props = tenantProp),
    adminOnly = true,
    run = (principal, args) =>
      tenantOf(principal, args) match
        case Left(err)     => IO.pure(Left(err))
        case Right(tenant) =>
          roles
            .listRoles(tenant, keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val createRoleTool = McpToolDef(
    name = "create_role",
    description = "Create a role in a tenant. Grant table permissions to it with " +
      "grant_role_permission.",
    inputSchema = objectSchema(
      required = List("name"),
      props = "name" -> strProp("Role name."),
      "description" -> strProp("Optional description."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant <- tenantOf(principal, args)
        name   <- required(args, "name")
      yield (tenant, name)) match
        case Left(err)             => IO.pure(Left(err))
        case Right((tenant, name)) =>
          roles
            .createRole(
              RoleCreateRequest(tenant, name, str(args, "description")),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val deleteRoleTool = McpToolDef(
    name = "delete_role",
    description = "Delete a role by id (its permissions and memberships go with it).",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("Role id (see list_roles).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          roles
            .deleteRole(RoleDeleteRequest(id), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(id))))
  )

  // ---------- memberships ----------

  private def membershipCall(
      principal: McpPrincipal,
      args: JsonObject,
      add: Boolean
  ): IO[Either[String, Json]] =
    val done = Json.obj("ok" -> Json.True)
    required(args, "kind") match
      case Left(err)          => IO.pure(Left(err))
      case Right("user_role") =>
        (for
          userId <- required(args, "user_id")
          roleId <- required(args, "role_id")
        yield UserRoleMembershipRequest(userId, roleId)) match
          case Left(err)  => IO.pure(Left(err))
          case Right(req) =>
            val io =
              if add then memberships.addUserRole(req, keyOf(principal))(scopeOf)
              else memberships.removeUserRole(req, keyOf(principal))(scopeOf)
            io.map(res => bridge(res).map(_ => done))
      case Right("user_group") =>
        (for
          userId  <- required(args, "user_id")
          groupId <- required(args, "group_id")
        yield UserGroupMembershipRequest(userId, groupId)) match
          case Left(err)  => IO.pure(Left(err))
          case Right(req) =>
            val io =
              if add then memberships.addUserGroup(req, keyOf(principal))(scopeOf)
              else memberships.removeUserGroup(req, keyOf(principal))(scopeOf)
            io.map(res => bridge(res).map(_ => done))
      case Right("group_role") =>
        (for
          groupId <- required(args, "group_id")
          roleId  <- required(args, "role_id")
        yield GroupRoleMembershipRequest(groupId, roleId)) match
          case Left(err)  => IO.pure(Left(err))
          case Right(req) =>
            val io =
              if add then memberships.addGroupRole(req, keyOf(principal))(scopeOf)
              else memberships.removeGroupRole(req, keyOf(principal))(scopeOf)
            io.map(res => bridge(res).map(_ => done))
      case Right(other) =>
        IO.pure(Left(s"unknown membership kind '$other': use user_role, user_group, group_role"))

  private val membershipSchema = objectSchema(
    required = List("kind"),
    props = "kind" -> strProp("One of user_role, user_group, group_role."),
    "user_id"  -> strProp("User id (user_role / user_group kinds)."),
    "role_id"  -> strProp("Role id (user_role / group_role kinds)."),
    "group_id" -> strProp("Group id (user_group / group_role kinds).")
  )

  private val addMembershipTool = McpToolDef(
    name = "add_membership",
    description = "Attach a user to a role or group, or a group to a role. Idempotent.",
    inputSchema = membershipSchema,
    adminOnly = true,
    run = (principal, args) => membershipCall(principal, args, add = true)
  )

  private val removeMembershipTool = McpToolDef(
    name = "remove_membership",
    description = "Detach a user from a role or group, or a group from a role. Idempotent.",
    inputSchema = membershipSchema,
    adminOnly = true,
    run = (principal, args) => membershipCall(principal, args, add = false)
  )

  private val listGroupRoleMembershipsTool = McpToolDef(
    name = "list_group_role_memberships",
    description = "List the roles attached to a group.",
    inputSchema = objectSchema(
      required = List("group_id"),
      props = "group_id" -> strProp("Group id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "group_id") match
        case Left(err)      => IO.pure(Left(err))
        case Right(groupId) =>
          memberships
            .listGroupRoles(groupId, keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )
