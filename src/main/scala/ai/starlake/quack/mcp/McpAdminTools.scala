package ai.starlake.quack.mcp

import ai.starlake.quack.model.RoleDistribution
import ai.starlake.quack.ondemand.api.{
  ActiveStatementHandlers,
  AuditHandlers,
  KillStatementRequest,
  MaintenanceHandlers,
  MaintenanceRunRequest,
  NodeHandlers,
  NodeOpRequest,
  PoolHandlers,
  ResumePoolRequest,
  ScalePoolRequest,
  SuspendPoolRequest,
  TagCreateRequest,
  TagHandlers,
  TagProtectRequest
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax._

/** The MCP admin tier: pool/node operations, statement kill, maintenance, tags, and audit search.
  * Every tool is `adminOnly = true` (the route re-checks at call time) and delegates to the SAME
  * REST handlers the admin UI uses, with the principal's raw bearer as `apiKey`, so the
  * `TenantScopeCheck` gates and audit trail behave identically to REST.
  *
  * Deny-list (spec 2026-08-18, "Decisions" item 2): nothing here can weaken protection or destroy
  * irreversibly. In particular `protect_tag` has NO unprotect argument and there is no tag-delete
  * tool; the only direction this surface can move a guardrail is ON.
  */
final class McpAdminTools(
    pools: PoolHandlers,
    nodes: NodeHandlers,
    statements: ActiveStatementHandlers,
    maintenance: MaintenanceHandlers,
    tags: TagHandlers,
    auditH: AuditHandlers,
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
    auditSearchTool
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
      "Protect a tag so its snapshot survives retention and cannot be expired. There is no " +
        "unprotect from here: protection can only be removed by a human through the UI or CLI.",
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
            // isProtected is pinned true: the deny-list (spec, Decisions item 2) forbids any
            // protection-weakening operation from /mcp, so this tool has no boolean and the
            // request is hardcoded to the protecting direction.
            .protect(
              TagProtectRequest(tenant, database, name, isProtected = true),
              keyOf(principal)
            )(
              scopeOf
            )
            .map(res => bridge(res).map(_.asJson))
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
