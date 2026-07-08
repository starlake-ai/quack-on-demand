package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model._
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import cats.effect.Ref
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant

/** Executes one queued maintenance run end to end (spec sections 4 and 9): spawn an ephemeral node,
  * run the chain in order, record per-step counters + heartbeat, enforce holds, always tear the
  * node down. All effectful collaborators are injected as functions so the spec drives the runner
  * without HTTP or a live catalog.
  */
final class MaintenanceRunner(
    store: ControlPlaneStore,
    spawn: (String, String) => IO[Option[RunningNode]], // (tenant, tenantDb) -> node
    stop: String => IO[Unit],                           // nodeId
    exec: (RunningNode, String) => IO[Either[String, Unit]],
    snapshotsOlderThan: (String, String, Instant) => List[Long],
    pinnedSnapshotsOf: (String, String) => Set[Long],
    pinnedFilesOf: (String, String) => Set[String],
    scheduledForDeletion: (String, String) => List[String],
    totalBytesOf: (String, String) => Long,
    effectivePolicyOf: (String, String, Option[String], Option[String]) => EffectivePolicy,
    catalogAlias: (String, String) => String, // ATTACH alias on the node = dbName
    audit: AuditRecorder
) extends LazyLogging:

  private case class TableScope(schema: String, table: String)

  private def parseScope(scope: String): Option[TableScope] =
    if scope.startsWith("table:") then
      scope.drop(6).split("\\.", 2) match
        case Array(s, t) => Some(TableScope(s, t))
        case _           => None
    else None

  private def wants(run: MaintenanceRun, op: String): Boolean =
    run.operations.forall(_.split(",").map(_.trim).contains(op))

  def executeRun(run: MaintenanceRun): IO[Unit] =
    spawn(run.tenant, run.tenantDb).flatMap {
      case None =>
        IO.blocking(
          store.finishMaintenanceRun(run.id, "failed", RunCounters(), Some("node spawn failed"))
        ) *> auditRun(run, "failed", RunCounters())
      case Some(node) =>
        IO.blocking(store.heartbeatMaintenanceRun(run.id, RunCounters())) *>
          chain(run, node).guarantee(stop(node.nodeId).handleErrorWith(_ => IO.unit))
    }

  private def chain(run: MaintenanceRun, node: RunningNode): IO[Unit] =
    for
      alias      <- IO.pure(catalogAlias(run.tenant, run.tenantDb))
      tableScope <- IO.pure(parseScope(run.scope))
      pol        <- IO.pure(
        effectivePolicyOf(
          run.tenant,
          run.tenantDb,
          tableScope.map(_.schema),
          tableScope.map(_.table)
        )
      )
      pinned     <- IO.pure(pinnedSnapshotsOf(run.tenant, run.tenantDb))
      candidates <-
        IO.pure(
          if tableScope.isDefined then Nil
          else
            snapshotsOlderThan(
              run.tenant,
              run.tenantDb,
              Instant.now().minusSeconds(pol.retentionDays * 86400L)
            )
        )
      partitioned <- IO.pure(candidates.partition(v => !pinned.contains(v)))
      toExpire = partitioned._1
      skipped  = partitioned._2

      // Pinned-file fail-safe guard before cleanup (spec section 4 step 5).
      pinnedHit <-
        IO.pure {
          if tableScope.isDefined then Nil
          else
            val pf = pinnedFilesOf(run.tenant, run.tenantDb)
            if pf.isEmpty then Nil
            else scheduledForDeletion(run.tenant, run.tenantDb).filter(pf.contains)
        }

      countersRef <- Ref.of[IO, RunCounters](RunCounters(snapshotsSkippedPinned = skipped.size))
      statusRef   <- Ref.of[IO, String]("succeeded")
      errorRef    <- Ref.of[IO, Option[String]](None)

      step = (name: String, sql: String, onOk: RunCounters => RunCounters) =>
        exec(node, sql).flatMap {
          case Right(()) =>
            countersRef.update(onOk) *>
              countersRef.get
                .flatMap(c => IO.blocking(store.heartbeatMaintenanceRun(run.id, c)))
                .as(true)
          case Left(msg) =>
            statusRef.set("failed") *> errorRef.set(Some(s"$name: $msg")).as(false)
        }

      flushIO =
        if wants(run, "flush") then step("flush", MaintenanceChainSql.flush(alias), identity)
        else IO.pure(true)

      expireIO = IO.defer {
        if tableScope.isDefined || toExpire.isEmpty || !wants(run, "expire") then IO.pure(true)
        else
          step(
            "expire",
            MaintenanceChainSql.expireVersions(alias, toExpire),
            _.copy(snapshotsExpired = toExpire.size)
          )
      }

      mergeIO = IO.defer {
        if !pol.compactionEnabled || !wants(run, "merge") then IO.pure(true)
        else
          step(
            "merge",
            MaintenanceChainSql.mergeAdjacent(alias),
            c => c.copy(filesMerged = c.filesMerged + 1)
          )
      }

      rewriteIO = IO.defer {
        tableScope match
          case Some(ts) if wants(run, "rewrite") =>
            step(
              "rewrite",
              MaintenanceChainSql.rewriteTable(alias, ts.schema, ts.table),
              c => c.copy(filesRewritten = c.filesRewritten + 1)
            )
          case _ => IO.pure(true)
      }

      cleanupIO = IO.defer {
        if tableScope.isDefined || !wants(run, "cleanup") then IO.pure(true)
        else if pinnedHit.nonEmpty then
          statusRef.set("partial") *>
            errorRef
              .set(
                Some(
                  s"cleanup skipped: pinned files scheduled for deletion: " +
                    s"${pinnedHit.take(5).mkString(", ")}"
                )
              )
              .as(true)
        else
          val reclaimable = scheduledForDeletion(run.tenant, run.tenantDb).size
          step(
            "cleanup",
            MaintenanceChainSql.cleanupOldFiles(alias, pol.cleanupGraceDays),
            c => c.copy(filesCleaned = reclaimable)
          )
      }

      orphanIO = IO.defer {
        if tableScope.isDefined || !wants(run, "orphans") then IO.pure(true)
        else
          step(
            "orphans",
            MaintenanceChainSql.deleteOrphans(alias, pol.orphanMinAgeDays),
            c => c.copy(orphansDeleted = c.orphansDeleted + 1)
          )
      }

      // Sequential with short-circuit on failure; chain order is load-bearing.
      bytesBefore <- IO.pure(totalBytesOf(run.tenant, run.tenantDb))
      _           <-
        for
          ok1 <- flushIO
          ok2 <- if ok1 then expireIO else IO.pure(false)
          ok3 <- if ok2 then mergeIO else IO.pure(false)
          ok4 <- if ok3 then rewriteIO else IO.pure(false)
          ok5 <- if ok4 then cleanupIO else IO.pure(false)
          _   <- if ok5 then orphanIO else IO.pure(false)
        yield ()
      status     <- statusRef.get
      error      <- errorRef.get
      bytesAfter <- IO.blocking(totalBytesOf(run.tenant, run.tenantDb))
      _          <- countersRef.update(
        _.copy(bytesReclaimed = math.max(0L, bytesBefore - bytesAfter))
      )
      counters <- countersRef.get
      _        <- IO.blocking(store.finishMaintenanceRun(run.id, status, counters, error))
      _        <- auditRun(run, status, counters)
    yield ()

  private def auditRun(run: MaintenanceRun, status: String, c: RunCounters): IO[Unit] =
    IO.blocking(
      audit.restAs(
        "system",
        "system",
        "control-plane",
        AuditActions.MaintenanceRun,
        status,
        tenant = Some(run.tenant),
        target = Some(s"${run.tenantDb}/${run.scope}"),
        detail = Map(
          "trigger"                -> run.trigger,
          "snapshotsExpired"       -> c.snapshotsExpired.toString,
          "snapshotsSkippedPinned" -> c.snapshotsSkippedPinned.toString,
          "bytesReclaimed"         -> c.bytesReclaimed.toString
        )
      )
    ).handleErrorWith(_ => IO.unit)
