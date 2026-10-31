package ai.starlake.quack.model

import java.time.Instant

/** One ATTACH-able external catalog under a tenant-db. The `alias` field is the DuckDB catalog
  * name; ACL grants reference it as the first segment of a 3-part table ref.
  *
  * Two shapes, discriminated by `sourceType`:
  *   - `Sql`: `setupSql` is user-typed and may include INSTALL/LOAD/CREATE SECRET/ATTACH
  *     statements, with `{{alias}}` and `{{secret.NAME}}` placeholders resolved at node spawn.
  *   - Typed (`IcebergRest`, `Postgres`, `Mysql`): `config` holds the typed JSON
  *     (`ondemand.federation.iceberg.IcebergRestConfig`, or `ondemand.federation.rdbms.RdbmsConfig`
  *     for the two relational types) and the SQL is rendered from it. Stored as text here because
  *     `model` is a leaf package.
  *
  * `readOnly` is persisted here and enforced by the node for every principal, superusers included,
  * through the signed session handle (spec decision 24): every handle but the system principal's
  * carries the pool's read-only aliases (`ReadOnlyCatalogs`), and qod-node refuses a statement
  * whose plan writes into one, creates or drops an object there, or ATTACHes or DETACHes it (`QoD:
  * catalog <alias> is read-only`). It judges the plan DuckDB bound, so a write the plan does not
  * show as one into the catalog (a raw-SQL passthrough such as `postgres_execute`, reserved to
  * superusers by the node, or a mutating `CALL`) is not covered by that rule. A changed flag
  * reaches the next handle: a stored source write drops the `ReadOnlyCatalogs` cache of its
  * tenant-db's pools on the replica that served it and sends `qod_topology`, whose restore drops
  * every peer's (`FederatedSourceCaches`); a new alias is known to the router as soon. A manifest
  * import's restore does the same. The REST create path defaults a new typed (`iceberg_rest`,
  * `postgres`, `mysql`) source to true. This field defaults to false so pre-0038 sources keep their
  * behaviour.
  *
  * Typed sources get a second, engine-level layer, which binds only at ATTACH time (node spawn, via
  * `FederationBlobBuilder`'s rendered startup SQL): flipping `readOnly` on a live pool does NOT
  * change an already-attached node's engine-level enforcement until the pool's nodes recycle.
  *   - `IcebergRest`: QoD writes the ATTACH itself
  *     ([[ai.starlake.quack.ondemand.federation.iceberg.IcebergSetupSql.render]]), so a true value
  *     here is threaded onto the ATTACH as a bare `READ_ONLY` option, and the `iceberg` extension
  *     honours that bit against a LIVE REST catalog: `IcebergRestE2ESpec` attaches an
  *     `apache/iceberg-rest-fixture` catalog with the renderer's own output and gets INSERT,
  *     UPDATE, DELETE, CREATE TABLE and DROP TABLE each refused with "which is attached in
  *     read-only mode". That evidence is DuckDB v1.5.4 against an unauthenticated fixture
  *     (`authType = none`); a credentialed catalog gets the identical ATTACH option but is not
  *     itself covered.
  *   - `Postgres` / `Mysql`: engine-level the same way, through
  *     [[ai.starlake.quack.ondemand.federation.rdbms.RdbmsSetupSql.render]], with one residual. The
  *     postgres extension runs every statement on the remote in a `READ ONLY` transaction, which
  *     refuses DML and `postgres_execute` writes alike, but a caller who may run the raw-SQL
  *     passthroughs can end that transaction itself: inside one DuckDB transaction,
  *     `CALL postgres_execute('<alias>', 'COMMIT')` followed by a write ran the write in autocommit
  *     on the 2.0 CLI. A database role without write privileges on the remote is the complete
  *     guarantee. For MySQL, whether `READ_ONLY` itself refuses writes through the extension is
  *     unverified (no extension in the 2.0 toolchain).
  *   - `Sql`: the operator writes the ATTACH text (`setupSql`), so QoD has no rendering step to add
  *     the flag to; the node's rule is the only enforcement for these sources, with the gaps above.
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
    readOnly: Boolean = false,
    // Whether a superuser (or the manager) wrote this source. Only superusers may write a `sql`
    // source (CONTRACT Addendum 4 item 26); one stored before that rule has no known author, and
    // boot warns about it. Liquibase 0053.
    setupSqlTrusted: Boolean = false
):

  /** Empty list when the shape invariant holds. Format-specific validation of `config` lives in the
    * iceberg and rdbms packages, which this leaf package cannot reach.
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
        case typed =>
          if hasConfig then Nil else List(s"config is required for sourceType '${typed.wire}'")
