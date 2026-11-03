package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{Branch, BranchMerge}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.branch.{
  BranchActor,
  BranchChangeSet,
  BranchFailure,
  BranchService,
  ProposeOptions,
  RowCounts,
  TableChange
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
    /** Who the change counts run as: the requester, resolved like every other data endpoint. */
    callerOf: RestCaller,
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
    )(scopeOf).flatMap { (tid, db) =>
      // A token's databases axis on the PARENT (review 5 R2): every branch endpoint, REST and MCP.
      callerOf.resolve(apiKey).toOption.map(RestCaller.callerOf("branch-scope", _)) match
        case Some(caller) =>
          TokenScope.databaseRefusal(sup, tid, caller.restriction, db).toLeft((tid, db))
        case None => Right((tid, db))
    }

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

  /** One table's stored row numbers as the viewer may see them. Decide ran as the system principal,
    * so the numbers show only where the viewer's own count of the table succeeded: otherwise they
    * learn the table has conflicts, not how many.
    */
  private def rowConflicts(c: RowCounts, visible: Boolean): BranchRowConflicts =
    if visible then
      BranchRowConflicts(
        schema = c.schema,
        table = c.table,
        hasConflicts = c.conflicts > 0L,
        toUpdate = c.toUpdate,
        toDelete = c.toDelete,
        toInsert = c.toInsert,
        cellConflicts = c.cellConflicts,
        updateDeleteConflicts = c.updateDeleteConflicts,
        deleteUpdateConflicts = c.deleteUpdateConflicts,
        keyMatches = c.keyMatches,
        mainRowsByKey = c.mainRowsByKey,
        columnConflicts = c.columnConflicts,
        samples = c.samples
      )
    else BranchRowConflicts(c.schema, c.table, c.conflicts > 0L, countsUnavailable = true)

  /** A merge row as the wire shows it. Its stored summary and row numbers were produced as the
    * system caller (the lifecycle's record, `BranchService.propose`), so they are shown only to a
    * superuser (`systemCounts`): everyone else reaching here is a tenant admin, whom the nodes
    * judge by their grants like any tenant user (Phase D), so they get the counts zeroed and
    * flagged `countsUnavailable` and ask `changes` for the ones they may read.
    */
  private def mergeEntry(m: BranchMerge, systemCounts: Boolean): BranchMergeEntry =
    val summary = io.circe.parser.parse(m.summaryJson).getOrElse(io.circe.Json.Null)
    BranchMergeEntry(
      id = m.id,
      status = m.status.wire,
      proposer = m.proposer,
      approver = m.approver,
      mainSnapshotAtPropose = m.mainSnapshotAtPropose,
      mainSnapshotAfter = m.mainSnapshotAfter,
      tagName = m.tagName,
      // rowConflicts below is empty when the counts cannot be read, which would read as no conflicts.
      error = m.error.orElse(RowCounts.listFromJson(m.rowConflictsJson).left.toOption),
      summary = if systemCounts then summary else BranchHandlers.withoutCounts(summary),
      conflicts = io.circe.parser.parse(m.conflictsJson).getOrElse(io.circe.Json.Null),
      createdAt = m.createdAt,
      decidedAt = m.decidedAt,
      onConflict = m.onConflict,
      holdWriters = m.holdWriters,
      // What the proposal's id pins, for the approver to read. Unreadable keys: the merge refuses them.
      keys = BranchService.keysOf(m.mergeKeysJson).getOrElse(Map.empty),
      attempts = m.attempts,
      // Unreadable counts show no numbers; the merge itself refuses them.
      rowConflicts =
        RowCounts.listFromJson(m.rowConflictsJson).getOrElse(Nil).map(rowConflicts(_, systemCounts))
    )

  /** `proposal`: whose row numbers to attach. `visible`: whether the viewer may see a table's. */
  private def changesResponse(
      b: Branch,
      cs: BranchChangeSet,
      proposal: Option[BranchMerge],
      visible: TableChange => Boolean
  ): BranchChangesResponse =
    val rows =
      proposal.toList.flatMap(m => RowCounts.listFromJson(m.rowConflictsJson).getOrElse(Nil))
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
          reason = t.reason,
          countsUnavailable = t.countsUnavailable,
          threeWay = t.threeWay,
          rowConflicts = rows
            .find(c => c.schema == t.schema && c.table == t.table)
            .map(rowConflicts(_, visible(t)))
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
        val systemCounts = scopeOf(apiKey).superuser
        BranchDetailResponse(entry(b), merges.map(mergeEntry(_, systemCounts)))
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
    // Counts run as the requester (a session or, over MCP, the PAT curried as apiKey), resolved
    // like the data diff's: the node decides which tables they may count, never the system caller.
    (for
      (tid, db, actor) <- gatedActor(tenant, tenantDb, apiKey)(scopeOf)
      countsAs         <-
        if counts.getOrElse(true) then callerOf(s"branch-changes-$tid-$db", apiKey).map(Some(_))
        else Right(None)
    yield (tid, db, actor, countsAs)) match
      case Left(e)                           => IO.pure(Left(e))
      case Right((tid, db, actor, countsAs)) =>
        val countsAsked = countsAs.nonEmpty
        val superuser   = scopeOf(apiKey).superuser
        service
          .changes(tid, db, branch, countsAs, actor, apiKey)
          .flatMap {
            case Left(f)        => IO.pure(Left(toErr(f)))
            case Right((b, cs)) =>
              IO.blocking(service.proposalOf(b)).map { proposal =>
                Right(
                  changesResponse(
                    b,
                    cs,
                    proposal,
                    t => superuser || (countsAsked && t.counted)
                  )
                )
              }
          }

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

  def propose(req: BranchProposeRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[BranchProposeResponse] =
    // The reply's change set counts as the proposer (resolved like `changes`); the stored summary
    // keeps the system's counts, shown to superusers only (mergeEntry).
    (for
      (tid, db, actor) <- gatedActor(req.tenant, req.tenantDb, apiKey)(scopeOf)
      countsAs         <- callerOf(s"branch-propose-$tid-$db", apiKey)
      options          <- ProposeOptions.parse(req.onConflict, req.keys).left.map(toErr)
    yield (tid, db, actor, countsAs, options)) match
      case Left(e)                                    => IO.pure(Left(e))
      case Right((tid, db, actor, countsAs, options)) =>
        val systemCounts = scopeOf(apiKey).superuser
        service
          .propose(tid, db, req.branch, options, actor, countsAs, apiKey)
          .map(_.left.map(toErr).map { case (b, m, cs) =>
            BranchProposeResponse(
              entry(b),
              mergeEntry(m, systemCounts),
              changesResponse(b, cs, Some(m), t => systemCounts || t.counted)
            )
          })

  def merge(req: BranchMergeRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[BranchMergeResponse] =
    gatedActor(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e)                 => IO.pure(Left(e))
      case Right((tid, db, actor)) =>
        val superuser = scopeOf(apiKey).superuser
        service
          .merge(
            tid,
            db,
            req.branch,
            req.proposalId,
            req.expectedMainSnapshot,
            req.holdWriters,
            actor,
            apiKey
          )
          .map(_.left.map(toErr).map { case (b, m, cs) =>
            BranchMergeResponse(
              entry(b),
              mergeEntry(m, superuser),
              changesResponse(b, cs, Some(m), _ => superuser)
            )
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

  /** A stored merge summary with every table's counts zeroed and flagged `countsUnavailable`, the
    * shape a refused count has in a change set.
    */
  def withoutCounts(summary: io.circe.Json): io.circe.Json =
    summary.hcursor
      .downField("tables")
      .withFocus(
        _.mapArray(
          _.map(
            _.mapObject(t =>
              t.add("inserted", io.circe.Json.fromLong(0L))
                .add("deleted", io.circe.Json.fromLong(0L))
                .add("updated", io.circe.Json.fromLong(0L))
                .add("countsUnavailable", io.circe.Json.True)
            )
          )
        )
      )
      .top
      .getOrElse(summary)

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
