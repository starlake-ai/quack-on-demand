package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{PoolKey, TenantDb}
import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.typesafe.scalalogging.Logger
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths}
import java.util.UUID
import scala.jdk.CollectionConverters.*

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

  /** A [[NodeMetastore.DroppedKeys]] over a fresh logger, and what that logger receives at every
    * level (the level is part of what is pinned), kept off the console.
    */
  private def droppedKeys(): (NodeMetastore.DroppedKeys, () => List[ILoggingEvent]) =
    val logger =
      LoggerFactory.getLogger(s"NodeMetastoreSpec.${UUID.randomUUID}").asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.TRACE)
    logger.setAdditive(false)
    (new NodeMetastore.DroppedKeys(Logger(logger)), () => appender.list.asScala.toList)

  "NodeMetastore.DroppedKeys" should "log at ERROR once per pool and key, by name, never by value" in {
    val (log, events) = droppedKeys()
    def lines         = events().map(_.getFormattedMessage)
    val m             = allowed ++ Map("BASH_ENV" -> "value-a", "PATH" -> "value-b")
    log.report(poolA, m)
    log.report(poolA, m)
    lines.size shouldBe 2
    lines.head should include(poolA.toString)
    lines.head should include("'BASH_ENV'")
    lines(1) should include("'PATH'")
    log.report(poolB, m)
    log.report(poolA, m + ("lockdownSql" -> "value-c"))
    lines.size shouldBe 5
    lines.last should include("'lockdownSql'")
    withClue("a metastore value in the log: ")(lines.exists(_.contains("value-")) shouldBe false)
    // A key carrying a line break cannot forge a second log line.
    log.report(poolA, Map("a\nb" -> "v"))
    lines.size shouldBe 6
    lines.last.exists(_.isControl) shouldBe false
    // The default root level is ERROR (logback.xml): a WARN would reach no operator.
    events().map(_.getLevel).distinct shouldBe List(Level.ERROR)
  }

  it should "say how to remove each key: a secret-named one by sending it empty" in {
    val (log, events) = droppedKeys()
    log.report(
      poolA,
      Map("applicationName" -> "v", "s3_secret_access_key" -> "v", "AZURE_ACCOUNT_KEY" -> "v")
    )
    def line(key: String) = events().map(_.getFormattedMessage).find(_.contains(s"'$key'")).value
    // Shown in responses and not carried over: an update that leaves it out removes it.
    line("applicationName") should include(
      "sending the database's metastore without it through database/update"
    )
    // Hidden from responses and carried over by an update that leaves it out: sent empty, under
    // the name the API accepts (any spelling of it is removed), is the one way to remove it.
    line("s3_secret_access_key") should include(
      "sending 's3_secret_access_key' with an empty value through database/update"
    )
    line("AZURE_ACCOUNT_KEY") should include(
      "sending 'azure_account_key' with an empty value through database/update"
    )
  }
