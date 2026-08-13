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
    sup.setMutationGates(List(denyPools("pool quota reached (2)"))).unsafeRunSync()
    val key = PoolKey("acme", dbName(sup), "p1")
    val ex  = intercept[QuotaExceededException] {
      sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    }
    ex.reason shouldBe "pool quota reached (2)"
    sup.get(key) shouldBe None
  }

  it should "proceed when gateBypass is set" in {
    val sup = setup()
    sup.setMutationGates(List(denyAll)).unsafeRunSync()
    val key = PoolKey("acme", dbName(sup), "p1")
    sup.createPool(key, RoleDistribution(0, 0, 1), gateBypass = true).unsafeRunSync()
    sup.get(key) should not be None
  }

  it should "refuse when a gate throws (fail closed)" in {
    val sup = setup()
    sup.setMutationGates(List(throwing)).unsafeRunSync()
    val key = PoolKey("acme", dbName(sup), "p1")
    intercept[QuotaExceededException] {
      sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    }.reason should include("store down")
  }

  "createTenantDb" should "return Left(QuotaExceeded) when a gate refuses" in {
    val sup = setup()
    sup.setMutationGates(List(denyAll)).unsafeRunSync()
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
    sup.setMutationGates(List(denyAll)).unsafeRunSync()
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

  "createPool" should "carry the requested cpu/memory in the gate mutation" in {
    val sup               = setup()
    val seen              = new java.util.concurrent.atomic.AtomicReference[StructureMutation]()
    val spy: MutationGate = m => IO { seen.set(m); Right(()) }
    sup.setMutationGates(List(spy)).unsafeRunSync()
    val key = PoolKey("acme", dbName(sup), "bi")
    sup.createPool(key, RoleDistribution(0, 1, 0), cpu = "2", memory = "8Gi").unsafeRunSync()
    seen.get() match
      case StructureMutation.CreatePool(_, _, 1, cpu, memory) =>
        cpu shouldBe "2"; memory shouldBe "8Gi"
      case other => fail(s"expected CreatePool with shape, got $other")
  }

  "scale" should "carry the pool's current declared shape in ResizePool" in {
    val sup = setup()
    val key = PoolKey("acme", dbName(sup), "bi")
    sup.createPool(key, RoleDistribution(0, 1, 0), cpu = "1", memory = "4Gi").unsafeRunSync()
    val seen              = new java.util.concurrent.atomic.AtomicReference[StructureMutation]()
    val spy: MutationGate = m => IO { seen.set(m); Right(()) }
    sup.setMutationGates(List(spy)).unsafeRunSync()
    sup.scale(key, 2, RoleDistribution(0, 2, 0), force = false).unsafeRunSync()
    seen.get() match
      case StructureMutation.ResizePool(_, _, _, 1, 2, cpu, memory) =>
        cpu shouldBe "1"; memory shouldBe "4Gi"
      case other => fail(s"expected ResizePool with shape, got $other")
  }

  "setPoolResources" should "be gated and refuse with QuotaExceeded" in {
    val sup = setup()
    val key = PoolKey("acme", dbName(sup), "bi")
    sup.createPool(key, RoleDistribution(0, 1, 0)).unsafeRunSync()
    val deny: MutationGate = {
      case _: StructureMutation.SetPoolResources => IO.pure(Left("denied"))
      case _                                     => IO.pure(Right(()))
    }
    sup.setMutationGates(List(deny)).unsafeRunSync()
    sup.setPoolResources(key, "4", "16Gi").unsafeRunSync() match
      case Left(SupervisorError.QuotaExceeded(msg)) => msg shouldBe "denied"
      case other                                    => fail(s"expected QuotaExceeded, got $other")
    // gateBypass short-circuits
    sup.setPoolResources(key, "4", "16Gi", gateBypass = true).unsafeRunSync().isRight shouldBe true
  }

  it should "carry nodes and from/to shapes in the mutation" in {
    val sup = setup()
    val key = PoolKey("acme", dbName(sup), "bi")
    sup.createPool(key, RoleDistribution(0, 2, 0), cpu = "1", memory = "4Gi").unsafeRunSync()
    val seen              = new java.util.concurrent.atomic.AtomicReference[StructureMutation]()
    val spy: MutationGate = m => IO { seen.set(m); Right(()) }
    sup.setMutationGates(List(spy)).unsafeRunSync()
    sup.setPoolResources(key, "2", "8Gi").unsafeRunSync()
    seen.get() match
      case StructureMutation.SetPoolResources(_, _, _, 2, "1", "4Gi", "2", "8Gi") => succeed
      case other => fail(s"expected SetPoolResources with from/to shapes, got $other")
  }
