package ai.starlake.quack.security

import ai.starlake.quack.{Main, ManagerConfig}
import ai.starlake.quack.boot.ManagementAuthWiring
import ai.starlake.quack.edge.auth.{LocalIdp, OidcSsoService, OidcStateClaims, OidcStateCodec}
import ai.starlake.quack.edge.config.AuthenticationConfig
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import ch.qos.logback.classic.Level
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The anonymous SSO callback of a tenant whose issuer serves a discovery document with a URL that
  * is no URL, over HTTP on the real manager ([[ManagerServerHarness]]), with the service wired as
  * `ManagementAuthWiring.build` wires it for Main.
  *
  * A tenant admin picks the issuer, and anyone can then call the callback. A `jwks_uri` that was no
  * URI threw out of `completeAuth` (the id_token validator was built from it): 200 anonymous
  * callbacks were 200 `500`s and 200 ERROR entries with their stacks at the default level, 4.3 KB
  * each, quoting the issuer's text and the query's `code` and `state`, 1.5 MB a second of log from
  * one client. Now the document is refused where it is read, and the callback is the IdP's error
  * like any other.
  */
class SsoBadEndpointsSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:
  import Main.given

  private val Acme      = SecurityFixtures.TenantId
  private val Secret    = "sso-bad-endpoints-state-secret-0123456789"
  private val ClientId  = "acme-console"
  private val Marker    = "marker-7f3a"
  private val Callbacks = 200
  // Tapir's own logger: an exception reaching its handler is logged there at ERROR, with its stack.
  private val TapirLog = "sttp.tapir.server.http4s.Http4sDefaultServerLog"

  private def managerConfig: ManagerConfig =
    ConfigSource
      .string(
        s"""quack-on-demand.auth.management {
           |  identitySource = "db"
           |  sessionJwtSecret = "$Secret"
           |  publicBaseUrl = "https://qod.example.com"
           |  oidc { issuerUrl = "", clientId = "", clientSecret = "" }
           |}""".stripMargin
      )
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]

  /** A discovery document for `issuer` whose `field` holds `value`. */
  private def discoveryDoc(issuer: String, field: String, value: String): String =
    val fields = Map(
      "issuer"                 -> s"$issuer",
      "authorization_endpoint" -> s"$issuer/auth",
      "token_endpoint"         -> s"$issuer/token",
      "jwks_uri"               -> s"$issuer/jwks"
    ) + (field -> value)
    Json.obj(fields.toList.map((k, v) => k -> Json.fromString(v))*).noSpaces

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  "The SSO callback of a tenant whose issuer serves a URL that is no URL" should "answer the IdP error to every callback, and write no ERROR" in
    List(
      "jwks_uri"               -> s"not a uri ^ $Marker",
      "jwks_uri"               -> "file:/dev/ptmx",
      "token_endpoint"         -> s"/relative/$Marker",
      "authorization_endpoint" -> s"javascript:alert('$Marker')"
    ).foreach { (field, value) =>
      val idp = new LocalIdp
      try
        val issuer = s"${idp.base}/t"
        idp.serve("/t/.well-known/openid-configuration", 200, discoveryDoc(issuer, field, value))
        // An answer the validator would be built from, if the document had been taken.
        idp.serve("/t/token", 200, """{"id_token":"not-a-jwt"}""")
        val fix = SecurityFixtures.freshStore()
        fix.store.listTenants().find(_.id == Acme).foreach { t =>
          fix.store.upsertTenant(
            t.copy(
              authProvider = "keycloak",
              authConfig = Map("issuerUrl" -> issuer, "clientId" -> ClientId),
              authClientSecret = Some("acme-console-secret")
            )
          )
        }
        val h = ManagerServerHarness.boot(
          fix.store,
          oidcOf = Some(sup =>
            ManagementAuthWiring
              .build(managerConfig, AuthenticationConfig.disabled, id => sup.getTenantById(id))
              .oidcSso
              .getOrElse(fail("no SSO service"))
          ),
          resolveTenantOf =
            Some(sup => raw => sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id))
        )
        try
          // A state the manager signed, as `start` would have issued it: the callback is what an
          // anonymous client repeats, and it needs nothing but this.
          val codec = new OidcStateCodec(Secret, 600000L)
          val state = codec.sign(
            OidcStateClaims(
              "tenant",
              Some(Acme),
              codec.genNonce("seed"),
              "/ui/",
              System.currentTimeMillis()
            )
          )
          val cookie = s"${OidcSsoService.STATE_COOKIE}=$state::${codec.genPkce("seed").verifier}"
          def callback(): (Int, String) =
            val resp = h.httpClient.send(
              HttpRequest
                .newBuilder(
                  URI.create(s"${h.baseUrl}/api/auth/oidc/callback?code=abc&state=${enc(state)}")
                )
                .header("Cookie", cookie)
                .timeout(RequestTimeout)
                .GET()
                .build(),
              HttpResponse.BodyHandlers.ofString()
            )
            (resp.statusCode(), resp.headers().firstValue("location").orElse(""))

          val (answers, events) = LogCapture.logged(TapirLog)(List.fill(Callbacks)(callback()))

          withClue(s"[$field is $value] ") {
            answers.distinct shouldBe List((302, "/ui/?error=idp_error"))
            val loud = events.filter(_.getLevel.isGreaterOrEqual(Level.WARN))
            loud shouldBe empty
          }
        finally h.shutdown()
      finally idp.close()
    }
