package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{PoolKey, TenantDb}
import com.typesafe.scalalogging.Logger

import scala.collection.concurrent.TrieMap

/** What of a node's metastore reaches its launcher: the one rule every backend applies.
  *
  * A tenant-db's metastore is tenant-admin input, and every backend used to turn each of its
  * non-secret keys into an environment variable of the node's launcher: `BASH_ENV` (bash expands it
  * before running the spawn script), `PATH`, `LD_PRELOAD` or `QOD_NODE_BIN` then ran commands as
  * the manager's user (local), inside the node pod before node lockdown applied (Kubernetes) or on
  * a fleet server, and `lockdownSql` / `dbInitSql` replaced what the manager set. Now only
  * [[TenantDb.NodeEnvKeys]] become environment variables ([[env]]), only the metastore's own
  * secrets become files ([[secrets]]), and every other key reaches no node ([[dropped]]), named
  * once per pool and key by [[DroppedKeys]]. The API refuses such keys
  * ([[TenantDb.KnownMetastoreKeys]]); a row stored before that keeps working without them.
  */
object NodeMetastore:

  /** The entries a launcher passes as environment variables: exactly [[TenantDb.NodeEnvKeys]],
    * compared by exact name.
    */
  def env(metastore: Map[String, String]): Map[String, String] =
    metastore.filter { case (k, _) => TenantDb.NodeEnvKeys.contains(k) }

  /** The metastore's own secrets ([[TenantDb.NodeSecretEnvKeys]]), which a launcher delivers as
    * files: matched in any spelling, like [[NodeSecretFiles.canonical]] (a `PgPassword` is redacted
    * from every API response, so it is a secret too).
    */
  def secrets(metastore: Map[String, String]): Map[String, String] =
    metastore.filter { case (k, _) => isSecret(k) }

  /** Every other key, sorted: it reaches no node, by environment or by file. */
  def dropped(metastore: Map[String, String]): List[String] =
    metastore.keys.filterNot(k => TenantDb.NodeEnvKeys.contains(k) || isSecret(k)).toList.sorted

  private def isSecret(key: String): Boolean =
    TenantDb.NodeSecretEnvKeys.exists(_.equalsIgnoreCase(key))

  /** Names each [[dropped]] key through a backend's `logger`, once per pool and key, never its
    * value, with how to remove it: a stored row keeps its keys until an update removes them, so
    * every spawn of its pools would repeat it. At ERROR, not WARN: the default root level is ERROR
    * (logback.xml), and a key that stops reaching its nodes (a per-database setting a pool relied
    * on) must reach the operator.
    */
  final class DroppedKeys(logger: Logger):
    private val reported = TrieMap.empty[(PoolKey, String), Unit]

    def report(pool: PoolKey, metastore: Map[String, String]): Unit =
      dropped(metastore).foreach { k =>
        if reported.putIfAbsent((pool, k), ()).isEmpty then
          val known = TenantDb.KnownMetastoreKeys.toList.sorted.mkString(", ")
          logger.error(
            s"$pool: ignoring metastore key '${TenantDb.printableKey(k)}', which reaches no " +
              s"node: a metastore holds only $known. ${removal(k)}"
          )
      }

  /** How `database/update` removes a dropped key. A secret-named one ([[TenantDb.SecretKeys]], in
    * any spelling) is hidden from responses and kept by an update that leaves it out, so it goes by
    * sending its own name empty ([[TenantDb.metastoreUpdateKeyError]] accepts that); any other by
    * sending the metastore without it.
    */
  private def removal(key: String): String =
    TenantDb.SecretKeys.find(_.equalsIgnoreCase(key)) match
      case Some(secret) =>
        s"Remove it by sending '$secret' with an empty value through database/update: an " +
          "update that leaves a secret key out keeps it."
      case None =>
        "Remove it by sending the database's metastore without it through database/update."
