package ai.starlake.quack.observability.metrics

import ai.starlake.quack.edge.SessionRegistry
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, Role, RoleDistribution, RunningNode}
import ai.starlake.quack.ondemand.PoolState
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.server.http4s.Http4sServerInterpreter
import java.time.Instant

class MetricsIntegrationSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_default", "sales")
  private val ro1 = RunningNode("ro1", poolKey, Role.ReadOnly, "127.0.0.1", 21900, "tok",
                                Some(1L), None, Instant.EPOCH)
  private def pool(nodes: List[RunningNode]): PoolState =
    PoolState(poolKey, nodes, RoleDistribution(0, 1, 0), Map.empty, Map.empty)

  "metrics stack" should "expose statements_total + node_in_flight after one routed statement" in:
    MetricsRegistry.resource(MetricsConfig()).use { reg =>
      IO {
        val tracker  = new NodeLoadTracker
        val sessions = new SessionRegistry
        val bindings = new MetricsBindings(reg.composite, tracker, sessions, () => List(pool(List(ro1))))
        val si       = new StatementInstruments(reg.composite)
        val endpoint = new MetricsEndpoint(reg.prometheus, () => bindings.refresh())

        // Simulate one in-flight + one completed statement.
        tracker.onStart("ro1")
        si.record("acme", "sales", "ok", 7L)
        tracker.onFinish("ro1", 7L)

        val routes = Http4sServerInterpreter[IO]().toRoutes(endpoint.serverEndpoints)
        val resp   = routes.orNotFound.run(Request[IO](Method.GET, uri"/metrics")).unsafeRunSync()
        val body   = resp.bodyText.compile.string.unsafeRunSync()

        resp.status.code shouldBe 200
        body should include ("statements_total{")
        body should include ("tenant=\"acme\"")
        body should include ("pool=\"sales\"")
        body should include ("status=\"ok\"")
        body should include ("node_in_flight{")
        body should include ("node_id=\"ro1\"")
        body should include ("pool_nodes{")
      }
    }.unsafeRunSync()
