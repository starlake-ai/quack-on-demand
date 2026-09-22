package ai.starlake.quack.ondemand.branch

import java.util.Locale
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
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import io.circe.syntax._

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.control.NonFatal

/** Who is asking. `isAdmin` = tenant admin on the tenant, superuser, or the static key; every other
  * principal is a plain data-plane user and must be able to connect to the parent.
  */
final case class BranchActor(identity: String, isAdmin: Boolean)

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

/** Runs the merge batch on an ephemeral node built from `spec`. `Right(())` once the batch
  * committed; `Left(reason)` otherwise (a reason containing "conflict" is the engine's optimistic
  * concurrency loser; "timeout" is a bounded wait that expired without an answer).
  */
trait MergeExecutor:
  def run(spec: NodeSpec, batch: String): IO[Either[String, Unit]]

/** Change-feed counts of one modified table on the branch pool: `(inserted, deleted, updated)`. */
trait ChangeCounter:
  def count(
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
    counter: ChangeCounter,
    purgeFiles: (String, Map[String, String]) => Either[String, Unit],
    audit: AuditRecorder = AuditRecorder.noop,
    events: ManagerEventSink = ManagerEventSink.noop,
    now: () => Instant = () => Instant.now()
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

  /** A non-admin may act on a parent iff it can connect to at least one of its pools: exactly the
    * FlightSQL handshake gate, so "may query" and "may branch" are the same permission.
    */
  private def mayUse(tenant: String, parent: TenantDb, actor: BranchActor): Res[Unit] =
    if actor.isAdmin then Right(())
    else
      val pools = sup.list().map(_.key).filter(k => k.tenant == tenant && k.tenantDb == parent.name)
      val ok    = pools.exists(k => sup.authorizeHandshake(tenant, k.pool, actor.identity).isRight)
      if ok then Right(())
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
                cpu = donor.map(_.cpu).getOrElse(""),
                memory = donor.map(_.memory).getOrElse(""),
                podTemplateYaml = donor.map(_.podTemplateYaml).getOrElse(""),
                lockdown = donor.flatMap(_.lockdown),
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
    * `withCounts` (each count is one statement on the branch pool; failures degrade to zeroes with
    * a WARN so the listing never fails on a hibernated pool).
    */
  def changes(
      tenant: String,
      parentDb: String,
      name: String,
      withCounts: Boolean,
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
        computeChanges(tenant, parent, b, withCounts).map { cs =>
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
      withCounts: Boolean
  ): IO[BranchChangeSet] =
    IO.blocking {
      val br = resolveReader(tenant, b.tenantDbName)
      val mr = resolveReader(tenant, parent.name)
      BranchChanges.compute(br, mr, b.forkSnapshot)
    }.flatMap { cs =>
      if !withCounts then IO.pure(cs)
      else
        val alias = parentAliasOf(tenant, parent)
        cs.tables
          .foldLeft(IO.pure(List.empty[TableChange])) { (acc, t) =>
            acc.flatMap { done =>
              if t.kind != ChangeKind.Modified then IO.pure(done :+ t)
              else
                counter.count(tenant, b, alias, t, cs.forkSnapshot, cs.headSnapshot).map {
                  case Right((ins, del, upd)) =>
                    done :+ t.copy(inserted = ins, deleted = del, updated = upd)
                  case Left(err) =>
                    logger.warn(s"branch changes: counts for ${t.schema}.${t.table} failed: $err")
                    done :+ t
                }
            }
          }
          .map(ts => cs.copy(tables = ts))
    }

  // ---------- propose ----------

  def propose(
      tenant: String,
      parentDb: String,
      name: String,
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
    val pre = for
      _      <- enabled
      parent <- parentOf(tenant, parentDb)
      _      <- mayUse(tenant, parent, actor)
      b      <- liveBranch(parent, name)
      _      <- b.status match
        case BranchStatus.Open     => Right(())
        case BranchStatus.Proposed =>
          Left(BranchFailure.conflict("already_proposed", s"branch '$name' is already proposed"))
        case other => Left(BranchFailure.conflict("not_open", s"branch '$name' is ${other.wire}"))
    yield (parent, b)
    pre match
      case Left(f) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.BranchPropose,
          "denied",
          tenant = Some(tenant)
        )
        IO.pure(Left(f))
      case Right((parent, b)) =>
        computeChanges(tenant, parent, b, withCounts = true).flatMap { cs =>
          IO.blocking {
            val merge = store.createBranchMerge(
              BranchMerge(
                id = Names.newSurrogateId("mg"),
                branchId = b.id,
                proposer = actor.identity,
                approver = None,
                status = BranchMergeStatus.Proposed,
                summaryJson = BranchService.summaryJson(cs).noSpaces,
                conflictsJson = BranchService.conflictsJson(cs).noSpaces,
                mainSnapshotAtPropose = cs.mainSnapshot
              )
            )
            val updated = store.updateBranch(b.copy(status = BranchStatus.Proposed)).getOrElse(b)
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.BranchPropose,
              "ok",
              tenant = Some(tenant),
              target = Some(s"${parent.name}/$name"),
              detail = Map(
                "mergeId"   -> merge.id,
                "tables"    -> cs.tables.size.toString,
                "conflicts" -> cs.conflicts.size.toString,
                "mergeable" -> cs.mergeable.toString
              )
            )
            Right((updated, merge, cs))
          }
        }

  // ---------- merge ----------

  def merge(
      tenant: String,
      parentDb: String,
      name: String,
      expectedMainSnapshot: Option[Long],
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
    val pre = for
      _      <- enabled
      parent <- parentOf(tenant, parentDb)
      _      <-
        if actor.isAdmin then Right(())
        else Left(BranchFailure.forbidden("admin_required", "merge requires a tenant admin"))
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
        if merge.proposer == actor.identity then
          Left(
            BranchFailure.forbidden(
              "self_merge_forbidden",
              s"'${actor.identity}' proposed this merge; a different principal must approve it"
            )
          )
        else Right(())
    yield (parent, b, merge)
    pre match
      case Left(f) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.BranchMerge,
          "denied",
          tenant = Some(tenant)
        )
        IO.pure(Left(f))
      case Right((parent, b, merge)) =>
        val lockKey = PoolKey(tenant, parent.name, "__merge")
        sup.poolLocks.withLock(lockKey)(
          runMerge(tenant, parent, b, merge, expectedMainSnapshot, actor, apiKey)
        )

  private def runMerge(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      merge: BranchMerge,
      expectedMainSnapshot: Option[Long],
      actor: BranchActor,
      apiKey: Option[String]
  ): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
    computeChanges(tenant, parent, b, withCounts = false).flatMap { cs =>
      def refuse(f: BranchFailure): IO[Res[(Branch, BranchMerge, BranchChangeSet)]] =
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.BranchMerge,
          "denied",
          tenant = Some(tenant),
          target = Some(s"${parent.name}/${b.name}"),
          detail = Map("error" -> f.code)
        )
        IO.pure(Left(f))

      if expectedMainSnapshot.exists(_ != cs.mainSnapshot) then
        refuse(
          BranchFailure.conflict(
            "concurrent_write",
            s"main is at snapshot ${cs.mainSnapshot}, expected ${expectedMainSnapshot.get}"
          )
        )
      else if cs.conflicts.nonEmpty then
        refuse(
          BranchFailure.conflict(
            "merge_conflict",
            "main changed since the fork on: " +
              cs.conflicts.map(c => s"${c.schema}.${c.table} (${c.reason})").mkString("; ")
          )
        )
      else if cs.unsupported.nonEmpty || cs.tables.exists(!_.mergeable) then
        val alt = cs.tables
          .filterNot(_.mergeable)
          .map(t => s"${t.schema}.${t.table}: ${t.reason.getOrElse("")}")
        refuse(
          BranchFailure.unprocessable(
            "merge_unsupported",
            "not fast-forwardable in v1: " + (alt ++ cs.unsupported).mkString("; ")
          )
        )
      else if cs.tables.isEmpty then
        refuse(
          BranchFailure.unprocessable("nothing_to_merge", s"branch '${b.name}' has no changes")
        )
      else
        val alias   = parentAliasOf(tenant, parent)
        val message = BranchMergeSql.commitMessage(b.name, merge.id, merge.proposer)
        val batch   = BranchMergeSql.batch(
          parentAlias = alias,
          branchAlias = BranchMergeSql.BranchAlias,
          changes = cs.tables,
          fork = cs.forkSnapshot,
          head = cs.headSnapshot,
          author = BranchMergeSql.author(tenant, actor.identity),
          message = message
        )
        sup.mergeNodeSpec(tenant, parent.name, b.tenantDbName, b.dataPath) match
          case None =>
            refuse(BranchFailure.upstream("merge_failed", "cannot build the merge node spec"))
          case Some(spec) =>
            mergeExecutor.run(spec, batch).flatMap { outcome =>
              // Locate the merge snapshot by its unique message: the authority on "did it
              // commit", which also settles a timed-out wait.
              IO.blocking(resolveReader(tenant, parent.name).snapshotByCommitMessage(message))
                .flatMap {
                  case Some(snap) => finishMerge(tenant, parent, b, merge, cs, snap, actor, apiKey)
                  case None       =>
                    val reason = outcome.left.getOrElse("committed snapshot not found")
                    val f      =
                      if reason.toLowerCase(Locale.ROOT).contains("conflict") then
                        BranchFailure.conflict(
                          "concurrent_write",
                          s"merge lost a commit race: $reason"
                        )
                      else BranchFailure.upstream("merge_failed", reason)
                    IO.blocking {
                      store.updateBranchMerge(
                        merge.copy(
                          status = BranchMergeStatus.Failed,
                          approver = Some(actor.identity),
                          error = Some(reason),
                          decidedAt = Some(now())
                        )
                      )
                      // A failed attempt reopens the proposal: re-propose to retry.
                      store.updateBranch(b.copy(status = BranchStatus.Open))
                    } *> refuse(f)
                }
            }
    }

  private def finishMerge(
      tenant: String,
      parent: TenantDb,
      b: Branch,
      merge: BranchMerge,
      cs: BranchChangeSet,
      snapshot: Long,
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
      val doneMerge = store
        .updateBranchMerge(
          merge.copy(
            status = BranchMergeStatus.Merged,
            approver = Some(actor.identity),
            mainSnapshotAfter = Some(snapshot),
            tagName = Some(tagName),
            decidedAt = Some(now())
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
          "mergeId"  -> merge.id,
          "proposer" -> merge.proposer,
          "approver" -> actor.identity,
          "snapshot" -> snapshot.toString,
          "tag"      -> tagName,
          "tables"   -> cs.tables.size.toString
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
