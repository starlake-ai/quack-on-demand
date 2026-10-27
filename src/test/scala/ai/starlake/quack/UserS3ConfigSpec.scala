package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class UserS3ConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  private def load(env: Map[String, String] = Map.empty): ManagerConfig =
    val overlay = ConfigFactory.parseString(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))
    val conf    = overlay
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-on-demand").loadOrThrow[ManagerConfig]

  "userS3.allowedEndpoints" should "default to AWS's own endpoint only" in {
    load().userS3.endpoints shouldBe Nil
  }

  it should "take QOD_USER_S3_ALLOWED_ENDPOINTS, normalized" in {
    load(
      Map("QOD_USER_S3_ALLOWED_ENDPOINTS" -> "MinIO.local:9000,http://127.0.0.1:9000")
    ).userS3.endpoints shouldBe List("minio.local:9000", "http://127.0.0.1:9000")
  }

  it should "refuse an entry that is not host[:port] or http://host[:port]" in
    List("https://minio.local", "minio.local/x", "a b").foreach { bad =>
      withClue(s"'$bad': ") {
        intercept[IllegalArgumentException](UserS3Config(bad)).getMessage should include(
          "QOD_USER_S3_ALLOWED_ENDPOINTS"
        )
      }
    }

  it should "be listed in the config registry under its env var" in {
    ConfigRegistry
      .collect(List("quack-on-demand" -> classOf[ManagerConfig]))
      .find(_.path == "quack-on-demand.userS3.allowedEndpoints")
      .map(_.envVar) shouldBe Some("QOD_USER_S3_ALLOWED_ENDPOINTS")
  }
