// src/test/scala/ai/starlake/quack/security/MockOidcServer.scala
package ai.starlake.quack.security

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

/** WireMock-based mock OIDC Identity Provider for the security e2e test suite.
  *
  * Boots an ephemeral-port HTTP server that mimics the three endpoints a
  * standards-compliant OIDC provider exposes:
  *
  *   - GET  /.well-known/openid-configuration - discovery document
  *   - GET  /jwks                             - JWKS with the test public key
  *   - POST /token                            - ROPC (Resource Owner Password
  *                                             Credentials) token endpoint
  *
  * The token endpoint succeeds for the single seeded user and returns a signed
  * JWT produced by [[JwtTestSigner]]. All other username/password combinations
  * receive HTTP 400.
  *
  * Tests that need different claim shapes can reach into
  * `server.wireMockServer.stubFor(...)` at runtime to replace the `/token` stub
  * with any custom response.
  */
object MockOidcServer:

  /** A running mock OIDC server.
    *
    * @param baseUrl          the HTTP base URL (e.g. "http://localhost:53521")
    * @param issuer           the OIDC issuer - equal to baseUrl by convention
    * @param wireMockServer   the underlying WireMockServer, exposed so callers
    *                         can add custom stubs at runtime
    * @param shutdown         call this in afterAll / try-finally to release the port
    */
  final case class Server(
      baseUrl:         String,
      issuer:          String,
      wireMockServer:  WireMockServer,
      shutdown:        () => Unit
  )

  private val ClientId     = "test-client"
  private val ClientSecret = "test-secret"

  /** Start a WireMock instance on an ephemeral port that serves:
    *
    *   - GET  /.well-known/openid-configuration
    *   - GET  /jwks
    *   - POST /token (ROPC; success only for seededUser / seededPassword)
    *
    * @param seededUser     username accepted by the ROPC endpoint
    * @param seededPassword password accepted by the ROPC endpoint
    */
  def boot(
      seededUser:     String = "oidcuser",
      seededPassword: String = "oidcpw"
  ): Server =
    val wm = new WireMockServer(options().dynamicPort())
    wm.start()

    val port    = wm.port()
    val baseUrl = s"http://localhost:$port"
    val issuer  = baseUrl

    // ------------------------------------------------------------------ //
    // Discovery document                                                   //
    // ------------------------------------------------------------------ //
    val discoveryBody =
      s"""{
         |  "issuer": "$issuer",
         |  "authorization_endpoint": "$baseUrl/authorize",
         |  "token_endpoint": "$baseUrl/token",
         |  "jwks_uri": "$baseUrl/jwks",
         |  "response_types_supported": ["code"],
         |  "subject_types_supported": ["public"],
         |  "id_token_signing_alg_values_supported": ["RS256"]
         |}""".stripMargin

    wm.stubFor(
      get(urlPathEqualTo("/.well-known/openid-configuration"))
        .willReturn(
          okJson(discoveryBody)
        )
    )

    // ------------------------------------------------------------------ //
    // JWKS endpoint                                                        //
    // ------------------------------------------------------------------ //
    val jwksBody = JwtTestSigner.jwksJson().noSpaces

    wm.stubFor(
      get(urlPathEqualTo("/jwks"))
        .willReturn(
          okJson(jwksBody)
        )
    )

    // ------------------------------------------------------------------ //
    // Token endpoint (ROPC)                                                //
    // ------------------------------------------------------------------ //
    // Success stub: matches the seeded user/password
    val accessToken = JwtTestSigner.mint(
      payload  = Map("sub" -> seededUser, "role" -> "admin", "groups" -> ""),
      issuer   = issuer,
      audience = Some(ClientId)
    )
    val tokenSuccessBody =
      s"""{
         |  "access_token": "$accessToken",
         |  "token_type": "Bearer",
         |  "expires_in": 300
         |}""".stripMargin

    // More-specific stub (matching username + password) takes precedence over
    // the generic fallback below because WireMock uses stub-ordering priority.
    wm.stubFor(
      post(urlPathEqualTo("/token"))
        .withRequestBody(containing("grant_type=password"))
        .withRequestBody(containing(s"username=${urlEncode(seededUser)}"))
        .withRequestBody(containing(s"password=${urlEncode(seededPassword)}"))
        .willReturn(
          okJson(tokenSuccessBody)
        )
    )

    // Fallback stub: wrong credentials
    wm.stubFor(
      post(urlPathEqualTo("/token"))
        .withRequestBody(containing("grant_type=password"))
        .atPriority(10) // lower priority than the success stub (default priority 5)
        .willReturn(
          aResponse()
            .withStatus(400)
            .withHeader("Content-Type", "application/json")
            .withBody("""{"error":"invalid_grant","error_description":"Bad credentials"}""")
        )
    )

    Server(
      baseUrl        = baseUrl,
      issuer         = issuer,
      wireMockServer = wm,
      shutdown       = () => wm.stop()
    )

  /** URL-encode a plain string for embedding in form bodies or URL matching.
    * Only handles the characters that commonly appear in test usernames and
    * passwords. For a full implementation use java.net.URLEncoder.
    */
  private def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")