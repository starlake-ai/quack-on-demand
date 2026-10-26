package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api.{
  CreateColumnPolicyRequest,
  CreateRowPolicyRequest,
  DeleteColumnPolicyRequest,
  DeleteRowPolicyRequest,
  PoolPermissionGrantRequest,
  PoolPermissionHandlers,
  PoolPermissionRevokeRequest,
  RoleColumnPolicyHandlers,
  RoleHandlers,
  RolePermissionGrantRequest,
  RolePermissionRevokeRequest,
  RoleRowPolicyHandlers,
  UpdateColumnPolicyRequest,
  UpdateRowPolicyRequest
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax._

/** The MCP access-control tier: role table permissions, column policies (masking), row policies,
  * pool permissions. Full-surface per spec 2026-09-10. Same delegation contract as the other tiers:
  * raw bearer as apiKey, REST guards and audit apply unchanged.
  */
final class McpAccessTools(
    roles: RoleHandlers,
    columnPolicies: RoleColumnPolicyHandlers,
    rowPolicies: RoleRowPolicyHandlers,
    poolPermissions: PoolPermissionHandlers,
    scopeOf: SessionScope.Resolver
):

  import McpToolArgs._

  def tools: List[McpToolDef] = List(
    grantRolePermissionTool,
    revokeRolePermissionTool,
    listRolePermissionsTool,
    createColumnPolicyTool,
    updateColumnPolicyTool,
    deleteColumnPolicyTool,
    listColumnPoliciesTool,
    createRowPolicyTool,
    updateRowPolicyTool,
    deleteRowPolicyTool,
    listRowPoliciesTool,
    grantPoolPermissionTool,
    revokePoolPermissionTool,
    listPoolPermissionsTool
  )

  private def keyOf(principal: McpPrincipal): Option[String] = principal.rawToken

  /** Shared shape: one required id arg, handler returns Unit. */
  private def byIdTool(
      name: String,
      description: String,
      idDescription: String,
      act: (McpPrincipal, String) => IO[Either[String, Json]]
  ): McpToolDef =
    McpToolDef(
      name = name,
      description = description,
      inputSchema = objectSchema(
        required = List("id"),
        props = "id" -> strProp(idDescription)
      ),
      adminOnly = true,
      run = (principal, args) =>
        required(args, "id") match
          case Left(err) => IO.pure(Left(err))
          case Right(id) => act(principal, id)
    )

  /** Shared shape: one required role_id arg, handler returns a list response. */
  private def byRoleIdTool(
      name: String,
      description: String,
      act: (McpPrincipal, String) => IO[Either[String, Json]]
  ): McpToolDef =
    McpToolDef(
      name = name,
      description = description,
      inputSchema = objectSchema(
        required = List("role_id"),
        props = "role_id" -> strProp("Role id (see list_roles).")
      ),
      adminOnly = true,
      run = (principal, args) =>
        required(args, "role_id") match
          case Left(err)     => IO.pure(Left(err))
          case Right(roleId) => act(principal, roleId)
    )

  // ---------- role table permissions ----------

  private val grantRolePermissionTool = McpToolDef(
    name = "grant_role_permission",
    description = "Grant a table permission (verb RO|RW|DDL|ALL) to a role. Catalog, schema " +
      "and table default to '*'.",
    inputSchema = objectSchema(
      required = List("role_id", "verb"),
      props = "role_id" -> strProp("Role id (see list_roles)."),
      "verb"    -> strProp("RO | RW | DDL | ALL."),
      "catalog" -> strProp("Catalog name pattern (default *)."),
      "schema"  -> strProp("Schema name pattern (default *)."),
      "table"   -> strProp("Table name pattern (default *).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        roleId <- required(args, "role_id")
        verb   <- required(args, "verb")
      yield (roleId, verb)) match
        case Left(err)             => IO.pure(Left(err))
        case Right((roleId, verb)) =>
          roles
            .grantPermission(
              RolePermissionGrantRequest(
                roleId = roleId,
                catalog = str(args, "catalog").getOrElse("*"),
                schema = str(args, "schema").getOrElse("*"),
                table = str(args, "table").getOrElse("*"),
                verb = verb
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val revokeRolePermissionTool = byIdTool(
    "revoke_role_permission",
    "Revoke a role table permission by its grant id (see list_role_permissions).",
    "Permission grant id.",
    (principal, id) =>
      roles
        .revokePermission(RolePermissionRevokeRequest(id), keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_ => Json.obj("revoked" -> Json.fromString(id))))
  )

  private val listRolePermissionsTool = byRoleIdTool(
    "list_role_permissions",
    "List the table permissions granted to a role.",
    (principal, roleId) =>
      roles
        .listPermissions(roleId, keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  // ---------- column policies (masking) ----------

  private val createColumnPolicyTool = McpToolDef(
    name = "create_column_policy",
    description = "Attach a column policy to a role: action 'deny' hides the column, 'mask' " +
      "replaces it with transform_sql (a SQL expression, e.g. '''***''').",
    inputSchema = objectSchema(
      required = List("role_id", "column_name", "action"),
      props = "role_id" -> strProp("Role id."),
      "column_name"   -> strProp("Column name."),
      "action"        -> strProp("deny | mask."),
      "transform_sql" -> strProp("Masking SQL expression (mask action only)."),
      "catalog"       -> strProp("Catalog name pattern (default *)."),
      "schema"        -> strProp("Schema name pattern (default *)."),
      "table"         -> strProp("Table name pattern (default *).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        roleId <- required(args, "role_id")
        column <- required(args, "column_name")
        action <- required(args, "action")
      yield (roleId, column, action)) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((roleId, column, action)) =>
          columnPolicies
            .create(
              CreateColumnPolicyRequest(
                roleId = roleId,
                catalogName = str(args, "catalog").getOrElse("*"),
                schemaName = str(args, "schema").getOrElse("*"),
                tableName = str(args, "table").getOrElse("*"),
                columnName = column,
                action = action,
                transformSql = str(args, "transform_sql")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val updateColumnPolicyTool = McpToolDef(
    name = "update_column_policy",
    description = "Change a column policy's action and/or transform SQL by policy id.",
    inputSchema = objectSchema(
      required = List("id", "action"),
      props = "id" -> strProp("Policy id (see list_column_policies)."),
      "action"        -> strProp("deny | mask."),
      "transform_sql" -> strProp("Masking SQL expression (mask action only).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        id     <- required(args, "id")
        action <- required(args, "action")
      yield (id, action)) match
        case Left(err)           => IO.pure(Left(err))
        case Right((id, action)) =>
          columnPolicies
            .update(
              UpdateColumnPolicyRequest(id, action, str(args, "transform_sql")),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("updated" -> Json.fromString(id))))
  )

  private val deleteColumnPolicyTool = byIdTool(
    "delete_column_policy",
    "Delete a column policy by id.",
    "Policy id (see list_column_policies).",
    (principal, id) =>
      columnPolicies
        .delete(DeleteColumnPolicyRequest(id), keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(id))))
  )

  private val listColumnPoliciesTool = byRoleIdTool(
    "list_column_policies",
    "List the column policies attached to a role.",
    (principal, roleId) =>
      columnPolicies
        .list(roleId, keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  // ---------- row policies ----------

  private val createRowPolicyTool = McpToolDef(
    name = "create_row_policy",
    description = "Attach a row-level security predicate to a role; only rows matching " +
      "predicate_sql are visible to it.",
    inputSchema = objectSchema(
      required = List("role_id", "predicate_sql"),
      props = "role_id" -> strProp("Role id."),
      "predicate_sql" -> strProp("SQL boolean predicate, e.g. \"segment = 'BUILDING'\"."),
      "catalog"       -> strProp("Catalog name pattern (default *)."),
      "schema"        -> strProp("Schema name pattern (default *)."),
      "table"         -> strProp("Table name pattern (default *).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        roleId    <- required(args, "role_id")
        predicate <- required(args, "predicate_sql")
      yield (roleId, predicate)) match
        case Left(err)                  => IO.pure(Left(err))
        case Right((roleId, predicate)) =>
          rowPolicies
            .create(
              CreateRowPolicyRequest(
                roleId = roleId,
                catalogName = str(args, "catalog").getOrElse("*"),
                schemaName = str(args, "schema").getOrElse("*"),
                tableName = str(args, "table").getOrElse("*"),
                predicateSql = predicate
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val updateRowPolicyTool = McpToolDef(
    name = "update_row_policy",
    description = "Replace a row policy's predicate by policy id.",
    inputSchema = objectSchema(
      required = List("id", "predicate_sql"),
      props = "id" -> strProp("Policy id (see list_row_policies)."),
      "predicate_sql" -> strProp("New SQL boolean predicate.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        id        <- required(args, "id")
        predicate <- required(args, "predicate_sql")
      yield (id, predicate)) match
        case Left(err)              => IO.pure(Left(err))
        case Right((id, predicate)) =>
          rowPolicies
            .update(UpdateRowPolicyRequest(id, predicate), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("updated" -> Json.fromString(id))))
  )

  private val deleteRowPolicyTool = byIdTool(
    "delete_row_policy",
    "Delete a row policy by id.",
    "Policy id (see list_row_policies).",
    (principal, id) =>
      rowPolicies
        .delete(DeleteRowPolicyRequest(id), keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(id))))
  )

  private val listRowPoliciesTool = byRoleIdTool(
    "list_row_policies",
    "List the row policies attached to a role.",
    (principal, roleId) =>
      rowPolicies
        .list(roleId, keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  // ---------- pool permissions ----------

  private val grantPoolPermissionTool = McpToolDef(
    name = "grant_pool_permission",
    description = "Grant pool access to a user OR a group (exactly one). Omit pool_id to " +
      "grant every pool in the tenant.",
    inputSchema = objectSchema(
      required = Nil,
      props = "pool_id" -> strProp("Pool id; omit for all pools in the tenant."),
      "user_id"  -> strProp("User id (set this or group_id, not both)."),
      "group_id" -> strProp("Group id (set this or user_id, not both)."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      tenantOf(principal, args) match
        case Left(err)     => IO.pure(Left(err))
        case Right(tenant) =>
          poolPermissions
            .grant(
              PoolPermissionGrantRequest(
                tenant = tenant,
                poolId = str(args, "pool_id"),
                userId = str(args, "user_id"),
                groupId = str(args, "group_id")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val revokePoolPermissionTool = byIdTool(
    "revoke_pool_permission",
    "Revoke a pool permission by its grant id (see list_pool_permissions).",
    "Pool permission grant id.",
    (principal, id) =>
      poolPermissions
        .revoke(PoolPermissionRevokeRequest(id), keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_ => Json.obj("revoked" -> Json.fromString(id))))
  )

  private val listPoolPermissionsTool = McpToolDef(
    name = "list_pool_permissions",
    description = "List pool permissions, optionally filtered by tenant, user, or group.",
    inputSchema = objectSchema(
      required = Nil,
      props = "user_id" -> strProp("Filter by user id."),
      "group_id" -> strProp("Filter by group id."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      poolPermissions
        .list(
          str(args, "tenant"),
          str(args, "user_id"),
          str(args, "group_id"),
          keyOf(principal)
        )(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )
