package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, RunningNode}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant
import scala.collection.concurrent.TrieMap

class HealthHandlerSpec extends AnyFlatSpec with Matchers:

  private final class StubBackend extends QuackBackend:
    private val nodes                          = TrieMap.empty[String, RunningNode]
    def start(spec: NodeSpec): IO[RunningNode] = IO {
      val n = RunningNode(
        spec.nodeId,
        spec.poolKey,
        spec.role,
        "127.0.0.1",
        21000 + nodes.size,
        "tok-" + spec.nodeId,
        Some(1L),
        None,
        Instant.EPOCH,
        maxConcurrent = spec.maxConcurrent
      )
      nodes.put(spec.nodeId, n); n
    }
    def stop(id: String): IO[Unit]                = IO { nodes.remove(id); () }
    def isAlive(id: String): Boolean              = nodes.contains(id)
    def discoverExisting(): IO[List[RunningNode]] = IO.pure(nodes.values.toList)
    def cleanup(): IO[Unit]                       = IO(nodes.clear())

  private def sup(): PoolSupervisor =
    new PoolSupervisor(new StubBackend, new NodeLoadTracker, new InMemoryControlPlaneStore())

  "HealthHandler" should "return ok when the database is reachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => true)
    h.health.unsafeRunSync().isRight shouldBe true
  }

  it should "return 503 when the database is unreachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => false)
    h.health.unsafeRunSync() shouldBe Left(StatusCode.ServiceUnavailable)
  }
