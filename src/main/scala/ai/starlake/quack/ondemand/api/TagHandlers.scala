package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{Names, SnapshotTag, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** Snapshot-tag CRUD (EPIC P2 / Spec 06). Every mutation is TenantScopeCheck-gated and audited;
  * protected-tag mutations use the dedicated hold actions so removing a retention hold is
  * distinctly visible in the audit log. Snapshot existence is checked through injected functions so
  * the handler is testable without a live DuckLake catalog.
  */
final class TagHandlers(
    sup: PoolSupervisor,
    store: ControlPlaneStore,
    snapshotExists: (String, String, Long) => Boolean,
    snapshotsExist: (String, String, Set[Long]) => Set[Long],
    audit: AuditRecorder = AuditRecorder.noop
):

  // Mirror the alias used by the sibling handler files.
  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]

  private def resolveTenantId(raw: String): Option[String] =
    sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id)

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  /** 1..128 chars after trim, not all digits (would be ambiguous with snapshot ids). */
  private def validateName(name: String): Option[String] =
    val n = name.trim
    if n.isEmpty || n.length > 128 then Some("tag name must be 1..128 characters")
    else if n.forall(_.isDigit) then Some("tag name must not be all digits")
    else None

  private def toEntry(t: SnapshotTag, exists: Boolean): CatalogTagEntry =
    CatalogTagEntry(t.name, t.snapshotId, t.isProtected, t.createdBy, t.createdAt, exists)

  /** Resolve the tenant + tenant-db + scope gate shared by all four operations. Left = ready-made
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
                  "snapshot tags require a ducklake tenant-db"
                )
              case Some(td) => Right((tid, td.name))

  def create(req: TagCreateRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[CatalogTagEntry] = IO.blocking {
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.TagCreate, "denied")
        Left(e)
      case Right((tid, db)) =>
        validateName(req.name) match
          case Some(msg) => err(StatusCode.BadRequest, "invalid_name", msg)
          case None      =>
            val name = req.name.trim
            if !snapshotExists(tid, db, req.snapshotId) then
              err(StatusCode.NotFound, "not_found", s"snapshot ${req.snapshotId} not found")
            else
              store.createSnapshotTag(
                SnapshotTag(
                  Names.newSurrogateId("stag"),
                  tid,
                  db,
                  name,
                  req.snapshotId,
                  isProtected = req.isProtected
                )
              ) match
                case Left(_) =>
                  err(StatusCode.Conflict, "duplicate", s"tag '$name' already exists")
                case Right(t) =>
                  val action =
                    if t.isProtected then AuditActions.TagHoldCreate else AuditActions.TagCreate
                  audit.rest(
                    apiKey,
                    "control-plane",
                    action,
                    "ok",
                    tenant = Some(tid),
                    target = Some(name),
                    detail = Map(
                      "snapshotId" -> t.snapshotId.toString,
                      "protected"  -> t.isProtected.toString
                    )
                  )
                  Right(toEntry(t, exists = true))
  }

  def delete(req: TagDeleteRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] = IO.blocking {
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.TagDelete, "denied")
        Left(e)
      case Right((tid, db)) =>
        store.deleteSnapshotTag(tid, db, req.name.trim) match
          case None    => err(StatusCode.NotFound, "not_found", s"tag '${req.name}' not found")
          case Some(t) =>
            val action =
              if t.isProtected then AuditActions.TagHoldRemove else AuditActions.TagDelete
            audit.rest(
              apiKey,
              "control-plane",
              action,
              "ok",
              tenant = Some(tid),
              target = Some(t.name),
              detail = Map(
                "snapshotId" -> t.snapshotId.toString,
                "protected"  -> t.isProtected.toString
              )
            )
            Right(())
  }

  def protect(req: TagProtectRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[CatalogTagEntry] = IO.blocking {
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.TagHoldCreate, "denied")
        Left(e)
      case Right((tid, db)) =>
        store.setSnapshotTagProtected(tid, db, req.name.trim, req.isProtected) match
          case None    => err(StatusCode.NotFound, "not_found", s"tag '${req.name}' not found")
          case Some(t) =>
            val action =
              if req.isProtected then AuditActions.TagHoldCreate else AuditActions.TagHoldRemove
            audit.rest(
              apiKey,
              "control-plane",
              action,
              "ok",
              tenant = Some(tid),
              target = Some(t.name),
              detail = Map(
                "snapshotId" -> t.snapshotId.toString,
                "protected"  -> t.isProtected.toString
              )
            )
            Right(toEntry(t, snapshotExists(tid, db, t.snapshotId)))
  }

  def list(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[List[CatalogTagEntry]] = IO.blocking {
    gate(rawTenant, tenantDb, apiKey)(scopeOf).map { case (tid, db) =>
      val tags     = store.listSnapshotTags(tid, db)
      val existing = snapshotsExist(tid, db, tags.map(_.snapshotId).toSet)
      tags.map(t => toEntry(t, existing.contains(t.snapshotId)))
    }
  }

  /** Shared AS OF resolution for the catalog table endpoint: at most one of asOf / asOfTag; a tag
    * resolves to its snapshot id. NOT scope-gated: the catalog GET surface is gated by the caller
    * (ManagerServer wiring passes the same authToken guard as the tags list).
    */
  def resolveAsOf(
      rawTenant: String,
      tenantDb: String,
      asOf: Option[Long],
      asOfTag: Option[String]
  ): Either[(StatusCode, String), Option[Long]] =
    (asOf, asOfTag) match
      case (Some(_), Some(_)) =>
        Left((StatusCode.BadRequest, "supply either asOf or asOfTag, not both"))
      case (None, Some(tag)) =>
        val tid = resolveTenantId(rawTenant).getOrElse(rawTenant)
        store.findSnapshotTag(tid, tenantDb, tag.trim) match
          case Some(t) => Right(Some(t.snapshotId))
          case None    => Left((StatusCode.NotFound, s"tag '$tag' not found"))
      case (some, None) => Right(some)
