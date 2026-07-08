package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{MaintenancePolicy, Names, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.maintenance.{CronExpr, PolicyMath}
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** Managed-maintenance REST surface (EPIC Spec 09). Every mutation is TenantScopeCheck-gated and
  * audited, same shape as [[TagHandlers]]: resolve tenant -> TenantScopeCheck.reject -> tenant-db
  * lookup -> DuckLake-kind check returning `invalid_kind`.
  */
final class MaintenanceHandlers(
    sup: PoolSupervisor,
    store: ControlPlaneStore,
    audit: AuditRecorder = AuditRecorder.noop
):

  // Mirror the alias used by the sibling handler files.
  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]

  private val ValidScopeKinds = Set("tenantdb", "schema", "table")
  private val ValidOperations = Set("flush", "expire", "merge", "rewrite", "cleanup", "orphans")

  private def resolveTenantId(raw: String): Option[String] =
    sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id)

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  /** Resolve the tenant + tenant-db + scope gate shared by every operation. Left = ready-made
    * rejection; Right = (tenantId, tenantDbName).
    */
  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    resolveTenantId(rawTenant) match
      case None =>
        err(StatusCode.NotFound, "not_found", s"tenant '$rawTenant' is not registered")
      case Some(tid) =>
        TenantScopeCheck.reject(apiKey, tid)(scopeOf) match
          case Some(e) => Left(e)
          case None    =>
            sup.findTenantDb(tid, tenantDb) match
              case None =>
                err(StatusCode.NotFound, "not_found", s"tenant-db '$tenantDb' not found")
              case Some(td) if td.kind != TenantDbKind.DuckLake =>
                err(
                  StatusCode.BadRequest,
                  "invalid_kind",
                  "managed maintenance requires a ducklake tenant-db"
                )
              case Some(td) => Right((tid, td.name))

  /** Validate the scope fields + numeric/cron/operations rules pinned by the spec. Returns the
    * first violation as (errorCode, message), or None when the request is well-formed.
    */
  private def validateUpsert(
      req: MaintenancePolicyUpsertRequest
  ): Option[(String, String)] =
    if !ValidScopeKinds.contains(req.scopeKind) then
      Some(
        (
          "invalid_scope",
          s"scopeKind must be one of ${ValidScopeKinds.mkString(", ")}, got '${req.scopeKind}'"
        )
      )
    else if req.scopeKind == "schema" && req.scopeSchema.forall(_.trim.isEmpty) then
      Some(("invalid_scope", "scopeKind=schema requires scopeSchema"))
    else if req.scopeKind == "table" &&
      (req.scopeSchema.forall(_.trim.isEmpty) || req.scopeTable.forall(_.trim.isEmpty))
    then Some(("invalid_scope", "scopeKind=table requires scopeSchema and scopeTable"))
    else if req.retentionDays.exists(_ < 1) then
      Some(("invalid_value", "retentionDays must be >= 1"))
    else if req.smallFileMinCount.exists(_ < 1) then
      Some(("invalid_value", "smallFileMinCount must be >= 1"))
    else if req.cleanupGraceDays.exists(_ < 1) then
      Some(("invalid_value", "cleanupGraceDays must be >= 1"))
    else if req.orphanMinAgeDays.exists(_ < 1) then
      Some(("invalid_value", "orphanMinAgeDays must be >= 1"))
    else
      req.cron match
        case Some(c) =>
          CronExpr.parse(c) match
            case Left(msg) => Some(("invalid_cron", msg))
            case Right(_)  => None
        case None => None

  private def validateOperations(operations: Option[String]): Option[String] =
    operations.flatMap { csv =>
      val ops     = csv.split(",").map(_.trim).filter(_.nonEmpty).toList
      val unknown = ops.filterNot(ValidOperations.contains)
      if ops.isEmpty then Some("operations must not be empty")
      else if unknown.nonEmpty then
        Some(s"unknown operations: ${unknown.mkString(", ")} (allowed: ${ValidOperations
            .mkString(", ")})")
      else None
    }

  /** Manual-run scope: absent, exactly "tenantdb", or `table:<schema>.<table>` with non-empty
    * schema and table and exactly one dot. Anything else is rejected, because the runner's
    * parseScope treats an unrecognized scope as tenantdb and a typo like "table:noDot" would
    * silently escalate to a lake-wide expiry/cleanup.
    */
  private def validateRunScope(scope: Option[String]): Option[String] =
    scope.flatMap { raw =>
      val s = raw.trim
      if s == "tenantdb" then None
      else if s.startsWith("table:") then
        s.drop(6).split("\\.", -1) match
          case Array(schema, table) if schema.nonEmpty && table.nonEmpty => None
          case _                                                         =>
            Some(
              s"invalid scope '$raw': table scope must be 'table:<schema>.<table>' with " +
                "non-empty schema and table and exactly one dot"
            )
      else
        Some(
          s"invalid scope '$raw': accepted forms are 'tenantdb' (or omit the field) and " +
            "'table:<schema>.<table>'"
        )
    }

  private def toEntry(p: MaintenancePolicy): MaintenancePolicyEntry =
    MaintenancePolicyEntry(
      id = p.id,
      tenant = p.tenant,
      tenantDb = p.tenantDb,
      scopeKind = p.scopeKind,
      scopeSchema = p.scopeSchema,
      scopeTable = p.scopeTable,
      enabled = p.enabled,
      retentionDays = p.retentionDays,
      compactionEnabled = p.compactionEnabled,
      targetFileSize = p.targetFileSize,
      smallFileMinCount = p.smallFileMinCount,
      rewriteDeleteThreshold = p.rewriteDeleteThreshold,
      cleanupGraceDays = p.cleanupGraceDays,
      orphanMinAgeDays = p.orphanMinAgeDays,
      cron = p.cron,
      updatedAt = p.updatedAt
    )

  def upsertPolicy(req: MaintenancePolicyUpsertRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[MaintenancePolicyEntry] = IO.blocking {
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.MaintenancePolicyUpsert, "denied")
        Left(e)
      case Right((tid, db)) =>
        validateUpsert(req) match
          case Some((code, msg)) => err(StatusCode.BadRequest, code, msg)
          case None              =>
            val policy = MaintenancePolicy(
              id = Names.newSurrogateId("mpol"),
              tenant = tid,
              tenantDb = db,
              scopeKind = req.scopeKind,
              scopeSchema = req.scopeSchema,
              scopeTable = req.scopeTable,
              enabled = req.enabled,
              retentionDays = req.retentionDays,
              compactionEnabled = req.compactionEnabled,
              targetFileSize = req.targetFileSize,
              smallFileMinCount = req.smallFileMinCount,
              rewriteDeleteThreshold = req.rewriteDeleteThreshold,
              cleanupGraceDays = req.cleanupGraceDays,
              orphanMinAgeDays = req.orphanMinAgeDays,
              cron = req.cron
            )
            val saved = store.upsertMaintenancePolicy(policy)
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.MaintenancePolicyUpsert,
              "ok",
              tenant = Some(tid),
              target = Some(s"$db/${saved.scopeKind}"),
              detail = Map("scopeKind" -> saved.scopeKind)
            )
            Right(toEntry(saved))
  }

  def deletePolicy(req: MaintenancePolicyDeleteRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] = IO.blocking {
    store.findMaintenancePolicy(req.id) match
      case None =>
        err(StatusCode.NotFound, "not_found", s"policy '${req.id}' not found")
      case Some(p) =>
        TenantScopeCheck.reject(apiKey, p.tenant)(scopeOf) match
          case Some(e) =>
            audit.rest(apiKey, "control-plane", AuditActions.MaintenancePolicyDelete, "denied")
            Left(e)
          case None =>
            store.deleteMaintenancePolicy(req.id)
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.MaintenancePolicyDelete,
              "ok",
              tenant = Some(p.tenant),
              target = Some(s"${p.tenantDb}/${p.scopeKind}")
            )
            Right(())
  }

  def listPolicies(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[MaintenancePolicyListResponse] = IO.blocking {
    gate(rawTenant, tenantDb, apiKey)(scopeOf).map { case (tid, db) =>
      val rows      = store.listMaintenancePolicies(tid, db)
      val effective = PolicyMath.effective(rows, None, None)
      MaintenancePolicyListResponse(
        rows = rows.map(toEntry),
        effective = MaintenanceEffectiveEntry(
          enabled = effective.enabled,
          retentionDays = effective.retentionDays,
          compactionEnabled = effective.compactionEnabled,
          targetFileSize = effective.targetFileSize,
          smallFileMinCount = effective.smallFileMinCount,
          rewriteDeleteThreshold = effective.rewriteDeleteThreshold,
          cleanupGraceDays = effective.cleanupGraceDays,
          orphanMinAgeDays = effective.orphanMinAgeDays,
          cron = effective.cron
        )
      )
    }
  }

  def listRuns(
      rawTenant: String,
      tenantDb: String,
      limit: Option[Int],
      before: Option[Long],
      apiKey: Option[String]
  )(
      scopeOf: String => Option[SessionScope]
  ): Out[List[MaintenanceRunEntry]] = IO.blocking {
    gate(rawTenant, tenantDb, apiKey)(scopeOf).map { case (tid, db) =>
      store
        .listMaintenanceRuns(tid, db, limit.getOrElse(50).max(1).min(500), before)
        .map { r =>
          MaintenanceRunEntry(
            id = r.id,
            tenant = r.tenant,
            tenantDb = r.tenantDb,
            scope = r.scope,
            trigger = r.trigger,
            operations = r.operations,
            status = r.status,
            queuedAt = r.queuedAt,
            startedAt = r.startedAt,
            finishedAt = r.finishedAt,
            heartbeatAt = r.heartbeatAt,
            nodeId = r.nodeId,
            snapshotsExpired = r.counters.snapshotsExpired,
            snapshotsSkippedPinned = r.counters.snapshotsSkippedPinned,
            filesMerged = r.counters.filesMerged,
            filesRewritten = r.counters.filesRewritten,
            filesCleaned = r.counters.filesCleaned,
            orphansDeleted = r.counters.orphansDeleted,
            bytesReclaimed = r.counters.bytesReclaimed,
            error = r.error
          )
        }
    }
  }

  def triggerRun(req: MaintenanceRunRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[MaintenanceRunResponse] = IO.blocking {
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.MaintenanceRunManual, "denied")
        Left(e)
      case Right((tid, db)) =>
        validateRunScope(req.scope) match
          case Some(msg) => err(StatusCode.BadRequest, "invalid_scope", msg)
          case None      =>
            validateOperations(req.operations) match
              case Some(msg) => err(StatusCode.BadRequest, "invalid_operations", msg)
              case None      =>
                val run = store.enqueueMaintenanceRun(
                  tid,
                  db,
                  req.scope.map(_.trim).getOrElse("tenantdb"),
                  "manual",
                  req.operations
                )
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.MaintenanceRunManual,
                  "ok",
                  tenant = Some(tid),
                  target = Some(s"$db/${run.scope}"),
                  detail = Map("operations" -> req.operations.getOrElse("all"))
                )
                Right(MaintenanceRunResponse(run.id))
  }
