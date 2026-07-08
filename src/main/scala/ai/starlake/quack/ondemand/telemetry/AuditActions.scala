package ai.starlake.quack.ondemand.telemetry

/** Single source of truth for every audit action string the code can emit. Emit call sites
  * reference these constants (never a raw literal), so `GET /api/audit/actions` serves an
  * exhaustive vocabulary that cannot drift from the code. Adding an action = add the constant here,
  * add it to `all`, and use it at the call site.
  */
object AuditActions:
  // auth
  val AuthApiKeyFailure = "auth.api-key.failure"
  val AuthLogin         = "auth.login"
  val AuthLoginFailure  = "auth.login.failure"
  val AuthLogout        = "auth.logout"
  val AuthRevoke        = "auth.revoke"
  // tenant + database
  val TenantCreate      = "tenant.create"
  val TenantDelete      = "tenant.delete"
  val TenantSetDisabled = "tenant.setDisabled"
  val TenantAuthUpdate  = "tenant.auth.update"
  val DatabaseCreate    = "database.create"
  val DatabaseDelete    = "database.delete"
  val DatabaseUpdate    = "database.update"
  // pool
  val PoolCreate           = "pool.create"
  val PoolScale            = "pool.scale"
  val PoolStop             = "pool.stop"
  val PoolDelete           = "pool.delete"
  val PoolSetDisabled      = "pool.setDisabled"
  val PoolSetResources     = "pool.setResources"
  val PoolSetPodTemplate   = "pool.setPodTemplate"
  val PoolPermissionGrant  = "pool.permission.grant"
  val PoolPermissionRevoke = "pool.permission.revoke"
  // users, roles, groups
  val UserCreate             = "user.create"
  val UserUpdate             = "user.update"
  val UserDelete             = "user.delete"
  val RoleCreate             = "role.create"
  val RoleDelete             = "role.delete"
  val RolePermissionGrant    = "role.permission.grant"
  val RolePermissionRevoke   = "role.permission.revoke"
  val RoleRowPolicySet       = "role.rowPolicy.set"
  val RoleRowPolicyDelete    = "role.rowPolicy.delete"
  val RoleColumnPolicySet    = "role.columnPolicy.set"
  val RoleColumnPolicyDelete = "role.columnPolicy.delete"
  val GroupCreate            = "group.create"
  val GroupDelete            = "group.delete"
  // memberships
  val MembershipUserRoleAdd     = "membership.user-role.add"
  val MembershipUserRoleRemove  = "membership.user-role.remove"
  val MembershipUserGroupAdd    = "membership.user-group.add"
  val MembershipUserGroupRemove = "membership.user-group.remove"
  val MembershipGroupRoleAdd    = "membership.group-role.add"
  val MembershipGroupRoleRemove = "membership.group-role.remove"
  // node + statement ops, manifest
  val NodeQuarantine   = "node.quarantine"
  val NodeUnquarantine = "node.unquarantine"
  val NodeRestart      = "node.restart"
  val StatementKill    = "statement.kill"
  val ManifestImport   = "manifest.import"
  // federation
  val FederationSourceUpsert = "federation.source.upsert"
  val FederationSourceDelete = "federation.source.delete"
  val FederationSecretUpsert = "federation.secret.upsert"
  val FederationSecretDelete = "federation.secret.delete"
  // snapshot tags
  val TagCreate     = "tag.create"
  val TagDelete     = "tag.delete"
  val TagHoldCreate = "tag.hold.create"
  val TagHoldRemove = "tag.hold.remove"
  // data plane (FlightSQL)
  val SqlDenied = "sql.denied"
  val SqlWrite  = "sql.write"
  val SqlDdl    = "sql.ddl"

  /** Exhaustive sorted vocabulary served by GET /api/audit/actions. */
  val all: List[String] = List(
    AuthApiKeyFailure,
    AuthLogin,
    AuthLoginFailure,
    AuthLogout,
    AuthRevoke,
    TenantCreate,
    TenantDelete,
    TenantSetDisabled,
    TenantAuthUpdate,
    DatabaseCreate,
    DatabaseDelete,
    DatabaseUpdate,
    PoolCreate,
    PoolScale,
    PoolStop,
    PoolDelete,
    PoolSetDisabled,
    PoolSetResources,
    PoolSetPodTemplate,
    PoolPermissionGrant,
    PoolPermissionRevoke,
    UserCreate,
    UserUpdate,
    UserDelete,
    RoleCreate,
    RoleDelete,
    RolePermissionGrant,
    RolePermissionRevoke,
    RoleRowPolicySet,
    RoleRowPolicyDelete,
    RoleColumnPolicySet,
    RoleColumnPolicyDelete,
    GroupCreate,
    GroupDelete,
    MembershipUserRoleAdd,
    MembershipUserRoleRemove,
    MembershipUserGroupAdd,
    MembershipUserGroupRemove,
    MembershipGroupRoleAdd,
    MembershipGroupRoleRemove,
    NodeQuarantine,
    NodeUnquarantine,
    NodeRestart,
    StatementKill,
    ManifestImport,
    FederationSourceUpsert,
    FederationSourceDelete,
    FederationSecretUpsert,
    FederationSecretDelete,
    SqlDenied,
    SqlWrite,
    SqlDdl,
    TagCreate,
    TagDelete,
    TagHoldCreate,
    TagHoldRemove
  ).sorted

  require(all.distinct.size == all.size, "duplicate audit action in AuditActions.all")
