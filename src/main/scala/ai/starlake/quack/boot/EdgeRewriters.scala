package ai.starlake.quack.boot

import ai.starlake.quack.edge.cls.{
  ColumnCatalog,
  ColumnPolicyRewriter,
  DuckLakeColumnCatalog,
  UnresolvedMode
}
import ai.starlake.quack.edge.rls.RowPolicyRewriter
import ai.starlake.quack.observability.metrics.StatementInstruments
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.route.{StatementClassifier, StatementClassifierConfig}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

/** Statement-pipeline components built from operator config, extracted from Main.bootManager: the
  * routing classifier and the CLS / RLS rewriters.
  */
object EdgeRewriters extends LazyLogging:

  /** Build the routing classifier from operator config. Defaults live in application.conf under
    * `quack-on-demand.statementClassifier.*`; matching `QOD_CLASSIFIER_*` env vars override.
    * Comma-separated.
    */
  def statementClassifier(): StatementClassifier =
    val classifierRoot =
      com.typesafe.config.ConfigFactory.load().getConfig("quack-on-demand.statementClassifier")
    val classifierCfg = StatementClassifierConfig(
      select = StatementClassifierConfig.parseCsv(classifierRoot.getString("select")),
      dml = StatementClassifierConfig.parseCsv(classifierRoot.getString("dml")),
      ddl = StatementClassifierConfig.parseCsv(classifierRoot.getString("ddl")),
      begin = StatementClassifierConfig.parseCsv(classifierRoot.getString("begin")),
      commit = StatementClassifierConfig.parseCsv(classifierRoot.getString("commit")),
      rollback = StatementClassifierConfig.parseCsv(classifierRoot.getString("rollback"))
    )
    new StatementClassifier(classifierCfg)

  /** Column-level security rewriter. Resolves a DuckDB-side catalog name to its
    * DuckLakeCatalogReader by looking up the tenant-db owning that catalog and reusing the shared
    * reader cache. Returns Nil for unknown catalogs; the rewriter's unresolvedMode then decides
    * whether to deny or pass through.
    */
  /** SELECT-path column-masking rewriter. Takes a pre-built [[columnCatalog]] so the caller can
    * share ONE catalog instance (hence one cache) with the write-path [[protectedWriteGuard]],
    * rather than each building its own.
    */
  def columnPolicyRewriter(catalog: ColumnCatalog): ColumnPolicyRewriter =
    val clsConfig = com.typesafe.config.ConfigFactory.load().getConfig("quack-on-demand.cls")
    val clsEnabled: Boolean = clsConfig.getBoolean("enabled")
    if !clsEnabled then
      logger.info(
        "column-level security is DISABLED (quack-on-demand.cls.enabled=false). " +
          "Every statement bypasses the rewriter."
      )
    val unresolvedTableMode: UnresolvedMode =
      clsConfig.getString("unresolvedTable").toLowerCase match
        case "deny" => UnresolvedMode.Deny
        case "pass" => UnresolvedMode.Pass
        case other  =>
          logger.warn(
            s"unknown quack-on-demand.cls.unresolvedTable='$other', defaulting to pass"
          )
          UnresolvedMode.Pass
    new ColumnPolicyRewriter(
      catalog = catalog,
      unresolvedMode = unresolvedTableMode,
      enabled = clsEnabled
    )

  /** DuckLake-backed column catalog shared by the SELECT-path [[columnPolicyRewriter]] and the
    * write-path [[protectedWriteGuard]]. Both must resolve a table's columns identically so the
    * guard's Deny-mode oracle and the rewriter agree on which columns a masked SELECT exposes.
    * Resolves a DuckDB-side catalog name to its DuckLakeCatalogReader via the tenant-db owning that
    * catalog; unknown catalogs return Nil so the caller's unresolvedMode decides deny-vs-pass.
    */
  def columnCatalog(
      sup: PoolSupervisor,
      catalogReaders: CatalogReaders,
      stmtInstruments: StatementInstruments
  ): ColumnCatalog =
    new DuckLakeColumnCatalog(
      fetch = (cat, sch, tab) =>
        IO.blocking {
          sup.findTenantDbByCatalogName(cat) match
            case None     => Nil
            case Some(td) =>
              sup.getTenantById(td.tenantId) match
                case None    => Nil
                case Some(t) => catalogReaders.get(t.id, td.name).columnNames(sch, tab)
        },
      instruments = Some(stmtInstruments)
    )

  /** Write-path protected-read guard. Reads the same CLS / RLS enable flags the SELECT-path
    * rewriters consult, and takes the [[columnCatalog]] (NOT a pre-built rewriter): the guard owns
    * its own Deny-mode oracle internally, so an unresolvable protected table denies rather than
    * passing through. Inert when both flags are off.
    */
  def protectedWriteGuard(
      catalog: ColumnCatalog
  ): ai.starlake.quack.edge.policy.ProtectedWriteGuard =
    val clsEnabled =
      com.typesafe.config.ConfigFactory
        .load()
        .getConfig("quack-on-demand.cls")
        .getBoolean("enabled")
    val rlsEnabled =
      com.typesafe.config.ConfigFactory.load().getBoolean("quack-on-demand.rls.enabled")
    new ai.starlake.quack.edge.policy.ProtectedWriteGuard(catalog, clsEnabled, rlsEnabled)

  def rowPolicyRewriter(): RowPolicyRewriter =
    val rlsEnabled: Boolean =
      com.typesafe.config.ConfigFactory.load().getBoolean("quack-on-demand.rls.enabled")
    if !rlsEnabled then
      logger.info(
        "row-level security is DISABLED (quack-on-demand.rls.enabled=false). " +
          "Every statement bypasses the row-policy rewriter."
      )
    new RowPolicyRewriter(enabled = rlsEnabled)
