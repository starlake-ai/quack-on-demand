package ai.starlake.quack.ondemand.runtime

import java.util.Locale
import ai.starlake.quack.model.{NodeSpec, PoolKey, RunningNode}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import java.lang.{ProcessBuilder => JProcessBuilder}
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

/** Runs each node as a child process: the spawn script (bash, or PowerShell on Windows), which
  * starts qod-node.
  *
  * @param nodeBin
  *   the qod-node every spawn runs, handed to the script as `QOD_NODE_BIN` ([[QodNodeBinary]]; Main
  *   resolves it at boot). None leaves the script to its inherited `QOD_NODE_BIN` or `PATH`, for
  *   tests whose `commandFor` runs no script at all.
  * @param nodeKeys
  *   the keys every spawn's `QOD_NODE_AUTHZ_KEY` derives from; Main passes the manager's, so its
  *   handles verify
  * @param nodeExtensions
  *   the extension policy every spawn hands qod-node (spec decision 17); Main passes the manager's
  */
final class LocalQuackBackend(
    min: Int,
    max: Int,
    commandFor: (NodeSpec, Int, String) => List[String] =
      LocalQuackBackend.defaultCommand(LocalQuackBackend.DefaultSpawnScript),
    nodeBin: Option[Path] = None,
    nodeKeys: ai.starlake.quack.edge.authz.NodeKeys =
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
    nodeExtensions: ai.starlake.quack.NodeExtensionsConfig =
      ai.starlake.quack.NodeExtensionsConfig()
) extends QuackBackend
    with LazyLogging:

  private val ports     = new PortAllocator(min, max)
  private val processes = TrieMap.empty[String, Process]
  private val tokens    = TrieMap.empty[String, String]
  private val nodePorts = TrieMap.empty[String, Int]
  // Processes of nodes recovered via `adopt()` - we don't have a Process
  // handle for those (parent JVM didn't fork them), but we can still
  // SIGTERM them on stop(). Only identity-verified handles (NodeProcess), so
  // a signal never reaches a process that merely reuses the row's pid.
  private val adoptedProcs = TrieMap.empty[String, ProcessHandle]
  // Ports of node processes still running that this backend does not hold (left by an earlier
  // manager), each leased until that process exits: a new node must never be started onto a port a
  // leftover may still be listening on. Keyed by port; the value is the process holding the lease.
  private val reserved = TrieMap.empty[Int, ProcessHandle]
  // The OS reported no start instant for a node: logged once, see start().
  private val startInstantMissingLogged = new java.util.concurrent.atomic.AtomicBoolean(false)
  // Metastore keys that reach no node, named once per pool and key.
  private val droppedKeys = new NodeMetastore.DroppedKeys(logger)

  // A manager killed together with a node it was spawning can leave a secrets directory behind.
  NodeSecretFiles.sweepStale()

  def start(spec: NodeSpec): IO[RunningNode] = IO.blocking {
    // One process per node id. A start under an id this backend still holds (reconcile replacing
    // a node it judged dead) stops the held process first: overwriting the handle instead would
    // leave that process running untracked, holding its port past every stop path, and leak the
    // port lease too.
    if processes.contains(spec.nodeId) || adoptedProcs.contains(spec.nodeId) ||
      nodePorts.contains(spec.nodeId)
    then
      held(spec.nodeId).filter(_.isAlive).foreach { h =>
        logger.warn(s"start of ${spec.nodeId}: stopping its still-running process ${h.pid()} first")
      }
      release(spec.nodeId)
    val port  = ports.lease().getOrElse(throw new RuntimeException(s"no free port in [$min, $max]"))
    val token = LocalQuackBackend.randomToken()
    val cmd   = commandFor(spec, port, token)
    val pb    = new JProcessBuilder(cmd*).inheritIO()
    // Propagate the metastore's TenantDb.NodeEnvKeys as environment variables to the spawned node.
    val env = pb.environment()
    // Nothing secret may sit in the node's environment (or argv): a node user reads both back
    // through getenv() / /proc/<pid>/environ. The sensitive values (NodeSecretFiles.Keys: metastore
    // pgPassword / encryptionKey, federation and object-store SQL, the node token, the node's key,
    // the manager's own QOD_S3_* / QOD_AZURE_* credentials) go to a private directory the script
    // reads and deletes; the manager's other credentials it inherited (the session JWT secret,
    // passwords, any *API_KEY*...) are dropped outright.
    val inherited = env.asScala.toMap
    val secrets   = NodeSecretFiles.collect(
      spec.metastore,
      spec.extraSetupSql,
      spec.objectStoreSql,
      spec.tenantStoreSql,
      token,
      nodeKeys.keyHex(spec.nodeId),
      inherited
    )
    inherited.keys.foreach { k =>
      if NodeSecretFiles.isKey(k) || NodeSecretFiles.isInheritedSecret(k) then env.remove(k)
    }
    nodeBin.foreach(b => env.put(QodNodeBinary.EnvVar, b.toString))
    // spec.metastore is ALREADY the fully-resolved, per-kind node metastore --
    // PoolSupervisor.effectiveMetastoreFor merged the manager defaults with the
    // tenant-db's own params and dropped whatever the kind doesn't want (e.g. no
    // `dataPath` for a pathless duckdb-file / in-memory tenant-db). Do NOT overlay
    // a separate defaults map here: re-merging manager defaults on top of this
    // would resurrect a key `effectiveMetastoreFor` deliberately removed (e.g. the
    // bootstrap tenant-db's DuckLake directory reappearing as `dataPath` on a
    // pathless duckdb-file node, which then fails ATTACH with "Is a directory").
    // Only TenantDb.NodeEnvKeys of it reach the script (NodeMetastore): the metastore is
    // tenant-admin input, and any other name (BASH_ENV, PATH, QOD_NODE_BIN, lockdownSql...) would
    // run commands as this process's user or replace a variable set here, whatever the order of
    // the puts.
    droppedKeys.report(spec.poolKey, spec.metastore)
    NodeMetastore.env(spec.metastore).foreach { case (k, v) => env.put(k, v) }
    env.put("kind", spec.kindWire)
    // The node's id, which qod-node checks every session handle against (the script passes it as
    // --node-id). No metastore key of this name reaches the environment.
    env.put(NodeSecretFiles.NodeIdEnv, spec.nodeId)
    // The extension policy qod-node fixes before its database opens (spec decision 17): all three,
    // always, after the metastore overlay, so neither a variable this process inherited (they are
    // also the manager's own config variables) nor a metastore entry decides one.
    nodeExtensions.env.foreach { case (k, v) => env.put(k, v) }
    // Tenant-db initSql: spawn-quack-node.sh emits it after the proxy settings and before the
    // catalog ATTACH, unlike extraSetupSql which runs after it.
    if spec.dbInitSql.nonEmpty then env.put("dbInitSql", spec.dbInitSql)
    // Engine lockdown block (NodeLockdown.sql). spawn-quack-node.sh appends it after extraSetupSql,
    // last, so the restrictions are in effect before qod-node serves any tenant statement.
    if spec.lockdownSql.nonEmpty then env.put("lockdownSql", spec.lockdownSql)
    val secretsDir =
      try NodeSecretFiles.writePrivateDir(secrets)
      catch
        case e: Throwable =>
          ports.release(port)
          throw e
    env.put(NodeSecretFiles.DirEnv, secretsDir.toString)
    env.put(NodeSecretFiles.EphemeralEnv, "1")
    val proc =
      try pb.start()
      catch
        case e: Throwable =>
          NodeSecretFiles.deleteQuietly(secretsDir)
          ports.release(port)
          throw e
    // The script and qod-node delete the directory once read; this covers a script that dies first
    // (a bad kind, no qod-node, a kill before it got that far).
    proc.onExit().thenRun(() => NodeSecretFiles.deleteQuietly(secretsDir))
    processes.put(spec.nodeId, proc)
    tokens.put(spec.nodeId, token)
    nodePorts.put(spec.nodeId, port)
    val startInstant = proc.toHandle.info().startInstant()
    if !startInstant.isPresent && proc.isAlive && startInstantMissingLogged.compareAndSet(
        false,
        true
      )
    then
      logger.error(
        "The OS reports no start time for spawned node processes, so a node's process cannot be " +
          "verified (NodeProcess): reconcile will read every local node as dead and replace it on " +
          "each pass. Run the manager where ProcessHandle.info().startInstant() is available."
      )

    RunningNode(
      nodeId = spec.nodeId,
      poolKey = spec.poolKey,
      role = spec.role,
      host = "127.0.0.1",
      port = port,
      token = token,
      pid = Some(proc.pid()),
      podName = None,
      // The OS's own start instant when it reports one: NodeProcess matches the row against it, and
      // on Linux the JVM derives it from a boot time read once, so a wall-clock step after the
      // manager started cannot make this node's own row fail its identity check.
      startedAt = startInstant.orElse(Instant.now()),
      maxConcurrent = spec.maxConcurrent
    )
  }

  def stop(key: PoolKey, nodeId: String): IO[Unit] = IO.blocking(release(nodeId))

  /** Terminate the node's process tree and drop every entry held for its id. An adopt entry is
    * dropped even when a Process handle exists (reconcile adopts the nodes this backend started
    * too), so `cleanup()` never signals a process that is already gone.
    */
  private def release(nodeId: String): Unit =
    val adopted = adoptedProcs.remove(nodeId)
    processes.remove(nodeId).map(_.toHandle).orElse(adopted).foreach { h =>
      LocalQuackBackend.stopTree(h, LocalQuackBackend.ShutdownGracePerProcessSec)
    }
    tokens.remove(nodeId)
    nodePorts.remove(nodeId).foreach(ports.release)

  /** The process this backend holds under `nodeId`: the one it forked, else the adopted one. */
  private def held(nodeId: String): Option[ProcessHandle] =
    processes.get(nodeId).map(_.toHandle).orElse(adoptedProcs.get(nodeId))

  /** A node's process from an earlier manager generation that this backend never adopted (it was
    * not listening when reconcile first saw it) is not reached by `release`, so a replacement would
    * orphan it. Stop it here, but only when its identity is verified ([[NodeProcess]]).
    */
  override def stopStray(node: RunningNode): IO[Unit] = IO.blocking {
    NodeProcess
      .of(node)
      .filterNot(h => held(node.nodeId).exists(_.pid() == h.pid()))
      .foreach { h =>
        logger.warn(
          s"${node.nodeId}: stopping process ${h.pid()} an earlier manager left running, " +
            "before replacing it"
        )
        LocalQuackBackend.stopTree(h, LocalQuackBackend.ShutdownGracePerProcessSec)
      }
  }

  def isAlive(nodeId: String): Boolean =
    processes.get(nodeId).exists(_.isAlive)

  /** Register a node that survived a manager restart: claim its port, record its token, and attach
    * a ProcessHandle so a future `stop()` actually kills the right OS process. We can't recover the
    * original `java.lang.Process` (the child wasn't a JVM-spawned subprocess anymore) - `processes`
    * keeps a `Process` map, so we only stash what we have. `stop()` falls back to the adopted
    * handle when the entry is adopt-only. A pid whose process does not match the row's `startedAt`
    * is not the node's and is never recorded, so no stop path can signal it.
    */
  override def adopt(node: RunningNode): IO[Unit] = IO.blocking {
    // A reservation made by reservePorts passes to the adopted node: its stop releases the port.
    reserved.remove(node.port)
    ports.markLeased(node.port)
    tokens.put(node.nodeId, node.token)
    nodePorts.put(node.nodeId, node.port)
    NodeProcess.of(node) match
      case Some(h) => adoptedProcs.put(node.nodeId, h)
      case None    => adoptedProcs.remove(node.nodeId)
    ()
  }

  /** Lease the port of every row whose process is still running but not held here, until that
    * process exits. A verified leftover is adopted or stopped in its pool's turn; an unverified one
    * (its start time disagrees with the row: a wall-clock step on Linux across an unclean manager
    * exit, or a reused pid) is never signalled, so its port stays out of the allocator instead and
    * an operator is told which pid to check. Without this, a replacement or another pool's spawn
    * earlier in the same pass could be started onto a port a leftover still listens on, and never
    * pass its health probe.
    */
  override def reservePorts(nodes: List[RunningNode]): IO[Unit] = IO.blocking {
    nodes.foreach { n =>
      val heldHere = processes.contains(n.nodeId) || adoptedProcs.contains(n.nodeId)
      if !heldHere && !reserved.contains(n.port) then
        n.pid
          .flatMap(p => Option(ProcessHandle.of(p).orElse(null)))
          .filter(_.isAlive)
          .foreach { h =>
            if ports.tryLease(n.port) then
              reserved.put(n.port, h)
              h.onExit().thenRun(() => if reserved.remove(n.port, h) then ports.release(n.port))
              if NodeProcess.of(n).isEmpty then
                logger.warn(
                  s"${n.nodeId}: pid ${h.pid()} is running but is not this node's process as " +
                    s"recorded (start time differs from the row); it is not signalled, and port " +
                    s"${n.port} stays reserved until it exits. If it is a leftover quack node, " +
                    "stop it by hand."
                )
          }
    }
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
    // Adopt-only nodes carry no Process handle, only the adopted ProcessHandle; without them a node
    // inherited from a previous manager generation survives SIGTERM deterministically.
    val handles = (processes.values.map(_.toHandle) ++
      adoptedProcs.collect { case (id, h) if !processes.contains(id) => h }).toList
      .filter(_.isAlive)
    // First pass: SIGTERM everyone in parallel (`taskkill /T` on Windows so
    // the qod-node.exe grandchild of each spawn wrapper goes down too).
    handles.foreach(h => LocalQuackBackend.terminate(h, force = false))
    // Second pass: bounded wait, then SIGKILL stragglers.
    handles.foreach { h =>
      if !LocalQuackBackend.awaitExit(h, LocalQuackBackend.ShutdownGracePerProcessSec) then
        LocalQuackBackend.terminate(h, force = true)
    }
    processes.clear()
    tokens.clear()
    nodePorts.clear()
    adoptedProcs.clear()
    reserved.clear()
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

  /** In-repo Windows fallback spawn script (a PowerShell mirror of `spawn-quack-node.sh`).
    * Production passes `mgrCfg.spawnScriptWindows` (HOCON `quack-on-demand.spawnScriptWindows`, env
    * `QOD_SPAWN_SCRIPT_WINDOWS`).
    */
  val DefaultSpawnScriptWindows: String = "./scripts/spawn-quack-node.ps1"

  /** True when the manager runs on Windows. Drives spawn-command shape (PowerShell wrapper vs bash
    * script) and process teardown (`taskkill /T` vs POSIX signals).
    */
  private[runtime] val isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase(Locale.ROOT).contains("win")

  /** Default command. Invokes `script` (the bundled spawn script in production), which assembles
    * the init SQL and starts qod-node, which runs it and serves over quack. The token is NOT on the
    * command line (any local user reads argv): qod-node reads it from the secrets directory.
    */
  def defaultCommand(script: String): (NodeSpec, Int, String) => List[String] =
    (_, port, _) => List(script, port.toString)

  /** This manager process's spawn marker: its REST port plus a per-boot random id, e.g.
    * `20900-3fa9c1e2`. Every node gets it as the spawn script's second argument, and the script
    * puts it in the node directory name (`quack-node.<marker>.XXXXXX`, which qod-node's
    * `--secret-directory` argv carries). `stop-jar`, `kill-quack-nodes` and `qod stop` match on
    * `<port>-`, so stopping one manager only touches that manager's nodes, not those of another
    * manager on the same host (an isolated test stack next to the one on :20900). The boot id tells
    * two lives of the same port apart in the node directory names.
    */
  def managerMarker(restPort: Int): String = s"$restPort-$bootId"

  private lazy val bootId: String =
    val b = new Array[Byte](4)
    new SecureRandom().nextBytes(b)
    b.map(x => f"${x & 0xff}%02x").mkString

  /** OS-aware default command. On Windows the child is
    * `powershell.exe -File <winScript> <port> <marker>` (the wrapper process the manager holds a
    * `Process` handle for; its `qod-node.exe` grandchild is reaped via `taskkill /T`). On Unix it
    * is `<unixScript> <port> <marker>`. `marker` is [[managerMarker]]; it is required so no
    * production spawn goes out unmarked (an unmarked node is invisible to the per-manager stop
    * paths).
    */
  def defaultCommand(
      unixScript: String,
      winScript: String,
      marker: String
  ): (NodeSpec, Int, String) => List[String] =
    if isWindows then
      (_, port, _) =>
        List(
          "powershell.exe",
          "-NoProfile",
          "-NonInteractive",
          "-ExecutionPolicy",
          "Bypass",
          "-File",
          winScript,
          port.toString,
          marker
        )
    else (_, port, _) => List(unixScript, port.toString, marker)

  /** Terminate a spawned node's process tree. On Windows uses `taskkill /PID <pid> /T` (`/F` when
    * `force`) so the whole process tree (the PowerShell wrapper and its `qod-node.exe` child) is
    * killed; `Process.destroy()` alone would leave the grandchild orphaned. On Unix signals through
    * the handle, which carries the process start time, so a pid reused by another process is never
    * signalled.
    */
  private[runtime] def terminate(h: ProcessHandle, force: Boolean): Unit =
    if isWindows then
      // taskkill names a bare pid: only signal while this handle's own process still runs.
      if h.isAlive then
        val cmd =
          if force then List("taskkill", "/PID", h.pid().toString, "/T", "/F")
          else List("taskkill", "/PID", h.pid().toString, "/T")
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
    else if force then
      // SIGKILL bypasses the wrapper's trap, which is what normally
      // forwards TERM to the qod-node child; sweep the descendants
      // first so the tree cannot outlive its wrapper.
      h.descendants().forEach { (d: java.lang.ProcessHandle) =>
        d.destroyForcibly(); ()
      }
      h.destroyForcibly()
      ()
    else
      h.destroy()
      ()

  /** True once `h` has exited, waiting at most `seconds`. */
  private[runtime] def awaitExit(h: ProcessHandle, seconds: Long): Boolean =
    try
      h.onExit().get(seconds, java.util.concurrent.TimeUnit.SECONDS)
      true
    catch case _: Throwable => !h.isAlive

  /** TERM, bounded wait, then KILL with a descendant sweep. A process that already exited is left
    * alone: its pid may belong to someone else by now.
    */
  private[runtime] def stopTree(h: ProcessHandle, graceSec: Long): Unit =
    if h.isAlive then
      terminate(h, force = false)
      if !awaitExit(h, graceSec) then terminate(h, force = true)

  private val rnd           = new SecureRandom()
  def randomToken(): String =
    val bytes = new Array[Byte](32)
    rnd.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
