package ai.starlake.quack.boot

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}
import ai.starlake.quack.ondemand.state.{FederatedSourceOps, InMemoryFederatedSourceStore}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.core.read.ListAppender
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/** [[BootPreflight.checkFederatedAliases]]: a best-effort boot report of federated source aliases
  * that [[ai.starlake.quack.model.Names]]'s identifier rule now rejects (a hyphen or dot, mixed
  * case that no longer round-trips, or an over-length alias), added alongside typed iceberg_rest
  * sources. An operator carrying such a legacy alias should learn about it on restart rather than
  * on their next edit attempt, but a legacy install must still be allowed to boot.
  */
class BootPreflightFederatedAliasesSpec extends AnyFlatSpec with Matchers:

  /** Attaches a throwaway appender to BootPreflight's own logger for the duration of `run`, and
    * returns every formatted message it captured. Isolated per call (start/detach), so tests don't
    * interfere with each other or leak an appender into the shared logger.
    */
  private def errorMessages(run: => Unit): List[String] =
    val logbackLogger = LoggerFactory.getLogger(BootPreflight.getClass).asInstanceOf[LogbackLogger]
    val appender      = new ListAppender[ILoggingEvent]()
    appender.start()
    logbackLogger.addAppender(appender)
    try
      run
      appender.list.asScala.toList.map(_.getFormattedMessage)
    finally logbackLogger.detachAppender(appender)

  "checkFederatedAliases" should
    "report exactly the invalid alias when a store has one invalid and one valid" in {
      val store = new InMemoryFederatedSourceStore
      store.upsertSource(
        FederatedSource(
          id = "fs-1",
          tenantDbId = "td-1",
          alias = "ext-s3",
          setupSql = "ATTACH ... AS ext-s3;"
        )
      )
      store.upsertSource(
        FederatedSource(
          id = "fs-2",
          tenantDbId = "td-1",
          alias = "ext_s3",
          setupSql = "ATTACH ... AS ext_s3;"
        )
      )
      val messages = errorMessages(BootPreflight.checkFederatedAliases(store))
      messages should have size 1
      messages.head should include("td-1")
      messages.head should include("ext-s3")
    }

  it should "report nothing when every alias is valid" in {
    val store = new InMemoryFederatedSourceStore
    store.upsertSource(
      FederatedSource(
        id = "fs-1",
        tenantDbId = "td-1",
        alias = "ext_s3",
        setupSql = "ATTACH ... AS ext_s3;"
      )
    )
    val messages = errorMessages(BootPreflight.checkFederatedAliases(store))
    messages shouldBe empty
  }

  it should "not propagate an exception when the store's enumeration throws" in {
    val throwing = new FederatedSourceOps:
      def upsertSource(s: FederatedSource): Unit                                = ()
      def deleteSource(id: String): Unit                                        = ()
      def getSource(tenantDbId: String, alias: String): Option[FederatedSource] = None
      def listSources(tenantDbId: String): List[FederatedSource]                = Nil
      def upsertSecret(s: FederatedSecret): Unit                                = ()
      def deleteSecret(sourceId: String, name: String): Unit                    = ()
      def getSecret(sourceId: String, name: String): Option[FederatedSecret]    = None
      def listSecrets(sourceId: String): List[FederatedSecret]                  = Nil
      def tenantDbIdsWithSources(): Set[String] = throw new RuntimeException("boom")

    noException should be thrownBy BootPreflight.checkFederatedAliases(throwing)
  }
