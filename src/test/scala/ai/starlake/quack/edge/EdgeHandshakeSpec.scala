package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.{
  AuthenticatedProfile,
  AuthenticationService,
  DatabaseAuthenticator
}
import ai.starlake.quack.edge.config.AuthenticationConfig
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.rbac.{
  AuthorizedHandshake,
  AuthzRequest,
  EffectiveSet,
  HandshakeDenial
}
import ai.starlake.quack.ondemand.state.RbacUser
import ai.starlake.quack.security.InMemoryAuthService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The handshake shared by the FlightSQL edge and the Quack front door, exercised over a basic
  * chain that admits any password, so the gates around the auth chain are what is pinned; and with
  * no provider at all, which refuses every connection.
  */
class EdgeHandshakeSpec extends AnyFlatSpec with Matchers:

  private val anyPassword = InMemoryAuthService.acceptingAnyPassword()

  /** What the any-password chain answers for `user` in the tenant realm `tenant`. */
  private def profile(user: String, tenant: String) =
    AuthenticatedProfile(
      user,
      "user",
      Set.empty,
      Map.empty,
      DatabaseAuthenticator.AuthMethod,
      Some(tenant)
    )

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
      authorize = _ => authorizeResult.left.map(HandshakeDenial.Denied(_))
    )

  "authenticate" should "bind a validated basic credential to its pool" in:
    val out = handshake().authenticate(None, Some(("alice", "pw")), Some("bi"), Some("acme"), false)
    out shouldBe Right(
      HandshakeBound(PoolKey("acme", "acme_db", "bi"), "alice", eff, Some(profile("alice", "acme")))
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
        Right(AuthorizedHandshake(PoolKey("acme", "acme_db", "bi"), "t-1", "p-1", eff.user, eff))
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
