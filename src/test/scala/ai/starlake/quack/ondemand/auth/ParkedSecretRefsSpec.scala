package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.model.Tenant
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

class ParkedSecretRefsSpec extends AnyFlatSpec with Matchers:

  private val parked = Tenant(
    "acme",
    authProvider = "keycloak",
    authConfig = Map(
      "issuerUrl"                -> "https://kc.example.com/realms/acme",
      "clientId"                 -> "qod",
      ParkedSecretRefs.ParkedKey -> "env:ACME_OIDC_SECRET"
    )
  )

  "ParkedSecretRefs.messages" should "name the tenant, the parked reference and the superuser restore step" in:
    val msgs = ParkedSecretRefs.messages(List(parked))
    msgs.size shouldBe 1
    val m = msgs.head
    m should include("tenant 'acme'")
    m should include("'env:ACME_OIDC_SECRET'")
    m should include("a superuser runs: qod tenant set-auth acme --auth-provider keycloak")
    m should include("--auth-config issuerUrl=... --auth-config clientId=...")
    m should include("--auth-config clientSecretRef=env:ACME_OIDC_SECRET")

  it should "say nothing for a tenant without a parked reference, a live reference included" in:
    val live = Tenant(
      "initech",
      authProvider = "google",
      authConfig = Map("clientSecretRef" -> "env:INITECH")
    )
    ParkedSecretRefs.messages(List(Tenant("globex"), live)) shouldBe Nil

  it should "give one message per parked tenant, in the order given" in:
    val other =
      parked.copy(id = "umbrella", authConfig = Map(ParkedSecretRefs.ParkedKey -> "env:U"))
    ParkedSecretRefs
      .messages(List(parked, Tenant("globex"), other))
      .map(_.takeWhile(_ != ':')) shouldBe List("tenant 'acme'", "tenant 'umbrella'")

  it should "print a control character of a stored reference as '?'" in:
    val forged =
      parked.copy(authConfig = Map(ParkedSecretRefs.ParkedKey -> "env:A\nFAKE LOG LINE"))
    val m = ParkedSecretRefs.messages(List(forged)).head
    m should not include "\n"
    m should include("env:A?FAKE LOG LINE")

  "ParkedSecretRefs.report" should "log each message at ERROR, which the default level shows" in:
    val log      = LoggerFactory.getLogger(ParkedSecretRefs.getClass).asInstanceOf[Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    log.addAppender(appender)
    try ParkedSecretRefs.report(List(parked, Tenant("globex")))
    finally log.detachAppender(appender)
    appender.list.asScala.toList.map(e => (e.getLevel, e.getFormattedMessage)) shouldBe
      ParkedSecretRefs.messages(List(parked)).map(m => (Level.ERROR, m))
