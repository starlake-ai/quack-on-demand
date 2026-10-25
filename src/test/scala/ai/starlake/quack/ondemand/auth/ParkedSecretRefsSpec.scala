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

  /** The message for `acme` holding `value` under the parked key, as changeset 0048 leaves it. */
  private def messageFor(value: String): String =
    ParkedSecretRefs
      .messages(List(parked.copy(authConfig = Map(ParkedSecretRefs.ParkedKey -> value))))
      .head

  /** What follows "a superuser runs: ": the part of the line an operator selects and runs. */
  private def commandOf(message: String): String =
    val marker = "a superuser runs: "
    message.substring(message.indexOf(marker) + marker.length)

  "ParkedSecretRefs.messages" should
    "name the tenant, the parked reference and the superuser restore step" in {
      val msgs = ParkedSecretRefs.messages(List(parked))
      msgs.size shouldBe 1
      val m = msgs.head
      m should include("tenant 'acme'")
      m should include("'env:ACME_OIDC_SECRET'")
      m should include("a superuser runs: qod tenant set-auth acme --auth-provider keycloak")
      m should include("--auth-config issuerUrl=... --auth-config clientId=...")
      m should include("--auth-config clientSecretRef=env:ACME_OIDC_SECRET")
      // A plain reference stays a bare word.
      m should not include "'clientSecretRef=env:ACME_OIDC_SECRET'"
    }

  it should "tell the superuser that the save replaces the whole auth config" in:
    messageFor("env:ACME_OIDC_SECRET") should include(
      "repeat every other key of the tenant's auth config (scopes, baseUrl, realm, tenantId, " +
        "...): the save replaces the whole map"
    )

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

  it should "never put a stored reference outside [A-Za-z0-9_:./=-] into the restore command" in:
    Seq(
      "env:X;id",
      "env:X$(id)",
      "env:A B",
      "env:X&calc&",
      "env:A\\';id;#",
      "env:A';calc;'"
    ).foreach { ref =>
      withClue(ref) {
        val m       = messageFor(ref)
        val command = commandOf(m)
        command should startWith("qod tenant set-auth acme")
        command should endWith("--auth-config clientId=... --auth-config clientSecretRef=...")
        command should not include ref
        // The value is still shown, sanitized, in the prose before the command.
        m should include(s"clientSecretRef '$ref' was stored")
      }
    }

  it should "ask for a reference the command cannot carry to be checked and typed by hand" in:
    val m = messageFor("env:A B")
    m should include("shown sanitized above")
    m should include("so the command below does not carry it")
    m should include("type it by hand")

  it should "end the line with the restore command, after all the advice" in:
    val m = messageFor("env:ACME_OIDC_SECRET")
    commandOf(m) shouldBe
      "qod tenant set-auth acme --auth-provider keycloak --auth-config issuerUrl=... " +
      "--auth-config clientId=... --auth-config clientSecretRef=env:ACME_OIDC_SECRET"
    val advice = m.take(m.indexOf("a superuser runs: "))
    advice should include("Confirm the reference is meant for this tenant")
    advice should include("the save replaces the whole map")

  it should "say that a value without a prefix is not a reference and ask for a secret source" in:
    val m = messageFor("hunter2-literal")
    m should include("The stored value is not a secret reference")
    m should include("a superuser sets a secret source for the tenant")
    m should include("(a client secret or a reference)")
    m should not include "Confirm the reference is meant for this tenant"
    commandOf(m) should startWith("qod tenant set-auth acme")

  it should "leave bare a reference of only letters, digits and _ : . / = -" in:
    val ref = "aws-sm:arn:aws:secretsmanager:eu-west-1:123:secret:Name/x.y_z=1"
    val m   = messageFor(ref)
    m should include(s"--auth-config clientSecretRef=$ref")
    m should not include s"'clientSecretRef=$ref'"

  it should "print a control character of a stored reference as '?'" in:
    val m = messageFor("env:A\nFAKE LOG LINE")
    m should not include "\n"
    m should include("env:A?FAKE LOG LINE")

  it should "print a format or separator character of a stored reference as '?'" in:
    // Built from code points so the source holds no invisible character.
    Seq(
      "bidi override U+202E"       -> 0x202e,
      "bidi isolate U+2066"        -> 0x2066,
      "line separator U+2028"      -> 0x2028,
      "paragraph separator U+2029" -> 0x2029,
      // Outside the BMP: a surrogate pair is one code point, so one '?', not two.
      "tag character U+E0041" -> 0xe0041
    ).foreach { (what, codePoint) =>
      withClue(what) {
        val special = Character.toString(codePoint)
        val m       = messageFor(s"env:A${special}B")
        m should include("env:A?B")
        m should not include special
      }
    }

  it should "print a reference only when it has a secret-reference prefix" in:
    Seq(
      "env:ACME_OIDC_SECRET",
      "aws-sm:arn:aws:secretsmanager:eu-west-1:123:secret:Name",
      "gcp-sm:projects/p/secrets/s/versions/latest",
      "azure-kv:oidc-secret",
      "vault:secret/data/acme#client"
    ).foreach { ref =>
      withClue(ref)(messageFor(ref) should include(s"clientSecretRef '$ref' was stored"))
    }

  it should "never print a stored value that has no secret-reference prefix" in:
    Seq(
      "hunter2-literal",
      "ENV:UPPER",
      "env",
      " env:X",
      "https://kc.example.com/secret",
      ""
    ).foreach { value =>
      withClue(s"'$value'") {
        val m = messageFor(value)
        if value.nonEmpty then m should not include value
        m should include("clientSecretRef <not a secret reference, not shown> was stored")
        // The restore step carries no value either, only the placeholder.
        m should include("--auth-config clientId=... --auth-config clientSecretRef=...")
      }
    }

  "ParkedSecretRefs.report" should "log each message at ERROR, which the default level shows" in:
    val log      = LoggerFactory.getLogger(ParkedSecretRefs.getClass).asInstanceOf[Logger]
    val appender = new ListAppender[ILoggingEvent]
    val additive = log.isAdditive
    appender.start()
    log.addAppender(appender)
    // The test's ERROR lines must stay out of the suite output: no hand-off to the root appender.
    log.setAdditive(false)
    try ParkedSecretRefs.report(List(parked, Tenant("globex")))
    finally
      log.setAdditive(additive)
      log.detachAppender(appender)
    appender.list.asScala.toList.map(e => (e.getLevel, e.getFormattedMessage)) shouldBe
      ParkedSecretRefs.messages(List(parked)).map(m => (Level.ERROR, m))
