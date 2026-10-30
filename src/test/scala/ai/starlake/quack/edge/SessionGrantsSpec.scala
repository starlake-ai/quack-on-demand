package ai.starlake.quack.edge

import ai.starlake.quack.edge.authz.PolicyClock
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.rbac.{
  AuthorizedHandshake,
  AuthzRequest,
  EffectiveSet,
  HandshakeDenial
}
import ai.starlake.quack.ondemand.state.RbacUser
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.concurrent.TrieMap

/** A data-plane session's grants follow its tenant's policy version (Task 11 addendum 1): resolved
  * again, through the handshake's own gates, on the first statement after the version moved, and
  * never otherwise; refused, never stale, when that resolution refuses or cannot run.
  */
class SessionGrantsSpec extends AnyFlatSpec with Matchers:

  private val pool  = PoolKey("acme", "acme_db", "bi")
  private val alice = RbacUser("u-1", Some("acme"), "alice", kind = "user")
  private val root  = RbacUser("u-0", None, "root", kind = "admin")

  private def set(user: RbacUser, tag: String): EffectiveSet =
    EffectiveSet(user, Nil, Nil, Nil, Nil, claims = Map("tag" -> tag))

  private def ok(s: EffectiveSet): Either[HandshakeDenial, AuthorizedHandshake] =
    Right(AuthorizedHandshake(pool, "acme", "p-1", s.user, s))

  // The wire spelling of the tenant: the versions are keyed by the canonical id the gates resolve.
  private val req = AuthzRequest("ACME", "bi", "alice", superuserAdmissible = false, edge = "test")

  private final class Versions extends PolicyClock:
    val of                              = TrieMap("acme" -> 1L)
    @volatile var gen                   = 0L
    def current(tenantId: String): Long = of.getOrElse(tenantId, 0L)
    def generation: Long                = gen
    def bump(): Unit                    = of.update("acme", current("acme") + 1)
    def advance(): Unit                 = gen += 1

  /** The gates: answers `next()` and counts its calls. */
  private final class Gates(var next: () => Either[HandshakeDenial, AuthorizedHandshake]):
    var calls                                                                = 0
    def apply(r: AuthzRequest): Either[HandshakeDenial, AuthorizedHandshake] =
      calls += 1
      r shouldBe req
      next()

  private def open(gates: Gates, versions: Versions): SessionGrants =
    SessionGrants.authorize(req, gates.apply, versions) match
      case Right((_, grants)) => grants
      case Left(d)            => fail(s"handshake refused: $d")

  "a session's grants" should "be the handshake's while the version has not moved" in {
    val v     = new Versions
    val gates = new Gates(() => ok(set(alice, "h")))
    val g     = open(gates, v)
    gates.calls shouldBe 1
    (1 to 3).foreach(_ => g.current().map(_.claims("tag")) shouldBe Right("h"))
    g.currentIO.unsafeRunSync().map(_.claims("tag")) shouldBe Right("h")
    gates.calls shouldBe 1
  }

  it should "be resolved again, once, after the version moved" in {
    val v     = new Versions
    val gates = new Gates(() => ok(set(alice, "h")))
    val g     = open(gates, v)
    gates.next = () => ok(set(alice, "r"))
    v.bump()
    g.current().map(_.claims("tag")) shouldBe Right("r")
    g.currentIO.unsafeRunSync().map(_.claims("tag")) shouldBe Right("r")
    gates.calls shouldBe 2
  }

  it should "be resolved again, once, after the generation moved while the version held" in {
    // A local change whose version read failed: only the generation moved.
    val v     = new Versions
    val gates = new Gates(() => ok(set(alice, "h")))
    val g     = open(gates, v)
    (1 to 3).foreach(_ => g.current().map(_.claims("tag")) shouldBe Right("h"))
    gates.calls shouldBe 1
    gates.next = () => ok(set(alice, "r"))
    v.advance()
    // One resolution per move, not one per statement.
    (1 to 3).foreach(_ => g.current().map(_.claims("tag")) shouldBe Right("r"))
    g.currentIO.unsafeRunSync().map(_.claims("tag")) shouldBe Right("r")
    gates.calls shouldBe 2
  }

  it should "read the generation before resolving, so a move during the resolution resolves again" in {
    val v     = new Versions
    val gates = new Gates(() => ok(set(alice, "h")))
    val g     = open(gates, v)
    gates.next = () =>
      v.advance()
      ok(set(alice, "before"))
    v.advance()
    g.current().map(_.claims("tag")) shouldBe Right("before")
    gates.next = () => ok(set(alice, "after"))
    g.current().map(_.claims("tag")) shouldBe Right("after")
    gates.calls shouldBe 3
  }

  it should "read the version before resolving, so a bump during the resolution resolves again" in {
    val v     = new Versions
    val gates = new Gates(() => ok(set(alice, "h")))
    val g     = open(gates, v)
    // The change lands while the gates read the grants: they return what they read before it.
    gates.next = () =>
      v.bump()
      ok(set(alice, "before"))
    v.bump()
    g.current().map(_.claims("tag")) shouldBe Right("before")
    gates.next = () => ok(set(alice, "after"))
    g.current().map(_.claims("tag")) shouldBe Right("after")
    gates.calls shouldBe 3
  }

  it should "read the handshake's version before its resolution too" in {
    val v     = new Versions
    val gates = new Gates(() =>
      v.bump()
      ok(set(alice, "h"))
    )
    val g = open(gates, v)
    gates.next = () => ok(set(alice, "r"))
    g.current().map(_.claims("tag")) shouldBe Right("r")
  }

  it should "refuse a user now disabled, or without the pool, and keep refusing" in {
    val v     = new Versions
    val gates = new Gates(() => ok(set(alice, "h")))
    val g     = open(gates, v)
    gates.next = () => Left(HandshakeDenial.Denied("user 'alice' is disabled"))
    v.bump()
    g.current() shouldBe Left(
      RouterFailure.AccessDenied("permission denied: user 'alice' is disabled")
    )
    // No stale set behind the refusal: the next statement asks the gates again.
    gates.next = () => Left(HandshakeDenial.Denied("user 'alice' has no access to pool 'acme/bi'"))
    g.currentIO.unsafeRunSync() shouldBe Left(
      RouterFailure.AccessDenied("permission denied: user 'alice' has no access to pool 'acme/bi'")
    )
    gates.next = () => ok(set(alice, "back"))
    g.current().map(_.claims("tag")) shouldBe Right("back")
  }

  it should "refuse a user deleted and created again under the same name" in {
    val v     = new Versions
    val gates = new Gates(() => ok(set(alice, "h")))
    val g     = open(gates, v)
    gates.next = () => ok(set(alice.copy(id = "u-2"), "other"))
    v.bump()
    g.current() match
      case Left(RouterFailure.AccessDenied(m)) => m should include("no longer")
      case other                               => fail(s"expected a refusal, got $other")
  }

  it should "be retryable UNAVAILABLE when the gates cannot run, never the stale set" in {
    val v     = new Versions
    val gates = new Gates(() => ok(set(alice, "h")))
    val g     = open(gates, v)
    gates.next = () => throw new java.sql.SQLException("connection refused")
    v.bump()
    g.current() match
      case Left(RouterFailure.Unavailable(m)) => m should not include "connection refused"
      case other                              => fail(s"expected UNAVAILABLE, got $other")
    gates.next = () => Left(HandshakeDenial.Unavailable("OPA unreachable"))
    g.currentIO.unsafeRunSync() match
      case Left(RouterFailure.Unavailable(m)) => m should include("OPA unreachable")
      case other                              => fail(s"expected UNAVAILABLE, got $other")
  }

  "a superuser's or the system principal's grants" should "never be resolved again" in
    List(set(root, "su"), set(alice, "sys").copy(system = true)).foreach { s =>
      val v     = new Versions
      val gates = new Gates(() => ok(s))
      val g     = open(gates, v)
      v.bump()
      g.current().map(_.claims("tag")) shouldBe Right(s.claims("tag"))
      gates.calls shouldBe 1
    }

  "a refused handshake" should "give the gates' refusal" in {
    val gates = new Gates(() => Left(HandshakeDenial.Denied("nope")))
    SessionGrants.authorize(req, gates.apply, new Versions) shouldBe
      Left(HandshakeDenial.Denied("nope"))
  }
