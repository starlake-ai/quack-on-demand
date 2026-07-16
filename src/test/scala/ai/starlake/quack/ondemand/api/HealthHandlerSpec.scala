package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class HealthHandlerSpec extends AnyFlatSpec with Matchers:

  private def sup(): PoolSupervisor =
    new PoolSupervisor(
      new StubQuackBackend(tokenFor = StubQuackBackend.PerNodeToken),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore()
    )

  "HealthHandler.health (liveness)" should "return ok when the database is reachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => true)
    h.health.unsafeRunSync().isRight shouldBe true
  }

  it should "return ok even when the database is unreachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => false)
    h.health.unsafeRunSync().isRight shouldBe true
  }

  "HealthHandler.ready (readiness)" should "return ok when the database is reachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => true)
    h.ready.unsafeRunSync().isRight shouldBe true
  }

  it should "return 503 when the database is unreachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => false)
    h.ready.unsafeRunSync() shouldBe Left(StatusCode.ServiceUnavailable)
  }
