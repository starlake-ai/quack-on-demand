package ai.starlake.quack.boot

import ai.starlake.quack.ManagedObjectStoreConfig
import ai.starlake.quack.ondemand.state.ManagedPrefixRow
import ai.starlake.quack.ondemand.storage.ManagedStoreClient
import cats.effect.{FiberIO, IO}
import com.typesafe.scalalogging.LazyLogging

/** The managed-object-store purge worker. Tenant-db drop only tombstones the prefix
  * (`markManagedPrefixDeleted`); the objects survive the retention window so an operator can still
  * recover them. Once `purgeEligibleAt` is past, this loop deletes the objects under the prefix and
  * stamps `purgedAt`.
  *
  * Work is bounded per tick on purpose: at most `maxBatchesPerPrefixPerTick` list+delete rounds per
  * prefix, so a huge prefix drains across several sweeps instead of holding the fiber (and the S3
  * connection) for minutes. That makes the worker resumable by construction: the tombstone row
  * keeps coming back as due until a listing comes back empty.
  *
  * Failures are isolated per prefix: a `Left` from list or delete logs a warn and moves to the NEXT
  * row, and the failed row is simply retried on the next sweep (`purgedAt` stays NULL).
  */
final class ManagedStoreWiring(
    cfg: ManagedObjectStoreConfig,
    client: ManagedStoreClient,
    due: java.time.Instant => List[ManagedPrefixRow],
    markPurged: (String, java.time.Instant) => Unit,
    isLeader: () => Boolean,
    batchSize: Int = 1000,
    maxBatchesPerPrefixPerTick: Int = 10,
    now: () => java.time.Instant = () => java.time.Instant.now()
) extends LazyLogging:

  private val bucketRoot = s"s3://${cfg.bucket}/"

  /** `ManagedPrefixRow.prefix` holds the full `s3://bucket/...` URL (it doubles as the tenant-db
    * dataPath); `listPrefix` wants the key prefix inside the bucket. A row that does not live under
    * the configured bucket is skipped rather than listed: passing the un-stripped URL through would
    * match nothing at best, and an empty prefix would enumerate the WHOLE bucket at worst.
    */
  private def keyPrefixOf(row: ManagedPrefixRow): Option[String] =
    val stripped = row.prefix.stripPrefix(bucketRoot)
    if stripped == row.prefix || stripped.isEmpty then
      logger.warn(
        s"managed purge: skipping ${row.id}, prefix '${row.prefix}' is not under '$bucketRoot'"
      )
      None
    else Some(stripped)

  @annotation.tailrec
  private def drain(row: ManagedPrefixRow, keyPrefix: String, batchesLeft: Int): Unit =
    if batchesLeft <= 0 then
      logger.info(
        s"managed purge: ${row.prefix} still has objects after " +
          s"$maxBatchesPerPrefixPerTick batches, resuming next sweep"
      )
    else
      client.listPrefix(keyPrefix, batchSize) match
        case Left(err) =>
          logger.warn(s"managed purge: list failed for ${row.prefix}, retrying next sweep: $err")
        case Right(Nil) =>
          markPurged(row.id, now())
          logger.info(
            s"managed purge: purged ${row.prefix} (${row.tenant}/${row.tenantDbName})"
          )
        case Right(keys) =>
          client.deleteBatch(keys) match
            case Left(err) =>
              logger.warn(
                s"managed purge: delete failed for ${row.prefix}, retrying next sweep: $err"
              )
            case Right(()) => drain(row, keyPrefix, batchesLeft - 1)

  private def drainRow(row: ManagedPrefixRow): Unit =
    keyPrefixOf(row).foreach(kp => drain(row, kp, maxBatchesPerPrefixPerTick))

  /** One purge pass over every due tombstone. Leader-only.
    *
    * IO.defer around the leader check: `fiber` binds this to a single IO value once (see its
    * `foreverM`), so the leadership branch must be re-evaluated on every run rather than baked in
    * at construction time. Otherwise a replica that boots before winning the advisory lock latches
    * "not leader" forever.
    *
    * One `IO.blocking` PER ROW rather than one wrapping the whole loop: `IO.blocking` is
    * uncancelable while running, so a single blocking block around every due row would make a
    * shutdown landing mid-sweep stall until every row (bounded per row, but unbounded in row count)
    * has drained. Folding the rows into the IO chain gives cancellation a checkpoint between rows,
    * same shape as `AutoscaleWiring.sweep`'s per-action fold.
    */
  def tick(): IO[Unit] = IO.defer {
    if !isLeader() then IO.unit
    else
      IO.blocking(due(now())).flatMap { rows =>
        rows.foldLeft(IO.unit)((acc, row) => acc *> IO.blocking(drainRow(row)))
      }
  }

  /** One sweep every `cfg.purgeSweepInterval` (60s floor). `enabled = false` starts no loop at all:
    * with managed storage off nothing ever writes a tombstone row.
    */
  def fiber: IO[FiberIO[Unit]] =
    if !cfg.enabled then IO.unit.start
    else
      (tick().handleErrorWith(t =>
        IO.delay(logger.warn(s"managed purge: pass failed, continuing: ${t.getMessage}"))
      ) *> IO.sleep(cfg.purgeSweepInterval)).foreverM.void.start
