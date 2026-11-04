package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.model.TenantDb

/** The managed Postgres as fleet servers reach it. The manager may dial the metastore by a name
  * only it resolves (a compose service name); `QOD_FLEET_NODE_PG_HOST` / `_PORT` replace that
  * address inside fleet assignments only. A database on its own Postgres, another port of the same
  * host included, is never touched. The server is compared as the rule on QoD's login compares it
  * ([[TenantDb.onDefaultServer]]), so a row that inherits that login never keeps a spelling only
  * the manager's network resolves.
  */
object NodePgAddress:
  def rewrite(
      metastore: Map[String, String],
      defaultPgHost: String,
      defaultPgPort: String,
      nodePgHost: String,
      nodePgPort: String
  ): Map[String, String] =
    val default =
      Map("pgHost" -> defaultPgHost) ++ Option.when(defaultPgPort.nonEmpty)(
        "pgPort" -> defaultPgPort
      )
    val managed = defaultPgHost.nonEmpty && metastore.contains("pgHost") &&
      TenantDb.onDefaultServer(metastore, default)
    if !managed then metastore
    else
      val withHost =
        if nodePgHost.nonEmpty then metastore.updated("pgHost", nodePgHost) else metastore
      if nodePgPort.nonEmpty then withHost.updated("pgPort", nodePgPort) else withHost
