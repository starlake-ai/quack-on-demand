package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.spi.{MutationGate, QuotaExceededException, StructureMutation}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PoolSupervisorGateSpec extends AnyFlatSpec with Matchers:

  private def denyPools(reason: String) = new MutationGate:
    def check(m: StructureMutation): IO[Either[String, Unit]] = m match
      case _: StructureMutation.CreatePool => IO.pure(Left(reason))
      case _                               => IO.pure(Right(()))

  private val denyAll = new MutationGate:
    def check(m: StructureMutation): IO[Either[String, Unit]] = IO.pure(Left("denied"))

  private val throwing = new MutationGate:
    def check(m: StructureMutation): IO[Either[String, Unit]] =
      IO.raiseError(new RuntimeException("store down"))

  private def setup() =
    val sup = new PoolSupervisor(
      new StubQuackBackend(),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore()
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup

  private def dbName(sup: PoolSupervisor) =
    sup.listTenantDbsByTenant("acme").head.name

  "createPool" should "raise QuotaExceededException when a gate refuses" in {
    val sup = setup()
    sup.setMutationGates(List(denyPools("pool quota reached (2)")))
    val key = PoolKey("acme", dbName(sup), "p1")
    val ex  = intercept[QuotaExceededException] {
      sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    }
    ex.reason shouldBe "pool quota reached (2)"
    sup.get(key) shouldBe None
  }

  it should "proceed when gateBypass is set" in {
    val sup = setup()
    sup.setMutationGates(List(denyAll))
    val key = PoolKey("acme", dbName(sup), "p1")
    sup.createPool(key, RoleDistribution(0, 0, 1), gateBypass = true).unsafeRunSync()
    sup.get(key) should not be None
  }

  it should "refuse when a gate throws (fail closed)" in {
    val sup = setup()
    sup.setMutationGates(List(throwing))
    val key = PoolKey("acme", dbName(sup), "p1")
    intercept[QuotaExceededException] {
      sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    }.reason should include("store down")
  }

  "createTenantDb" should "return Left(QuotaExceeded) when a gate refuses" in {
    val sup = setup()
    sup.setMutationGates(List(denyAll))
    sup
      .createTenantDb("acme", "second", TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync() match
      case Left(SupervisorError.QuotaExceeded(msg)) => msg shouldBe "denied"
      case other                                    => fail(s"expected QuotaExceeded, got $other")
  }

  "scale" should "raise QuotaExceededException when a gate refuses" in {
    val sup = setup()
    val key = PoolKey("acme", dbName(sup), "p1")
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup.setMutationGates(List(denyAll))
    intercept[QuotaExceededException] {
      sup.scale(key, 2, RoleDistribution(0, 0, 2), force = false).unsafeRunSync()
    }
  }

  "no gates" should "leave all three paths unchanged" in {
    val sup = setup()
    val key = PoolKey("acme", dbName(sup), "p1")
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup.scale(key, 2, RoleDistribution(0, 0, 2), force = false).unsafeRunSync()
    sup
      .createTenantDb("acme", "second", TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
      .isRight shouldBe true
  }
