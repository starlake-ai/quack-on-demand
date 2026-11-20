package ai.starlake.quack.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

/** A disabled tenant over the real REST surface: only a superuser disables or enables one, its
  * users cannot sign in, and their sessions are refused everything but signing out, until a
  * superuser enables it again. Superusers keep full access to the tenant.
  */
class TenantDisableApiSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val t = SecurityFixtures.TenantId

  private def withHarness(body: ManagerServerHarness.Harness => Unit): Unit =
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try body(h)
    finally h.shutdown()

  private def setDisabled(
      h: ManagerServerHarness.Harness,
      token: String,
      disabled: Boolean
  ): HttpResponse[String] =
    post(
      h.httpClient,
      s"${h.baseUrl}/api/tenant/setDisabled",
      s"""{"name":"$t","disabled":$disabled}""",
      apiKey = Some(token)
    )

  private def login(
      h: ManagerServerHarness.Harness,
      user: String,
      pw: String
  ): HttpResponse[String] =
    post(
      h.httpClient,
      s"${h.baseUrl}/api/auth/login",
      s"""{"username":"$user","password":"$pw","tenant":"$t"}"""
    )

  private def code(r: HttpResponse[String]): String =
    withClue(s"status ${r.statusCode()} body: ${r.body()}")(errorCode(r.body()).getOrElse(""))

  private def alice(h: ManagerServerHarness.Harness): String =
    h.mintToken(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword, Some(t))

  "POST /api/tenant/setDisabled" should "be refused to the tenant's own admin" in withHarness { h =>
    val r = setDisabled(h, alice(h), disabled = true)
    r.statusCode() shouldBe 403
    code(r) shouldBe "superuser_required"
  }

  "a disabled tenant" should "refuse its users' sign-in and their open sessions, then take them back" in
    withHarness { h =>
      val a    = alice(h)
      val root = h.superuserSession()
      setDisabled(h, root, disabled = true).statusCode() shouldBe 200

      // An open session: refused everything but signing out.
      val listed = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(a))
      listed.statusCode() shouldBe 403
      code(listed) shouldBe "tenant_disabled"
      val whoami = get(h.httpClient, s"${h.baseUrl}/api/auth/whoami", apiKey = Some(a))
      whoami.statusCode() shouldBe 403

      // A new sign-in, admin or plain user: refused.
      val adminLogin = login(h, SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword)
      adminLogin.statusCode() shouldBe 403
      code(adminLogin) shouldBe "tenant_disabled"
      val userLogin = login(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)
      userLogin.statusCode() shouldBe 403
      code(userLogin) shouldBe "tenant_disabled"

      // A superuser keeps the tenant.
      get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(root)).statusCode() shouldBe
        200

      setDisabled(h, root, disabled = false).statusCode() shouldBe 200
      get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(a)).statusCode() shouldBe 200
      login(h, SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword).statusCode() shouldBe
        200
    }

  it should "still let a refused session sign out" in withHarness { h =>
    val a = alice(h)
    setDisabled(h, h.superuserSession(), disabled = true).statusCode() shouldBe 200
    val req = HttpRequest
      .newBuilder(URI.create(s"${h.baseUrl}/api/auth/logout"))
      .header("X-API-Key", a)
      .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
      .build()
    h.httpClient.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() should not be 403
  }
