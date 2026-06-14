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

  "ManagementAuthConfig" should "default to db identity source and preferred_username claim" in {
    val cfg = ConfigFactory.parseString(
      s"""auth.management { identitySource = "db", identityClaim = "preferred_username", $sessionFields }"""
    )
    val mac = ConfigSource.fromConfig(cfg).at("auth.management").loadOrThrow[ManagementAuthConfig]
    mac.identitySource shouldBe "db"
    mac.identityClaim shouldBe "preferred_username"
  }

  it should "parse oidc identity source" in {
    val cfg = ConfigFactory.parseString(
      s"""auth.management { identitySource = "oidc", identityClaim = "email", $sessionFields }"""
    )
    val mac = ConfigSource.fromConfig(cfg).at("auth.management").loadOrThrow[ManagementAuthConfig]
    mac.identitySource shouldBe "oidc"
    mac.identityClaim shouldBe "email"
  }
