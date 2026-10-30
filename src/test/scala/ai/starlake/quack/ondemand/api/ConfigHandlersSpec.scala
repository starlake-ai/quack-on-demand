package ai.starlake.quack.ondemand.api

import ai.starlake.quack.{FlightConfig, ManagerConfig}
import ai.starlake.quack.edge.config.{AuthenticationConfig, ValidationConfig}
import ai.starlake.quack.observability.metrics.MetricsConfig
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigHandlersSpec extends AnyFlatSpec with Matchers:

  private def liveEntries: List[ConfigEntry] = ConfigRegistry.collect(
    ConfigRegistry.rootsFor(
      managerCls = classOf[ManagerConfig],
      flightCls = classOf[FlightConfig],
      authCls = classOf[AuthenticationConfig],
      validationCls = classOf[ValidationConfig],
      metricsCls = classOf[MetricsConfig]
    )
  )

  "ConfigRegistry.collect" should "discover scalar entries from every annotated root" in {
    val es = liveEntries
    // Sanity: at least one entry from every root the UI cares about.
    es.exists(_.path == "quack-on-demand.port") shouldBe true
    es.exists(_.path == "quack-on-demand.admin.password") shouldBe true
    es.exists(_.path == "quack-on-demand.defaultMetastore.pgHost") shouldBe true
    es.exists(_.path == "quack-on-demand.k8s.namespace") shouldBe true
    es.exists(_.path == "quack-on-demand.metrics.sink") shouldBe true
    es.exists(_.path == "quack-on-demand.metrics.aws.stepSeconds") shouldBe true
    es.exists(_.path == "quack-flightsql.port") shouldBe true
    es.exists(_.path == "quack-flightsql.auth.database.password") shouldBe true
    es.exists(_.path == "quack-flightsql.auth.oauthScopes") shouldBe true
    es.exists(_.path == "quack-flightsql.validation.bypassUsers") shouldBe true
    // The acl block was removed (its keys refuse boot): the config page lists none of them.
    es.exists(_.path.startsWith("quack-flightsql.acl.")) shouldBe false
    es.exists(_.path == "quack-flightsql.opa.defaultMode") shouldBe true
  }

  it should "carry the env-var name on each entry" in {
    val byPath = liveEntries.map(e => e.path -> e).toMap
    byPath("quack-on-demand.port").envVar shouldBe "QOD_ON_DEMAND_PORT"
    byPath("quack-on-demand.admin.password").envVar shouldBe "QOD_ADMIN_PASSWORD"
    byPath("quack-on-demand.defaultMetastore.pgPassword").envVar shouldBe "QOD_PG_PASSWORD"
    byPath("quack-flightsql.port").envVar shouldBe "PROXY_PORT"
    byPath("quack-flightsql.auth.jwt.secretKey").envVar shouldBe "JWT_SECRET_KEY"
  }

  it should "expose the pod-template gate env var" in {
    liveEntries.exists(_.envVar == "QOD_POD_TEMPLATE_ENABLED") shouldBe true
  }

  it should "flag known secrets as sensitive" in {
    val sens = liveEntries.filter(_.sensitive).map(_.path).toSet
    sens should contain("quack-on-demand.admin.password")
    sens should contain("quack-on-demand.defaultMetastore.pgPassword")
    sens should contain("quack-on-demand.auth.management.sessionJwtSecret")
    sens should contain("quack-flightsql.auth.database.password")
    sens should contain("quack-flightsql.auth.jwt.secretKey")
    sens should contain("quack-flightsql.auth.keycloak.clientSecret")
    sens should contain("quack-flightsql.auth.google.clientSecret")
    sens should contain("quack-flightsql.auth.azure.clientSecret")
    sens should contain("quack-on-demand.metrics.azure.instrumentationKey")
  }

  it should "drop the removed static API key from the config page" in {
    liveEntries.exists(_.path == "quack-on-demand.apiKey") shouldBe false
    liveEntries.exists(_.envVar == "QOD_API_KEY") shouldBe false
  }

  "ConfigHandlers.list" should "never leak sensitive values; render plain ones as-is" in {
    val cfg   = ConfigFactory.load()
    val views = new ConfigHandlers(cfg, liveEntries)
      .list(None)(_ => SessionScope.Superuser)
      .unsafeRunSync()
      .toOption
      .get
      .entries
    val byPath = views.map(v => v.path -> v).toMap

    // Sensitive: value is exactly "(set)" or "(unset)"; the raw string never appears.
    val pw = byPath("quack-on-demand.admin.password")
    pw.sensitive shouldBe true
    pw.value should (be("(set)") or be("(unset)"))

    // Plain port renders the configured default from the bundled conf.
    byPath("quack-on-demand.port").value shouldBe "20900"
    byPath("quack-flightsql.port").value shouldBe "31338"
  }

  it should "never carry the session secret, configured or the control plane's" in {
    val secret                         = "config-page-session-secret-0123456789abcdef"
    def entries(sessionSecret: String) =
      val cfg = ConfigFactory
        .parseString(s"""quack-on-demand.auth.management.sessionJwtSecret = "$sessionSecret"""")
        .withFallback(ConfigFactory.load())
      new ConfigHandlers(cfg, liveEntries)
        .list(None)(_ => SessionScope.Superuser)
        .unsafeRunSync()
        .toOption
        .get
        .entries
    val path = "quack-on-demand.auth.management.sessionJwtSecret"

    val configured = entries(secret)
    configured.exists(_.value.contains(secret)) shouldBe false
    configured.find(_.path == path).map(_.value) shouldBe Some("(set)")
    // Unset, the manager signs with the control plane's secret: the page reads only the
    // configuration, so it shows the key as unset and has no path to the stored value.
    entries("").find(_.path == path).map(_.value) shouldBe Some("(unset)")
  }

  it should "render missing optional values as '(unset)'" in {
    // smtp.host has no default in application.conf; dropping the path keeps the case independent
    // of QOD_SMTP_HOST in the invoking shell.
    val cfg   = ConfigFactory.load().withoutPath("quack-on-demand.smtp.host")
    val views = new ConfigHandlers(cfg, liveEntries)
      .list(None)(_ => ai.starlake.quack.ondemand.auth.SessionScope.Superuser)
      .unsafeRunSync()
      .toOption
      .get
      .entries
    val byPath = views.map(v => v.path -> v).toMap

    val host = byPath("quack-on-demand.smtp.host")
    host.isSet shouldBe false
    host.value shouldBe "(unset)"
  }
