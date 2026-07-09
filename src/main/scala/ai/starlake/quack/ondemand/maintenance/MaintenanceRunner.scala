package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model._
import ai.starlake.quack.observability.metrics.MaintenanceMetrics
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
    audit: AuditRecorder,
    metrics: MaintenanceMetrics = MaintenanceMetrics.noop
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
    IO.realTimeInstant.flatMap { startedAt =>
      spawn(run.tenant, run.tenantDb).flatMap {
        case None =>
          IO.blocking(
            store.finishMaintenanceRun(run.id, "failed", RunCounters(), Some("node spawn failed"))
          ).flatMap(finishIfNotSwept(run, "failed", RunCounters(), startedAt))
        case Some(node) =>
          IO.blocking(store.heartbeatMaintenanceRun(run.id, RunCounters())) *>
            chain(run, node, startedAt)
              .guarantee(stop(node.nodeId).handleErrorWith(_ => IO.unit))
              .handleErrorWith { t =>
                // Defensive: a synchronous throw from an injected lookup escapes the
                // step-level Left handling; still record + audit the finished run.
                //
                // Note: this same handler also catches the case where the chain ran every
                // step successfully and only the final store.finishMaintenanceRun call inside
                // chain() threw (for example a transient store error). When that happens we
                // land here with just the thrown error, not the real per-step counters the
                // chain had already accumulated, so the run gets recorded "failed" with zero
                // counters even though the work actually completed. This is inherent
                // best-effort behavior given the current design (the counters live in a Ref
                // local to chain() and are not threaded out to this handler), not a new bug
                // introduced here; it is called out so a future change that wants exact
                // counters in that edge case knows where to look.
                val msg = Option(t.getMessage).getOrElse(t.toString)
                IO.blocking(
                  store.finishMaintenanceRun(run.id, "failed", RunCounters(), Some(msg))
                ).handleErrorWith(_ => IO.pure(false))
                  .flatMap(finishIfNotSwept(run, "failed", RunCounters(), startedAt))
              }
      }
    }

  /** [[finish]] records the audit trail + metrics for a run's terminal state -- but only when this
    * caller actually won the transition to that state. A `false` from `store.finishMaintenanceRun`
    * means a concurrent sweep already failed the row (stale heartbeat) between this run's last
    * liveness check and now; overwriting that with a success audit would misreport what happened,
    * so this path only logs and skips.
    */
  private def finishIfNotSwept(
      run: MaintenanceRun,
      status: String,
      counters: RunCounters,
      startedAt: Instant
  )(won: Boolean): IO[Unit] =
    if won then finish(run, status, counters, startedAt)
    else
      IO.delay(
        logger.warn(
          s"maintenance run ${run.id} (${run.tenant}/${run.tenantDb}) was already swept as " +
            "stale before this run could record its own finish; skipping duplicate finish/audit"
        )
      )

  private def chain(run: MaintenanceRun, node: RunningNode, startedAt: Instant): IO[Unit] =
    IO.defer {
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

        countersRef <- Ref.of[IO, RunCounters](RunCounters(snapshotsSkippedPinned = skipped.size))
        statusRef   <- Ref.of[IO, String]("succeeded")
        errorRef    <- Ref.of[IO, Option[String]](None)
        sweptRef    <- Ref.of[IO, Boolean](false)

        // `false` from heartbeatMaintenanceRun means a sweeper already reaped this run as a
        // zombie (stale heartbeat) elsewhere and marked it terminal-failed. Stop the chain
        // right there like a step failure would, but do not touch statusRef/errorRef: that
        // would be this caller overwriting a status it no longer owns. sweptRef records that
        // this is why the chain stopped, so the finish path below skips the finish-write and
        // success audit instead of clobbering the sweeper's row.
        step = (name: String, sql: String, onOk: RunCounters => RunCounters) =>
          exec(node, sql).flatMap {
            case Right(()) =>
              countersRef.update(onOk) *>
                countersRef.get
                  .flatMap(c => IO.blocking(store.heartbeatMaintenanceRun(run.id, c)))
                  .flatMap { stillOwned =>
                    if stillOwned then IO.pure(true)
                    else sweptRef.set(true).as(false)
                  }
            case Left(msg) =>
              statusRef.set("failed") *> errorRef.set(Some(s"$name: $msg")).as(false)
          }

        flushIO = IO.defer {
          if wants(run, "flush") then step("flush", MaintenanceChainSql.flush(alias), identity)
          else IO.pure(true)
        }

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
          else
            // Pinned-file fail-safe guard, read immediately before issuing cleanup (spec
            // section 4 step 5): this run's own expire+merge steps can newly schedule files
            // for deletion, so the guard must see the post-chain state, not a prelude snapshot.
            // One read of scheduledForDeletion is reused for both the guard and the
            // reclaimable count below.
            val scheduled = scheduledForDeletion(run.tenant, run.tenantDb)
            val pf        = pinnedFilesOf(run.tenant, run.tenantDb)
            val pinnedHit = if pf.isEmpty then Nil else scheduled.filter(pf.contains)
            if pinnedHit.nonEmpty then
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
              step(
                "cleanup",
                MaintenanceChainSql.cleanupOldFiles(alias, pol.cleanupGraceDays),
                c => c.copy(filesCleaned = scheduled.size)
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
        swept    <- sweptRef.get
        // When a mid-chain heartbeat already reported the run swept, the store row is already
        // terminal (failed by the sweeper); don't call finishMaintenanceRun again for it (that
        // would either no-op against a row this caller no longer owns, or -- worse, if the
        // sweeper's write and this write interleaved some other way -- risk overwriting it) and
        // don't emit a success/failed audit for a run this caller didn't actually conclude.
        // finishIfNotSwept(won = false) reuses the existing "already swept" log-and-skip path.
        _ <-
          if swept then finishIfNotSwept(run, status, counters, startedAt)(false)
          else
            IO.blocking(store.finishMaintenanceRun(run.id, status, counters, error))
              .flatMap(finishIfNotSwept(run, status, counters, startedAt))
      yield ()
    }

  /** Shared finish-path side effects: audit trail + metrics. Called from all three places
    * `executeRun`/`chain` can conclude a run (spawn failure, chain completion, unhandled throw).
    */
  private def finish(
      run: MaintenanceRun,
      status: String,
      c: RunCounters,
      startedAt: Instant
  ): IO[Unit] =
    for
      finishedAt <- IO.realTimeInstant
      _          <- auditRun(run, status, c)
      _          <- IO
        .delay(
          metrics.recordRun(
            tenant = run.tenant,
            tenantDb = run.tenantDb,
            result = status,
            durationSeconds =
              math.max(0L, finishedAt.toEpochMilli - startedAt.toEpochMilli) / 1000.0,
            bytesReclaimed = c.bytesReclaimed,
            filesCompacted = c.filesMerged + c.filesRewritten,
            snapshotsExpired = c.snapshotsExpired
          )
        )
        .handleErrorWith(_ => IO.unit)
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
