package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.TenantDbKind
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader

/** Catalog browser handlers. Catalog reads are scoped to a specific `(tenant, tenantDb)` pair
  * because each tenant-db owns its own DuckLake catalog. Tests stub `resolveReader`; production
  * wires it to a `(tenant, tenantDb) -> reader` cache backed by
  * `PoolSupervisor.effectiveMetastoreFor`.
  *
  * `kindOf` short-circuits the DuckLake JDBC query for tenant-dbs whose `kind` is `duckdb-file` or
  * `memory` -- those have no `ducklake_schema` metadata table to query, so the reader would 500
  * with a Postgres "relation does not exist" error. Returning empty lists keeps the UI's catalog
  * browser usable; admins can still browse via FlightSQL.
  */
class CatalogHandlers(
    resolveReader: (String, String) => DuckLakeCatalogReader,
    kindOf: (String, String) => Option[TenantDbKind] = (_, _) => Some(TenantDbKind.DuckLake)
):

  private def isDuckLake(tenant: String, tenantDb: String): Boolean =
    kindOf(tenant, tenantDb).contains(TenantDbKind.DuckLake)

  def listSchemas(tenant: String, tenantDb: String): List[CatalogSchemaEntry] =
    if !isDuckLake(tenant, tenantDb) then Nil
    else resolveReader(tenant, tenantDb).listSchemas()

  def listTables(tenant: String, tenantDb: String, schema: String): List[CatalogTableEntry] =
    if !isDuckLake(tenant, tenantDb) then Nil
    else resolveReader(tenant, tenantDb).listTables(schema)

  def listSnapshots(
      tenant: String,
      tenantDb: String,
      limit: Option[Int] = None,
      before: Option[Long] = None
  ): List[CatalogSnapshotEntry] =
    if !isDuckLake(tenant, tenantDb) then Nil
    else
      val effectiveLimit = limit.getOrElse(200).max(1).min(1000)
      resolveReader(tenant, tenantDb).listSnapshots(effectiveLimit, before)

  def getTable(
      tenant: String,
      tenantDb: String,
      schema: String,
      table: String,
      asOf: Option[Long] = None
  ): Option[CatalogTableDetailResponse] =
    if !isDuckLake(tenant, tenantDb) then None
    else resolveReader(tenant, tenantDb).getTable(schema, table, asOf)
