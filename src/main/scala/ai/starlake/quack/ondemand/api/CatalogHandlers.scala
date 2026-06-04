package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader

/** Catalog browser handlers. Catalog reads are scoped to a specific
  * `(tenant, tenantDb)` pair because each tenant-db owns its own
  * DuckLake catalog. Tests stub `resolveReader`; production wires it
  * to a `(tenant, tenantDb) -> reader` cache backed by
  * `PoolSupervisor.effectiveMetastoreFor`. */
class CatalogHandlers(resolveReader: (String, String) => DuckLakeCatalogReader):

  def listSchemas(tenant: String, tenantDb: String): List[CatalogSchemaEntry] =
    resolveReader(tenant, tenantDb).listSchemas()

  def listTables(tenant: String, tenantDb: String, schema: String): List[CatalogTableEntry] =
    resolveReader(tenant, tenantDb).listTables(schema)

  def getTable(
      tenant:   String,
      tenantDb: String,
      schema:   String,
      table:    String
  ): Option[CatalogTableDetailResponse] =
    resolveReader(tenant, tenantDb).getTable(schema, table)