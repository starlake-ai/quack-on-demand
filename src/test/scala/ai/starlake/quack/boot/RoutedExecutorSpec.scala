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
        "/tmp/qod-routed-executor-spec"
      )
      .unsafeRunSync()
    val key = PoolKey("acme", "acme_tpch1", "bi")
    sup.createPool(key, RoleDistribution(1, 0, 0)).unsafeRunSync()
    // A tenant-scoped admin whose user name happens to be the old sentinel; no pool grant.
    store.upsertUserIdentity(
      RbacUser(id = "u-sentinel", tenant = Some("acme"), username = "superuser", kind = "admin")
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
    val f   = new Fixture
    val out = f.exec(ExecCaller.unrestricted("preview-acme", "superuser"))
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
      Some("pat-1")
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
