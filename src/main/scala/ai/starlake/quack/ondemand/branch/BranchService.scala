package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.BranchingConfig
import ai.starlake.quack.model.{
  Branch,
  BranchMerge,
  BranchMergeStatus,
  BranchStatus,
  Names,
  NodeSpec,
  PoolKey,
  RoleDistribution,
  SnapshotTag,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.rbac.{AuthzRequest, HandshakeDenial}
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.{IO, Outcome}
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import io.circe.syntax._

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Who is asking. `isAdmin` = tenant admin on the tenant, or superuser; every other principal is a
  * plain data-plane user and must be able to connect to the parent. `branchOnly` = the credential
  * is a PAT restricted to branch writes: it may create and write branches, but a merge writes main,
  * so merge refuses it (`branch_only_token`).
  */
final case class BranchActor(identity: String, isAdmin: Boolean, branchOnly: Boolean = false)

/** A refusal, with the HTTP status the REST layer maps it to. Codes are the stable snake_case
  * vocabulary of design section 4.
  */
final case class BranchFailure(status: Int, code: String, message: String)

object BranchFailure:
  def badRequest(code: String, msg: String)    = BranchFailure(400, code, msg)
  def forbidden(code: String, msg: String)     = BranchFailure(403, code, msg)
  def notFound(msg: String)                    = BranchFailure(404, "not_found", msg)
  def conflict(code: String, msg: String)      = BranchFailure(409, code, msg)
  def unprocessable(code: String, msg: String) = BranchFailure(422, code, msg)
  def upstream(code: String, msg: String)      = BranchFailure(502, code, msg)

  /** A deciding authority (OPA) could not be reached: retryable, never a permission verdict. */
  def unavailable(code: String, msg: String) = BranchFailure(503, code, msg)

/** Runs work on an ephemeral merge node built from `spec`: spawns it, waits for it to accept
  * connections, runs `body`, stops it whatever happens. `Left`: the node never came up, or what
  * `body` answered. Each call on the node is bounded: one that does not answer in time is abandoned
  * (the statement may still run until the node is stopped) and answers a `Left` that starts with
  * [[MergeExecutor.TimedOut]], and the commit message on main then decides what happened. An engine
  * error can hold the word "timeout" too (a lock timeout), so a consumer tests
  * `startsWith(MergeExecutor.TimedOut)`, never `contains("timeout")`.
  */
trait MergeExecutor:
  def withNode[A](spec: NodeSpec)(body: MergeNode => IO[Either[String, A]]): IO[Either[String, A]]

object MergeExecutor:

  /** How the reason of a node call that did not answer within its bound starts. */
  val TimedOut = "timeout: "

/** Change-feed counts of one modified table on the branch pool: `(inserted, deleted, updated)`.
  * `caller` is who the count statement runs as, required so no call site can leave it to a default:
  * the requester for counts asked over REST or MCP (the node may refuse them a table, and that
  * table then shows no counts), the system caller only for the lifecycle's own summary (propose).
  */
trait ChangeCounter:
  def count(
      caller: ai.starlake.quack.ondemand.api.ExecCaller,
      tenant: String,
      branch: Branch,
      alias: String,
      change: TableChange,
      fork: Long,
      head: Long
  ): IO[Either[String, (Long, Long, Long)]]

/** Orchestration of the branch lifecycle (Epic 1, design section 4): create, changes, propose,
  * merge, discard, expiry, teardown. Every collaborator that touches the world (Postgres clone,
  * merge node, file purge, change counts) is injected so the lifecycle is unit-testable on the
  * in-memory store with a stub backend.
  *
  * Tenant coordinates are the tenant id (== lowercased name) and the PARENT tenant-db name; callers
  * resolve them through `TenantDbGate` first.
  */
final class BranchService(
    cfg: BranchingConfig,
    sup: PoolSupervisor,
    store: ControlPlaneStore,
    resolveReader: (String, String) => DuckLakeCatalogReader,
    /** `(parent metastore, parent db, branch db, branch data path)` -> clone outcome; Main binds
      * [[BranchCloner]], tests inject a fake.
      */
    cloneCatalog: (Map[String, String], String, String, String) => Either[String, CloneResult],
    mergeExecutor: MergeExecutor,
    /** Holds the merge commit to the main snapshot its change set was validated against. */
    mergeFence: MergeFence,
    counter: ChangeCounter,
    purgeFiles: (String, Map[String, String]) => Either[String, Unit],
    audit: AuditRecorder = AuditRecorder.noop,
    events: ManagerEventSink = ManagerEventSink.noop,
    now: () => Instant = () => Instant.now(),
    /** The pause between merge attempts when main moved. */
    attemptWait: FiniteDuration = 3.seconds,
    /** How long commits already past the hold get to land before main's head is read. */
    holdSettle: FiniteDuration = 200.millis
) extends LazyLogging:

  private type Res[A] = Either[BranchFailure, A]

  // ---------- lookups ----------

  private def parentOf(tenant: String, parentDb: String): Res[TenantDb] =
    sup.findTenantDb(tenant, parentDb) match
      case None => Left(BranchFailure.notFound(s"tenant-db '$parentDb' not found"))
      case Some(td) if td.kind != TenantDbKind.DuckLake =>
        Left(BranchFailure.badRequest("invalid_kind", "branching requires a ducklake tenant-db"))
      case Some(td) if td.branchOf.nonEmpty =>
        Left(
          BranchFailure.badRequest(
            "branch_of_branch_unsupported",
            s"'$parentDb' is itself a branch; branch the parent instead"
          )
        )
      case Some(td) => Right(td)

  private def liveBranch(parent: TenantDb, name: String): Res[Branch] =
    store
      .findBranch(parent.id, name)
      .toRight(BranchFailure.notFound(s"branch '$name' not found on '${parent.name}'"))

  /** The branch's own pool key: where its statements run. */
  def poolKeyOf(b: Branch): PoolKey = PoolKey(b.tenant, b.tenantDbName, b.poolName)

  /** Resolve `(tenant, parentDb, branch)` to the live branch and its pool key, for the FlightSQL
    * handshake and the MCP data tools.
    */
  def resolveTarget(tenant: String, parentDb: String, name: String): Res[(Branch, PoolKey)] =
    for
      parent <- parentOf(tenant, parentDb)
      b      <- liveBranch(parent, name)
    yield (b, poolKeyOf(b))

  /** The branch catalog's current head snapshot (the `to` bound of every branch diff). */
  def headSnapshot(tenant: String, b: Branch): Option[Long] =
    resolveReader(tenant, b.tenantDbName).maxSnapshotId()

  def list(tenant: String, parentDb: String, includeTerminal: Boolean): Res[List[Branch]] =
    parentOf(tenant, parentDb).map { parent =>
      store.listBranches(parent.id, if includeTerminal then Set.empty else BranchStatus.Live)
    }

  def get(tenant: String, parentDb: String, name: String): Res[(Branch, List[BranchMerge])] =
    parentOf(tenant, parentDb).flatMap { parent =>
      store
        .findBranch(parent.id, name, liveOnly = false)
        .toRight(BranchFailure.notFound(s"branch '$name' not found on '${parent.name}'"))
        .map(b => (b, store.listBranchMerges(b.id)))
    }

  /** The branch's open proposal, if any. */
  def proposalOf(b: Branch): Option[BranchMerge] =
    store.listBranchMerges(b.id).find(_.status == BranchMergeStatus.Proposed)

  /** A non-admin may act on a parent iff it can connect to at least one of its pools: exactly the
    * FlightSQL handshake gate, so "may query" and "may branch" are the same permission. Admins
    * (tenant admins and superusers) never reach the gate, so it runs with
    * `superuserAdmissible = false`: the actor binds a row of the parent's tenant, never a superuser
    * row. Uses `authorizeHandshakeDetailed` (not the legacy string-left delegate) so an OPA outage
    * on an opa-mode parent is distinguishable from an ordinary grant refusal: `Res` has its own
    * error channel (`BranchFailure`), so `Unavailable` is surfaced as a retryable 503 rather than
    * collapsed into the same "acl_denied" a real policy Deny gets.
    */
  private def mayUse(tenant: String, parent: TenantDb, actor: BranchActor): Res[Unit] =
    if actor.isAdmin then Right(())
    else
      val pools = sup.list().map(_.key).filter(k => k.tenant == tenant && k.tenantDb == parent.name)
      val outcomes = pools.map { k =>
        sup.authorizeHandshakeDetailed(
          AuthzRequest(tenant, k.pool, actor.identity, superuserAdmissible = false, edge = "rest")
        )
      }
      if outcomes.exists(_.isRight) then Right(())
      else if outcomes.exists {
          case Left(_: HandshakeDenial.Unavailable) => true
          case _                                    => false
        }
      then
        Left(
          BranchFailure.unavailable(
            "authz_unavailable",
            s"authorization service unavailable for '${actor.identity}' on '${parent.name}'"
          )
        )
      else
        Left(
          BranchFailure.forbidden(
            "acl_denied",
            s"user '${actor.identity}' cannot connect to any pool of '${parent.name}'"
          )
        )

  private def enabled: Res[Unit] =
    if cfg.enabled then Right(())
    else
      Left(
        BranchFailure.badRequest("branching_disabled", "branching is disabled (QOD_BRANCH_ENABLED)")
      )

  // ---------- create ----------

  private def validTtl(ttlHours: Option[Int]): Res[Unit] =
    if ttlHours.exists(_ < 0) then
      Left(BranchFailure.badRequest("invalid_ttl", "ttlHours must be >= 0"))
    else Right(())

  private def noDuplicate(parent: TenantDb, name: String): Res[Unit] =
    store.findBranch(parent.id, name) match
      case Some(_) =>
        Left(
          BranchFailure.conflict("duplicate", s"branch '$name' already exists on '${parent.name}'")
        )
      case None => Right(())

  private def underLimit(parent: TenantDb): Res[Unit] =
    val live = store.listBranches(parent.id, BranchStatus.Live).size
    if live >= cfg.maxPerDatabase then
      Left(
        BranchFailure.conflict(
          "branch_limit",
          s"'${parent.name}' already has $live live branches (limit ${cfg.maxPerDatabase})"
        )
      )
    else Right(())

  def create(
      tenant: String,
      parentDb: String,
      rawName: String,
      ttlHours: Option[Int],
      fromSnapshot: Option[Long],
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[Branch]] =
    val checked: Res[(TenantDb, String, Option[Instant])] =
      for
        _      <- enabled
        parent <- parentOf(tenant, parentDb)
        _      <- mayUse(tenant, parent, actor)
        name   <- BranchNames
          .validateName(rawName)
          .left
          .map(BranchFailure.badRequest("invalid_name", _))
        _ <- validTtl(ttlHours)
        _ <- noDuplicate(parent, name)
        _ <- underLimit(parent)
      yield
        val ttl     = ttlHours.getOrElse(cfg.defaultTtlHours)
        val expires =
          if ttl <= 0 then None
          else Some(now().plus(ttl.toLong, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS))
        (parent, name, expires)

    checked match
      case Left(f) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.BranchCreate,
          "denied",
          tenant = Some(tenant)
        )
        IO.pure(Left(f))
      case Right((parent, name, expires)) =>
        provision(tenant, parent, name, expires, fromSnapshot, actor, apiKey)

  private def provision(
      tenant: String,
      parent: TenantDb,
      name: String,
      expires: Option[Instant],
      fromSnapshot: Option[Long],
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[Branch]] = IO.defer {
    val id          = BranchNames.newId()
    val parentMeta  = sup.effectiveMetastoreFor(tenant, parent.name)
    val parentDbNm  = parentMeta.getOrElse("dbName", parent.name)
    val parentPath  = parentMeta.getOrElse("dataPath", parent.dataPath)
    val branchDb    = BranchNames.tenantDbName(parent.name, id)
    val branchPath  = BranchNames.dataPath(parentPath, id)
    val poolName    = BranchNames.poolName(id)
    val parentAlias = TenantDb.catalogAlias(parentMeta, parent.name)

    def fail[A](code: String, msg: String, status: Int = 502): Res[A] =
      Left(BranchFailure(status, code, msg))

    // Step 1: Postgres database + metadata clone (pure JDBC, no node).
    val cloned: IO[Res[CloneResult]] = IO.blocking {
      sup.databaseAdmin.createDatabase(branchDb) match
        case Left(err) => fail("branch_create_failed", s"cannot provision '$branchDb': $err")
        case Right(_)  =>
          cloneCatalog(parentMeta, parentDbNm, branchDb, branchPath) match
            case Left(err) =>
              dropQuietly(branchDb)
              fail("branch_create_failed", err)
            case Right(res) =>
              fromSnapshot match
                case Some(want) if want != res.forkSnapshot =>
                  dropQuietly(branchDb)
                  fail(
                    "fork_snapshot_unsupported",
                    s"v1 forks at the parent head (snapshot ${res.forkSnapshot}); " +
                      s"fromSnapshot=$want is not supported",
                    status = 409
                  )
                case _ => Right(res)
    }

    cloned.flatMap {
      case Left(f)    => IO.pure(auditDenied(apiKey, tenant, f))
      case Right(res) =>
        // Step 2: tenant-db row under the parent's alias, then the pool, then the branch row.
        val td = TenantDb(
          id = Names.newSurrogateId("td"),
          tenantId = parent.tenantId,
          name = branchDb,
          kind = TenantDbKind.DuckLake,
          metastore = parent.metastore
            .updated("dbName", branchDb)
            .updated(TenantDb.CatalogAliasKey, parentAlias),
          dataPath = branchPath,
          objectStore = parent.objectStore,
          defaultDatabase = parent.defaultDatabase,
          defaultSchema = parent.defaultSchema,
          initSql = parent.initSql,
          // The parent's text, so the parent's author: as trusted as it is there.
          initSqlTrusted = parent.initSqlTrusted,
          branchOf = Some(parent.id),
          // A branch is a clone of the parent's catalog: BranchCloner copies ducklake_metadata
          // wholesale, so the clone is already encrypted or not exactly as the parent is. Carry the
          // same value onto the control-plane row so the branch pool's own nodes pass the matching
          // flag on their ATTACHes.
          encrypted = parent.encrypted
        )
        IO.blocking(sup.registerBranchTenantDb(td)).flatMap {
          case Left(err) =>
            IO.blocking(dropQuietly(branchDb))
              .as(
                auditDenied(apiKey, tenant, BranchFailure(502, "branch_create_failed", err.message))
              )
          case Right(_) =>
            val donor = store.listPools(parent.id).sortBy(_.name).headOption
            val key   = PoolKey(tenant, branchDb, poolName)
            sup
              .createPool(
                key,
                RoleDistribution(writeonly = 0, readonly = 0, dual = 1),
                maxConcurrentPerNode = donor.map(_.maxConcurrentPerNode).getOrElse(0),
                initSql = donor.map(_.initSql).getOrElse(""),
                initSqlTrusted = donor.exists(_.initSqlTrusted),
                cpu = donor.map(_.cpu).getOrElse(""),
                memory = donor.map(_.memory).getOrElse(""),
                podTemplateYaml = donor.map(_.podTemplateYaml).getOrElse(""),
                idleTimeoutSec = donor.flatMap(_.idleTimeoutSec)
              )
              .attempt
              .flatMap {
                case Left(e) =>
                  val (status, code) = e match
                    case _: ai.starlake.quack.spi.QuotaExceededException => (409, "quota_exceeded")
                    case _ => (502, "branch_create_failed")
                  rollbackRow(tenant, td) *> IO.pure(
                    auditDenied(apiKey, tenant, BranchFailure(status, code, e.getMessage))
                  )
                case Right(_) =>
                  val b = Branch(
                    id = id,
                    tenant = tenant,
                    parentDbId = parent.id,
                    parentDbName = parent.name,
                    name = name,
                    tenantDbId = td.id,
                    tenantDbName = branchDb,
                    poolName = poolName,
                    dataPath = branchPath,
                    forkSnapshot = res.forkSnapshot,
                    ownerUser = actor.identity,
                    status = BranchStatus.Open,
                    expiresAt = expires
                  )
                  IO.blocking(store.createBranch(b)).flatMap {
                    case Left(_) =>
                      // Lost a race on the live-name index: undo compute + catalog.
                      sup.deletePool(key, force = true).attempt *> rollbackRow(tenant, td) *>
                        IO.pure(
                          auditDenied(
                            apiKey,
                            tenant,
                            BranchFailure.conflict("duplicate", s"branch '$name' already exists")
                          )
                        )
                    case Right(created) =>
                      audit.rest(
                        apiKey,
                        "control-plane",
                        AuditActions.BranchCreate,
                        "ok",
                        tenant = Some(tenant),
                        target = Some(s"${parent.name}/$name"),
                        detail = Map(
                          "branchId"     -> created.id,
                          "forkSnapshot" -> created.forkSnapshot.toString,
                          "tablesCopied" -> res.tablesCopied.toString,
                          "expiresAt"    -> created.expiresAt.map(_.toString).getOrElse("never")
                        )
                      )
                      events.emit(
                        ManagerEvent.BranchCreated(tenant, parent.name, name, actor.identity)
                      )
                      logger.info(
                        s"branch created: $tenant/${parent.name}@$name (${created.id}) at snapshot " +
                          s"${created.forkSnapshot}, pool $poolName, catalog $branchDb"
                      )
                      IO.pure(Right(created))
                  }
              }
        }
    }
  }

  private def auditDenied(apiKey: Option[String], tenant: String, f: BranchFailure): Res[Branch] =
    audit.rest(
      apiKey,
      "control-plane",
      AuditActions.BranchCreate,
      "denied",
      tenant = Some(tenant),
      detail = Map("error" -> f.code)
    )
    Left(f)

  private def dropQuietly(db: String): Unit =
    sup.databaseAdmin.dropDatabase(db) match
      case Right(_)  => ()
      case Left(err) => logger.warn(s"branch: DROP DATABASE \"$db\" failed during rollback: $err")

  /** Undo a registered branch tenant-db row (and its Postgres database). */
  private def rollbackRow(tenant: String, td: TenantDb): IO[Unit] =
    sup.deleteTenantDb(tenant, td.name).attempt.flatMap {
      case Right(Right(())) => IO.unit
      case Right(Left(err)) =>
        IO.delay(logger.warn(s"branch: rollback of '${td.name}' failed: ${err.message}"))
      case Left(e) =>
        IO.delay(logger.warn(s"branch: rollback of '${td.name}' threw: ${e.getMessage}"))
    }

  // ---------- changes ----------

  private def parentAliasOf(tenant: String, parent: TenantDb): String =
    TenantDb.catalogAlias(sup.effectiveMetastoreFor(tenant, parent.name), parent.name)

  /** The change set of a live branch against main, with change-feed counts for modified tables when
    * `countsAs` names a caller: each count is one statement on the branch pool run as that caller
    * (the requester, never the system caller: the node decides which tables they may count). A
    * failed or refused count degrades to zeroes flagged `countsUnavailable`, with a WARN, so the
    * listing never fails on a hibernated pool or a table the requester holds no grant on. `None`:
    * no counts.
    */
  def changes(
      tenant: String,
      parentDb: String,
      name: String,
      countsAs: Option[ai.starlake.quack.ondemand.api.ExecCaller],
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[(Branch, BranchChangeSet)]] =
    val pre = for
      _      <- enabled
      parent <- parentOf(tenant, parentDb)
      _      <- mayUse(tenant, parent, actor)
      b      <- liveBranch(parent, name)
    yield (parent, b)
    pre match
      case Left(f)            => IO.pure(Left(f))
      case Right((parent, b)) =>
        computeChanges(tenant, parent, b, countsAs).map { cs =>
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.BranchChangesRead,
            "ok",
            tenant = Some(tenant),
            target = Some(s"${parent.name}/$name"),
            detail =
              Map("tables" -> cs.tables.size.toString, "conflicts" -> cs.conflicts.size.toString)
          )
          Right((b, cs))
        }

  private def computeChanges(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      countsAs: Option[ai.starlake.quack.ondemand.api.ExecCaller]
  ): IO[BranchChangeSet] =
    IO.blocking {
      val br = resolveReader(tenant, b.tenantDbName)
      val mr = resolveReader(tenant, parent.name)
      BranchChanges.compute(br, mr, b.forkSnapshot)
    }.flatMap(cs => countsAs.fold(IO.pure(cs))(caller => countAs(tenant, parent, b, cs, caller)))

  /** `cs` with change-feed counts of its modified tables, each counted as `caller`. */
  private def countAs(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      cs: BranchChangeSet,
      caller: ai.starlake.quack.ondemand.api.ExecCaller
  ): IO[BranchChangeSet] =
    val alias = parentAliasOf(tenant, parent)
    cs.tables
      .foldLeft(IO.pure(List.empty[TableChange])) { (acc, t) =>
        acc.flatMap { done =>
          if t.kind != ChangeKind.Modified then IO.pure(done :+ t)
          else
            counter.count(caller, tenant, b, alias, t, cs.forkSnapshot, cs.headSnapshot).map {
              case Right((ins, del, upd)) =>
                done :+ t.copy(inserted = ins, deleted = del, updated = upd)
              case Left(err) =>
                logger.warn(s"branch changes: counts for ${t.schema}.${t.table} failed: $err")
                done :+ t.copy(countsUnavailable = true)
            }
        }
      }
      .map(ts => cs.copy(tables = ts))

  // ---------- propose ----------

  /** Records a merge proposal: the change set, and what a merge would do to its rows now, read on a
    * merge node without writing to either catalog (design section 5.1). Conflicts are recorded, not
    * refused: merge decides. Proposing again replaces an open proposal. The stored summary
    * (`summaryJson`) counts every modified table as the system caller: the lifecycle's own record,
    * shown in full to superusers only (`BranchHandlers` strips its counts for anyone else). The
    * change set returned to the proposer counts as `countsAs`, the requester, like [[changes]]: a
    * table the node refuses them is flagged `countsUnavailable`, never filled from the system's
    * counts.
    */
  def propose(
      tenant: String,
      parentDb: String,
      name: String,
      options: ProposeOptions,
      actor: BranchActor,
      countsAs: ai.starlake.quack.ondemand.api.ExecCaller,
      apiKey: Option[String]
  ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
    def denied(f: BranchFailure): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
      audit.rest(
        apiKey,
        "control-plane",
        AuditActions.BranchPropose,
        "denied",
        tenant = Some(tenant),
        detail = Map("error" -> f.code)
      )
      IO.pure(Left(f))
    // The branch as it is now: a merge or a discard may have ended it since the last look.
    def proposable(parent: TenantDb): Res[Branch] =
      for
        b <- liveBranch(parent, name)
        _ <- b.status match
          case BranchStatus.Open | BranchStatus.Proposed => Right(())
          case other => Left(BranchFailure.conflict("not_open", s"branch '$name' is ${other.wire}"))
      yield b
    // Reads the catalog, so only inside IO.blocking: under the lock, and at the record step.
    def replaceable(parent: TenantDb): Res[Branch] =
      proposable(parent).flatMap(b => unsettled(tenant, parent, b).toLeft(b))
    val pre = for
      _      <- enabled
      parent <- parentOf(tenant, parentDb)
      _      <- mayUse(tenant, parent, actor)
      _      <- proposable(parent)
    yield parent
    pre match
      case Left(f)       => denied(f)
      case Right(parent) =>
        // One merge node at a time per parent, as for a merge, and a proposal never overlaps one: a
        // merge must not find its proposal replaced, nor a proposal revive what a merge ended.
        sup.poolLocks.withLock(PoolKey(tenant, parent.name, "__merge"))(
          IO.blocking(replaceable(parent)).flatMap {
            case Left(f)  => denied(f)
            case Right(b) =>
              // The lifecycle's own summary (stored on the merge row): every modified table, counted
              // as the system caller, whoever proposes. The proposer's reply counts as the proposer.
              val system =
                ai.starlake.quack.ondemand.api.ExecCaller.system(s"branch-counts-$tenant")
              computeChanges(tenant, parent, b, countsAs = None).flatMap { shape =>
                analyze(tenant, parent, b, shape, options).flatMap {
                  case Left(f)     => denied(f)
                  case Right(rows) =>
                    (for
                      stored <- countAs(tenant, parent, b, shape, system)
                      reply  <-
                        if countsAs.system then IO.pure(stored)
                        else countAs(tenant, parent, b, shape, countsAs)
                    yield (stored, reply)).flatMap { (cs, reply) =>
                      IO.blocking {
                        // A discard or an expiry does not take the lock: the analysis may have
                        // outlived the branch.
                        replaceable(parent).map { current =>
                          store
                            .listBranchMerges(current.id)
                            .filter(_.status == BranchMergeStatus.Proposed)
                            .foreach(m =>
                              store.updateBranchMerge(
                                m.copy(
                                  status = BranchMergeStatus.Abandoned,
                                  decidedAt = Some(now())
                                )
                              )
                            )
                          val merge = store.createBranchMerge(
                            BranchMerge(
                              id = Names.newSurrogateId("mg"),
                              branchId = current.id,
                              proposer = actor.identity,
                              approver = None,
                              status = BranchMergeStatus.Proposed,
                              summaryJson = BranchService.summaryJson(cs).noSpaces,
                              conflictsJson = BranchService.conflictsJson(cs).noSpaces,
                              mainSnapshotAtPropose = cs.mainSnapshot,
                              onConflict = options.onConflict.map(_.wire),
                              mergeKeysJson = options.keys.asJson.noSpaces,
                              headSnapshotAtPropose = Some(cs.headSnapshot),
                              rowConflictsJson = RowCounts.listToJson(rows)
                            )
                          )
                          val updated = store
                            .updateBranch(current.copy(status = BranchStatus.Proposed))
                            .getOrElse(current)
                          audit.rest(
                            apiKey,
                            "control-plane",
                            AuditActions.BranchPropose,
                            "ok",
                            tenant = Some(tenant),
                            target = Some(s"${parent.name}/$name"),
                            detail = Map(
                              "mergeId"    -> merge.id,
                              "tables"     -> cs.tables.size.toString,
                              "conflicts"  -> cs.conflicts.size.toString,
                              "mergeable"  -> cs.mergeable.toString,
                              "onConflict" -> options.onConflict.fold("none")(_.wire)
                            )
                          )
                          (updated, merge, reply)
                        }
                      }.flatMap {
                        case Left(f)  => denied(f)
                        case Right(r) => IO.pure(Right(r))
                      }
                    }
                }
              }
          }
        )

  /** Why the open proposal must not be replaced, if so. A new proposal has a new commit message, so
    * it could not settle a commit of the old one. The prefix covers a send that timed out and can
    * still land, which only this proposal's next merge can settle. The look covers a commit that
    * landed: the open proposal's, which the next merge's first look settles, or an older one's,
    * which only a discard ends.
    */
  private def unsettled(tenant: String, parent: TenantDb, b: Branch): Option[BranchFailure] =
    def mergeAgain = BranchFailure.conflict(
      "proposal_unsettled",
      s"the last merge of branch '${b.name}' may have landed on main; merge again, or discard it"
    )
    val timedOut = store
      .listBranchMerges(b.id)
      .filter(_.status == BranchMergeStatus.Proposed)
      .exists(_.error.exists(_.startsWith(MergeExecutor.TimedOut)))
    if timedOut then Some(mergeAgain)
    else
      landedCommit(tenant, parent, b).map {
        case (m, _) if m.status == BranchMergeStatus.Proposed => mergeAgain
        case (m, snapshot)                                    => landedElsewhere(m, snapshot)
      }

  /** A branch's commit lands once. A batch can land long after its merge stopped waiting, on a node
    * that outlived its stop or a dead manager, and its proposal may have closed or been replaced by
    * then. So every look reads the message of every proposal of the branch, whatever its status,
    * the open one first: its commit settles whatever else is on main. One catalog read per
    * proposal. Blocks.
    */
  private def landedCommit(
      tenant: String,
      parent: TenantDb,
      b: Branch
  ): Option[(BranchMerge, Long)] =
    lazy val reader = resolveReader(tenant, parent.name)
    store
      .listBranchMerges(b.id)
      .sortBy(_.status != BranchMergeStatus.Proposed)
      .iterator
      .flatMap(m =>
        reader
          .snapshotByCommitMessage(BranchMergeSql.commitMessage(b.name, m.id, m.proposer))
          .map((m, _))
      )
      .nextOption()

  /** The refusal when the commit on main is not the one of the proposal at hand: nothing can settle
    * it, and merging the branch again would apply it twice.
    */
  private def landedElsewhere(m: BranchMerge, snapshot: Long): BranchFailure =
    BranchFailure.conflict(
      "proposal_unsettled",
      s"merge ${m.id} landed as snapshot $snapshot; discard this branch"
    )

  /** The tables of `cs` that merge by rows, or why they cannot be read. */
  private def rowTables(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      cs: BranchChangeSet,
      keys: Map[String, List[String]]
  ): Res[List[RowMergeTable]] =
    RowMergeTable
      .plan(
        cs,
        resolveReader(tenant, b.tenantDbName),
        resolveReader(tenant, parent.name),
        keys
      )
      .left
      .map {
        case RowMergeTable.Problem.HistoryGap(missing) =>
          BranchFailure.conflict(
            "history_gap",
            s"main's snapshot $missing since the fork is gone, so its changes cannot be read; " +
              "discard this branch and create it again"
          )
        case RowMergeTable.Problem.MixedSnapshots(schema, table, snapshots) =>
          BranchFailure.unprocessable(
            "merge_unsupported",
            s"$schema.$table: snapshot(s) ${snapshots.mkString(", ")} cannot be read correctly: " +
              "they mix maintenance and data, or hold a change this version does not know"
          )
      }

  /** What a merge would do to the rows of `cs` now. Nothing to read when nothing merges by rows, or
    * when a table conflict or an unsupported change already rules the merge out.
    */
  private def analyze(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      cs: BranchChangeSet,
      options: ProposeOptions
  ): IO[Res[List[RowCounts]]] =
    IO.blocking {
      val br = resolveReader(tenant, b.tenantDbName)
      MergeDecision
        .keyProblem(
          options.keys,
          cs.tables,
          t => t.tableId.toList.flatMap(br.topLevelColumnsOfTableAt(_, cs.headSnapshot)).map(_.name)
        )
        .map(BranchFailure.badRequest("invalid_key", _))
        .toLeft(())
        .flatMap(_ =>
          if !cs.mergeable then Right(Nil)
          else rowTables(tenant, parent, b, cs, options.keys)
        )
    }.flatMap {
      case Left(f)    => IO.pure(Left(f))
      case Right(Nil) => IO.pure(Right(Nil))
      case Right(ts)  =>
        sup.mergeNodeSpec(tenant, parent.name, b.tenantDbName, b.dataPath) match
          case None =>
            IO.pure(
              Left(BranchFailure.upstream("merge_failed", "cannot build the merge node spec"))
            )
          case Some(spec) =>
            val runner = new RowMergeRunner(
              parentAliasOf(tenant, parent),
              BranchMergeSql.BranchAlias,
              cs.forkSnapshot,
              options.onConflict
            )
            mergeExecutor
              .withNode(spec)(node =>
                runner.prepare(node, ts).flatMap {
                  case Left(err) => IO.pure(Left(err))
                  case Right(_)  => runner.decide(node, ts)
                }
              )
              .map(
                _.left
                  .map(e => BranchFailure.upstream("merge_failed", MergeDecision.failureText(e)))
              )
    }

  // ---------- merge ----------

  /** `proposalId`: the open proposal the approver read. Proposing again replaces a proposal, so a
    * merge by name alone could land a policy, keys and a head nobody read, which would empty the
    * rule that the approver differs from the proposer. It is checked again under the lock.
    */
  def merge(
      tenant: String,
      parentDb: String,
      name: String,
      proposalId: String,
      expectedMainSnapshot: Option[Long],
      holdWriters: Boolean,
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
    def pre: Res[(TenantDb, Branch, BranchMerge)] = for
      _      <- enabled
      parent <- parentOf(tenant, parentDb)
      _      <-
        if actor.isAdmin then Right(())
        else Left(BranchFailure.forbidden("admin_required", "merge requires a tenant admin"))
      _ <-
        if !actor.branchOnly then Right(())
        else
          Left(
            BranchFailure.forbidden(
              "branch_only_token",
              "this token may only write on a branch; merging writes main and needs a " +
                "credential without the branch-only restriction"
            )
          )
      b     <- liveBranch(parent, name)
      merge <- b.status match
        case BranchStatus.Proposed =>
          store
            .listBranchMerges(b.id)
            .find(_.status == BranchMergeStatus.Proposed)
            .toRight(BranchFailure.conflict("not_proposed", s"branch '$name' has no open proposal"))
        case other =>
          Left(
            BranchFailure.conflict(
              "not_proposed",
              s"branch '$name' is ${other.wire}; propose it first"
            )
          )
      _ <-
        if merge.id == proposalId then Right(())
        else
          Left(
            BranchFailure.conflict(
              "proposal_replaced",
              s"the open proposal of branch '$name' is ${merge.id}, not $proposalId; read it, then " +
                "merge it by its id"
            )
          )
      _ <-
        if merge.proposer == actor.identity then
          Left(
            BranchFailure.forbidden(
              "self_merge_forbidden",
              s"'${actor.identity}' proposed this merge; a different principal must approve it"
            )
          )
        else Right(())
    yield (parent, b, merge)
    def denied(f: BranchFailure): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
      audit.rest(
        apiKey,
        "control-plane",
        AuditActions.BranchMerge,
        "denied",
        tenant = Some(tenant),
        detail = Map("error" -> f.code)
      )
      IO.pure(Left(f))
    pre match
      case Left(f)               => denied(f)
      case Right((parent, _, _)) =>
        val lockKey = PoolKey(tenant, parent.name, "__merge")
        // Read again under the lock: another merge or a new proposal may have gone first.
        sup.poolLocks.withLock(lockKey)(
          IO.blocking(pre).flatMap {
            case Left(f)                    => denied(f)
            case Right((current, b, merge)) =>
              runMerge(tenant, current, b, merge, expectedMainSnapshot, holdWriters, actor, apiKey)
          }
        )

  private def runMerge(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      merge: BranchMerge,
      expectedMainSnapshot: Option[Long],
      holdWriters: Boolean,
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
    // This proposal's commit, found on main: tagged and torn down, never applied again.
    def settle(snapshot: Long, attempts: Int): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
      computeChanges(tenant, parent, b, countsAs = None).flatMap(cs =>
        finishMerge(tenant, parent, b, merge, cs, snapshot, holdWriters, attempts, actor, apiKey)
      )

    // What a refusal does to the proposal (design section 8.4): one that no longer reflects reality
    // closes, and the branch reopens for a new one; otherwise it stays open for a retry. A proposal
    // closes only after a look. A late batch can land at any point of a run, and its own commit can
    // be what fails the check, on a table it created ("also created on main"): that merge landed,
    // so it settles, with its tag and its event. A failed look raises, which leaves the row open.
    def refuse(
        f: BranchFailure,
        closes: Boolean,
        attempts: Int
    ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
      if closes then
        IO.blocking(landedCommit(tenant, parent, b)).flatMap {
          case Some((landed, snapshot)) if landed.id == merge.id =>
            // Before any attempt of this run, the count is the one of the run that sent it.
            settle(snapshot, if attempts > 0 then attempts else merge.attempts)
          case _ => recordRefusal(f, closes, attempts)
        }
      else recordRefusal(f, closes, attempts)

    def recordRefusal(
        f: BranchFailure,
        closes: Boolean,
        attempts: Int
    ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
      IO.blocking {
        if closes then
          store.updateBranchMerge(
            merge.copy(
              status = BranchMergeStatus.Failed,
              approver = Some(actor.identity),
              error = Some(f.message),
              decidedAt = Some(now()),
              holdWriters = holdWriters,
              attempts = attempts
            )
          )
          store.updateBranch(b.copy(status = BranchStatus.Open))
        else if attempts > 0 then
          store.updateBranchMerge(
            merge.copy(
              // An earlier send that timed out can still land: this refusal says nothing about it.
              error =
                merge.error.filter(_.startsWith(MergeExecutor.TimedOut)).orElse(Some(f.message)),
              holdWriters = holdWriters,
              attempts = attempts
            )
          )
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.BranchMerge,
          "denied",
          tenant = Some(tenant),
          target = Some(s"${parent.name}/${b.name}"),
          detail = Map("error" -> f.code)
        )
        Left(f)
      }

    // Validated twice: once before the freeze, so a merge refused anyway never restarts the
    // branch's node, and again on the frozen branch, whose head can no longer move.
    def validateAndRun: IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
      computeChanges(tenant, parent, b, countsAs = None).flatMap { early =>
        refusalOf(b, merge, early, expectedMainSnapshot) match
          case Some((f, closes)) => refuse(f, closes, 0)
          case None              =>
            withFrozenBranch(b) {
              computeChanges(tenant, parent, b, countsAs = None).flatMap { cs =>
                refusalOf(b, merge, cs, expectedMainSnapshot) match
                  case Some((f, closes)) => refuse(f, closes, 0)
                  case None              =>
                    val stored = for
                      keys     <- BranchService.keysOf(merge.mergeKeysJson)
                      proposed <- RowCounts.listFromJson(merge.rowConflictsJson)
                    yield (keys, proposed)
                    stored match
                      case Left(reason) =>
                        refuse(BranchFailure.upstream("merge_failed", reason), true, 0)
                      case Right((keys, proposed)) =>
                        runAttempts(
                          tenant,
                          parent,
                          b,
                          merge,
                          cs,
                          keys,
                          proposed,
                          expectedMainSnapshot,
                          holdWriters,
                          actor,
                          apiKey,
                          refuse
                        )
              }
            }
      }

    // A commit that landed and never settled (the manager died between them) is finished, never
    // applied again: with no key, a row merge would insert the branch's new rows a second time, and
    // nothing would notice.
    IO.blocking(landedCommit(tenant, parent, b)).flatMap {
      case Some((landed, snapshot)) if landed.id == merge.id => settle(snapshot, merge.attempts)
      case Some((landed, snapshot)) => refuse(landedElsewhere(landed, snapshot), false, 0)
      case None                     => validateAndRun
    }

  /** Why `cs` cannot be merged, if it cannot, and whether that closes the proposal. */
  private def refusalOf(
      b: Branch,
      merge: BranchMerge,
      cs: BranchChangeSet,
      expectedMainSnapshot: Option[Long]
  ): Option[(BranchFailure, Boolean)] =
    if !merge.headSnapshotAtPropose.contains(cs.headSnapshot) then
      Some(
        (
          BranchFailure.conflict(
            "proposal_stale",
            s"branch '${b.name}' changed since it was proposed; propose it again"
          ),
          true
        )
      )
    else if expectedMainSnapshot.exists(_ != cs.mainSnapshot) then
      Some(
        (
          BranchFailure.conflict(
            "concurrent_write",
            s"main is at snapshot ${cs.mainSnapshot}, expected ${expectedMainSnapshot.get}"
          ),
          false
        )
      )
    else if cs.conflicts.nonEmpty then
      Some(
        (
          BranchFailure.conflict(
            "merge_conflict",
            "cannot be merged: " +
              cs.conflicts.map(c => s"${c.schema}.${c.table} (${c.reason})").mkString("; ")
          ),
          true
        )
      )
    else if cs.unsupported.nonEmpty || cs.tables.exists(!_.mergeable) then
      val alt = cs.tables
        .filterNot(_.mergeable)
        .map(t => s"${t.schema}.${t.table}: ${t.reason.getOrElse("")}")
      Some(
        (
          BranchFailure.unprocessable(
            "merge_unsupported",
            "cannot be merged: " + (alt ++ cs.unsupported).mkString("; ")
          ),
          false
        )
      )
    else if cs.tables.isEmpty then
      Some(
        (
          BranchFailure.unprocessable("nothing_to_merge", s"branch '${b.name}' has no changes"),
          false
        )
      )
    else None

  /** Runs `body` with the branch's writes frozen: its pool disabled (no new connection, never woken
    * by a query) and suspended (its nodes stopped, so no open session writes either), so the branch
    * head the change set is computed at is the head that merges, and no write lands between the
    * merge and the teardown that follows it, where it would be lost. Unless `body` merged, the pool
    * is put back as it was (enabled and resumed only when it was so before). A pool already gone
    * has nothing to freeze.
    */
  private def withFrozenBranch(b: Branch)(
      body: IO[Res[(Branch, BranchMerge, BranchChangeSet)]]
  ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
    val key = poolKeyOf(b)
    sup.get(key) match
      case None        => body
      case Some(state) =>
        val wasDisabled                                                   = state.disabled
        val wasSuspended                                                  = state.suspended
        def failed(what: String)(e: Either[SupervisorError, ?]): IO[Unit] = e match
          case Left(err) => IO.raiseError(new IllegalStateException(s"$what: ${err.message}"))
          case Right(_)  => IO.unit
        val freeze =
          (if wasDisabled then IO.unit
           else sup.setPoolDisabled(key, disabled = true).flatMap(failed("disable"))) *>
            (if wasSuspended then IO.unit
             else sup.suspendPool(key, reason = "merge").flatMap(failed("suspend")))
        val thaw =
          (if wasSuspended then IO.unit
           else sup.resumePool(key, reason = "merge").flatMap(failed("resume"))) *>
            (if wasDisabled then IO.unit
             else sup.setPoolDisabled(key, disabled = false).flatMap(failed("enable")))
        val thawLogged = thaw.handleErrorWith(e =>
          IO.delay(
            logger.warn(
              s"branch merge: pool $key not restored after the merge did not complete: " +
                s"${e.getMessage}; enable and resume it by hand"
            )
          )
        )
        freeze.attempt.flatMap {
          case Left(e) =>
            thawLogged *> IO.pure(
              Left(
                BranchFailure.upstream(
                  "merge_failed",
                  s"cannot freeze the branch's writes for the merge: ${e.getMessage}"
                )
              )
            )
          case Right(_) =>
            body.guaranteeCase {
              case Outcome.Succeeded(res) =>
                res.flatMap {
                  case Right(_) => IO.unit // merged: the teardown already removed the pool
                  case Left(_)  => thawLogged
                }
              case _ => thawLogged
            }
        }

  /** Prepare once, then attempts of Decide and Apply under the fence (design section 5.2). The
    * commit message on main is the authority on whether an attempt committed.
    */
  private def runAttempts(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      merge: BranchMerge,
      first: BranchChangeSet,
      keys: Map[String, List[String]],
      proposed: List[RowCounts],
      expectedMainSnapshot: Option[Long],
      holdWriters: Boolean,
      actor: BranchActor,
      apiKey: Option[String],
      refuse: (BranchFailure, Boolean, Int) => IO[Res[(Branch, BranchMerge, BranchChangeSet)]]
  ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
    import BranchService.AttemptEnd
    val alias   = parentAliasOf(tenant, parent)
    val message = BranchMergeSql.commitMessage(b.name, merge.id, merge.proposer)
    val author  = BranchMergeSql.author(tenant, actor.identity)
    val policy  = merge.onConflict.flatMap(ConflictPolicy.fromWire)

    val meta       = sup.effectiveMetastoreFor(tenant, parent.name)
    val parentMeta = meta.updated("dbName", meta.getOrElse("dbName", parent.name))
    val holdSec    = Option.when(holdWriters)(cfg.mergeTimeoutSec)
    val runner     =
      new RowMergeRunner(alias, BranchMergeSql.BranchAlias, first.forkSnapshot, policy)

    // A batch that timed out may still be running, where a stop returned with the node alive, and a
    // commit with no fence row passes the trigger. So the row stays after a timed-out run and after
    // a retry of one: it matches only its own message, which both batches share. Until a retry
    // arms, it holds the late batch to the S of the batch's own run. From the retry's arm on, it
    // holds that batch to the retry's S: the batch is refused once a merged table moved after that
    // S, the retry's own commit included, and passes the fence if nothing committed since. Main's
    // commits from before that S are then left to DuckLake's own check, which starts at the batch's
    // first catalog read: a commit between the batch's S and that read has no check at all, and the
    // batch's stale UPDATE can overwrite it. A known residual, in the design. A retry's hold still
    // ends with the merge: arming with no hold clears it and keeps the fence. A timed-out run's own
    // hold has expired by then.
    val earlierTimeout    = merge.error.exists(_.startsWith(MergeExecutor.TimedOut))
    def release: IO[Unit] =
      if !earlierTimeout then IO.blocking(mergeFence.disarm(parentMeta, message))
      else if !holdWriters then IO.unit
      else
        IO.blocking(
          mergeFence.arm(
            parentMeta,
            message,
            first.mainSnapshot,
            BranchService.fencedIds(first.tables),
            None
          )
        ).flatMap {
          case Left(err) => IO(logger.error(s"branch merge: hold not cleared, it expires: $err"))
          case Right(_)  => IO.unit
        }

    def planRefusal(f: BranchFailure): AttemptEnd =
      AttemptEnd.Refused(f, closes = f.code == "history_gap")

    /** The look after `reason`, what a stage or a send answered. A send that timed out can still
      * land, and a stalled catalog is a likely cause of both, so after one a look that fails reads
      * as not found: `Failed` then keeps the fence row and `failed` stores the prefix, the only
      * record that a batch can still land. After any other answer a failed look raises.
      */
    def lookAfter(reason: Option[String]): IO[Option[(BranchMerge, Long)]] =
      IO.blocking(landedCommit(tenant, parent, b)).handleErrorWith {
        case NonFatal(e) if reason.exists(_.startsWith(MergeExecutor.TimedOut)) =>
          IO.blocking(
            logger.error(
              s"branch merge: cannot look for the commits of ${parent.name}@${b.name} after a " +
                s"send that timed out, taking them as not landed: ${e.getMessage}"
            )
          ).as(None)
        case e => IO.raiseError(e)
      }

    def decideAndApply(
        node: MergeNode,
        cs: BranchChangeSet,
        tables: List[RowMergeTable],
        ranged: Boolean
    ): IO[AttemptEnd] =
      runner.decide(node, tables).flatMap {
        case Left(err)     => IO.pure(AttemptEnd.Failed(err))
        case Right(counts) =>
          val others = cs.tables.exists(_.kind != ChangeKind.Modified)
          MergeDecision.decide(counts, proposed, policy, others) match
            case MergeDecision.Verdict.Refuse(f) =>
              IO.pure(AttemptEnd.Refused(f, closes = true))
            case MergeDecision.Verdict.NothingToDo =>
              IO.pure(
                AttemptEnd.Refused(
                  BranchFailure.unprocessable(
                    "nothing_to_merge",
                    s"every change on branch '${b.name}' is already on main; discard it"
                  ),
                  closes = false
                )
              )
            case MergeDecision.Verdict.Proceed =>
              val batch = BranchMergeSql.applyBatch(
                parentAlias = alias,
                branchAlias = BranchMergeSql.BranchAlias,
                tableChanges = cs.tables,
                rowMerges = tables.zip(counts),
                ranged = ranged,
                updateChunkRows = cfg.mergeUpdateChunkRows,
                fork = cs.forkSnapshot,
                head = cs.headSnapshot,
                author = author,
                message = message
              )
              // A manager that dies in this send leaves what a send that timed out leaves: its
              // batch can still land. Whatever the send answers replaces this mark, since `refuse`
              // and `finishMerge` copy from the row read at the start of the run.
              IO.blocking(
                store.updateBranchMerge(merge.copy(error = Some(BranchService.ApplySent)))
              ) >>
                node.exec(batch).flatMap { sent =>
                  lookAfter(sent.left.toOption).map {
                    case Some((landed, snapshot)) if landed.id == merge.id =>
                      AttemptEnd.Committed(snapshot, cs)
                    // This send's own batch can still land when it timed out: `Failed` keeps its
                    // fence row and its prefix, and the next look refuses.
                    case Some((landed, snapshot))
                        if !sent.left.exists(_.startsWith(MergeExecutor.TimedOut)) =>
                      AttemptEnd.Refused(landedElsewhere(landed, snapshot), closes = false)
                    case _ =>
                      sent match
                        case Right(_) => AttemptEnd.Failed("committed snapshot not found")
                        case Left(err) if MergeDecision.mainMoved(err) =>
                          AttemptEnd.Retry(ranged, err)
                        case Left(err) if MergeDecision.outOfMemory(err) && !ranged =>
                          AttemptEnd.Retry(ranged = true, err)
                        case Left(err) => AttemptEnd.Failed(err)
                  }
                }
      }

    def attempt(node: MergeNode, n: Int, ranged: Boolean): IO[(AttemptEnd, Int)] =
      computeChanges(tenant, parent, b, countsAs = None).flatMap { cs =>
        // Past the first look, node start and Prepare are a long window for a late batch to land
        // in, below the S this attempt's fence starts at, where neither the fence nor DuckLake's
        // own check sees it. Reading S first makes this look complete: a landing it misses is above
        // S, so the fence refuses the send and the look after the send finds it.
        IO.blocking(landedCommit(tenant, parent, b)).flatMap {
          case Some((landed, snapshot)) if landed.id == merge.id =>
            IO.pure((AttemptEnd.Committed(snapshot, cs), n))
          case Some((landed, snapshot)) =>
            IO.pure((AttemptEnd.Refused(landedElsewhere(landed, snapshot), closes = false), n))
          case None => decideAtHead(node, cs, n, ranged)
        }
      }

    /** The rest of an attempt, with main's head S read in `cs` and no commit of the branch at or
      * below it.
      */
    def decideAtHead(
        node: MergeNode,
        cs: BranchChangeSet,
        n: Int,
        ranged: Boolean
    ): IO[(AttemptEnd, Int)] =
      refusalOf(b, merge, cs, if n == 1 then expectedMainSnapshot else None) match
        case Some((f, closes)) => IO.pure((AttemptEnd.Refused(f, closes), n))
        case None              =>
          IO.blocking(rowTables(tenant, parent, b, cs, keys)).flatMap {
            case Left(f)       => IO.pure((planRefusal(f), n))
            case Right(tables) =>
              IO.blocking(
                mergeFence.arm(
                  parentMeta,
                  message,
                  cs.mainSnapshot,
                  BranchService.fencedIds(cs.tables),
                  holdSec
                )
              ).flatMap {
                case Left(err) =>
                  IO.pure(
                    (
                      AttemptEnd.Refused(
                        BranchFailure.upstream("merge_failed", err),
                        closes = false
                      ),
                      n
                    )
                  )
                case Right(_) =>
                  // A send that timed out can still commit, so nothing disarms here: the guarantee
                  // around withNode decides, once the node's stop has returned.
                  decideAndApply(node, cs, tables, ranged)
                    .flatMap {
                      case AttemptEnd.Retry(next, _) if n < cfg.mergeMaxAttempts =>
                        // Under the hold, no release between attempts: at most one stale commit
                        // can land once the hold is up, and each release lets in another.
                        val pause =
                          if holdWriters then IO.unit else release *> IO.sleep(attemptWait)
                        pause *> attempt(node, n + 1, next)
                      case AttemptEnd.Retry(_, reason) if MergeDecision.outOfMemory(reason) =>
                        IO.pure((AttemptEnd.Failed(reason), n))
                      case AttemptEnd.Retry(_, _) =>
                        IO.pure(
                          (
                            AttemptEnd.Refused(
                              BranchFailure.conflict(
                                "concurrent_write",
                                s"main kept moving across $n attempts; merge again, or merge " +
                                  "with --hold-writers if the writers are on the merged tables"
                              ),
                              closes = false
                            ),
                            n
                          )
                        )
                      case end => IO.pure((end, n))
                    }
              }
          }

    /** Holds writers before main's head is read. A commit between that read and the arm is not
      * held, and the fence refuses the attempt: on a hot table, every attempt. `holdSettle` lets
      * commits already past the hold land first; the arm at main's head then starts after them.
      */
    def holdFirst: IO[Either[String, Unit]] =
      if !holdWriters then IO.pure(Right(()))
      else
        IO.blocking(
          mergeFence.arm(
            parentMeta,
            message,
            first.mainSnapshot,
            BranchService.fencedIds(first.tables),
            holdSec
          )
        ).flatMap(armed => if armed.isRight then IO.sleep(holdSettle).as(armed) else IO.pure(armed))

    /** A stage that timed out leaves the proposal open: its send can still land, where a stop
      * returned with the node alive, as Kubernetes and fleet can, and the next merge settles that
      * commit under this proposal. Closed, it could only be refused, and the branch discarded.
      */
    def failed(reason: String, attempts: Int): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
      val closes = !reason.startsWith(MergeExecutor.TimedOut)
      refuse(
        BranchFailure.upstream("merge_failed", MergeDecision.failureText(reason)),
        closes,
        attempts
      )

    sup.mergeNodeSpec(tenant, parent.name, b.tenantDbName, b.dataPath) match
      case None =>
        refuse(
          BranchFailure.upstream("merge_failed", "cannot build the merge node spec"),
          false,
          0
        )
      case Some(spec) =>
        mergeExecutor
          .withNode[(AttemptEnd, Int)](spec) { node =>
            IO.blocking(rowTables(tenant, parent, b, first, keys)).flatMap {
              case Left(f)       => IO.pure(Right((planRefusal(f), 0)))
              case Right(tables) =>
                runner
                  .prepare(node, tables)
                  .flatMap {
                    case Left(err) => IO.pure(Left(err))
                    case Right(_)  => runner.warmUp(node, tables)
                  }
                  .flatMap {
                    case Left(err) => IO.pure(Right((AttemptEnd.Failed(err), 0)))
                    case Right(_)  =>
                      holdFirst.flatMap {
                        case Left(err) =>
                          val refused = AttemptEnd.Refused(
                            BranchFailure.upstream("merge_failed", err),
                            closes = false
                          )
                          IO.pure(Right((refused, 0)))
                        case Right(_) => attempt(node, 1, ranged = false).map(Right(_))
                      }
                  }
            }
          }
          .guaranteeCase {
            case Outcome.Succeeded(res) =>
              res.flatMap {
                case Left(reason) if reason.startsWith(MergeExecutor.TimedOut) => IO.unit
                case Right((AttemptEnd.Failed(reason), _))
                    if reason.startsWith(MergeExecutor.TimedOut) =>
                  IO.unit
                case _ => release
              }
            case _ => release
          }
          .flatMap { ended =>
            // The node's stop has returned, so on most runtimes nothing more can land. A send that
            // timed out can have committed after the attempt's own look: look once more.
            def unlessLanded(reason: String, attempts: Int) =
              lookAfter(Some(reason)).flatMap {
                case Some((landed, snapshot)) if landed.id == merge.id =>
                  finishMerge(
                    tenant,
                    parent,
                    b,
                    merge,
                    first,
                    snapshot,
                    holdWriters,
                    attempts,
                    actor,
                    apiKey
                  )
                // This send's own batch can still land when it timed out: `failed` keeps its
                // prefix, and the next look refuses.
                case Some((landed, snapshot)) if !reason.startsWith(MergeExecutor.TimedOut) =>
                  refuse(landedElsewhere(landed, snapshot), false, attempts)
                case _ => failed(reason, attempts)
              }
            ended match
              case Left(reason)                                   => unlessLanded(reason, 0)
              case Right((AttemptEnd.Committed(snapshot, cs), n)) =>
                finishMerge(tenant, parent, b, merge, cs, snapshot, holdWriters, n, actor, apiKey)
              case Right((AttemptEnd.Refused(f, closes), n)) => refuse(f, closes, n)
              case Right((AttemptEnd.Failed(reason), n))     => unlessLanded(reason, n)
              case Right((AttemptEnd.Retry(_, reason), n))   => unlessLanded(reason, n)
          }

  private def finishMerge(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      merge: BranchMerge,
      cs: BranchChangeSet,
      snapshot: Long,
      holdWriters: Boolean,
      attempts: Int,
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
    IO.blocking {
      val tagName = s"merge-${b.name}-${merge.id.stripPrefix("mg-").take(8)}"
      store.createSnapshotTag(
        SnapshotTag(
          Names.newSurrogateId("stag"),
          tenant,
          parent.name,
          tagName,
          snapshot,
          isProtected = false,
          createdBy = Some(actor.identity)
        )
      ) match
        case Left(err) => logger.warn(s"branch merge: tag '$tagName' not created: $err")
        case Right(_)  => ()
      // A settle on a branch thawed after an earlier run: what users wrote on it since the proposal
      // never reached main, and the teardown below removes it. Only snapshot ids, no row of it.
      val branchMoved = !merge.headSnapshotAtPropose.contains(cs.headSnapshot)
      val atProposal  = merge.headSnapshotAtPropose.fold("unknown")(_.toString)
      if branchMoved then
        logger.error(
          s"branch merge: settling $tenant/${parent.name}@${b.name} at main snapshot $snapshot " +
            s"with the branch at snapshot ${cs.headSnapshot}, proposed at $atProposal: the " +
            "teardown removes what was written on it since"
        )
      val doneMerge = store
        .updateBranchMerge(
          merge.copy(
            status = BranchMergeStatus.Merged,
            approver = Some(actor.identity),
            mainSnapshotAfter = Some(snapshot),
            tagName = Some(tagName),
            decidedAt = Some(now()),
            error = None, // an earlier attempt's, now that it merged
            holdWriters = holdWriters,
            attempts = attempts
          )
        )
        .getOrElse(merge)
      val doneBranch = store.updateBranch(b.copy(status = BranchStatus.Merged)).getOrElse(b)
      audit.rest(
        apiKey,
        "control-plane",
        AuditActions.BranchMerge,
        "ok",
        tenant = Some(tenant),
        target = Some(s"${parent.name}/${b.name}"),
        detail = Map(
          "mergeId"     -> merge.id,
          "proposer"    -> merge.proposer,
          "approver"    -> actor.identity,
          "snapshot"    -> snapshot.toString,
          "tag"         -> tagName,
          "tables"      -> cs.tables.size.toString,
          "holdWriters" -> holdWriters.toString,
          "attempts"    -> attempts.toString,
          "onConflict"  -> merge.onConflict.getOrElse("none")
        ) ++ (
          if branchMoved then
            Map(
              "branchHeadAtProposal" -> atProposal,
              "branchHeadAtSettle"   -> cs.headSnapshot.toString
            )
          else Map.empty
        )
      )
      events.emit(
        ManagerEvent
          .BranchMerged(tenant, parent.name, b.name, merge.proposer, actor.identity, snapshot)
      )
      logger.info(
        s"branch merged: $tenant/${parent.name}@${b.name} -> snapshot $snapshot (tag $tagName)"
      )
      (doneBranch, doneMerge)
    }.flatMap { case (doneBranch, doneMerge) =>
      teardown(doneBranch, "merged").as(Right((doneBranch, doneMerge, cs)))
    }

  // ---------- discard / expiry / teardown ----------

  def discard(
      tenant: String,
      parentDb: String,
      name: String,
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[Branch]] =
    val pre = for
      _      <- enabled
      parent <- parentOf(tenant, parentDb)
      b      <- liveBranch(parent, name)
      _      <-
        if actor.isAdmin || b.ownerUser == actor.identity then Right(())
        else
          Left(
            BranchFailure.forbidden(
              "not_owner",
              s"branch '$name' belongs to '${b.ownerUser}'; only the owner or a tenant admin may discard it"
            )
          )
    yield (parent, b)
    pre match
      case Left(f) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.BranchDiscard,
          "denied",
          tenant = Some(tenant)
        )
        IO.pure(Left(f))
      case Right((parent, b)) =>
        retire(b, BranchStatus.Discarded, "discard").map { done =>
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.BranchDiscard,
            "ok",
            tenant = Some(tenant),
            target = Some(s"${parent.name}/$name"),
            detail = Map("branchId" -> b.id)
          )
          Right(done)
        }

  /** Expire every live branch whose TTL has passed; returns the branch names discarded. Run by the
    * leader's sweep.
    */
  def expireDue(): IO[List[Branch]] = IO.defer {
    val sweptAt = now()
    val due     = sup
      .listTenants()
      .flatMap(t => store.listTenantBranches(t.id, BranchStatus.Live))
      .filter(b => b.expiresAt.exists(_.isBefore(sweptAt)))
    due.foldLeft(IO.pure(List.empty[Branch])) { (acc, b) =>
      acc.flatMap { done =>
        retire(b, BranchStatus.Expired, "expired").attempt.map {
          case Right(x) =>
            audit.restAs(
              "system",
              "system",
              "control-plane",
              AuditActions.BranchExpire,
              "ok",
              tenant = Some(b.tenant),
              target = Some(s"${b.parentDbName}/${b.name}"),
              detail = Map("branchId" -> b.id)
            )
            done :+ x
          case Left(e) =>
            logger.warn(
              s"branch expiry: ${b.tenant}/${b.parentDbName}@${b.name} failed: ${e.getMessage}"
            )
            done
        }
      }
    }
  }

  /** Status transition + open merge abandonment, then teardown. */
  private def retire(b: Branch, status: BranchStatus, reason: String): IO[Branch] =
    IO.blocking {
      store.listBranchMerges(b.id).filter(_.status == BranchMergeStatus.Proposed).foreach { m =>
        store.updateBranchMerge(
          m.copy(status = BranchMergeStatus.Abandoned, decidedAt = Some(now()))
        )
      }
      store.updateBranch(b.copy(status = status)).getOrElse(b)
    }.flatMap(done => teardown(done, reason).as(done))

  /** Free the branch's compute and catalog: pool (stop nodes), tenant-db row + Postgres database
    * (through the supervisor, which also evicts the catalog reader), then the branch-only files.
    * Idempotent: every step tolerates "already gone". The branch row is kept as history with
    * `purgedAt` stamped once the files are gone.
    */
  def teardown(b: Branch, reason: String): IO[Unit] =
    val key = poolKeyOf(b)
    sup.deletePool(key, force = true).attempt.flatMap {
      case Left(e)  => IO.delay(logger.warn(s"branch teardown: pool $key: ${e.getMessage}"))
      case Right(_) => IO.unit
    } *> sup.deleteTenantDb(b.tenant, b.tenantDbName).attempt.flatMap {
      case Right(Right(()))                                      => IO.unit
      case Right(Left(err)) if err.message.contains("not found") => IO.unit
      case Right(Left(err))                                      =>
        IO.delay(logger.warn(s"branch teardown: tenant-db '${b.tenantDbName}': ${err.message}"))
      case Left(e) =>
        IO.delay(
          logger.warn(s"branch teardown: tenant-db '${b.tenantDbName}' threw: ${e.getMessage}")
        )
    } *> IO
      .blocking {
        val objectStore =
          sup.findTenantDb(b.tenant, b.parentDbName).map(_.objectStore).getOrElse(Map.empty)
        purgeFiles(b.dataPath, objectStore) match
          case Right(_) =>
            store.updateBranch(b.copy(purgedAt = Some(now())))
            ()
          case Left(err) =>
            logger.warn(s"branch teardown: files under '${b.dataPath}' not purged: $err")
        events.emit(ManagerEvent.BranchDiscarded(b.tenant, b.parentDbName, b.name, reason))
      }
      .handleErrorWith { case NonFatal(e) =>
        IO.delay(logger.warn(s"branch teardown: purge step threw: ${e.getMessage}"))
      }

object BranchService:

  /** A proposal's `error` while an Apply is on its way. With the prefix of a send that timed out,
    * so that a manager dying in the send reads like one: the next run keeps the fence row, and
    * propose refuses.
    */
  private[branch] val ApplySent: String =
    s"${MergeExecutor.TimedOut}an Apply was sent and has not answered"

  /** The tables a merge's fence watches. Only a table the branch created has no id; any other with
    * none would stay outside the fence with no error.
    */
  private[branch] def fencedIds(tables: List[TableChange]): Set[Long] =
    tables.flatMap { t =>
      if t.tableId.isEmpty && t.kind != ChangeKind.Created then
        throw new IllegalArgumentException(s"changed table ${t.schema}.${t.table} has no id")
      t.tableId
    }.toSet

  /** How one merge attempt ended. */
  private[branch] enum AttemptEnd:
    case Committed(snapshot: Long, cs: BranchChangeSet)
    case Retry(ranged: Boolean, reason: String)
    case Refused(failure: BranchFailure, closes: Boolean)
    case Failed(reason: String)

  /** A proposal's stored keys. Unreadable is an error, never "no keys": a double insert would then
    * simply land.
    */
  def keysOf(json: String): Either[String, Map[String, List[String]]] =
    io.circe.parser
      .decode[Map[String, List[String]]](json)
      .left
      .map(_ => "the proposal's keys cannot be read; propose again")

  def summaryJson(cs: BranchChangeSet): Json =
    Json.obj(
      "forkSnapshot" -> cs.forkSnapshot.asJson,
      "headSnapshot" -> cs.headSnapshot.asJson,
      "mainSnapshot" -> cs.mainSnapshot.asJson,
      "tables"       -> Json.arr(cs.tables.map { t =>
        Json.obj(
          "schema"   -> t.schema.asJson,
          "table"    -> t.table.asJson,
          "kind"     -> t.kind.wire.asJson,
          "inserted" -> t.inserted.asJson,
          "deleted"  -> t.deleted.asJson,
          "updated"  -> t.updated.asJson
        )
      }*),
      "unsupported" -> cs.unsupported.asJson
    )

  def conflictsJson(cs: BranchChangeSet): Json =
    Json.arr(cs.conflicts.map { c =>
      Json.obj("schema" -> c.schema.asJson, "table" -> c.table.asJson, "reason" -> c.reason.asJson)
    }*)
