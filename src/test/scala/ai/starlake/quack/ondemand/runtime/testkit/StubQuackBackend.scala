package ai.starlake.quack.ondemand.runtime.testkit

import ai.starlake.quack.model.{NodeSpec, RunningNode}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import cats.effect.IO

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap

/** Shared stub [[QuackBackend]] for specs (previously copied nearly verbatim per spec).
  *
  * Tracking flavor: started nodes are recorded in a TrieMap so `isAlive` / `discoverExisting` /
  * `stop` reflect what was started. Ports are `portBase + <live node count>` at start time (the
  * exact scheme every per-spec copy used); tokens come from `tokenFor(nodeId)`.
  *
  * For the fire-and-forget flavor (nothing tracked, every node reports alive) use
  * [[StubQuackBackend.noop]].
  */
final class StubQuackBackend(
    portBase: Int = 21000,
    tokenFor: String => String = StubQuackBackend.DefaultToken
) extends QuackBackend:
  private val nodes = TrieMap.empty[String, RunningNode]

  def start(spec: NodeSpec): IO[RunningNode] = IO {
    val n = RunningNode(
      spec.nodeId,
      spec.poolKey,
      spec.role,
      "127.0.0.1",
      portBase + nodes.size,
      tokenFor(spec.nodeId),
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

object StubQuackBackend:

  /** Every node gets the literal token `tok`. */
  val DefaultToken: String => String = _ => "tok"

  /** Per-node token `tok-<nodeId>` (HA + health specs assert on distinct tokens). */
  val PerNodeToken: String => String = id => s"tok-$id"

  /** No-op flavor: every start succeeds, nothing is tracked, `isAlive` is always true and
    * `discoverExisting` is always empty.
    *
    *   - `countingPorts = false` (default): every node reports port `portBase`.
    *   - `countingPorts = true`: ports increment from `portBase` per start (for specs that need
    *     distinct ports without tracking).
    */
  def noop(portBase: Int = 21000, countingPorts: Boolean = false): QuackBackend =
    new QuackBackend:
      private val counter                     = new AtomicInteger(portBase)
      def start(s: NodeSpec): IO[RunningNode] =
        IO.pure(
          RunningNode(
            s.nodeId,
            s.poolKey,
            s.role,
            "127.0.0.1",
            if countingPorts then counter.getAndIncrement() else portBase,
            "tok",
            Some(1L),
            None,
            Instant.EPOCH,
            maxConcurrent = s.maxConcurrent
          )
        )
      def stop(id: String): IO[Unit]                = IO.unit
      def isAlive(id: String): Boolean              = true
      def discoverExisting(): IO[List[RunningNode]] = IO.pure(Nil)
      def cleanup(): IO[Unit]                       = IO.unit
