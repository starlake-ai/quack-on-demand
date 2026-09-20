package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{Branch, BranchMerge}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.branch.{
  BranchActor,
  BranchChangeSet,
  BranchFailure,
  BranchService
}
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for branches (Epic 1). Gate = [[TenantDbGate]] on the PARENT tenant-db (tenant
  * resolve, [[TenantScopeCheck]], DuckLake kind), then everything else is the service's business
  * rules. The actor is the session's username (tenant admin or superuser) or, for the static key
  * and the MCP PAT seam, the identity `identityOf` resolves: an MCP data-tier PAT curries its
  * bearer as `apiKey` and arrives here as a non-admin actor whose `mayUse` gate is the handshake.
  */
final class BranchHandlers(
    sup: PoolSupervisor,
    service: BranchService,
    preview: CatalogPreviewHandlers,
    sessions: String => Option[ai.starlake.quack.ondemand.api.SessionTokenStore.Session],
    actorOf: Option[String] => BranchActor,
    audit: AuditRecorder = AuditRecorder.noop
):

  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]

  private def toErr(f: BranchFailure): (StatusCode, ErrorResponse) =
    (StatusCode(f.status), ErrorResponse(f.code, f.message))

  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    TenantDbGate(
      sup,
      rawTenant,
      tenantDb,
      apiKey,
      requireDuckLake = Some("branching requires a ducklake tenant-db")
    )(scopeOf)

  private def entry(b: Branch): BranchEntry =
    BranchEntry(
      id = b.id,
      tenant = b.tenant,
      database = b.parentDbName,
      name = b.name,
      status = b.status.wire,
      forkSnapshot = b.forkSnapshot,
      owner = b.ownerUser,
      pool = b.poolName,
      catalogDb = b.tenantDbName,
      expiresAt = b.expiresAt,
      createdAt = b.createdAt,
      updatedAt = b.updatedAt
    )

  private def mergeEntry(m: BranchMerge): BranchMergeEntry =
    BranchMergeEntry(
      id = m.id,
      status = m.status.wire,
      proposer = m.proposer,
      approver = m.approver,
      mainSnapshotAtPropose = m.mainSnapshotAtPropose,
      mainSnapshotAfter = m.mainSnapshotAfter,
      tagName = m.tagName,
      error = m.error,
      summary = io.circe.parser.parse(m.summaryJson).getOrElse(io.circe.Json.Null),
      conflicts = io.circe.parser.parse(m.conflictsJson).getOrElse(io.circe.Json.Null),
      createdAt = m.createdAt,
      decidedAt = m.decidedAt
    )

  private def changesResponse(b: Branch, cs: BranchChangeSet): BranchChangesResponse =
    BranchChangesResponse(
      branch = b.name,
      forkSnapshot = cs.forkSnapshot,
      headSnapshot = cs.headSnapshot,
      mainSnapshot = cs.mainSnapshot,
      tables = cs.tables.map(t =>
        BranchTableChange(
          schema = t.schema,
          table = t.table,
          kind = t.kind.wire,
          inserted = t.inserted,
          deleted = t.deleted,
          updated = t.updated,
          mergeable = t.mergeable,
          reason = t.reason
        )
      ),
      conflicts = cs.conflicts.map(c => BranchConflictEntry(c.schema, c.table, c.reason)),
      unsupported = cs.unsupported,
      mergeable = cs.mergeable
    )

  def create(req: BranchCreateRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[BranchEntry] =
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e)          => IO.pure(Left(e))
      case Right((tid, db)) =>
        service
          .create(tid, db, req.name, req.ttlHours, req.fromSnapshot, actorOf(apiKey), apiKey)
          .map(_.left.map(toErr).map(entry))

  def list(
      tenant: String,
      tenantDb: String,
      includeTerminal: Option[Boolean],
      apiKey: Option[String]
  )(
      scopeOf: String => Option[SessionScope]
  ): Out[BranchListResponse] = IO.blocking {
    gate(tenant, tenantDb, apiKey)(scopeOf).flatMap { case (tid, db) =>
      service
        .list(tid, db, includeTerminal.getOrElse(false))
        .left
        .map(toErr)
        .map(bs => BranchListResponse(bs.map(entry)))
    }
  }

  def get(tenant: String, tenantDb: String, branch: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[BranchDetailResponse] = IO.blocking {
    gate(tenant, tenantDb, apiKey)(scopeOf).flatMap { case (tid, db) =>
      service.get(tid, db, branch).left.map(toErr).map { case (b, merges) =>
        BranchDetailResponse(entry(b), merges.map(mergeEntry))
      }
    }
  }

  def changes(
      tenant: String,
      tenantDb: String,
      branch: String,
      counts: Option[Boolean],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[BranchChangesResponse] =
    gate(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e)          => IO.pure(Left(e))
      case Right((tid, db)) =>
        service
          .changes(tid, db, branch, counts.getOrElse(true), actorOf(apiKey), apiKey)
          .map(_.left.map(toErr).map { case (b, cs) => changesResponse(b, cs) })

  /** Delegates to the catalog data-diff on the BRANCH tenant-db over `(fork, head]`. */
  def diff(
      tenant: String,
      tenantDb: String,
      branch: String,
      schema: String,
      table: String,
      limit: Option[Int],
      cursor: Option[String],
      changeType: Option[String],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[DataDiffResponse] =
    gate(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e)          => IO.pure(Left(e))
      case Right((tid, db)) =>
        bounds(tid, db, branch) match
          case Left(e)              => IO.pure(Left(e))
          case Right((b, from, to)) =>
            preview.dataDiff(
              tid,
              b.tenantDbName,
              schema,
              table,
              from,
              to,
              limit,
              cursor,
              changeType,
              apiKey
            )(
              scopeOf
            )

  def schemaDiff(
      tenant: String,
      tenantDb: String,
      branch: String,
      schema: String,
      table: String,
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[SchemaDiffResponse] =
    gate(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e)          => IO.pure(Left(e))
      case Right((tid, db)) =>
        bounds(tid, db, branch) match
          case Left(e)              => IO.pure(Left(e))
          case Right((b, from, to)) =>
            preview.schemaDiff(tid, b.tenantDbName, schema, table, from, to, apiKey)(scopeOf)

  /** `(branch, fork, head)` as the numeric bound strings the catalog diff handlers parse. */
  private def bounds(
      tid: String,
      db: String,
      branch: String
  ): Either[(StatusCode, ErrorResponse), (Branch, String, String)] =
    service.resolveTarget(tid, db, branch).left.map(toErr).flatMap { case (b, _) =>
      service.headSnapshot(tid, b) match
        case Some(head) => Right((b, b.forkSnapshot.toString, head.toString))
        case None       =>
          Left(
            (StatusCode.BadGateway, ErrorResponse("diff_failed", "branch catalog has no snapshots"))
          )
    }

  def propose(req: BranchOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[BranchProposeResponse] =
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e)          => IO.pure(Left(e))
      case Right((tid, db)) =>
        service
          .propose(tid, db, req.branch, actorOf(apiKey), apiKey)
          .map(_.left.map(toErr).map { case (b, m, cs) =>
            BranchProposeResponse(entry(b), mergeEntry(m), changesResponse(b, cs))
          })

  def merge(req: BranchMergeRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[BranchMergeResponse] =
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e)          => IO.pure(Left(e))
      case Right((tid, db)) =>
        service
          .merge(tid, db, req.branch, req.expectedMainSnapshot, actorOf(apiKey), apiKey)
          .map(_.left.map(toErr).map { case (b, m, cs) =>
            BranchMergeResponse(entry(b), mergeEntry(m), changesResponse(b, cs))
          })

  def discard(req: BranchOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[BranchEntry] =
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e)          => IO.pure(Left(e))
      case Right((tid, db)) =>
        service
          .discard(tid, db, req.branch, actorOf(apiKey), apiKey)
          .map(_.left.map(toErr).map(entry))
