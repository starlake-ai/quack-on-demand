package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api._
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.syntax._
import Dtos.given

/** The agent-facing branch tools (Epic 1): `create_branch`, `list_branches`, `branch_changes`,
  * `diff`, `propose_merge`, `discard`. All data tier (`adminOnly = false`): any principal that may
  * connect to the parent database may branch it, exactly the FlightSQL handshake gate. `merge` is
  * deliberately absent: a human (a different principal than the proposer) approves through REST or
  * the CLI. Each tool curries `principal.rawToken` into the REST handler's `apiKey` seam, so the
  * handler resolves the same PAT principal this route did.
  */
final class McpBranchTools(branches: BranchHandlers, scopeOf: String => Option[SessionScope]):

  import McpToolArgs._

  def tools: List[McpToolDef] =
    List(
      createBranchTool,
      listBranchesTool,
      branchChangesTool,
      diffTool,
      proposeMergeTool,
      discardTool
    )

  private def tenantAndDb(principal: McpPrincipal, args: io.circe.JsonObject) =
    for
      tenant   <- tenantOf(principal, args)
      database <- str(args, "database").toRight("the 'database' argument is required")
    yield (tenant, database)

  private val createBranchTool = McpToolDef(
    name = "create_branch",
    description =
      "Create a writable branch of a database: a zero-copy clone of the catalog at its current " +
        "head. Then pass branch=<name> to run_sql / describe_table / list_tables to read and " +
        "write on the branch; the live database is never touched. Review with branch_changes " +
        "and diff, then propose_merge; a human merges. Branches expire after their TTL.",
    inputSchema = objectSchema(
      required = List("database", "name"),
      props = "database" -> strProp("Database (tenant-db) to branch."),
      "name" -> strProp(
        "Branch name: lowercase letter first, then letters, digits, '_' or '-', at most 48 chars."
      ),
      "ttl_hours" -> intProp(
        "Hours until the branch expires and is discarded; 0 = never; default = server setting."
      ),
      tenantProp
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        (tenant, database) <- tenantAndDb(principal, args)
        name               <- str(args, "name").toRight("the 'name' argument is required")
      yield (tenant, database, name)) match
        case Left(err)                       => IO.pure(Left(err))
        case Right((tenant, database, name)) =>
          branches
            .create(
              BranchCreateRequest(tenant, database, name, ttlHours = int(args, "ttl_hours")),
              principal.rawToken
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val listBranchesTool = McpToolDef(
    name = "list_branches",
    description =
      "List the live branches of a database (include_terminal=true adds merged, discarded and expired ones).",
    inputSchema = objectSchema(
      required = List("database"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "include_terminal" -> boolProp("Also return merged / discarded / expired branches."),
      tenantProp
    ),
    adminOnly = false,
    run = (principal, args) =>
      tenantAndDb(principal, args) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          branches
            .list(tenant, database, bool(args, "include_terminal"), principal.rawToken)(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val branchChangesTool = McpToolDef(
    name = "branch_changes",
    description =
      "The branch's change set since its fork: touched tables (created, dropped, recreated, " +
        "modified with insert/delete/update counts, or altered), conflicts against the live " +
        "database, and anything v1 cannot merge. `mergeable` is the fast-forward verdict.",
    inputSchema = objectSchema(
      required = List("database", "branch"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "branch" -> strProp("Branch name."),
      "counts" -> boolProp("Compute row counts per modified table (default true)."),
      tenantProp
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        (tenant, database) <- tenantAndDb(principal, args)
        branch             <- str(args, "branch").toRight("the 'branch' argument is required")
      yield (tenant, database, branch)) match
        case Left(err)                         => IO.pure(Left(err))
        case Right((tenant, database, branch)) =>
          branches
            .changes(tenant, database, branch, bool(args, "counts"), principal.rawToken)(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val diffTool = McpToolDef(
    name = "diff",
    description =
      "Row-level diff of one table between the branch's fork and its head: inserted, deleted " +
        "and updated rows (before/after), paginated with a cursor. Call branch_changes first to " +
        "see which tables changed.",
    inputSchema = objectSchema(
      required = List("database", "branch", "schema", "table"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "branch"      -> strProp("Branch name."),
      "schema"      -> strProp("Schema of the table."),
      "table"       -> strProp("Table name."),
      "limit"       -> intProp("Rows per page."),
      "cursor"      -> strProp("Opaque cursor from a previous page (nextCursor)."),
      "change_type" -> strProp("Filter: insert | delete | update (comma-separated)."),
      tenantProp
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        (tenant, database) <- tenantAndDb(principal, args)
        branch             <- str(args, "branch").toRight("the 'branch' argument is required")
        schema             <- str(args, "schema").toRight("the 'schema' argument is required")
        table              <- str(args, "table").toRight("the 'table' argument is required")
      yield (tenant, database, branch, schema, table)) match
        case Left(err)                                        => IO.pure(Left(err))
        case Right((tenant, database, branch, schema, table)) =>
          branches
            .diff(
              tenant,
              database,
              branch,
              schema,
              table,
              int(args, "limit"),
              str(args, "cursor"),
              str(args, "change_type"),
              principal.rawToken
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val proposeMergeTool = McpToolDef(
    name = "propose_merge",
    description =
      "Propose the branch for merge: records a merge request carrying the change set and the " +
        "conflicts as of now. A human with a different identity reviews and merges (there is no " +
        "merge tool). Refused when the branch is already proposed.",
    inputSchema = objectSchema(
      required = List("database", "branch"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "branch" -> strProp("Branch name."),
      tenantProp
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        (tenant, database) <- tenantAndDb(principal, args)
        branch             <- str(args, "branch").toRight("the 'branch' argument is required")
      yield (tenant, database, branch)) match
        case Left(err)                         => IO.pure(Left(err))
        case Right((tenant, database, branch)) =>
          branches
            .propose(BranchOpRequest(tenant, database, branch), principal.rawToken)(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val discardTool = McpToolDef(
    name = "discard",
    description =
      "Discard a branch you own (or any branch, as an admin): its compute, catalog and " +
        "branch-only files are freed. Irreversible; the live database is unaffected.",
    inputSchema = objectSchema(
      required = List("database", "branch"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "branch" -> strProp("Branch name."),
      tenantProp
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        (tenant, database) <- tenantAndDb(principal, args)
        branch             <- str(args, "branch").toRight("the 'branch' argument is required")
      yield (tenant, database, branch)) match
        case Left(err)                         => IO.pure(Left(err))
        case Right((tenant, database, branch)) =>
          branches
            .discard(BranchOpRequest(tenant, database, branch), principal.rawToken)(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )
