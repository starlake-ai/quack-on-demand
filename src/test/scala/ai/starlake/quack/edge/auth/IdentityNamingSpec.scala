package ai.starlake.quack.edge.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The shared naming rule, on profiles shaped as the identity-provider authenticators build them.
  */
class IdentityNamingSpec extends AnyFlatSpec with Matchers:

  private def profile(username: String, claims: Map[String, String]) =
    AuthenticatedProfile(username, "user", Set.empty, claims, "keycloak")

  "IdentityNaming.userNameMayBindRow" should "refuse a blank user name, whatever claims carry it" in {
    IdentityNaming.userNameMayBindRow(profile("", Map("preferred_username" -> ""))) shouldBe false
    IdentityNaming.userNameMayBindRow(
      profile("  ", Map("preferred_username" -> "  "))
    ) shouldBe false
  }

  it should "count a name from the email claim only when email_verified is true" in {
    val claims = Map("email" -> "carol@acme.example")
    IdentityNaming.userNameMayBindRow(profile("carol@acme.example", claims)) shouldBe false
    IdentityNaming.userNameMayBindRow(
      profile("carol@acme.example", claims + ("email_verified" -> "true"))
    ) shouldBe true
  }

  it should "count a preferred_username or a subject as it is" in {
    IdentityNaming.userNameMayBindRow(
      profile("carol@acme.example", Map("preferred_username" -> "carol@acme.example"))
    ) shouldBe true
    IdentityNaming.userNameMayBindRow(profile("sub-1", Map("sub" -> "sub-1"))) shouldBe true
  }

  "IdentityNaming.namesNoUser" should "hold for an empty or whitespace-only user name only" in {
    IdentityNaming.namesNoUser(profile("", Map.empty)) shouldBe true
    IdentityNaming.namesNoUser(profile(" \t", Map("sub" -> " \t"))) shouldBe true
    IdentityNaming.namesNoUser(profile("carol", Map("sub" -> "carol"))) shouldBe false
  }
