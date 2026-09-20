package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.config.AuthenticationConfig
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.rbac.{AuthorizedHandshake, EffectiveSet}
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The handshake shared by the FlightSQL edge and the Quack front door, exercised in
  * trust-the-client mode (no providers) so the gates around the auth chain are what is pinned.
  */
class EdgeHandshakeSpec extends AnyFlatSpec with Matchers:

  private val trustMode = new AuthenticationService(AuthenticationConfig.disabled, "x")

  private val eff =
    EffectiveSet(RbacUser("u-1", Some("t-1"), "alice", role = "user"), Nil, Nil, Nil, Nil)

  private def handshake(
      authorizeResult: Either[String, AuthorizedHandshake] = Right(
        AuthorizedHandshake(PoolKey("acme", "acme_db", "bi"), "t-1", "p-1", eff.user, eff)
      )
  ) =
    new EdgeHandshake(
      trustMode,
      lookupPool = (t, p) =>
        if t == "acme" && p == "bi" then Right("acme_db") else Left(s"pool '$p' not found"),
      resolveTenant = raw =>
        if raw == "acme" || raw == "t-0a1b2c3d" then Some(Tenant("t-0a1b2c3d", "acme")) else None,
      authorize = (_, _, _, _, _, _) => authorizeResult
    )

  "authenticate" should "bind a basic credential to its pool in trust mode" in:
    val out = handshake().authenticate(None, Some(("alice", "pw")), Some("bi"), Some("acme"), false)
    out shouldBe Right(HandshakeBound(PoolKey("acme", "acme_db", "bi"), "alice", eff, None))

  it should "accept the tenant id form and normalize it to the display name" in:
    val out =
      handshake().authenticate(None, Some(("alice", "pw")), Some("bi"), Some("t-0a1b2c3d"), false)
    out.toOption.get.poolKey.tenant shouldBe "acme"

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

  it should "tell a stale bearer apart from a missing credential" in:
    val out = handshake().authenticate(Some("stale-peer-id"), None, Some("bi"), Some("acme"), false)
    out match
      case Left(HandshakeFailure.Unauthenticated(msg)) => msg should include("session expired")
      case other => fail(s"expected Unauthenticated, got $other")

  it should "refuse a handshake with no credential at all" in:
    val out = handshake().authenticate(None, None, Some("bi"), Some("acme"), false)
    out.isLeft shouldBe true
    out.swap.toOption.get.message should include("no JWT or username")
