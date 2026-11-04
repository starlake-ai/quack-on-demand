package ai.starlake.quack.ondemand.federation.iceberg

import ai.starlake.quack.model.TenantDb

/** What the manager prints about an `iceberg_rest` federated source, which stays open to tenant
  * admins: when one is created or changed and at each boot, disabled ones included, never at node
  * spawn. On stdout and not through the logger, whose default level is ERROR. It names the source
  * and says nothing of its connection: no URI, token or secret.
  */
object IcebergSourceWarning:

  /** What to do about it, in one place. */
  private val Advice =
    "In production disable local file access on its nodes (a superuser's init SQL: " +
      "SET disabled_filesystems = 'LocalFileSystem', on an object-store pool)."

  /** A control character in a stored name cannot forge a line. */
  def line(tenant: String, database: String, alias: String): String =
    val source = List(tenant, database, alias).map(TenantDb.printableKey).mkString("/")
    s"WARN: Iceberg source $source: a node reads whatever files its catalog lists, local files " +
      s"included. $Advice"

  /** `System.out`, as `Console.out` stays on what a thread inherited and handlers run on a pool. */
  def print(tenant: String, database: String, alias: String): Unit =
    System.out.println(line(tenant, database, alias))
