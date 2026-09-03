package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  Names,
  NodePlacement,
  NodeSpec,
  Pool,
  PoolCohort,
  PoolKey,
  Role,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.rbac.RbacResolver
import ai.starlake.quack.ondemand.runtime.{NodeLockdown, ObjectStoreSecret, QuackBackend}
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  DbAdmin,
  EmailPolicy,
  NoopDbAdmin,
  PoolPermission,
  RbacGroup,
  RbacRole,
  RbacUser,
  RolePermission
}
import ai.starlake.quack.ondemand.storage.ManagedPrefix
import ai.starlake.quack.route.PoolSnapshot
import ai.starlake.quack.spi.ManagerEvent
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap

/** Patch for [[PoolSupervisor.updateTenantDb]]. Absent fields unchanged; present fields replace.
  * Map fields carry over response-redacted keys ([[TenantDb.SecretKeys]]) the incoming map omits
  * (see [[PoolSupervisor.mergeSecretKeys]]). An empty string clears a scalar Option field.
  */
final case class TenantDbPatch(
    metastore: Option[Map[String, String]] = None,
    objectStore: Option[Map[String, String]] = None,
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    initSql: Option[String] = None
)

/** Result of [[PoolSupervisor.updateTenantDb]]: updated row, restarted node ids, and per-node
  * restart failures (nodeId, message). Failed restarts are collected, not thrown; reconcile heals.
  */
final case class TenantDbUpdateResult(
    td: TenantDb,
    restartedNodes: List[String],
    failedRestarts: List[(String, String)]
)

/** Owns the in-memory topology and mediates every mutation through [[ControlPlaneStore]]:
  *   - `Tenant`: ownership umbrella (id, displayName, disabled).
  *   - `TenantDb`: one DuckLake/Postgres database under a tenant; owns (metastore, dataPath,
  *     objectStore). Pools and metastore live here.
  *   - `Pool`: desired compute config under a tenant-db; inherits the tenant-db's metastore +
  *     objectStore.
  *   - `RunningNode`: runtime instance of a pool's compute.
  */
final class PoolSupervisor(
    backend: QuackBackend,
    tracker: NodeLoadTracker,
    store: ControlPlaneStore,
    defaultMetastore: Map[String, String] = Map.empty,
    dbAdmin: DbAdmin = NoopDbAdmin,
    federationBlobOf: String => IO[Option[String]] = _ => IO.pure(None),
    /** Fired after a tenant-db row is removed ([[deleteTenantDb]] or cascaded via
      * [[deleteTenant]]). Default no-op; Main evicts the
      * [[ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader]] cache so the per-tenant-db
      * Hikari pool releases its connections on delete.
      */
    onTenantDbDeleted: (String, String) => Unit = (_, _) => (),
    /** Fired from [[updateTenantDb]] when the stored metastore changes (e.g. credential rotation).
      * Main wires the same evict as [[onTenantDbDeleted]] so a stale catalog reader is replaced.
      */
    onTenantDbChanged: (String, String) => Unit = (_, _) => (),
    /** Fired after a pool is torn down ([[stopPool]] / [[suspendPool]] / [[deletePool]]). Default
      * no-op; Main clears the pool's placement-directory and locality-tracker entries so a resumed
      * or recreated pool starts from a clean placement slate instead of routing on assignments
      * keyed to nodes that no longer exist.
      */
    onPoolTeardown: PoolKey => Unit = _ => (),
    /** Module event emission (SPI). Noop by default; Main wires the ModuleEventBus sink. Emit AFTER
      * the store/state mutation succeeds so modules never observe an uncommitted change.
      */
    events: ai.starlake.quack.spi.ManagerEventSink = ai.starlake.quack.spi.ManagerEventSink.noop,
    /** Cross-replica per-pool lock. Non-HA default
      * ([[ai.starlake.quack.ondemand.ha.PoolLocker.noop]]) is pass-through. Under HA a
      * [[ai.starlake.quack.ondemand.ha.PgPoolLocker]] serializes a pool's mutations against the
      * leader's reconcile so neither observes half-written node rows.
      */
    locks: ai.starlake.quack.ondemand.ha.PoolLocker = ai.starlake.quack.ondemand.ha.PoolLocker.noop,
    publish: ai.starlake.quack.ondemand.ha.StateChangePublisher =
      ai.starlake.quack.ondemand.ha.StateChangePublisher.noop,
    /** Engine lockdown gate (QOD_NODE_LOCKDOWN), from Main via `lockdownCfg.enabled`. Every
      * NodeSpec build site stamps `effectiveLockdown(key)` into `lockdownSql`; this flag is the
      * global default the per-pool tri-state inherits, so the query path and the maintenance path
      * can never drift.
      */
    lockdownEnabled: Boolean = false,
    /** Managed object storage (QOD_MANAGED_STORE_*), from Main; `None` when the config block is
      * disabled. Present-and-enabled is the precondition for `createTenantDb(managedStorage =
      * true)`: it supplies the bucket the per-incarnation prefix is carved from, the credentials
      * the tenant-db's objectStore is filled with, and the `retainDays` window [[deleteTenantDb]]
      * stamps on the tombstone row.
      */
    managedStore: Option[ai.starlake.quack.ManagedObjectStoreConfig] = None
):

  private val logger = LoggerFactory.getLogger(getClass)

  // Surrogate-id-indexed caches of the persisted state.
  private val tenants   = TrieMap.empty[String, Tenant]   // id -> Tenant
  private val tenantDbs = TrieMap.empty[String, TenantDb] // id -> TenantDb
  private val poolRows  = TrieMap.empty[String, Pool]     // id -> Pool

  // Runtime cache keyed by the natural PoolKey for fast routing.
  private val pools = TrieMap.empty[PoolKey, PoolState]
  // PoolKey -> pool.id, so per-node mutations know the FK to qodstate_pool.
  private val poolIdByKey = TrieMap.empty[PoolKey, String]

  // tenant-db.id -> the DataPathMismatchException message that blocked it. Populated by
  // ensureDuckLakeInitialized when the guard refuses a pre-existing dataPath at boot; consulted by
  // reconcile() to skip that tenant-db's pools instead of failing every node spawn with the same
  // DuckDB DATA_PATH error. Cleared by updateTenantDb (remediation) and deleteTenantDb. In-memory
  // only: a restart also clears it and re-attempts on the next boot.
  private val dataPathBlocked = TrieMap.empty[String, String]

  /** Module-contributed veto hooks (quota policy). Set once by Main after moduleStart; empty in
    * zero-module boots and in every existing test, so default behavior is unchanged.
    */
  private val mutationGates: cats.effect.Ref[IO, List[ai.starlake.quack.spi.MutationGate]] =
    cats.effect.Ref.unsafe(Nil)

  def setMutationGates(gates: List[ai.starlake.quack.spi.MutationGate]): IO[Unit] =
    mutationGates.set(gates)

  /** First Left wins. A throwing gate refuses (fail closed): a broken quota store must not grant
    * unlimited provisioning. gateBypass short-circuits (superuser / static-key callers).
    */
  private def gateCheck(
      m: ai.starlake.quack.spi.StructureMutation,
      bypass: Boolean
  ): IO[Either[String, Unit]] =
    if bypass then IO.pure(Right(()))
    else
      mutationGates.get.flatMap { gates =>
        if gates.isEmpty then IO.pure(Right(()))
        else
          gates.foldLeft(IO.pure(Right(()): Either[String, Unit])) { (acc, g) =>
            acc.flatMap {
              case l @ Left(_) => IO.pure(l)
              case Right(())   =>
                g.check(m).attempt.map {
                  case Left(t)  => Left(s"gate error: ${t.getMessage}")
                  case Right(r) => r
                }
            }
          }
      }

  /** In-memory mirror of the snapshot's RBAC slice. REST handlers and the FlightSQL handshake read
    * effective sets from here without re-joining on every call. Rebuilt at `restore()`, updated
    * incrementally after each RBAC mutation.
    */
  val rbacResolver: RbacResolver = new RbacResolver()

  /** Short-TTL cache for `effectiveSetForUser`: every handshake costs 3 store reads + resolver
    * joins, and the same (userId, JWT claims) tuple repeats under load. Invalidated wholesale on
    * every RBAC mutation and on `restore()`. The key bakes in the JWT fingerprint so a claim flip
    * is reflected immediately even within the TTL.
    */
  private final case class EffectiveCacheKey(userId: String, jwtRolesHash: Int, jwtGroupsHash: Int)
  private val EffectiveCacheTtl: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  /** Caffeine-backed: `expireAfterWrite` handles the TTL, `maximumSize` bounds memory so
    * accumulated (userId, jwtRolesHash, jwtGroupsHash) keys can't leak.
    */
  private val effectiveCache: com.github.benmanes.caffeine.cache.Cache[
    EffectiveCacheKey,
    ai.starlake.quack.ondemand.rbac.EffectiveSet
  ] =
    com.github.benmanes.caffeine.cache.Caffeine
      .newBuilder()
      .expireAfterWrite(java.time.Duration.ofSeconds(EffectiveCacheTtl.toSeconds))
      .maximumSize(10_000L)
      .build[EffectiveCacheKey, ai.starlake.quack.ondemand.rbac.EffectiveSet]()

  /** Drop every cached `EffectiveSet` without broadcasting. Used by [[restore]] and by peer
    * notification handlers: broadcasting from those paths would echo forever across replicas.
    */
  private def invalidateEffectiveCacheLocal(): Unit = effectiveCache.invalidateAll()

  /** Drop every cached `EffectiveSet` and notify peers, so a freshly-granted role or pool
    * permission takes effect on the next handshake, not after a TTL window.
    */
  private def invalidateEffectiveCache(): Unit =
    invalidateEffectiveCacheLocal()
    publish.rbacChanged()

  /** Broadcast both channels after a store mutation done OUTSIDE the supervisor's own mutators
    * (e.g. ManifestImporter). restore() never broadcasts, so external writers call this after
    * restore().
    */
  def broadcastStateChanged(): Unit =
    publish.topologyChanged()
    publish.rbacChanged()

  // ---------- Bootstrap / replay ----------

  /** Per-tenant-db naming and data location, resolved per kind on top of `defaultMetastore ++
    * td.metastore`. The manager default describes the BOOTSTRAP tenant-db (a DuckLake directory
    * plus its own `dbName`), so inheriting it wholesale is wrong for every other kind.
    *
    *   - `ducklake`: each tenant-db is its own Postgres database named after `td.name`, with
    *     parquet alongside at `parent(defaultDataPath)/td.name`. Operators override via
    *     `dbName`/`dataPath` in `td.metastore` or `td.dataPath`.
    *   - `duckdb-file`: `dataPath` is a FILE (the node script runs `ATTACH '$dataPath'`), never the
    *     default DuckLake DIRECTORY, so it comes from `td.dataPath`, else `td.metastore`, else the
    *     key is dropped entirely. `dbName` falls back to `td.name`, never to the default's (which
    *     names an unrelated catalog).
    *   - `memory`: `dataPath` is meaningless and is dropped. `dbName` falls back to DuckDB's
    *     built-in `memory` catalog, so the health probe's `CREATE SCHEMA IF NOT EXISTS
    *     <dbName>.<schemaName>` targets a catalog the node actually has instead of failing on every
    *     tick and leaving the node unroutable.
    *
    * `schemaName` is left as the merge yields it for every kind (the default `main` is correct).
    */
  private def effectiveMetastoreFor(td: TenantDb): Map[String, String] =
    val merged = defaultMetastore ++ td.metastore
    td.kind match
      case TenantDbKind.DuckLake =>
        val withDb   = merged.updated("dbName", td.metastore.getOrElse("dbName", td.name))
        val rootData = defaultMetastore.getOrElse("dataPath", "")
        val tdData   =
          if td.dataPath.nonEmpty then td.dataPath
          else if td.metastore.contains("dataPath") then td.metastore("dataPath")
          else if rootData.isEmpty then ""
          else PoolSupervisor.replaceLastSegment(rootData, td.name)
        if tdData.nonEmpty then withDb.updated("dataPath", tdData) else withDb

      case TenantDbKind.DuckDbFile =>
        val withDb = merged.updated("dbName", td.metastore.getOrElse("dbName", td.name))
        val tdData =
          if td.dataPath.nonEmpty then td.dataPath
          else td.metastore.getOrElse("dataPath", "")
        if tdData.nonEmpty then withDb.updated("dataPath", tdData) else withDb.removed("dataPath")

      case TenantDbKind.InMemory =>
        merged
          .updated("dbName", td.metastore.getOrElse("dbName", "memory"))
          .removed("dataPath")

  /** True when `key`'s tenant-db is in [[dataPathBlocked]]. False when the pool has no persisted
    * row (InMemory-only test pools): such a pool never wrote to the store, so it can't race a
    * boot-time DuckLakeInitializer failure.
    */
  private def isDataPathBlocked(key: PoolKey): Boolean =
    poolIdByKey.get(key).flatMap(poolRows.get).exists(p => dataPathBlocked.contains(p.tenantDbId))

  /** Test-only seam: seed [[dataPathBlocked]] directly so specs can assert reconcile()'s skip. */
  private[ondemand] def blockDataPathForTest(tenantDbId: String, message: String): Unit =
    dataPathBlocked.put(tenantDbId, message)

  /** Test-only seam: read back [[dataPathBlocked]] membership. */
  private[ondemand] def isDataPathBlockedForTest(tenantDbId: String): Boolean =
    dataPathBlocked.contains(tenantDbId)

  def restore(): Unit =
    val snap = store.snapshot()

    // Diff-aware rehydration: restore() is also driven by peer NOTIFY handlers, so a row a peer
    // DELETED must disappear from this mirror, not just get overwritten. Compute the snapshot key
    // sets, propagate deletions, then upsert. Removing before putting avoids a window where a
    // surviving entry is briefly missing.
    val snapTenantIds   = snap.tenants.iterator.map(_.id).toSet
    val snapTenantDbIds = snap.tenantDbs.iterator.map(_.id).toSet
    val snapPoolIds     = snap.pools.iterator.map(_.id).toSet

    // Rebuild the PoolKey set exactly as the upsert pass below derives it: a pool
    // contributes a key only when its tenant-db and tenant both resolve in the snapshot.
    val tdById                     = snap.tenantDbs.iterator.map(td => td.id -> td).toMap
    val tById                      = snap.tenants.iterator.map(t => t.id -> t).toMap
    val snapPoolKeys: Set[PoolKey] = snap.pools.iterator.flatMap { p =>
      for
        td <- tdById.get(p.tenantDbId)
        t  <- tById.get(td.tenantId)
      yield PoolKey(t.id, td.name, p.name)
    }.toSet

    tenants.keys.toList.filterNot(snapTenantIds).foreach(tenants.remove)
    tenantDbs.keys.toList.filterNot(snapTenantDbIds).foreach(tenantDbs.remove)
    poolRows.keys.toList.filterNot(snapPoolIds).foreach(poolRows.remove)
    // A pool a peer deleted directly in the store leaves this replica holding stale placement /
    // locality state (PlacementDirectory + LocalityTracker never auto-expire), so fire the teardown
    // hook per removed key. Deletions only: suspend keeps the pool row. Boot-time hydration removes
    // nothing, so the hook stays silent there.
    val removedPoolKeys = pools.keys.toList.filterNot(snapPoolKeys)
    removedPoolKeys.foreach(pools.remove)
    removedPoolKeys.foreach(onPoolTeardown)
    poolIdByKey.keys.toList.filterNot(snapPoolKeys).foreach(poolIdByKey.remove)
    // A tenant-db a peer deleted directly in the store can no longer spawn anything, so any
    // dataPath block held for it is moot; drop it rather than leaking it forever.
    dataPathBlocked.keys.toList.filterNot(snapTenantDbIds).foreach(dataPathBlocked.remove)

    snap.tenants.foreach(t => tenants.put(t.id, t))
    snap.tenantDbs.foreach(td => tenantDbs.put(td.id, td))
    snap.pools.foreach(p => poolRows.put(p.id, p))
    snap.pools.foreach { p =>
      val opt = for
        td <- tenantDbs.get(p.tenantDbId)
        t  <- tenants.get(td.tenantId)
      yield (td, t)
      opt.foreach { case (td, t) =>
        val key = PoolKey(t.id, td.name, p.name)
        poolIdByKey.put(key, p.id)
        val nodesHere = snap.nodes.filter(_.poolKey == key)
        val merged    = effectiveMetastoreFor(td)
        pools.put(
          key,
          PoolState(
            key = key,
            nodes = nodesHere,
            distribution = p.distribution,
            metastore = merged,
            s3 = td.objectStore,
            maxConcurrentPerNode = p.maxConcurrentPerNode,
            disabled = p.disabled,
            suspended = p.suspended,
            dbInitSql = td.initSql,
            initSql = p.initSql,
            // Session defaults for SQL validation / policy-rewrite. Omitting these degraded every
            // restored pool to the metastore's schemaName ("main"), so schema-qualified refs
            // stopped matching tenant-db grants after a restart / NOTIFY rehydration.
            defaultDatabase = td.defaultDatabase,
            defaultSchema = td.defaultSchema,
            cpu = p.cpu,
            memory = p.memory,
            podTemplateYaml = p.podTemplateYaml
          )
        )
      }
    }
    // Hand the RBAC graph to the resolver in one shot; later mutations mirror incrementally.
    rbacResolver.replace(snap)
    // Seed operator quarantine flags so a restarted manager or NOTIFY-woken replica keeps refusing
    // to route to quarantined nodes.
    val quarantinedIds = store.listQuarantinedNodeIds()
    pools.values.flatMap(_.nodes).foreach { n =>
      tracker.setQuarantined(n.nodeId, quarantinedIds.contains(n.nodeId))
    }
    // Local-only: restore() is called by peer-notification handlers; broadcasting here
    // would cause infinite echo across replicas.
    invalidateEffectiveCacheLocal()

  /** Wraps a mutator body so a failure after the store write leaves the caches consistent with the
    * store instead of half-applied. All caches (`tenants`, `tenantDbs`, `poolRows`, `pools`,
    * `poolIdByKey`, `rbacResolver`, tracker quarantine flags, effective-set cache) are DERIVED
    * state, fully rebuildable from `store.snapshot()` via [[restore]]. On any exception this
    * best-effort restore()s then rethrows the ORIGINAL exception unchanged (a rebuild failure is
    * logged and swallowed). `restore()` acquires no lock and never spawns / calls `backend` /
    * broadcasts, so it is safe to run under a held `locks.withLock`.
    */
  private def withCacheRecovery[A](op: String)(body: => A): A =
    try body
    catch
      case t: Throwable =>
        try restore()
        catch
          case rebuildError: Throwable =>
            logger.error(
              s"withCacheRecovery[$op]: cache rebuild after failed mutation also failed: " +
                s"${rebuildError.getMessage}",
              rebuildError
            )
        logger.warn(
          s"withCacheRecovery[$op]: mutation failed, caches rebuilt from store: ${t.getMessage}"
        )
        throw t

  /** [[withCacheRecovery]] for an IO body: recovery runs in the same IO chain (under whatever lock
    * the IO was built with, e.g. `locks.withLock`), then the original error is re-raised.
    */
  private def withCacheRecoveryIO[A](op: String)(io: IO[A]): IO[A] =
    io.onError { case t =>
      IO {
        try restore()
        catch
          case rebuildError: Throwable =>
            logger.error(
              s"withCacheRecoveryIO[$op]: cache rebuild after failed mutation also failed: " +
                s"${rebuildError.getMessage}",
              rebuildError
            )
        logger.warn(
          s"withCacheRecoveryIO[$op]: mutation failed, caches rebuilt from store: ${t.getMessage}"
        )
      }
    }

  /** Initialize the DuckLake catalog for every `kind=ducklake` tenant-db, one controlled session
    * per tenant-db so concurrent node ATTACHes don't race on the `ducklake_metadata` CREATE TABLE.
    * Idempotent. Runs after [[restore]] (tenant-dbs cache populated) and BEFORE [[reconcile]]
    * (nodes find a ready catalog); the YAML import path persists tenant-dbs without per-row
    * bootstrap.
    */
  def ensureDuckLakeInitialized(): IO[Unit] = IO.blocking {
    tenantDbs.values.toList.foreach { td =>
      if td.kind == TenantDbKind.DuckLake then
        try
          DuckLakeInitializer.initBlocking(effectiveMetastoreFor(td))
          dataPathBlocked.remove(td.id)
        catch
          case t: DuckLakeInitializer.DataPathMismatchException =>
            // Not transient: every future node spawn hits the same DuckDB DATA_PATH error, so log
            // loudly and block this tenant-db's pools from reconcile()'s spawns (see
            // isDataPathBlocked). The loop continues: one bad tenant-db must not abort boot.
            dataPathBlocked.put(td.id, t.getMessage)
            logger.error(s"ensureDuckLakeInitialized: '${td.name}' ${t.getMessage}")
          case t: Throwable =>
            logger.warn(
              s"ensureDuckLakeInitialized: '${td.name}' pre-init raised ${t.getClass.getSimpleName}: " +
                s"${t.getMessage}. First pool spawn will retry."
            )
    }
  }

  /** Re-check every persisted node; respawn dead ones. */
  def reconcile(): IO[Unit] = IO.defer {
    // A dataPath-blocked tenant-db must not have its pools' spawns retried every pass (that
    // reproduces the per-node DATA_PATH noise this guard eliminates). Skip those pools; the guard
    // only re-runs at boot/create/update, never per tick.
    val (blocked, runnable) = pools.toList.partition { case (key, _) => isDataPathBlocked(key) }
    val skipLog             = blocked
      .groupBy { case (key, _) => key.tenantDb }
      .toList
      .foldLeft(IO.unit) { case (acc, (tenantDbName, ps)) =>
        acc *> IO.delay(
          logger.warn(
            s"reconcile: skipping ${ps.size} pool(s) of tenant-db '$tenantDbName': " +
              "dataPath mismatch; see boot ERROR"
          )
        )
      }
    IO.delay(
      logger.info(
        s"reconcile: checking ${runnable.size} pool(s), " +
          s"${runnable.map(_._2.nodes.size).sum} node(s)"
      )
    ) *> skipLog *>
      runnable.foldLeft(IO.unit) { case (acc, (key, state)) =>
        acc *> reconcilePool(key, state).void
      }
  }

  /** Run [[reconcile]] forever, sleeping `interval` between passes, so a node that dies while the
    * manager is up is respawned on the next tick. A throwing tick is logged and swallowed so one
    * bad pass doesn't kill the loop. Started as a cancelable fiber by `Main`; cancellation is
    * normal shutdown. Drained pools (zero distribution) are left alone.
    */
  def reconcileLoop(
      interval: scala.concurrent.duration.FiniteDuration,
      gate: () => Boolean = () => true
  ): IO[Unit] =
    ((if gate() then reconcile() else IO.unit).handleErrorWith { t =>
      IO.delay(logger.warn(s"reconcile loop: pass failed, continuing: ${t.getMessage}"))
    } *> IO.sleep(interval)).foreverM.void

  private def reconcilePool(key: PoolKey, state: PoolState): IO[PoolState] =
    locks.withLock(key) {
      reconcilePoolUnlocked(key, state)
    }

  private def reconcilePoolUnlocked(key: PoolKey, state: PoolState): IO[PoolState] =
    // Refresh BOTH halves of the pool's state INSIDE the advisory lock so a second lock holder acts
    // on the first holder's committed writes, not the pre-lock PoolState the fold captured:
    //   1. the in-memory PoolState (suspend/resume mutate `pools` under the same lock) -- acting on
    //      the captured `state` could let the heal arm below drain a pool that resumed between the
    //      pass snapshot and this pool's turn, on its stale suspended=true;
    //   2. the persisted node rows, on top of that fresh state.
    // Deferred via IO.blocking so the reads run when withLock's bracket executes (after the lock is
    // acquired), not at IO-construction time. Fall back to the passed / fresh state when the pool or
    // its rows are missing (InMemory / no-row cases).
    IO.blocking {
      val current = pools.get(key).getOrElse(state)
      poolId(key) match
        case Some(pid) =>
          val rows      = store.listNodes(pid)
          val withNodes = if rows.nonEmpty then current.copy(nodes = rows) else current
          // The distribution above rides the in-memory `pools` cache, which can lag the
          // persisted row (stale-low on a peer replica's fresh scale-up, or after a restore
          // race). A too-small cached distribution makes the heal below compute a too-small
          // target, pruning a dead row the true target would have respawned instead. Overlay
          // the authoritative distribution from the store when the pool's row is found; keep
          // the cached value otherwise (InMemory / no-row cases).
          val storeRow =
            poolRows
              .get(pid)
              .map(_.tenantDbId)
              .flatMap(tid => store.listPools(tid).find(_.id == pid))
          storeRow.fold(withNodes)(row => withNodes.copy(distribution = row.distribution))
        case None => current
    }.flatMap(fresh => reconcilePoolUnlockedWith(key, fresh))

  /** The full NodeSpec for one slot of a pool, from its PoolState. Shared by every spawn path
    * (createPool, scaleUnlocked, spawnFromDistribution, respawn) so the contract can't drift.
    */
  private def specFromState(
      key: PoolKey,
      state: PoolState,
      nodeId: String,
      role: ai.starlake.quack.model.Role,
      placement: NodePlacement,
      maxConcurrent: Int
  ): NodeSpec =
    NodeSpec(
      poolKey = key,
      nodeId = nodeId,
      role = role,
      metastore = state.metastore,
      s3 = state.s3,
      maxConcurrent = maxConcurrent,
      kindWire = state.kindWire,
      extraSetupSql = PoolSupervisor.joinInitAndBlob(state.initSql, state.extraSetupSql),
      dbInitSql = state.dbInitSql,
      lockdownSql =
        NodeLockdown.sql(state.metastore.getOrElse("dataPath", ""), effectiveLockdown(key)),
      objectStoreSql = ObjectStoreSecret.sql(state.s3, state.metastore.getOrElse("dataPath", "")),
      placement = placement,
      cpu = Option(state.cpu).filter(_.nonEmpty),
      memory = Option(state.memory).filter(_.nonEmpty),
      podTemplateYaml = Option(state.podTemplateYaml).filter(_.nonEmpty)
    )

  /** Start `specs` sequentially, clearing any stale NodeLoadTracker entry first (a reused node id
    * must not inherit a lingering draining=true flag). Returns the started nodes in spawn order.
    */
  private def spawnAll(key: PoolKey, specs: List[NodeSpec]): IO[List[RunningNode]] =
    specs.foldLeft(IO.pure(List.empty[RunningNode])) { (acc, spec) =>
      acc.flatMap(rs =>
        IO.delay(tracker.remove(spec.nodeId)) *> startNodeEmitting(key, spec).map(rs :+ _)
      )
    }

  /** Start one node and emit [[ManagerEvent.NodeStarted]]. Every `backend.start` call site routes
    * through here so module event emission can't drift out of sync with a new spawn path.
    */
  private def startNodeEmitting(key: PoolKey, spec: NodeSpec): IO[RunningNode] =
    backend
      .start(spec)
      .flatTap(n =>
        IO(events.emit(ManagerEvent.NodeStarted(key.tenant, key.tenantDb, key.pool, n.nodeId)))
      )

  /** Stop one node and emit [[ManagerEvent.NodeStopped]] with `reason`. Every `backend.stop` call
    * site routes through here, as with [[startNodeEmitting]].
    */
  private def stopNodeEmitting(key: PoolKey, nodeId: String, reason: String): IO[Unit] =
    backend.stop(key, nodeId) <*
      IO(events.emit(ManagerEvent.NodeStopped(key.tenant, key.tenantDb, key.pool, nodeId, reason)))

  /** [[stopNodeEmitting]] that degrades a backend failure to a loud warn instead of raising:
    * teardown paths must never let a dead pod (or an apiserver blip) block registry cleanup. The
    * row deletion that follows at every call site stays strict - Postgres failures surface.
    */
  private def stopNodeBestEffort(key: PoolKey, nodeId: String, reason: String): IO[Unit] =
    stopNodeEmitting(key, nodeId, reason).handleErrorWith { t =>
      IO.delay(
        logger.warn(
          s"stop of $key/$nodeId failed: ${t.getMessage}; " +
            "removing registry row anyway (possible leaked pod)"
        )
      )
    }

  private def respawnSpec(key: PoolKey, state: PoolState, n: RunningNode): NodeSpec =
    specFromState(key, state, n.nodeId, n.role, placementForNodeId(key, n.nodeId), n.maxConcurrent)

  /** NodeSpec for an ephemeral Spec 09 maintenance node. Never registered in the Router or
    * NodeLoadTracker; the caller owns the full lifecycle. Borrows a serving pool's resolved config
    * (metastore, s3, kindWire, init SQL) so it ATTACHes the same catalog the same way; falls back
    * to the effective metastore + the tenant-db's own `objectStore` when the tenant-db has no pool
    * yet, so a per-db-credentialed bucket still authors its `CREATE SECRET` on a donor-less run.
    * The pool segment is the reserved name `__maint` so node ids can't collide with a serving
    * pool's.
    */
  def maintenanceNodeSpec(tenantName: String, tenantDbName: String): Option[NodeSpec] =
    findTenantDb(tenantName, tenantDbName).map { td =>
      val key    = PoolKey(tenantName, td.name, "__maint")
      val nodeId = s"maint-${td.name}-${System.nanoTime()}"
      // Borrow a serving pool's resolved config when one exists (s3 creds, kindWire, initSql).
      val donor = pools.values.find(s => s.key.tenant == key.tenant && s.key.tenantDb == td.name)
      val metastore = donor.map(_.metastore).getOrElse(effectiveMetastoreFor(tenantName, td.name))
      val s3        = donor.map(_.s3).getOrElse(td.objectStore)
      val dataPath  = metastore.getOrElse("dataPath", "")
      NodeSpec(
        poolKey = key,
        nodeId = nodeId,
        role = Role.Dual,
        metastore = metastore,
        s3 = s3,
        maxConcurrent = 1,
        kindWire = donor.map(_.kindWire).getOrElse("ducklake"),
        extraSetupSql = donor
          .map(s => PoolSupervisor.joinInitAndBlob(s.initSql, s.extraSetupSql))
          .getOrElse(""),
        dbInitSql = donor.map(_.dbInitSql).getOrElse(""),
        lockdownSql = NodeLockdown.sql(dataPath, effectiveLockdown(key)),
        objectStoreSql = ObjectStoreSecret.sql(s3, dataPath)
      )
    }

  private def reconcilePoolUnlockedWith(key: PoolKey, state: PoolState): IO[PoolState] =
    if state.suspended && state.nodes.nonEmpty then
      // Crash-mid-suspend heal: suspendPool persists suspended=true BEFORE draining, so a crash in
      // that window reloads a suspended pool whose nodes are still alive. Drain-forget them the way
      // suspendPool would. No PoolSuspended re-emission (already announced); per-node NodeStopped
      // events still flow through drainAndStop on each successful stop.
      drainAndForgetNodes(key, state.nodes) *>
        IO.delay {
          val updated = state.copy(nodes = Nil)
          pools.put(key, updated)
          publish.topologyChanged()
          updated
        }
    else if state.nodes.isEmpty && state.distribution.total > 0 && !state.suspended then
      // Pool persisted with zero running nodes (fresh YAML bootstrap: ManifestImporter writes the
      // pool row but spawns no nodes). Spawn the full distribution via createPool's NodeSpec path.
      spawnFromDistribution(key, state)
    else
      backend.liveNodeIds(key).flatMap { live =>
        // pid-carrying nodes (local backend) keep the existing pid+socket probe; pid-less nodes
        // (k8s) are live iff the labeled pod list contains them. live=None means the backend can't
        // enumerate (local mode / apiserver blip): treat as alive, heal nothing.
        def podAlive(n: RunningNode): Boolean = n.pid match
          case Some(_) => isReachable(n)
          case None    => live.fold(true)(_.contains(n.nodeId))

        // Heal to target: rows beyond distribution.total are pruned, but ONLY rows whose pod is
        // authoritatively absent. Two guards, both deliberate:
        //   1. pruning needs an authoritative membership answer, so it runs only when the backend
        //      could enumerate the pool (live.isDefined). A pid+socket probe is far weaker evidence
        //      - a local node still binding its port reads as dead - and must never DELETE a row;
        //      those keep the pre-existing respawn treatment.
        //   2. a LIVE node over target is never deleted here. Under HA this replica's cached
        //      distribution can lag a scale-up another replica just committed, and deleting a
        //      healthy pod on that stale target would take serving capacity down. A live overage
        //      therefore leaks by design and is warned about once per pass; scale() owns removing
        //      live nodes.
        // Dead rows within target are not pruned either: they respawn in the fold below.
        val target      = state.distribution.total
        val excessCount = if live.isEmpty then 0 else (state.nodes.size - target).max(0)
        val excess      = state.nodes.filterNot(podAlive).take(excessCount)
        val keep        = state.nodes.filterNot(n => excess.exists(_.nodeId == n.nodeId))
        val liveOverage = excessCount - excess.size

        val overageWarn =
          if liveOverage > 0 then
            IO.delay(
              logger.warn(
                s"reconcile: $key has $liveOverage live node(s) beyond target=$target; " +
                  "excess live nodes retained; scale the pool or stop the strays"
              )
            )
          else IO.unit

        val pruneIO = excess.foldLeft(overageWarn) { (acc, n) =>
          acc *>
            IO.delay(
              logger.warn(s"reconcile: pruning dead node row $key/${n.nodeId} (target=$target)")
            ) *>
            stopNodeBestEffort(key, n.nodeId, "reconcile-prune") *>
            IO.blocking(store.deleteNode(n.nodeId)) *>
            IO.delay(tracker.remove(n.nodeId))
        }

        pruneIO *>
          keep
            .foldLeft(IO.pure(List.empty[RunningNode])) { (acc, n) =>
              acc.flatMap { kept =>
                if podAlive(n) then backend.adopt(n).as(kept :+ n)
                else
                  logger.warn(
                    s"reconcile: $key/${n.nodeId} (pid=${n.pid.getOrElse("?")} port=${n.port}) " +
                      "is dead; respawning"
                  )
                  val wasQuarantined = tracker.snapshot(n.nodeId).quarantined
                  IO.delay(tracker.remove(n.nodeId)) *>
                    startNodeEmitting(key, respawnSpec(key, state, n))
                      .flatMap { fresh =>
                        // Re-apply the pre-remove quarantine so an operator quarantine survives a
                        // node crash. Only automatic reconcile respawn preserves it; restartNode
                        // clears it.
                        val restore: IO[Unit] =
                          if wasQuarantined then
                            IO.delay(tracker.setQuarantined(fresh.nodeId, true))
                          else IO.unit
                        poolIdByKey.get(key) match
                          case Some(pid) =>
                            IO.blocking(store.upsertNode(fresh, pid)) *> restore.as(kept :+ fresh)
                          case None =>
                            restore.as(kept :+ fresh)
                      }
              }
            }
            .flatMap { newNodes =>
              val changed = excess.nonEmpty || newNodes.zip(keep).exists((a, b) => a ne b)
              if changed then
                val updated = state.copy(nodes = newNodes)
                IO.delay { pools.put(key, updated); publish.topologyChanged() }.as(updated)
              else IO.pure(state)
            }
      }

  /** Spawn the full distribution for a pool whose persisted state has no nodes yet. Mirrors
    * createPool's spawn block on an existing PoolState. Cohort placement is recovered from the
    * pool's authored cohorts when present; otherwise nodes spawn placement-less.
    */
  private def spawnFromDistribution(key: PoolKey, state: PoolState): IO[PoolState] =
    val poolEntity = poolIdByKey.get(key).flatMap(poolRows.get)
    val cohortPlan: List[(ai.starlake.quack.model.Role, NodePlacement)] = poolEntity match
      case Some(p) =>
        p.effectiveCohorts.flatMap(c => c.distribution.asRoleList.map(r => (r, c.placement)))
      case None =>
        state.distribution.asRoleList.map(r => (r, NodePlacement.empty))
    // The cohort plan rides the poolRows cache, which can lag `state.distribution` (refreshed
    // in-lock, authoritatively, by reconcilePoolUnlocked). A stale cohort plan whose total
    // disagrees with the target would otherwise spawn the wrong node count - including zero -
    // and log a misleading "spawned 0 node(s)". Fall back to the flat, placement-less
    // distribution when they disagree: the gate (this method firing at all) and the plan it
    // builds must share one source of truth.
    val plan: List[(ai.starlake.quack.model.Role, NodePlacement)] =
      if cohortPlan.size == state.distribution.total then cohortPlan
      else state.distribution.asRoleList.map(r => (r, NodePlacement.empty))
    val specs = plan.zipWithIndex.map { case ((role, placement), i) =>
      specFromState(
        key,
        state,
        PoolSupervisor.nodeId(key, i + 1),
        role,
        placement,
        state.maxConcurrentPerNode
      )
    }
    spawnAll(key, specs)
      .flatMap { running =>
        val updated = state.copy(nodes = running)
        pools.put(key, updated)
        logger.info(
          s"reconcile: spawned ${running.size} node(s) for empty pool $key"
        )
        poolIdByKey.get(key) match
          case Some(pid) =>
            running
              .foldLeft(IO.unit)((acc, n) => acc *> IO.blocking(store.upsertNode(n, pid)))
              .map { _ => publish.topologyChanged(); updated }
          case None =>
            IO.delay { publish.topologyChanged(); updated }
      }

  /** Cohort placement owning the node at 1-based `index` in the pool's spawn order.
    * [[NodePlacement.empty]] when there are no explicit cohorts or the index is out of range. Used
    * to respawn a dead node onto the same K8s nodeSelector as the original.
    */
  private def placementForNodeId(key: PoolKey, nodeId: String): NodePlacement =
    val maybeIdx        = nodeId.split('-').lastOption.flatMap(_.toIntOption)
    val maybePoolEntity = poolIdByKey.get(key).flatMap(poolRows.get)
    (maybeIdx, maybePoolEntity) match
      case (Some(i), Some(p)) if p.cohorts.nonEmpty =>
        var remaining            = i
        var found: NodePlacement = NodePlacement.empty
        val it                   = p.cohorts.iterator
        while remaining > 0 && it.hasNext do
          val c    = it.next()
          val size = c.distribution.total
          if remaining <= size then
            found = c.placement
            remaining = 0
          else remaining -= size
        found
      case _ => NodePlacement.empty

  private def isReachable(n: RunningNode): Boolean =
    n.pid match
      case None    => true // K8s: defer to control-plane liveness + HealthProbe.
      case Some(p) =>
        val pidAlive = Option(java.lang.ProcessHandle.of(p))
          .flatMap(o => if o.isPresent then Some(o.get()) else None)
          .exists(_.isAlive)
        if !pidAlive then false
        else
          val sock = new java.net.Socket()
          try { sock.connect(new java.net.InetSocketAddress(n.host, n.port), 250); true }
          catch case _: Throwable => false
          finally
            try sock.close()
            catch case _: Throwable => ()

  // ---------- Read API ----------

  /** True when the [[QuackBackend]] honors placement hints (K8s nodeSelector / tolerations).
    * Exposed so `/client-config` can flag the UI to hide cohort controls in local mode.
    */
  def supportsPlacement: Boolean = backend.supportsPlacement

  def get(key: PoolKey): Option[PoolState] = pools.get(key)

  /** Surface the internal `qodstate_pool.id` for a (tenant, tenantDb, pool) triple so the RBAC
    * pool-grant UI can submit the id the grant endpoint expects. None until the pool is hydrated.
    */
  def poolId(key: PoolKey): Option[String] = poolIdByKey.get(key)

  /** The persisted [[Pool]] row by id, for fields the runtime [[PoolState]] doesn't carry (the
    * cohorts placement plan).
    */
  def poolEntity(id: String): Option[Pool]         = poolRows.get(id)
  def list(): List[PoolState]                      = pools.values.toList
  def snapshot(key: PoolKey): Option[PoolSnapshot] =
    pools.get(key).map(p => PoolSnapshot(p.key, p.nodes, tracker.snapshotAll))

  def listTenants(): List[Tenant]             = tenants.values.toList.sortBy(_.displayName)
  def getTenant(name: String): Option[Tenant] =
    val n = name.toLowerCase
    tenants.values.find(_.id == n)

  /** Lookup by surrogate id (`qodstate_tenant.id`). The `tenants` map is keyed by id: a direct hit.
    */
  def getTenantById(id: String): Option[Tenant] = tenants.get(id)

  def listPoolsOfTenant(name: String): List[String] =
    pools.values.filter(_.key.tenant == name.toLowerCase).map(_.key.pool).toList.sorted

  def listTenantDbsByTenant(tenantName: String): List[TenantDb] =
    getTenant(tenantName)
      .map(t => tenantDbs.values.filter(_.tenantId == t.id).toList.sortBy(_.name))
      .getOrElse(Nil)

  /** Bucket keys of every DuckLake dataPath the control plane knows: all tenant-db dataPaths
    * (explicit or metastore-carried) plus the default-metastore root. Feeds the edge
    * LockdownScreen's bucket denial; Main unions in the managed-store root bucket. Derived on
    * demand from the in-memory maps, so HA replicas stay fresh through the existing topology
    * snapshot refresh.
    */
  def duckLakeBuckets(): Set[String] =
    val paths =
      tenantDbs.values.flatMap(td => List(td.dataPath) ++ td.metastore.get("dataPath")) ++
        defaultMetastore.get("dataPath")
    paths.flatMap(ai.starlake.quack.model.BucketKeys.of).toSet

  def findTenantDb(tenantName: String, tenantDbName: String): Option[TenantDb] =
    getTenant(tenantName).flatMap { t =>
      val nm = tenantDbName.toLowerCase
      tenantDbs.values.find(td => td.tenantId == t.id && td.name == nm)
    }

  /** Find the (tenant, tenantDb) whose composed Postgres `dbName` (`${tenant}_${tenantDb}`,
    * lowercased, persisted into `TenantDb.name`) matches `catalog`. Used by the CLS rewriter's
    * column-catalog fetcher. Matching `td.name` with `equalsIgnoreCase` stays in sync with the
    * formula `effectiveMetastoreFor` / `spawn-quack-node.sh` use, with no drift. `None` if no
    * tenant-db matches.
    */
  def findTenantDbByCatalogName(catalog: String): Option[TenantDb] =
    if catalog == null || catalog.isEmpty then None
    else tenantDbs.values.find(_.name.equalsIgnoreCase(catalog))

  /** Resolve `(tenant, poolName) -> PoolKey` so the edge can route a connection addressing only
    * `tenant` + `pool`. Pool names are unique within a tenant, so at most one match exists.
    */
  def findPoolKeyByTenantAndPoolName(tenant: String, poolName: String): Option[PoolKey] =
    val t = tenant.toLowerCase
    pools.keys.find(k => k.tenant == t && k.pool == poolName)

  /** Effective metastore for a tenant-db (defaults + td params + per-tenant-db naming). Used by the
    * catalog browser.
    */
  def effectiveMetastoreFor(tenantName: String, tenantDbName: String): Map[String, String] =
    findTenantDb(tenantName, tenantDbName)
      .map(effectiveMetastoreFor)
      .getOrElse(defaultMetastore)

  /** The manager-wide metastore defaults (`quack-on-demand.defaultMetastore`), raw. Backing for the
    * REST `database/metastore-defaults` endpoint; response surfaces must never include
    * `pgPassword`.
    */
  def metastoreDefaults: Map[String, String] = defaultMetastore

  // ---------- Tenant-of-resource lookups (RBAC scope check) ----------

  /** Tenant id owning a `qodstate_user` row. Outer `Option` distinguishes "not found" from
    * "superuser" (`Some(None)`).
    */
  def tenantForUser(userId: String): Option[Option[String]] =
    store.getUserById(userId).map(_.tenant)

  /** Full user row by id; the lock guardrails need role + enabled, not just the tenant. */
  def findUserById(userId: String): Option[RbacUser] =
    store.getUserById(userId)

  /** Full user row by (tenant, username); resolves a session identity to its row. */
  def findUser(tenant: Option[String], username: String): Option[RbacUser] =
    store.findUser(tenant, username)

  /** All tenant-NULL rows, for the last-enabled-superuser floor (cheaper than listUsers). */
  def listSuperusers(): List[RbacUser] =
    store.listSuperusers()

  def tenantForRole(roleId: String): Option[String] =
    rbacResolver.role(roleId).map(_.tenantId)

  def tenantForGroup(groupId: String): Option[String] =
    rbacResolver.group(groupId).map(_.tenantId)

  /** Resolve a role-permission id to its owning tenant via the parent role. */
  def tenantForRolePermission(id: String): Option[String] =
    store.getRolePermission(id).flatMap(p => rbacResolver.role(p.roleId).map(_.tenantId))

  def tenantForPoolPermission(id: String): Option[String] =
    store.getPoolPermission(id).map(_.tenantId)

  // ---------- Tenant API ----------

  def createTenant(t: Tenant): IO[Either[SupervisorError, Tenant]] = IO.blocking {
    withCacheRecovery("createTenant") {
      // The tenant id is a normalized lowercase slug; displayName is a free-form label (falls back
      // to the id when blank).
      Names.normalizeOrError(t.id, "tenant id") match
        case Left(err) => Left(SupervisorError.InvalidName(err))
        case Right(id) if !id.headOption.exists(_.isLetter) =>
          Left(SupervisorError.InvalidName(s"tenant id '$id' must start with a letter"))
        case Right(id) =>
          if tenants.values.exists(_.id == id) then
            Left(SupervisorError.AlreadyExists(s"tenant already exists: $id"))
          else
            val withId = t.copy(
              id = id,
              displayName = if t.displayName.trim.nonEmpty then t.displayName.trim else id
            )
            // Every new tenant gets a built-in `admin` role with a wildcard ALL permission,
            // inserted in the same transaction as the tenant row so a partial failure leaves no
            // orphans. BootstrapAccessSeeder wires the bootstrap admin superuser to it at boot.
            val adminRole = RbacRole(
              id = newId("r"),
              tenantId = withId.id,
              name = PoolSupervisor.AdminRoleName,
              description = Some(s"Built-in admin role for tenant ${withId.displayName}")
            )
            val adminPerm = RolePermission(
              id = newId("rp"),
              roleId = adminRole.id,
              catalogName = RolePermission.Wildcard,
              schemaName = RolePermission.Wildcard,
              tableName = RolePermission.Wildcard,
              verb = "ALL"
            )
            store.createTenantWithAdminRole(withId, adminRole, adminPerm)
            tenants.put(withId.id, withId)
            rbacResolver.putRole(adminRole)
            rbacResolver.putRolePermission(adminPerm)
            publish.topologyChanged()
            events.emit(ManagerEvent.TenantCreated(withId.id))
            Right(withId)
    }
  }

  def setTenantDisabled(name: String, disabled: Boolean): IO[Either[SupervisorError, Tenant]] =
    IO.blocking {
      withCacheRecovery("setTenantDisabled") {
        getTenant(name) match
          case None    => Left(SupervisorError.NotFound(s"tenant not found: $name"))
          case Some(t) =>
            val updated = t.copy(disabled = disabled)
            store.upsertTenant(updated)
            tenants.put(updated.id, updated)
            publish.topologyChanged()
            Right(updated)
      }
    }

  /** Swap the tenant's auth provider + provider config. Existing users/roles/groups unchanged.
    * Config-shape validation lives in the REST handler so the supervisor stays storage-only.
    */
  def setTenantAuth(
      name: String,
      authProvider: String,
      authConfig: Map[String, String]
  ): IO[Either[SupervisorError, Tenant]] = IO.blocking {
    withCacheRecovery("setTenantAuth") {
      if !Tenant.ValidAuthProviders.contains(authProvider) then
        Left(
          SupervisorError.InvalidArgument(
            s"authProvider must be one of ${Tenant.ValidAuthProviders.toList.sorted.mkString(", ")}"
          )
        )
      else
        getTenant(name) match
          case None    => Left(SupervisorError.NotFound(s"tenant not found: $name"))
          case Some(t) =>
            val updated = t.copy(authProvider = authProvider, authConfig = authConfig)
            store.upsertTenant(updated)
            tenants.put(updated.id, updated)
            publish.topologyChanged()
            Right(updated)
    }
  }

  def deleteTenant(name: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("deleteTenant") {
      getTenant(name) match
        case None    => Left(SupervisorError.NotFound(s"tenant not found: $name"))
        case Some(t) =>
          val tdbs = tenantDbs.values.filter(_.tenantId == t.id).toList
          // Symmetric with deleteTenantDb's guard: the in-memory poolRows cache alone can miss a
          // DB-only stray pool row (crash orphan, or a peer replica's fresh pool the LISTEN/NOTIFY
          // hasn't propagated yet), which would otherwise pass this guard and then hit
          // store.deleteTenantDb's FK RESTRICT partway through the per-tenant-db delete loop below
          // - a bodyless 500 AND a non-atomic partial deletion. Union both sources across every
          // tenant-db and fail closed. Same TOCTOU caveat as deleteTenantDb: read-then-delete is
          // not transacted.
          val activePoolIds =
            tdbs.flatMap(td => poolRows.values.filter(_.tenantDbId == td.id).map(_.id)).toSet ++
              tdbs.flatMap(td => store.listPools(td.id).map(_.id)).toSet
          if activePoolIds.nonEmpty then
            Left(
              SupervisorError.Conflict(
                s"tenant '$name' has ${activePoolIds.size} pool(s); stop them first"
              )
            )
          else
            tdbs.foreach { td =>
              store.deleteTenantDb(td.id)
              // Same tombstone stamp as deleteTenantDb: a cascaded delete must not strand a
              // managed prefix un-eligible, or its objects would be billed forever.
              stampManagedPrefixDeleted(td.id, td.name, purgeManagedData = false)
              tenantDbs.remove(td.id)
              try onTenantDbDeleted(name.toLowerCase, td.name)
              catch case _: Throwable => ()
              dbAdmin.dropDatabase(td.name) match
                case Right(_)  => ()
                case Left(err) =>
                  logger.warn(
                    s"deleteTenant: tenant-db row removed but DROP DATABASE \"${td.name}\" failed: $err"
                  )
              events.emit(ManagerEvent.TenantDbDeleted(name, td.name))
            }
            store.deleteTenant(t.id)
            tenants.remove(t.id)
            publish.topologyChanged()
            events.emit(ManagerEvent.TenantDeleted(name))
            Right(())
    }
  }

  // ---------- TenantDb API ----------

  def createTenantDb(
      tenantName: String,
      suffix: String,
      kind: TenantDbKind,
      metastore: Map[String, String],
      dataPath: String,
      objectStore: Map[String, String] = Map.empty,
      defaultDatabase: Option[String] = None,
      defaultSchema: Option[String] = None,
      initSql: String = "",
      /** Carve this database's storage out of the operator-managed bucket: the caller supplies
        * neither `dataPath` nor `objectStore`, and both are resolved here from [[managedStore]]
        * (prefix keyed by the freshly minted surrogate id, credentials from the config block).
        * Refused when managed storage is not configured. The REST layer additionally refuses it
        * together with a caller-supplied dataPath/objectStore and on non-DuckLake kinds.
        */
      managedStorage: Boolean = false,
      gateBypass: Boolean = false
  ): IO[Either[SupervisorError, TenantDb]] =
    gateCheck(
      ai.starlake.quack.spi.StructureMutation.CreateTenantDb(tenantName.toLowerCase),
      gateBypass
    ).flatMap {
      case Left(reason) => IO.pure(Left(SupervisorError.QuotaExceeded(reason)))
      case Right(())    =>
        IO.blocking {
          withCacheRecovery("createTenantDb") {
            Names.normalizeTenantDbName(tenantName, suffix) match
              case Left(err)   => Left(SupervisorError.InvalidName(err))
              case Right(full) =>
                val tn = tenantName.toLowerCase
                getTenant(tn) match
                  case None => Left(SupervisorError.NotFound(s"tenant not found: $tn"))
                  case Some(_) if managedStorage && managedStore.forall(!_.enabled) =>
                    Left(
                      SupervisorError.InvalidArgument(
                        "managed storage is not configured: set QOD_MANAGED_STORE_ENABLED " +
                          "and its credentials"
                      )
                    )
                  case Some(t)
                      if tenantDbs.values.exists(td => td.tenantId == t.id && td.name == full) =>
                    Left(
                      SupervisorError.AlreadyExists(
                        s"tenant-db '$full' already exists in tenant '$tn'"
                      )
                    )
                  case Some(t) =>
                    // Per-kind prep. DuckLake auto-injects dbName and pre-provisions the Postgres
                    // database + metadata tables; DuckDbFile and InMemory skip both.
                    val effectiveMeta = kind match
                      case TenantDbKind.DuckLake   => metastore.updated("dbName", full)
                      case TenantDbKind.DuckDbFile => metastore
                      case TenantDbKind.InMemory   => metastore

                    // Minted up front: a managed prefix is keyed by this id, so a recreated
                    // database of the same name never lands on its predecessor's data.
                    val id         = newId("td")
                    val managedCfg =
                      if managedStorage then managedStore.filter(_.enabled) else None
                    // `full` is `<tenant>_<suffix>` and ManagedPrefix.dataPath re-joins its
                    // (tenant, dbName) arguments with an underscore, so the db-name piece fed
                    // here is `full` minus its tenant prefix. Result: the spec's shape
                    // `s3://<bucket>/<tenant>_<dbname>-<id8>/`.
                    val effectiveDataPath = managedCfg.fold(dataPath) { cfg =>
                      ManagedPrefix.dataPath(cfg.bucket, tn, full.stripPrefix(s"${tn}_"), id)
                    }
                    val effectiveObjectStore =
                      managedCfg.fold(objectStore)(ManagedPrefix.objectStoreFor)

                    /** Tombstone row for the managed prefix, written with the tenant-db row so no
                      * managed create can leave storage carved out with nothing to purge it. No-op
                      * for BYO / default-path databases.
                      */
                    def recordManagedPrefix(): Unit =
                      managedCfg.foreach { _ =>
                        store.insertManagedPrefix(
                          id,
                          tn,
                          full,
                          effectiveDataPath,
                          java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                        )
                      }

                    val td = TenantDb(
                      id = id,
                      tenantId = t.id,
                      name = full,
                      kind = kind,
                      metastore = effectiveMeta,
                      dataPath = effectiveDataPath,
                      objectStore = effectiveObjectStore,
                      defaultDatabase = defaultDatabase,
                      defaultSchema = defaultSchema,
                      initSql = initSql
                    )

                    TenantDb.validate(td, defaultMetastore) match
                      case Some(msg) =>
                        Left(
                          SupervisorError.InvalidArgument(s"invalid kind=${kind.wireValue}: $msg")
                        )
                      case None =>
                        kind match
                          case TenantDbKind.DuckLake =>
                            dbAdmin.createDatabase(full) match
                              case Left(err) =>
                                Left(
                                  SupervisorError.Internal(
                                    s"failed to provision Postgres database '$full': $err"
                                  )
                                )
                              case Right(_) =>
                                // Pre-init the ducklake_* metadata tables so the first pool nodes
                                // don't race on `CREATE TABLE __ducklake_metadata`.
                                try
                                  DuckLakeInitializer.initBlocking(
                                    (defaultMetastore ++ effectiveMeta)
                                      .updated("dataPath", effectiveDataPath)
                                  )
                                  store.upsertTenantDb(td)
                                  recordManagedPrefix()
                                  tenantDbs.put(td.id, td)
                                  publish.topologyChanged()
                                  events.emit(ManagerEvent.TenantDbCreated(tenantName, td.name))
                                  Right(td)
                                catch
                                  case t: DuckLakeInitializer.DataPathMismatchException =>
                                    // Not transient: retrying reproduces the DATA_PATH error on
                                    // every future node spawn, so refuse to create the tenant-db
                                    // instead of the swallow-and-retry handling below.
                                    logger.error(
                                      s"createTenantDb: DuckLake pre-init for '$full' refused: " +
                                        t.getMessage
                                    )
                                    Left(SupervisorError.Internal(t.getMessage))
                                  case t: Throwable =>
                                    logger.warn(
                                      s"createTenantDb: DuckLake pre-init for '$full' failed; " +
                                        s"first pool spawn will retry the ATTACH. Cause: ${t.getMessage}"
                                    )
                                    store.upsertTenantDb(td)
                                    recordManagedPrefix()
                                    tenantDbs.put(td.id, td)
                                    publish.topologyChanged()
                                    events.emit(ManagerEvent.TenantDbCreated(tenantName, td.name))
                                    Right(td)
                          case TenantDbKind.DuckDbFile | TenantDbKind.InMemory =>
                            store.upsertTenantDb(td)
                            recordManagedPrefix()
                            tenantDbs.put(td.id, td)
                            publish.topologyChanged()
                            events.emit(ManagerEvent.TenantDbCreated(tenantName, td.name))
                            Right(td)
          }
        }
    }

  /** Stamp the deleted tenant-db's managed-prefix tombstone: `deletedAt` now, `purgeEligibleAt`
    * `retainDays` later (or now when the caller asked for an immediate purge). The retention window
    * falls back to the config default when managed storage is not wired on this replica, so a row
    * written by another replica is never stranded permanently un-eligible. Already-stamped rows are
    * left alone: re-stamping would silently extend or shorten a window the purge worker may already
    * be acting on.
    */
  private def stampManagedPrefixDeleted(
      tenantDbId: String,
      tenantDbName: String,
      purgeManagedData: Boolean
  ): Unit =
    store.managedPrefix(tenantDbId) match
      case Some(row) if row.deletedAt.isEmpty =>
        val now        = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val retainDays = managedStore.map(_.retainDays).getOrElse(7)
        val eligible   =
          if purgeManagedData then now
          else now.plus(retainDays.toLong, java.time.temporal.ChronoUnit.DAYS)
        store.markManagedPrefixDeleted(tenantDbId, now, eligible)
      case Some(_) => ()
      case None    =>
        if purgeManagedData then
          logger.warn(s"purgeManagedData ignored: '$tenantDbName' has no managed prefix")

  /** `purgeManagedData` makes a managed database's storage eligible for the purge worker
    * immediately instead of after `managedStore.retainDays`; the objects themselves are removed
    * asynchronously, so the call still returns as fast as a plain delete. Ignored with a WARN on a
    * BYO / default-path database, which has no managed prefix to purge.
    */
  def deleteTenantDb(
      tenantName: String,
      tenantDbName: String,
      purgeManagedData: Boolean = false
  ): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      withCacheRecovery("deleteTenantDb") {
        val tn = tenantName.toLowerCase
        getTenant(tn) match
          case None    => Left(SupervisorError.NotFound(s"tenant not found: $tn"))
          case Some(t) =>
            tenantDbs.values.find(td => td.tenantId == t.id && td.name == tenantDbName) match
              case None =>
                Left(
                  SupervisorError.NotFound(s"tenant-db '$tenantDbName' not found in tenant '$tn'")
                )
              case Some(td) =>
                // The store is the authoritative source, not just the in-memory cache: a DB-only
                // stray pool row (crash orphan, or a peer replica's fresh pool the LISTEN/NOTIFY
                // hasn't propagated yet) would pass an in-memory-only guard and then blow up on
                // store.deleteTenantDb's FK RESTRICT (qodstate_pool.tenant_db_id) as a bodyless
                // 500. Union both sources and fail closed - a DB-only row may be a live peer's
                // pool, never sweep it here. This read-then-delete pair is not transacted, so a
                // microseconds-wide TOCTOU window against a concurrent pool insert remains by
                // design; the FK RESTRICT is the backstop if that window is ever hit.
                val activePoolIds =
                  poolRows.values.filter(_.tenantDbId == td.id).map(_.id).toSet ++
                    store.listPools(td.id).map(_.id).toSet
                if activePoolIds.nonEmpty then
                  Left(
                    SupervisorError.Conflict(
                      s"tenant-db '$tenantDbName' has ${activePoolIds.size} pool(s); stop them first"
                    )
                  )
                else
                  store.deleteTenantDb(td.id)
                  stampManagedPrefixDeleted(td.id, tenantDbName, purgeManagedData)
                  tenantDbs.remove(td.id)
                  dataPathBlocked.remove(td.id)
                  try onTenantDbDeleted(tn, tenantDbName)
                  catch case _: Throwable => ()
                  dbAdmin.dropDatabase(td.name) match
                    case Right(_)  => ()
                    case Left(err) =>
                      logger.warn(
                        s"deleteTenantDb: control-plane row removed but " +
                          s"DROP DATABASE \"${td.name}\" failed: $err"
                      )
                  publish.topologyChanged()
                  events.emit(ManagerEvent.TenantDbDeleted(tenantName, tenantDbName))
                  Right(())
      }
    }

  /** Merge a patch into a tenant-db, persist, refresh caches, and restart every node of its pools
    * when node-affecting fields (metastore, objectStore, initSql) changed. Restart is all-at-once
    * via restartNode's path; per-node failures are collected, not thrown (reconcile heals).
    * Response-redacted keys ([[TenantDb.SecretKeys]]) are preserved when omitted; an empty value
    * removes the key.
    */
  def updateTenantDb(
      tenantName: String,
      dbName: String,
      patch: TenantDbPatch
  ): IO[Either[SupervisorError, TenantDbUpdateResult]] =
    IO.blocking(findTenantDb(tenantName, dbName)).flatMap {
      case None =>
        IO.pure(Left(SupervisorError.NotFound(s"tenant-db $tenantName/$dbName not found")))
      case Some(td) =>
        val merged = td.copy(
          metastore = patch.metastore.fold(td.metastore)(mergeSecretKeys(td.metastore, _)),
          objectStore = patch.objectStore.fold(td.objectStore)(mergeSecretKeys(td.objectStore, _)),
          defaultDatabase = patch.defaultDatabase.fold(td.defaultDatabase)(nonBlank),
          defaultSchema = patch.defaultSchema.fold(td.defaultSchema)(nonBlank),
          initSql = patch.initSql.getOrElse(td.initSql)
        )
        TenantDb.validateSafety(merged) match
          case Some(msg) => IO.pure(Left(SupervisorError.InvalidArgument(s"invalid: $msg")))
          case None      =>
            // Keys the manager config can stand in for: dropping one of these from a
            // ducklake row is "revert to default", not a contract violation. duckdb-file
            // has no default-merge contract on create, so it keeps the strict guard.
            val defaultedKeys =
              if merged.kind == TenantDbKind.DuckLake then
                defaultMetastore.collect { case (k, v) if v.nonEmpty => k }.toSet
              else Set.empty[String]
            val droppedRequired =
              (td.metastore.keySet & TenantDb.requiredMetastoreKeys(merged.kind)) --
                merged.metastore.keySet -- defaultedKeys
            if droppedRequired.nonEmpty then
              IO.pure(
                Left(
                  SupervisorError.InvalidArgument(
                    s"invalid: metastore update drops required key(s) ${droppedRequired.mkString(", ")}; " +
                      "send the full map (pgPassword may be omitted, it is preserved)"
                  )
                )
              )
            else {
              val nodeAffecting =
                merged.metastore != td.metastore ||
                  merged.objectStore != td.objectStore ||
                  merged.initSql != td.initSql
              val refresh = withCacheRecoveryIO("updateTenantDb") {
                IO.blocking {
                  store.upsertTenantDb(merged)
                  tenantDbs.put(merged.id, merged)
                  // Node-affecting edit is the documented remediation for a boot-time
                  // DataPathMismatchException, so clear any block rather than skipping this
                  // tenant-db's pools forever. Optimistic: a wrong fix re-blocks on the next boot.
                  if nodeAffecting then dataPathBlocked.remove(merged.id)
                  // Unlocked read-modify-write; self-heals via restore()/NOTIFY.
                  pools.foreach { case (key, state) =>
                    if key.tenant == tenantName.toLowerCase && key.tenantDb == merged.name then
                      pools.put(
                        key,
                        state.copy(
                          metastore = effectiveMetastoreFor(merged),
                          s3 = merged.objectStore,
                          dbInitSql = merged.initSql,
                          defaultDatabase = merged.defaultDatabase,
                          defaultSchema = merged.defaultSchema
                        )
                      )
                  }
                  if merged.metastore != td.metastore then
                    try onTenantDbChanged(tenantName.toLowerCase, merged.name)
                    catch case _: Throwable => ()
                  publish.topologyChanged()
                }
              }
              val restarts =
                if !nodeAffecting then IO.pure((List.empty[String], List.empty[(String, String)]))
                else
                  IO.delay {
                    pools.toList.collect {
                      case (key, state)
                          if key.tenant == tenantName.toLowerCase && key.tenantDb == merged.name =>
                        state.nodes.map(n => (key, n.nodeId))
                    }.flatten
                  }.flatMap { targets =>
                    targets.foldLeft(IO.pure((List.empty[String], List.empty[(String, String)]))) {
                      case (acc, (key, nodeId)) =>
                        acc.flatMap { case (ok, failed) =>
                          restartNode(key, nodeId).attempt.map {
                            case Right(Right(())) => (ok :+ nodeId, failed)
                            case Right(Left(msg)) => (ok, failed :+ (nodeId -> msg.message))
                            case Left(t)          =>
                              (ok, failed :+ (nodeId -> Option(t.getMessage).getOrElse(t.toString)))
                          }
                        }
                    }
                  }
              refresh *> restarts.map { case (ok, failed) =>
                Right(TenantDbUpdateResult(merged, ok, failed))
              }
            }
    }

  /** Empty patch value clears an Option field; non-blank sets it. */
  private def nonBlank(s: String): Option[String] =
    val t = s.trim
    if t.isEmpty then None else Some(t)

  /** Replace-the-map, except keys redacted from API responses ([[TenantDb.SecretKeys]]:
    * `pgPassword` plus object-store secrets) are carried from the stored map when the incoming map
    * lacks them (no client can round-trip a value it never sees). An incoming redacted key with an
    * EMPTY value removes it.
    */
  private def mergeSecretKeys(
      stored: Map[String, String],
      incoming: Map[String, String]
  ): Map[String, String] =
    val secretKeys =
      stored.keys.filter(k => TenantDb.SecretKeys.exists(_.equalsIgnoreCase(k))).toList
    val carried = secretKeys.collect {
      case k if !incoming.keys.exists(_.equalsIgnoreCase(k)) => k -> stored(k)
    }
    val explicit = incoming.filter { case (k, v) =>
      !(TenantDb.SecretKeys.exists(_.equalsIgnoreCase(k)) && v.isEmpty)
    }
    explicit ++ carried

  // ---------- Pool API ----------

  /** Create a pool under an existing tenant-db. The tenant-db's metastore + objectStore are the
    * only storage config (no per-pool override). The caller must have created the tenant-db first.
    */
  def createPool(
      key: PoolKey,
      dist: RoleDistribution,
      maxConcurrentPerNode: Int = 0,
      cohorts: List[PoolCohort] = Nil,
      // Persist disabled=true: the edge refuses fresh handshakes until the operator enables the
      // pool. Nodes are still spawned (same as setPoolDisabled(true) right after create).
      disabled: Boolean = false,
      // Persist suspended=true and spawn NO nodes: the pool exists in the control plane (catalog
      // init still runs) and cold-starts on the first statement or explicit resume. Signup's
      // mass-provisioning depends on this being cheap.
      startSuspended: Boolean = false,
      // Free-form per-pool SQL prepended to the federation blob, shipped via $extraSetupSql. Order
      // matters: initSql runs FIRST so PRAGMAs are in effect before any federation source's ATTACH.
      initSql: String = "",
      cpu: String = "",
      memory: String = "",
      podTemplateYaml: String = "",
      // Per-pool lockdown override persisted at create time. None = inherit.
      lockdown: Option[Boolean] = None,
      // Owner-declared scale-out band, persisted at create time. Both None = fixed size.
      minNodes: Option[Int] = None,
      maxNodes: Option[Int] = None,
      // Per-pool hibernation window in seconds, persisted at create time. None = inherit the
      // manager-wide default; 0 = explicit opt-out; positive = idle window (5-minute floor
      // applied by the sweep).
      idleTimeoutSec: Option[Int] = None,
      gateBypass: Boolean = false
  ): IO[List[RunningNode]] =
    gateCheck(
      ai.starlake.quack.spi.StructureMutation
        .CreatePool(key.tenant, key.tenantDb, dist.total, cpu, memory),
      gateBypass
    ).flatMap {
      case Left(reason) =>
        IO.raiseError(new ai.starlake.quack.spi.QuotaExceededException(reason))
      case Right(()) =>
        locks.withLock(key)(withCacheRecoveryIO("createPool")(IO.defer {
          val size = dist.total
          require(
            dist.writeonly >= 0 && dist.readonly >= 0 && dist.dual >= 0,
            s"role distribution must be non-negative: $dist"
          )
          require(size > 0, s"role distribution must sum to at least 1: $dist")
          require(dist.isValidFor(size), s"role distribution does not sum to $size")
          // Cohorts are always persisted as authored so a local-defined pool can be exported and
          // replayed on K8s with placement intact. K8s reads `NodeSpec.placement`; local backends
          // ignore it (the UI warns before submit).
          if cohorts.nonEmpty && !backend.supportsPlacement then
            logger.info(
              s"backend ${backend.getClass.getSimpleName} does not honor node placement; " +
                s"persisting ${cohorts.size} cohort(s) for pool $key but they will be " +
                "ignored at runtime"
            )
          // Per-cohort distributions must sum to `dist`; reject mismatches up-front so the persisted
          // row never disagrees with the spawned nodes.
          cohorts.foreach { c =>
            require(
              c.distribution.writeonly >= 0 && c.distribution.readonly >= 0 && c.distribution.dual >= 0,
              s"cohort distribution must be non-negative: ${c.distribution}"
            )
          }
          if cohorts.nonEmpty then
            val summed = cohorts.map(_.distribution).foldLeft(RoleDistribution(0, 0, 0)) { (a, b) =>
              RoleDistribution(a.writeonly + b.writeonly, a.readonly + b.readonly, a.dual + b.dual)
            }
            require(
              summed == dist,
              s"cohort distributions sum to $summed but pool distribution is $dist"
            )

          findTenantDb(key.tenant, key.tenantDb) match
            case None =>
              IO.raiseError(
                new IllegalStateException(
                  s"tenant-db '${key.tenant}/${key.tenantDb}' not found; create it first"
                )
              )
            case Some(td)
                if pools.keys.exists(k =>
                  k.tenant == key.tenant && k.pool == key.pool && k.tenantDb != key.tenantDb
                ) =>
              // Pool names are unique within a tenant (qodstate_pool UNIQUE (tenant_id, name)). The
              // edge resolves (tenant, pool) -> PoolKey at handshake, so the same name in two
              // tenant-dbs under one tenant would make that lookup ambiguous.
              IO.raiseError(
                new IllegalStateException(
                  s"pool '${key.pool}' already exists under tenant '${key.tenant}' " +
                    "in a different tenant-db; pool names must be unique per tenant"
                )
              )
            case Some(td) =>
              val merged   = effectiveMetastoreFor(td)
              val kindWire = td.kind.wireValue
              federationBlobOf(td.id).flatMap { blobOpt =>
                // initSql runs first so PRAGMAs/INSTALL land before the federation blob's ATTACHes;
                // both ship via NodeSpec.extraSetupSql.
                val fedBlob = blobOpt.getOrElse("")
                // state.extraSetupSql keeps the federation blob ONLY, so a restart that re-projects
                // PoolState still has initSql separately and respawn re-concatenates without
                // double-prepending.
                val poolEntity = Pool(
                  id = newId("p"),
                  tenantId = td.tenantId,
                  tenantDbId = td.id,
                  name = key.pool,
                  size = size,
                  distribution = dist,
                  maxConcurrentPerNode = maxConcurrentPerNode,
                  disabled = disabled,
                  suspended = startSuspended,
                  cohorts = cohorts,
                  initSql = initSql,
                  cpu = cpu,
                  memory = memory,
                  podTemplateYaml = podTemplateYaml,
                  lockdown = lockdown,
                  minNodes = minNodes,
                  maxNodes = maxNodes,
                  idleTimeoutSec = idleTimeoutSec
                )
                IO.blocking(store.upsertPool(poolEntity)) *> IO.delay {
                  poolRows.put(poolEntity.id, poolEntity)
                  poolIdByKey.put(key, poolEntity.id)
                } *> IO.defer {
                  // Deferred: specFromState resolves effectiveLockdown(key) via poolRows, which the
                  // IO.delay above only populates once this chain runs. A non-deferred block would
                  // evaluate specs eagerly, before that put, and fall back to the global flag.
                  // Walk cohorts in order; empty `cohorts` falls back to one placement-less cohort
                  // carrying `dist`.
                  val plan: List[(ai.starlake.quack.model.Role, NodePlacement)] =
                    poolEntity.effectiveCohorts.flatMap { c =>
                      c.distribution.asRoleList.map(role => (role, c.placement))
                    }
                  // Pool state sans nodes; spawned nodes fold back in below before it is published.
                  val preState = PoolState(
                    key,
                    Nil,
                    dist,
                    merged,
                    td.objectStore,
                    maxConcurrentPerNode,
                    disabled = disabled,
                    suspended = startSuspended,
                    kindWire = kindWire,
                    // Federation blob only; respawn concatenates with initSql fresh.
                    extraSetupSql = fedBlob,
                    dbInitSql = td.initSql,
                    initSql = initSql,
                    defaultDatabase = td.defaultDatabase,
                    defaultSchema = td.defaultSchema,
                    cpu = cpu,
                    memory = memory,
                    podTemplateYaml = podTemplateYaml
                  )
                  val specs = plan.zipWithIndex.map { case ((role, placement), i) =>
                    specFromState(
                      key,
                      preState,
                      PoolSupervisor.nodeId(key, i + 1),
                      role,
                      placement,
                      maxConcurrentPerNode
                    )
                  }
                  val spawnIO: IO[List[RunningNode]] =
                    if startSuspended then IO.pure(Nil) else spawnAll(key, specs)
                  spawnIO
                    .flatMap { running =>
                      pools.put(key, preState.copy(nodes = running))
                      running
                        .foldLeft(IO.unit)((acc, n) =>
                          acc *> IO.blocking(store.upsertNode(n, poolEntity.id))
                        )
                        .map { _ =>
                          publish.topologyChanged()
                          events.emit(ManagerEvent.PoolCreated(key.tenant, key.tenantDb, key.pool))
                        }
                        .as(running)
                    }
                }
              }
        }))
    }

  def setPoolDisabled(key: PoolKey, disabled: Boolean): IO[Either[SupervisorError, Pool]] =
    IO.blocking {
      withCacheRecovery("setPoolDisabled") {
        pools.get(key) match
          case None        => Left(SupervisorError.NotFound(s"pool not found: $key"))
          case Some(state) =>
            poolIdByKey.get(key).flatMap(poolRows.get) match
              case None =>
                Left(
                  SupervisorError.Internal(
                    s"pool entity missing for $key (control-plane out of sync)"
                  )
                )
              case Some(p) =>
                val updated = p.copy(disabled = disabled)
                store.upsertPool(updated)
                poolRows.put(updated.id, updated)
                pools.put(key, state.copy(disabled = disabled))
                publish.topologyChanged()
                Right(updated)
      }
    }

  /** Single resolution point for the per-pool lockdown tri-state. None on the pool row (or no row
    * at all: maintenance nodes, races during create) falls back to the manager-global flag. Feeds
    * BOTH the edge screen (FlightSqlRouter.lockdownFor) and the spawn-time engine SQL.
    */
  def effectiveLockdown(key: PoolKey): Boolean =
    poolIdByKey.get(key).flatMap(poolRows.get).flatMap(_.lockdown).getOrElse(lockdownEnabled)

  /** The owner-declared scale-out band, resolved from the persisted row (the band stays out of
    * PoolState on purpose, like lockdown). None = pool unknown OR fixed size.
    */
  def autoscaleBand(key: PoolKey): Option[(Int, Int)] =
    poolIdByKey.get(key).flatMap(poolRows.get).flatMap(p => p.minNodes.zip(p.maxNodes))

  /** The pool's hibernation override, resolved from the persisted row (stays out of PoolState on
    * purpose, like the band). None = pool unknown OR no override; 0 = explicit opt-out; a positive
    * value is the pool's idle window in seconds (see HibernationWiringSupport.idleFor).
    */
  def idleTimeoutSec(key: PoolKey): Option[Int] =
    poolIdByKey.get(key).flatMap(poolRows.get).flatMap(_.idleTimeoutSec)

  /** Persists under the pool's advisory lock so the write serializes with reconcile's respawn (same
    * lock), mirroring [[setPoolLockdown]]. Does NOT validate the band shape (min < max, size inside
    * the band, hardCap): that is the handler's job via `AutoscaleBand.validate`. Some sets the
    * band, None clears it back to fixed size.
    */
  def setPoolAutoscale(
      key: PoolKey,
      band: Option[(Int, Int)]
  ): IO[Either[SupervisorError, Pool]] =
    locks.withLock(key) {
      IO.blocking {
        withCacheRecovery("setPoolAutoscale") {
          pools.get(key) match
            case None    => Left(SupervisorError.NotFound(s"pool not found: $key"))
            case Some(_) =>
              poolIdByKey.get(key).flatMap(poolRows.get) match
                case None =>
                  Left(
                    SupervisorError.Internal(
                      s"pool entity missing for $key (control-plane out of sync)"
                    )
                  )
                case Some(p) =>
                  val updated = p.copy(minNodes = band.map(_._1), maxNodes = band.map(_._2))
                  store.upsertPool(updated)
                  poolRows.put(updated.id, updated)
                  publish.topologyChanged()
                  Right(updated)
        }
      }
    }

  /** Persists under the pool's advisory lock so the write serializes with reconcile's respawn (same
    * lock): either the persist lands before reconcile builds a NodeSpec off
    * `effectiveLockdown(key)` (new node picks up the fresh value), or after (the caller's later
    * restart loop, run by [[ai.starlake.quack.ondemand.api.PoolHandlers.setLockdown]] AFTER this
    * IO, catches it up). No node is registered mid-write on a half-applied flag.
    */
  def setPoolLockdown(
      key: PoolKey,
      lockdown: Option[Boolean]
  ): IO[Either[SupervisorError, Pool]] =
    locks.withLock(key) {
      IO.blocking {
        withCacheRecovery("setPoolLockdown") {
          pools.get(key) match
            case None    => Left(SupervisorError.NotFound(s"pool not found: $key"))
            case Some(_) =>
              poolIdByKey.get(key).flatMap(poolRows.get) match
                case None =>
                  Left(
                    SupervisorError.Internal(
                      s"pool entity missing for $key (control-plane out of sync)"
                    )
                  )
                case Some(p) =>
                  val updated = p.copy(lockdown = lockdown)
                  store.upsertPool(updated)
                  poolRows.put(updated.id, updated)
                  publish.topologyChanged()
                  Right(updated)
        }
      }
    }

  def setPoolResources(
      key: PoolKey,
      cpu: String,
      memory: String,
      gateBypass: Boolean = false
  ): IO[Either[SupervisorError, Pool]] =
    val current  = get(key)
    val mutation = ai.starlake.quack.spi.StructureMutation.SetPoolResources(
      key.tenant,
      key.tenantDb,
      key.pool,
      nodes = current.map(_.distribution.total).getOrElse(0),
      fromCpu = current.map(_.cpu).getOrElse(""),
      fromMemory = current.map(_.memory).getOrElse(""),
      toCpu = cpu,
      toMemory = memory
    )
    gateCheck(mutation, gateBypass).flatMap {
      case Left(reason) => IO.pure(Left(SupervisorError.QuotaExceeded(reason)))
      case Right(())    =>
        IO.blocking {
          withCacheRecovery("setPoolResources") {
            pools.get(key) match
              case None        => Left(SupervisorError.NotFound(s"pool not found: $key"))
              case Some(state) =>
                poolIdByKey.get(key).flatMap(poolRows.get) match
                  case None =>
                    Left(
                      SupervisorError.Internal(
                        s"pool entity missing for $key (control-plane out of sync)"
                      )
                    )
                  case Some(p) =>
                    val updated = p.copy(cpu = cpu, memory = memory)
                    store.upsertPool(updated)
                    poolRows.put(updated.id, updated)
                    pools.put(key, state.copy(cpu = cpu, memory = memory))
                    publish.topologyChanged()
                    Right(updated)
          }
        }
    }

  def setPoolTemplate(key: PoolKey, yaml: String): IO[Either[SupervisorError, Pool]] =
    IO.blocking {
      withCacheRecovery("setPoolTemplate") {
        pools.get(key) match
          case None        => Left(SupervisorError.NotFound(s"pool not found: $key"))
          case Some(state) =>
            poolIdByKey.get(key).flatMap(poolRows.get) match
              case None =>
                Left(
                  SupervisorError.Internal(
                    s"pool entity missing for $key (control-plane out of sync)"
                  )
                )
              case Some(p) =>
                val updated = p.copy(podTemplateYaml = yaml)
                store.upsertPool(updated)
                poolRows.put(updated.id, updated)
                pools.put(key, state.copy(podTemplateYaml = yaml))
                publish.topologyChanged()
                Right(updated)
      }
    }

  def setMaxConcurrent(key: PoolKey, nodeId: String, max: Int): IO[Option[RunningNode]] =
    pools.get(key).flatMap(s => s.nodes.find(_.nodeId == nodeId)) match
      case None    => IO.pure(None)
      case Some(n) =>
        withCacheRecoveryIO("setMaxConcurrent") {
          val u        = n.copy(maxConcurrent = max)
          val state    = pools(key)
          val newNodes = state.nodes.map(x => if x.nodeId == nodeId then u else x)
          pools.put(key, state.copy(nodes = newNodes))
          poolIdByKey.get(key) match
            case Some(pid) =>
              IO.blocking(store.upsertNode(u, pid)).map { _ => publish.topologyChanged(); Some(u) }
            case None => IO.delay { publish.topologyChanged(); Some(u) }
        }

  def scale(
      key: PoolKey,
      targetSize: Int,
      newDist: RoleDistribution,
      force: Boolean,
      gateBypass: Boolean = false,
      reason: String = "manual"
  ): IO[List[RunningNode]] =
    require(newDist.isValidFor(targetSize), "role distribution does not sum to targetSize")
    val st    = get(key)
    val from  = st.map(_.distribution.total).getOrElse(0)
    val shape = st.map(s => (s.cpu, s.memory)).getOrElse(("", ""))
    gateCheck(
      ai.starlake.quack.spi.StructureMutation.ResizePool(
        key.tenant,
        key.tenantDb,
        key.pool,
        from,
        targetSize,
        shape._1,
        shape._2
      ),
      gateBypass
    ).flatMap {
      case Left(reason) =>
        IO.raiseError(new ai.starlake.quack.spi.QuotaExceededException(reason))
      case Right(()) =>
        locks
          .withLock(key) {
            withCacheRecoveryIO("scale")(scaleUnlocked(key, targetSize, newDist, force))
          }
          .flatTap { nodes =>
            IO(
              events.emit(
                ManagerEvent
                  .PoolScaled(key.tenant, key.tenantDb, key.pool, from, nodes.size, reason)
              )
            )
          }
    }

  private def scaleUnlocked(
      key: PoolKey,
      targetSize: Int,
      newDist: RoleDistribution,
      force: Boolean
  ): IO[List[RunningNode]] =
    pools.get(key) match
      case None => IO.raiseError(new NoSuchElementException(s"pool not found: $key"))
      case Some(state) if state.suspended =>
        // Scaling a hibernated pool would spawn nodes while suspended stays true, and the next
        // reconcile heal pass would drain them right back. The REST handler pre-checks and 409s;
        // this raise covers non-REST callers.
        IO.raiseError(
          new IllegalStateException(s"pool $key is suspended; resume it before scaling")
        )
      case Some(state) =>
        val poolId = poolIdByKey.getOrElse(key, "")

        // Reconcile per role against the ACTUAL roles of the running nodes, never a positional slice
        // of `newDist.asRoleList` (which is ordered [WriteOnly, ReadOnly, Dual], so a positional
        // diff mis-assigns roles). Per-role diffing also lets one operation both add and remove
        // (e.g. swap a ReadOnly for a WriteOnly at constant size). Counts read by name via
        // `newDist.countFor`.

        // Surplus nodes of each over-provisioned role (newest first), and the deficit roles to spawn.
        val toRemove: List[RunningNode] = RoleDistribution.spawnOrder.flatMap { role =>
          val have   = state.nodes.filter(_.role == role)
          val excess = have.size - newDist.countFor(role)
          if excess > 0 then have.takeRight(excess) else Nil
        }
        val rolesToAdd: List[Role] = RoleDistribution.spawnOrder.flatMap { role =>
          List.fill((newDist.countFor(role) - state.nodes.count(_.role == role)).max(0))(role)
        }

        if toRemove.isEmpty && rolesToAdd.isEmpty then IO.pure(state.nodes)
        else
          // Fresh ids start above the current high-water mark so they never collide with survivors
          // during a mixed add/remove. Scaling clears authored cohorts (updatePoolEntityDist below),
          // so new nodes spawn placement-less by design.
          val baseIndex = state.size
          val specs     = rolesToAdd.zipWithIndex.map { case (role, i) =>
            specFromState(
              key,
              state,
              PoolSupervisor.nodeId(key, baseIndex + i + 1),
              role,
              NodePlacement.empty,
              state.maxConcurrentPerNode
            )
          }
          val survivors = state.nodes.filterNot(n => toRemove.exists(_.nodeId == n.nodeId))

          val stopRemoved =
            if force then
              toRemove.foldLeft(IO.unit)((acc, n) =>
                acc *> stopNodeBestEffort(key, n.nodeId, "scale-down")
              )
            else
              toRemove.foldLeft(IO.unit) { (acc, n) =>
                acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *> drainAndStop(key, n)
              }
          val deleteRemoved =
            // Drop both the store row and the tracker entry now the node is stopped. setDraining
            // (force=false above) created the entry; without this remove it lingers in snapshotAll
            // with draining=true forever.
            toRemove.foldLeft(IO.unit) { (acc, n) =>
              acc *> IO.blocking(store.deleteNode(n.nodeId)) *> IO.delay(tracker.remove(n.nodeId))
            }

          stopRemoved *> deleteRemoved *>
            spawnAll(key, specs)
              .map(survivors ++ _)
              .flatMap { combined =>
                pools.put(key, state.copy(nodes = combined, distribution = newDist))
                updatePoolEntityDist(key, newDist, combined.size)
                val added = combined.drop(survivors.size)
                (if poolId.nonEmpty then
                   added
                     .foldLeft(IO.unit)((acc, n) => acc *> IO.blocking(store.upsertNode(n, poolId)))
                 else IO.unit).map { _ => publish.topologyChanged(); combined }
              }

  /** Stop every node but KEEP the pool registered: the row survives and in-memory state stays with
    * empty nodes + zero distribution, so the pool is scaled to 0 and stays drained across a restart
    * (reconcile only respawns non-zero distributions). Use [[deletePool]] to remove it.
    * `force=true` kills immediately; `force=false` drains first. On a suspended pool (nodes already
    * gone) the same end state is persisted directly: distribution zeroed and the suspended flag
    * cleared, so it stays down instead of auto-waking on the next query.
    */
  def stopPool(key: PoolKey, force: Boolean): IO[Unit] =
    val work: IO[Unit] = pools.get(key) match
      case None                   => IO.unit
      case Some(s) if s.suspended =>
        // A suspended pool has no nodes, so scale(0) would early-return without persisting the
        // zeroed distribution: it stays non-zero and suspended stays true, so the next query
        // auto-wakes a pool the operator stopped. Persist the stopPool contract directly: zero the
        // distribution AND clear the flag. Re-checked under the lock in case a resume raced the
        // unlocked read above (then the plain scale-to-zero path applies).
        locks.withLock(key)(withCacheRecoveryIO("stopPool")(IO.defer {
          pools.get(key) match
            case None                            => IO.unit
            case Some(state) if !state.suspended =>
              scaleUnlocked(key, 0, RoleDistribution(0, 0, 0), force).void
            case Some(state) =>
              val zero = RoleDistribution(0, 0, 0)
              poolIdByKey.get(key).flatMap(poolRows.get) match
                case None    => IO.unit
                case Some(p) =>
                  val updated =
                    p.copy(size = 0, distribution = zero, cohorts = Nil, suspended = false)
                  IO.blocking {
                    store.upsertPool(updated)
                    poolRows.put(updated.id, updated)
                    pools.put(key, state.copy(nodes = Nil, distribution = zero, suspended = false))
                    publish.topologyChanged()
                  }
        }))
      case Some(_) => scale(key, 0, RoleDistribution(0, 0, 0), force).void
    work.flatTap(_ => IO(onPoolTeardown(key)))

  /** Scale-to-zero: set suspended=true, then drain-stop every node while KEEPING the persisted
    * distribution (unlike [[stopPool]], which zeroes it). Reconcile never respawns suspended pools
    * (and drains any live nodes a crash in the flag-persist/drain window left); the edge (or
    * [[resumePool]]) wakes them. Idempotent. The flag is set FIRST so a query racing the drain sees
    * suspended=true and re-wakes the pool once the lock frees.
    */
  def suspendPool(key: PoolKey, reason: String): IO[Either[SupervisorError, Pool]] =
    locks
      .withLock(key)(withCacheRecoveryIO("suspendPool")(IO.defer {
        pools.get(key) match
          case None        => IO.pure(Left(SupervisorError.NotFound(s"pool not found: $key")))
          case Some(state) =>
            poolIdByKey.get(key).flatMap(poolRows.get) match
              case None =>
                IO.pure(
                  Left(
                    SupervisorError.Internal(
                      s"pool entity missing for $key (control-plane out of sync)"
                    )
                  )
                )
              case Some(p) =>
                val updated = p.copy(suspended = true)
                val flagIO  = IO.blocking {
                  store.upsertPool(updated)
                  poolRows.put(updated.id, updated)
                  pools.put(key, state.copy(suspended = true))
                  publish.topologyChanged()
                }
                flagIO *> drainAndForgetNodes(key, state.nodes) *> IO
                  .delay {
                    pools.put(key, state.copy(nodes = Nil, suspended = true))
                    publish.topologyChanged()
                    events.emit(
                      ManagerEvent.PoolSuspended(key.tenant, key.tenantDb, key.pool, reason)
                    )
                  }
                  .as(Right(updated))
      }))
      .flatTap {
        case Right(_) => IO(onPoolTeardown(key))
        case Left(_)  => IO.unit
      }

  /** Wake a suspended pool: clear the flag, respawn to the stored distribution (via
    * [[spawnFromDistribution]], the same path reconcile uses). Idempotent; resuming a non-suspended
    * pool is a no-op success. Returns once spawning is initiated; callers observe readiness via
    * [[snapshot]].
    */
  def resumePool(key: PoolKey, reason: String): IO[Either[SupervisorError, Pool]] =
    locks.withLock(key)(withCacheRecoveryIO("resumePool")(IO.defer {
      pools.get(key) match
        case None        => IO.pure(Left(SupervisorError.NotFound(s"pool not found: $key")))
        case Some(state) =>
          poolIdByKey.get(key).flatMap(poolRows.get) match
            case None =>
              IO.pure(
                Left(
                  SupervisorError.Internal(
                    s"pool entity missing for $key (control-plane out of sync)"
                  )
                )
              )
            case Some(p) if !state.suspended => IO.pure(Right(p))
            case Some(p)                     =>
              val updated = p.copy(suspended = false)
              val cleared = state.copy(suspended = false)
              IO.blocking {
                store.upsertPool(updated)
                poolRows.put(updated.id, updated)
                pools.put(key, cleared)
                publish.topologyChanged()
              } *>
                (if state.nodes.isEmpty && state.distribution.total > 0 then
                   spawnFromDistribution(key, cleared).void
                 else IO.unit) *>
                IO.delay(
                  events.emit(ManagerEvent.PoolResumed(key.tenant, key.tenantDb, key.pool, reason))
                ).as(Right(updated))
    }))

  /** Remove the pool entirely: stop every node, then delete the pool and its node rows and forget
    * it in memory. The only path that deletes a pool; [[stopPool]] merely scales it to 0.
    */
  def deletePool(key: PoolKey, force: Boolean): IO[Unit] =
    locks
      .withLock(key) {
        withCacheRecoveryIO("deletePool")(deletePoolUnlocked(key, force))
      }
      .flatTap(_ => IO(onPoolTeardown(key)))

  private def deletePoolUnlocked(key: PoolKey, force: Boolean): IO[Unit] =
    pools.get(key) match
      case None        => IO.unit
      case Some(state) =>
        val stopAll =
          if force then
            state.nodes.foldLeft(IO.unit)((acc, n) =>
              acc *> stopNodeBestEffort(key, n.nodeId, "pool-delete")
            )
          else
            state.nodes.foldLeft(IO.unit) { (acc, n) =>
              acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *> drainAndStop(key, n)
            }
        stopAll *>
          state.nodes.foldLeft(IO.unit)((acc, n) =>
            // Store row and tracker entry both go now the node is stopped, so a drained-then-deleted
            // node leaves nothing behind in snapshotAll.
            acc *> IO.blocking(store.deleteNode(n.nodeId)) *> IO.delay(tracker.remove(n.nodeId))
          ) *>
          IO.blocking {
            poolIdByKey.get(key).foreach { pid =>
              // Sweep DB-side rows the in-memory state never saw (crash orphans from a
              // failed teardown) so the pool-row delete can't hit the FK RESTRICT. A
              // stray row backed by a live pod loses its row without a stop; reconcile
              // or a manual sweep reaps the pod.
              store.deleteNodesForPool(pid)
              store.deletePool(pid)
              poolRows.remove(pid)
            }
            pools.remove(key)
            poolIdByKey.remove(key)
            publish.topologyChanged()
            events.emit(ManagerEvent.PoolDeleted(key.tenant, key.tenantDb, key.pool))
          }

  private def drainAndStop(key: PoolKey, n: RunningNode, reason: String = "drain"): IO[Unit] =
    stopNodeBestEffort(key, n.nodeId, reason)

  /** Drain-stop then forget each node (mark draining, [[drainAndStop]] with reason "suspend", then
    * delete the store row + tracker entry). Shared by [[suspendPool]] and reconcile's
    * suspended-pool heal so the per-node sequence can't drift.
    */
  private def drainAndForgetNodes(key: PoolKey, nodes: List[RunningNode]): IO[Unit] =
    val drainIO = nodes.foldLeft(IO.unit) { (acc, n) =>
      acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *>
        drainAndStop(key, n, reason = "suspend")
    }
    val forgetIO = nodes.foldLeft(IO.unit) { (acc, n) =>
      acc *> IO.blocking(store.deleteNode(n.nodeId)) *> IO.delay(tracker.remove(n.nodeId))
    }
    drainIO *> forgetIO

  /** Operator restart of a single node: stop it (in-flight statements fail to their clients),
    * respawn through reconcile's NodeSpec path, clear any quarantine so the fresh node is routable,
    * broadcast. Left(message) when pool or node is unknown.
    */
  def restartNode(key: PoolKey, nodeId: String): IO[Either[SupervisorError, Unit]] =
    locks.withLock(key) {
      withCacheRecoveryIO("restartNode") {
        IO.delay(pools.get(key)).flatMap {
          case None        => IO.pure(Left(SupervisorError.NotFound(s"pool $key not found")))
          case Some(state) =>
            state.nodes.find(_.nodeId == nodeId) match
              case None =>
                IO.pure(Left(SupervisorError.NotFound(s"node $nodeId not found in $key")))
              case Some(n) =>
                for
                  _     <- stopNodeEmitting(key, n.nodeId, "respawn")
                  _     <- IO.delay(tracker.remove(n.nodeId))
                  fresh <- startNodeEmitting(key, respawnSpec(key, state, n))
                  _     <- poolIdByKey.get(key) match
                    case Some(pid) => IO.blocking(store.upsertNode(fresh, pid))
                    case None      => IO.unit
                  _ <- IO.blocking(store.setNodeQuarantined(nodeId, false))
                  _ <- IO.delay {
                    tracker.setQuarantined(nodeId, false)
                    val updated = state.copy(
                      nodes = state.nodes.map(x => if x.nodeId == nodeId then fresh else x)
                    )
                    pools.put(key, updated)
                    publish.topologyChanged()
                  }
                yield Right(())
        }
      }
    }

  // ---------- RBAC: users ----------

  /** Persist a new (tenant, username) principal with its bcrypt hash, and register it with the
    * resolver so role grants can FK to it. Returns the persisted [[RbacUser]]. `tenant = None`
    * creates a superuser. `mustChangePassword = true` marks `password` as temporary: the principal
    * cannot log in until it is swapped through the self-service change-password path.
    */
  def createUser(
      tenant: Option[String],
      username: String,
      password: String,
      role: String = "user",
      userStore: ai.starlake.quack.ondemand.state.UserStore,
      mustChangePassword: Boolean = false,
      email: Option[String] = None,
      // enabled = false persists the row disabled from the first write (SCIM
      // creates with active: false), atomically -- no enabled window.
      enabled: Boolean = true,
      // failIfExists = true makes this a true CREATE: an existing (tenant,
      // username) row is refused untouched (no password rotation, no role
      // change) instead of upserted. SCIM provisioning retries depend on it.
      failIfExists: Boolean = false
  ): IO[Either[SupervisorError, RbacUser]] = IO.blocking {
    withCacheRecovery("createUser") {
      if username.isEmpty || password.isEmpty then
        Left(SupervisorError.InvalidArgument("username and password are required"))
      else
        tenant match
          case Some(t) if t.isEmpty =>
            Left(
              SupervisorError.InvalidArgument("tenant must be non-empty (use None for superuser)")
            )
          case Some(t)
              if !tenants.values.exists(x => x.id == t || x.displayName == t.toLowerCase) =>
            Left(SupervisorError.NotFound(s"tenant not found: $t"))
          case _ =>
            val resolvedTenantId = tenant.flatMap { t =>
              tenants.values.find(x => x.id == t || x.displayName == t.toLowerCase).map(_.id)
            }
            // An email-format username IS its own email: derive/verify it here so a
            // conflicting supplied value is refused instead of persisted.
            EmailPolicy.resolve(username, email) match
              case Left(msg)       => Left(SupervisorError.InvalidEmail(msg))
              case Right(effEmail) =>
                // Create always sets email, even to None (clearing is not meaningful on a
                // brand-new row, but a fresh insert with no email is the common case).
                val out = userStore.upsertUser(
                  resolvedTenantId,
                  username,
                  password,
                  role,
                  mustChangePassword = Some(mustChangePassword),
                  email = Some(effEmail),
                  enabled = Option.when(!enabled)(false),
                  insertOnly = failIfExists
                )
                if failIfExists && !out.inserted then
                  Left(SupervisorError.InvalidArgument(s"user already exists: $username"))
                else
                  val u = RbacUser(
                    out.id,
                    resolvedTenantId,
                    username,
                    role,
                    enabled = enabled,
                    mustChangePassword = mustChangePassword,
                    email = effEmail
                  )
                  store.upsertUserIdentity(u)
                  Right(u)
    }
  }

  def updateUserPassword(
      userId: String,
      password: Option[String],
      role: Option[String],
      userStore: ai.starlake.quack.ondemand.state.UserStore,
      mustChangePassword: Option[Boolean] = None,
      email: Option[Option[String]] = None,
      enabled: Option[Boolean] = None
  ): IO[Either[SupervisorError, RbacUser]] = IO.blocking {
    withCacheRecovery("updateUserPassword") {
      if mustChangePassword.contains(true) && password.isEmpty then
        Left(
          SupervisorError.InvalidArgument(
            "mustChangePassword requires a password in the same request"
          )
        )
      else
        store.getUserById(userId) match
          case None    => Left(SupervisorError.NotFound(s"user not found: $userId"))
          case Some(u) =>
            // email: outer None = unchanged (leave the rule untouched); Some(inner) = a
            // write, resolved against the ROW's username so an email-format user's email
            // stays locked to the username and a conflicting value is refused.
            val emailCheck: Either[SupervisorError, Option[Option[String]]] = email match
              case None        => Right(None)
              case Some(inner) =>
                EmailPolicy.resolve(u.username, inner) match
                  case Left(msg)  => Left(SupervisorError.InvalidEmail(msg))
                  case Right(eff) => Right(Some(eff))
            emailCheck match
              case Left(err)       => Left(err)
              case Right(effEmail) =>
                val newRole = role.getOrElse(u.role)
                // A rotation always writes the flag: the requested value, or false when
                // absent -- an unflagged admin reset hands out a normal password and
                // clears any pending must-change state. Role-only updates leave it alone.
                val newFlag = password.map { pw =>
                  val flag = mustChangePassword.getOrElse(false)
                  userStore.upsertUser(
                    u.tenant,
                    u.username,
                    pw,
                    newRole,
                    mustChangePassword = Some(flag),
                    email = effEmail
                  )
                  flag
                }
                // enabled and/or email land via a row rewrite through the control-plane
                // store with the already-persisted hash, so only the intended columns
                // move. The password branch above never writes `enabled`, so a lock
                // rides this rewrite even when a rotation happened in the same request.
                val needRewrite = enabled.nonEmpty || (password.isEmpty && effEmail.nonEmpty)
                val rewriteOk: Either[SupervisorError, Unit] =
                  if !needRewrite then Right(())
                  else
                    store.getPasswordHash(u.tenant, u.username) match
                      case Some(hash) =>
                        store.upsertUserWithHash(
                          u.tenant,
                          u.username,
                          hash,
                          newRole,
                          enabled = enabled.getOrElse(u.enabled),
                          mustChangePassword = newFlag.getOrElse(u.mustChangePassword),
                          email = effEmail.getOrElse(u.email)
                        )
                        Right(())
                      case None =>
                        // A row with no stored hash cannot be rewritten without inventing
                        // a credential. Refuse loudly rather than answering ok while
                        // writing nothing; unreachable for API-created rows because
                        // password_hash is NOT NULL, so this only ever names corruption.
                        Left(
                          SupervisorError.Internal(
                            s"user ${u.username} has no stored password hash; update refused"
                          )
                        )
                rewriteOk match
                  case Left(err) => Left(err)
                  case Right(()) =>
                    // upsertUserIdentity only writes (tenant, username, role) on conflict,
                    // so the flag/email/enabled just persisted above survive; carry them
                    // on the returned value.
                    val updated =
                      u.copy(
                        role = newRole,
                        mustChangePassword = newFlag.getOrElse(u.mustChangePassword),
                        email = effEmail.getOrElse(u.email),
                        enabled = enabled.getOrElse(u.enabled)
                      )
                    store.upsertUserIdentity(updated)
                    invalidateEffectiveCache()
                    Right(updated)
    }
  }

  def deleteUser(userId: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("deleteUser") {
      store.getUserById(userId) match
        case None    => Left(SupervisorError.NotFound(s"user not found: $userId"))
        case Some(_) =>
          // ON DELETE CASCADE in qodstate_user_role / user_group / pool_permission cleans up the
          // user's edges automatically.
          store.deleteUser(userId)
          invalidateEffectiveCache()
          Right(())
    }
  }

  def listUsers(tenant: Option[String]): List[RbacUser] = tenant match
    case Some(t) =>
      tenants.values.find(x => x.id == t || x.displayName == t.toLowerCase) match
        case Some(tn) => store.listUsers(Some(tn.id))
        case None     => Nil
    case None => store.listUsers(None)

  // ---------- RBAC: roles ----------

  def createRole(
      tenantId: String,
      name: String,
      description: Option[String] = None
  ): IO[Either[SupervisorError, RbacRole]] = IO.blocking {
    withCacheRecovery("createRole") {
      if name.isEmpty then Left(SupervisorError.InvalidArgument("role name must be non-empty"))
      else if !tenants.contains(tenantId) then
        Left(SupervisorError.NotFound(s"tenant not found: $tenantId"))
      else if store.findRole(tenantId, name).isDefined then
        Left(SupervisorError.AlreadyExists(s"role '$name' already exists in tenant '$tenantId'"))
      else
        val r = RbacRole(newId("r"), tenantId, name, description)
        store.upsertRole(r)
        rbacResolver.putRole(r)
        invalidateEffectiveCache()
        Right(r)
    }
  }

  def deleteRole(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("deleteRole") {
      rbacResolver.role(id) match
        case None    => Left(SupervisorError.NotFound(s"role not found: $id"))
        case Some(_) =>
          store.deleteRole(id)
          rbacResolver.removeRole(id)
          invalidateEffectiveCache()
          Right(())
    }
  }

  def listRoles(tenantId: String): List[RbacRole] = store.listRoles(tenantId)

  def grantRolePermission(
      roleId: String,
      catalog: String,
      schema: String,
      table: String,
      verb: String
  ): IO[Either[SupervisorError, RolePermission]] = IO.blocking {
    withCacheRecovery("grantRolePermission") {
      val upper = verb.toUpperCase
      if !RolePermission.ValidVerbs.contains(upper) then
        Left(
          SupervisorError.InvalidArgument(
            s"verb must be one of ${RolePermission.ValidVerbs.mkString(", ")}"
          )
        )
      else if rbacResolver.role(roleId).isEmpty then
        Left(SupervisorError.NotFound(s"role not found: $roleId"))
      else
        val p         = RolePermission(newId("rp"), roleId, catalog, schema, table, upper)
        val persisted = store.insertRolePermission(p)
        rbacResolver.putRolePermission(persisted)
        invalidateEffectiveCache()
        Right(persisted)
    }
  }

  def revokeRolePermission(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("revokeRolePermission") {
      if store.deleteRolePermission(id) then
        rbacResolver.removeRolePermission(id)
        invalidateEffectiveCache()
        Right(())
      else Left(SupervisorError.NotFound(s"role permission not found: $id"))
    }
  }

  def listRolePermissions(roleId: String): List[RolePermission] =
    store.listRolePermissions(roleId)

  // ---------- RBAC: column policies ----------

  /** Resolve a column-policy id to its owning tenant via the parent role. Used by
    * `TenantScopeCheck` to refuse cross-tenant calls without a separate join.
    */
  def tenantForColumnPolicy(id: String): Option[String] =
    store.getColumnPolicy(id).flatMap(p => rbacResolver.role(p.roleId).map(_.tenantId))

  def createColumnPolicy(
      roleId: String,
      catalogName: String,
      schemaName: String,
      tableName: String,
      columnName: String,
      action: String,
      transformSql: Option[String]
  ): IO[Either[SupervisorError, state.RoleColumnPolicy]] = IO.blocking {
    withCacheRecovery("createColumnPolicy") {
      val normalisedTransform = transformSql.map(_.trim).filter(_.nonEmpty)
      if !state.RoleColumnPolicy.ValidActions.contains(action) then
        Left(
          SupervisorError.InvalidArgument(
            s"action must be one of ${state.RoleColumnPolicy.ValidActions.mkString(", ")}"
          )
        )
      else if columnName == state.RoleColumnPolicy.Wildcard || columnName.trim.isEmpty then
        Left(SupervisorError.InvalidArgument("columnName is required and must not be '*'"))
      else if action == state.RoleColumnPolicy.ActionMask && normalisedTransform.isEmpty then
        Left(SupervisorError.InvalidArgument("transformSql is required when action='mask'"))
      else if action == state.RoleColumnPolicy.ActionDeny && normalisedTransform.isDefined then
        Left(SupervisorError.InvalidArgument("transformSql must be empty when action='deny'"))
      else
        val validatedTransform: Either[SupervisorError, Option[String]] =
          if action == state.RoleColumnPolicy.ActionMask then
            normalisedTransform
              .toRight(
                SupervisorError.InvalidArgument("transformSql is required when action='mask'")
              )
              .flatMap { raw =>
                ai.starlake.quack.edge.cls.TransformSqlValidator.validate(raw, columnName) match
                  case ai.starlake.quack.edge.cls.TransformSqlValidator.Invalid(reason) =>
                    Left(SupervisorError.InvalidArgument(s"invalid transformSql: $reason"))
                  case ai.starlake.quack.edge.cls.TransformSqlValidator.Valid(canon) =>
                    Right(Some(canon))
              }
          else Right(None)
        validatedTransform match
          case Left(err)             => Left(err)
          case Right(canonTransform) =>
            val p = state.RoleColumnPolicy(
              id = newId("cp"),
              roleId = roleId,
              catalogName = catalogName,
              schemaName = schemaName,
              tableName = tableName,
              columnName = columnName,
              action = action,
              transformSql = canonTransform
            )
            val persisted = store.insertColumnPolicy(p)
            invalidateEffectiveCache()
            Right(persisted)
    }
  }

  def updateColumnPolicy(
      id: String,
      action: String,
      transformSql: Option[String]
  ): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("updateColumnPolicy") {
      val normalisedTransform = transformSql.map(_.trim).filter(_.nonEmpty)
      if !state.RoleColumnPolicy.ValidActions.contains(action) then
        Left(
          SupervisorError.InvalidArgument(
            s"action must be one of ${state.RoleColumnPolicy.ValidActions.mkString(", ")}"
          )
        )
      else if action == state.RoleColumnPolicy.ActionMask && normalisedTransform.isEmpty then
        Left(SupervisorError.InvalidArgument("transformSql is required when action='mask'"))
      else if action == state.RoleColumnPolicy.ActionDeny && normalisedTransform.isDefined then
        Left(SupervisorError.InvalidArgument("transformSql must be empty when action='deny'"))
      else
        val validatedTransform: Either[SupervisorError, Option[String]] =
          if action == state.RoleColumnPolicy.ActionMask then
            normalisedTransform
              .toRight(
                SupervisorError.InvalidArgument("transformSql is required when action='mask'")
              )
              .flatMap { raw =>
                // updateColumnPolicy doesn't know the columnName; fetch it from the store.
                store.getColumnPolicy(id) match
                  case None => Left(SupervisorError.NotFound(s"column policy $id not found"))
                  case Some(existing) =>
                    ai.starlake.quack.edge.cls.TransformSqlValidator
                      .validate(raw, existing.columnName) match
                      case ai.starlake.quack.edge.cls.TransformSqlValidator.Invalid(reason) =>
                        Left(SupervisorError.InvalidArgument(s"invalid transformSql: $reason"))
                      case ai.starlake.quack.edge.cls.TransformSqlValidator.Valid(canon) =>
                        Right(Some(canon))
              }
          else Right(None)
        validatedTransform match
          case Left(err)             => Left(err)
          case Right(canonTransform) =>
            val ok = store.updateColumnPolicy(id, action, canonTransform)
            if ok then { invalidateEffectiveCache(); Right(()) }
            else Left(SupervisorError.NotFound(s"column policy $id not found"))
    }
  }

  def deleteColumnPolicy(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("deleteColumnPolicy") {
      if store.deleteColumnPolicy(id) then { invalidateEffectiveCache(); Right(()) }
      else Left(SupervisorError.NotFound(s"column policy $id not found"))
    }
  }

  def listColumnPoliciesByRole(roleId: String): IO[List[state.RoleColumnPolicy]] =
    IO.blocking(store.listColumnPolicies(roleId))

  // ---------- RBAC: row policies ----------

  def tenantForRowPolicy(id: String): Option[String] =
    store.getRowPolicy(id).flatMap(p => rbacResolver.role(p.roleId).map(_.tenantId))

  def createRowPolicy(
      roleId: String,
      catalogName: String,
      schemaName: String,
      tableName: String,
      predicateSql: String
  ): IO[Either[SupervisorError, state.RoleRowPolicy]] = IO.blocking {
    withCacheRecovery("createRowPolicy") {
      ai.starlake.quack.edge.rls.RowPredicateValidator.validate(predicateSql) match
        case ai.starlake.quack.edge.rls.RowPredicateValidator.Invalid(reason) =>
          Left(SupervisorError.InvalidArgument(s"invalid predicateSql: $reason"))
        case ai.starlake.quack.edge.rls.RowPredicateValidator.Valid(canon) =>
          val p = state.RoleRowPolicy(
            id = newId("rp"),
            roleId = roleId,
            catalogName = catalogName,
            schemaName = schemaName,
            tableName = tableName,
            predicateSql = canon
          )
          val persisted = store.insertRowPolicy(p)
          invalidateEffectiveCache()
          Right(persisted)
    }
  }

  def updateRowPolicy(id: String, predicateSql: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      withCacheRecovery("updateRowPolicy") {
        ai.starlake.quack.edge.rls.RowPredicateValidator.validate(predicateSql) match
          case ai.starlake.quack.edge.rls.RowPredicateValidator.Invalid(reason) =>
            Left(SupervisorError.InvalidArgument(s"invalid predicateSql: $reason"))
          case ai.starlake.quack.edge.rls.RowPredicateValidator.Valid(canon) =>
            val ok = store.updateRowPolicy(id, canon)
            if ok then { invalidateEffectiveCache(); Right(()) }
            else Left(SupervisorError.NotFound(s"row policy $id not found"))
      }
    }

  def deleteRowPolicy(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("deleteRowPolicy") {
      if store.deleteRowPolicy(id) then { invalidateEffectiveCache(); Right(()) }
      else Left(SupervisorError.NotFound(s"row policy $id not found"))
    }
  }

  def listRowPoliciesByRole(roleId: String): IO[List[state.RoleRowPolicy]] =
    IO.blocking(store.listRowPolicies(roleId))

  // ---------- RBAC: groups ----------

  def createGroup(
      tenantId: String,
      name: String,
      description: Option[String] = None
  ): IO[Either[SupervisorError, RbacGroup]] = IO.blocking {
    withCacheRecovery("createGroup") {
      if name.isEmpty then Left(SupervisorError.InvalidArgument("group name must be non-empty"))
      else if !tenants.contains(tenantId) then
        Left(SupervisorError.NotFound(s"tenant not found: $tenantId"))
      else if store.findGroup(tenantId, name).isDefined then
        Left(SupervisorError.AlreadyExists(s"group '$name' already exists in tenant '$tenantId'"))
      else
        val g = RbacGroup(newId("g"), tenantId, name, description)
        store.upsertGroup(g)
        rbacResolver.putGroup(g)
        invalidateEffectiveCache()
        Right(g)
    }
  }

  def deleteGroup(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("deleteGroup") {
      rbacResolver.group(id) match
        case None    => Left(SupervisorError.NotFound(s"group not found: $id"))
        case Some(_) =>
          store.deleteGroup(id)
          rbacResolver.removeGroup(id)
          invalidateEffectiveCache()
          Right(())
    }
  }

  def listGroups(tenantId: String): List[RbacGroup] = store.listGroups(tenantId)

  /** User ids belonging to a group, for the SCIM Group.members projection. */
  def usersInGroup(groupId: String): List[String] = store.listUsersInGroup(groupId)

  /** Batch membership read for the SCIM Groups list: one query for all users' group edges
    * instead of one usersInGroup call per group.
    */
  def groupsByUsers(userIds: List[String]): Map[String, Set[String]] =
    store.listGroupsByUsers(userIds)

  /** SCIM externalId writes: identity metadata only, no effect on the RBAC closure, so no
    * effective-cache invalidation.
    */
  def setUserExternalId(id: String, externalId: Option[String]): Unit =
    store.setUserExternalId(id, externalId)

  def setGroupExternalId(id: String, externalId: Option[String]): Unit =
    store.setGroupExternalId(id, externalId)

  def listRolesForGroup(groupId: String): List[RbacRole] =
    rbacResolver.rolesForGroup(groupId).toList.flatMap(rbacResolver.role).sortBy(_.name)

  // ---------- RBAC: memberships ----------

  def addUserRole(userId: String, roleId: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("addUserRole") {
      membershipCheck(userId, roleId, rbacResolver.role(_), _.tenantId, "role") match
        case Some(err) => Left(err)
        case None      =>
          store.addUserRole(userId, roleId)
          invalidateEffectiveCache()
          Right(())
    }
  }

  def removeUserRole(userId: String, roleId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      withCacheRecovery("removeUserRole") {
        store.removeUserRole(userId, roleId)
        invalidateEffectiveCache()
        Right(())
      }
    }

  def addUserGroup(userId: String, groupId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      withCacheRecovery("addUserGroup") {
        membershipCheck(userId, groupId, rbacResolver.group(_), _.tenantId, "group") match
          case Some(err) => Left(err)
          case None      =>
            store.addUserGroup(userId, groupId)
            invalidateEffectiveCache()
            Right(())
      }
    }

  def removeUserGroup(userId: String, groupId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      withCacheRecovery("removeUserGroup") {
        store.removeUserGroup(userId, groupId)
        invalidateEffectiveCache()
        Right(())
      }
    }

  def addGroupRole(groupId: String, roleId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      withCacheRecovery("addGroupRole") {
        (rbacResolver.group(groupId), rbacResolver.role(roleId)) match
          case (None, _) => Left(SupervisorError.NotFound(s"group not found: $groupId"))
          case (_, None) => Left(SupervisorError.NotFound(s"role not found: $roleId"))
          case (Some(g), Some(r)) if g.tenantId != r.tenantId =>
            Left(
              SupervisorError.InvalidArgument(
                s"cross-tenant membership not allowed: group tenant ${g.tenantId} " +
                  s"!= role tenant ${r.tenantId}"
              )
            )
          case (Some(_), Some(_)) =>
            store.addGroupRole(groupId, roleId)
            rbacResolver.addGroupRoleEdge(groupId, roleId)
            invalidateEffectiveCache()
            Right(())
      }
    }

  def removeGroupRole(groupId: String, roleId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      withCacheRecovery("removeGroupRole") {
        store.removeGroupRole(groupId, roleId)
        rbacResolver.removeGroupRoleEdge(groupId, roleId)
        invalidateEffectiveCache()
        Right(())
      }
    }

  /** Validate a user->role or user->group edge before it is written. Beyond existence, enforces
    * TENANT ALIGNMENT: allowed only when user and role/group share a tenant. Roles/groups always
    * carry a non-null `tenantId`; a user's `tenant: Option` is `None` for a superuser. Requires
    * `user.tenant == Some(otherTenant)`:
    *   - same tenant -> allowed;
    *   - tenant-A user + tenant-B role/group -> rejected (the escalation this closes);
    *   - superuser + any tenant-scoped role/group -> rejected (a superuser already bypasses the
    *     pool/ACL gates, so it must not accrue a tenant-scoped role; mirrors
    *     `grantPoolPermission`).
    */
  private def membershipCheck[A](
      userId: String,
      otherId: String,
      lookup: String => Option[A],
      tenantOf: A => String,
      otherLabel: String
  ): Option[SupervisorError] =
    store.getUserById(userId) match
      case None       => Some(SupervisorError.NotFound(s"user not found: $userId"))
      case Some(user) =>
        lookup(otherId) match
          case None        => Some(SupervisorError.NotFound(s"$otherLabel not found: $otherId"))
          case Some(other) =>
            val otherTenant = tenantOf(other)
            if user.tenant.contains(otherTenant) then None
            else
              Some(
                SupervisorError.InvalidArgument(
                  s"cross-tenant membership not allowed: user tenant " +
                    s"${user.tenant.getOrElse("(superuser)")} != $otherLabel tenant $otherTenant"
                )
              )

  // ---------- RBAC: pool permissions ----------

  def grantPoolPermission(
      tenantId: String,
      poolId: Option[String],
      userId: Option[String],
      groupId: Option[String]
  ): IO[Either[SupervisorError, PoolPermission]] = IO.blocking {
    withCacheRecovery("grantPoolPermission") {
      // Short-circuit on the first failing predicate. The tenant-scoping check (principal belongs to
      // the target tenant) is last since it needs the principal lookup to have succeeded.
      val problem: Option[SupervisorError] =
        if !tenants.contains(tenantId) then
          Some(SupervisorError.NotFound(s"tenant not found: $tenantId"))
        else if userId.isDefined == groupId.isDefined then
          Some(SupervisorError.InvalidArgument("exactly one of userId / groupId must be set"))
        else
          poolId
            .flatMap(p =>
              if poolRows.contains(p) then None
              else Some(SupervisorError.NotFound(s"pool not found: $p"))
            )
            .orElse(
              userId.flatMap(u =>
                if store.getUserById(u).isDefined then None
                else Some(SupervisorError.NotFound(s"user not found: $u"))
              )
            )
            .orElse(
              groupId.flatMap(g =>
                if rbacResolver.group(g).isDefined then None
                else Some(SupervisorError.NotFound(s"group not found: $g"))
              )
            )
            .orElse {
              // Principal must belong to the tenant the grant covers. A superuser (tenant=None)
              // cannot receive a tenant-scoped grant; the resolver's bypass already gives them
              // every pool.
              val ok = userId match
                case Some(u) => store.getUserById(u).exists(_.tenant.contains(tenantId))
                case None    => groupId.flatMap(rbacResolver.group).exists(_.tenantId == tenantId)
              if ok then None
              else
                Some(
                  SupervisorError.InvalidArgument("principal does not belong to the target tenant")
                )
            }

      problem match
        case Some(err) => Left(err)
        case None      =>
          val pp        = PoolPermission(newId("pp"), tenantId, poolId, userId, groupId)
          val persisted = store.insertPoolPermission(pp)
          rbacResolver.putPoolPermission(persisted)
          invalidateEffectiveCache()
          Right(persisted)
    }
  }

  def revokePoolPermission(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    withCacheRecovery("revokePoolPermission") {
      if store.deletePoolPermission(id) then
        rbacResolver.removePoolPermission(id)
        invalidateEffectiveCache()
        Right(())
      else Left(SupervisorError.NotFound(s"pool permission not found: $id"))
    }
  }

  def listPoolPermissions(
      tenantId: Option[String] = None,
      userId: Option[String] = None,
      groupId: Option[String] = None
  ): List[PoolPermission] = store.listPoolPermissions(tenantId, userId, groupId)

  // ---------- RBAC: handshake authorization ----------

  /** End-to-end FlightSQL handshake gate, in order:
    *   1. resolve `(tenant, pool) -> PoolKey` + tenant/pool kill switches
    *   2. lookup the user via [[ControlPlaneStore.findUserForLogin]]; reject `enabled = false`.
    *      This query ALSO enforces tenant scope (returns `tenant IS NULL` superusers OR
    *      `tenant = <tenantId>`), so no app-layer `user.tenant == tenantRow.id` re-check. If
    *      findUserForLogin ever drops the tenant filter, reinstate the scope check between gates 2
    *      and 3.
    *   3. compute the effective set (groups, roles, permissions, pool grants)
    *   4. pool-access check (skipped for superusers): effective pool grants must cover the
    *      addressed pool (pool_id NULL = "every pool in this tenant")
    *
    * Returns [[ai.starlake.quack.ondemand.rbac.AuthorizedHandshake]] on success; a left-string
    * names the failed gate.
    */
  def authorizeHandshake(
      tenantName: String,
      poolName: String,
      username: String,
      jwtRoles: Set[String] = Set.empty,
      jwtGroups: Set[String] = Set.empty
  ): Either[String, ai.starlake.quack.ondemand.rbac.AuthorizedHandshake] =
    // 1. Pool + kill switches.
    findPoolKeyByTenantAndPoolName(tenantName, poolName)
      .toRight(
        s"pool '$poolName' not found in tenant '$tenantName'"
      )
      .flatMap { key =>
        getTenant(key.tenant) match
          case Some(t) if t.disabled =>
            Left(s"tenant '${key.tenant}' is disabled")
          case None =>
            Left(s"tenant '${key.tenant}' is not registered")
          case Some(tenantRow) =>
            get(key) match
              case Some(s) if s.disabled =>
                Left(s"pool '${key.pool}' in tenant '${key.tenant}' is disabled")
              case Some(_) =>
                val poolId = poolIdByKey.getOrElse(key, "")
                // 2. User lookup, tenant-scoped at the SQL layer (see scaladoc): any returned user
                //    is admissible.
                store.findUserForLogin(tenantRow.id, username) match
                  case None =>
                    Left(s"user '$username' is not registered in tenant '${key.tenant}'")
                  case Some(user) if !user.enabled =>
                    Left(s"user '$username' is disabled")
                  case Some(user) =>
                    // 3. Effective set. Superusers get an empty set; the per-statement validator
                    //    bypasses them.
                    val eff =
                      if user.tenant.isEmpty then
                        ai.starlake.quack.ondemand.rbac.EffectiveSet(user, Nil, Nil, Nil, Nil)
                      else
                        effectiveSetForUser(user.id, jwtRoles, jwtGroups).getOrElse(
                          ai.starlake.quack.ondemand.rbac.EffectiveSet(user, Nil, Nil, Nil, Nil)
                        )
                    // 4. Pool-access check.
                    val poolOk =
                      user.tenant.isEmpty ||
                        eff.poolPerms
                          .exists(p => p.tenantId == tenantRow.id && p.poolId.forall(_ == poolId))
                    if !poolOk then
                      Left(s"user '$username' has no access to pool '${key.tenant}/${key.pool}'")
                    else
                      Right(
                        ai.starlake.quack.ondemand.rbac.AuthorizedHandshake(
                          poolKey = key,
                          tenantId = tenantRow.id,
                          poolId = poolId,
                          user = user,
                          effectiveSet = eff
                        )
                      )
              case None =>
                Left(s"pool '${key.pool}' not found in tenant '${key.tenant}'")
      }

  // ---------- RBAC: effective-set closure ----------

  /** Closure of `(roles, groups, permissions, pool grants)` for one user. Combines direct edges
    * (from Postgres) with the cached [[rbacResolver]] graph AND any JWT-claimed role / group names,
    * resolved against `qodstate_role.name` / `qodstate_group.name` in the user's tenant and
    * union-merged before closure. `jwtRoles` / `jwtGroups` are name sets; empty means no claims.
    * Names unknown to the manager are silently dropped.
    */
  def effectiveSetForUser(
      userId: String,
      jwtRoles: Set[String] = Set.empty,
      jwtGroups: Set[String] = Set.empty
  ): Option[ai.starlake.quack.ondemand.rbac.EffectiveSet] =
    val key    = EffectiveCacheKey(userId, jwtRoles.hashCode, jwtGroups.hashCode)
    val cached = effectiveCache.getIfPresent(key)
    if cached != null then Some(cached)
    else
      computeEffectiveSetForUser(userId, jwtRoles, jwtGroups).map { computed =>
        effectiveCache.put(key, computed)
        computed
      }

  private def computeEffectiveSetForUser(
      userId: String,
      jwtRoles: Set[String],
      jwtGroups: Set[String]
  ): Option[ai.starlake.quack.ondemand.rbac.EffectiveSet] =
    store.getUserById(userId).map { u =>
      val directRoleIdsLocal = store.listDirectRolesForUser(u.id).toSet
      val groupIdsLocal      = store.listGroupsForUser(u.id).toSet
      // JWT-claim resolution. Only tenant-scoped users carry a tenant id; superusers bypass
      // per-statement validation upstream, so the union is a no-op for them.
      val jwtRoleIds  = u.tenant.toSet.flatMap(t => rbacResolver.rolesByNamesInTenant(t, jwtRoles))
      val jwtGroupIds =
        u.tenant.toSet.flatMap(t => rbacResolver.groupsByNamesInTenant(t, jwtGroups))
      val directRoleIds  = directRoleIdsLocal ++ jwtRoleIds
      val groupIds       = groupIdsLocal ++ jwtGroupIds
      val viaGroups      = groupIds.flatMap(rbacResolver.rolesForGroup)
      val allRoleIds     = directRoleIds ++ viaGroups
      val effRoles       = allRoleIds.flatMap(rbacResolver.role).toList.sortBy(_.name)
      val effGroups      = groupIds.flatMap(rbacResolver.group).toList.sortBy(_.name)
      val effPerms       = rbacResolver.permissionsForRoles(allRoleIds)
      val directPools    = store.listPoolPermissionsForUser(u.id)
      val viaGroupPools  = groupIds.toList.flatMap(rbacResolver.poolPermissionsForGroup)
      val columnPolicies = allRoleIds.toList.flatMap(store.listColumnPolicies)
      val rowPolicies    = allRoleIds.toList.flatMap(store.listRowPolicies)
      ai.starlake.quack.ondemand.rbac.EffectiveSet(
        u,
        effRoles,
        effGroups,
        effPerms,
        directPools ++ viaGroupPools,
        columnPolicies,
        rowPolicies
      )
    }

  /** Bulk version: one Postgres round-trip per dependency instead of N+1. Keyed by user id; users
    * with no edges are present with empty lists. Used by `/user/list`.
    */
  def effectiveSetsForUsers(
      users: List[RbacUser]
  ): Map[String, ai.starlake.quack.ondemand.rbac.EffectiveSet] =
    if users.isEmpty then Map.empty
    else
      val ids       = users.map(_.id)
      val rolesByU  = store.listDirectRolesByUsers(ids)
      val groupsByU = store.listGroupsByUsers(ids)
      val poolsByU  = store.listPoolPermissionsByUsers(ids)
      users.iterator.map { u =>
        val directRoleIds = rolesByU.getOrElse(u.id, Set.empty)
        val groupIds      = groupsByU.getOrElse(u.id, Set.empty)
        val viaGroups     = groupIds.flatMap(rbacResolver.rolesForGroup)
        val allRoleIds    = directRoleIds ++ viaGroups
        val effRoles      = allRoleIds.flatMap(rbacResolver.role).toList.sortBy(_.name)
        val effGroups     = groupIds.flatMap(rbacResolver.group).toList.sortBy(_.name)
        val effPerms      = rbacResolver.permissionsForRoles(allRoleIds)
        val directPools   = poolsByU.getOrElse(u.id, Nil)
        val viaGroupPools = groupIds.toList.flatMap(rbacResolver.poolPermissionsForGroup)
        u.id -> ai.starlake.quack.ondemand.rbac.EffectiveSet(
          u,
          effRoles,
          effGroups,
          effPerms,
          directPools ++ viaGroupPools
        )
      }.toMap

  // ---------- helpers ----------

  private def newId(prefix: String): String = ai.starlake.quack.model.Names.newSurrogateId(prefix)

  private def updatePoolEntityDist(key: PoolKey, dist: RoleDistribution, size: Int): Unit =
    poolIdByKey.get(key).flatMap(poolRows.get).foreach { p =>
      // Scale changes the distribution out from under any cohort plan, so clear cohorts; recreate
      // with cohorts is the supported way to change placement after the fact.
      val updated = p.copy(size = size, distribution = dist, cohorts = Nil)
      store.upsertPool(updated)
      poolRows.put(updated.id, updated)
    }

object PoolSupervisor:
  val AdminRoleName: String = "admin"

  /** Concatenate per-pool [[ai.starlake.quack.ondemand.PoolState.initSql]] with the federation blob
    * for shipment as a single `extraSetupSql` to spawn-quack-node.sh. Order: `initSql` FIRST
    * (PRAGMAs / SET / INSTALL before any federation ATTACH), blob SECOND, with a forced newline
    * between the two non-empty fragments (spawn-quack-node.sh echoes the value into a `duckdb`
    * pipe, which needs the statement terminator). Empty fragments are dropped. The tenant-db
    * initSql is NOT joined here: it must run before the quack extension is loaded, so it rides
    * [[ai.starlake.quack.model.NodeSpec.dbInitSql]] instead.
    */
  def joinInitAndBlob(initSql: String, federationBlob: String): String =
    val a = Option(initSql).getOrElse("").trim
    val b = Option(federationBlob).getOrElse("").trim
    (a, b) match
      case ("", "") => ""
      case (i, "")  => i
      case ("", f)  => f
      case (i, f)   => s"$i\n$f"

  /** Compose a node id safe as a Kubernetes pod + service name. `key.tenantDb` is the composed
    * Postgres name `${tenant}_${tenantDb}` and carries an underscore, valid in Postgres but illegal
    * in pod names (RFC 1123). The Postgres name is unchanged; this only sanitizes the node-id
    * surface.
    */
  def nodeId(key: ai.starlake.quack.model.PoolKey, index: Int): String =
    // RFC-1123 forbids '_'; slugs may contain it. Map '_' -> '-' on every component. Collision-free
    // since a valid slug never contains '-'. Labels keep the raw slug.
    val safeTenant = key.tenant.replace('_', '-')
    val safeDb     = key.tenantDb.replace('_', '-')
    val safePool   = key.pool.replace('_', '-')
    s"quack-$safeTenant-$safeDb-$safePool-$index"

  /** Replace the last segment of a dataPath with `newSegment`, to derive a per-tenant-db dataPath
    * alongside the root. URI-style paths (`<scheme>://...`) are string-handled so `Paths.get`
    * doesn't collapse the scheme's `//` to `/` (which DuckLake's `data_path` check then rejects on
    * re-ATTACH); filesystem paths fall through to NIO.
    *
    * Examples: ./ducklake/tpch + acme_tpch -> ./ducklake/acme_tpch; s3://qod-ducklake/tpch +
    * acme_tpch -> s3://qod-ducklake/acme_tpch.
    */
  private[ondemand] def replaceLastSegment(path: String, newSegment: String): String =
    // Strict URI scheme: a leading letter then letters/digits/+/-/. then `://`.
    val schemeRe = """^([a-zA-Z][a-zA-Z0-9+\-.]*://)(.*)$""".r
    path match
      case schemeRe(prefix, rest) =>
        val trimmed = rest.stripSuffix("/")
        val i       = trimmed.lastIndexOf('/')
        if i < 0 then prefix + newSegment
        else prefix + trimmed.substring(0, i) + "/" + newSegment
      case _ =>
        val p      = java.nio.file.Paths.get(path)
        val parent = p.getParent
        if parent == null then newSegment else parent.resolve(newSegment).toString
