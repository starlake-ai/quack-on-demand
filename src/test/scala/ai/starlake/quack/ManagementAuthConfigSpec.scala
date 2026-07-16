package ai.starlake.quack

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource
import pureconfig._
import pureconfig.generic.semiauto.deriveReader
import pureconfig.generic.ProductHint

class ManagementAuthConfigSpec extends AnyFlatSpec, Matchers:

  private val camel                        = ConfigFieldMapping(CamelCase, CamelCase)
  given ProductHint[ManagementAuthConfig]  = ProductHint[ManagementAuthConfig](camel)
  given ConfigReader[ManagementAuthConfig] = deriveReader[ManagementAuthConfig]

  private val sessionFields =
    """sessionJwtSecret = "", sessionCookieSecure = "auto", sessionCookiePath = "/api""""

  "ManagementAuthConfig" should "parse db identity source" in {
    val cfg = ConfigFactory.parseString(
      s"""auth.management { identitySource = "db", $sessionFields }"""
    )
    val mac = ConfigSource.fromConfig(cfg).at("auth.management").loadOrThrow[ManagementAuthConfig]
    mac.identitySource shouldBe "db"
  }

  it should "parse oidc identity source" in {
    val cfg = ConfigFactory.parseString(
      s"""auth.management { identitySource = "oidc", $sessionFields }"""
    )
    val mac = ConfigSource.fromConfig(cfg).at("auth.management").loadOrThrow[ManagementAuthConfig]
    mac.identitySource shouldBe "oidc"
  }

  it should "default publicBaseUrl to empty and accept an override" in {
    val cfg = ManagementAuthConfig(
      identitySource = "db",
      sessionJwtSecret = "x",
      sessionCookieSecure = "auto",
      sessionCookiePath = "/api",
      publicBaseUrl = ""
    )
    cfg.publicBaseUrl shouldBe ""
    cfg.copy(publicBaseUrl = "https://qod.example.com").publicBaseUrl shouldBe
      "https://qod.example.com"
  }
