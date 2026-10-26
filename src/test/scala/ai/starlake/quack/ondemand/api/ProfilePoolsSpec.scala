package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.StatementHistoryStore
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.model.{
  Pool,
  PoolKey,
  RoleDistribution,
  Tenant,
  TenantAcl,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.rbac.{EffectiveSet, HandshakeDenial, OpaPoolAccess}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, PoolPermission, RbacUser}
import ai.starlake.quack.ondemand.telemetry.NoopTelemetryStore
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** `GET /api/profile/pools`: the pools a caller may connect to, each decided by the FlightSQL
  * handshake's own decision for the request the routed executor builds, narrowed first by a PAT's
  * axes. A real supervisor over the in-memory store; no server, no Postgres.
  */
class ProfilePoolsSpec extends AnyFlatSpec with Matchers:

  import Dtos.given

  /** acme (qod mode): acme_sales {bi, etl, old (disabled)} and acme_hr {hr}. globex: globex_main
    * {gx}. initech (opa mode): initech_main {ix}. umbrella (disabled): umbrella_main {um}.
    *
    * carol@acme is granted bi, hr and old; ANOTHER carol, in globex, is granted gx. dave@initech
    * holds no grant (OPA decides for initech). erin@acme is disabled, granted bi. frank@umbrella is
    * granted um. root is the superuser.
    */
  private final class Fixture:
    val store = new InMemoryControlPlaneStore()

    private def tenant(id: String, acl: TenantAcl = TenantAcl(), disabled: Boolean = false) =
      store.upsertTenant(Tenant(id = id, displayName = id, disabled = disabled, acl = acl))

    private def db(tenantId: String, name: String) =
      store.upsertTenantDb(
        TenantDb(
          id = s"td-$name",
          tenantId = tenantId,
          name = name,
          kind = TenantDbKind.InMemory,
          metastore = Map.empty,
          dataPath = ""
        )
      )

    def pool(tenantId: String, dbName: String, name: String, disabled: Boolean = false) =
      store.upsertPool(
        Pool(
          id = s"p-$name",
          tenantId = tenantId,
          tenantDbId = s"td-$dbName",
          name = name,
          size = 1,
          distribution = RoleDistribution(0, 0, 1),
          disabled = disabled
        )
      )

    private def grant(tenantId: String, userId: String, pools: String*): Unit =
      pools.foreach(p =>
        store.insertPoolPermission(
          PoolPermission(
            id = s"pp-$userId-$p",
            tenantId = tenantId,
            poolId = Some(s"p-$p"),
            userId = Some(userId)
          )
        )
      )

    tenant("acme")
    tenant("globex")
    tenant("initech", acl = TenantAcl(Some("opa"), Some("http://opa")))
    tenant("umbrella", disabled = true)
    db("acme", "acme_sales")
    db("acme", "acme_hr")
    db("globex", "globex_main")
    db("initech", "initech_main")
    db("umbrella", "umbrella_main")
    pool("acme", "acme_sales", "bi")
    pool("acme", "acme_sales", "etl")
    pool("acme", "acme_sales", "old", disabled = true)
    pool("acme", "acme_hr", "hr")
    pool("globex", "globex_main", "gx")
    pool("initech", "initech_main", "ix")
    pool("umbrella", "umbrella_main", "um")

    store.upsertUserWithHash(None, "root", "x", "admin")
    grant("acme", store.upsertUserWithHash(Some("acme"), "carol", "x", "user"), "bi", "hr", "old")
    grant("globex", store.upsertUserWithHash(Some("globex"), "carol", "x", "user"), "gx")
    store.upsertUserWithHash(Some("initech"), "dave", "x", "user")
    grant(
      "acme",
      store.upsertUserWithHash(Some("acme"), "erin", "x", "user", enabled = false),
      "bi"
    )
    grant("umbrella", store.upsertUserWithHash(Some("umbrella"), "frank", "x", "user"), "um")

    val sup = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()

    val tokens = new SessionTokenStore()

    def session(user: String, tenant: Option[String], superuser: Boolean = false): Option[String] =
      Some(
        tokens.mintWithScope(
          AuthenticatedProfile(
            username = user,
            role = if superuser then "admin" else "user",
            groups = Set.empty,
            claims = Map.empty,
            authMethod = "db",
            tenant = tenant
          ),
          if superuser then SessionScope.Superuser else SessionScope.NoAccess
        )
      )

    private val carol = store.findUser(Some("acme"), "carol").get
    private val root  = store.findUser(None, "root").get

    private def pat(user: RbacUser, id: String, restriction: TokenRestriction): PatPrincipal =
      PatPrincipal(user, id, SessionScope.NoAccess, isAdmin = false, restriction)

    private val pats: Map[String, PatPrincipal] = Map(
      "qod_pat_carol_sales" -> pat(
        carol,
        "pat-1",
        TokenRestriction.Unrestricted.copy(databases = Some(Set("acme_sales")))
      ),
      "qod_pat_carol_hr" -> pat(
        carol,
        "pat-2",
        TokenRestriction.Unrestricted.copy(pools = Some(Set("hr")))
      ),
      "qod_pat_carol_none" -> pat(
        carol,
        "pat-3",
        TokenRestriction.Unrestricted
          .copy(databases = Some(Set("acme_sales")), pools = Some(Set("hr")))
      ),
      // A superuser's data-only token: dropAdmin takes its admin scope, not its row.
      "qod_pat_root_data" -> pat(
        root,
        "pat-4",
        TokenRestriction.Unrestricted.copy(dropAdmin = true)
      )
    )

    val handlers = new ProfileHandlers(
      tokens.get,
      NoopTelemetryStore,
      new StatementHistoryStore(),
      sup.getTenantById,
      sup,
      RestCaller(sessionOf = tokens.get, patOf = pats.get)
    )

    def list(token: Option[String]): Either[(StatusCode, ErrorResponse), List[String]] =
      handlers
        .pools(token)
        .unsafeRunSync()
        .map(_.pools.map(p => s"${p.tenant}/${p.tenantDb}/${p.pool}"))

  /** OPA stub for the opa-mode tenant; records the edge each decision was asked for. */
  private final class StubOpa(answer: Either[HandshakeDenial, Unit]) extends OpaPoolAccess:
    var edges: List[String]       = Nil
    def isOpa(t: Tenant): Boolean = t.acl.mode.contains("opa")
    def connect(
        t: Tenant,
        k: PoolKey,
        parents: List[String],
        u: RbacUser,
        e: EffectiveSet,
        edge: String
    ): Either[HandshakeDenial, Unit] =
      edges = edges :+ edge
      answer

  "GET /api/profile/pools" should "list exactly the pools a user is granted, in their own tenant only" in {
    val f = new Fixture
    // Not etl (no grant), not old (granted, but disabled), and not globex/gx: the carol granted
    // there is another user of the same name, and globex is not this caller's tenant.
    f.list(f.session("carol", Some("acme"))) shouldBe
      Right(List("acme/acme_hr/hr", "acme/acme_sales/bi"))
  }

  it should "narrow a PAT by its databases and pools axes" in {
    val f = new Fixture
    f.list(Some("qod_pat_carol_sales")) shouldBe Right(List("acme/acme_sales/bi"))
    f.list(Some("qod_pat_carol_hr")) shouldBe Right(List("acme/acme_hr/hr"))
    f.list(Some("qod_pat_carol_none")) shouldBe Right(Nil)
  }

  it should "follow the tenant's OPA in opa mode, asked as the routed executor asks" in {
    val f     = new Fixture
    val allow = StubOpa(Right(()))
    f.sup.wireOpa(allow)
    f.list(f.session("dave", Some("initech"))) shouldBe Right(List("initech/initech_main/ix"))
    allow.edges shouldBe List("mcp")
    f.sup.wireOpa(StubOpa(Left(HandshakeDenial.Denied("policy says no"))))
    f.list(f.session("dave", Some("initech"))) shouldBe Right(Nil)
  }

  it should "answer 503 with the handshake's message when the tenant's OPA is unreachable" in {
    val f = new Fixture
    f.sup.wireOpa(StubOpa(Left(HandshakeDenial.Unavailable("opa at http://opa: refused"))))
    f.handlers
      .pools(f.session("dave", Some("initech")))
      .unsafeRunSync()
      .left
      .toOption
      .map((status, err) => (status, err.error, err.message)) shouldBe
      Some((StatusCode.ServiceUnavailable, "authz_unavailable", "opa at http://opa: refused"))
  }

  it should "stop asking OPA at the first unavailable decision" in {
    val f = new Fixture
    f.pool("initech", "initech_main", "iy")
    f.pool("initech", "initech_main", "iz")
    f.sup.restore()
    val down = StubOpa(Left(HandshakeDenial.Unavailable("opa at http://opa: refused")))
    f.sup.wireOpa(down)
    f.list(f.session("dave", Some("initech"))).left.map(_._1) shouldBe
      Left(StatusCode.ServiceUnavailable)
    down.edges should have size 1
  }

  it should "list every pool of every enabled tenant for a superuser session or data-only PAT" in {
    val f   = new Fixture
    val all = Right(
      List(
        "acme/acme_hr/hr",
        "acme/acme_sales/bi",
        "acme/acme_sales/etl",
        "globex/globex_main/gx",
        "initech/initech_main/ix"
      )
    )
    f.list(f.session("root", None, superuser = true)) shouldBe all
    // The handshake admits the superuser row on every pool, so a data-only token of it is not
    // tenant-bound either.
    f.list(Some("qod_pat_root_data")) shouldBe all
  }

  it should "leave out a disabled pool and every pool of a disabled tenant" in {
    val f = new Fixture
    f.list(f.session("root", None, superuser = true))
      .map(_.filter(p => p.endsWith("/old") || p.startsWith("umbrella/"))) shouldBe Right(Nil)
    f.list(f.session("frank", Some("umbrella"))) shouldBe Right(Nil)
  }

  it should "list nothing for a disabled user" in {
    val f = new Fixture
    f.list(f.session("erin", Some("acme"))) shouldBe Right(Nil)
  }

  it should "send exactly tenant, tenantDb, pool and the pool/list status for each pool" in {
    val f    = new Fixture
    val resp = f.handlers
      .pools(f.session("carol", Some("acme")))
      .unsafeRunSync()
      .getOrElse(fail("expected a pool listing"))
    resp.asJson.noSpaces shouldBe
      """{"pools":[{"tenant":"acme","tenantDb":"acme_hr","pool":"hr","status":"ready"},""" +
      """{"tenant":"acme","tenantDb":"acme_sales","pool":"bi","status":"ready"}]}"""
  }

  it should "refuse a caller it cannot identify, like the other profile routes" in {
    val f                              = new Fixture
    def refusal(token: Option[String]) =
      f.handlers.pools(token).unsafeRunSync().left.toOption.map((s, e) => (s.code, e.error))
    refusal(None) shouldBe Some((400, "no_session_identity"))
    refusal(Some("qod_pat_unknown")) shouldBe Some((400, "no_session_identity"))
  }
