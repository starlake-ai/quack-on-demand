package ai.starlake.quack.ondemand

import ai.starlake.quack.model.TenantDb

/** The boot report of stored `defaultDatabase` values that name another catalog than the one the
  * database's nodes resolve unqualified names in (`TenantDb.defaultDatabaseError`), which every
  * write now refuses. Such a value is ignored: the policy rewriters qualify an unqualified name
  * with the pool's session path (`FlightSqlRouter.nameDefaults`), exactly as the node's USE prelude
  * resolves it, so it changes nothing. One line per row, on stderr (the logger's default level is
  * ERROR), naming the tenant and the database and how to clear it, never the value.
  */
object DefaultDatabaseReport:

  /** The WARN line of every row of `tenantDbs` whose `defaultDatabase` is so refused. */
  def lines(tenantDbs: List[TenantDb]): List[String] =
    tenantDbs
      .filter(td =>
        TenantDb
          .defaultDatabaseError(
            td.defaultDatabase,
            TenantDb.sessionCatalog(td.kind, td.name, td.metastore)
          )
          .isDefined
      )
      .map(td =>
        s"WARN: tenant '${td.tenantId}' database '${td.name}': its defaultDatabase names another " +
          "catalog than the database's own and is ignored: unqualified table names, and the row " +
          "and column policies on them, resolve in the database's own catalog. Clear it with qod " +
          s"database update --tenant ${td.tenantId} --name ${td.name} --default-database ''."
      )
      .sorted

  /** Prints [[lines]] of `tenantDbs` on stderr. */
  def report(tenantDbs: List[TenantDb]): Unit = lines(tenantDbs).foreach(System.err.println)
