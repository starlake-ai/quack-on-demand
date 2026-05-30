package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader

/** Thin glue between the Tapir endpoints and the JDBC reader. Constructor
  * takes a `resolveReader` so tests can stub it; production wires it to a
  * per-tenant reader cache backed by `PoolSupervisor.effectiveMetastoreFor`
  * (Task 5 does the wiring). */
class CatalogHandlers(resolveReader: String => DuckLakeCatalogReader):

  def listSchemas(tenant: String): List[CatalogSchemaEntry] =
    resolveReader(tenant).listSchemas()

  def listTables(tenant: String, schema: String): List[CatalogTableEntry] =
    resolveReader(tenant).listTables(schema)

  def getTable(
      tenant: String,
      schema: String,
      table: String
  ): Option[CatalogTableDetailResponse] =
    resolveReader(tenant).getTable(schema, table)