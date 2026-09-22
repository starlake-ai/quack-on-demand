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
  * cannot fully resolve at all -- for a statement it can parse on its own, it does NOT trust a
  * "classified as not a write" verdict as proof that a statement is a read; see
  * `CatalogWriteScreen`'s scaladoc for the exact per-statement rule, INCLUDING the two places it
  * deliberately widens past what it can prove rather than parse: a batch the parser cannot split
  * into one statement per fragment (fails closed on the fragment list instead), a blank parse
  * snippet (always treated as a write), and `PREPARE`/`EXECUTE` (always treated as a write,
  * regardless of what they run). The consequence an operator needs to know before flipping this
  * flag: while ANY source on the pool is read-only, every write on the WHOLE pool must be fully
  * parseable and fully qualified or it is refused, including writes against OTHER attached catalogs
  * and including statements `SqlParser` has no arm for at all (`ATTACH`/`DETACH`/
  * `CREATE SECRET`/`COPY`/`GRANT`); a two-part write such as `INSERT INTO some_other_db.orders` is
  * refused too, because the parser cannot tell a catalog head from a schema head there. This screen
  * is not parity with `PostgresAclValidator` and it makes no promise beyond writes -- and even
  * within writes it promises only that a write this screen can resolve, or can prove write-shaped
  * by one of the rules above, is denied; it is not a guarantee that every conceivable way of
  * driving a write past this catalog has been enumerated. The REST create path defaults a new
  * `iceberg_rest` source to true (Task 5). This field defaults to false so pre-0038 sources keep
  * their behaviour.
  *
  * Enforcement is a two-layer split by `sourceType`, and the layers are NOT equally strong, NOT
  * equally proven, and NOT synchronized with each other:
  *   - `IcebergRest`: engine-level. QoD writes the ATTACH itself
  *     ([[ai.starlake.quack.ondemand.federation.iceberg.IcebergSetupSql.render]]), so a true value
  *     here is threaded onto the ATTACH as a bare `READ_ONLY` option, and the `iceberg` extension
  *     honours that bit against a LIVE REST catalog: `IcebergRestE2ESpec` attaches an
  *     `apache/iceberg-rest-fixture` catalog with the renderer's own output and gets INSERT,
  *     UPDATE, DELETE, CREATE TABLE and DROP TABLE each refused with "which is attached in
  *     read-only mode", reads still serving and the row count unchanged. That evidence is DuckDB
  *     v1.5.4 against an unauthenticated fixture (`authType = none`); a credentialed catalog gets
  *     the identical ATTACH option but is not itself covered. Keep relying on `CatalogWriteScreen`
  *     as defence in depth regardless. This layer also binds only at ATTACH time (node spawn, via
  *     `FederationBlobBuilder`'s rendered startup SQL): flipping `readOnly` on a live pool does NOT
  *     change an already-attached node's engine-level enforcement until the pool's nodes recycle,
  *     even though `CatalogWriteScreen` picks the new value up within its ~60s cache. Concretely,
  *     false -> true briefly leaves only the screen (with all its gaps) standing, and true -> false
  *     leaves the catalog engine-read-only (raw DuckDB read-only-mode errors, not this screen's
  *     denial) until an operator recycles the pool. A mutating `CALL` is the one shape this layer
  *     closes that the screen alone cannot (`CatalogWriteScreen` cannot resolve `CALL` and admits
  *     it) - though the live spec above covers those DML/DDL verbs rather than `CALL` itself, and
  *     this layer binds only once the pool has recycled.
  *   - `Sql`: edge-screen-only. The operator writes the ATTACH text (`setupSql`), so QoD has no
  *     rendering step to add the flag to; `CatalogWriteScreen` is the ONLY enforcement for these
  *     sources, with every gap documented above PLUS a mutating `CALL`, which the screen cannot
  *     resolve and therefore admits unconditionally - there is no second layer to catch it for this
  *     source type.
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
