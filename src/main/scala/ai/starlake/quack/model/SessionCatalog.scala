package ai.starlake.quack.model

/** The session catalog and schema a statement runs against on a pool: the defaults unqualified
  * table references resolve to, and the catalog the CLS/RLS rewriters and the metadata filter treat
  * as "this tenant-db".
  *
  * One resolver, used by every door. The router resolves its parser defaults from it
  * (`FlightSqlRouter.defaultsOf`) and the REST data edge qualifies its generated three-part names
  * from it; if the two derived the catalog separately, the policy rewriters would qualify against
  * one catalog while the node read another.
  *
  * `metastore` is the pool's EFFECTIVE metastore (`PoolState.metastore`, the manager default merged
  * with the row per kind), not the raw row map: only the effective map is guaranteed to carry
  * `dbName` for the `duckdb-file` kind. The `*Override` arguments are the tenant-db's own
  * `defaultDatabase`/`defaultSchema` fields, which win for every kind.
  */
object SessionCatalog:

  /** The session catalog. `ducklake` and `duckdb-file` use the alias the node ATTACHes the database
    * under ([[TenantDb.catalogAlias]], so a branch resolves to its parent's name); `memory` uses
    * DuckDB's built-in `memory` catalog, since nothing is attached for that kind. An unknown kind
    * resolves to None, as does an attaching kind whose metastore names no catalog.
    */
  def database(
      kindWire: String,
      metastore: Map[String, String],
      databaseOverride: Option[String] = None
  ): Option[String] =
    databaseOverride.orElse(kindWire match
      case TenantDbKind.DuckLake.wireValue | TenantDbKind.DuckDbFile.wireValue =>
        Option(TenantDb.catalogAlias(metastore)).filter(_.nonEmpty)
      case TenantDbKind.InMemory.wireValue => Some("memory")
      case _                               => None)

  /** The session schema: the metastore's `schemaName` for the attaching kinds, `main` for `memory`,
    * None for an unknown kind or an empty `schemaName`.
    */
  def schema(
      kindWire: String,
      metastore: Map[String, String],
      schemaOverride: Option[String] = None
  ): Option[String] =
    schemaOverride.orElse(kindWire match
      case TenantDbKind.DuckLake.wireValue | TenantDbKind.DuckDbFile.wireValue =>
        metastore.get("schemaName").filter(_.nonEmpty)
      case TenantDbKind.InMemory.wireValue => Some("main")
      case _                               => None)
