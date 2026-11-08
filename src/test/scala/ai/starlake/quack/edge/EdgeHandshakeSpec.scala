package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.{
  AuthFailure,
  AuthScope,
  AuthenticationService,
  BasicAuthProvider,
  DatabaseAuthenticator,
  JwtBearerAuthenticator
}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.config.{AuthenticationConfig, JwtAuthConfig}
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.{
  AuthorizedHandshake,
  AuthzRequest,
  EffectiveSet,
  HandshakeDenial
}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{
  PoolPermission,
  RbacGroup,
  RbacRole,
  RbacUser,
  RolePermission
}
import ai.starlake.quack.security.{InMemoryAuthService, SecurityFixtures}
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.Date

/** The handshake shared by the FlightSQL edge and the Quack front door, exercised over a basic
  * chain that admits any password, so the gates around the auth chain are what is pinned; and with
  * no provider at all, which refuses every connection.
  */
class EdgeHandshakeSpec extends AnyFlatSpec with Matchers:

  private val anyPassword = InMemoryAuthService.acceptingAnyPassword()

  /** What the any-password chain answers for `user` in the tenant realm `tenant`. */
  private def profile(user: String, tenant: String) =
    DatabaseAuthenticator.profileFor(user, "user", Some(tenant))

  private val eff =
    EffectiveSet(RbacUser("u-1", Some("t-1"), "alice", kind = "user"), Nil, Nil, Nil, Nil)

  private def handshake(
      authorizeResult: Either[String, AuthorizedHandshake] = Right(
        AuthorizedHandshake(PoolKey("acme", "acme_db", "bi"), "t-1", "p-1", eff.user, eff)
      )
  ) =
    new EdgeHandshake(
      anyPassword,
      lookupPool = (t, p) =>
        if t == "acme" && p == "bi" then Right("acme_db") else Left(s"pool '$p' not found"),
      resolveTenant = raw =>
        if raw == "acme" then Some(Tenant("acme", "Acme Corporation")) else None,
      authorize = _ => authorizeResult.left.map(HandshakeDenial.Denied(_)),
      versions = ai.starlake.quack.edge.authz.PolicyClock.zero
    )

  "authenticate" should "bind a validated basic credential to its pool" in:
    val out = handshake().authenticate(None, Some(("alice", "pw")), Some("bi"), Some("acme"), false)
    out.map(b => (b.poolKey, b.user, b.grants.current(), b.profile)) shouldBe Right(
      (PoolKey("acme", "acme_db", "bi"), "alice", Right(eff), Some(profile("alice", "acme")))
    )

  /** A handshake whose password chain answers `failure`, as a database that cannot check would. */
  private def refusing(failure: AuthFailure) =
    val chain = new BasicAuthProvider:
      val name                                                     = "stub"
      def authenticate(scope: AuthScope, user: String, pw: String) = Left(failure)
    new EdgeHandshake(
      InMemoryAuthService.realServiceWithBasicChain(List(chain)),
      lookupPool = (_, _) => Right("acme_db"),
      resolveTenant = _ => Some(Tenant("acme", "Acme Corporation")),
      authorize = _ => Left(HandshakeDenial.Denied("not reached")),
      versions = ai.starlake.quack.edge.authz.PolicyClock.zero
    )

  it should "call a password that could not be checked unavailable, and a wrong one unauthenticated" in:
    def attempt(failure: AuthFailure) =
      refusing(failure).authenticate(None, Some(("alice", "pw")), Some("bi"), Some("acme"), false)
    attempt(AuthFailure.Unavailable) shouldBe Left(
      HandshakeFailure.Unavailable(AuthFailure.Unavailable.message)
    )
    attempt(AuthFailure.InvalidCredentials("Invalid password")) shouldBe Left(
      HandshakeFailure.Unauthenticated("Authentication failed: stub: Invalid password")
    )

  it should "route by the tenant id, never the display name" in:
    // Pools are keyed by tenant id: the display name must not replace the wire tenant.
    val out =
      handshake().authenticate(
        None,
        Some(("alice", "pw")),
        Some("bi"),
        Some("Acme Corporation"),
        false
      )
    out.isLeft shouldBe true

  it should "refuse a handshake without a tenant" in:
    val out = handshake().authenticate(None, Some(("alice", "pw")), Some("bi"), None, false)
    out match
      case Left(HandshakeFailure.Unauthenticated(msg)) =>
        msg should include("'tenant' header required")
      case other => fail(s"expected Unauthenticated, got $other")

  it should "refuse an unknown pool" in:
    val out =
      handshake().authenticate(None, Some(("alice", "pw")), Some("nope"), Some("acme"), false)
    out match
      case Left(HandshakeFailure.Unauthenticated(msg)) => msg should include("not found")
      case other => fail(s"expected Unauthenticated, got $other")

  it should "surface an authorize failure as Unauthorized" in:
    val out = handshake(Left("no grant on pool")).authenticate(
      None,
      Some(("alice", "pw")),
      Some("bi"),
      Some("acme"),
      false
    )
    out shouldBe Left(HandshakeFailure.Unauthorized("permission denied: no grant on pool"))

  it should "surface an OPA outage as Unavailable and pass the edge name to authorize" in:
    var seen: Option[AuthzRequest] = None
    val hs                         = new EdgeHandshake(
      anyPassword,
      lookupPool = (_, _) => Right("acme_db"),
      resolveTenant = _ => Some(Tenant("acme")),
      authorize = { r =>
        seen = Some(r)
        Left(HandshakeDenial.Unavailable("connection refused"))
      },
      versions = ai.starlake.quack.edge.authz.PolicyClock.zero,
      edge = "quack"
    )
    hs.authenticate(None, Some(("alice", "pw")), Some("bi"), Some("acme"), false) shouldBe
      Left(HandshakeFailure.Unavailable("authorization service unavailable: connection refused"))
    seen.map(_.edge) shouldBe Some("quack")

  it should "refuse a bearer no provider accepts" in:
    val out = handshake().authenticate(Some("stale-peer-id"), None, Some("bi"), Some("acme"), false)
    out match
      case Left(HandshakeFailure.Unauthenticated(msg)) => msg should include("No bearer auth")
      case other => fail(s"expected Unauthenticated, got $other")

  it should "refuse, never throw, a three-part bearer whose body is not base64" in:
    // The pre-resolution decodes the body before any provider validates the token: a malformed
    // one from an unauthenticated client must be an ordinary refusal on both doors.
    val out =
      handshake().authenticate(Some("a.!!not-base64!!.c"), None, Some("bi"), Some("acme"), false)
    out match
      case Left(HandshakeFailure.Unauthenticated(_)) => succeed
      case other => fail(s"expected Unauthenticated, got $other")

  "authenticate with no provider configured" should "refuse every connection, superusers included" in:
    val open = new EdgeHandshake(
      new AuthenticationService(AuthenticationConfig.disabled, "x"),
      lookupPool = (_, _) => Right("acme_db"),
      resolveTenant = _ => Some(Tenant("acme")),
      authorize = _ =>
        Right(AuthorizedHandshake(PoolKey("acme", "acme_db", "bi"), "t-1", "p-1", eff.user, eff)),
      versions = ai.starlake.quack.edge.authz.PolicyClock.zero
    )
    for (bearer, basic, superuser) <- List(
        (None, Some(("alice", "pw")), false),
        (None, Some(("admin", "")), true),
        (Some("an-unknown-bearer"), None, false),
        (None, None, false)
      )
    do
      open.authenticate(bearer, basic, Some("bi"), Some("acme"), superuser) match
        case Left(HandshakeFailure.Unauthenticated(msg)) =>
          msg should include("no authenticator is configured")
        case other => fail(s"expected Unauthenticated, got $other")

  "authenticate" should "refuse a handshake with no credential at all" in:
    val out = handshake().authenticate(None, None, Some("bi"), Some("acme"), false)
    out.isLeft shouldBe true
    out.swap.toOption.get.message should include("no credentials presented")

  // An account's kind (admin / user) is no tenant role or group name: a tenant may hold a role and
  // a group literally named `user`, and a password login of kind `user` must not hold them. Only
  // the names an identity provider's token carries are resolved as roles and groups.

  /** The canonical fixture plus a role `user` granting one table and a group `user` granting the
    * pool, neither held by bob (kind `user`).
    */
  private def kindNamedRbac(extra: SecurityFixtures.Fixture => Unit = _ => ()) =
    val fix = SecurityFixtures.freshStore()
    val s   = fix.store
    s.upsertRole(RbacRole(id = "r-user0001", tenantId = SecurityFixtures.TenantId, name = "user"))
    s.insertRolePermission(
      RolePermission(
        id = "rp-user0001",
        roleId = "r-user0001",
        catalogName = SecurityFixtures.TenantDbName,
        schemaName = "main",
        tableName = "orders",
        verb = "RO"
      )
    )
    s.upsertGroup(RbacGroup(id = "g-user0001", tenantId = SecurityFixtures.TenantId, name = "user"))
    s.insertPoolPermission(
      PoolPermission(
        id = "pp-guser001",
        tenantId = SecurityFixtures.TenantId,
        poolId = Some(SecurityFixtures.PoolId),
        groupId = Some("g-user0001")
      )
    )
    extra(fix)
    val sup = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, s)
    sup.restore()
    (fix, sup)

  /** The effective set a handshake bound, failing the test when it bound none. */
  private def effOf(out: Either[HandshakeFailure, HandshakeBound]): EffectiveSet =
    out match
      case Right(b) => b.grants.current().fold(e => fail(s"no grants: $e"), identity)
      case Left(f)  => fail(s"expected a bound session, got $f")

  private def overSupervisor(svc: AuthenticationService, sup: PoolSupervisor) =
    new EdgeHandshake(
      svc,
      lookupPool = (tn, p) =>
        sup.findPoolKeyByTenantAndPoolName(tn, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
      resolveTenant = raw => sup.getTenant(raw),
      authorize = sup.authorizeHandshakeDetailed,
      versions = sup.policyVersions
    )

  private val JwtSecret = "edge-handshake-spec-secret-0123456789"

  private def bearerFor(user: String, role: Option[String], groups: List[String]) =
    val now = Instant.now()
    val b   = new JWTClaimsSet.Builder()
      .subject(user)
      .claim("preferred_username", user)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(300)))
    role.foreach(r => b.claim("role", r))
    if groups.nonEmpty then b.claim("groups", java.util.Arrays.asList(groups*))
    val jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), b.build())
    jwt.sign(new MACSigner(JwtSecret))
    jwt.serialize()

  private def bearerService =
    InMemoryAuthService.realServiceWithChains(
      Nil,
      List(
        new JwtBearerAuthenticator(
          JwtAuthConfig(secretKey = JwtSecret, publicKeyPath = "", issuer = "", audience = ""),
          "role"
        )
      ),
      None
    )

  "a password login" should "not hold the pool of a group named after its account kind" in:
    val (fix, sup) = kindNamedRbac()
    val hs         =
      overSupervisor(new InMemoryAuthService.Service(fix.store, providersEnabled = true), sup)
    hs.authenticate(
      None,
      Some((SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)),
      Some(SecurityFixtures.PoolName),
      Some(SecurityFixtures.TenantId),
      false
    ) match
      case Left(HandshakeFailure.Unauthorized(_)) => succeed
      case other                                  => fail(s"expected the pool refused, got $other")

  it should "not hold the table grants of a role named after its account kind" in:
    // A pool grant of bob's own, so the handshake binds and the effective set can be read.
    val (fix, sup) = kindNamedRbac(f =>
      f.store.insertPoolPermission(
        PoolPermission(
          id = "pp-bob00001",
          tenantId = SecurityFixtures.TenantId,
          poolId = Some(SecurityFixtures.PoolId),
          userId = Some(f.bobUserId)
        )
      )
    )
    val hs =
      overSupervisor(new InMemoryAuthService.Service(fix.store, providersEnabled = true), sup)
    val eff = effOf(
      hs.authenticate(
        None,
        Some((SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)),
        Some(SecurityFixtures.PoolName),
        Some(SecurityFixtures.TenantId),
        false
      )
    )
    eff.roles.map(_.name) should not contain "user"
    eff.groups.map(_.name) should not contain "user"
    eff.permissions.map(_.tableName) should not contain "orders"

  it should "carry its account kind as its role and no token role or group name" in:
    val p = DatabaseAuthenticator.profileFor("bob", "user", Some(SecurityFixtures.TenantId))
    p.role shouldBe "user"
    p.tokenRoles shouldBe empty
    p.groups shouldBe empty

  "a bearer token" should "hold the role and group its claims name, even when named user" in:
    val (_, sup) = kindNamedRbac()
    val hs       = overSupervisor(bearerService, sup)
    val eff      = effOf(
      hs.authenticate(
        Some(bearerFor(SecurityFixtures.BobUsername, Some("user"), List("user"))),
        None,
        Some(SecurityFixtures.PoolName),
        Some(SecurityFixtures.TenantId),
        false
      )
    )
    eff.roles.map(_.name) should contain("user")
    eff.groups.map(_.name) should contain("user")
    eff.permissions.map(_.tableName) should contain("orders")

  it should "hold no role or group named user when its claims name none" in:
    val (_, sup) = kindNamedRbac()
    val hs       = overSupervisor(bearerService, sup)
    hs.authenticate(
      Some(bearerFor(SecurityFixtures.BobUsername, None, Nil)),
      None,
      Some(SecurityFixtures.PoolName),
      Some(SecurityFixtures.TenantId),
      false
    ) match
      case Left(HandshakeFailure.Unauthorized(_)) => succeed
      case other                                  => fail(s"expected the pool refused, got $other")

  // With JWT_SECRET_KEY equal to the session secret (the Starflow pairing), the edge's bearer
  // provider verifies a QoD management session, which Starflow presents as its FlightSQL bearer.
  // The session's account kind must not read as a role the token claims.

  private def sessionOf(user: String, kind: String) =
    new ai.starlake.quack.ondemand.api.SessionTokenStore(secret = JwtSecret).mintWithScope(
      DatabaseAuthenticator.profileFor(user, kind, Some(SecurityFixtures.TenantId)),
      ai.starlake.quack.ondemand.auth.SessionScope(superuser = false, Set.empty)
    )

  private def jwtProvider =
    new JwtBearerAuthenticator(
      JwtAuthConfig(secretKey = JwtSecret, publicKeyPath = "", issuer = "", audience = ""),
      "role"
    )

  "a management session presented as a bearer" should "claim no role or group" in:
    for kind <- List("user", "admin") do
      val p = jwtProvider.authenticate(sessionOf(SecurityFixtures.BobUsername, kind)).toOption.get
      p.username shouldBe SecurityFixtures.BobUsername
      p.tokenRoles shouldBe empty
      p.groups shouldBe empty

  it should "not hold the pool of a group named after its account kind" in:
    val (_, sup) = kindNamedRbac()
    overSupervisor(bearerService, sup).authenticate(
      Some(sessionOf(SecurityFixtures.BobUsername, "user")),
      None,
      Some(SecurityFixtures.PoolName),
      Some(SecurityFixtures.TenantId),
      false
    ) match
      case Left(HandshakeFailure.Unauthorized(_)) => succeed
      case other                                  => fail(s"expected the pool refused, got $other")

  it should "not hold the table grants of a role named after its account kind" in:
    val (fix, sup) = kindNamedRbac(f =>
      f.store.insertPoolPermission(
        PoolPermission(
          id = "pp-bob00001",
          tenantId = SecurityFixtures.TenantId,
          poolId = Some(SecurityFixtures.PoolId),
          userId = Some(f.bobUserId)
        )
      )
    )
    val eff = effOf(
      overSupervisor(bearerService, sup).authenticate(
        Some(sessionOf(SecurityFixtures.BobUsername, "user")),
        None,
        Some(SecurityFixtures.PoolName),
        Some(SecurityFixtures.TenantId),
        false
      )
    )
    eff.user.username shouldBe SecurityFixtures.BobUsername
    eff.roles.map(_.name) should not contain "user"
    eff.groups.map(_.name) should not contain "user"
    eff.permissions.map(_.tableName) should not contain "orders"

  "an external token whose role claim is user" should "still claim role user" in:
    val p = jwtProvider.authenticate(bearerFor("carol", Some("user"), Nil)).toOption.get
    p.tokenRoles shouldBe Set("user")
