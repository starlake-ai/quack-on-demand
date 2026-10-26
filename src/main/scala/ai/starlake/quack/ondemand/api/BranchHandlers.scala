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
  * rules. The actor is what `actorOf` resolves: the session's username (tenant admin or superuser)
  * or a PAT's owner (an MCP data-tier PAT curries its bearer as `apiKey` and arrives here as a
  * non-admin actor whose `mayUse` gate is the handshake); no credential, or any other token, is a
  * 401 before the service is reached (see [[BranchHandlers.actorResolver]]). The data diff
  * delegates to [[CatalogPreviewHandlers]], whose [[RestCaller]] resolves the executor identity.
  */
final class BranchHandlers(
    sup: PoolSupervisor,
    service: BranchService,
    preview: CatalogPreviewHandlers,
    actorOf: Option[String] => Either[(StatusCode, ErrorResponse), BranchActor],
    audit: AuditRecorder = AuditRecorder.noop
):

  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]

  private def toErr(f: BranchFailure): (StatusCode, ErrorResponse) =
    (StatusCode(f.status), ErrorResponse(f.code, f.message))

  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    TenantDbGate(
      sup,
      rawTenant,
      tenantDb,
      apiKey,
      requireDuckLake = Some("branching requires a ducklake tenant-db")
    )(scopeOf)

  /** [[gate]], then the actor: an unresolvable token is a 401 and never reaches the service. */
  private def gatedActor(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Either[(StatusCode, ErrorResponse), (String, String, BranchActor)] =
    for
      (tid, db) <- gate(rawTenant, tenantDb, apiKey)(scopeOf)
      actor     <- actorOf(apiKey)
    yield (tid, db, actor)

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
      scopeOf: SessionScope.Resolver
  ): Out[BranchEntry] =
    gatedActor(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e)                 => IO.pure(Left(e))
      case Right((tid, db, actor)) =>
        service
          .create(tid, db, req.name, req.ttlHours, req.fromSnapshot, actor, apiKey)
          .map(_.left.map(toErr).map(entry))

  def list(
      tenant: String,
      tenantDb: String,
      includeTerminal: Option[Boolean],
      apiKey: Option[String]
  )(
      scopeOf: SessionScope.Resolver
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
      scopeOf: SessionScope.Resolver
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
  )(scopeOf: SessionScope.Resolver): Out[BranchChangesResponse] =
    gatedActor(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e)                 => IO.pure(Left(e))
      case Right((tid, db, actor)) =>
        service
          .changes(tid, db, branch, counts.getOrElse(true), actor, apiKey)
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
  )(scopeOf: SessionScope.Resolver): Out[DataDiffResponse] =
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
  )(scopeOf: SessionScope.Resolver): Out[SchemaDiffResponse] =
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
      scopeOf: SessionScope.Resolver
  ): Out[BranchProposeResponse] =
    gatedActor(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e)                 => IO.pure(Left(e))
      case Right((tid, db, actor)) =>
        service
          .propose(tid, db, req.branch, actor, apiKey)
          .map(_.left.map(toErr).map { case (b, m, cs) =>
            BranchProposeResponse(entry(b), mergeEntry(m), changesResponse(b, cs))
          })

  def merge(req: BranchMergeRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[BranchMergeResponse] =
    gatedActor(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e)                 => IO.pure(Left(e))
      case Right((tid, db, actor)) =>
        service
          .merge(tid, db, req.branch, req.expectedMainSnapshot, actor, apiKey)
          .map(_.left.map(toErr).map { case (b, m, cs) =>
            BranchMergeResponse(entry(b), mergeEntry(m), changesResponse(b, cs))
          })

  def discard(req: BranchOpRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[BranchEntry] =
    gatedActor(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e)                 => IO.pure(Left(e))
      case Right((tid, db, actor)) =>
        service
          .discard(tid, db, req.branch, actor, apiKey)
          .map(_.left.map(toErr).map(entry))

object BranchHandlers:

  /** The branch actor a bearer resolves to, through [[RestCaller.resolve]] (the one resolution): a
    * session -> its user, admin when it manages a tenant; a PAT -> its owner, admin per the PAT,
    * carrying its `branchOnly` restriction (merge refuses it). No credential, or any other token
    * (unknown, expired, revoked, a session that died between the guard and the handler), is refused
    * with 401 `unauthorized`: never a superuser admin actor able to approve a merge.
    */
  def actorResolver(
      callers: RestCaller
  ): Option[String] => Either[(StatusCode, ErrorResponse), BranchActor] =
    apiKey =>
      callers.resolve(apiKey).map {
        case RestCaller.Principal.Session(s) =>
          BranchActor(
            s.profile.username,
            isAdmin = s.scope.superuser || s.scope.manageableTenants.nonEmpty
          )
        case RestCaller.Principal.Pat(p) =>
          BranchActor(p.user.username, isAdmin = p.isAdmin, branchOnly = p.restriction.branchOnly)
      }
