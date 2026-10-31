package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class TenantFilesConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  private def load(env: Map[String, String] = Map.empty): ManagerConfig =
    val overlay = ConfigFactory.parseString(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))
    val conf    = overlay
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-on-demand").loadOrThrow[ManagerConfig]

  "tenantFiles.root" should "default to unset" in {
    load().tenantFiles.rootOpt shouldBe None
  }

  it should "take QOD_TENANT_FILES_ROOT, without its trailing slash" in {
    load(Map("QOD_TENANT_FILES_ROOT" -> "/srv/qod/")).tenantFiles.rootOpt shouldBe Some("/srv/qod")
    load(Map("QOD_TENANT_FILES_ROOT" -> "/srv/qod")).tenantFiles.rootOpt shouldBe Some("/srv/qod")
  }

  it should "refuse a root that is not an absolute folder of at least one segment" in
    List("/", "srv/qod", "./srv", "/srv/../etc", "/srv//qod", "/srv/./qod").foreach { bad =>
      withClue(s"'$bad': ") {
        intercept[IllegalArgumentException](TenantFilesConfig(bad)).getMessage should include(
          "QOD_TENANT_FILES_ROOT"
        )
      }
    }

  it should "be listed in the config registry under its env var" in {
    ConfigRegistry
      .collect(List("quack-on-demand" -> classOf[ManagerConfig]))
      .find(_.path == "quack-on-demand.tenantFiles.root")
      .map(_.envVar) shouldBe Some("QOD_TENANT_FILES_ROOT")
  }
