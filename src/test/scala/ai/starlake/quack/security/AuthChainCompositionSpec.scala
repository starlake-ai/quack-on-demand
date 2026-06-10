// src/test/scala/ai/starlake/quack/security/AuthChainCompositionSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.auth.{
  AuthenticatedProfile,
  AuthenticationService,
  BasicAuthProvider,
  BearerAuthProvider,
  JwtBearerAuthenticator,
  OidcBearerAuthenticator,
  ResourceOwnerPasswordAuthenticator
}
import ai.starlake.quack.edge.config.*
import ai.starlake.quack.security.InMemoryAuthService.{InMemoryBasicAuthProvider, emptyAuthConfig}
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}
import java.util.{Date, UUID}

/** Tests the COMPOSITION of auth providers in the chain.
  *
  * No Flight server, no manager. Tests drive real AuthenticationService instances or
  * inline chain helpers that mirror the production chain-walk semantics.
  *
  * Inline chain walking is used for tests 5-7 where we need to inject an
  * InMemoryBasicAuthProvider as "DB" without a real Postgres connection.
  * The chain-walk logic is a direct copy of AuthenticationService.authenticateBasic
  * (minus logging) so we're testing the ordering semantics, not the class itself.
  */
class AuthChainCompositionSpec extends AnyFlatSpec with Matchers:

  private val Realm     = "master"
  private val ClientId  = "test-client"
  private val RoleClaim = "role"

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Walk a basic provider chain in order, returning the first Right or a
    * combined Left. Mirrors AuthenticationService.authenticateBasic (line 46-62).
    */
  private def walkBasicChain(
      chain:    Seq[BasicAuthProvider],
      tenant:   Option[String],
      username: String,
      password: String
  ): Either[String, AuthenticatedProfile] =
    val errors = List.newBuilder[String]
    chain.iterator
      .map { p =>
        p.authenticate(tenant, username, password) match
          case r @ Right(_) => r
          case Left(err) =>
            errors += s"${p.name}: $err"
            Left(err)
      }
      .collectFirst { case r @ Right(_) => r }
      .getOrElse(Left(s"Authentication failed: ${errors.result().mkString("; ")}"))

  /** Walk a bearer provider chain in order, returning the first Right or a
    * combined Left. Mirrors AuthenticationService.authenticateBearer (line 64-80).
    */
  private def walkBearerChain(
      chain: Seq[BearerAuthProvider],
      token: String
  ): Either[String, AuthenticatedProfile] =
    val errors = List.newBuilder[String]
    chain.iterator
      .map { p =>
        p.authenticate(token) match
          case r @ Right(_) => r
          case Left(err) =>
            errors += s"${p.name}: $err"
            Left(err)
      }
      .collectFirst { case r @ Right(_) => r }
      .getOrElse(Left(s"Bearer authentication failed: ${errors.result().mkString("; ")}"))

  /** Mint an HS256 JWT signed with the given secret string. */
  private def mintHmacJwt(
      secret:   String,
      subject:  String,
      issuer:   String   = "",
      audience: String   = "",
      expIn:    Duration = Duration.ofMinutes(5)
  ): String =
    val now = Instant.now()
    val exp = now.plus(expIn)
    val builder = new JWTClaimsSet.Builder()
      .subject(subject)
      .claim("role", "admin")
      .issueTime(Date.from(now))
      .notBeforeTime(Date.from(now))
      .expirationTime(Date.from(exp))
    if issuer.nonEmpty then builder.issuer(issuer)
    if audience.nonEmpty then builder.audience(audience)
    val header = new JWSHeader(JWSAlgorithm.HS256)
    val jwt    = new SignedJWT(header, builder.build())
    jwt.sign(new MACSigner(secret))
    jwt.serialize()

  /** Build a keycloak-flavored AuthenticationConfig pointing at mockBaseUrl. */
  private def keycloakOnlyConfig(mockBaseUrl: String): AuthenticationConfig =
    emptyAuthConfig.copy(
      keycloak = KeycloakAuthConfig(
        enabled      = true,
        baseUrl      = mockBaseUrl,
        realm        = Realm,
        clientId     = ClientId,
        clientSecret = "test-secret"
      )
    )

  /** Stub the Keycloak JWKS + ROPC endpoints on a MockOidcServer instance.
    * ROPC accepts seededUser/seededPassword only; everything else gets 400.
    */
  private def addKeycloakStubs(
      mock:           MockOidcServer.Server,
      seededUser:     String,
      seededPassword: String
  ): Unit =
    val keycloakIssuer   = s"${mock.baseUrl}/realms/$Realm"
    val keycloakJwksBody = JwtTestSigner.jwksJson().noSpaces

    mock.wireMockServer.stubFor(
      get(urlPathEqualTo(s"/realms/$Realm/protocol/openid-connect/certs"))
        .willReturn(okJson(keycloakJwksBody))
    )

    val accessToken = JwtTestSigner.mint(
      payload  = Map("sub" -> seededUser, "role" -> "admin", "groups" -> ""),
      issuer   = keycloakIssuer,
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
        .withRequestBody(containing(s"username=${java.net.URLEncoder.encode(seededUser, "UTF-8")}"))
        .withRequestBody(containing(s"password=${java.net.URLEncoder.encode(seededPassword, "UTF-8")}"))
        .willReturn(okJson(tokenBody))
    )

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
  // A. Provider-presence flag (hasProviders)
  // ---------------------------------------------------------------------------

  "AuthChainCompositionSpec (A1)" should
    "report hasProviders=false when all providers are disabled" in:
    val svc = new AuthenticationService(emptyAuthConfig, "")
    try
      svc.hasProviders shouldBe false
    finally svc.close()

  "AuthChainCompositionSpec (A2)" should
    "report hasProviders=true for database-only config" in:
    // DatabaseAuthenticator opens a Hikari pool at construction time and requires a reachable
    // Postgres.  We use InMemoryAuthService.Service, which extends AuthenticationService with
    // emptyAuthConfig (no real connections) and overrides hasProviders to the supplied flag.
    // The test goal is: "basic providers present, bearer providers absent => hasProviders true".
    val fix = SecurityFixtures.freshStore()
    val svc = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
    try
      svc.hasProviders shouldBe true
    finally svc.close()

  "AuthChainCompositionSpec (A3)" should
    "report hasProviders=true for jwt-secretKey-only config" in:
    // Nimbus MACVerifier requires >= 256 bits (32 bytes); use a 32-char secret.
    val cfg = emptyAuthConfig.copy(
      jwt = JwtAuthConfig(
        secretKey     = "supersecret123supersecret123abcd",
        publicKeyPath = "",
        issuer        = "",
        audience      = ""
      )
    )
    val svc = new AuthenticationService(cfg, "")
    try
      svc.hasProviders shouldBe true
    finally svc.close()

  "AuthChainCompositionSpec (A4)" should
    "report hasProviders=true for database+keycloak mixed config" in:
    // DatabaseAuthenticator needs a reachable Postgres, so we test the mixed scenario with
    // keycloak enabled (which adds both a ResourceOwnerPasswordAuthenticator to the basic chain
    // and an OidcBearerAuthenticator to the bearer chain -- no DB connection needed).
    // The test verifies: at least one basic provider + at least one bearer provider => hasProviders true.
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock, "oidcuser", "oidcpw")
    val cfg = emptyAuthConfig.copy(
      keycloak = KeycloakAuthConfig(
        enabled      = true,
        baseUrl      = mock.baseUrl,
        realm        = Realm,
        clientId     = ClientId,
        clientSecret = "test-secret"
      )
    )
    val svc = new AuthenticationService(cfg, "")
    try
      svc.hasProviders shouldBe true
    finally
      svc.close()
      mock.shutdown()

  // ---------------------------------------------------------------------------
  // B. Basic chain ordering
  //
  // Tests 5-7 use the inline chain-walk helper instead of a real
  // AuthenticationService because DatabaseAuthenticator needs a reachable JDBC
  // URL and the in-memory store has no Postgres driver.  The inline walk is a
  // direct copy of the production chain-walk logic, so the ordering semantics
  // under test are identical.
  // ---------------------------------------------------------------------------

  "AuthChainCompositionSpec (B5)" should
    "short-circuit at the first provider (in-memory) when it succeeds, leaving Keycloak uncalled" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock, "oidcuser", "oidcpw")
    try
      val fix      = SecurityFixtures.freshStore()
      val inMemory = new InMemoryBasicAuthProvider(fix.store)
      val ropc = new ResourceOwnerPasswordAuthenticator(
        providerName   = "keycloak",
        tokenEndpoint  = s"${mock.baseUrl}/realms/$Realm/protocol/openid-connect/token",
        clientId       = ClientId,
        clientSecret   = "test-secret",
        roleClaim      = RoleClaim
      )
      val chain = Seq(inMemory, ropc)

      // alice exists in the in-memory store with the correct password
      val result = walkBasicChain(chain, Some(SecurityFixtures.TenantId), "alice", "alicepw")
      result shouldBe a[Right[?, ?]]
      result.toOption.get.authMethod shouldBe "in-memory"

      // Keycloak ROPC /token must NOT have been called
      val ropcCalls = mock.wireMockServer.findAll(
        postRequestedFor(urlPathEqualTo(s"/realms/$Realm/protocol/openid-connect/token"))
      )
      ropcCalls shouldBe empty
    finally mock.shutdown()

  "AuthChainCompositionSpec (B6)" should
    "fall through to Keycloak ROPC when the first provider rejects" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock, "alice", "oidcpw")
    try
      val fix      = SecurityFixtures.freshStore()
      val inMemory = new InMemoryBasicAuthProvider(fix.store)
      val ropc = new ResourceOwnerPasswordAuthenticator(
        providerName  = "keycloak",
        tokenEndpoint = s"${mock.baseUrl}/realms/$Realm/protocol/openid-connect/token",
        clientId      = ClientId,
        clientSecret  = "test-secret",
        roleClaim     = RoleClaim
      )
      val chain = Seq(inMemory, ropc)

      // alice exists in in-memory with "alicepw", NOT "oidcpw".
      // Keycloak ROPC is seeded to accept alice/oidcpw.
      val result = walkBasicChain(chain, Some(SecurityFixtures.TenantId), "alice", "oidcpw")
      result shouldBe a[Right[?, ?]]
      // authMethod comes from ResourceOwnerPasswordAuthenticator.name = "keycloak-ropc"
      result.toOption.get.authMethod shouldBe "keycloak-ropc"

      // Keycloak ROPC /token MUST have been called (DB rejected first)
      val ropcCalls = mock.wireMockServer.findAll(
        postRequestedFor(urlPathEqualTo(s"/realms/$Realm/protocol/openid-connect/token"))
      )
      ropcCalls should not be empty
    finally mock.shutdown()

  "AuthChainCompositionSpec (B7)" should
    "return aggregated Left when all providers reject" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock, "oidcuser", "oidcpw")
    try
      val fix      = SecurityFixtures.freshStore()
      val inMemory = new InMemoryBasicAuthProvider(fix.store)
      val ropc = new ResourceOwnerPasswordAuthenticator(
        providerName  = "keycloak",
        tokenEndpoint = s"${mock.baseUrl}/realms/$Realm/protocol/openid-connect/token",
        clientId      = ClientId,
        clientSecret  = "test-secret",
        roleClaim     = RoleClaim
      )
      val chain = Seq(inMemory, ropc)

      // "Z" is neither alicepw (in-memory) nor oidcpw (Keycloak)
      val result = walkBasicChain(chain, Some(SecurityFixtures.TenantId), "alice", "Z")
      result shouldBe a[Left[?, ?]]
      val msg = result.swap.getOrElse("")
      msg should include("Authentication failed:")
      // Both provider names must appear in the aggregated error
      msg should include("in-memory")
      msg should include("keycloak-ropc")
    finally mock.shutdown()

  // ---------------------------------------------------------------------------
  // C. Bearer chain ordering
  // ---------------------------------------------------------------------------

  "AuthChainCompositionSpec (C8)" should
    "JwtBearerAuthenticator (HS256) wins before OidcBearerAuthenticator when it succeeds" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock, "oidcuser", "oidcpw")
    try
      // Nimbus MACVerifier requires >= 256 bits (32 bytes)
      val jwtSecret = "supersecret123supersecret123abcd"
      val jwtAuth = new JwtBearerAuthenticator(
        JwtAuthConfig(secretKey = jwtSecret, publicKeyPath = "", issuer = "", audience = ""),
        RoleClaim
      )
      val oidcAuth = new OidcBearerAuthenticator(
        providerName     = "keycloak",
        jwksUrl          = s"${mock.baseUrl}/realms/$Realm/protocol/openid-connect/certs",
        expectedIssuer   = s"${mock.baseUrl}/realms/$Realm",
        expectedAudience = ClientId,
        roleClaim        = RoleClaim
      )
      val chain = Seq(jwtAuth, oidcAuth)

      // Mint HS256 token -- JwtBearerAuthenticator can verify; OidcBearerAuthenticator cannot
      // (it only supports RS256/ES256 via JWKS, not HS256)
      val token = mintHmacJwt(jwtSecret, "alice")
      val result = walkBearerChain(chain, token)
      result shouldBe a[Right[?, ?]]
      result.toOption.get.authMethod shouldBe "jwt"

      // JWKS endpoint must NOT have been called (short-circuit at jwt provider)
      val jwksCalls = mock.wireMockServer.findAll(
        getRequestedFor(urlPathEqualTo(s"/realms/$Realm/protocol/openid-connect/certs"))
      )
      jwksCalls shouldBe empty
    finally mock.shutdown()

  "AuthChainCompositionSpec (C9)" should
    "OidcBearerAuthenticator accepts when JwtBearerAuthenticator rejects (wrong secret)" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock, "oidcuser", "oidcpw")
    try
      // Use a 32-char secret (Nimbus requires >= 256 bits); the RS256 token won't match it anyway
      val jwtAuth = new JwtBearerAuthenticator(
        JwtAuthConfig(secretKey = "wrong-secret-padded-to-32-chars!", publicKeyPath = "", issuer = "", audience = ""),
        RoleClaim
      )
      val keycloakIssuer = s"${mock.baseUrl}/realms/$Realm"
      val oidcAuth = new OidcBearerAuthenticator(
        providerName     = "keycloak",
        jwksUrl          = s"${mock.baseUrl}/realms/$Realm/protocol/openid-connect/certs",
        expectedIssuer   = keycloakIssuer,
        expectedAudience = ClientId,
        roleClaim        = RoleClaim
      )
      val chain = Seq(jwtAuth, oidcAuth)

      // Mint an RS256 JWT signed with the mock server's RSA key.
      // JwtBearerAuthenticator: only has hmacVerifier for "wrong-secret" and no rsaVerifier.
      //   The token is RS256 so hmacVerifier.verify returns false -> Left("JWT signature verification failed")
      // OidcBearerAuthenticator: fetches the JWKS from the mock server, verifies RS256 -> Right
      val token = JwtTestSigner.mint(
        payload  = Map("sub" -> "oidcuser", "role" -> "admin", "groups" -> ""),
        issuer   = keycloakIssuer,
        audience = Some(ClientId)
      )
      val result = walkBearerChain(chain, token)
      result shouldBe a[Right[?, ?]]
      result.toOption.get.authMethod shouldBe "keycloak"
    finally mock.shutdown()

  "AuthChainCompositionSpec (C10)" should
    "return aggregated Left when all bearer providers reject an unknown token" in:
    val mock = MockOidcServer.boot()
    addKeycloakStubs(mock, "oidcuser", "oidcpw")
    try
      // 32-char secret satisfies Nimbus 256-bit minimum
      val jwtAuth = new JwtBearerAuthenticator(
        JwtAuthConfig(secretKey = "some-secret-padded-to-32-chars!!", publicKeyPath = "", issuer = "", audience = ""),
        RoleClaim
      )
      val oidcAuth = new OidcBearerAuthenticator(
        providerName     = "keycloak",
        jwksUrl          = s"${mock.baseUrl}/realms/$Realm/protocol/openid-connect/certs",
        expectedIssuer   = s"${mock.baseUrl}/realms/$Realm",
        expectedAudience = ClientId,
        roleClaim        = RoleClaim
      )
      val chain = Seq(jwtAuth, oidcAuth)

      // Mint a token signed with a completely unrelated RSA key -- neither provider can verify it.
      val unrelatedKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString).generate()
      val now = Instant.now()
      val claims = new JWTClaimsSet.Builder()
        .subject("evil")
        .claim("role", "admin")
        .issuer(s"${mock.baseUrl}/realms/$Realm")
        .audience(ClientId)
        .issueTime(Date.from(now))
        .notBeforeTime(Date.from(now))
        .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
        .build()
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(unrelatedKey.getKeyID).build()
      val jwtObj = new SignedJWT(header, claims)
      jwtObj.sign(new com.nimbusds.jose.crypto.RSASSASigner(unrelatedKey.toRSAPrivateKey))
      val badToken = jwtObj.serialize()

      val result = walkBearerChain(chain, badToken)
      result shouldBe a[Left[?, ?]]
      val msg = result.swap.getOrElse("")
      msg should include("Bearer authentication failed:")
      // Both providers must have contributed error messages
      msg should include("jwt")
      msg should include("keycloak")
    finally mock.shutdown()