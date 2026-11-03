package ai.starlake.quack.ondemand.api

import ai.starlake.acl.parser.Verb
import ai.starlake.quack.edge.sql.GrantMatch
import ai.starlake.quack.model.{Branch, BranchMerge, TenantDb}
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
import ai.starlake.quack.ondemand.rbac.{EffectiveSet, HandshakeDenial}
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import cats.effect.IO
import io.circe.Json
import sttp.model.StatusCode

/** REST handlers for branches (Epic 1). Any principal who may connect to one of the parent's pools
  * may branch it, in its own tenant; every endpoint passes [[gate]], the one place that says so,
  * and everything after it is the service's business rules. Who gets in:
  *
  *   - A superuser, or an admin of the tenant in hand, as for every tenant-db surface
  *     ([[TenantDbGate]]). An admin of another tenant who is a plain member here is a plain member
  *     here: [[BranchActor.isAdmin]] is decided for this tenant, and only here.
  *   - Anyone else, on the tenant they belong to only: the handshake resolves a user NAME per
  *     tenant, and in another tenant the same name can be another user. Then the handshake's own
  *     decision, the one the routed executor asks for this caller ([[ExecCaller.authzRequest]]), on
  *     the pools the token's `databases` and `pools` axes allow, as `ProfileHandlers.pools` does.
  *     An unavailable decision (the tenant's OPA down) is a 503, never a refusal.
  *
  * A non-admin's replies then name only the tables they hold a grant on ([[BranchActor.sees]]), and
  * carry no row numbers of a table they may not count (`BranchHandlers.changesResponse`). Merge is
  * the service's to refuse them: `admin_required`. No credential, or any other token, is a 401
  * before anything else. The data diffs delegate to [[CatalogPreviewHandlers]], whose
  * [[RestCaller]] resolves the executor identity.
  */
final class BranchHandlers(
    sup: PoolSupervisor,
    service: BranchService,
    preview: CatalogPreviewHandlers,
    /** The one credential resolution, and who the change counts run as: the requester. */
    callerOf: RestCaller,
    audit: AuditRecorder = AuditRecorder.noop
):

  private type Err    = (StatusCode, ErrorResponse)
  private type Out[T] = IO[Either[Err, T]]

  /** What [[gate]] decided: the tenant and parent database by their ids, who is asking, and as whom
    * their statements run.
    */
  private final case class Admitted(
      tid: String,
      db: String,
      actor: BranchActor,
      superuser: Boolean,
      principal: RestCaller.Principal
  ):
    def caller(connectionId: String): ExecCaller = RestCaller.callerOf(connectionId, principal)

  private def toErr(f: BranchFailure): Err =
    (StatusCode(f.status), ErrorResponse(f.code, f.message))

  private val DuckLakeOnly = Some("branching requires a ducklake tenant-db")

  /** The one gate of every branch endpoint, REST and MCP: see the class comment. */
  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Either[Err, Admitted] =
    callerOf.resolve(apiKey).flatMap { principal =>
      val scope = scopeOf(apiKey)
      TenantDbGate.resolveTenantId(sup, rawTenant) match
        case Some(tid) if BranchHandlers.adminOf(scope, tid) =>
          admittedAsAdmin(tid, tenantDb, apiKey, scope, principal)(scopeOf)
        case Some(tid) if ownTenant(principal).contains(tid) =>
          admittedAsMember(tid, tenantDb, apiKey, principal)
        // An unknown tenant, to someone who manages one: as on every other tenant-db surface.
        case None if scope.superuser || scope.manageableTenants.nonEmpty =>
          Left(
            (
              StatusCode.NotFound,
              ErrorResponse("not_found", s"tenant '$rawTenant' is not registered")
            )
          )
        // Anyone else gets one answer for a tenant that exists and for one that does not.
        case _ => Left(TenantScopeCheck.forbidden(rawTenant))
    }

  /** The tenant a credential belongs to, the one a plain member may branch in, as
    * `ProfileHandlers.pools` binds it.
    */
  private def ownTenant(principal: RestCaller.Principal): Option[String] = principal match
    case RestCaller.Principal.Session(s) => s.profile.tenant
    case RestCaller.Principal.Pat(p)     => p.user.tenant

  private def admittedAsAdmin(
      tid: String,
      tenantDb: String,
      apiKey: Option[String],
      scope: SessionScope,
      principal: RestCaller.Principal
  )(scopeOf: SessionScope.Resolver): Either[Err, Admitted] =
    val caller = RestCaller.callerOf("branch-scope", principal)
    for
      (_, db) <- TenantDbGate(sup, tid, tenantDb, apiKey, DuckLakeOnly)(scopeOf)
      // A token's databases axis on the PARENT (review 5 R2): every branch endpoint, REST and MCP.
      _ <- TokenScope.databaseRefusal(sup, tid, caller.restriction, db).toLeft(())
    yield Admitted(
      tid,
      db,
      BranchActor.admin(caller.identity, caller.restriction.branchOnly),
      scope.superuser,
      principal
    )

  private def admittedAsMember(
      tid: String,
      tenantDb: String,
      apiKey: Option[String],
      principal: RestCaller.Principal
  ): Either[Err, Admitted] =
    val caller = RestCaller.callerOf("branch-gate", principal)
    for
      _   <- TokenScope.databaseRefusal(sup, tid, caller.restriction, tenantDb).toLeft(())
      eff <- mayConnect(caller, tid, tenantDb)
      // The member may reach this database, so its kind is theirs to read.
      (_, db) <- TenantDbGate(sup, tid, tenantDb, apiKey, DuckLakeOnly)(BranchHandlers.decided(tid))
    yield
      val alias = TenantDb.catalogAlias(sup.effectiveMetastoreFor(tid, db), db)
      Admitted(
        tid,
        db,
        BranchActor.plain(
          caller.identity,
          BranchHandlers.seenBy(eff, alias),
          caller.restriction.branchOnly
        ),
        superuser = false,
        principal
      )

  /** Whether `caller` may connect to a pool of `db`, on the handshake's own decision: the effective
    * set it decides with, narrowed by a token's restriction. Decided lazily and in order, so the
    * first pool that admits them or cannot answer ends it (each OPA-mode decision is a round trip).
    * A database with no such pool, or none at all, reads the same, and says nothing of why.
    */
  private def mayConnect(caller: ExecCaller, tid: String, db: String): Either[Err, EffectiveSet] =
    sup
      .list()
      .map(_.key)
      .filter(k => k.tenant == tid && k.tenantDb == db)
      .filter(k => caller.restriction.allowsDatabase(k.tenantDb))
      .filter(k => caller.restriction.allowsPool(k.pool))
      .sortBy(_.pool)
      .to(LazyList)
      .map(k => sup.authorizeHandshakeDetailed(caller.authzRequest(k)))
      .collectFirst {
        case Right(authorized)                      => Right(authorized.effectiveSet)
        case Left(HandshakeDenial.Unavailable(msg)) =>
          Left(
            (
              StatusCode.ServiceUnavailable,
              ErrorResponse("authz_unavailable", s"authorization service unavailable: $msg")
            )
          )
      }
      .getOrElse(
        Left(
          (
            StatusCode.Forbidden,
            ErrorResponse("no_pool_access", s"you may connect to no pool of database '$db'")
          )
        )
      )

  /** [[gate]], off the compute pool (a decision can be a round trip to the tenant's OPA), then
    * `run`.
    */
  private def admitted[T](rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  )(run: Admitted => Out[T]): Out[T] =
    IO.blocking(gate(rawTenant, tenantDb, apiKey)(scopeOf)).flatMap {
      case Left(e)  => IO.pure(Left(e))
      case Right(a) => run(a)
    }

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
    admitted(req.tenant, req.tenantDb, apiKey)(scopeOf) { a =>
      service
        .create(a.tid, a.db, req.name, req.ttlHours, req.fromSnapshot, a.actor, apiKey)
        .map(_.left.map(toErr).map(entry))
    }

  def list(
      tenant: String,
      tenantDb: String,
      includeTerminal: Option[Boolean],
      apiKey: Option[String]
  )(
      scopeOf: SessionScope.Resolver
  ): Out[BranchListResponse] =
    admitted(tenant, tenantDb, apiKey)(scopeOf) { a =>
      IO.blocking(
        service
          .list(a.tid, a.db, includeTerminal.getOrElse(false))
          .left
          .map(toErr)
          .map(bs => BranchListResponse(bs.map(entry)))
      )
    }

  def get(tenant: String, tenantDb: String, branch: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[BranchDetailResponse] =
    admitted(tenant, tenantDb, apiKey)(scopeOf) { a =>
      IO.blocking(
        service.get(a.tid, a.db, branch).left.map(toErr).map { case (b, merges) =>
          BranchDetailResponse(
            entry(b),
            merges.map(BranchHandlers.mergeEntry(_, a.superuser, a.actor))
          )
        }
      )
    }

  def changes(
      tenant: String,
      tenantDb: String,
      branch: String,
      counts: Option[Boolean],
      apiKey: Option[String]
  )(scopeOf: SessionScope.Resolver): Out[BranchChangesResponse] =
    admitted(tenant, tenantDb, apiKey)(scopeOf) { a =>
      // Counts run as the requester (a session or, over MCP, the PAT curried as apiKey): the node
      // decides which tables they may count, never the system caller.
      val countsAs =
        Option.when(counts.getOrElse(true))(a.caller(s"branch-changes-${a.tid}-${a.db}"))
      service
        .changes(a.tid, a.db, branch, countsAs, a.actor, apiKey)
        .flatMap {
          case Left(f)        => IO.pure(Left(toErr(f)))
          case Right((b, cs)) =>
            IO.blocking(service.proposalOf(b)).map { proposal =>
              Right(
                BranchHandlers.changesResponse(
                  b,
                  cs,
                  proposal,
                  a.actor,
                  t => a.superuser || (countsAs.nonEmpty && t.counted)
                )
              )
            }
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
    admitted(tenant, tenantDb, apiKey)(scopeOf) { a =>
      IO.blocking(bounds(a.tid, a.db, branch)).flatMap {
        case Left(e)              => IO.pure(Left(e))
        case Right((b, from, to)) =>
          preview.dataDiffHiding(
            a.tid,
            b.tenantDbName,
            schema,
            table,
            from,
            to,
            limit,
            cursor,
            changeType,
            apiKey,
            (s, t) => !a.actor.sees(s, t)
          )(BranchHandlers.decided(a.tid))
      }
    }

  def schemaDiff(
      tenant: String,
      tenantDb: String,
      branch: String,
      schema: String,
      table: String,
      apiKey: Option[String]
  )(scopeOf: SessionScope.Resolver): Out[SchemaDiffResponse] =
    admitted(tenant, tenantDb, apiKey)(scopeOf) { a =>
      IO.blocking(bounds(a.tid, a.db, branch)).flatMap {
        case Left(e)              => IO.pure(Left(e))
        case Right((b, from, to)) =>
          preview.schemaDiffHiding(
            a.tid,
            b.tenantDbName,
            schema,
            table,
            from,
            to,
            apiKey,
            (s, t) => !a.actor.sees(s, t)
          )(BranchHandlers.decided(a.tid))
      }
    }

  /** `(branch, fork, head)` as the numeric bound strings the catalog diff handlers parse. */
  private def bounds(
      tid: String,
      db: String,
      branch: String
  ): Either[Err, (Branch, String, String)] =
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
    admitted(req.tenant, req.tenantDb, apiKey)(scopeOf) { a =>
      ProposeOptions.parse(req.onConflict, req.keys) match
        case Left(f)        => IO.pure(Left(toErr(f)))
        case Right(options) =>
          // The reply's change set counts as the proposer, like `changes`; the stored summary keeps
          // the system's counts, shown to superusers only (mergeEntry).
          service
            .propose(
              a.tid,
              a.db,
              req.branch,
              options,
              a.actor,
              a.caller(s"branch-propose-${a.tid}-${a.db}"),
              apiKey
            )
            .map(_.left.map(toErr).map { case (b, m, cs) =>
              BranchProposeResponse(
                entry(b),
                BranchHandlers.mergeEntry(m, a.superuser, a.actor),
                BranchHandlers.changesResponse(
                  b,
                  cs,
                  Some(m),
                  a.actor,
                  t => a.superuser || t.counted
                )
              )
            })
    }

  def merge(req: BranchMergeRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[BranchMergeResponse] =
    admitted(req.tenant, req.tenantDb, apiKey)(scopeOf) { a =>
      service
        .merge(
          a.tid,
          a.db,
          req.branch,
          req.proposalId,
          req.expectedMainSnapshot,
          req.holdWriters,
          a.actor,
          apiKey
        )
        .map(_.left.map(toErr).map { case (b, m, cs) =>
          BranchMergeResponse(
            entry(b),
            BranchHandlers.mergeEntry(m, a.superuser, a.actor),
            BranchHandlers.changesResponse(b, cs, Some(m), a.actor, _ => a.superuser)
          )
        })
    }

  def discard(req: BranchOpRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[BranchEntry] =
    admitted(req.tenant, req.tenantDb, apiKey)(scopeOf) { a =>
      service
        .discard(a.tid, a.db, req.branch, a.actor, apiKey)
        .map(_.left.map(toErr).map(entry))
    }

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

  /** Whether `scope` is an admin of tenant `tid`: a superuser, or an admin of that very tenant.
    * Never of any other: an admin elsewhere who is a plain member here is a plain member here,
    * whose replies are narrowed and who may not merge.
    */
  def adminOf(scope: SessionScope, tid: String): Boolean =
    scope.superuser || scope.manageableTenants.contains(tid)

  /** What a non-admin's replies may name: a table `eff` covers with any verb, as filtered metadata
    * narrows their listings. `alias`, the parent's catalog, is the only session catalog, so a
    * wildcard catalog covers it and nothing else. An empty set (nothing could be resolved) shows
    * nothing. An OPA tenant's users hold no QoD grants to go by, so they see nothing listed, as
    * with their `SHOW TABLES`.
    */
  def seenBy(eff: EffectiveSet, alias: String): (String, String) => Boolean =
    (schema, table) =>
      List(Verb.Read, Verb.Write, Verb.Ddl).exists(
        GrantMatch.covers(eff.permissions, Set(alias), alias, schema, table, _)
      )

  /** What [[gate]] hands [[CatalogPreviewHandlers]] for its own gate, and its own for the kind
    * check: this tenant, admitted. The branch gate has decided who may come this far, and the
    * statements still run as the caller, under the node's verdict.
    */
  private def decided(tid: String): SessionScope.Resolver =
    _ => SessionScope(superuser = false, manageableTenants = Set(tid))
