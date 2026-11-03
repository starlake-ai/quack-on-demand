package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api._
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax._
import Dtos.given

/** The agent-facing branch tools (Epic 1): `create_branch`, `list_branches`, `branch_changes`,
  * `diff`, `propose_merge`, `discard`. Listed for every PAT (`adminOnly = false`): the handlers'
  * one gate admits any principal who may connect to one of the parent's pools, in its own tenant, a
  * plain user's token and a `--drop-admin` one included, which then read as the plain users they
  * are. `merge` is deliberately absent: a human (a different principal than the proposer) approves
  * through REST or the CLI, and only an admin. Each tool curries `principal.rawToken` into the REST
  * handler's `apiKey` seam, so the handler resolves the same PAT principal this route did.
  */
final class McpBranchTools(branches: BranchHandlers, scopeOf: SessionScope.Resolver):

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
        "Hours until the branch expires and is discarded; default = server setting. An admin may " +
          "pass 0 = never; anyone else asks for 1 up to the server's default."
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
        "database, and anything v1 cannot merge. `mergeable` means no table conflict and " +
        "nothing unsupported: row conflicts are the merge's to decide, and the proposal shows " +
        "them. Unless you are an admin, only the tables you hold a grant on are named, and " +
        "`otherTables` counts the rest.",
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
      "Propose a branch you own (or any branch, as an admin) for merge: records a merge request " +
        "carrying the change set and what a merge would do to its rows now. A human with a " +
        "different identity, and an admin, reviews and merges " +
        "(there is no merge tool). Proposing again replaces an open proposal, unless its last " +
        "merge may have landed: that refuses with proposal_unsettled, and the human merges it " +
        "again or discards the branch, as the refusal says.",
    inputSchema = objectSchema(
      required = List("database", "branch"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "branch"      -> strProp("Branch name."),
      "on_conflict" -> strProp(
        "Which side wins every row conflict (a cell, a row one side deleted and the other " +
          "updated, rows matching a key): main or branch. Absent, any conflict refuses the merge."
      ),
      "keys" -> objProp(
        "schema.table to an array of column names: rows both sides inserted with the same key " +
          "become conflicts."
      ),
      tenantProp
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        (tenant, database) <- tenantAndDb(principal, args)
        branch             <- str(args, "branch").toRight("the 'branch' argument is required")
        keys               <- keysArg(args)
      yield (tenant, database, branch, keys)) match
        case Left(err)                               => IO.pure(Left(err))
        case Right((tenant, database, branch, keys)) =>
          branches
            .propose(
              BranchProposeRequest(tenant, database, branch, str(args, "on_conflict"), keys),
              principal.rawToken
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  /** `{"schema.table": ["column", ...]}`. Any other shape is an error, never "no key": a malformed
    * key must not switch the check off.
    */
  private def keysArg(args: JsonObject): Either[String, Map[String, List[String]]] =
    val err = "the 'keys' argument must be an object of schema.table to an array of column names"
    args("keys") match
      case None                => Right(Map.empty)
      case Some(j) if j.isNull => Right(Map.empty)
      case Some(j)             =>
        j.as[Map[String, List[String]]].left.map(_ => err)

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
