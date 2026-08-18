package ai.starlake.quack.mcp

import ai.starlake.quack.McpConfig
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  ArrowRowsDecoder,
  CatalogHandlers,
  CatalogHistoryHandlers,
  CatalogPreviewHandlers,
  ErrorResponse,
  PoolPicks,
  ProfileHandlers,
  TagHandlers,
  TenantDbHandlers
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax._
import sttp.model.StatusCode

/** The MCP data tier: the tools every authenticated principal gets. Each tool goes through the SAME
  * enforcement as the REST/FlightSQL surface it mirrors -- `run_sql` through the injected routed
  * executor (StatementValidator, classifier, router), the catalog reads through the handlers'
  * `TenantDbGate`/`TenantScopeCheck` with the principal's raw bearer as `apiKey` -- so there is no
  * second policy layer to keep in sync.
  *
  * Tool descriptions are agent-facing product copy: they tell the consuming agent how to work
  * (describe before querying, aggregate instead of dumping, how time travel is spelled).
  */
final class McpDataTools(
    cfg: McpConfig,
    executor: CatalogPreviewHandlers.PreviewExecutor,
    sup: PoolSupervisor,
    catalog: CatalogHandlers,
    history: CatalogHistoryHandlers,
    tags: TagHandlers,
    tenantDbs: TenantDbHandlers,
    profile: ProfileHandlers,
    scopeOf: String => Option[SessionScope]
):

  import McpDataTools._
  import McpToolArgs._

  def tools: List[McpToolDef] = List(
    runSqlTool,
    listDatabasesTool,
    listTablesTool,
    describeTableTool,
    tableHistoryTool,
    listSnapshotsTool,
    myUsageTool
  )

  // ---------- shared helpers ----------

  private def identityOf(principal: McpPrincipal): String = principal match
    case McpPrincipal.StaticKey => CatalogPreviewHandlers.SuperuserIdentity
    case McpPrincipal.Pat(p)    => p.user.username

  private def connectionIdOf(principal: McpPrincipal): String = principal match
    case McpPrincipal.StaticKey => "mcp-static"
    case McpPrincipal.Pat(p)    => s"mcp-${p.patId}"

  private def routerFailureText(f: RouterFailure): String = f match
    case RouterFailure.AccessDenied(reason)                               => reason
    case RouterFailure.Unavailable(reason) if reason.contains("resuming") =>
      "the pool is waking from suspend; retry in a few seconds"
    case other if other.reason.contains("ConnectException") =>
      // A statement can race a freshly (re)spawned node's startup: the resume hold
      // releases on the node record, not on its first passed health probe (the
      // documented readiness-hold gap). Transient by construction, so say so.
      "a node is still starting; retry in a few seconds"
    case other => other.reason

  /** Execute `sql` through the routed executor and decode at most `maxRows` rows. */
  private def execute(
      principal: McpPrincipal,
      poolKey: ai.starlake.quack.model.PoolKey,
      sql: String,
      maxRows: Int
  ): IO[Either[String, Json]] =
    executor(connectionIdOf(principal), identityOf(principal), poolKey, sql).flatMap {
      case Left(f)   => IO.pure(Left(routerFailureText(f)))
      case Right(qr) =>
        IO.blocking {
          val (columns, rows, truncated) =
            try ArrowRowsDecoder.decode(qr.rows, maxRows)
            finally qr.close()
          Right(
            Json.obj(
              "columns"    -> columns.asJson,
              "rows"       -> Json.arr(rows.map(r => Json.arr(r*))*),
              "truncated"  -> Json.fromBoolean(truncated),
              "nodeId"     -> Json.fromString(qr.nodeId),
              "durationMs" -> Json.fromLong(qr.durationMs)
            )
          )
        }
    }

  private def poolKeyFor(
      tenant: String,
      database: String,
      poolArg: Option[String]
  ): Either[String, ai.starlake.quack.model.PoolKey] =
    poolArg match
      case Some(pool) =>
        sup.findPoolKeyByTenantAndPoolName(tenant, pool) match
          case Some(key) if key.tenantDb == database => Right(key)
          case Some(key)                             =>
            Left(s"pool '$pool' serves database '${key.tenantDb}', not '$database'")
          case None => Left(s"pool '$pool' not found in tenant '$tenant'")
      case None =>
        PoolPicks
          .readPoolKey(sup, tenant, database)
          .toRight(s"no pool serves database '$database' in tenant '$tenant'")

  // ---------- run_sql ----------

  private val runSqlTool = McpToolDef(
    name = "run_sql",
    description =
      "Execute SQL against a database. Call describe_table first so you know the columns, and " +
        "prefer aggregation (GROUP BY, COUNT, LIMIT) over dumping rows: results are capped at " +
        s"${cfg.maxRows} rows and carry truncated=true when the cap cut them off. Writes are " +
        "allowed exactly as far as your grants go (RO/RW/DDL); RLS/CLS policies apply. Time " +
        "travel: SELECT ... FROM t AT (VERSION => n) with a snapshot id from list_snapshots.",
    inputSchema = objectSchema(
      required = List("sql", "database"),
      props = "sql" -> strProp("The SQL statement to execute."),
      "database" -> strProp("Target database (tenant-db) name."),
      "tenant"   -> strProp("Tenant id; only for superuser credentials (PATs infer it)."),
      "pool"     -> strProp("Pool to run on; defaults to a read-capable pool of the database."),
      "max_rows" -> intProp("Lower the server row cap for this call; it can never raise it.")
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        sql      <- str(args, "sql").toRight("the 'sql' argument is required")
        database <- str(args, "database").toRight("the 'database' argument is required")
        poolKey  <- poolKeyFor(tenant, database, str(args, "pool"))
      yield (poolKey, sql)) match
        case Left(err)             => IO.pure(Left(err))
        case Right((poolKey, sql)) =>
          val eff = math.min(cfg.maxRows, int(args, "max_rows").getOrElse(cfg.maxRows)).max(1)
          execute(principal, poolKey, sql, eff)
  )

  // ---------- list_databases ----------

  private val listDatabasesTool = McpToolDef(
    name = "list_databases",
    description =
      "List the databases (tenant-dbs) you can query, with their kind and pools. Start here " +
        "when you do not know the database name.",
    inputSchema = objectSchema(
      required = Nil,
      props = "tenant" -> strProp("Tenant id; only for superuser credentials (PATs infer it).")
    ),
    adminOnly = false,
    run = (principal, args) =>
      tenantOf(principal, args) match
        case Left(err)     => IO.pure(Left(err))
        case Right(tenant) =>
          tenantDbs
            .listTenantDbs(tenant, principal.rawToken)(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  // ---------- list_tables ----------

  private val listTablesTool = McpToolDef(
    name = "list_tables",
    description = "List schemas and tables of a database. Pass 'schema' to restrict to one schema.",
    inputSchema = objectSchema(
      required = List("database"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "schema" -> strProp("Only this schema."),
      "tenant" -> strProp("Tenant id; only for superuser credentials (PATs infer it).")
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- str(args, "database").toRight("the 'database' argument is required")
      yield (tenant, database)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          IO.blocking {
            val key = principal.rawToken
            for
              schemas <- bridge(catalog.listSchemas(tenant, database, key)(scopeOf))
              wanted = str(args, "schema").fold(schemas.map(_.name))(s => List(s))
              listed <- wanted.foldLeft[Either[String, List[Json]]](Right(Nil)) { (acc, s) =>
                acc.flatMap { done =>
                  bridge(catalog.listTables(tenant, database, s, key)(scopeOf))
                    .map(ts =>
                      done :+ Json.obj(
                        "name"   -> Json.fromString(s),
                        "tables" -> ts.asJson
                      )
                    )
                }
              }
            yield Json.obj("schemas" -> Json.arr(listed*))
          }
  )

  // ---------- describe_table ----------

  private val describeTableTool = McpToolDef(
    name = "describe_table",
    description =
      "Columns, types and a small data sample for one table. Call this before writing SQL " +
        "against a table you have not seen.",
    inputSchema = objectSchema(
      required = List("database", "schema", "table"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "schema" -> strProp("Schema name."),
      "table"  -> strProp("Table name."),
      "tenant" -> strProp("Tenant id; only for superuser credentials (PATs infer it).")
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- str(args, "database").toRight("the 'database' argument is required")
        schema   <- str(args, "schema").toRight("the 'schema' argument is required")
        table    <- str(args, "table").toRight("the 'table' argument is required")
      yield (tenant, database, schema, table)) match
        case Left(err)                                => IO.pure(Left(err))
        case Right((tenant, database, schema, table)) =>
          IO.blocking(
            bridge(
              catalog
                .getTable(tenant, database, schema, table, None, None, None, principal.rawToken)(
                  scopeOf
                )
            )
          ).flatMap {
            case Left(err)     => IO.pure(Left(err))
            case Right(detail) =>
              poolKeyFor(tenant, database, None) match
                case Left(_) =>
                  // No routable pool: the schema alone is still an answer.
                  IO.pure(Right(Json.obj("table" -> detail.asJson)))
                case Right(poolKey) =>
                  // LIMIT one past the sample cap so `truncated` marks that more rows exist.
                  val sampleSql =
                    s"""SELECT * FROM "$schema"."$table" LIMIT ${SampleRows + 1}"""
                  execute(principal, poolKey, sampleSql, SampleRows).map {
                    case Left(_)       => Right(Json.obj("table" -> detail.asJson))
                    case Right(sample) =>
                      Right(
                        Json.obj(
                          "table"   -> detail.asJson,
                          "columns" -> detail.columns.asJson,
                          "sample"  -> sample
                        )
                      )
                  }
          }
  )

  // ---------- table_history ----------

  private val tableHistoryTool = McpToolDef(
    name = "table_history",
    description =
      "Snapshot history of one table: who changed it, when, and how (change verbs). Use the " +
        "snapshot ids with run_sql time travel: AT (VERSION => n).",
    inputSchema = objectSchema(
      required = List("database", "schema", "table"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "schema" -> strProp("Schema name."),
      "table"  -> strProp("Table name."),
      "limit"  -> intProp("Max history entries (default 50)."),
      "tenant" -> strProp("Tenant id; only for superuser credentials (PATs infer it).")
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- str(args, "database").toRight("the 'database' argument is required")
        schema   <- str(args, "schema").toRight("the 'schema' argument is required")
        table    <- str(args, "table").toRight("the 'table' argument is required")
      yield (tenant, database, schema, table)) match
        case Left(err)                                => IO.pure(Left(err))
        case Right((tenant, database, schema, table)) =>
          IO.blocking(
            bridge(
              history.history(
                tenant,
                database,
                schema,
                table,
                int(args, "limit"),
                None,
                None,
                None,
                None,
                None,
                principal.rawToken
              )(scopeOf)
            ).map(_.asJson)
          )
  )

  // ---------- list_snapshots ----------

  private val listSnapshotsTool = McpToolDef(
    name = "list_snapshots",
    description =
      "Snapshots and named tags of a database, newest first. Query any snapshot with run_sql: " +
        "SELECT ... FROM t AT (VERSION => <snapshotId>), or reference a tag's snapshotId.",
    inputSchema = objectSchema(
      required = List("database"),
      props = "database" -> strProp("Database (tenant-db) name."),
      "limit"  -> intProp("Max snapshots (default 200)."),
      "tenant" -> strProp("Tenant id; only for superuser credentials (PATs infer it).")
    ),
    adminOnly = false,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- str(args, "database").toRight("the 'database' argument is required")
      yield (tenant, database)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          val key = principal.rawToken
          for
            snaps <- IO.blocking(
              bridge(
                catalog.listSnapshots(tenant, database, int(args, "limit"), None, None, key)(
                  scopeOf
                )
              )
            )
            tagged <- tags.list(tenant, database, key)(scopeOf).map(bridge)
          yield for
            s <- snaps
            t <- tagged
          yield Json.obj("snapshots" -> s.asJson, "tags" -> t.asJson)
  )

  // ---------- my_usage ----------

  private val myUsageTool = McpToolDef(
    name = "my_usage",
    description =
      "Your own recent usage: per-day statement counts and your latest statements. Self-scoped " +
        "to the PAT's owning user.",
    inputSchema = objectSchema(required = Nil, props = Seq.empty*),
    adminOnly = false,
    run = (principal, _) =>
      principal.rawToken match
        case None =>
          IO.pure(
            Left("my_usage needs a user-owned PAT; the static key has no identity to scope by")
          )
        case some =>
          for
            usage <- profile.usage(None, some).map(bridge)
            stmts <- profile.statements(Some(RecentStatements), some).map(bridge)
          yield for
            u <- usage
            s <- stmts
          yield Json.obj("usage" -> u.asJson, "statements" -> s.asJson)
  )

object McpDataTools:

  /** Sample rows shown by describe_table. */
  private val SampleRows = 5

  /** Recent statements included in my_usage. */
  private val RecentStatements = 20
