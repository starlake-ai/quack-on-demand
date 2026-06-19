package ai.starlake.quack.ondemand.api

import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ClientConfigResponseSpec extends AnyFlatSpec with Matchers:

  import Dtos.given

  it should "default identitySource to db and ssoProviderName to empty" in {
    val r = ClientConfigResponse(flightSqlHost = "h", flightSqlPort = 1, flightSqlTls = true)
    r.identitySource shouldBe "db"
    r.ssoProviderName shouldBe ""
  }

  it should "serialize identitySource and ssoProviderName to JSON" in {
    val json = ClientConfigResponse(
      flightSqlHost = "h",
      flightSqlPort = 1,
      flightSqlTls = true,
      identitySource = "oidc",
      ssoProviderName = "accounts.google.com"
    ).asJson.noSpaces
    json should include("\"identitySource\":\"oidc\"")
    json should include("\"ssoProviderName\":\"accounts.google.com\"")
  }
