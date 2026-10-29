package ai.starlake.quack.ondemand.initsql

import ai.starlake.quack.ondemand.runtime.NodeSecretFiles
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** One statement of a tenant's initSql that the tenant initSql allowlist refuses: its 1-based
  * position and why, never its text.
  */
final case class InitSqlRefusal(statement: Int, reason: String)

/** What the manager's write-time check made of a tenant's initSql. */
enum InitSqlVerdict:
  /** qod-node judged it: `statements` statements, the `refused` ones skipped by every node. */
  case Checked(statements: Int, refused: List[InitSqlRefusal])

  /** Not judged here (`why`); the node still is the gate. */
  case Unchecked(why: String)

/** The write-time check of a tenant admin's initSql (Task 20 Part D): a convenience that mirrors
  * the gate, which is the node. On a manager that resolves a qod-node binary it runs that binary's
  * `--check-init`, the same code as the node's init runner; elsewhere it judges nothing.
  */
trait InitSqlChecker:
  def check(sql: String): IO[InitSqlVerdict]

object InitSqlChecker extends LazyLogging:

  /** Why a manager without a local qod-node cannot check, for the response's warning. */
  val NoLocalNode: String =
    "this manager runs no qod-node of its own (Kubernetes, or fleet without " +
      "QOD_FLEET_EPHEMERAL=local), so the initSql was not checked here"

  /** A checker for a manager with no local qod-node: every verdict is [[InitSqlVerdict.Unchecked]].
    */
  val unavailable: InitSqlChecker = (_: String) => IO.pure(InitSqlVerdict.Unchecked(NoLocalNode))

  private final case class Wire(refused: List[InitSqlRefusal], statements: Int)
  private given Decoder[InitSqlRefusal] = deriveDecoder
  private given Decoder[Wire]           = deriveDecoder

  /** The verdict qod-node printed (`InitVerdictJson`), or why it is not one. */
  def parseVerdict(json: String): Either[String, InitSqlVerdict.Checked] =
    io.circe.parser
      .decode[Wire](json.trim)
      .left
      .map(_ => "qod-node --check-init printed no verdict")
      .map(w => InitSqlVerdict.Checked(w.statements, w.refused))

  /** Runs `<bin> --check-init` with `sql` on its stdin, within `timeoutSec`. The SQL never reaches
    * a file, an argument or a log line; the child gets this process's environment without its
    * credentials (as a local node does). Any failure is [[InitSqlVerdict.Unchecked]]: the node
    * still judges every statement when it starts.
    */
  def nodeBinary(bin: Path, timeoutSec: Long = 10): InitSqlChecker = (sql: String) =>
    IO.blocking {
      val pb  = new ProcessBuilder(bin.toString, "--check-init").redirectErrorStream(false)
      val env = pb.environment()
      env.keySet.asScala.toList.foreach { k =>
        if NodeSecretFiles.isKey(k) || NodeSecretFiles.isInheritedSecret(k) then env.remove(k)
      }
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      val p = pb.start()
      try
        val out  = new java.io.ByteArrayOutputStream()
        val pump = new Thread(() =>
          try
            p.getInputStream.transferTo(out)
            ()
          catch case _: java.io.IOException => ()
        )
        pump.setDaemon(true)
        pump.start()
        Using.resource(p.getOutputStream)(_.write(sql.getBytes(StandardCharsets.UTF_8)))
        if !p.waitFor(timeoutSec, TimeUnit.SECONDS) then
          InitSqlVerdict.Unchecked(s"the local qod-node did not answer within $timeoutSec s")
        else
          pump.join(TimeUnit.SECONDS.toMillis(timeoutSec))
          if p.exitValue() != 0 then
            InitSqlVerdict.Unchecked(s"the local qod-node check failed (exit ${p.exitValue()})")
          else
            parseVerdict(new String(out.toByteArray, StandardCharsets.UTF_8))
              .fold(InitSqlVerdict.Unchecked(_), identity)
      finally if p.isAlive then p.destroyForcibly()
    }.handleError { e =>
      logger.warn(s"initSql check: cannot run $bin --check-init: ${e.getClass.getSimpleName}")
      InitSqlVerdict.Unchecked("the local qod-node could not be run")
    }

  /** The refusal message of a write whose initSql the allowlist refuses. */
  def refusalMessage(statements: Int, refused: List[InitSqlRefusal]): String =
    // Statement 0 is the text as a whole (qod-node's size cap).
    val each = refused.map(r =>
      if r.statement == 0 then r.reason else s"statement ${r.statement} of $statements: ${r.reason}"
    )
    s"initSql holds ${refused.size} statement(s) a tenant admin may not run at node start (only " +
      "SET or RESET of an allowlisted setting to a literal, read-only PRAGMAs, and INSTALL or LOAD " +
      s"of an allowlisted extension by name; a superuser's initSql is not restricted): ${each.mkString("; ")}"

  /** The warning a write carries when its initSql could not be checked. */
  def uncheckedWarning(why: String): String =
    s"$why. Each node runs only the statements of the tenant initSql allowlist and skips the " +
      "others, logging their position."

  /** The write-time check of one write: Right(None) when there is nothing to check (a superuser's
    * or the manager's write, an empty initSql, or one equal to the stored `stored`) or the check
    * admits every statement; Right(Some(warning)) when it could not run; Left(message) when the
    * allowlist refuses a statement.
    */
  def writeCheck(
      checker: InitSqlChecker,
      writerTrusted: Boolean,
      incoming: String,
      stored: Option[String]
  ): IO[Either[String, Option[String]]] =
    if writerTrusted || incoming.trim.isEmpty || stored.contains(incoming) then IO.pure(Right(None))
    else
      checker.check(incoming).map {
        case InitSqlVerdict.Checked(_, Nil)     => Right(None)
        case InitSqlVerdict.Checked(n, refused) => Left(refusalMessage(n, refused))
        case InitSqlVerdict.Unchecked(why)      => Right(Some(uncheckedWarning(why)))
      }
