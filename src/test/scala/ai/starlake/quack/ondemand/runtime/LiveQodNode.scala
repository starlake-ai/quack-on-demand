package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.adapter.{QuackError, QuackResponse}
import ai.starlake.quack.edge.authz.{NodeKeys, NodePrincipal}
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.util.Try

/** A qod-node started directly (no spawn script) for specs that need a real node: its token and
  * node key in a private ephemeral secrets directory, `init` as its init file, plain HTTP on
  * 127.0.0.1. Statements reach it as the manager's do: the native client, after a session handle
  * from [[authorize]].
  */
final class LiveQodNode private (
    val port: Int,
    val token: String,
    val nodeId: String,
    val keys: NodeKeys,
    process: java.lang.Process,
    logFile: Path
):
  val node: RunningNode =
    RunningNode(
      nodeId,
      LiveQodNode.Pool,
      Role.Dual,
      "127.0.0.1",
      port,
      token,
      None,
      None,
      Instant.EPOCH
    )

  def endpoint: String = s"quack:127.0.0.1:$port"

  /** The prelude producer of `principal`'s statements on this node. */
  def authorize(principal: NodePrincipal = LiveQodNode.Superuser): () => String =
    () => keys.prelude(node, principal)

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

  def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  /** Start a node in `dir` and wait (60 s) for it to serve; throws with the node's log when it does
    * not. Callers `assume(QodNodeTestBinary.path.nonEmpty, ...)` first.
    */
  def start(
      dir: Path,
      init: String,
      nodeId: String = "quack-test-live-1",
      keys: NodeKeys = NodeKeys.ephemeral(),
      port: Int = freePort(),
      token: String = "tok-live-qod-node"
  ): LiveQodNode =
    val bin     = QodNodeTestBinary.path.getOrElse(throw new IllegalStateException("no qod-node"))
    val secrets = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> token, NodeSecretFiles.AuthzKey -> keys.keyHex(nodeId)),
      Some(dir)
    )
    val initFile = Files.writeString(dir.resolve(s"init-$port.sql"), init)
    val logFile  = dir.resolve(s"node-$port.log")
    val p        = new java.lang.ProcessBuilder(
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
      "--init",
      initFile.toString
    ).directory(dir.toFile)
      .redirectErrorStream(true)
      .redirectOutput(logFile.toFile)
      .start()
    val live     = new LiveQodNode(port, token, nodeId, keys, p, logFile)
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
