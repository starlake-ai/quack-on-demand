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
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** Who is asking, on the tenant in hand, once the handlers' one gate has let them by: it decides
  * who may connect, and this service trusts its actor (`BranchHandlers.gate`). `isAdmin` = an admin
  * of THIS tenant, or a superuser; every other principal is a plain data-plane user, who proposes
  * and discards only their own branches, asks for no branch with no TTL, and never merges. `sees`:
  * whether a reply may name a table (schema, table): an admin sees them all, anyone else the tables
  * they hold a read grant on, exactly what filtered metadata lists. `branchOnly` = the credential
  * is a PAT restricted to branch writes: it may create and write branches, but a merge writes main,
  * so merge refuses it (`branch_only_token`).
  */
final case class BranchActor private (
    identity: String,
    isAdmin: Boolean,
    sees: (String, String) => Boolean,
    branchOnly: Boolean
):
  def seesTable(t: TableChange): Boolean = sees(t.schema, t.table)

object BranchActor:
  def admin(identity: String, branchOnly: Boolean = false): BranchActor =
    new BranchActor(identity, isAdmin = true, (_, _) => true, branchOnly)

  /** `sees` has no default: what a plain user may see is for the caller to say. */
  def plain(
      identity: String,
      sees: (String, String) => Boolean,
      branchOnly: Boolean = false
  ): BranchActor =
    new BranchActor(identity, isAdmin = false, sees, branchOnly)

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

/** Runs work on an ephemeral merge node built from `spec`: spawns it, waits for it to accept
  * connections, runs `body`, stops it whatever happens. `Left`: the node never came up, or what
  * `body` answered. Each call on the node is bounded: one that does not answer in time is abandoned
  * (the statement in flight may still run until the node is stopped, and nothing follows it) and
  * answers a `Left` that starts with [[MergeExecutor.TimedOut]], and the commit message on main
  * then decides what happened. An engine error can hold the word "timeout" too (a lock timeout), so
  * a consumer tests `startsWith(MergeExecutor.TimedOut)`, never `contains("timeout")`.
  */
trait MergeExecutor:
  def withNode[A](spec: NodeSpec)(body: MergeNode => IO[Either[String, A]]): IO[Either[String, A]]

object MergeExecutor:

  /** How the reason of a node call with an unknown outcome starts: it did not answer within its
    * bound, or the answer to a COMMIT was lost. Either way a commit can still land.
    */
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
  * resolve them through `BranchHandlers.gate` first, which also decides who may connect.
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

  private def noSuchBranch(parent: TenantDb, name: String): BranchFailure =
    BranchFailure.notFound(s"branch '$name' not found on '${parent.name}'")

  private def liveBranch(parent: TenantDb, name: String): Res[Branch] =
    store.findBranch(parent.id, name).toRight(noSuchBranch(parent, name))

  /** The lock a run of `b`, a propose or a merge, holds from start to end, and that a discard or an
    * expiry must get. Without it they would drop the catalog and files under the run, whose own
    * status write would then bring back a branch with nothing left. Its own lock and not the
    * parent's `__merge`: that one is held for a whole run of any branch of the database, so a
    * discard would fail while a sibling merges, and a plain user looping `propose` on their branch
    * would keep the others alive past their TTL. Like `__merge`, it names no real pool. A run takes
    * it inside the parent's lock ([[underRunLocks]]), and nothing inside a run takes it again:
    * `PoolLocker` has no nesting. [[retire]] only tries it, and holds it for the status writes.
    */
  private def runLock(b: Branch): PoolKey = PoolKey(b.tenant, b.parentDbName, s"__run_${b.id}")

  /** Whether the next sweep ends `b`. A propose past this is refused; a merge is not: it is an
    * admin's, it cannot loop, and it is how an unsettled proposal gets recorded.
    */
  private def pastTtl(b: Branch, at: Instant): Boolean = b.expiresAt.exists(_.isBefore(at))

  /** One run at a time per parent, since each can take a merge node, and none under a discard or an
    * expiry of its branch. Whoever enters reads the branch's row again first: a queued run holds no
    * lock of the branch, and a discard or a merge may have ended it since the look that found it.
    */
  private def underRunLocks[A](b: Branch)(run: IO[A]): IO[A] =
    sup.poolLocks.withLock(PoolKey(b.tenant, b.parentDbName, "__merge"))(
      sup.poolLocks.withLock(runLock(b))(run)
    )

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

  /** A branch is a database next to its parent's, and QoD creates databases on its own server only.
    */
  private def onQodServer(parent: TenantDb): Res[Unit] =
    if TenantDb.onDefaultServer(parent.metastore, sup.metastoreDefaults) then Right(())
    else
      Left(
        BranchFailure.badRequest(
          "branch_own_server",
          s"database '${parent.name}' lies on its own Postgres server, where QoD creates no " +
            "database, so it cannot be branched"
        )
      )

  private def enabled: Res[Unit] =
    if cfg.enabled then Right(())
    else
      Left(
        BranchFailure.badRequest("branching_disabled", "branching is disabled (QOD_BRANCH_ENABLED)")
      )

  // ---------- create ----------

  /** A non-admin asks for a TTL of at least an hour and at most the server's default, when it has
    * one: a branch with no TTL stops snapshot expiry above its fork for the whole database until
    * someone discards it, and keeps a node up. Asking for nothing takes the default, the operator's
    * own choice, even when it is no TTL.
    */
  private def validTtl(ttlHours: Option[Int], actor: BranchActor): Res[Unit] =
    val limit = cfg.defaultTtlHours
    ttlHours match
      case Some(h) if !actor.isAdmin && (h < 1 || (limit > 0 && h > limit)) =>
        val allowed =
          if limit > 0 then s"between 1 and $limit, the server's default" else "at least 1"
        Left(BranchFailure.badRequest("invalid_ttl", s"ttlHours must be $allowed"))
      case Some(h) if h < 0 =>
        Left(BranchFailure.badRequest("invalid_ttl", "ttlHours must be >= 0"))
      case _ => Right(())

  /** Only the branch's owner or an admin acts on it, `what` saying how. */
  private def ownerOrAdmin(b: Branch, actor: BranchActor, what: String): Res[Unit] =
    if actor.isAdmin || b.ownerUser == actor.identity then Right(())
    else
      Left(
        BranchFailure.forbidden(
          "not_owner",
          s"branch '${b.name}' belongs to '${b.ownerUser}'; only the owner or a tenant admin may " +
            s"$what it"
        )
      )

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
        name   <- BranchNames
          .validateName(rawName)
          .left
          .map(BranchFailure.badRequest("invalid_name", _))
        _ <- validTtl(ttlHours, actor)
        _ <- onQodServer(parent)
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
    * listing never fails on a hibernated pool or a table the requester may not read. `None`: no
    * counts.
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
      b      <- liveBranch(parent, name)
    yield (parent, b)
    pre match
      case Left(f)            => IO.pure(Left(f))
      case Right((parent, b)) =>
        computeChanges(tenant, parent, b)
          .flatMap { shape =>
            countsAs.fold(IO.pure(shape))(countAs(tenant, parent, b, shape, _, actor.seesTable))
          }
          .map { cs =>
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

  private def computeChanges(tenant: String, parent: TenantDb, b: Branch): IO[BranchChangeSet] =
    IO.blocking {
      val br = resolveReader(tenant, b.tenantDbName)
      val mr = resolveReader(tenant, parent.name)
      BranchChanges.compute(br, mr, b.forkSnapshot)
    }

  /** `cs` with change-feed counts of its modified tables, each counted as `caller`. A table `only`
    * leaves out shows no counts and no statement names it: its answer would be dropped from the
    * reply anyway, and a refused count is a decide call, a refusal in the authorization metrics and
    * a WARN, for anyone reading the branch to cause at will.
    */
  private def countAs(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      cs: BranchChangeSet,
      caller: ai.starlake.quack.ondemand.api.ExecCaller,
      only: TableChange => Boolean
  ): IO[BranchChangeSet] =
    val alias = parentAliasOf(tenant, parent)
    cs.tables
      .foldLeft(IO.pure(List.empty[TableChange])) { (acc, t) =>
        acc.flatMap { done =>
          if t.kind != ChangeKind.Modified then IO.pure(done :+ t)
          else if !only(t) then IO.pure(done :+ t.copy(countsUnavailable = true))
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
    // The branch as it is now: a merge or a discard may have ended it since the last look, and its
    // TTL may have passed. For every principal: a sweep skips a branch a run holds, so a loop of
    // proposals would keep it past its TTL, holding back snapshot expiry for the whole database.
    def proposable(parent: TenantDb): Res[Branch] =
      for
        b <- liveBranch(parent, name)
        _ <- b.status match
          case BranchStatus.Open | BranchStatus.Proposed => Right(())
          case other => Left(BranchFailure.conflict("not_open", s"branch '$name' is ${other.wire}"))
        _ <-
          if pastTtl(b, now()) then
            Left(
              BranchFailure.conflict(
                "branch_expired",
                s"branch '$name' passed its TTL and is being discarded"
              )
            )
          else Right(())
      yield b
    // Reads the catalog, so only inside IO.blocking: under the locks, and at the record step. Only
    // the branch whose lock this run holds: the name may have passed to a new one since.
    def replaceable(parent: TenantDb, locked: Branch): Res[Branch] =
      proposable(parent)
        .filterOrElse(_.id == locked.id, noSuchBranch(parent, name))
        .flatMap(b => unsettled(b).toLeft(b))
    val pre = for
      _      <- enabled
      parent <- parentOf(tenant, parentDb)
      // Before anything else: a proposal starts a node and replaces the open one.
      b <- liveBranch(parent, name)
      _ <- ownerOrAdmin(b, actor, "propose")
      _ <- proposable(parent)
    yield (parent, b)
    pre match
      case Left(f)                => denied(f)
      case Right((parent, first)) =>
        // One merge node at a time per parent, as for a merge, and a proposal never overlaps one: a
        // merge must not find its proposal replaced, nor a proposal revive what a merge ended.
        underRunLocks(first)(
          IO.blocking(replaceable(parent, first)).flatMap {
            case Left(f)  => denied(f)
            case Right(b) =>
              // The lifecycle's own summary (stored on the merge row): every modified table, counted
              // as the system caller, whoever proposes. The proposer's reply counts as the proposer.
              val system =
                ai.starlake.quack.ondemand.api.ExecCaller.system(s"branch-counts-$tenant")
              computeChanges(tenant, parent, b).flatMap { shape =>
                analyze(tenant, parent, b, shape, options, actor).flatMap {
                  case Left(f)     => denied(f)
                  case Right(rows) =>
                    (for
                      stored <- countAs(tenant, parent, b, shape, system, _ => true)
                      reply  <-
                        if countsAs.system then IO.pure(stored)
                        else countAs(tenant, parent, b, shape, countsAs, actor.seesTable)
                    yield (stored, reply)).flatMap { (cs, reply) =>
                      IO.blocking {
                        // The open proposal's commit can land during the analysis, on a node that
                        // outlived its stop, and the TTL can pass. The row comes with the look: a
                        // discard or an expiry needs this run's lock, so the branch is as it was.
                        replaceable(parent, first).map { current =>
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
  private def unsettled(b: Branch): Option[BranchFailure] =
    def mergeAgain = BranchFailure.conflict(
      "proposal_unsettled",
      s"the last merge of branch '${b.name}' may have landed on main; merge again, which settles " +
        "it (a discard is refused once it has landed)"
    )
    val timedOut = store
      .listBranchMerges(b.id)
      .filter(_.status == BranchMergeStatus.Proposed)
      .exists(_.error.exists(_.startsWith(MergeExecutor.TimedOut)))
    if timedOut then Some(mergeAgain)
    else
      landedCommit(b).map {
        case (m, _) if m.status == BranchMergeStatus.Proposed => mergeAgain
        case (m, snapshot)                                    => landedElsewhere(m, snapshot)
      }

  /** A branch's commit lands once. A COMMIT already sent can land after its merge stopped waiting,
    * on a node that outlived its stop or a dead manager, and its proposal may have closed or been
    * replaced by then. So every look reads the message of every proposal of the branch, whatever
    * its status, the open one first: its commit settles whatever else is on main. One catalog read
    * per proposal. Blocks.
    */
  private def landedCommit(b: Branch): Option[(BranchMerge, Long)] =
    lazy val reader = resolveReader(b.tenant, b.parentDbName)
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

  /** The refusal for a history or a snapshot the row merge cannot read. */
  private def failureOf(problem: RowMergeTable.Problem): BranchFailure = problem match
    case RowMergeTable.Problem.HistoryGap(catalog, missing) =>
      val whose = if catalog == "main" then "main's" else s"the $catalog's"
      BranchFailure.conflict(
        "history_gap",
        s"$whose snapshot $missing, at or after the fork, is gone, so its changes cannot be " +
          "read; discard this branch and create it again"
      )
    case RowMergeTable.Problem.MixedSnapshots(schema, table, snapshots) =>
      BranchFailure.unprocessable(
        "merge_unsupported",
        s"$schema.$table: snapshot(s) ${snapshots.mkString(", ")} cannot be read correctly: " +
          "they mix maintenance and data, or hold a change this version does not know"
      )

  /** The tables of `cs` that merge by rows, or why they cannot be read. A history with a snapshot
    * gone from either catalog is refused here first, whether or not any table merges by rows.
    */
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
      .map(failureOf)

  /** What a merge would do to the rows of `cs` now. Nothing to read when nothing merges by rows, or
    * when a table conflict or an unsupported change already rules the merge out. The history is
    * checked in every case, since a snapshot gone from either catalog hides touches from `cs` and a
    * proposal over it would record a change set that is not the branch's.
    */
  private def analyze(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      cs: BranchChangeSet,
      options: ProposeOptions,
      actor: BranchActor
  ): IO[Res[List[RowCounts]]] =
    IO.blocking {
      val br = resolveReader(tenant, b.tenantDbName)
      MergeDecision
        .keyProblem(
          options.keys,
          // A table the proposer does not see reads as one not in the change set: its columns are
          // not theirs to probe.
          cs.tables.filter(actor.seesTable),
          t => t.tableId.toList.flatMap(br.topLevelColumnsOfTableAt(_, cs.headSnapshot)).map(_.name)
        )
        .map(BranchFailure.badRequest("invalid_key", _))
        .toLeft(())
        .flatMap(_ =>
          // `rowTables` checks the history itself; where it is skipped, the check still runs.
          if !cs.mergeable then
            RowMergeTable
              .historyGap(cs, br, resolveReader(tenant, parent.name))
              .map(failureOf)
              .toLeft(Nil)
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
      _     <- alreadyMerged(parent, name, proposalId).toLeft(())
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
      case Left(f)              => denied(f)
      case Right((_, first, _)) =>
        // Read again under the locks: another merge, a new proposal or a discard may have gone
        // first. The proposal's id holds this to the branch whose lock it has: a new branch of the
        // same name has other proposals.
        underRunLocks(first)(
          IO.blocking(pre).flatMap {
            case Left(f)                    => denied(f)
            case Right((current, b, merge)) =>
              runMerge(tenant, current, b, merge, expectedMainSnapshot, holdWriters, actor, apiKey)
          }
        )

  /** The refusal for a proposal of this parent's branch `name` that has merged. What a retry reads
    * after an answer that was lost, as the runbook says to merge again, and what a second approver
    * reads: the branch is gone by then, or the name is a new branch's, so nothing further on could
    * say what became of their merge. Only `Merged`, and only a row of this branch: any other keeps
    * its answer. The parent was found in this tenant, so its id holds the tenant too.
    */
  private def alreadyMerged(
      parent: TenantDb,
      name: String,
      proposalId: String
  ): Option[BranchFailure] =
    for
      m <- store.getBranchMerge(proposalId).filter(_.status == BranchMergeStatus.Merged)
      _ <- store.getBranch(m.branchId).filter(b => b.parentDbId == parent.id && b.name == name)
    yield
      val snapshot = m.mainSnapshotAfter.fold("unknown")(_.toString)
      BranchFailure.conflict(
        "already_merged",
        s"proposal ${m.id} of branch '$name' is already merged: main snapshot $snapshot, tag " +
          m.tagName.getOrElse("none")
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
      computeChanges(tenant, parent, b).flatMap(cs =>
        finishMerge(
          tenant,
          parent,
          b,
          merge,
          cs,
          snapshot,
          holdWriters,
          attempts,
          actor,
          apiKey,
          afterRecord = letGo(tenant, parent, b, merge, cs, snapshot)
        )
      )

    // A look for the branch's commits: this proposal's settles, another's refuses.
    def unlessFound(
        next: => IO[Res[(Branch, BranchMerge, BranchChangeSet)]]
    ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
      IO.blocking(landedCommit(b)).flatMap {
        case Some((landed, snapshot)) if landed.id == merge.id => settle(snapshot, merge.attempts)
        case Some((landed, snapshot)) => refuse(landedElsewhere(landed, snapshot), false, 0)
        case None                     => next
      }

    // What a refusal does to the proposal (design section 8.4): one that no longer reflects reality
    // closes, and the branch reopens for a new one; otherwise it stays open for a retry. A proposal
    // closes only after a look. A late commit can land at any point of a run, and can itself be what
    // fails the check, on a table it created ("also created on main"): that merge landed,
    // so it settles, with its tag and its event. A failed look raises, which leaves the row open.
    def refuse(
        f: BranchFailure,
        closes: Boolean,
        attempts: Int
    ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
      if closes then
        IO.blocking(landedCommit(b)).flatMap {
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
      computeChanges(tenant, parent, b).flatMap { early =>
        refusalOf(b, merge, early, expectedMainSnapshot) match
          case Some((f, closes)) => refuse(f, closes, 0)
          case None              =>
            withFrozenBranch(b) {
              computeChanges(tenant, parent, b).flatMap { cs =>
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
                        def run = runAttempts(
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
                        // A held run arms at this head before its attempts look, and `release` arms
                        // there again, so it looks first, as `attempt` does before its own arm: a
                        // commit at or below this head settles here, and one that lands after this
                        // read lands above it, which those arms refuse.
                        if holdWriters then unlessFound(run) else run
              }
            }
      }

    // A commit that landed and never settled (the manager died between them) is finished, never
    // applied again: with no key, a row merge would insert the branch's new rows a second time, and
    // nothing would notice.
    unlessFound(validateAndRun)

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

    val parentMeta = parentMetaOf(tenant, parent)
    val holdSec    = Option.when(holdWriters)(cfg.mergeTimeoutSec)
    val runner     =
      new RowMergeRunner(alias, BranchMergeSql.BranchAlias, first.forkSnapshot, policy)

    // A send that timed out stops at the statement in flight. When that is its COMMIT, the commit can
    // still land, where a stop returned with the node alive, and a commit with no fence row passes
    // the trigger. So the row stays after a timed-out run and after a retry of one: it matches only
    // its own message, which both runs share. Until a retry arms, it holds the late commit to the S
    // of its own run. From the retry's arm on, it holds that commit to the retry's S: the commit is
    // refused once a merged table moved after that S, the retry's own commit included, and passes
    // the fence if nothing committed since. Main's commits from before that S are then left to
    // DuckLake's own check, which starts at the run's first catalog read: a commit between the run's
    // S and that read has no check at all, and its stale UPDATE can overwrite it. A known residual,
    // in the design. A retry's hold still ends with the merge: arming with no hold clears it and
    // keeps the fence. A timed-out run's own hold has expired by then, unless a look finds the
    // commit first: the settle ends it and keeps the row too ([[letGo]]). Every arm's base is below
    // any commit of this message: an attempt looks before its arm, a held run before its first, and
    // a settle arms below the commit it found.
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
      * record that a commit can still land. After any other answer a failed look raises.
      */
    def lookAfter(reason: Option[String]): IO[Option[(BranchMerge, Long)]] =
      IO.blocking(landedCommit(b)).handleErrorWith {
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
                    "nothing to write: every change on the branch is already on main, or loses to " +
                      "main under the proposal's policy; discard it"
                  ),
                  closes = false
                )
              )
            case MergeDecision.Verdict.Proceed =>
              val statements = BranchMergeSql.applyBatch(
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
              // A manager that dies in this send leaves what a send that timed out leaves: a
              // COMMIT in flight can still land, and nothing comes after the statement in flight.
              // Whatever the send answers replaces this mark, since `refuse` and `finishMerge` copy
              // from the row read at the start of the run.
              IO.blocking(
                store.updateBranchMerge(merge.copy(error = Some(BranchService.ApplySent)))
              ) >>
                node.exec(statements).flatMap { sent =>
                  lookAfter(sent.left.toOption).map {
                    case Some((landed, snapshot)) if landed.id == merge.id =>
                      AttemptEnd.Committed(snapshot, cs)
                    // This send's own commit can still land when it timed out: `Failed` keeps its
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
      computeChanges(tenant, parent, b).flatMap { cs =>
        // Past the first look, node start and Prepare are a long window for a late commit to land
        // in, below the S this attempt's fence starts at, where neither the fence nor DuckLake's
        // own check sees it. Reading S first makes this look complete: a landing it misses is above
        // S, so the fence refuses the send and the look after the send finds it.
        IO.blocking(landedCommit(b)).flatMap {
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
      * commits already past the hold land first; the arm at main's head then starts after them. Its
      * base is below any commit of this branch: a held run looks once it has read `first`.
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
                    apiKey,
                    afterRecord = letGo(tenant, parent, b, merge, first, snapshot)
                  )
                // This send's own commit can still land when it timed out: `failed` keeps its
                // prefix, and the next look refuses.
                case Some((landed, snapshot)) if !reason.startsWith(MergeExecutor.TimedOut) =>
                  refuse(landedElsewhere(landed, snapshot), false, attempts)
                case _ => failed(reason, attempts)
              }
            ended match
              case Left(reason)                                   => unlessLanded(reason, 0)
              case Right((AttemptEnd.Committed(snapshot, cs), n)) =>
                // With an earlier send behind it, this is a settle like the others, whichever send
                // landed: the hold goes, whichever run armed it, and the row stays below this
                // commit. With none, nothing else can land, and the guarantee above has dealt with
                // the fence.
                finishMerge(
                  tenant,
                  parent,
                  b,
                  merge,
                  cs,
                  snapshot,
                  holdWriters,
                  n,
                  actor,
                  apiKey,
                  afterRecord =
                    if earlierTimeout then letGo(tenant, parent, b, merge, cs, snapshot)
                    else IO.unit
                )
              case Right((AttemptEnd.Refused(f, closes), n)) => refuse(f, closes, n)
              case Right((AttemptEnd.Failed(reason), n))     => unlessLanded(reason, n)
              case Right((AttemptEnd.Retry(_, reason), n))   => unlessLanded(reason, n)
          }

  private def parentMetaOf(tenant: String, parent: TenantDb): Map[String, String] =
    val meta = sup.effectiveMetastoreFor(tenant, parent.name)
    meta.updated("dbName", meta.getOrElse("dbName", parent.name))

  /** Ends the hold of a proposal that a look found landed: the outcome a timed-out send or a lost
    * answer left unknown is known now, so writers must not stay refused until the hold expires. The
    * row stays: another send of it can still be alive, and only the row refuses that COMMIT
    * ([[MergeFence.settled]]). At every settle, whoever asked for a hold: the merge that settles a
    * commit need not be the one that sent it, nor does the record keep every run's flag, and this
    * row is never worse than the one there. Best effort: a hold left behind expires, and the row
    * there is already below the commit, so a failure is logged and changes no answer.
    */
  private def letGo(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      merge: BranchMerge,
      cs: BranchChangeSet,
      snapshot: Long
  ): IO[Unit] =
    def notSettled(why: String): IO[Unit] =
      IO.blocking(
        logger.error(s"branch merge: fence not updated at a settle, a hold expires: $why")
      )
    IO.blocking(
      mergeFence.settled(
        parentMetaOf(tenant, parent),
        BranchMergeSql.commitMessage(b.name, merge.id, merge.proposer),
        snapshot,
        BranchService.fencedIds(cs.tables)
      )
    ).flatMap(_.fold(notSettled, _ => IO.unit))
      .handleErrorWith(e => notSettled(e.getMessage))

  /** `afterRecord` runs once the merge is on record and before the teardown, whose purge can take
    * its time: required, since each caller knows what is left of the fence.
    */
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
      apiKey: Option[String],
      afterRecord: IO[Unit]
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
      afterRecord *> teardown(doneBranch, "merged").as(Right((doneBranch, doneMerge, cs)))
    }

  // ---------- discard / expiry / teardown ----------

  def discard(
      tenant: String,
      parentDb: String,
      name: String,
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[Branch]] =
    def denied(f: BranchFailure): IO[Res[Branch]] =
      audit.rest(
        apiKey,
        "control-plane",
        AuditActions.BranchDiscard,
        "denied",
        tenant = Some(tenant),
        detail = Map("error" -> f.code)
      )
      IO.pure(Left(f))
    val pre = for
      _      <- enabled
      parent <- parentOf(tenant, parentDb)
      b      <- liveBranch(parent, name)
      _      <- ownerOrAdmin(b, actor, "discard")
    yield (parent, b)
    pre match
      case Left(f)            => denied(f)
      case Right((parent, b)) =>
        retire(b, BranchStatus.Discarded, "discard").flatMap {
          case BranchService.RetireEnd.Retired(done) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.BranchDiscard,
              "ok",
              tenant = Some(tenant),
              target = Some(s"${parent.name}/$name"),
              detail = Map("branchId" -> b.id)
            )
            IO.pure(Right(done))
          case BranchService.RetireEnd.Busy =>
            denied(
              BranchFailure.conflict(
                "branch_busy",
                s"a propose or a merge of branch '$name' is running; try again when it ends"
              )
            )
          case BranchService.RetireEnd.Gone                => denied(noSuchBranch(parent, name))
          case BranchService.RetireEnd.Landed(_, snapshot) =>
            denied(
              BranchFailure.conflict(
                "proposal_unsettled",
                s"a merge of branch '$name' landed on main as snapshot $snapshot and is not on " +
                  "record; merging it again records it, which needs an admin other than its " +
                  "proposer"
              )
            )
          case BranchService.RetireEnd.LookFailed =>
            denied(
              BranchFailure.upstream(
                "catalog_unavailable",
                s"main's catalog cannot be read to look for a merge of branch '$name' that " +
                  "landed, so nothing changed; try again"
              )
            )
        }

  /** Expire every live branch whose TTL has passed; returns the branch names discarded. Run by the
    * leader's sweep. A branch a run holds, or one gone since the listing, is left to the next
    * sweep. So is one whose merge landed with nothing on record: it stays, and keeps holding back
    * snapshot expiry on its parent, until someone acts, which is the point: only a merge records
    * it.
    */
  def expireDue(): IO[List[Branch]] = IO.defer {
    val sweptAt = now()
    val due     = sup
      .listTenants()
      .flatMap(t => store.listTenantBranches(t.id, BranchStatus.Live))
      .filter(pastTtl(_, sweptAt))
    due.foldLeft(IO.pure(List.empty[Branch])) { (acc, b) =>
      acc.flatMap { done =>
        retire(b, BranchStatus.Expired, "expired").attempt.map {
          case Right(BranchService.RetireEnd.Retired(x)) =>
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
          case Right(BranchService.RetireEnd.Busy | BranchService.RetireEnd.Gone) => done
          // Logged where the look failed.
          case Right(BranchService.RetireEnd.LookFailed)      => done
          case Right(BranchService.RetireEnd.Landed(m, snap)) =>
            logger.error(
              s"branch expiry: ${b.tenant}/${b.parentDbName}@${b.name} is past its TTL, but its " +
                s"merge ${m.id} landed on main as snapshot $snap and is not on record. The " +
                "branch stays, and keeps holding back snapshot expiry, until an admin other than " +
                "its proposer merges it again, which records it: qod branch merge --tenant " +
                s"${b.tenant} --db ${b.parentDbName} --branch ${b.name} --proposal ${m.id}"
            )
            done
          case Left(e) =>
            logger.warn(
              s"branch expiry: ${b.tenant}/${b.parentDbName}@${b.name} failed: ${e.getMessage}"
            )
            done
        }
      }
    }
  }

  /** The only way to `Discarded` or `Expired`: the branch's status and its open proposal, written
    * under [[runLock]] from the row as it is there, then the teardown. `b` only names the branch: a
    * row read before the lock can be stale, and a sweep writing from it would put `Expired` over
    * `Merged`. The teardown runs with the lock released: the status has flipped, so nothing live
    * reads the branch any more, and a lock must not sit over a long purge.
    *
    * An open proposal is never abandoned over its own commit on main: the record would say the
    * opposite of what main holds, and someone reading it may redo the work. So it looks first, and
    * only for that commit. Another proposal's has only a discard as its way out, and a send that
    * timed out with nothing on main leaves its fence row armed, a residual of its own: neither is
    * this rule, whatever the row's `error` says.
    */
  private def retire(
      b: Branch,
      status: BranchStatus,
      reason: String
  ): IO[BranchService.RetireEnd] =
    sup.poolLocks
      .tryWithLock(runLock(b))(
        IO.blocking {
          store.getBranch(b.id).filter(row => BranchStatus.Live(row.status)) match
            case None          => BranchService.RetireEnd.Gone
            case Some(current) =>
              val open = store
                .listBranchMerges(current.id)
                .filter(_.status == BranchMergeStatus.Proposed)
              val look =
                if open.isEmpty then Success(None)
                else Try(landedCommit(current).filter(_._1.status == BranchMergeStatus.Proposed))
              look match
                case Failure(e) =>
                  logger.warn(
                    s"branch ${current.tenant}/${current.parentDbName}@${current.name} left as " +
                      s"it is: cannot look for a merge of it that landed: ${e.getMessage}"
                  )
                  BranchService.RetireEnd.LookFailed
                case Success(Some((m, snapshot))) => BranchService.RetireEnd.Landed(m, snapshot)
                case Success(None)                =>
                  open.foreach { m =>
                    store.updateBranchMerge(
                      m.copy(status = BranchMergeStatus.Abandoned, decidedAt = Some(now()))
                    )
                  }
                  BranchService.RetireEnd.Retired(
                    store.updateBranch(current.copy(status = status)).getOrElse(current)
                  )
        }
      )
      .flatMap {
        case Some(retired: BranchService.RetireEnd.Retired) =>
          teardown(retired.branch, reason).as(retired)
        case Some(other) => IO.pure(other)
        case None        => IO.pure(BranchService.RetireEnd.Busy)
      }

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

  /** How a discard or an expiry ended: done; a run of the branch holds it; it ended since the row
    * that named it was read; its open proposal's commit is on main, with nothing on record; or that
    * could not be read. Only the first changed anything.
    */
  private[branch] enum RetireEnd:
    case Retired(branch: Branch)
    case Busy
    case Gone
    case Landed(merge: BranchMerge, snapshot: Long)
    case LookFailed

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
          "updated"  -> t.updated.asJson,
          // The system's count failed, so these zeros are no counts: a superuser reads them as is.
          "countsUnavailable" -> t.countsUnavailable.asJson
        )
      }*),
      "unsupported" -> cs.unsupported.asJson
    )

  def conflictsJson(cs: BranchChangeSet): Json =
    Json.arr(cs.conflicts.map { c =>
      Json.obj("schema" -> c.schema.asJson, "table" -> c.table.asJson, "reason" -> c.reason.asJson)
    }*)
