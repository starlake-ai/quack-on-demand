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
  def columnPolicyRewriter(
      sup: PoolSupervisor,
      catalogReaders: CatalogReaders,
      stmtInstruments: StatementInstruments
  ): ColumnPolicyRewriter =
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
    val columnCatalog: ColumnCatalog =
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
    new ColumnPolicyRewriter(
      catalog = columnCatalog,
      unresolvedMode = unresolvedTableMode,
      enabled = clsEnabled
    )

  def rowPolicyRewriter(): RowPolicyRewriter =
    val rlsEnabled: Boolean =
      com.typesafe.config.ConfigFactory.load().getBoolean("quack-on-demand.rls.enabled")
    if !rlsEnabled then
      logger.info(
        "row-level security is DISABLED (quack-on-demand.rls.enabled=false). " +
          "Every statement bypasses the row-policy rewriter."
      )
    new RowPolicyRewriter(enabled = rlsEnabled)
