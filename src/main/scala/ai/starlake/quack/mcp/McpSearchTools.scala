package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api.{
  SearchEndpoints,
  SearchHandlers,
  SearchIndexCreateRequest,
  SearchIndexDeleteRequest,
  SearchIndexListRequest
}
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.syntax._

/** The MCP tools of QoD Search's index registry (spec 6.1): create, list and drop an index. Each
  * delegates to the REST handler with the principal's raw bearer, so the tenant scope, the audit
  * trail and the refusals are REST's, and an error REST answers 5xx or 409 goes out marked
  * ([[McpToolArgs.bridge]]) for the tenant text rule. The build (`search/index/build`) has no tool:
  * a superuser operator tool, interim until Phase 2's indexer (`McpCoverageSpec`).
  */
final class McpSearchTools(handlers: SearchHandlers, scopeOf: SessionScope.Resolver):

  import McpToolArgs._
  import SearchEndpoints.given

  def tools: List[McpToolDef] = List(createTool, listTool, dropTool)

  private def keyOf(principal: McpPrincipal): Option[String] = principal.rawToken

  /** A token's databases axis, ahead of the handler (which checks it too): a token scoped elsewhere
    * neither creates, lists nor drops in this database (security I1).
    */
  private def inScope(principal: McpPrincipal, database: String): Either[String, String] =
    if principal.restriction.allowsDatabase(database) then Right(database)
    else Left(s"token_scope: database '$database' is not permitted for this token")

  private val createTool = McpToolDef(
    name = "create_search_index",
    description = "Create a full-text search index on a DuckLake table's VARCHAR columns (1 to " +
      "32). k1 and b are fixed at 1.2 and 0.75 in this release: an options, k1 or b argument is " +
      "refused (invalid_search_options).",
    inputSchema = objectSchema(
      required = List("database", "table", "columns"),
      props = tenantProp,
      "database" -> strProp("Database (tenant-db) name."),
      "table"    -> strProp("<table> or <schema>.<table>; the schema defaults to the database's."),
      "columns"  -> strListProp("The VARCHAR columns to index, in order.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database").flatMap(inScope(principal, _))
        table    <- required(args, "table")
        columns  <- strList(args, "columns", "column names")
      yield (tenant, database, table, columns.getOrElse(Nil))) match
        case Left(err)                                 => IO.pure(Left(err))
        case Right((tenant, database, table, columns)) =>
          val namesOptions = SearchIndexCreateRequest.RefusedKeys.exists(args.contains)
          handlers
            .create(
              SearchIndexCreateRequest(tenant, database, table, columns, namesOptions),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val listTool = McpToolDef(
    name = "list_search_indexes",
    description = "List a tenant's search indexes, of one database or of all. An index whose " +
      "table was dropped reads disabled.",
    inputSchema = objectSchema(
      required = Nil,
      props = tenantProp,
      "database" -> strProp("Database (tenant-db) name; omit for every database.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- str(args, "database") match
          case Some(d) => inScope(principal, d).map(Some(_))
          case None    => Right(None)
      yield (tenant, database)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          handlers
            .list(SearchIndexListRequest(tenant, database), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val dropTool = McpToolDef(
    name = "drop_search_index",
    description = "Drop a search index, named by its table's current name or by its index_id " +
      "(from list_search_indexes), exactly one of them; index_id also reaches a disabled index " +
      "whose name a recreated table now holds.",
    inputSchema = objectSchema(
      required = List("database"),
      props = tenantProp,
      "database" -> strProp("Database (tenant-db) name."),
      "table"    -> strProp("<table> or <schema>.<table>."),
      "index_id" -> strProp("The index's id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        tenant   <- tenantOf(principal, args)
        database <- required(args, "database").flatMap(inScope(principal, _))
      yield (tenant, database)) match
        case Left(err)                 => IO.pure(Left(err))
        case Right((tenant, database)) =>
          handlers
            .delete(
              SearchIndexDeleteRequest(tenant, database, str(args, "table"), str(args, "index_id")),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )
