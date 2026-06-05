package ai.starlake.quack.ondemand.api

import ai.starlake.quack.{FlightConfig, ManagerConfig}
import ai.starlake.quack.edge.config.{AclConfig, AuthenticationConfig, ValidationConfig}
import ai.starlake.quack.observability.metrics.MetricsConfig
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigHandlersSpec extends AnyFlatSpec with Matchers:

  private def liveEntries: List[ConfigEntry] = ConfigRegistry.collect(
    ConfigRegistry.rootsFor(
      managerCls    = classOf[ManagerConfig],
      flightCls     = classOf[FlightConfig],
      authCls       = classOf[AuthenticationConfig],
      aclCls        = classOf[AclConfig],
      validationCls = classOf[ValidationConfig],
      metricsCls    = classOf[MetricsConfig]
    )
  )

  "ConfigRegistry.collect" should "discover scalar entries from every annotated root" in {
    val es = liveEntries
    // Sanity: at least one entry from every root the UI cares about.
    es.exists(_.path == "quack-on-demand.port")                            shouldBe true
    es.exists(_.path == "quack-on-demand.admin.password")                  shouldBe true
    es.exists(_.path == "quack-on-demand.defaultMetastore.pgHost")         shouldBe true
    es.exists(_.path == "quack-on-demand.k8s.namespace")                   shouldBe true
    es.exists(_.path == "quack-on-demand.bootstrap.roleDistribution.dual") shouldBe true
    es.exists(_.path == "quack-on-demand.metrics.sink")                    shouldBe true
    es.exists(_.path == "quack-on-demand.metrics.aws.stepSeconds")         shouldBe true
    es.exists(_.path == "quack-flightsql.port")                            shouldBe true
    es.exists(_.path == "quack-flightsql.auth.database.password")          shouldBe true
    es.exists(_.path == "quack-flightsql.auth.oauth.scopes")               shouldBe true
    es.exists(_.path == "quack-flightsql.validation.bypassUsers")          shouldBe true
    es.exists(_.path == "quack-flightsql.acl.enabled")                     shouldBe true
  }

  it should "carry the env-var name on each entry" in {
    val byPath = liveEntries.map(e => e.path -> e).toMap
    byPath("quack-on-demand.port").envVar                       shouldBe "QOD_ON_DEMAND_PORT"
    byPath("quack-on-demand.admin.password").envVar             shouldBe "QOD_ADMIN_PASSWORD"
    byPath("quack-on-demand.defaultMetastore.pgPassword").envVar shouldBe "QOD_PG_PASSWORD"
    byPath("quack-flightsql.port").envVar                       shouldBe "PROXY_PORT"
    byPath("quack-flightsql.auth.jwt.secretKey").envVar         shouldBe "JWT_SECRET_KEY"
  }

  it should "flag known secrets as sensitive" in {
    val sens = liveEntries.filter(_.sensitive).map(_.path).toSet
    sens should contain ("quack-on-demand.admin.password")
    sens should contain ("quack-on-demand.defaultMetastore.pgPassword")
    sens should contain ("quack-on-demand.apiKey")
    sens should contain ("quack-flightsql.auth.database.password")
    sens should contain ("quack-flightsql.auth.jwt.secretKey")
    sens should contain ("quack-flightsql.auth.keycloak.clientSecret")
    sens should contain ("quack-flightsql.auth.google.clientSecret")
    sens should contain ("quack-flightsql.auth.azure.clientSecret")
    sens should contain ("quack-on-demand.metrics.azure.instrumentationKey")
  }

  "ConfigHandlers.list" should "never leak sensitive values; render plain ones as-is" in {
    val cfg = ConfigFactory.load()
    val views = new ConfigHandlers(cfg, liveEntries).list.unsafeRunSync()
      .toOption.get.entries
    val byPath = views.map(v => v.path -> v).toMap

    // Sensitive: value is exactly "(set)" or "(unset)"; the raw string never appears.
    val pw = byPath("quack-on-demand.admin.password")
    pw.sensitive shouldBe true
    pw.value should (be ("(set)") or be ("(unset)"))

    // Plain port renders the configured default from the bundled conf.
    byPath("quack-on-demand.port").value shouldBe "20900"
    byPath("quack-flightsql.port").value shouldBe "31338"
  }

  it should "render missing optional values as '(unset)'" in {
    val cfg = ConfigFactory.load()
    val views = new ConfigHandlers(cfg, liveEntries).list.unsafeRunSync()
      .toOption.get.entries
    val byPath = views.map(v => v.path -> v).toMap

    // apiKey has no default in application.conf and no env var set in the test JVM.
    val apiKey = byPath("quack-on-demand.apiKey")
    apiKey.isSet shouldBe false
    apiKey.value shouldBe "(unset)"
  }
