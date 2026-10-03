package ai.starlake.quack.boot

import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.ExecCaller
import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.route.StatementClassifier
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Privilege on the routed executor comes from a typed flag set only at trusted internal sites,
  * never from the caller's user name. Regression: user names are not reserved, so a tenant user
  * named "superuser" matched the old `identity == "superuser"` test and ran with the synthetic
  * superuser EffectiveSet: no ACL, no CLS/RLS, no protected-write guard.
  *
  * The caller's realm reaches the handshake gate as `AuthzRequest.superuserAdmissible`: a caller
  * that is not itself a superuser never binds a superuser row. `PoolSupervisor` is final, so the
  * request is observed through the real gate: the same identity over the same rows binds the
  * superuser row only when the caller's flag is true.
  */
class RoutedExecutorSpec extends AnyFlatSpec with Matchers:

  private final class Fixture:
    val store = new InMemoryControlPlaneStore()
    val sup   =
      new PoolSupervisor(StubQuackBackend.noop(countingPorts = true), new NodeLoadTracker, store)
    sup.createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db")).unsafeRunSync()
    sup
      .createTenantDb(
        "acme",
        "tpch1",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-routed-executor-spec",
        writerTrusted = true
      )
      .unsafeRunSync()
    val key = PoolKey("acme", "acme_tpch1", "bi")
    sup.createPool(key, RoleDistribution(1, 0, 0)).unsafeRunSync()
    // A tenant-scoped admin whose user name happens to be the old sentinel; no pool grant.
    store.upsertUserIdentity(
      RbacUser(id = "u-sentinel", tenant = Some("acme"), username = "superuser", kind = "admin")
    )
    // A superuser row; acme holds no row of that name.
    store.upsertUserIdentity(
      RbacUser(id = "u-root", tenant = None, username = "root", kind = "admin")
    )

    var routed: List[(ExecCaller, Option[EffectiveSet])] = Nil
    val executor                                         = RoutedExecutor(
      sup,
      StatementClassifier.default,
      (caller, _, _, eff, _) =>
        IO {
          routed = routed :+ ((caller, eff))
          Left(RouterFailure.Unavailable("recorded"))
        }
    )(recordExecution = false)

    def exec(caller: ExecCaller): Either[RouterFailure, QueryResult] =
      executor(caller, key, "SELECT * FROM main.region").unsafeRunSync()

  "the routed executor" should "evaluate a session of a tenant user named 'superuser' as that user" in {
    val f = new Fixture
    // A tenant session is never superuser-admissible: its scope is not a superuser's.
    val out =
      f.exec(ExecCaller.unrestricted("preview-acme", "superuser", superuserAdmissible = false))
    // Resolved through authorizeHandshake: the user has no grant on the pool, so it is denied
    // before the router, instead of running with the synthetic superuser set.
    out.left.toOption.collect { case RouterFailure.AccessDenied(r) => r }.getOrElse("") should
      include("no access to pool")
    f.routed shouldBe Nil
  }

  it should "evaluate a PAT whose owner is named 'superuser' as that user" in {
    val f   = new Fixture
    val pat = ExecCaller(
      "preview-acme",
      "superuser",
      TokenRestriction.Unrestricted.copy(maxRows = Some(5)),
      superuserAdmissible = false,
      patId = Some("pat-1")
    )
    f.exec(pat).left.toOption.map(_.getClass.getSimpleName) shouldBe Some("AccessDenied")
    f.routed shouldBe Nil
  }

  it should "give only a system caller the synthetic superuser set" in {
    val f = new Fixture
    f.exec(ExecCaller.system("restore-dryrun-acme"))
    f.routed.map((c, eff) => (c.system, eff.map(_.user.tenant))) shouldBe List((true, Some(None)))
    // Nodes run its statements as the system principal; without `system = true` the synthetic
    // set has id "" and every statement is refused at mint.
    f.routed.map((_, eff) => ai.starlake.quack.edge.authz.NodePrincipal.of(eff)) shouldBe
      List(ai.starlake.quack.edge.authz.NodePrincipal.System)
  }

  it should "bind no superuser row for a caller that is not superuser-admissible, and route nothing" in {
    val f   = new Fixture
    val out = f.exec(ExecCaller.unrestricted("preview-acme", "root", superuserAdmissible = false))
    // The same answer as an unknown user, so the refusal is no existence oracle.
    out.left.toOption.collect { case RouterFailure.AccessDenied(r) => r }.getOrElse("") should
      include("user 'root' is not registered in tenant 'acme'")
    f.routed shouldBe Nil
  }

  it should "bind the superuser row a superuser-admissible caller names, as a non-system caller" in {
    val f = new Fixture
    f.exec(ExecCaller.unrestricted("preview-acme", "root", superuserAdmissible = true))
    f.routed.map((c, eff) => (c.system, eff.map(e => (e.user.id, e.user.tenant)))) shouldBe
      List((false, Some(("u-root", None))))
  }

  // ---- a verified bearer JWT's roles, groups and claims ----------------------------------------

  /** A tenant user with no grant of their own; pool access comes from a group named by the JWT. */
  private def withGroupGrant(f: Fixture): Unit =
    f.store.upsertUserIdentity(RbacUser(id = "u-dave", tenant = Some("acme"), "dave", "user"))
    val group = f.sup.createGroup("acme", "analysts").unsafeRunSync().toOption.get
    f.sup.grantPoolPermission("acme", None, None, Some(group.id)).unsafeRunSync()
    f.sup.createRole("acme", "reader").unsafeRunSync()

  it should "widen the handshake by the caller's JWT groups and roles, and carry its claims" in {
    val f = new Fixture
    withGroupGrant(f)
    val plain = ExecCaller.unrestricted("rest-oidc-u-dave", "dave")
    f.exec(plain).left.toOption.map(_.getClass.getSimpleName) shouldBe Some("AccessDenied")
    f.routed shouldBe Nil
    val jwt = plain.copy(
      jwtRoles = Set("reader"),
      jwtGroups = Set("analysts"),
      jwtClaims = Map("sub" -> "dave"),
      superuserAdmissible = false
    )
    f.exec(jwt)
    val eff = f.routed.map(_._2).headOption.flatten.getOrElse(fail("not routed"))
    eff.user.id shouldBe "u-dave"
    eff.groups.map(_.name) shouldBe List("analysts")
    eff.roles.map(_.name) shouldBe List("reader")
    eff.claims shouldBe Map("sub" -> "dave")
  }

  it should "refuse a tenant-less superuser row when the credential cannot speak for one" in {
    val f = new Fixture
    f.store.upsertUserIdentity(RbacUser(id = "u-root", tenant = None, "root", "admin"))
    val caller = ExecCaller.unrestricted("rest-oidc-u-root", "root")
    // Admissible (the default every older caller keeps): the superuser row is matched.
    f.exec(caller)
    f.routed.map(_._2.map(_.user.id)) shouldBe List(Some("u-root"))
    f.exec(caller.copy(superuserAdmissible = false))
      .left
      .toOption
      .map(
        _.getClass.getSimpleName
      ) shouldBe Some("AccessDenied")
    f.routed.size shouldBe 1
  }
