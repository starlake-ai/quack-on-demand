package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.auth.TokenRestriction

/** Every name valid on a personal access token's `tools` axis: each MCP tool, plus each reserved
  * name of another surface (`TokenRestriction.ReservedToolNames`, today only `rest`, the REST data
  * edge). `GET /api/auth/pat/tools` lists it for the console's picker, and `PatHandlers.create`
  * refuses a name outside it, since an unknown name on the axis silently grants nothing.
  *
  * Static on purpose. `Main` builds the real MCP tool list only when MCP is enabled and every
  * handler it needs is wired, but a token outlives that wiring: a name stays valid on the axis
  * while MCP is off, so the catalog cannot be read off the live `McpRoutes`. Each tier below
  * mirrors one `Mcp*Tools` class, and `McpToolCatalogSpec` asserts the names and `adminOnly` flags
  * equal what those classes register, so a tool added there fails the build until it is listed
  * here. The descriptions are short labels for a person picking tools; the agent-facing text stays
  * on each `McpToolDef`.
  */
object McpToolCatalog:

  /** Where the console shows a name. An MCP tool is `Admin` exactly when it is `adminOnly` (only a
    * token whose owner is an admin is offered it), else `Data`; a reserved name is `Surface`.
    */
  enum Group(val wire: String):
    case Data    extends Group("data")
    case Admin   extends Group("admin")
    case Surface extends Group("surface")

  /** One MCP tool's static metadata. `adminOnly` must equal the registered `McpToolDef.adminOnly`.
    */
  final case class Tool(name: String, adminOnly: Boolean, description: String)

  /** One name on the axis. `label` is how a person reads it (the name itself for an MCP tool).
    * `readOnly` says whether a token with a ceiling of `RO` keeps it: `McpRoutes.ReadOnlyTools` for
    * an MCP tool, and the REST data edge only reads.
    */
  final case class Entry(
      name: String,
      label: String,
      group: Group,
      readOnly: Boolean,
      description: String
  )

  private def user(name: String, description: String): Tool =
    Tool(name, adminOnly = false, description)

  private def admin(name: String, description: String): Tool =
    Tool(name, adminOnly = true, description)

  /** [[McpDataTools]]. */
  val dataTier: List[Tool] = List(
    user("run_sql", "Execute SQL against a database, within the token's grants and ceiling."),
    user("list_databases", "List the databases you can query, with their kind and pools."),
    user("list_tables", "List the schemas and tables of a database."),
    user("describe_table", "Columns, types and a small data sample for one table."),
    user("table_history", "Snapshot history of one table: who changed it, when, and how."),
    user("list_snapshots", "Snapshots and named tags of a database, newest first."),
    user("my_usage", "Your own recent usage: per-day statement counts and latest statements.")
  )

  /** [[McpBranchTools]]. */
  val branchTier: List[Tool] = List(
    user("create_branch", "Create a writable, zero-copy branch of a database."),
    user("list_branches", "List the branches of a database."),
    user("branch_changes", "A branch's change set since its fork, with conflicts."),
    user("diff", "Row-level diff of one table between a branch's fork and its head."),
    user("propose_merge", "Propose a branch for merge; a different person approves it."),
    user("discard", "Discard a branch and free its compute, catalog and files.")
  )

  /** [[McpProfileTools]]. */
  val profileTier: List[Tool] = List(
    user("list_my_s3_credentials", "List your own S3 credentials (never their secrets)."),
    user("remove_my_s3_credential", "Remove one of your own S3 credentials.")
  )

  /** [[McpPlatformTools]]. */
  val platformTier: List[Tool] = List(
    admin("restore_snapshot", "Restore a table to an earlier snapshot or tag."),
    user("undrop_table", "Recover a dropped table from its last live snapshot."),
    user("list_recoverable", "List dropped tables still recoverable with undrop_table."),
    admin("list_federated_sources", "List a database's federated sources."),
    admin("upsert_federated_source", "Create or update a federated source."),
    admin("delete_federated_source", "Delete a federated source and its secrets."),
    admin("set_federated_secret", "Create or update a secret on a federated source."),
    admin("delete_federated_secret", "Delete a secret from a federated source."),
    admin("manifest_export", "Export the control plane as a YAML manifest (superuser only)."),
    admin("manifest_import", "Apply a YAML manifest to the control plane (superuser only)."),
    admin("create_pat", "Mint a child personal access token of the calling token."),
    admin("list_pats", "List the calling token's own subtree of tokens."),
    admin("revoke_pat", "Revoke a token in the caller's subtree."),
    admin("delete_pat", "Delete a revoked or expired token from the caller's subtree."),
    admin("get_config", "The manager's server configuration (superuser only)."),
    admin("statement_history", "Search the statement history."),
    admin("usage_trends", "Statement throughput and latency over time."),
    admin("usage_report", "Usage summary by tenant, pool or user.")
  )

  /** [[McpAdminTools]]. */
  val adminTier: List[Tool] = List(
    admin("list_pools", "List the pools you can manage, with nodes and health."),
    admin("get_pool_status", "Detailed status of one pool."),
    admin("scale_pool", "Scale a pool to an explicit role distribution."),
    admin("suspend_pool", "Suspend a pool to zero nodes."),
    admin("resume_pool", "Wake a suspended pool."),
    admin("restart_node", "Stop and respawn one node."),
    admin("quarantine_node", "Take a node out of routing without stopping it."),
    admin("unquarantine_node", "Return a quarantined node to routing."),
    admin("active_statements", "Statements currently executing."),
    admin("kill_statement", "Kill one running statement."),
    admin("run_maintenance", "Trigger a maintenance run on a database now."),
    admin("maintenance_runs", "Recent maintenance runs of a database."),
    admin("create_tag", "Name a snapshot with a tag."),
    admin("protect_tag", "Toggle the retention hold on a snapshot tag."),
    admin("get_maintenance_policy", "A database's maintenance policy, stored and effective."),
    admin("upsert_maintenance_policy", "Create or update a maintenance policy."),
    admin("delete_maintenance_policy", "Delete a maintenance policy row."),
    admin("delete_tag", "Delete a snapshot tag."),
    admin("audit_search", "Search the control-plane audit log."),
    admin("create_pool", "Create a pool of DuckDB nodes in a database."),
    admin("delete_pool", "Delete a pool and stop its nodes."),
    admin("stop_pool", "Stop a pool's nodes without deleting the pool."),
    admin("set_pool_disabled", "Disable or re-enable a pool for routing."),
    admin("set_pool_resources", "Set a pool's per-node CPU and memory."),
    admin("set_pool_init_sql", "Replace a pool's init SQL."),
    admin("set_pool_pod_template", "Set a pool's Kubernetes pod template (superuser only)."),
    admin("set_pool_autoscale", "Set or clear a pool's autoscale band."),
    admin("set_node_max_concurrent", "Set one node's max concurrent statements."),
    admin("create_database", "Register a database (tenant-db) in a tenant."),
    admin("list_databases_admin", "Admin view of a tenant's databases and their config."),
    admin("update_database", "Update a database's metastore, storage, defaults or init SQL."),
    admin("delete_database", "Unregister a database (tenant-db)."),
    admin("metastore_defaults", "The manager's default Postgres metastore values (superuser only).")
  )

  /** [[McpIdentityTools]]. */
  val identityTier: List[Tool] = List(
    admin("list_tenants", "List the tenants visible to the caller."),
    admin("create_tenant", "Create a tenant (superuser only)."),
    admin("delete_tenant", "Delete a tenant that has no pools."),
    admin("set_tenant_acl", "Set a tenant's data-access authorization (QoD grants or OPA)."),
    admin("list_tenant_locations", "List the local folders a tenant authorized for file access."),
    admin("add_tenant_location", "Authorize a local folder for a tenant's users."),
    admin("remove_tenant_location", "Remove an authorized location of a tenant."),
    admin("set_tenant_auth", "Set a tenant's auth provider and its configuration."),
    admin("set_tenant_disabled", "Disable or re-enable a tenant."),
    admin("list_users", "List users."),
    admin("create_user", "Create a user."),
    admin("update_user", "Update a user: password, kind, email, enabled."),
    admin("delete_user", "Delete a user."),
    admin("user_effective_permissions", "Everything a user can do, roles and grants resolved."),
    admin("list_groups", "List a tenant's groups."),
    admin("create_group", "Create a group in a tenant."),
    admin("delete_group", "Delete a group."),
    admin("list_roles", "List a tenant's roles."),
    admin("create_role", "Create a role in a tenant."),
    admin("delete_role", "Delete a role with its permissions and memberships."),
    admin("add_membership", "Attach a user to a role or group, or a group to a role."),
    admin("remove_membership", "Detach a user from a role or group, or a group from a role."),
    admin("list_group_role_memberships", "List the roles attached to a group.")
  )

  /** [[McpAccessTools]]. */
  val accessTier: List[Tool] = List(
    admin("grant_role_permission", "Grant a table permission to a role."),
    admin("revoke_role_permission", "Revoke a role's table permission."),
    admin("list_role_permissions", "List the table permissions granted to a role."),
    admin("create_column_policy", "Attach a column policy (deny or mask) to a role."),
    admin("update_column_policy", "Change a column policy."),
    admin("delete_column_policy", "Delete a column policy."),
    admin("list_column_policies", "List the column policies attached to a role."),
    admin("create_row_policy", "Attach a row-level security predicate to a role."),
    admin("update_row_policy", "Replace a row policy's predicate."),
    admin("delete_row_policy", "Delete a row policy."),
    admin("list_row_policies", "List the row policies attached to a role."),
    admin("grant_pool_permission", "Grant pool access to a user or a group."),
    admin("revoke_pool_permission", "Revoke a pool permission."),
    admin("list_pool_permissions", "List pool permissions.")
  )

  /** Every MCP tool, in the order `Main` concatenates the tiers. */
  val mcpTools: List[Tool] =
    dataTier ++ branchTier ++ adminTier ++ identityTier ++ accessTier ++ platformTier ++
      profileTier

  /** The reserved names, one per surface other than MCP. A reserved name added to
    * `TokenRestriction.ReservedToolNames` without an entry here fails `McpToolCatalogSpec`.
    */
  val surfaces: List[Entry] = List(
    Entry(
      name = TokenRestriction.RestTool,
      label = "REST data edge",
      group = Group.Surface,
      readOnly = true,
      description = "The read-only GET routes under /data/v1."
    )
  )

  /** The whole axis, data tools first, then admin tools, then other surfaces, each by name. */
  val entries: List[Entry] =
    (mcpTools.map { t =>
      Entry(
        name = t.name,
        label = t.name,
        group = if t.adminOnly then Group.Admin else Group.Data,
        readOnly = McpRoutes.ReadOnlyTools(t.name),
        description = t.description
      )
    } ++ surfaces).sortBy(e => (e.group.ordinal, e.name))

  val names: Set[String] = entries.map(_.name).toSet

  /** The requested names that are not on the axis, sorted, each as given. */
  def unknown(requested: Iterable[String]): List[String] =
    requested.iterator.filterNot(names).toList.distinct.sorted
