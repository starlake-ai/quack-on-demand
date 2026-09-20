package ai.starlake.quack.boot

import ai.starlake.quack.{BranchingConfig, ManagedObjectStoreConfig}
import ai.starlake.quack.edge.adapter.{QuackHttpAdapter, QuackResponse}
import ai.starlake.quack.model.{Branch, NodeSpec}
import ai.starlake.quack.ondemand.api.{
  ArrowRowsDecoder,
  CatalogPreviewHandlers,
  DataDiffSql,
  ExecCaller
}
import ai.starlake.quack.ondemand.branch.{BranchService, ChangeCounter, MergeExecutor, TableChange}
import ai.starlake.quack.ondemand.runtime.{NodeReadiness, QuackBackend}
import ai.starlake.quack.ondemand.storage.S3ManagedStoreClient
import cats.effect.{FiberIO, IO}
import com.typesafe.scalalogging.LazyLogging

import java.nio.file.{Files, Path}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** The world-touching collaborators of the branch service (Epic 1), extracted from Main like the
  * maintenance wiring: the ephemeral merge node runner (same spawn / await-ready / stop bracket as
  * maintenance), the change-feed counter (one `ducklake_table_changes` aggregate per modified table
  * on the branch pool through the routed executor), the branch-prefix purge (local filesystem or
  * S3-compatible store with the tenant-db's own credentials), and the leader-gated TTL sweep.
  */
object BranchWiring extends LazyLogging:

  /** Spawns `spec`, waits for it to accept connections, sends the batch, stops the node. The wait
    * is bounded by `mergeTimeoutSec`; a timeout is reported as `Left("timeout ...")` and the caller
    * settles the outcome by locating the commit message on the parent.
    */
  def mergeExecutor(
      cfg: BranchingConfig,
      backend: QuackBackend,
      adapter: QuackHttpAdapter
  ): MergeExecutor = new MergeExecutor:
    def run(spec: NodeSpec, batch: String): IO[Either[String, Unit]] =
      backend.start(spec).attempt.flatMap {
        case Left(e)     => IO.pure(Left(s"merge node spawn failed: ${e.getMessage}"))
        case Right(node) =>
          val body = NodeReadiness
            .awaitReachable(
              node.host,
              node.port,
              timeout = cfg.nodeReadyTimeoutSec.seconds,
              isAlive = () => backend.isAlive(node.nodeId)
            )
            .flatMap { ready =>
              if !ready then
                IO.pure(
                  Left(
                    s"merge node ${node.nodeId} did not accept connections within ${cfg.nodeReadyTimeoutSec}s"
                  )
                )
              else
                adapter
                  .send(node, batch, session = None, recordLoad = false)
                  .map {
                    case QuackResponse.Ok(_, _, close) =>
                      close(); Right(())
                    case QuackResponse.Failed(err, _) => Left(err.toString)
                  }
                  .timeoutTo(
                    cfg.mergeTimeoutSec.seconds,
                    IO.pure(
                      Left(s"timeout: merge batch did not answer within ${cfg.mergeTimeoutSec}s")
                    )
                  )
            }
          body.guarantee(backend.stop(node.poolKey, node.nodeId).handleErrorWith(_ => IO.unit))
      }

  /** Counts through the same executor the catalog data-diff uses, under the system identity (the
    * change-feed table function is refused by the ACL parser for non-superusers, as documented on
    * the restore dry run).
    */
  def changeCounter(
      executor: CatalogPreviewHandlers.PreviewExecutor,
      poolKeyOf: Branch => ai.starlake.quack.model.PoolKey,
      timeout: FiniteDuration
  ): ChangeCounter = new ChangeCounter:
    def count(
        tenant: String,
        branch: Branch,
        alias: String,
        change: TableChange,
        fork: Long,
        head: Long
    ): IO[Either[String, (Long, Long, Long)]] =
      val sql    = DataDiffSql.summarySql(alias, change.schema, change.table, fork, head)
      val caller =
        ExecCaller.unrestricted(s"branch-counts-$tenant", CatalogPreviewHandlers.SuperuserIdentity)
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
