package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  Pool,
  PoolKey,
  RoleDistribution,
  Tenant,
  TenantAcl,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.rbac.*
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, PoolPermission, RbacUser}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Gate 4 of the handshake for `opa` tenants: pool access is the tenant's OPA decision, QoD pool
  * grants are ignored, and an OPA outage (or an unwired OPA) surfaces as `Unavailable`.
  */
class OpaHandshakeSpec extends AnyFlatSpec with Matchers:

  /** Tenant `acme` (opa mode) with pool `bi` and two users holding NO pool grant: `alice` (enabled)
    * and `dave` (disabled). Tenant `globex` (default qod mode) with pool `bi` and `bob`, granted.
    * Tenant `weird` carries an unknown mode (`"foo"`, neither `"qod"` nor `"opa"`) with user
    * `carol`, no grant: the fail-closed rule treats anything but exactly `qod` as opa, wired or
    * not. A superuser row `root` (tenant = None) exists for every tenant.
    */
  private def freshSup(): PoolSupervisor =
    val s                                            = new InMemoryControlPlaneStore()
    def seedTenant(id: String, acl: TenantAcl): Unit =
      s.upsertTenant(Tenant(id = id, displayName = id, acl = acl))
      s.upsertTenantDb(
        TenantDb(
          id = s"td-$id",
          tenantId = id,
          name = s"${id}_db",
          kind = TenantDbKind.InMemory,
          metastore = Map.empty,
          dataPath = ""
        )
      )
      s.upsertPool(
        Pool(
          id = s"p-$id",
          tenantId = id,
          tenantDbId = s"td-$id",
          name = "bi",
          size = 1,
          distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1),
          maxConcurrentPerNode = 0,
          disabled = false
        )
      )
    seedTenant("acme", TenantAcl(Some("opa"), Some("http://opa")))
    seedTenant("globex", TenantAcl())
    seedTenant("weird", TenantAcl(Some("foo")))
    s.upsertUserWithHash(Some("acme"), "alice", "x", "user")
    s.upsertUserWithHash(Some("acme"), "dave", "x", "user", enabled = false)
    s.upsertUserWithHash(Some("weird"), "carol", "x", "user")
    s.upsertUserWithHash(None, "root", "x", "admin")
    val bobId = s.upsertUserWithHash(Some("globex"), "bob", "x", "user")
    s.insertPoolPermission(
      PoolPermission(
        id = "pp-bob",
        tenantId = "globex",
        poolId = Some("p-globex"),
        userId = Some(bobId)
      )
    )
    val sup = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, s)
    sup.restore()
    sup

  private val sup = freshSup()

  private def req(tenant: String, user: String): AuthzRequest =
    AuthzRequest(
      tenant,
      "bi",
      user,
      jwtClaims = Map("dept" -> "fin"),
      superuserAdmissible = true,
      edge = "flightsql"
    )

  private final class StubAccess(answer: Either[HandshakeDenial, Unit]) extends OpaPoolAccess:
    var seen: List[(String, Map[String, String], String)] = Nil
    def isOpa(t: Tenant): Boolean                         = t.acl.mode.contains("opa")
    def connect(
        t: Tenant,
        k: PoolKey,
        parents: List[String],
        u: RbacUser,
        e: EffectiveSet,
        edge: String
    ): Either[HandshakeDenial, Unit] =
      seen ::= ((u.username, e.claims, edge)); answer

  "authorizeHandshakeDetailed" should "admit an opa tenant user with no grants when OPA allows" in:
    val stub = StubAccess(Right(()))
    sup.wireOpa(stub)
    val r = sup.authorizeHandshakeDetailed(req("acme", "alice"))
    r.isRight shouldBe true
    r.toOption.get.effectiveSet.claims shouldBe Map("dept" -> "fin")
    stub.seen shouldBe List(("alice", Map("dept" -> "fin"), "flightsql"))

  it should "deny when OPA denies and surface Unavailable on OPA error" in:
    sup.wireOpa(StubAccess(Left(HandshakeDenial.Denied("nope"))))
    sup.authorizeHandshakeDetailed(req("acme", "alice")) match
      case Left(HandshakeDenial.Denied(m)) => m shouldBe "nope"
      case other                           => fail(other.toString)
    sup.wireOpa(StubAccess(Left(HandshakeDenial.Unavailable("down"))))
    sup.authorizeHandshakeDetailed(req("acme", "alice")) match
      case Left(HandshakeDenial.Unavailable(_)) => succeed
      case other                                => fail(other.toString)

  it should "refuse an opa tenant as Unavailable when OPA is not wired" in:
    freshSup().authorizeHandshakeDetailed(req("acme", "alice")) match
      case Left(HandshakeDenial.Unavailable(m)) => m should include("not wired")
      case other                                => fail(other.toString)

  it should "refuse an unwired tenant with an unknown mode as Unavailable (fail closed)" in:
    // "foo" is neither "qod" nor "opa": the unwired fallback cannot ask a manager default (it has
    // no access to one, see PoolSupervisor's gate-4 comment) so it treats anything but exactly
    // "qod" as opa, same as the wired isOpa check would.
    freshSup().authorizeHandshakeDetailed(req("weird", "carol")) match
      case Left(HandshakeDenial.Unavailable(m)) => m should include("not wired")
      case other                                => fail(other.toString)

  it should "admit a superuser on an opa tenant without ever calling OPA" in:
    val stub = StubAccess(Left(HandshakeDenial.Denied("must not be called")))
    sup.wireOpa(stub)
    sup.authorizeHandshakeDetailed(req("acme", "root")).isRight shouldBe true
    stub.seen shouldBe Nil

  it should "keep grant-based access for qod tenants and never call OPA" in:
    val stub = StubAccess(Left(HandshakeDenial.Denied("must not be called")))
    sup.wireOpa(stub)
    sup.authorizeHandshakeDetailed(req("globex", "bob")).isRight shouldBe true
    stub.seen shouldBe Nil

  it should "still refuse a disabled user before OPA" in:
    val stub = StubAccess(Right(()))
    sup.wireOpa(stub)
    sup.authorizeHandshakeDetailed(req("acme", "dave")) match
      case Left(HandshakeDenial.Denied(m)) => m should include("disabled")
      case other                           => fail(other.toString)
    stub.seen shouldBe Nil

  "authorizeHandshake" should "keep its string-left contract through the delegate" in:
    sup.wireOpa(StubAccess(Left(HandshakeDenial.Denied("nope"))))
    sup.authorizeHandshake("acme", "bi", "alice", superuserAdmissible = true) shouldBe Left("nope")
