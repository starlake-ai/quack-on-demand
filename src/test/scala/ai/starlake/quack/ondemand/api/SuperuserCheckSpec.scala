package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** [[SuperuserCheck]] over a total resolver: exactly a superuser passes; a tenant admin, a token
  * that resolves to nothing and an absent credential all get `403 superuser_required`.
  */
class SuperuserCheckSpec extends AnyFlatSpec with Matchers:

  private val scopeOf = SessionScope.resolver {
    case "root"  => Some(SessionScope.Superuser)
    case "alice" => Some(SessionScope(superuser = false, manageableTenants = Set("acme")))
    case _       => None
  }

  private val Refused = Some((StatusCode.Forbidden, "superuser_required"))

  private def refusal(apiKey: Option[String]) =
    SuperuserCheck.reject(apiKey)(scopeOf).map((code, err) => (code, err.error))

  "SuperuserCheck.reject" should "admit a superuser" in {
    refusal(Some("root")) shouldBe None
  }

  it should "refuse a tenant admin" in {
    refusal(Some("alice")) shouldBe Refused
  }

  it should "refuse a token that resolves to nothing, and an absent credential" in {
    refusal(Some("expired")) shouldBe Refused
    refusal(None) shouldBe Refused
  }
