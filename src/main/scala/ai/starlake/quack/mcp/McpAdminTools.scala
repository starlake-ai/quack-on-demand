package ai.starlake.quack.mcp

import ai.starlake.quack.model.RoleDistribution
import ai.starlake.quack.ondemand.api.{
  ActiveStatementHandlers,
  AuditHandlers,
  CreatePoolRequest,
  DeletePoolRequest,
  KillStatementRequest,
  MaintenanceHandlers,
  MaintenancePolicyDeleteRequest,
  MaintenancePolicyUpsertRequest,
  MaintenanceRunRequest,
  NodeHandlers,
  NodeOpRequest,
  PoolHandlers,
  ResumePoolRequest,
  ScalePoolRequest,
  SetMaxConcurrentRequest,
  SetPoolAutoscaleRequest,
  SetPoolDisabledRequest,
  SetPoolLockdownRequest,
  SetPoolResourcesRequest,
  SetPoolTemplateRequest,
  StopPoolRequest,
  SuspendPoolRequest,
  TagCreateRequest,
  TagDeleteRequest,
  TagHandlers,
  TagProtectRequest,
  TenantDbHandlers,
  TenantDbOpRequest,
  TenantDbRequest,
  UpdateTenantDbRequest
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax._

/** The MCP admin tier: pool/node/tenant-db operations, statement kill, maintenance, tags, and audit
  * search. Every tool is `adminOnly = true` (the route re-checks at call time) and delegates to the
  * SAME REST handlers the admin UI uses, with the principal's raw bearer as `apiKey`, so the
  * `TenantScopeCheck` gates and audit trail behave identically to REST.
  *
  * Full-surface (spec 2026-09-10, supersedes the 2026-08-18 deny-list): agents are admins in this
  * deployment model, so destructive and protection-weakening operations are exposed and rely on the
  * same server-side guards REST relies on.
  */
final class McpAdminTools(
    pools: PoolHandlers,
    nodes: NodeHandlers,
    statements: ActiveStatementHandlers,
    maintenance: MaintenanceHandlers,
    tags: TagHandlers,
    auditH: AuditHandlers,
    tenantDbs: TenantDbHandlers,
    scopeOf: String => Option[SessionScope]
):

  import McpToolArgs._

  def tools: List[McpToolDef] = List(
    listPoolsTool,
    getPoolStatusTool,
    scalePoolTool,
    suspendPoolTool,
    resumePoolTool,
    restartNodeTool,
    quarantineNodeTool,
    unquarantineNodeTool,
    activeStatementsTool,
    killStatementTool,
    runMaintenanceTool,
    maintenanceRunsTool,
    createTagTool,
    protectTagTool,
    getMaintenancePolicyTool,
    upsertMaintenancePolicyTool,
    deleteMaintenancePolicyTool,
    deleteTagTool,
    auditSearchTool,
    createPoolTool,
    deletePoolTool,
    stopPoolTool,
    setPoolDisabledTool,
    setPoolResourcesTool,
    setPoolPodTemplateTool,
    setPoolLockdownTool,
    setPoolAutoscaleTool,
    setNodeMaxConcurrentTool,
    createDatabaseTool,
    listDatabasesAdminTool,
    updateDatabaseTool,
    deleteDatabaseTool,
    metastoreDefaultsTool
  )

  private def keyOf(principal: McpPrincipal): Option[String] = principal.rawToken

  // ---------- pools ----------

  private val listPoolsTool = McpToolDef(
    name = "list_pools",
    description =
      "List every pool you can manage, with nodes, health, served counts, suspended flag and " +
        "autoscale band.",
    inputSchema = objectSchema(required = Nil),
    adminOnly = true,
    run = (principal, _) =>
      pools.listPools(keyOf(principal))(scopeOf).map(res => bridge(res).map(_.asJson))
  )

  private val getPoolStatusTool = McpToolDef(
    name = "get_pool_status",
    description = "Detailed status of one pool: nodes, health, served counts, roles.",
    inputSchema = objectSchema(
      required = List("database", "pool"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool" -> strProp("Pool name."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        pool     <- required(args, "pool")
      yield (tenant, database, pool)) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((tenant, database, pool)) =>
          pools.poolStatus(tenant, database, pool).map(res => bridge(res).map(_.asJson))
  )

  private val scalePoolTool = McpToolDef(
    name = "scale_pool",
    description =
      "Scale a pool to an explicit role distribution (writers/readers/dual node counts). A pool " +
        "with a declared autoscale band refuses targets outside it (outside_band): adjust the " +
        "band first, or stay inside it.",
    inputSchema = objectSchema(
      required = List("database", "pool"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"    -> strProp("Pool name."),
      "writers" -> intProp("Write-only node count (default 0)."),
      "readers" -> intProp("Read-only node count (default 0)."),
      "dual"    -> intProp("Dual (read+write) node count (default 0)."),
      "force"   -> boolProp("Force-shrink past drain protection."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        pool     <- required(args, "pool")
      yield (tenant, database, pool)) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((tenant, database, pool)) =>
          val dist =
            RoleDistribution(
              writeonly = int(args, "writers").getOrElse(0),
              readonly = int(args, "readers").getOrElse(0),
              dual = int(args, "dual").getOrElse(0)
            )
          pools
            .scalePool(
              ScalePoolRequest(
                tenant,
                database,
                pool,
                targetSize = dist.writeonly + dist.readonly + dist.dual,
                roleDistribution = dist,
                force = bool(args, "force").getOrElse(false)
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  /** (tenant, database, pool) triple every pool tool starts from. */
  private def poolTarget(
      principal: McpPrincipal,
      args: JsonObject
  ): Either[String, (String, String, String)] =
    for
      tenant   <- tenantOf(principal, args)
      database <- required(args, "database")
      pool     <- required(args, "pool")
    yield (tenant, database, pool)

  private def poolLifecycleTool(
      name: String,
      description: String,
      act: (McpPrincipal, String, String, String) => IO[Either[String, Json]]
  ): McpToolDef =
    McpToolDef(
      name = name,
      description = description,
      inputSchema = objectSchema(
        required = List("database", "pool"),
        props = "database" -> strProp("Database (tenant-db) name."),
        "pool" -> strProp("Pool name."),
        tenantProp
      ),
      adminOnly = true,
      run = (principal, args) =>
        (for
          tenant   <- tenantOf(principal, args)
          database <- required(args, "database")
          pool     <- required(args, "pool")
        yield (tenant, database, pool)) match
          case Left(err)                       => IO.pure(Left(err))
          case Right((tenant, database, pool)) => act(principal, tenant, database, pool)
    )

  private val suspendPoolTool = poolLifecycleTool(
    "suspend_pool",
    "Suspend a pool to zero nodes, keeping its role distribution. It wakes automatically on the " +
      "next statement, or explicitly via resume_pool.",
    (principal, tenant, database, pool) =>
      pools
        .suspendPool(SuspendPoolRequest(tenant, database, pool), keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  private val resumePoolTool = poolLifecycleTool(
    "resume_pool",
    "Wake a suspended pool back to its kept role distribution.",
    (principal, tenant, database, pool) =>
      pools
        .resumePool(ResumePoolRequest(tenant, database, pool), keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  // ---------- nodes ----------

  private def nodeOpTool(
      name: String,
      description: String,
      act: (NodeOpRequest, Option[String]) => IO[Either[String, Json]]
  ): McpToolDef =
    McpToolDef(
      name = name,
      description = description,
      inputSchema = objectSchema(
        required = List("database", "pool", "node_id"),
        props = "database" -> strProp("Database (tenant-db) name."),
        "pool"    -> strProp("Pool name."),
        "node_id" -> strProp("Node id (see get_pool_status)."),
        tenantProp
      ),
      adminOnly = true,
      run = (principal, args) =>
        (for
          tenant   <- tenantOf(principal, args)
          database <- required(args, "database")
          pool     <- required(args, "pool")
          nodeId   <- required(args, "node_id")
        yield NodeOpRequest(tenant, database, pool, nodeId)) match
          case Left(err)  => IO.pure(Left(err))
          case Right(req) => act(req, principal.rawToken)
    )

  private val restartNodeTool = nodeOpTool(
    "restart_node",
    "Stop and respawn one node (fresh port and process). Use for a node that is unhealthy or " +
      "stuck on an occupied port.",
    (req, key) => nodes.restartNode(req, key)(scopeOf).map(res => bridge(res).map(_.asJson))
  )

  private val quarantineNodeTool = nodeOpTool(
    "quarantine_node",
    "Take a node out of routing without stopping it, so it can be inspected.",
    (req, key) => nodes.quarantineNode(req, key)(scopeOf).map(res => bridge(res).map(_.asJson))
  )

  private val unquarantineNodeTool = nodeOpTool(
    "unquarantine_node",
    "Return a quarantined node to routing.",
    (req, key) => nodes.unquarantineNode(req, key)(scopeOf).map(res => bridge(res).map(_.asJson))
  )

  // ---------- statements ----------

  private val activeStatementsTool = McpToolDef(
    name = "active_statements",
    description = "Statements currently executing, with user, pool, node and a SQL preview.",
    inputSchema = objectSchema(required = Nil),
    adminOnly = true,
    run = (principal, _) =>
      statements.list(keyOf(principal))(scopeOf).map(res => bridge(res).map(_.asJson))
  )

  private val killStatementTool = McpToolDef(
    name = "kill_statement",
    description =
      "Kill one running statement by id (from active_statements). An id that is no longer " +
        "running answers status already-completed, not an error.",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("Statement id from active_statements.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          statements
            .kill(KillStatementRequest(id), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  // ---------- maintenance ----------

  private val runMaintenanceTool = McpToolDef(
    name = "run_maintenance",
    description =
      "Trigger a maintenance run (flush, expire, compaction chain) on a database now; returns " +
        "the run id to watch via maintenance_runs.",
    inputSchema = objectSchema(
      required = List("database"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "scope"      -> strProp("Optional scope (e.g. one schema.table)."),
      "operations" -> strProp("Optional comma-separated operations subset."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
      yield (tenant, database)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          maintenance
            .triggerRun(
              MaintenanceRunRequest(tenant, database, str(args, "scope"), str(args, "operations")),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val maintenanceRunsTool = McpToolDef(
    name = "maintenance_runs",
    description = "Recent maintenance runs of a database, newest first.",
    inputSchema = objectSchema(
      required = List("database"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "limit" -> intProp("Max runs (default 50)."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
      yield (tenant, database)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          maintenance
            .listRuns(tenant, database, int(args, "limit"), None, keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  // ---------- tags ----------

  private val createTagTool = McpToolDef(
    name = "create_tag",
    description =
      "Name a snapshot (from list_snapshots) so it can be referenced and protected. Tags are " +
        "created unprotected; protect_tag pins them.",
    inputSchema = objectSchema(
      required = List("database", "name", "snapshot_id"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "name"        -> strProp("Tag name."),
      "snapshot_id" -> intProp("Snapshot id to tag."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        name     <- required(args, "name")
        snapshot <- long(args, "snapshot_id").toRight("the 'snapshot_id' argument is required")
      yield (tenant, database, name, snapshot)) match
        case Left(err)                                 => IO.pure(Left(err))
        case Right((tenant, database, name, snapshot)) =>
          tags
            .create(
              TagCreateRequest(tenant, database, name, snapshot, isProtected = false),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val protectTagTool = McpToolDef(
    name = "protect_tag",
    description =
      "Toggle the retention hold on a snapshot tag: protected tags pin their snapshot against " +
        "retention expiry.",
    inputSchema = objectSchema(
      required = List("database", "name", "is_protected"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "name"         -> strProp("Tag name."),
      "is_protected" -> boolProp("true sets the retention hold, false releases it."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant      <- tenantOf(principal, args)
        database    <- required(args, "database")
        name        <- required(args, "name")
        isProtected <- bool(args, "is_protected").toRight("the 'is_protected' argument is required")
      yield (tenant, database, name, isProtected)) match
        case Left(err)                                    => IO.pure(Left(err))
        case Right((tenant, database, name, isProtected)) =>
          tags
            .protect(
              TagProtectRequest(tenant, database, name, isProtected),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val getMaintenancePolicyTool = McpToolDef(
    name = "get_maintenance_policy",
    description = "List a database's maintenance policy rows plus the effective merged " +
      "policy (retention, compaction, cleanup).",
    inputSchema = objectSchema(
      required = List("database"),
      props = "database" -> strProp("Database (tenant-db) name."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
      yield (tenant, database)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          maintenance
            .listPolicies(tenant, database, keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val upsertMaintenancePolicyTool = McpToolDef(
    name = "upsert_maintenance_policy",
    description = "Create or update a maintenance policy at tenantdb, schema, or table " +
      "scope. Only the provided fields are set; others inherit.",
    inputSchema = objectSchema(
      required = List("database", "scope_kind"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "scope_kind"               -> strProp("tenantdb | schema | table."),
      "scope_schema"             -> strProp("Schema (schema/table scopes)."),
      "scope_table"              -> strProp("Table (table scope)."),
      "enabled"                  -> boolProp("Enable/disable managed maintenance at this scope."),
      "retention_days"           -> intProp("Snapshot retention window in days."),
      "compaction_enabled"       -> boolProp("Enable compaction at this scope."),
      "target_file_size"         -> strProp("Compaction target file size, e.g. '512MB'."),
      "small_file_min_count"     -> intProp("Min small files before merge."),
      "rewrite_delete_threshold" -> Json.obj(
        "type"        -> Json.fromString("number"),
        "description" -> Json.fromString("Deleted-row fraction that triggers rewrite (0-1).")
      ),
      "cleanup_grace_days"  -> intProp("Days before expired files are cleaned."),
      "orphan_min_age_days" -> intProp("Min age for orphan deletion."),
      "cron"                -> strProp("Cron expression for the maintenance schedule."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant    <- tenantOf(principal, args)
        database  <- required(args, "database")
        scopeKind <- required(args, "scope_kind")
      yield (tenant, database, scopeKind)) match
        case Left(err)                            => IO.pure(Left(err))
        case Right((tenant, database, scopeKind)) =>
          maintenance
            .upsertPolicy(
              MaintenancePolicyUpsertRequest(
                tenant = tenant,
                tenantDb = database,
                scopeKind = scopeKind,
                scopeSchema = str(args, "scope_schema"),
                scopeTable = str(args, "scope_table"),
                enabled = bool(args, "enabled"),
                retentionDays = int(args, "retention_days"),
                compactionEnabled = bool(args, "compaction_enabled"),
                targetFileSize = str(args, "target_file_size"),
                smallFileMinCount = int(args, "small_file_min_count"),
                rewriteDeleteThreshold = double(args, "rewrite_delete_threshold"),
                cleanupGraceDays = int(args, "cleanup_grace_days"),
                orphanMinAgeDays = int(args, "orphan_min_age_days"),
                cron = str(args, "cron")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val deleteMaintenancePolicyTool = McpToolDef(
    name = "delete_maintenance_policy",
    description = "Delete a maintenance policy row by id (see get_maintenance_policy).",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("Policy row id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          maintenance
            .deletePolicy(MaintenancePolicyDeleteRequest(id), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(id))))
  )

  private val deleteTagTool = McpToolDef(
    name = "delete_tag",
    description = "Delete a snapshot tag. The snapshot itself is untouched; a protected " +
      "tag must be unprotected first.",
    inputSchema = objectSchema(
      required = List("database", "name"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "name" -> strProp("Tag name."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database")
        name     <- required(args, "name")
      yield (tenant, database, name)) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((tenant, database, name)) =>
          tags
            .delete(TagDeleteRequest(tenant, database, name), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(name))))
  )

  // ---------- audit ----------

  private val auditSearchTool = McpToolDef(
    name = "audit_search",
    description =
      "Search the control-plane audit log. Filters combine with AND; results are scoped to the " +
        "tenants you can manage.",
    inputSchema = objectSchema(
      required = Nil,
      props = "family" -> strProp("Event family filter."),
      "actor"  -> strProp("Acting user filter."),
      "action" -> strProp("Action name filter."),
      "q"      -> strProp("Free-text filter."),
      "from"   -> strProp("ISO-8601 lower time bound."),
      "to"     -> strProp("ISO-8601 upper time bound."),
      "limit"  -> intProp("Max events (default 100)."),
      "tenant" -> strProp("Narrow to one tenant (within your scope).")
    ),
    adminOnly = true,
    run = (principal, args) =>
      auditH
        .list(
          str(args, "family"),
          str(args, "tenant"),
          str(args, "actor"),
          str(args, "action"),
          str(args, "q"),
          str(args, "from"),
          str(args, "to"),
          int(args, "limit"),
          None,
          None,
          keyOf(principal)
        )(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  // ---------- pool lifecycle/settings, node max-concurrent ----------

  private val createPoolTool = McpToolDef(
    name = "create_pool",
    description = "Create a pool of DuckDB nodes in a database. Sizes are per role: " +
      "writers/readers/dual. Subject to module quota gates.",
    inputSchema = objectSchema(
      required = List("database", "pool"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"             -> strProp("Pool name."),
      "writers"          -> intProp("Write-only node count (default 0)."),
      "readers"          -> intProp("Read-only node count (default 0)."),
      "dual"             -> intProp("Dual (read+write) node count (default 0)."),
      "idle_timeout_sec" -> intProp(
        "Hibernation window seconds: -1 inherit (default), 0 never, >0 explicit."
      ),
      "max_concurrent_per_node" -> intProp("Per-node statement cap (0 = unlimited)."),
      "disabled"                -> boolProp("Create disabled."),
      "start_suspended"         -> boolProp("Create suspended at zero nodes."),
      "init_sql"                -> strProp("SQL run on each node at boot."),
      "cpu"                     -> strProp("K8s CPU quantity, e.g. '500m'."),
      "memory"                  -> strProp("K8s memory quantity, e.g. '2Gi'."),
      "lockdown"                -> strProp("inherit | on | off (default inherit)."),
      "min_nodes"               -> intProp("Autoscale floor."),
      "max_nodes"               -> intProp("Autoscale ceiling."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      poolTarget(principal, args) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((tenant, database, pool)) =>
          val dist = RoleDistribution(
            writeonly = int(args, "writers").getOrElse(0),
            readonly = int(args, "readers").getOrElse(0),
            dual = int(args, "dual").getOrElse(0)
          )
          pools
            .createPool(
              CreatePoolRequest(
                tenant = tenant,
                tenantDb = database,
                pool = pool,
                size = dist.writeonly + dist.readonly + dist.dual,
                roleDistribution = dist,
                idleTimeoutSec = int(args, "idle_timeout_sec").getOrElse(-1),
                maxConcurrentPerNode = int(args, "max_concurrent_per_node").getOrElse(0),
                disabled = bool(args, "disabled").getOrElse(false),
                startSuspended = bool(args, "start_suspended").getOrElse(false),
                initSql = str(args, "init_sql"),
                cpu = str(args, "cpu").getOrElse(""),
                memory = str(args, "memory").getOrElse(""),
                lockdown = str(args, "lockdown").getOrElse("inherit"),
                minNodes = int(args, "min_nodes"),
                maxNodes = int(args, "max_nodes")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val deletePoolTool = McpToolDef(
    name = "delete_pool",
    description = "Delete a pool and stop its nodes. force=true skips drain protection.",
    inputSchema = objectSchema(
      required = List("database", "pool"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"  -> strProp("Pool name."),
      "force" -> boolProp("Force past drain protection."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      poolTarget(principal, args) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((tenant, database, pool)) =>
          pools
            .deletePool(
              DeletePoolRequest(tenant, database, pool, bool(args, "force").getOrElse(false)),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(pool))))
  )

  private val stopPoolTool = McpToolDef(
    name = "stop_pool",
    description = "Stop all of a pool's nodes without deleting the pool. force=true skips " +
      "drain protection. resume_pool or the next statement brings it back.",
    inputSchema = objectSchema(
      required = List("database", "pool"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"  -> strProp("Pool name."),
      "force" -> boolProp("Force past drain protection."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      poolTarget(principal, args) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((tenant, database, pool)) =>
          pools
            .stopPool(
              StopPoolRequest(tenant, database, pool, bool(args, "force").getOrElse(false)),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("stopped" -> Json.fromString(pool))))
  )

  private val setPoolDisabledTool = McpToolDef(
    name = "set_pool_disabled",
    description = "Disable (true) or re-enable (false) a pool for routing.",
    inputSchema = objectSchema(
      required = List("database", "pool", "disabled"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"     -> strProp("Pool name."),
      "disabled" -> boolProp("true to disable, false to enable."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        target   <- poolTarget(principal, args)
        disabled <- bool(args, "disabled").toRight("the 'disabled' argument is required")
      yield (target, disabled)) match
        case Left(err)                                   => IO.pure(Left(err))
        case Right(((tenant, database, pool), disabled)) =>
          pools
            .setPoolDisabled(
              SetPoolDisabledRequest(tenant, database, pool, disabled),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val setPoolResourcesTool = McpToolDef(
    name = "set_pool_resources",
    description = "Set a pool's per-node CPU and memory (Kubernetes quantities). " +
      "Subject to module quota gates.",
    inputSchema = objectSchema(
      required = List("database", "pool", "cpu", "memory"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"   -> strProp("Pool name."),
      "cpu"    -> strProp("K8s CPU quantity, e.g. '500m'; empty = undeclared."),
      "memory" -> strProp("K8s memory quantity, e.g. '2Gi'; empty = undeclared."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        target <- poolTarget(principal, args)
        cpu    <- required(args, "cpu")
        memory <- required(args, "memory")
      yield (target, cpu, memory)) match
        case Left(err)                                      => IO.pure(Left(err))
        case Right(((tenant, database, pool), cpu, memory)) =>
          pools
            .setResources(
              SetPoolResourcesRequest(tenant, database, pool, cpu, memory),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val setPoolPodTemplateTool = McpToolDef(
    name = "set_pool_pod_template",
    description = "Set a pool's Kubernetes pod template YAML (superuser only).",
    inputSchema = objectSchema(
      required = List("database", "pool", "pod_template_yaml"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"              -> strProp("Pool name."),
      "pod_template_yaml" -> strProp("Pod template YAML; empty string clears it."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      poolTarget(principal, args) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((tenant, database, pool)) =>
          pools
            .setPodTemplate(
              SetPoolTemplateRequest(
                tenant,
                database,
                pool,
                str(args, "pod_template_yaml").getOrElse("")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val setPoolLockdownTool = McpToolDef(
    name = "set_pool_lockdown",
    description = "Set a pool's node-lockdown override: inherit | on | off (superuser only).",
    inputSchema = objectSchema(
      required = List("database", "pool", "lockdown"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"     -> strProp("Pool name."),
      "lockdown" -> strProp("inherit | on | off."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        target   <- poolTarget(principal, args)
        lockdown <- required(args, "lockdown")
      yield (target, lockdown)) match
        case Left(err)                                   => IO.pure(Left(err))
        case Right(((tenant, database, pool), lockdown)) =>
          pools
            .setLockdown(
              SetPoolLockdownRequest(tenant, database, pool, lockdown),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val setPoolAutoscaleTool = McpToolDef(
    name = "set_pool_autoscale",
    description = "Set or clear a pool's autoscale band. Omit both bounds to clear it.",
    inputSchema = objectSchema(
      required = List("database", "pool"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"      -> strProp("Pool name."),
      "min_nodes" -> intProp("Autoscale floor; omit to clear."),
      "max_nodes" -> intProp("Autoscale ceiling; omit to clear."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      poolTarget(principal, args) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((tenant, database, pool)) =>
          pools
            .setPoolAutoscale(
              SetPoolAutoscaleRequest(
                tenant,
                database,
                pool,
                minNodes = int(args, "min_nodes"),
                maxNodes = int(args, "max_nodes")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val setNodeMaxConcurrentTool = McpToolDef(
    name = "set_node_max_concurrent",
    description = "Set one node's max concurrent statements (0 = unlimited).",
    inputSchema = objectSchema(
      required = List("database", "pool", "node_id", "max"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "pool"    -> strProp("Pool name."),
      "node_id" -> strProp("Node id (see get_pool_status)."),
      "max"     -> intProp("Max concurrent statements; 0 = unlimited."),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        target <- poolTarget(principal, args)
        nodeId <- required(args, "node_id")
        max    <- int(args, "max").toRight("the 'max' argument is required")
      yield (target, nodeId, max)) match
        case Left(err)                                      => IO.pure(Left(err))
        case Right(((tenant, database, pool), nodeId, max)) =>
          nodes
            .setMaxConcurrent(
              SetMaxConcurrentRequest(tenant, database, pool, nodeId, max),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("ok" -> Json.True)))
  )

  // ---------- tenant-dbs (databases) ----------

  private val createDatabaseTool = McpToolDef(
    name = "create_database",
    description = "Register a database (tenant-db) in a tenant: a DuckLake catalog " +
      "(kind=ducklake, needs metastore config), a DuckDB file (duckdb-file), or in-memory " +
      "(memory).",
    inputSchema = objectSchema(
      required = List("name"),
      props = "name" -> strProp("Database (tenant-db) name."),
      "kind"      -> strProp("ducklake | duckdb-file | memory (default ducklake)."),
      "metastore" -> objProp(
        "DuckLake metastore config: pgHost, pgPort, pgUser, " +
          "pgPassword, dbName, schemaName."
      ),
      "data_path"        -> strProp("Data directory / object-store path."),
      "object_store"     -> objProp("Object-store credentials config."),
      "default_database" -> strProp("Default catalog name presented to clients."),
      "default_schema"   -> strProp("Default schema presented to clients."),
      "init_sql"         -> strProp("SQL run on each node at attach."),
      "managed_storage"  -> boolProp("Let the manager provision metastore + storage."),
      "encrypted"        -> boolProp(
        "Encrypt this database's data at rest. Create-time only: it cannot be changed later."
      ),
      "encryption_key" -> strProp(
        "duckdb-file only: supply your own encryption key instead of letting QoD mint one. " +
          "Never readable back."
      ),
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
          tenantDbs
            .createTenantDb(
              TenantDbRequest(
                tenant = tenant,
                name = name,
                kind = str(args, "kind").getOrElse("ducklake"),
                metastore = mapArg(args, "metastore"),
                dataPath = str(args, "data_path").getOrElse(""),
                objectStore = mapArg(args, "object_store"),
                defaultDatabase = str(args, "default_database"),
                defaultSchema = str(args, "default_schema"),
                initSql = str(args, "init_sql").getOrElse(""),
                managedStorage = bool(args, "managed_storage").getOrElse(false),
                encrypted = bool(args, "encrypted").getOrElse(false),
                encryptionKey = str(args, "encryption_key").filter(_.nonEmpty)
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val listDatabasesAdminTool = McpToolDef(
    name = "list_databases_admin",
    description = "Admin view of a tenant's databases (tenant-dbs) with metastore config, " +
      "data paths, and federation counts. For plain catalog browsing use list_databases.",
    inputSchema = objectSchema(required = Nil, props = tenantProp),
    adminOnly = true,
    run = (principal, args) =>
      tenantOf(principal, args) match
        case Left(err)     => IO.pure(Left(err))
        case Right(tenant) =>
          tenantDbs
            .listTenantDbs(tenant, keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val updateDatabaseTool = McpToolDef(
    name = "update_database",
    description = "Update a database's metastore/object-store config, defaults, or init " +
      "SQL. Affected nodes are restarted; the response lists restart failures.",
    inputSchema = objectSchema(
      required = List("name"),
      props = "name" -> strProp("Database (tenant-db) name."),
      "metastore"        -> objProp("Replacement metastore config (omit = unchanged)."),
      "object_store"     -> objProp("Replacement object-store config (omit = unchanged)."),
      "default_database" -> strProp("New default catalog (omit = unchanged)."),
      "default_schema"   -> strProp("New default schema (omit = unchanged)."),
      "init_sql"         -> strProp("New init SQL (omit = unchanged)."),
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
          tenantDbs
            .update(
              UpdateTenantDbRequest(
                tenant = tenant,
                name = name,
                metastore = mapArgOpt(args, "metastore"),
                objectStore = mapArgOpt(args, "object_store"),
                defaultDatabase = str(args, "default_database"),
                defaultSchema = str(args, "default_schema"),
                initSql = str(args, "init_sql")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val deleteDatabaseTool = McpToolDef(
    name = "delete_database",
    description = "Unregister a database (tenant-db). purge_managed_data=true also drops " +
      "manager-provisioned metastore schema and storage.",
    inputSchema = objectSchema(
      required = List("name"),
      props = "name" -> strProp("Database (tenant-db) name."),
      "purge_managed_data" -> boolProp("Also destroy managed metastore/storage."),
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
          tenantDbs
            .deleteTenantDb(
              TenantDbOpRequest(
                tenant,
                name,
                purgeManagedData = bool(args, "purge_managed_data").getOrElse(false)
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(name))))
  )

  private val metastoreDefaultsTool = McpToolDef(
    name = "metastore_defaults",
    description = "The manager's default Postgres metastore connection values, for " +
      "prefilling create_database.",
    inputSchema = objectSchema(required = Nil),
    adminOnly = true,
    run = (principal, _) =>
      tenantDbs
        .metastoreDefaults(keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )
