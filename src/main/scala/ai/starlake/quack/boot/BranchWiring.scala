package ai.starlake.quack.boot

import ai.starlake.quack.{BranchingConfig, ManagedObjectStoreConfig}
import ai.starlake.quack.edge.adapter.{QuackError, QuackHttpAdapter, QuackResponse}
import ai.starlake.quack.model.{Branch, NodeSpec, RunningNode}
import ai.starlake.quack.ondemand.api.{
  ArrowRowsDecoder,
  CatalogPreviewHandlers,
  DataDiffSql,
  ExecCaller
}
import ai.starlake.quack.ondemand.branch.{
  BranchService,
  ChangeCounter,
  MergeExecutor,
  MergeNode,
  TableChange
}
import ai.starlake.quack.ondemand.runtime.{NodeReadiness, QuackBackend}
import ai.starlake.quack.ondemand.storage.S3ManagedStoreClient
import cats.effect.{FiberIO, IO}
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** The world-touching collaborators of the branch service (Epic 1), extracted from Main like the
  * maintenance wiring: the ephemeral merge node runner (same spawn / await-ready / stop bracket as
  * maintenance), the change-feed counter (one `ducklake_table_changes` aggregate per modified table
  * on the branch pool through the routed executor), the branch-prefix purge (local filesystem or
  * S3-compatible store with the tenant-db's own credentials), and the leader-gated TTL sweep.
  */
object BranchWiring extends LazyLogging:

  /** Merge node reads are one row of counts or up to 80 sample rowids. A longer result is refused,
    * never cut.
    */
  private val MergeNodeMaxRows = 1000

  /** Spawns `spec`, waits for it to accept connections, runs the body, stops the node. Each send is
    * bounded by `mergeTimeoutSec`: one that does not answer in time is abandoned (it ends when the
    * node is stopped) and reported as a `Left` starting with [[MergeExecutor.TimedOut]], and the
    * caller settles the outcome by locating the commit message on the parent. A node that cannot be
    * stopped is logged at ERROR.
    */
  def mergeExecutor(
      cfg: BranchingConfig,
      backend: QuackBackend,
      adapter: QuackHttpAdapter
  ): MergeExecutor = new MergeExecutor:

    def withNode[A](
        spec: NodeSpec
    )(body: MergeNode => IO[Either[String, A]]): IO[Either[String, A]] =
      // A bracket: the spawn runs masked, so a cancel pending when it returns still stops the node.
      backend
        .start(spec)
        .attempt
        .bracket {
          case Left(e)     => IO.pure(Left(s"merge node spawn failed: ${e.getMessage}"))
          case Right(node) =>
            NodeReadiness
              .awaitReachable(
                node.host,
                node.port,
                timeout = cfg.nodeReadyTimeoutSec.seconds,
                isAlive = () => backend.isAlive(node.nodeId)
              )
              .flatMap { ready =>
                if ready then body(on(node))
                else
                  IO.pure(
                    Left(
                      s"merge node ${node.nodeId} did not accept connections within ${cfg.nodeReadyTimeoutSec}s"
                    )
                  )
              }
        } {
          case Left(_)     => IO.unit
          case Right(node) => stopped(node)
        }

    /** Stops `node`. One that cannot be stopped is logged, not swallowed: it may still be running,
      * and a caller that counts on the node being gone (the merge's last look at main) needs the
      * trace.
      */
    private def stopped(node: RunningNode): IO[Unit] =
      backend.stop(node.poolKey, node.nodeId).handleErrorWith { e =>
        IO.delay(
          logger.error(
            s"merge node ${node.nodeId} could not be stopped and may still be running: " +
              e.getMessage,
            e
          )
        )
      }

    /** `call` within `mergeTimeoutSec`. The bound holds even when the send is stuck: the production
      * client's send is uncancelable (a blocking POST with no request timeout of its own), so
      * waiting for it to end, as `timeoutTo` does, would hold the answer for as long as the node
      * hangs. The send is abandoned instead, and ends when `backend.stop` kills the node.
      */
    private def bounded[A](call: IO[Either[String, A]]): IO[Either[String, A]] =
      call
        .timeoutAndForget(cfg.mergeTimeoutSec.seconds)
        .recover { case _: TimeoutException =>
          Left(
            s"${MergeExecutor.TimedOut}the merge node did not answer within ${cfg.mergeTimeoutSec}s"
          )
        }

    private def send(node: RunningNode, sql: String) =
      adapter.send(
        node,
        sql,
        ai.starlake.quack.edge.authz.NodePrincipal.System,
        session = None,
        recordLoad = false
      )

    // A close is a blocking POST (the DISCONNECT), so it runs on the blocking pool.
    private def on(node: RunningNode): MergeNode = new MergeNode:
      def exec(batch: String): IO[Either[String, Unit]] =
        bounded(send(node, batch).flatMap {
          case QuackResponse.Ok(_, _, close) => IO.blocking(close()).as(Right(()))
          case QuackResponse.Failed(err, _)  => IO.pure(Left(textOf(err)))
        })

      def rows(sql: String): IO[Either[String, List[List[Json]]]] =
        bounded(send(node, sql).flatMap {
          case QuackResponse.Failed(err, _)       => IO.pure(Left(textOf(err)))
          case QuackResponse.Ok(reader, _, close) =>
            IO.blocking(ArrowRowsDecoder.decode(reader, MergeNodeMaxRows))
              .guarantee(IO.blocking(close()))
              .attempt
              .map {
                case Left(e)             => Left(s"reading the result: ${e.getMessage}")
                case Right((_, _, true)) =>
                  Left(s"the result holds more than $MergeNodeMaxRows rows")
                case Right((_, rows, false)) => Right(rows)
              }
        })

    private def textOf(err: QuackError): String = err match
      case QuackError.Transient(message, _) => message
      case QuackError.Permanent(message)    => message

  /** Counts through the same executor the catalog data-diff uses, as the caller the service hands
    * it: the requester for counts asked over REST or MCP, the system caller only for propose's
    * summary. Never a fallback to the system caller: a refused count is the caller's answer.
    */
  def changeCounter(
      executor: CatalogPreviewHandlers.PreviewExecutor,
      poolKeyOf: Branch => ai.starlake.quack.model.PoolKey,
      timeout: FiniteDuration
  ): ChangeCounter = new ChangeCounter:
    def count(
        caller: ExecCaller,
        tenant: String,
        branch: Branch,
        alias: String,
        change: TableChange,
        fork: Long,
        head: Long
    ): IO[Either[String, (Long, Long, Long)]] =
      val sql = DataDiffSql.summarySql(alias, change.schema, change.table, fork, head)
      executor(caller, poolKeyOf(branch), sql).timeout(timeout).attempt.map {
        case Left(e)          => Left(e.getMessage)
        case Right(Left(f))   => Left(f.reason)
        case Right(Right(qr)) =>
          try
            val (_, rows, _) = ArrowRowsDecoder.decode(qr.rows, 64)
            val s            = DataDiffSql.foldSummary(rows)
            Right((s.inserted, s.deleted, s.updated))
          catch case NonFatal(e) => Left(e.getMessage)
          finally qr.close()
      }

  /** Delete every object under a branch data prefix. Local paths: recursive delete. `s3://`
    * prefixes: list and batch-delete with the tenant-db's objectStore credentials (BYO or managed,
    * the map carries the same keys). Other schemes: Left, the caller logs and leaves the files.
    */
  def purgeFiles(dataPath: String, objectStore: Map[String, String]): Either[String, Unit] =
    if dataPath.startsWith("s3://") || dataPath.startsWith("s3a://") || dataPath.startsWith("r2://")
    then purgeS3(dataPath, objectStore)
    else if dataPath.contains("://") then
      Left(s"unsupported object-store scheme for purge: $dataPath")
    else purgeLocal(Path.of(dataPath))

  private def purgeLocal(dir: Path): Either[String, Unit] =
    try
      if Files.exists(dir) then
        Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => Files.deleteIfExists(p))
      Right(())
    catch case NonFatal(e) => Left(s"local purge of $dir failed: ${e.getMessage}")

  private def purgeS3(dataPath: String, objectStore: Map[String, String]): Either[String, Unit] =
    val noScheme = dataPath.substring(dataPath.indexOf("://") + 3)
    val slash    = noScheme.indexOf('/')
    if slash <= 0 then Left(s"cannot split bucket/key from '$dataPath'")
    else
      val bucket = noScheme.substring(0, slash)
      val prefix = noScheme.substring(slash + 1)
      if prefix.isEmpty then Left(s"refusing to purge a whole bucket: '$dataPath'")
      else
        val cfg = ManagedObjectStoreConfig(
          enabled = true,
          endpoint = objectStore.getOrElse("s3_endpoint", ""),
          region = objectStore.getOrElse("s3_region", "us-east-1"),
          bucket = bucket,
          accessKeyId = objectStore.getOrElse("s3_access_key_id", ""),
          secretAccessKey = objectStore.getOrElse("s3_secret_access_key", ""),
          urlStyle = objectStore.getOrElse("s3_url_style", "path")
        )
        val client = new S3ManagedStoreClient(cfg)
        @annotation.tailrec
        def drain(rounds: Int): Either[String, Unit] =
          if rounds <= 0 then Left(s"prefix '$dataPath' still has objects after 100 batches")
          else
            client.listPrefix(prefix, 1000) match
              case Left(err)   => Left(err)
              case Right(Nil)  => Right(())
              case Right(keys) =>
                client.deleteBatch(keys) match
                  case Left(err) => Left(err)
                  case Right(()) => drain(rounds - 1)
        drain(100)

  /** One expiry pass every `cfg.sweepInterval`, leader-only, re-evaluated per run (IO.defer). */
  def expiryFiber(
      cfg: BranchingConfig,
      service: BranchService,
      isLeader: () => Boolean
  ): IO[FiberIO[Unit]] =
    if !cfg.enabled then IO.unit.start
    else
      val tick = IO.defer {
        if !isLeader() then IO.unit
        else
          service.expireDue().flatMap { expired =>
            if expired.isEmpty then IO.unit
            else
              IO.delay(
                logger.info(s"branch expiry: discarded ${expired.map(_.name).mkString(", ")}")
              )
          }
      }
      (tick.handleErrorWith(t =>
        IO.delay(logger.warn(s"branch expiry: pass failed, continuing: ${t.getMessage}"))
      ) *> IO.sleep(cfg.sweepInterval)).foreverM.void.start
