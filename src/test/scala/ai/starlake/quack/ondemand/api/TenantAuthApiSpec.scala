package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.security.{ManagerServerHarness, SecurityFixtures, SecurityHttpHelpers}
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.http.HttpResponse

/** REST surface of a tenant's sign-in configuration (`tenant/setAuth`, and the `authConfig` rule
  * `tenant/create` shares): the OIDC client secret is write-only and bound to its issuer, and only
  * a superuser adds or changes a `clientSecretRef`.
  */
class TenantAuthApiSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val ApiKey = "k"
  private val t      = SecurityFixtures.TenantId
  private val Issuer = "https://idp.example.com/realms/acme"
  private val Secret = "kc-s3cr3t-value"
  private val Oidc   = Map("issuerUrl" -> Issuer, "clientId" -> "qod")

  private def withHarness(
      body: (ManagerServerHarness.Harness, SecurityFixtures.Fixture) => Unit
  ): Unit =
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some(ApiKey))
    try body(h, fix)
    finally h.shutdown()

  // Sessions are minted first, while `acme` is still a password (db) tenant: a password login into
  // an OIDC tenant is refused, and a minted session outlives the tenant's switch to OIDC.
  private def alice(h: ManagerServerHarness.Harness): String =
    h.mintToken(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword, Some(t))

  private def root(h: ManagerServerHarness.Harness): String =
    h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)

  private def setAuth(
      h: ManagerServerHarness.Harness,
      token: String,
      config: Map[String, String],
      clientSecret: Option[String] = None
  ): HttpResponse[String] =
    val fields = List(
      "name"         -> Json.fromString(t),
      "authProvider" -> Json.fromString("keycloak"),
      "authConfig"   -> Json.obj(config.toList.map((k, v) => k -> Json.fromString(v))*)
    ) ++ clientSecret.map(s => "clientSecret" -> Json.fromString(s))
    post(
      h.httpClient,
      s"${h.baseUrl}/api/tenant/setAuth",
      Json.obj(fields*).noSpaces,
      apiKey = Some(token)
    )

  private def ok(r: HttpResponse[String]) =
    withClue(s"status ${r.statusCode()} body: ${r.body()}") {
      r.statusCode() shouldBe 200
      parse(r.body()).toOption.get.hcursor
    }

  private def code(r: HttpResponse[String]): String =
    withClue(s"body: ${r.body()}")(errorCode(r.body()).getOrElse(""))

  private def stored(fix: SecurityFixtures.Fixture): Tenant =
    fix.store.listTenants().find(_.id == t).get

  "POST /api/tenant/setAuth" should
    "store a tenant admin's secret write-only, keep it under the same issuer, clear it on empty" in
    withHarness { (h, fix) =>
      val a  = alice(h)
      val r1 = setAuth(h, a, Oidc, Some(Secret))
      val j1 = ok(r1)
      r1.body() should not include Secret
      j1.get[Boolean]("clientSecretSet") shouldBe Right(true)
      j1.downField("clientSecret").succeeded shouldBe false
      stored(fix).authClientSecret shouldBe Some(Secret)

      // The listing carries the same write-only shape.
      val list = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(a))
      list.body() should not include Secret
      parse(list.body()).toOption.get.hcursor
        .downField("tenants")
        .downArray
        .get[Boolean]("clientSecretSet") shouldBe Right(true)

      // Same issuer, no clientSecret: kept. An empty value removes it.
      val kept = ok(setAuth(h, a, Oidc.updated("scopes", "openid email")))
      kept.get[Boolean]("clientSecretSet") shouldBe Right(true)
      ok(setAuth(h, a, Oidc, Some(""))).get[Boolean]("clientSecretSet") shouldBe Right(false)
      stored(fix).authClientSecret shouldBe None
    }

  it should
    "refuse a tenant admin's new or changed clientSecretRef with 403, storing nothing" in
    withHarness { (h, fix) =>
      val a      = alice(h)
      val before = stored(fix)
      val added  = setAuth(h, a, Oidc + ("clientSecretRef" -> "env:AWS_SECRET_ACCESS_KEY"))
      added.statusCode() shouldBe 403
      code(added) shouldBe "superuser_required"
      stored(fix) shouldBe before

      // A superuser set one; a tenant admin may not change it either.
      ok(setAuth(h, root(h), Oidc + ("clientSecretRef" -> "env:ACME_OIDC_SECRET")))
      val changed = setAuth(h, a, Oidc + ("clientSecretRef" -> "env:OTHER"))
      changed.statusCode() shouldBe 403
      code(changed) shouldBe "superuser_required"
      stored(fix).authConfig.get("clientSecretRef") shouldBe Some("env:ACME_OIDC_SECRET")
    }

  it should
    "pair a reference with any issuer for a superuser, the same issuer only for a tenant admin" in
    withHarness { (h, fix) =>
      val a       = alice(h)
      val su      = root(h)
      val withRef = Oidc + ("clientSecretRef" -> "env:ACME_OIDC_SECRET")
      ok(setAuth(h, su, withRef)).get[Boolean]("clientSecretSet") shouldBe Right(false)
      stored(fix).authConfig shouldBe withRef

      // A tenant admin carrying it unchanged under the same issuer keeps it; the other edit lands.
      ok(setAuth(h, a, withRef.updated("clientId", "qod2")))
      stored(fix).authConfig shouldBe withRef.updated("clientId", "qod2")

      // A superuser may carry it to a new issuer in the same write.
      val moved = withRef.updated("issuerUrl", "https://second.example.com")
      ok(setAuth(h, su, moved))
      stored(fix).authConfig shouldBe moved

      // A tenant admin carrying it unchanged to a new issuer drops it; the new issuer lands.
      ok(setAuth(h, a, moved.updated("issuerUrl", "https://third.example.com")))
      stored(fix).authConfig.get("clientSecretRef") shouldBe None
      stored(fix).authConfig.get("issuerUrl") shouldBe Some("https://third.example.com")
    }

  it should
    "answer 400 to a blank secret, a clientSecret config key, and a value plus a new reference" in
    withHarness { (h, _) =>
      val blank = setAuth(h, ApiKey, Oidc, Some("   "))
      blank.statusCode() shouldBe 400
      code(blank) shouldBe "invalid_client_secret"

      val inConfig = setAuth(h, ApiKey, Oidc + ("clientSecret" -> Secret))
      inConfig.statusCode() shouldBe 400
      code(inConfig) shouldBe "invalid_auth_config"
      inConfig.body() should not include Secret

      // The static key counts as a superuser, so the reference itself is admitted.
      val both = setAuth(h, ApiKey, Oidc + ("clientSecretRef" -> "env:X"), Some(Secret))
      both.statusCode() shouldBe 400
      code(both) shouldBe "secret_conflict"
    }

  it should "answer 404 for an unknown tenant" in withHarness { (h, _) =>
    val r = post(
      h.httpClient,
      s"${h.baseUrl}/api/tenant/setAuth",
      """{"name":"nope","authProvider":"db"}""",
      apiKey = Some(ApiKey)
    )
    r.statusCode() shouldBe 404
    code(r) shouldBe "not_found"
  }

  "POST /api/tenant/create" should
    "refuse a clientSecret key in authConfig with 400 invalid_auth_config, creating nothing" in
    withHarness { (h, fix) =>
      val body = Json
        .obj(
          "id"           -> Json.fromString("initech"),
          "displayName"  -> Json.fromString("Initech"),
          "authProvider" -> Json.fromString("keycloak"),
          "authConfig"   -> Json.obj(
            "issuerUrl"    -> Json.fromString(Issuer),
            "clientSecret" -> Json.fromString(Secret)
          )
        )
        .noSpaces
      // The static key is a superuser caller: the create gate admits it, the config rule does not.
      val r = post(h.httpClient, s"${h.baseUrl}/api/tenant/create", body, apiKey = Some(ApiKey))
      r.statusCode() shouldBe 400
      code(r) shouldBe "invalid_auth_config"
      r.body() should not include Secret
      fix.store.listTenants().exists(_.id == "initech") shouldBe false
    }
