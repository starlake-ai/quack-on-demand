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
    // When running from an installed app-image (QOD_APP_HOME set), prepend the bundled duckdb bin
    // so spawn-quack-node.sh's `command -v duckdb` resolves it. Additive: no-op in dev/source runs.
    LocalQuackBackend.duckdbPathPrefix(sys.env.get("QOD_APP_HOME")).foreach { prefix =>
      val current = Option(env.get("PATH")).getOrElse("")
      env.put(
        "PATH",
        if current.isEmpty then prefix else s"$prefix${java.io.File.pathSeparator}$current"
      )
    }
    defaultMetastore.foreach { case (k, v) => env.put(k, v) }
    spec.metastore.foreach { case (k, v) => env.put(k, v) }
    env.put("kind", spec.kindWire)
    if spec.extraSetupSql.nonEmpty then env.put("extraSetupSql", spec.extraSetupSql)
    // Tenant-db initSql: spawn-quack-node.sh emits it after the proxy settings
    // and before `INSTALL quack; LOAD quack;`, unlike extraSetupSql which runs
    // after the catalog ATTACH.
    if spec.dbInitSql.nonEmpty then env.put("dbInitSql", spec.dbInitSql)
    // Engine lockdown block (NodeLockdown.sql). spawn-quack-node.sh appends it
    // after extraSetupSql, right before quack_serve, so the value
    // restrictions are in effect before the node serves any tenant statement.
    if spec.lockdownSql.nonEmpty then env.put("lockdownSql", spec.lockdownSql)
    // Engine lockdown freeze (NodeLockdown.freezeSql). spawn-quack-node.sh
    // appends it AFTER quack_serve returns: quack_serve itself needs to
    // configure the server, so lock_configuration must not run before it.
    if spec.lockdownFreezeSql.nonEmpty then env.put("lockdownFreezeSql", spec.lockdownFreezeSql)
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
        LocalQuackBackend.terminate(p.pid(), force = false)
        if !p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) then
          LocalQuackBackend.terminate(p.pid(), force = true)
      case None =>
        // Adopted (from `reconcile`): no Process handle, but we kept the
        // pid. Fall back to pid-based teardown so the whole node tree still
        // dies (SIGTERM on Unix, `taskkill /T` on Windows).
        adoptedPids.remove(nodeId).foreach { pid =>
          LocalQuackBackend.terminate(pid, force = false)
          val handle = Option(java.lang.ProcessHandle.of(pid))
            .flatMap(o => if o.isPresent then Some(o.get()) else None)
          val exited = handle match
            case Some(h) =>
              try
                h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
                true
              catch case _: Throwable => false
            case None => true // already gone
          if !exited then LocalQuackBackend.terminate(pid, force = true)
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
    // First pass: SIGTERM everyone in parallel (`taskkill /T` on Windows so
    // the duckdb.exe grandchild of each spawn wrapper goes down too).
    procs.foreach { case (_, p) => LocalQuackBackend.terminate(p.pid(), force = false) }
    // Second pass: bounded wait, then SIGKILL stragglers.
    procs.foreach { case (_, p) =>
      if !p.waitFor(
          LocalQuackBackend.ShutdownGracePerProcessSec,
          java.util.concurrent.TimeUnit.SECONDS
        )
      then LocalQuackBackend.terminate(p.pid(), force = true)
    }
    // Adopt-only nodes carry no Process handle, only a pid; without this
    // pass a node inherited from a previous manager generation survives
    // SIGTERM deterministically. Same TERM -> bounded wait -> KILL idiom as
    // `stop(nodeId)`'s adopted branch.
    val adopted = adoptedPids.toList
    adopted.foreach { case (_, pid) => LocalQuackBackend.terminate(pid, force = false) }
    adopted.foreach { case (_, pid) =>
      val handle = Option(java.lang.ProcessHandle.of(pid))
        .flatMap(o => if o.isPresent then Some(o.get()) else None)
      val exited = handle match
        case Some(h) =>
          try
            h.onExit()
              .get(
                LocalQuackBackend.ShutdownGracePerProcessSec,
                java.util.concurrent.TimeUnit.SECONDS
              )
            true
          catch case _: Throwable => false
        case None => true // already gone
      if !exited then LocalQuackBackend.terminate(pid, force = true)
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

  /** Directory to prepend to the spawned node's PATH so it resolves the bundled `duckdb`. Set only
    * when `QOD_APP_HOME` is present (installed app-image); `None` for repo/dev runs, which rely on
    * `duckdb` already being on PATH.
    */
  def duckdbPathPrefix(appHome: Option[String]): Option[String] =
    appHome.filter(_.nonEmpty).map(h => s"$h/duckdb/bin")

  /** In-repo Windows fallback spawn script (a PowerShell mirror of `spawn-quack-node.sh`).
    * Production passes `mgrCfg.spawnScriptWindows` (HOCON `quack-on-demand.spawnScriptWindows`, env
    * `QOD_SPAWN_SCRIPT_WINDOWS`).
    */
  val DefaultSpawnScriptWindows: String = "./scripts/spawn-quack-node.ps1"

  /** True when the manager runs on Windows. Drives spawn-command shape (PowerShell wrapper vs bash
    * script) and process teardown (`taskkill /T` vs POSIX signals).
    */
  private[runtime] val isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** Default command. Invokes `script` (the bundled spawn script in production), which starts
    * DuckDB, attaches the DuckLake catalog, and calls `quack_serve(...)`.
    */
  def defaultCommand(script: String): (NodeSpec, Int, String) => List[String] =
    (_, port, token) => List(script, port.toString, token)

  /** OS-aware default command. On Windows the child is
    * `powershell.exe -File <winScript> <port> <token>` (the wrapper process the manager holds a
    * `Process` handle for; its `duckdb.exe` grandchild is reaped via `taskkill /T`). On Unix it is
    * the bash `<unixScript> <port> <token>` invocation, byte-identical to [[defaultCommand]].
    */
  def defaultCommand(
      unixScript: String,
      winScript: String
  ): (NodeSpec, Int, String) => List[String] =
    if isWindows then
      (_, port, token) =>
        List(
          "powershell.exe",
          "-NoProfile",
          "-NonInteractive",
          "-ExecutionPolicy",
          "Bypass",
          "-File",
          winScript,
          port.toString,
          token
        )
    else (_, port, token) => List(unixScript, port.toString, token)

  /** Terminate a spawned node by pid. On Windows uses `taskkill /PID <pid> /T` (`/F` when `force`)
    * so the whole process tree — the PowerShell wrapper and its `duckdb.exe` child — is killed;
    * `Process.destroy()` alone would leave the grandchild orphaned. On Unix routes through
    * `ProcessHandle` so behavior matches the prior `destroy()` / `destroyForcibly()` path.
    */
  private[runtime] def terminate(pid: Long, force: Boolean): Unit =
    if isWindows then
      val cmd =
        if force then List("taskkill", "/PID", pid.toString, "/T", "/F")
        else List("taskkill", "/PID", pid.toString, "/T")
      try
        val p = new java.lang.ProcessBuilder(cmd*)
          .redirectOutput(
            java.lang.ProcessBuilder.Redirect.DISCARD
          )
          .redirectError(java.lang.ProcessBuilder.Redirect.DISCARD)
          .start()
        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        ()
      catch case _: Throwable => ()
    else
      Option(java.lang.ProcessHandle.of(pid))
        .flatMap(o => if o.isPresent then Some(o.get()) else None)
        .foreach { h =>
          if force then
            // SIGKILL bypasses the wrapper's trap, which is what normally
            // forwards TERM to the duckdb grandchild; sweep the descendants
            // first so the tree cannot outlive its wrapper.
            h.descendants().forEach { (d: java.lang.ProcessHandle) =>
              d.destroyForcibly(); ()
            }
            h.destroyForcibly()
            ()
          else
            h.destroy()
            ()
        }

  private val rnd           = new SecureRandom()
  def randomToken(): String =
    val bytes = new Array[Byte](32)
    rnd.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
