package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, RunningNode}
import cats.effect.IO

import java.lang.{ProcessBuilder => JProcessBuilder}
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import scala.collection.concurrent.TrieMap

final class LocalQuackBackend(
    min: Int,
    max: Int,
    defaultMetastore: Map[String, String] = Map.empty,
    commandFor: (NodeSpec, Int, String) => List[String] =
      LocalQuackBackend.defaultCommand(LocalQuackBackend.DefaultSpawnScript)
) extends QuackBackend:

  private val ports     = new PortAllocator(min, max)
  private val processes = TrieMap.empty[String, Process]
  private val tokens    = TrieMap.empty[String, String]
  private val nodePorts = TrieMap.empty[String, Int]
  // PIDs of nodes recovered via `adopt()` - we don't have a Process
  // handle for those (parent JVM didn't fork them), but we can still
  // SIGTERM via ProcessHandle on stop().
  private val adoptedPids = TrieMap.empty[String, Long]

  def start(spec: NodeSpec): IO[RunningNode] = IO.blocking {
    val port  = ports.lease().getOrElse(throw new RuntimeException(s"no free port in [$min, $max]"))
    val token = LocalQuackBackend.randomToken()
    val cmd   = commandFor(spec, port, token)
    val pb    = new JProcessBuilder(cmd*).inheritIO()
    // Propagate metastore as environment variables to the spawned Quack node.
    // Defaults from application.conf apply first; per-pool entries from
    // CreatePoolRequest.metastore override them.
    val env = pb.environment()
    defaultMetastore.foreach { case (k, v) => env.put(k, v) }
    spec.metastore.foreach { case (k, v) => env.put(k, v) }
    env.put("kind", spec.kindWire)
    if spec.extraSetupSql.nonEmpty then env.put("extraSetupSql", spec.extraSetupSql)
    val proc = pb.start()
    processes.put(spec.nodeId, proc)
    tokens.put(spec.nodeId, token)
    nodePorts.put(spec.nodeId, port)

    RunningNode(
      nodeId = spec.nodeId,
      poolKey = spec.poolKey,
      role = spec.role,
      host = "127.0.0.1",
      port = port,
      token = token,
      pid = Some(proc.pid()),
      podName = None,
      startedAt = Instant.now(),
      maxConcurrent = spec.maxConcurrent
    )
  }

  def stop(nodeId: String): IO[Unit] = IO.blocking {
    processes.remove(nodeId) match
      case Some(p) =>
        p.destroy()
        if !p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) then p.destroyForcibly()
      case None =>
        // Adopted (from `reconcile`): no Process handle, but we kept the
        // pid. Fall back to ProcessHandle so SIGTERM still lands.
        adoptedPids.remove(nodeId).foreach { pid =>
          Option(java.lang.ProcessHandle.of(pid))
            .flatMap(o => if o.isPresent then Some(o.get()) else None)
            .foreach { h =>
              h.destroy()
              try h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
              catch case _: Throwable => h.destroyForcibly()
            }
        }
    tokens.remove(nodeId)
    nodePorts.remove(nodeId).foreach(ports.release)
    ()
  }

  def isAlive(nodeId: String): Boolean =
    processes.get(nodeId).exists(_.isAlive)

  /** Register a node that survived a manager restart: claim its port, record its token, and attach
    * a ProcessHandle so a future `stop()` actually kills the right OS process. We can't recover the
    * original `java.lang.Process` (the child wasn't a JVM-spawned subprocess anymore) - `processes`
    * keeps a `Process` map, so we only stash what we have. `stop()` falls back to
    * `ProcessHandle.of(pid)` when the entry is adopt-only.
    */
  override def adopt(node: RunningNode): IO[Unit] = IO.blocking {
    ports.markLeased(node.port)
    tokens.put(node.nodeId, node.token)
    nodePorts.put(node.nodeId, node.port)
    node.pid.foreach(p => adoptedPids.put(node.nodeId, p))
    ()
  }

  def discoverExisting(): IO[List[RunningNode]] = IO.pure(List.empty)
  // Local-mode recovery is driven by StateStore, not by scanning the OS.

  /** Graceful teardown of every tracked child process. Called from the JVM shutdown hook in `Main`
    * so SIGTERM-on-the-manager translates into a clean drain instead of orphan node processes
    * hanging around.
    *
    * Pattern (per-process): SIGTERM via `destroy()`, wait up to
    * [[LocalQuackBackend.ShutdownGracePerProcessSec]] seconds for the child to exit; SIGKILL via
    * `destroyForcibly()` if it does not. Same idiom `stop(nodeId)` uses for an individual node.
    *
    * Idempotent + safe to call multiple times (the second call sees an empty `processes` map). Also
    * clears adopt-tracking maps so a restart inside the same JVM (e.g. tests) does not see stale
    * state.
    */
  def cleanup(): IO[Unit] = IO.blocking {
    val procs = processes.toList
    // First pass: SIGTERM everyone in parallel.
    procs.foreach { case (_, p) => p.destroy() }
    // Second pass: bounded wait, then SIGKILL stragglers.
    procs.foreach { case (_, p) =>
      if !p.waitFor(
          LocalQuackBackend.ShutdownGracePerProcessSec,
          java.util.concurrent.TimeUnit.SECONDS
        )
      then p.destroyForcibly()
    }
    processes.clear()
    tokens.clear()
    nodePorts.clear()
    adoptedPids.clear()
  }

object LocalQuackBackend:

  /** Per-process grace seconds inside `cleanup()` between SIGTERM and the SIGKILL fallback. 5 s
    * matches the per-node grace used by `stop(nodeId)`.
    */
  val ShutdownGracePerProcessSec: Long = 5L

  /** Path to the spawn script the default `commandFor` invokes. Production code passes
    * `mgrCfg.spawnScript` (HOCON `quack-on-demand.spawnScript`, env override `QOD_SPAWN_SCRIPT`);
    * this constant is the in-repo fallback so the test suite and zero-config dev runs keep working.
    */
  val DefaultSpawnScript: String = "./scripts/spawn-quack-node.sh"

  /** Default command. Invokes `script` (the bundled spawn script in production), which starts
    * DuckDB, attaches the DuckLake catalog, and calls `quack_serve(...)`.
    */
  def defaultCommand(script: String): (NodeSpec, Int, String) => List[String] =
    (_, port, token) => List(script, port.toString, token)

  private val rnd           = new SecureRandom()
  def randomToken(): String =
    val bytes = new Array[Byte](32)
    rnd.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
