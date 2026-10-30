// src/test/scala/ai/starlake/quack/security/DataPlaneIdentityNamingSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.{EdgeHandshake, HandshakeBound, HandshakeFailure}
import ai.starlake.quack.edge.adapter.QuackTransport
import ai.starlake.quack.edge.auth.{
  AuthenticationService,
  JwtBearerAuthenticator,
  OidcBearerAuthenticator,
  ResourceOwnerPasswordAuthenticator,
  TenantOidcRegistry
}
import ai.starlake.quack.edge.config.JwtAuthConfig
import ai.starlake.quack.edge.quack.{QuackFrontDoor, QuackSessionRegistry, QuackWire}
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.AuthzRequest
import ai.starlake.quack.ondemand.state.PoolPermission
import ai.starlake.quack.spi.ManagerEventSink
import at.favre.lib.crypto.bcrypt.BCrypt
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.tomakehurst.wiremock.client.WireMock
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.apache.arrow.flight.{
  FlightCallHeaders,
  FlightDescriptor,
  FlightRuntimeException,
  FlightStatusCode,
  HeaderCallOption
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The data plane's naming rule for an identity-provider profile, on the handshake every front door
  * shares ([[EdgeHandshake]]: the FlightSQL edge and the native Quack front door):
  *
  *   - a user name that came from the token's email claim (no `preferred_username`, the email
  *     equals the name) counts only when the IdP marks the address verified; otherwise the
  *     handshake is refused as unauthenticated, naming the cause, and nothing is bound: not the
  *     superuser row of that name (the seeded `admin@localhost.local`), not a tenant row;
  *   - a token that names no user is refused the same way, never bound to a row named `unknown` or
  *     to its subject behind an empty `preferred_username`;
  *   - a `preferred_username` and the database authenticator's verified row are unaffected.
  *
  * Driven with the real [[AuthenticationService]] over the in-memory database check, the real
  * password-grant and OIDC bearer authenticators against a stub IdP ([[MockOidcServer]]), a real
  * [[TenantOidcRegistry]], and the real supervisor gate (`authorizeHandshakeDetailed`) behind a
  * recorder: an empty recorder means no row was ever looked up for the identity.
  */
class DataPlaneIdentityNamingSpec extends AnyFlatSpec with Matchers:

  private val Acme      = SecurityFixtures.TenantId
  private val Pool      = SecurityFixtures.PoolName
  private val SeededSu  = "admin@localhost.local"
  private val AcmeEmail = "carol@acme.example"
  private val Realm     = "acme-realm"
  private val AcmeAud   = "acme-client"

  private val IvanPassword  = "ivan-idp-password"
  private val IvyPassword   = "ivy-idp-password"
  private val TinaPassword  = "tina-idp-password"
  private val TomPassword   = "tom-idp-password"
  private val EmailPassword = "email-user-idp-password"

  // The operator's own external JWT signer (HS256), tried before the OIDC bearer as in production.
  private val JwtSecret = "operator-jwt-secret-0123456789abcdef"

  private def hash(pw: String): String = BCrypt.withDefaults().hashToString(10, pw.toCharArray)

  /** The fixture's acme tenant, signing in through its own Keycloak realm on the stub IdP, plus:
    * the seeded superuser name, a superuser row named `unknown`, and an acme row named like an
    * email address with access to the pool.
    */
  private def seed(idp: MockOidcServer.Server): SecurityFixtures.Fixture =
    val fix = SecurityFixtures.freshStore()
    val s   = fix.store
    s.upsertTenant(
      Tenant(
        id = Acme,
        displayName = SecurityFixtures.TenantName,
        authProvider = "keycloak",
        authConfig = Map("baseUrl" -> idp.baseUrl, "realm" -> Realm, "clientId" -> AcmeAud)
      )
    )
    s.upsertUserWithHash(
      tenant = None,
      username = SeededSu,
      passwordHash = hash("x1"),
      kind = "admin"
    )
    s.upsertUserWithHash(
      tenant = None,
      username = "unknown",
      passwordHash = hash("x2"),
      kind = "admin"
    )
    val carolId = s.upsertUserWithHash(
      tenant = Some(Acme),
      username = AcmeEmail,
      passwordHash = hash("x3"),
      kind = "admin"
    )
    s.addUserRole(carolId, SecurityFixtures.AdminRoleId)
    s.insertPoolPermission(
      PoolPermission(
        id = "pp-carol0001",
        tenantId = Acme,
        poolId = Some(SecurityFixtures.PoolId),
        userId = Some(carolId)
      )
    )
    fix

  /** The stub IdP's password grant answers `username` / `password` with an access token holding
    * `claims` and the role claim `admin`; its tenant realm publishes the test signing key.
    */
  private def stubIdp(idp: MockOidcServer.Server): Unit =
    def account(username: String, password: String, claims: Map[String, Any]): Unit =
      val typed = URLEncoder.encode(username, StandardCharsets.UTF_8)
      val token = JwtTestSigner.mint(Map[String, Any]("role" -> "admin") ++ claims, idp.issuer)
      idp.wireMockServer.stubFor(
        WireMock
          .post(WireMock.urlPathEqualTo("/token"))
          .withRequestBody(WireMock.containing("grant_type=password"))
          .withRequestBody(WireMock.containing(s"username=$typed"))
          .withRequestBody(WireMock.containing(s"password=$password"))
          .willReturn(
            WireMock.okJson(s"""{"access_token":"$token","token_type":"Bearer","expires_in":300}""")
          )
      )
    // ivan and ivy: no preferred_username, named by the email claim, the seeded superuser's name;
    // ivy's IdP marks the address verified. tina and tom: the same shape naming the acme row.
    account("ivan", IvanPassword, Map("email" -> SeededSu))
    account("ivy", IvyPassword, Map("email" -> SeededSu, "email_verified" -> true))
    account("tina", TinaPassword, Map("email" -> AcmeEmail))
    account("tom", TomPassword, Map("email" -> AcmeEmail, "email_verified" -> true))
    // Keycloak's "email as username": preferred_username equals the email, no email_verified.
    account(
      AcmeEmail,
      EmailPassword,
      Map("preferred_username" -> AcmeEmail, "email" -> AcmeEmail)
    )
    idp.wireMockServer.stubFor(
      WireMock
        .get(WireMock.urlPathEqualTo(s"/realms/$Realm/protocol/openid-connect/certs"))
        .willReturn(WireMock.okJson(JwtTestSigner.jwksJson().noSpaces))
    )

  /** The production chain walk: the database check first, then the manager-wide Keycloak password
    * grant; the manager-wide Keycloak bearer; the tenant's own Keycloak bearer from the registry.
    */
  private def authService(
      fix: SecurityFixtures.Fixture,
      idp: MockOidcServer.Server
  ): AuthenticationService =
    InMemoryAuthService.realServiceWithChains(
      basic = List(
        new InMemoryAuthService.InMemoryBasicAuthProvider(fix.store),
        new ResourceOwnerPasswordAuthenticator(
          "keycloak",
          s"${idp.baseUrl}/token",
          "qod-console",
          "qod-console-secret",
          "role"
        )
      ),
      bearer = List(
        new JwtBearerAuthenticator(
          JwtAuthConfig(secretKey = JwtSecret, publicKeyPath = "", issuer = "", audience = ""),
          "role"
        ),
        new OidcBearerAuthenticator("keycloak", s"${idp.baseUrl}/jwks", idp.issuer, "", "role")
      ),
      tenantOidcRegistry = Some(
        new TenantOidcRegistry(id => fix.store.listTenants().find(_.id == id), "role")
      )
    )

  /** The handshake as Main wires it (FlightEdgeServer and the front door alike), its supervisor
    * gate recording every request it is asked.
    */
  private def handshakeOf(
      auth: AuthenticationService,
      sup: PoolSupervisor,
      asked: ConcurrentLinkedQueue[AuthzRequest],
      edge: String
  ): EdgeHandshake =
    new EdgeHandshake(
      auth,
      lookupPool = (t, p) =>
        sup.findPoolKeyByTenantAndPoolName(t, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
      resolveTenant = sup.getTenant,
      authorize = { r =>
        asked.add(r)
        sup.authorizeHandshakeDetailed(r)
      },
      versions = sup.policyVersions,
      edge = edge
    )

  private final case class World(
      fix: SecurityFixtures.Fixture,
      idp: MockOidcServer.Server,
      h: FlightEdgeHarness.Harness,
      auth: AuthenticationService,
      asked: ConcurrentLinkedQueue[AuthzRequest]
  ):
    val handshake: EdgeHandshake = handshakeOf(auth, h.supervisor, asked, "flightsql")

    def basic(user: String, password: String, superuser: Boolean) =
      handshake.authenticate(None, Some((user, password)), Some(Pool), Some(Acme), superuser)

    def bearer(token: String, superuser: Boolean) =
      handshake.authenticate(Some(token), None, Some(Pool), Some(Acme), superuser)

    /** A token from the manager-wide issuer. */
    def systemToken(claims: Map[String, Any]): String =
      JwtTestSigner.mint(Map[String, Any]("role" -> "admin") ++ claims, idp.issuer)

    /** A token from the operator's own external JWT signer. */
    def externalJwt(claims: Map[String, Any]): String =
      val b = new JWTClaimsSet.Builder().claim("role", "admin")
      claims.foreach((k, v) => b.claim(k, v))
      val jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), b.build())
      jwt.sign(new MACSigner(JwtSecret))
      jwt.serialize()

    /** A token from the acme tenant's own realm. */
    def tenantToken(claims: Map[String, Any]): String =
      JwtTestSigner.mint(
        Map[String, Any]("role" -> "admin") ++ claims,
        s"${idp.baseUrl}/realms/$Realm",
        audience = Some(AcmeAud)
      )

    def askedNames: List[String] = asked.asScala.toList.map(_.username)

  private def withWorld(body: World => Unit): Unit =
    val idp = MockOidcServer.boot()
    try
      stubIdp(idp)
      val fix  = seed(idp)
      val auth = authService(fix, idp)
      val h    = FlightEdgeHarness.boot(
        fix.store,
        authService = Some(auth),
        wiring = FlightEdgeHarness.RouterWiring(metadataFilterRewriter =
          new MetadataFilterRewriter(enabled = true)
        )
      )
      try body(World(fix, idp, h, auth, new ConcurrentLinkedQueue[AuthzRequest]()))
      finally h.shutdown()
    finally idp.shutdown()

  private def refusedForUnverifiedEmail(out: Either[HandshakeFailure, HandshakeBound]): Unit =
    out match
      case Left(HandshakeFailure.Unauthenticated(msg)) =>
        msg should include("did not verify the email address that names this account")
        msg should include("preferred_username")
      case other => fail(s"expected an unauthenticated refusal, got $other")

  private def refusedForNoName(out: Either[HandshakeFailure, HandshakeBound]): Unit =
    out match
      case Left(HandshakeFailure.Unauthenticated(msg)) => msg should include("names no user")
      case other => fail(s"expected an unauthenticated refusal, got $other")

  private def boundTo(
      out: Either[HandshakeFailure, HandshakeBound],
      user: String,
      tenant: Option[String]
  ): Unit =
    out match
      case Right(b) =>
        b.user shouldBe user
        val set = b.grants.current().fold(f => fail(s"no grants: $f"), identity)
        set.user.username shouldBe user
        set.user.tenant shouldBe tenant
      case Left(other) => fail(s"expected a bound handshake, got $other")

  // ---------------------------------------------------------------------------------------------
  // FlightSQL basic handshake answered by the manager-wide password grant
  // ---------------------------------------------------------------------------------------------

  "A basic handshake answered by the manager-wide password grant" should
    "refuse an account named by an email claim the IdP does not verify as unauthenticated, never binding the superuser row of that name" in
    withWorld { w =>
      refusedForUnverifiedEmail(w.basic("ivan", IvanPassword, superuser = true))
      w.askedNames shouldBe empty
    }

  it should "refuse it in a tenant scope too, binding no tenant row" in withWorld { w =>
    refusedForUnverifiedEmail(w.basic("tina", TinaPassword, superuser = false))
    w.askedNames shouldBe empty
  }

  it should "bind the superuser row an email claim names once the IdP marks the address verified" in
    withWorld { w =>
      boundTo(w.basic("ivy", IvyPassword, superuser = true), SeededSu, None)
    }

  it should "bind by preferred_username with no email_verified needed (Keycloak email as username)" in
    withWorld { w =>
      boundTo(w.basic(AcmeEmail, EmailPassword, superuser = false), AcmeEmail, Some(Acme))
    }

  it should "leave the database authenticator's verified row as it binds today" in withWorld { w =>
    boundTo(
      w.basic(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword, superuser = false),
      SecurityFixtures.AliceUsername,
      Some(Acme)
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Manager-wide bearer, system scope
  // ---------------------------------------------------------------------------------------------

  "A manager-wide bearer in the system scope" should
    "refuse a token named by an unverified email claim, never binding the superuser row of that name" in
    withWorld { w =>
      refusedForUnverifiedEmail(w.bearer(w.systemToken(Map("email" -> SeededSu)), superuser = true))
      w.askedNames shouldBe empty
    }

  it should "bind the superuser row once the IdP marks the address verified" in withWorld { w =>
    val token = w.systemToken(Map("email" -> SeededSu, "email_verified" -> true))
    boundTo(w.bearer(token, superuser = true), SeededSu, None)
  }

  it should "bind by preferred_username whatever the email claim holds" in withWorld { w =>
    val token =
      w.systemToken(Map("preferred_username" -> SeededSu, "email" -> "someone@else.example"))
    boundTo(w.bearer(token, superuser = true), SeededSu, None)
  }

  it should "refuse a token that names no user, never binding a row named unknown" in withWorld {
    w =>
      refusedForNoName(w.bearer(w.systemToken(Map.empty), superuser = true))
      w.askedNames shouldBe empty
  }

  it should "refuse a token whose preferred_username is empty, never naming it by its subject" in
    withWorld { w =>
      val token = w.systemToken(Map("preferred_username" -> "", "sub" -> SeededSu))
      refusedForNoName(w.bearer(token, superuser = true))
      w.askedNames shouldBe empty
    }

  it should "refuse a token whose preferred_username is only whitespace, never naming it by its subject" in
    withWorld { w =>
      val token = w.systemToken(Map("preferred_username" -> "   ", "sub" -> SeededSu))
      refusedForNoName(w.bearer(token, superuser = true))
      w.askedNames shouldBe empty
    }

  // ---------------------------------------------------------------------------------------------
  // External JWT bearer (the operator's own signer), data plane only
  // ---------------------------------------------------------------------------------------------

  "An external JWT bearer" should
    "refuse a token named by an email claim without email_verified, never binding the superuser row of that name" in
    withWorld { w =>
      refusedForUnverifiedEmail(w.bearer(w.externalJwt(Map("email" -> SeededSu)), superuser = true))
      w.askedNames shouldBe empty
    }

  it should "refuse a token that names no user, never binding a row named unknown" in
    withWorld { w =>
      refusedForNoName(w.bearer(w.externalJwt(Map.empty), superuser = true))
      w.askedNames shouldBe empty
    }

  it should "bind the superuser row once the token marks the address verified" in withWorld { w =>
    val out = w.bearer(
      w.externalJwt(Map("email" -> SeededSu, "email_verified" -> true)),
      superuser = true
    )
    boundTo(out, SeededSu, None)
    out.toOption.flatMap(_.profile).map(_.authMethod) shouldBe Some("jwt")
  }

  // ---------------------------------------------------------------------------------------------
  // Tenant-scoped bearer, validated by the tenant's own IdP (TenantOidcRegistry)
  // ---------------------------------------------------------------------------------------------

  "A tenant-scoped bearer validated by the tenant's own IdP" should
    "refuse a token named by an unverified email claim, binding no tenant row" in withWorld { w =>
      refusedForUnverifiedEmail(
        w.bearer(w.tenantToken(Map("email" -> AcmeEmail)), superuser = false)
      )
      w.askedNames shouldBe empty
    }

  it should "bind the tenant row once the IdP marks the address verified" in withWorld { w =>
    val token = w.tenantToken(Map("email" -> AcmeEmail, "email_verified" -> true))
    boundTo(w.bearer(token, superuser = false), AcmeEmail, Some(Acme))
  }

  it should "bind by preferred_username with no email_verified needed" in withWorld { w =>
    val token = w.tenantToken(Map("preferred_username" -> AcmeEmail, "email" -> AcmeEmail))
    boundTo(w.bearer(token, superuser = false), AcmeEmail, Some(Acme))
  }

  it should "refuse a token that names no user" in withWorld { w =>
    refusedForNoName(w.bearer(w.tenantToken(Map.empty), superuser = false))
    w.askedNames shouldBe empty
  }

  // ---------------------------------------------------------------------------------------------
  // Over the wires: the FlightSQL edge and the native Quack front door
  // ---------------------------------------------------------------------------------------------

  private def basicHeaders(user: String, password: String, superuser: Boolean): HeaderCallOption =
    val hdrs = new FlightCallHeaders()
    hdrs.insert("tenant", Acme)
    hdrs.insert("pool", Pool)
    if superuser then hdrs.insert("superuser", "true")
    val basic =
      Base64.getEncoder.encodeToString(s"$user:$password".getBytes(StandardCharsets.UTF_8))
    hdrs.insert("authorization", s"Basic $basic")
    new HeaderCallOption(hdrs)

  /** The status a getInfo answers with these credentials: the auth gate runs before the producer,
    * which refuses the raw bytes once the gate passed.
    */
  private def flightStatus(w: World, opt: HeaderCallOption): (FlightStatusCode, String) =
    val client = w.h.newClient()
    try
      client.getInfo(FlightDescriptor.command("SELECT 1".getBytes(StandardCharsets.UTF_8)), opt)
      (FlightStatusCode.OK, "")
    catch case e: FlightRuntimeException => (e.status().code(), e.status().description())
    finally client.close()

  "The FlightSQL edge" should
    "answer UNAUTHENTICATED naming the cause for an account named by an unverified email" in
    withWorld { w =>
      val (code, msg) = flightStatus(w, basicHeaders("ivan", IvanPassword, superuser = true))
      code shouldBe FlightStatusCode.UNAUTHENTICATED
      msg should include("did not verify the email address that names this account")
    }

  it should "pass the auth gate once the IdP marks the address verified" in withWorld { w =>
    val (code, msg) = flightStatus(w, basicHeaders("ivy", IvyPassword, superuser = true))
    withClue(msg) {
      code should not be FlightStatusCode.UNAUTHENTICATED
      code should not be FlightStatusCode.UNAUTHORIZED
    }
  }

  private def frontDoor(w: World): (QuackFrontDoor, QuackSessionRegistry) =
    val transport = new QuackTransport:
      def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] =
        IO.raiseError(new java.io.IOException("no node in this spec"))
    val sessions = new QuackSessionRegistry(sessionTtlSec = 3600, maxHeartbeatSec = 3600)
    val sink: ManagerEventSink = _ => ()
    val door                   = new QuackFrontDoor(
      w.h.router,
      handshakeOf(w.auth, w.h.supervisor, w.asked, "quack"),
      sessions,
      transport,
      sink,
      "test"
    )
    (door, sessions)

  private def connect(door: QuackFrontDoor, token: String): Array[Byte] =
    val hello = QuackWire.ConnectionRequest(token, "v2.0.0", "osx_arm64", 3L, 3L, "qod-test", 0L)
    door.handle(QuackWire.encodeConnectionRequest(hello, 1L)).unsafeRunSync()

  "The native Quack front door" should
    "refuse an account named by an unverified email with an error naming the cause, binding no session" in
    withWorld { w =>
      val (door, sessions) = frontDoor(w)
      val resp             =
        connect(door, s"tenant=$Acme&pool=$Pool&user=ivan&password=$IvanPassword&superuser=true")
      QuackWire.messageType(resp) shouldBe QuackWire.Type.ErrorResponse
      val msg = QuackWire.decodeErrorMessage(resp).toOption.get
      msg should startWith("Authentication failed")
      msg should include("did not verify the email address that names this account")
      sessions.all shouldBe empty
      w.askedNames shouldBe empty
    }

  it should "bind the session of an account whose address the IdP marks verified" in withWorld {
    w =>
      val (door, sessions) = frontDoor(w)
      val resp             =
        connect(door, s"tenant=$Acme&pool=$Pool&user=ivy&password=$IvyPassword&superuser=true")
      QuackWire.messageType(resp) shouldBe QuackWire.Type.ConnectionResponse
      sessions.all.map(_.bound.user) shouldBe List(SeededSu)
  }
