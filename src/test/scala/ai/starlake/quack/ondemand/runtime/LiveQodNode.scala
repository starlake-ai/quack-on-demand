package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.adapter.{QuackError, QuackHttpClient, QuackResponse}
import ai.starlake.quack.edge.authz.{NodeKeys, NodePrincipal}
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import cats.effect.unsafe.implicits.global

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.{Callable, ExecutionException, Executors, TimeUnit}
import scala.util.Try

/** A qod-node started directly (no spawn script) for specs that need a real node: its token and
  * node key in a private ephemeral secrets directory, `init` on its stdin (as the spawn scripts
  * pipe it), plain HTTP on 127.0.0.1. Statements reach it as the manager's do: the native client,
  * after a session handle from [[authorize]].
  *
  * The node ends with the JVM that started it (`--parent-pid`), so a test JVM that dies before
  * [[stop]] (SIGKILL, a stopped sbt, a CI timeout) leaves nothing running. The address half is
  * [[LiveQodNode.At]], which the specs whose nodes a spawn script starts use on their own.
  */
final class LiveQodNode private (
    val at: LiveQodNode.At,
    process: java.lang.Process,
    logFile: Path
):
  def port: Int         = at.port
  def token: String     = at.token
  def nodeId: String    = at.nodeId
  def keys: NodeKeys    = at.keys
  def node: RunningNode = at.node
  def endpoint: String  = at.endpoint

  /** The prelude producer of `principal`'s statements on this node. */
  def authorize(principal: NodePrincipal = LiveQodNode.Superuser): () => String =
    at.authorize(principal)

  /** `sql` as `principal`: the rows as lines (Right), or the node's error (Left). */
  def query(
      client: QuackHttpClient,
      sql: String,
      principal: NodePrincipal = LiveQodNode.Superuser
  ): Either[String, List[String]] =
    at.query(client, sql, principal)

  def log: String = Try(Files.readString(logFile)).getOrElse("")

  /** SIGKILL, so a run never leaves an orphan. */
  def stop(): Unit =
    process.destroyForcibly()
    process.waitFor(20, TimeUnit.SECONDS)

object LiveQodNode:

  /** The principal specs run as: superuser statements reach everything on the node. */
  val Superuser: NodePrincipal =
    NodePrincipal("test-superuser", "test-superuser", None, superuser = true, system = false)

  val Pool: PoolKey = PoolKey("test", "test_db", "test")

  /** A node's address, apart from its process: what the manager needs to reach it, whoever started
    * it (this helper, or a spawn script a spec runs). `keys` must be the ones the node was given
    * (its key is `keys.keyHex(nodeId)`).
    */
  final class At(val nodeId: String, val port: Int, val token: String, val keys: NodeKeys):

    val node: RunningNode =
      RunningNode(nodeId, Pool, Role.Dual, "127.0.0.1", port, token, None, None, Instant.EPOCH)

    def endpoint: String = s"quack:127.0.0.1:$port"

    /** The prelude producer of `principal`'s statements on this node: a fresh handle per call. */
    def authorize(principal: NodePrincipal = Superuser): () => String =
      () => keys.prelude(node, principal)

    /** `sql` as the manager runs it: the native client, after a session handle of `principal`. The
      * rows as lines (Right), or the node's error (Left).
      */
    def query(
        client: QuackHttpClient,
        sql: String,
        principal: NodePrincipal = Superuser
    ): Either[String, List[String]] =
      lines(client.query(endpoint, token, sql, None, authorize(principal)).unsafeRunSync())

  def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  /** The one thread that starts every node, for the life of the JVM. On Linux the parent-death
    * signal qod-node arms (PR_SET_PDEATHSIG, when `--parent-pid` names its real parent) fires when
    * the parent THREAD exits, not the process: a node started from a pooled thread (a cats-effect
    * blocking thread reclaimed after idling, for one) would be SIGTERMed in the middle of a suite.
    */
  private val launcher = Executors.newSingleThreadExecutor { task =>
    val thread = new Thread(task, "live-qod-node-launcher")
    thread.setDaemon(true)
    thread
  }

  private def launch(builder: java.lang.ProcessBuilder): java.lang.Process =
    val task: Callable[java.lang.Process] = () => builder.start()
    try launcher.submit(task).get()
    catch case e: ExecutionException => throw e.getCause

  /** Start a node in `dir` and wait (60 s) for it to serve; throws with the node's log when it does
    * not. Callers `assume(QodNodeTestBinary.path.nonEmpty, ...)` first. `home`, when given, is the
    * node's HOME (a [[LiveExtensionCache.seed]]ed one shares the run's extension downloads).
    */
  def start(
      dir: Path,
      init: String,
      nodeId: String = "quack-test-live-1",
      keys: NodeKeys = NodeKeys.ephemeral(),
      port: Int = freePort(),
      token: String = "tok-live-qod-node",
      home: Option[Path] = None
  ): LiveQodNode =
    val bin     = QodNodeTestBinary.path.getOrElse(throw new IllegalStateException("no qod-node"))
    val nonce   = NodeSecretFiles.newInitNonce()
    val secrets = NodeSecretFiles.writePrivateDir(
      Map(
        NodeSecretFiles.TokenKey     -> token,
        NodeSecretFiles.AuthzKey     -> keys.keyHex(nodeId),
        NodeSecretFiles.InitNonceKey -> nonce
      ),
      Some(dir)
    )
    val logFile = dir.resolve(s"node-$port.log")
    val builder = new java.lang.ProcessBuilder(
      bin.toString,
      "--port",
      port.toString,
      "--bind",
      "127.0.0.1",
      "--secrets-dir",
      secrets.toString,
      "--secrets-ephemeral",
      "--secret-directory",
      dir.resolve(s"secret-dir-$port").toString,
      "--node-id",
      nodeId,
      "--parent-pid",
      ProcessHandle.current().pid().toString,
      "--init",
      "-"
    ).directory(dir.toFile)
      .redirectErrorStream(true)
      .redirectOutput(logFile.toFile)
    home.foreach(h => builder.environment().put("HOME", h.toString))
    val p    = launch(builder)
    val live = new LiveQodNode(new At(nodeId, port, token, keys), p, logFile)
    // The init SQL goes down the node's stdin and the pipe is closed, as the spawn scripts do (init
    // SQL can carry credentials, so never a file). A node that ended before reading it is reported
    // with its log below.
    Try {
      val stdin = p.getOutputStream
      // The first line names this start's nonce, as the spawn scripts write it.
      try stdin.write(s"-- qod:init-nonce $nonce\n$init".getBytes(StandardCharsets.UTF_8))
      finally stdin.close()
    }
    val deadline = System.currentTimeMillis() + 60000
    while !live.log.contains("[qod-node] ready") && p.isAlive &&
      System.currentTimeMillis() < deadline
    do Thread.sleep(100)
    if !live.log.contains("[qod-node] ready") then
      live.stop()
      throw new IllegalStateException(s"qod-node did not start:\n${live.log}")
    live

  /** Every row of an Ok response (closed), each cell as Arrow's object. */
  def rows(resp: QuackResponse): List[List[Any]] = resp match
    case ok: QuackResponse.Ok =>
      try
        val out  = scala.collection.mutable.ListBuffer.empty[List[Any]]
        val root = ok.rows.getVectorSchemaRoot
        while ok.rows.loadNextBatch() do
          (0 until root.getRowCount).foreach { i =>
            out += root.getFieldVectors.toArray.toList
              .map(_.asInstanceOf[org.apache.arrow.vector.FieldVector].getObject(i))
          }
        out.toList
      finally ok.close()
    case QuackResponse.Failed(err, _) => throw new AssertionError(s"statement failed: $err")

  /** The rows as `a,b` lines (Right), or the node's error (Left). */
  def lines(resp: QuackResponse): Either[String, List[String]] = resp match
    case QuackResponse.Failed(QuackError.Permanent(m), _) => Left(m)
    case QuackResponse.Failed(QuackError.Transient(m), _) => Left(m)
    case ok: QuackResponse.Ok                             =>
      Right(rows(ok).map(_.map(c => Option(c).map(_.toString).getOrElse("")).mkString(",")))
