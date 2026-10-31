package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{PoolKey, TenantDbKind}
import ai.starlake.quack.ondemand.api.{
  CatalogColumnEntry,
  CatalogSchemaEntry,
  CatalogTableDetailResponse,
  CatalogTableEntry
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json

import scala.concurrent.duration.DurationInt

enum NodeCatalogFailure:
  /** The database has no pool to run the metadata statement on. */
  case NoPool
  case Failed(message: String)
  case TimedOut

/** Catalog browser reads for the kinds whose catalog lives inside the node: `duckdb-file` (the
  * attached `.db` file, held open by the node) and `memory`. The manager cannot read those from
  * Postgres the way [[DuckLakeCatalogReader]] reads DuckLake's `ducklake_*` tables, so it asks a
  * node of the database's pool through DuckDB's own `duckdb_schemas()` / `duckdb_tables()` /
  * `duckdb_columns()` functions.
  *
  * Synchronous on purpose: the catalog handlers are already evaluated inside `IO.blocking` by
  * `ManagerServer` and the MCP tools, the same bridge `PoolSupervisor` uses for its own sync calls.
  */
trait NodeCatalog:
  def listSchemas(
      tenant: String,
      tenantDb: String
  ): Either[NodeCatalogFailure, List[CatalogSchemaEntry]]
  def listTables(
      tenant: String,
      tenantDb: String,
      schema: String
  ): Either[NodeCatalogFailure, List[CatalogTableEntry]]
  def getTable(
      tenant: String,
      tenantDb: String,
      schema: String,
      table: String
  ): Either[NodeCatalogFailure, Option[CatalogTableDetailResponse]]

object NodeCatalog:

  /** The DuckDB catalog a node attaches the database under: `memory` is DuckDB's built-in default
    * catalog (the spawn scripts attach nothing for it); a `duckdb-file` is attached under its
    * [[ai.starlake.quack.model.TenantDb.catalogAlias]].
    */
  def aliasFor(kind: TenantDbKind, catalogAlias: => String): String =
    if kind == TenantDbKind.InMemory then "memory" else catalogAlias

  private def lit(v: String): String = "'" + v.replace("'", "''") + "'"

  /** One row per schema of the catalog with its table count. Not filtered on
    * `duckdb_schemas().internal`: an attached file's `main` schema is flagged internal.
    */
  def schemasSql(alias: String): String =
    s"""SELECT s.schema_name,
       |  (SELECT count(*) FROM duckdb_tables() t
       |   WHERE t.database_name = s.database_name AND t.schema_name = s.schema_name) AS table_count
       |FROM duckdb_schemas() s
       |WHERE s.database_name = ${lit(alias)}
       |ORDER BY s.schema_name""".stripMargin

  def tablesSql(alias: String, schema: String, table: Option[String] = None): String =
    val tablePred = table.fold("")(t => s" AND table_name = ${lit(t)}")
    s"""SELECT schema_name, table_name, estimated_size
       |FROM duckdb_tables()
       |WHERE database_name = ${lit(alias)} AND schema_name = ${lit(schema)}$tablePred
       |ORDER BY table_name""".stripMargin

  def columnsSql(alias: String, schema: String, table: String): String =
    s"""SELECT c.column_index, c.column_name, c.data_type, c.is_nullable,
       |  list_contains(coalesce((SELECT flatten(list(k.constraint_column_names))
       |    FROM duckdb_constraints() k
       |    WHERE k.database_name = c.database_name AND k.schema_name = c.schema_name
       |      AND k.table_name = c.table_name AND k.constraint_type = 'PRIMARY KEY'), []),
       |    c.column_name) AS is_pk
       |FROM duckdb_columns() c
       |WHERE c.database_name = ${lit(alias)} AND c.schema_name = ${lit(schema)}
       |  AND c.table_name = ${lit(table)}
       |ORDER BY c.column_index""".stripMargin

  private def str(j: Json): String   = j.asString.getOrElse(j.noSpaces)
  private def long(j: Json): Long    = j.asNumber.flatMap(_.toLong).getOrElse(-1L)
  private def bool(j: Json): Boolean = j.asBoolean.getOrElse(str(j).equalsIgnoreCase("true"))

  def schemaRows(rows: List[List[Json]]): List[CatalogSchemaEntry] =
    rows.collect { case name :: count :: _ => CatalogSchemaEntry(str(name), long(count).toInt) }

  /** `rowCount` is DuckDB's `estimated_size`; a node-held catalog has no data files to list. */
  def tableRows(rows: List[List[Json]]): List[CatalogTableEntry] =
    rows.collect { case schema :: name :: size :: _ =>
      CatalogTableEntry(str(schema), str(name), long(size), dataFileCount = 0, folder = None)
    }

  def columnRows(rows: List[List[Json]]): List[CatalogColumnEntry] =
    rows.collect { case ordinal :: name :: tpe :: nullable :: pk :: _ =>
      CatalogColumnEntry(long(ordinal).toInt, str(name), str(tpe), bool(nullable), bool(pk))
    }

/** [[NodeCatalog]] over the routed executor. `query` runs one statement on `poolKey` as the
  * manager's system caller and returns its decoded rows; the router wakes a suspended pool first
  * (then holds the statement until a node is routable), so browsing a scaled-to-zero database
  * resumes it, like a query does. System, not the REST caller: the DuckLake browser reads its
  * catalog straight from Postgres without consulting grants, and this keeps the two kinds alike;
  * the REST gate (tenant scope) has already admitted the caller, and every statement here is fixed
  * text over quoted literals.
  */
final class RoutedNodeCatalog(
    pickPool: (String, String) => Option[PoolKey],
    aliasOf: (String, String) => String,
    query: (PoolKey, String) => IO[Either[RouterFailure, List[List[Json]]]],
    timeoutSec: Int
) extends NodeCatalog:
  import NodeCatalog.*

  private def run(tenant: String, tenantDb: String, sql: String => String) =
    pickPool(tenant, tenantDb) match
      case None      => Left(NodeCatalogFailure.NoPool)
      case Some(key) =>
        query(key, sql(aliasOf(tenant, tenantDb)))
          .timeout(timeoutSec.seconds)
          .attempt
          .unsafeRunSync() match
          case Right(Right(rows))   => Right(rows)
          case Right(Left(failure)) => Left(NodeCatalogFailure.Failed(failure.reason))
          case Left(_: java.util.concurrent.TimeoutException) => Left(NodeCatalogFailure.TimedOut)
          case Left(t) => Left(NodeCatalogFailure.Failed(String.valueOf(t.getMessage)))

  def listSchemas(tenant: String, tenantDb: String) =
    run(tenant, tenantDb, schemasSql).map(schemaRows)

  def listTables(tenant: String, tenantDb: String, schema: String) =
    run(tenant, tenantDb, tablesSql(_, schema)).map(tableRows)

  def getTable(tenant: String, tenantDb: String, schema: String, table: String) =
    run(tenant, tenantDb, tablesSql(_, schema, Some(table))).map(tableRows).flatMap {
      case Nil        => Right(None)
      case entry :: _ =>
        run(tenant, tenantDb, columnsSql(_, schema, table))
          .map(rows => Some(CatalogTableDetailResponse(entry, columnRows(rows), dataFiles = Nil)))
    }
