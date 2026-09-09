package ai.starlake.quack.edge.admin

import ai.starlake.quack.model.{NodeSpec, PoolKey, RunningNode}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import cats.effect.IO

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Shared fake [[QuackBackend]] for admin-dialect tests: nodes are tracked in memory and never
  * actually spawned. Mirrors the anonymous backend in `FlightSqlRouterSpec.setup`.
  */
object TestBackends:

  def fake(): QuackBackend = new QuackBackend:
    private val n          = TrieMap.empty[String, RunningNode]
    def start(s: NodeSpec) = IO {
      val r = RunningNode(
        s.nodeId,
        s.poolKey,
        s.role,
        "127.0.0.1",
        21000 + n.size,
        "tok",
        Some(1L),
        None,
        Instant.EPOCH,
        maxConcurrent = s.maxConcurrent
      )
      n.put(s.nodeId, r); r
    }
    def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
    def isAlive(id: String)            = n.contains(id)
    def discoverExisting()             = IO.pure(n.values.toList)
    def cleanup()                      = IO(n.clear())
