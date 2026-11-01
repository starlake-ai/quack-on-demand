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
    stop: RunningNode => IO[Unit],
    exec: (RunningNode, String) => IO[Either[String, Long]], // Right(rows the statement returned)
    snapshotsOlderThan: (String, String, Instant) => List[Long],
    pinnedSnapshotsOf: (String, String) => Set[Long],
    hasLiveBranchOf: (String, String) => Boolean, // merge + cleanup skipped while true (F25)
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

  /** Whether the run's database is a branch catalog, which shares its parent's files. */
  private def isBranch(run: MaintenanceRun): Boolean =
    store.listTenantDbs(run.tenant).find(_.name == run.tenantDb).exists(_.branchOf.isDefined)

  def executeRun(run: MaintenanceRun): IO[Unit] =
    IO.realTimeInstant.flatMap { startedAt =>
      // Whatever queued it: a branch is never maintained (see MaintenanceScheduler), and no node
      // is started for one.
      IO.blocking(isBranch(run)).flatMap { branch =>
        if branch then
          val reason = "a branch database is never maintained: it shares its parent's files"
          IO.blocking(store.finishMaintenanceRun(run.id, "failed", RunCounters(), Some(reason)))
            .flatMap(finishIfNotSwept(run, "failed", RunCounters(), startedAt))
        else spawnAndRun(run, startedAt)
      }
    }

  private def spawnAndRun(run: MaintenanceRun, startedAt: Instant): IO[Unit] =
    IO.defer {
      spawn(run.tenant, run.tenantDb).flatMap {
        case None =>
          IO.blocking(
            store.finishMaintenanceRun(run.id, "failed", RunCounters(), Some("node spawn failed"))
          ).flatMap(finishIfNotSwept(run, "failed", RunCounters(), startedAt))
        case Some(node) =>
          // `false` from the first heartbeat means a sweeper failed the run while its node was
          // starting: the run is no longer this caller's, so no step may run (cleanup deletes
          // files), and the sweeper's row stays as it set it.
          IO.blocking(store.heartbeatMaintenanceRun(run.id, RunCounters()))
            .flatMap { stillOwned =>
              if stillOwned then chain(run, node, startedAt)
              else finishIfNotSwept(run, "failed", RunCounters(), startedAt)(false)
            }
            .guarantee(stop(node).handleErrorWith(_ => IO.unit))
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
        countersRef <- Ref.of[IO, RunCounters](RunCounters())
        statusRef   <- Ref.of[IO, String]("succeeded")
        errorRef    <- Ref.of[IO, Option[String]](None)
        sweptRef    <- Ref.of[IO, Boolean](false)

        // `false` from heartbeatMaintenanceRun means a sweeper already reaped this run as a
        // zombie (stale heartbeat) elsewhere and marked it terminal-failed. Stop the chain
        // right there like a step failure would, but do not touch statusRef/errorRef: that
        // would be this caller overwriting a status it no longer owns. sweptRef records that
        // this is why the chain stopped, so the finish path below skips the finish-write and
        // success audit instead of clobbering the sweeper's row.
        // `onOk` gets the counters and the number of rows the statement returned.
        step = (name: String, sql: String, onOk: (RunCounters, Long) => RunCounters) =>
          exec(node, sql).flatMap {
            case Right(rows) =>
              countersRef.update(onOk(_, rows)) *>
                countersRef.get
                  .flatMap(c => IO.blocking(store.heartbeatMaintenanceRun(run.id, c)))
                  .flatMap { stillOwned =>
                    if stillOwned then IO.pure(true)
                    else sweptRef.set(true).as(false)
                  }
            case Left(msg) =>
              statusRef.set("failed") *> errorRef.set(Some(s"$name: $msg")).as(false)
          }

        // A live branch reads the parent's Parquet files by absolute path. Merge hard-deletes the
        // rows of the files it merges away and schedules them for cleanup, so no pin derived from
        // the parent can see them, and the next cleanup would delete what the branch still scans
        // (audit F25). Both steps are skipped while the database has a live branch; the run is
        // recorded partial with the reason. Read at each step, not in the prelude: a branch cloned
        // while the merge ran forks over the files it just scheduled.
        skipForBranch = (what: String) =>
          statusRef.set("partial") *>
            errorRef
              .update { prev =>
                val msg = s"$what skipped: the database has a live branch reading its files"
                Some(prev.fold(msg)(p => s"$p; $msg"))
              }
              .as(true)

        flushIO = IO.defer {
          if wants(run, "flush") then step("flush", MaintenanceChainSql.flush(alias), (c, _) => c)
          else IO.pure(true)
        }

        // The pins are read under the database's snapshot-pin lock, held through the expire call:
        // a protected tag created while the run went on (the lock serializes it with this step)
        // is seen here, never expired after the fact.
        expireIO = IO.defer {
          if tableScope.isDefined || candidates.isEmpty || !wants(run, "expire") then IO.pure(true)
          else
            cats.effect.Resource
              .fromAutoCloseable(IO.blocking(store.lockSnapshotPins(run.tenant, run.tenantDb)))
              .use { _ =>
                IO.blocking(pinnedSnapshotsOf(run.tenant, run.tenantDb)).flatMap { pinned =>
                  val (toExpire, skipped) = candidates.partition(v => !pinned.contains(v))
                  countersRef.update(_.copy(snapshotsSkippedPinned = skipped.size)) *>
                    (if toExpire.isEmpty then IO.pure(true)
                     else
                       step(
                         "expire",
                         MaintenanceChainSql.expireVersions(alias, toExpire),
                         (c, _) => c.copy(snapshotsExpired = toExpire.size)
                       ))
                }
              }
        }

        mergeIO = IO.defer {
          if !pol.compactionEnabled || !wants(run, "merge") then IO.pure(true)
          else if hasLiveBranchOf(run.tenant, run.tenantDb) then skipForBranch("merge")
          else
            step(
              "merge",
              // A table-scoped run compacts its own table only, never its siblings.
              tableScope.fold(MaintenanceChainSql.mergeAdjacent(alias))(ts =>
                MaintenanceChainSql.mergeAdjacentTable(alias, ts.schema, ts.table)
              ),
              (c, _) => c.copy(filesMerged = c.filesMerged + 1)
            )
        }

        rewriteIO = IO.defer {
          tableScope match
            case Some(ts) if wants(run, "rewrite") =>
              step(
                "rewrite",
                MaintenanceChainSql.rewriteTable(
                  alias,
                  ts.schema,
                  ts.table,
                  pol.rewriteDeleteThreshold
                ),
                (c, _) => c.copy(filesRewritten = c.filesRewritten + 1)
              )
            case _ => IO.pure(true)
        }

        // No pinned-file check here: the one this replaced compared paths in two forms that never
        // match (data_path-relative scheduled paths, table-relative file paths) against pins read
        // after the merge had deleted the rows behind them, so it never fired (audit F25). A
        // protected tag's files need none (time travel at the tag reads the merged file); a live
        // branch's are kept by skipping merge and cleanup above. `filesCleaned` is what cleanup
        // reports deleting (one row per file), not what was scheduled: files inside the grace
        // window stay scheduled.
        cleanupIO = IO.defer {
          if tableScope.isDefined || !wants(run, "cleanup") then IO.pure(true)
          else if hasLiveBranchOf(run.tenant, run.tenantDb) then skipForBranch("cleanup")
          else
            step(
              "cleanup",
              MaintenanceChainSql.cleanupOldFiles(alias, pol.cleanupGraceDays),
              (c, deleted) => c.copy(filesCleaned = deleted.toInt)
            )
        }

        orphanIO = IO.defer {
          if tableScope.isDefined || !wants(run, "orphans") then IO.pure(true)
          else
            step(
              "orphans",
              MaintenanceChainSql.deleteOrphans(alias, pol.orphanMinAgeDays),
              (c, _) => c.copy(orphansDeleted = c.orphansDeleted + 1)
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
