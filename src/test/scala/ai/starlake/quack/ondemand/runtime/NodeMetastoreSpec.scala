package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{PoolKey, TenantDb}
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.typesafe.scalalogging.Logger
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.util.UUID

/** SEC-1: a tenant-db's metastore is tenant-admin input, and only [[TenantDb.NodeEnvKeys]] of it
  * may become a launcher's environment, only its own secrets may become files, and every other key
  * reaches no node.
  */
class NodeMetastoreSpec extends AnyFlatSpec with Matchers with OptionValues:

  /** Every key the launcher reads from its environment, as the manager resolves them. */
  private val allowed = Map(
    "pgHost"       -> "pg.internal",
    "pgPort"       -> "5432",
    "pgUser"       -> "qod",
    "dbName"       -> "acme_lake",
    "catalogAlias" -> "acme_lake",
    "schemaName"   -> "main",
    "dataPath"     -> "/data/acme_lake",
    "encrypted"    -> "true"
  )

  /** Names bash, the dynamic loader, the spawn script or qod-node would act on, a manager-owned
    * setting, and a name nothing reads.
    */
  private val hostile = Map(
    "BASH_ENV"                 -> "v-bash-env",
    "PATH"                     -> "v-path",
    "LD_PRELOAD"               -> "v-ld-preload",
    "DYLD_INSERT_LIBRARIES"    -> "v-dyld",
    "QOD_NODE_BIN"             -> "v-node-bin",
    "QOD_SECRETS_DIR"          -> "v-secrets-dir",
    "QOD_NODE_ID"              -> "v-node-id",
    "PG_ADMIN_DB"              -> "v-admin-db",
    "kind"                     -> "v-kind",
    "dbInitSql"                -> "v-db-init",
    "resourceSql"              -> "v-resource",
    "tenantDbInitSql"          -> "v-tenant-db-init",
    "tenantPoolInitSql"        -> "v-tenant-pool-init",
    "QOD_INIT_TRUSTED_DB"      -> "v-trusted-db",
    "QOD_INIT_TRUSTED_POOL"    -> "v-trusted-pool",
    "lockdownSql"              -> "v-lockdown",
    "extraSetupSql"            -> "v-extra",
    "objectStoreSql"           -> "v-store",
    "QOD_NODE_TOKEN"           -> "v-token",
    "QOD_S3_ENDPOINT"          -> "v-s3-endpoint",
    "QOD_S3_SECRET_ACCESS_KEY" -> "v-s3-secret",
    "pghost"                   -> "v-wrong-case",
    "applicationName"          -> "v-unread"
  )

  private val poolA = PoolKey("acme", "acme_lake", "bi")
  private val poolB = PoolKey("acme", "acme_lake", "etl")

  "TenantDb.NodeEnvKeys" should "be the metastore keys both spawn scripts read from the env" in {
    TenantDb.NodeEnvKeys shouldBe allowed.keySet
    TenantDb.NodeEnvKeys should contain allOf (TenantDb.CatalogAliasKey, TenantDb.EncryptedKey)
    val sh  = Files.readString(Paths.get("scripts/spawn-quack-node.sh"))
    val ps1 = Files.readString(Paths.get("scripts/spawn-quack-node.ps1"))
    TenantDb.NodeEnvKeys.foreach { k =>
      withClue(s"spawn-quack-node.sh reads $k: ")(sh.contains("${" + k + ":-") shouldBe true)
      val ps1Reads = ps1.contains(s"Env-Or '$k'") || ps1.contains(s"GetEnvironmentVariable('$k')")
      withClue(s"spawn-quack-node.ps1 reads $k: ")(ps1Reads shouldBe true)
    }
    // None of them is a secret: a secret must never be an environment variable.
    TenantDb.NodeEnvKeys.filter(NodeSecretFiles.isKey) shouldBe empty
  }

  "TenantDb.KnownMetastoreKeys" should "be the launcher's keys but the stamp, plus the secrets" in {
    TenantDb.KnownMetastoreKeys shouldBe
      (allowed.keySet - TenantDb.EncryptedKey) ++ Set("pgPassword", "encryptionKey")
    TenantDb.unknownMetastoreKeys(allowed ++ hostile) shouldBe
      (hostile.keySet + TenantDb.EncryptedKey).toList.sorted
  }

  "NodeMetastore.env" should "keep exactly TenantDb.NodeEnvKeys" in {
    NodeMetastore.env(allowed ++ hostile ++ Map("pgPassword" -> "p", "PgPassword" -> "q")) shouldBe
      allowed
  }

  "NodeMetastore.secrets" should "keep the metastore's own secrets in any spelling, only" in {
    val secrets = Map("pgPassword" -> "p", "PgPassword" -> "q", "EncryptionKey" -> "k")
    NodeMetastore.secrets(allowed ++ hostile ++ secrets) shouldBe secrets
  }

  "NodeMetastore.dropped" should "name every other key, sorted" in {
    NodeMetastore.dropped(allowed ++ hostile + ("pgPassword" -> "p")) shouldBe
      hostile.keys.toList.sorted
    NodeMetastore.dropped(allowed + ("pgPassword" -> "p")) shouldBe Nil
  }

  /** What a [[NodeMetastore.DroppedKeys]] over a fresh logger logs while `body` reports through it,
    * at every level (the level is part of what is pinned), kept off the console.
    */
  private def droppedKeys(body: NodeMetastore.DroppedKeys => Unit): List[ILoggingEvent] =
    val name = s"NodeMetastoreSpec.${UUID.randomUUID}"
    LogCapture.logged(name)(body(new NodeMetastore.DroppedKeys(Logger(name))))._2

  "NodeMetastore.DroppedKeys" should "log at ERROR once per pool and key, by name, not value" in {
    val m      = allowed ++ Map("BASH_ENV" -> "value-a", "PATH" -> "value-b")
    val events = droppedKeys { log =>
      log.report(poolA, m)
      log.report(poolA, m)
      log.report(poolB, m)
      log.report(poolA, m + ("lockdownSql" -> "value-c"))
      // A key carrying a line break cannot forge a second log line.
      log.report(poolA, Map("a\nb" -> "v"))
    }
    // The default root level is ERROR (logback.xml): a WARN would reach no operator.
    LogCapture.droppedKeyLines(events) shouldBe List(
      (Level.ERROR, poolA.toString, "BASH_ENV"),
      (Level.ERROR, poolA.toString, "PATH"),
      (Level.ERROR, poolB.toString, "BASH_ENV"),
      (Level.ERROR, poolB.toString, "PATH"),
      (Level.ERROR, poolA.toString, "lockdownSql"),
      (Level.ERROR, poolA.toString, "a\\u000ab")
    )
    val lines = events.map(_.getFormattedMessage)
    lines.size shouldBe 6
    withClue("a metastore value in the log: ")(lines.exists(_.contains("value-")) shouldBe false)
    lines.exists(_.exists(_.isControl)) shouldBe false
  }

  it should "say how to remove each key, always sending the database's full metastore" in {
    val events = droppedKeys(
      _.report(
        poolA,
        Map("applicationName" -> "v", "s3_secret_access_key" -> "v", "AZURE_ACCOUNT_KEY" -> "v")
      )
    )
    def line(key: String) = events.map(_.getFormattedMessage).find(_.contains(s"'$key'")).value
    // An update replaces the whole metastore: one sending only the key would revert every other key
    // (a branch row would lose its catalogAlias), so the advice always sends the full map.
    // Shown in responses and not carried over: an update that leaves it out removes it.
    line("applicationName") should include(
      "sending the database's full metastore without it through database/update"
    )
    // Hidden from responses and carried over by an update that leaves it out: sent empty, under
    // the name the API accepts (any spelling of it is removed), is the one way to remove it.
    line("s3_secret_access_key") should include(
      "sending the database's full metastore through database/update with " +
        "'s3_secret_access_key' set to an empty value"
    )
    line("AZURE_ACCOUNT_KEY") should include(
      "sending the database's full metastore through database/update with " +
        "'azure_account_key' set to an empty value"
    )
  }
