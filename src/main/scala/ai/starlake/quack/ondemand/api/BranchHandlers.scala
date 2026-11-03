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
import io.circe.Json
import sttp.model.StatusCode

/** REST handlers for branches (Epic 1). Gate = [[TenantDbGate]] on the PARENT tenant-db (tenant
  * resolve, [[TenantScopeCheck]], DuckLake kind), then everything else is the service's business
  * rules. The gate admits only a superuser or an admin of the tenant, so `BranchService.mayUse`'s
  * non-admin arm is not reached from here. The actor is what `actorOf` resolves: the session's
  * username (tenant admin or superuser) or a PAT's owner (an MCP PAT curries its bearer as
  * `apiKey`); no credential, or any other token, is a 401 before the service is reached (see
  * [[BranchHandlers.actorResolver]]). The data diff delegates to [[CatalogPreviewHandlers]], whose
  * [[RestCaller]] resolves the executor identity.
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
    gatedActor(tenant, tenantDb, apiKey)(scopeOf).flatMap { case (tid, db, actor) =>
      service.get(tid, db, branch).left.map(toErr).map { case (b, merges) =>
        val systemCounts = scopeOf(apiKey).superuser
        BranchDetailResponse(
          entry(b),
          merges.map(BranchHandlers.mergeEntry(_, systemCounts, actor))
        )
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
                  BranchHandlers.changesResponse(
                    b,
                    cs,
                    proposal,
                    actor,
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
    gatedActor(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e)                 => IO.pure(Left(e))
      case Right((tid, db, actor)) =>
        bounds(tid, db, branch) match
          case Left(e)              => IO.pure(Left(e))
          case Right((b, from, to)) =>
            preview.dataDiffHiding(
              tid,
              b.tenantDbName,
              schema,
              table,
              from,
              to,
              limit,
              cursor,
              changeType,
              apiKey,
              (s, t) => !actor.sees(s, t)
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
    gatedActor(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e)                 => IO.pure(Left(e))
      case Right((tid, db, actor)) =>
        bounds(tid, db, branch) match
          case Left(e)              => IO.pure(Left(e))
          case Right((b, from, to)) =>
            preview.schemaDiffHiding(
              tid,
              b.tenantDbName,
              schema,
              table,
              from,
              to,
              apiKey,
              (s, t) => !actor.sees(s, t)
            )(scopeOf)

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
              BranchHandlers.mergeEntry(m, systemCounts, actor),
              BranchHandlers.changesResponse(b, cs, Some(m), actor, t => systemCounts || t.counted)
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
              BranchHandlers.mergeEntry(m, superuser, actor),
              BranchHandlers.changesResponse(b, cs, Some(m), actor, _ => superuser)
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

  /** One table's stored row numbers, for a viewer who may see them. */
  private def rowNumbers(c: RowCounts): BranchRowConflicts =
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

  /** One table's stored row numbers for an admin who may not see them: they learn the table has
    * conflicts, not how many.
    */
  private def flaggedOnly(c: RowCounts): BranchRowConflicts =
    BranchRowConflicts(c.schema, c.table, c.conflicts > 0L, countsUnavailable = true)

  /** The `schema` and `table` of a stored entry of a summary or a conflict list, when it has both.
    */
  private def nameOf(entry: Json): Option[(String, String)] =
    for
      schema <- entry.hcursor.get[String]("schema").toOption
      table  <- entry.hcursor.get[String]("table").toOption
    yield (schema, table)

  /** The entries of a stored list that `actor` sees. Anything else, an entry with no name or a list
    * that is no list, shows nothing.
    */
  private def entriesSeen(actor: BranchActor, list: Option[Json]): Vector[Json] =
    list
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .filter(e => nameOf(e).exists((schema, table) => actor.sees(schema, table)))

  /** A merge row as the wire shows it. Its stored summary and row numbers were produced as the
    * system caller (the lifecycle's record, `BranchService.propose`), so they are shown only to a
    * superuser (`systemCounts`): every other admin is judged by the nodes on their grants like any
    * tenant user (Phase D), so they get the counts zeroed and flagged `countsUnavailable` and ask
    * `changes` for the ones they may read. A non-admin gets those counts too, no row numbers at all
    * (see [[changesResponse]]) and only what [[BranchActor.sees]] lets them: the summary's tables,
    * none of its raw verbs (they can name views and macros), and the conflicts and keys of those
    * tables. `error` stays as recorded, though refusal text can name a table of the change set.
    */
  private[api] def mergeEntry(
      m: BranchMerge,
      systemCounts: Boolean,
      actor: BranchActor
  ): BranchMergeEntry =
    val summary = io.circe.parser.parse(m.summaryJson).getOrElse(Json.Null)
    val shown   = if systemCounts then summary else withoutCounts(summary)
    // What the proposal's id pins, for the approver to read. Unreadable keys: the merge refuses them.
    val keys                = BranchService.keysOf(m.mergeKeysJson).getOrElse(Map.empty)
    val conflicts           = io.circe.parser.parse(m.conflictsJson).getOrElse(Json.Null)
    def seenTables(s: Json) = entriesSeen(actor, s.hcursor.downField("tables").focus)
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
      summary =
        if actor.isAdmin then shown
        else
          shown.mapObject(
            _.add("tables", Json.fromValues(seenTables(shown)))
              .add("unsupported", Json.arr())
          )
      ,
      conflicts =
        if actor.isAdmin then conflicts
        else Json.fromValues(entriesSeen(actor, Some(conflicts))),
      createdAt = m.createdAt,
      decidedAt = m.decidedAt,
      onConflict = m.onConflict,
      holdWriters = m.holdWriters,
      keys =
        if actor.isAdmin then keys
        else
          // A key's `schema.table` is matched against the summary's own names, since either part can
          // hold a dot: a key on no table the viewer sees does not show.
          val named = seenTables(summary).flatMap(nameOf).map((s, t) => s"$s.$t").toSet
          keys.filter((table, _) => named(table))
      ,
      attempts = m.attempts,
      // Unreadable counts show no numbers; the merge itself refuses them.
      rowConflicts =
        if !actor.isAdmin then Nil
        else
          RowCounts
            .listFromJson(m.rowConflictsJson)
            .getOrElse(Nil)
            .map(c => if systemCounts then rowNumbers(c) else flaggedOnly(c))
    )

  /** `proposal`: whose row numbers to attach. `mayCount`: whether the viewer may see a table's.
    *
    * A proposal's numbers come from Decide, which compares as the system principal, so they show
    * only to a viewer who may count the table. An admin who may not gets the flag alone: they can
    * lift their own mask, so it tells them nothing new. A non-admin who may not gets no entry at
    * all: with a write grant and a mask or a row policy, `hasConflicts` is an equality oracle on
    * what main wrote since the fork, through a key on the column or an UPDATE of a cell main also
    * changed. A non-admin who may count holds no policy at all, since a policy holder's count is
    * refused on every table, so the numbers tell them nothing they cannot read.
    *
    * A non-admin's lists name only the tables they see, and `otherTables` counts the rest, with the
    * raw verbs of `unsupported`, which can name views and macros. `mergeable` is the whole
    * branch's.
    */
  private[api] def changesResponse(
      b: Branch,
      cs: BranchChangeSet,
      proposal: Option[BranchMerge],
      actor: BranchActor,
      mayCount: TableChange => Boolean
  ): BranchChangesResponse =
    val rows =
      proposal.toList.flatMap(m => RowCounts.listFromJson(m.rowConflictsJson).getOrElse(Nil))
    val named =
      cs.tables.map(t => (t.schema, t.table)) ++ cs.conflicts.map(c => (c.schema, c.table))
    val others = named.distinct.count((schema, table) => !actor.sees(schema, table))
    BranchChangesResponse(
      branch = b.name,
      forkSnapshot = cs.forkSnapshot,
      headSnapshot = cs.headSnapshot,
      mainSnapshot = cs.mainSnapshot,
      tables = cs.tables
        .filter(actor.seesTable)
        .map(t =>
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
              .flatMap(c =>
                if mayCount(t) then Some(rowNumbers(c))
                else if actor.isAdmin then Some(flaggedOnly(c))
                else None
              )
          )
        ),
      conflicts = cs.conflicts
        .filter(c => actor.sees(c.schema, c.table))
        .map(c => BranchConflictEntry(c.schema, c.table, c.reason)),
      unsupported = if actor.isAdmin then cs.unsupported else Nil,
      mergeable = cs.mergeable,
      otherTables = if actor.isAdmin then 0 else others + cs.unsupported.size
    )

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
          if s.scope.superuser || s.scope.manageableTenants.nonEmpty then
            BranchActor.admin(s.profile.username)
          else BranchActor.plain(s.profile.username, sees = (_, _) => false)
        case RestCaller.Principal.Pat(p) =>
          val branchOnly = p.restriction.branchOnly
          if p.isAdmin then BranchActor.admin(p.user.username, branchOnly)
          else BranchActor.plain(p.user.username, sees = (_, _) => false, branchOnly)
      }
