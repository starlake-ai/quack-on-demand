// src/test/scala/ai/starlake/quack/security/OidcAuthChainSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.auth.{AuthenticationService, AuthScope, OidcBearerAuthenticator}
import ai.starlake.quack.edge.config.*
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}
import java.util.{Date, UUID}

/** End-to-end auth-chain tests driving a real AuthenticationService against MockOidcServer.
  *
  * No Flight server, no manager. Pure auth-chain testing.
  *
  * Each test boots its own MockOidcServer instance via try/finally so ports are
  * always released even on test failure.
  *
  * Keycloak URL conventions used here:
  *   - JWKS:  {baseUrl}/realms/{realm}/protocol/openid-connect/certs
  *   - ROPC:  {baseUrl}/realms/{realm}/protocol/openid-connect/token
  *   - Issuer: {baseUrl}/realms/{realm}
  *
  * Google / Azure / AWS bearer tests construct OidcBearerAuthenticator directly
  * (bypassing OidcProviderFactory) so they can point the JWKS URL at the mock
  * server instead of the hardcoded production endpoints those factories use.
  */
class OidcAuthChainSpec extends AnyFlatSpec with Matchers:

  private val Realm      = "master"
  private val ClientId   = "test-client"
  private val RoleClaim  = "role"

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Build a minimal AuthenticationConfig with only Keycloak enabled. */
  private def keycloakConfig(baseUrl: String): AuthenticationConfig =
    AuthenticationConfig(
      roleClaim = RoleClaim,
      database  = DatabaseAuthConfig(
        enabled     = false,
        jdbcUrl     = "",
        username    = "",
        password    = "",
        systemQuery = "",
        tenantQuery = ""
      ),
      keycloak  = KeycloakAuthConfig(
        enabled      = true,
        baseUrl      = baseUrl,
        realm        = Realm,
        clientId     = ClientId,
        clientSecret = "test-secret"
      ),
      google    = GoogleAuthConfig(
        enabled               = false,
        clientId              = "",
        clientSecret          = "",
        groupsLookup          = false,
        serviceAccountKeyPath = "",
        groupsCacheTtlSeconds = 0L
      ),
      azure     = AzureAuthConfig(
        enabled      = false,
        tenantId     = "",
        clientId     = "",
        clientSecret = ""
      ),
      aws       = AwsAuthConfig(
        enabled    = false,
        region     = "",
        userPoolId = "",
        clientId   = ""
      ),
      jwt       = JwtAuthConfig(
        secretKey     = "",
        publicKeyPath = "",
        issuer        = "",
        audience      = ""
      )
    )

  /** Stub Keycloak-pattern endpoints on the mock server (JWKS + ROPC). */
  private def addKeycloakStubs(mock: MockOidcServer.Server): Unit =
    val keycloakJwksBody = JwtTestSigner.jwksJson().noSpaces

    // JWKS at Keycloak's expected path
    mock.wireMockServer.stubFor(
      get(urlPathEqualTo(s"/realms/$Realm/protocol/openid-connect/certs"))
        .willReturn(okJson(keycloakJwksBody))
    )

    // ROPC endpoint at Keycloak's expected path -- delegate to the same
    // seeded-user response that the root /token stub already serves.
    // We stub it separately to match the Keycloak-flavored path.
    val accessToken = JwtTestSigner.mint(
      payload  = Map("sub" -> "oidcuser", "role" -> "admin", "groups" -> ""),
      issuer   = s"${mock.baseUrl}/realms/$Realm",
      audience = Some(ClientId)
    )
    val tokenBody =
      s"""{
         |  "access_token": "$accessToken",
         |  "token_type": "Bearer",
         |  "expires_in": 300
         |}""".stripMargin

    mock.wireMockServer.stubFor(
      post(urlPathEqualTo(s"/realms/$Realm/protocol/openid-connect/token"))
        .withRequestBody(containing("grant_type=password"))
        .withRequestBody(containing("username=oidcuser"))
        .withRequestBody(containing("password=oidcpw"))
        .willReturn(okJson(tokenBody))
    )

    // Wrong-password fallback for the Keycloak ROPC path
    mock.wireMockServer.stubFor(
      post(urlPathEqualTo(s"/realms/$Realm/protocol/openid-connect/token"))
        .withRequestBody(containing("grant_type=password"))
        .atPriority(10)
        .willReturn(
          aResponse()
            .withStatus(400)
            .withHeader("Content-Type", "application/json")
            .withBody("""{"error":"invalid_grant","error_description":"Bad credentials"}""")
        )
    )

  // ---------------------------------------------------------------------------
  // A. Bearer JWT verification (Keycloak path)
  // ---------------------------------------------------------------------------

  "OidcAuthChainSpec (A1)" should
    "accept a valid JWT signed by MockOidcServer's key" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock)
    val svc = new AuthenticationService(keycloakConfig(mock.baseUrl), "")
    try
      val keycloakIssuer = s"${mock.baseUrl}/realms/$Realm"
      val jwt = JwtTestSigner.mint(
        payload  = Map("sub" -> "u", "role" -> "admin"),
        issuer   = keycloakIssuer,
        audience = Some(ClientId),
        expIn    = Duration.ofMinutes(5)
      )
      val result = svc.authenticateBearer(jwt)
      result shouldBe a[Right[?, ?]]
      val profile = result.toOption.get
      // OidcBearerAuthenticator uses preferred_username -> email -> sub fallback
      profile.username shouldBe "u"
      profile.role     shouldBe "admin"
    finally
      svc.close()
      mock.shutdown()

  "OidcAuthChainSpec (A2)" should
    "reject a JWT signed by a different (unknown) RSA key" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock)
    val svc = new AuthenticationService(keycloakConfig(mock.baseUrl), "")
    try
      val wrongKey    = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString).generate()
      val wrongSigner = new RSASSASigner(wrongKey.toRSAPrivateKey)

      val keycloakIssuer = s"${mock.baseUrl}/realms/$Realm"
      val claimsBuilder  = new JWTClaimsSet.Builder()
        .subject("u")
        .claim("role", "admin")
        .issuer(keycloakIssuer)
        .audience(ClientId)
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(wrongKey.getKeyID)
        .build()
      val jwt = new SignedJWT(header, claimsBuilder.build())
      jwt.sign(wrongSigner)
      val badJwt = jwt.serialize()

      val result = svc.authenticateBearer(badJwt)
      result shouldBe a[Left[?, ?]]
    finally
      svc.close()
      mock.shutdown()

  "OidcAuthChainSpec (A3)" should
    "reject an expired JWT" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock)
    val svc = new AuthenticationService(keycloakConfig(mock.baseUrl), "")
    try
      val keycloakIssuer = s"${mock.baseUrl}/realms/$Realm"
      // Negative duration -> exp is in the past
      val jwt = JwtTestSigner.mint(
        payload  = Map("sub" -> "u", "role" -> "admin"),
        issuer   = keycloakIssuer,
        audience = Some(ClientId),
        expIn    = Duration.ofMinutes(-1)
      )
      val result = svc.authenticateBearer(jwt)
      result shouldBe a[Left[?, ?]]
      // The error message should reference expiry in some way
      val msg = result.swap.getOrElse("")
      msg.toLowerCase should (include("expired") or include("expir") or include("jwt"))
    finally
      svc.close()
      mock.shutdown()

  "OidcAuthChainSpec (A4)" should
    "reject a JWT with the wrong issuer" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock)
    val svc = new AuthenticationService(keycloakConfig(mock.baseUrl), "")
    try
      val jwt = JwtTestSigner.mint(
        payload  = Map("sub" -> "u", "role" -> "admin"),
        issuer   = "https://evil.example.com",
        audience = Some(ClientId),
        expIn    = Duration.ofMinutes(5)
      )
      val result = svc.authenticateBearer(jwt)
      result shouldBe a[Left[?, ?]]
    finally
      svc.close()
      mock.shutdown()

  "OidcAuthChainSpec (A5)" should
    "reject a JWT with the wrong audience (audience enforcement is enabled when clientId is set)" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock)
    val svc = new AuthenticationService(keycloakConfig(mock.baseUrl), "")
    try
      val keycloakIssuer = s"${mock.baseUrl}/realms/$Realm"
      // OidcBearerAuthenticator enforces audience when expectedAudience is non-empty.
      // OidcProviderFactory.createKeycloak passes config.clientId as the expected audience.
      // clientId = "test-client", so a JWT with aud = "not-our-client" must be rejected.
      val jwt = JwtTestSigner.mint(
        payload  = Map("sub" -> "u", "role" -> "admin"),
        issuer   = keycloakIssuer,
        audience = Some("not-our-client"),
        expIn    = Duration.ofMinutes(5)
      )
      val result = svc.authenticateBearer(jwt)
      result shouldBe a[Left[?, ?]]
    finally
      svc.close()
      mock.shutdown()

  // ---------------------------------------------------------------------------
  // B. ROPC (Resource Owner Password Credentials) for Keycloak
  // ---------------------------------------------------------------------------

  "OidcAuthChainSpec (B6)" should
    "authenticate via ROPC with correct credentials" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock)
    val svc = new AuthenticationService(keycloakConfig(mock.baseUrl), "")
    try
      val result = svc.authenticateBasic(AuthScope.Tenant("acme"), "oidcuser", "oidcpw")
      result shouldBe a[Right[?, ?]]
      val profile = result.toOption.get
      profile.username shouldBe "oidcuser"
      profile.role     shouldBe "admin"
    finally
      svc.close()
      mock.shutdown()

  "OidcAuthChainSpec (B7)" should
    "reject ROPC authentication with wrong password" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock)
    val svc = new AuthenticationService(keycloakConfig(mock.baseUrl), "")
    try
      val result = svc.authenticateBasic(AuthScope.Tenant("acme"), "oidcuser", "wrongpw")
      result shouldBe a[Left[?, ?]]
    finally
      svc.close()
      mock.shutdown()

  // ---------------------------------------------------------------------------
  // C. Single happy-path for Google / Azure / AWS bearer
  //
  // OidcProviderFactory hardcodes the JWKS URLs for these providers
  // (googleapis.com / login.microsoftonline.com / cognito-idp.*.amazonaws.com).
  // We cannot point those factory methods at WireMock without DNS overrides, so
  // we construct OidcBearerAuthenticator directly with the mock server's JWKS
  // endpoint and the matching expected issuer / audience. This validates the
  // shared OidcBearerAuthenticator logic (JWKS fetch, RS256 verification, claim
  // extraction) for all three provider shapes.
  // ---------------------------------------------------------------------------

  "OidcAuthChainSpec (C8)" should
    "accept a Google-shaped bearer JWT via OidcBearerAuthenticator" in:
    val mock = MockOidcServer.boot()
    try
      // Google's expected issuer per OidcProviderFactory.createGoogle is
      // "https://accounts.google.com". For the test we use the mock's issuer
      // so the JWT and the authenticator agree.
      val mockIssuer = mock.issuer
      val authenticator = new OidcBearerAuthenticator(
        providerName     = "google",
        jwksUrl          = s"${mock.baseUrl}/jwks",
        expectedIssuer   = mockIssuer,
        expectedAudience = "google-client-id",
        roleClaim        = RoleClaim
      )
      val jwt = JwtTestSigner.mint(
        payload  = Map("sub" -> "google-user", "role" -> "viewer", "email" -> "google-user@example.com"),
        issuer   = mockIssuer,
        audience = Some("google-client-id"),
        expIn    = Duration.ofMinutes(5)
      )
      val result = authenticator.authenticate(jwt)
      result shouldBe a[Right[?, ?]]
      val profile = result.toOption.get
      // preferred_username absent -> email fallback
      profile.username shouldBe "google-user@example.com"
      profile.role     shouldBe "viewer"
    finally mock.shutdown()

  "OidcAuthChainSpec (C9)" should
    "accept an Azure-shaped bearer JWT via OidcBearerAuthenticator" in:
    val mock = MockOidcServer.boot()
    try
      val mockIssuer = mock.issuer
      // Azure's issuer per factory: https://login.microsoftonline.com/{tenantId}/v2.0
      // We use the mock's issuer for both sides to keep the test self-contained.
      val authenticator = new OidcBearerAuthenticator(
        providerName     = "azure",
        jwksUrl          = s"${mock.baseUrl}/jwks",
        expectedIssuer   = mockIssuer,
        expectedAudience = "azure-client-id",
        roleClaim        = RoleClaim
      )
      val jwt = JwtTestSigner.mint(
        payload  = Map("sub" -> "azure-user", "role" -> "analyst", "preferred_username" -> "azure-user@corp.com"),
        issuer   = mockIssuer,
        audience = Some("azure-client-id"),
        expIn    = Duration.ofMinutes(5)
      )
      val result = authenticator.authenticate(jwt)
      result shouldBe a[Right[?, ?]]
      val profile = result.toOption.get
      profile.username shouldBe "azure-user@corp.com"
      profile.role     shouldBe "analyst"
    finally mock.shutdown()

  "OidcAuthChainSpec (C10)" should
    "accept an AWS Cognito-shaped bearer JWT via OidcBearerAuthenticator" in:
    val mock = MockOidcServer.boot()
    try
      // AWS Cognito's JWKS URL per factory:
      //   https://cognito-idp.{region}.amazonaws.com/{userPoolId}/.well-known/jwks.json
      // The issuer is the same base URL without the jwks suffix.
      // We use the mock server's /jwks endpoint and issuer for both sides.
      val mockIssuer = mock.issuer
      val authenticator = new OidcBearerAuthenticator(
        providerName     = "aws-cognito",
        jwksUrl          = s"${mock.baseUrl}/jwks",
        expectedIssuer   = mockIssuer,
        expectedAudience = "cognito-app-client-id",
        roleClaim        = RoleClaim
      )
      val jwt = JwtTestSigner.mint(
        payload  = Map("sub" -> "cognito-user", "role" -> "reader"),
        issuer   = mockIssuer,
        audience = Some("cognito-app-client-id"),
        expIn    = Duration.ofMinutes(5)
      )
      val result = authenticator.authenticate(jwt)
      result shouldBe a[Right[?, ?]]
      val profile = result.toOption.get
      profile.username shouldBe "cognito-user"
      profile.role     shouldBe "reader"
    finally mock.shutdown()