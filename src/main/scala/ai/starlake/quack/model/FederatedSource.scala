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
  * `readOnly` is persisted here; enforcement at the edge is
  * [[ai.starlake.quack.edge.sql.CatalogWriteScreen]], a defence-in-depth screen wired into
  * `FlightSqlRouter` (the ACL graph remains the primary gate). That screen parses every statement
  * it cannot positively prove is read-side and denies a write it resolves against this catalog, or
  * cannot fully resolve at all -- it does NOT trust a "classified as not a write" verdict as proof
  * that a statement is a read; see `CatalogWriteScreen`'s scaladoc for the exact per-statement
  * rule. The consequence an operator needs to know before flipping this flag: while ANY source on
  * the pool is read-only, every write on the WHOLE pool must be fully parseable and fully qualified
  * or it is refused, including writes against OTHER attached catalogs and including statements
  * `SqlParser` has no arm for at all (`ATTACH`/`DETACH`/`CREATE SECRET`/`COPY`/`GRANT`); a two-part
  * write such as `INSERT INTO some_other_db.orders` is refused too, because the parser cannot tell
  * a catalog head from a schema head there. This screen guarantees no resolvable write reaches this
  * catalog; it is not parity with `PostgresAclValidator` and it makes no promise beyond writes. The
  * REST create path defaults a new `iceberg_rest` source to true (Task 5). This field defaults to
  * false so pre-0038 sources keep their behaviour.
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
