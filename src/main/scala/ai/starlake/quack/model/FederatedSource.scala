package ai.starlake.quack.model

import java.time.Instant

/** One ATTACH-able external catalog under a tenant-db. The `alias` field is the DuckDB catalog
  * name; ACL grants reference it as the first segment of a 3-part table ref.
  *
  * Two shapes, discriminated by `sourceType`:
  *   - `Sql`: `setupSql` is user-typed and may include INSTALL/LOAD/CREATE SECRET/ATTACH
  *     statements, with `{{alias}}` and `{{secret.NAME}}` placeholders resolved at node spawn.
  *   - `IcebergRest`: `config` holds the typed JSON
  *     (`ondemand.federation.iceberg.IcebergRestConfig`) and the SQL is rendered from it. Stored as
  *     text here because `model` is a leaf package.
  *
  * `readOnly` is persisted here; enforcement at the edge arrives with `CatalogWriteScreen` (plan
  * Task 6) and the REST create path defaults a new `iceberg_rest` source to true (Task 5). It
  * defaults to false so pre-0038 sources keep their behaviour in the meantime.
  */
final case class FederatedSource(
    id: String,
    tenantDbId: String,
    alias: String,
    setupSql: String = "",
    description: Option[String] = None,
    disabled: Boolean = false,
    createdAt: Option[Instant] = None,
    sourceType: FederatedSourceType = FederatedSourceType.Sql,
    config: Option[String] = None,
    readOnly: Boolean = false
):

  /** Empty list when the shape invariant holds. Format-specific validation of `config` lives in the
    * iceberg package, which this leaf package cannot reach.
    */
  def validate: List[String] =
    val hasSql    = setupSql.trim.nonEmpty
    val hasConfig = config.exists(_.trim.nonEmpty)
    if hasSql && hasConfig then
      List("set exactly one of setupSql / config (setupSql is for sourceType 'sql')")
    else
      sourceType match
        case FederatedSourceType.Sql =>
          if hasSql then Nil else List("setupSql is required for sourceType 'sql'")
        case FederatedSourceType.IcebergRest =>
          if hasConfig then Nil else List("config is required for sourceType 'iceberg_rest'")
