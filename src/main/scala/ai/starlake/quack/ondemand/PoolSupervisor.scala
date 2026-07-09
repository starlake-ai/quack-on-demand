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
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  DbAdmin,
  NoopDbAdmin,
  PoolPermission,
  RbacGroup,
  RbacRole,
  RbacUser,
  RolePermission
}
import ai.starlake.quack.route.PoolSnapshot
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap

/** Patch applied to an existing tenant-db by [[PoolSupervisor.updateTenantDb]]. Absent fields are
  * unchanged; present fields replace their stored counterpart. For map fields the replace-and-carry
  * semantics in [[PoolSupervisor.mergeSecretKeys]] preserve response-redacted keys (pgPassword)
  * when the incoming map omits them. An empty string value for a scalar Option field clears it
  * (sets to None).
  */
final case class TenantDbPatch(
    metastore: Option[Map[String, String]] = None,
    objectStore: Option[Map[String, String]] = None,
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    initSql: Option[String] = None
)

/** Result of [[PoolSupervisor.updateTenantDb]]: the updated tenant-db row, the list of node ids
  * that were restarted, and per-node restart failures (nodeId, message). Failed restarts are
  * collected rather than thrown; the reconcile loop heals them.
  */
final case class TenantDbUpdateResult(
    td: TenantDb,
    restartedNodes: List[String],
    failedRestarts: List[(String, String)]
)

/** Owns the in-memory topology and mediates every mutation through [[ControlPlaneStore]]:
  *
  *   - `Tenant` ownership umbrella (`id`, `displayName`, `disabled`).
  *   - `TenantDb` one DuckLake / Postgres database under a tenant. Owns the
  *     `(metastore, dataPath, objectStore)` tuple. Pools and metastore live HERE.
  *   - `Pool` desired compute config under one tenant-db. Inherits the tenant-db's metastore +
  *     objectStore.
  *   - `RunningNode` runtime instance of a pool's compute.
  */
final class PoolSupervisor(
    backend: QuackBackend,
    tracker: NodeLoadTracker,
    store: ControlPlaneStore,
    defaultMetastore: Map[String, String] = Map.empty,
    dbAdmin: DbAdmin = NoopDbAdmin,
    federationBlobOf: String => IO[Option[String]] = _ => IO.pure(None),
    /** Callback fired immediately after a tenant-db row is removed (either via [[deleteTenantDb]]
      * or cascaded through [[deleteTenant]]). The default is a no-op; `Main` wires it to evict the
      * [[ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader]] cache so the per-tenant-db
      * Hikari pool releases its connections + heap on delete.
      */
    onTenantDbDeleted: (String, String) => Unit = (_, _) => (),
    /** Callback fired from [[updateTenantDb]] when the stored metastore changes (e.g. credential
      * rotation). `Main` wires it to the same evict function as [[onTenantDbDeleted]] so the stale
      * catalog reader is replaced by a fresh one that picks up the new credentials.
      */
    onTenantDbChanged: (String, String) => Unit = (_, _) => (),
    /** Cross-replica per-pool lock. In non-HA mode the
      * [[ai.starlake.quack.ondemand.ha.PoolLocker.noop]] default makes every wrap a pass-through,
      * so single-manager behavior is unchanged. Under HA a
      * [[ai.starlake.quack.ondemand.ha.PgPoolLocker]] serializes a pool's mutations against the
      * leader's reconcile pass so neither ever observes half-written node rows.
      */
    locks: ai.starlake.quack.ondemand.ha.PoolLocker = ai.starlake.quack.ondemand.ha.PoolLocker.noop,
    publish: ai.starlake.quack.ondemand.ha.StateChangePublisher =
      ai.starlake.quack.ondemand.ha.StateChangePublisher.noop
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

  /** In-memory mirror of the RBAC slice of the snapshot. The REST handlers and the FlightSQL
    * handshake gates read effective sets from here without re-joining qodstate_role /
    * qodstate_group on every call. Rebuilt from the store snapshot at `restore()` and updated
    * incrementally after each supervisor RBAC mutation.
    */
  val rbacResolver: RbacResolver = new RbacResolver()

  /** Short-TTL cache for `effectiveSetForUser`. Every FlightSQL handshake costs 3 store reads
    * (`getUserById` + `listDirectRolesForUser` + `listGroupsForUser`) + resolver joins; under any
    * real load the same (userId, JWT claims) tuple repeats every few seconds, so a 60s cache
    * collapses N handshakes' worth of work into 1. Invalidated wholesale on every RBAC mutation
    * (the graph cache that backs it is small + cheap to rebuild) and on `restore()`.
    *
    * Cache key bakes in the JWT fingerprint so a JWT role/group claim flip is reflected immediately
    * even within the TTL.
    */
  private final case class EffectiveCacheKey(userId: String, jwtRolesHash: Int, jwtGroupsHash: Int)
  private val EffectiveCacheTtl: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  /** Caffeine-backed cache of resolved EffectiveSets. Caffeine handles the TTL (`expireAfterWrite`)
    * and bounds memory (`maximumSize`) so distinct (userId, jwtRolesHash, jwtGroupsHash)
    * combinations accumulated over the manager's lifetime can't leak. The previous hand-rolled
    * `ConcurrentHashMap + expiresAt` form did neither.
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

  /** Drop every cached `EffectiveSet` and notify peer replicas. Called from every RBAC mutator so a
    * freshly-granted role or pool permission takes effect on the next handshake, not after a TTL
    * window.
    */
  private def invalidateEffectiveCache(): Unit =
    invalidateEffectiveCacheLocal()
    publish.rbacChanged()

  /** Broadcast both channels after a store mutation performed OUTSIDE the supervisor's own mutators
    * (e.g. manifest YAML import writes rows via ManifestImporter). restore() itself never
    * broadcasts, so external writers call this once after their restore().
    */
  def broadcastStateChanged(): Unit =
    publish.topologyChanged()
    publish.rbacChanged()

  // ---------- Bootstrap / replay ----------

  /** Per-tenant-db naming convention, applied ONLY to `kind=ducklake` because that's the kind where
    * each tenant-db IS its own Postgres database (named after `td.name`) with parquet stored
    * alongside it at `parent(defaultDataPath)/td.name`. Operators can override either by setting
    * `dbName`/`dataPath` in `td.metastore` or `td.dataPath` explicitly. Mirrors what the deleted
    * programmatic bootstrap did at create time and what the LOAD_TPC loader scripts write to.
    *
    * For `duckdb-file` and `memory` kinds the convention does not apply: those persistence layouts
    * are operator-defined and we fall through to the plain `defaultMetastore ++ td.metastore`
    * merge.
    */
  private def effectiveMetastoreFor(td: TenantDb): Map[String, String] =
    val merged = defaultMetastore ++ td.metastore
    if td.kind != TenantDbKind.DuckLake then merged
    else
      val withDb   = merged.updated("dbName", td.metastore.getOrElse("dbName", td.name))
      val rootData = defaultMetastore.getOrElse("dataPath", "")
      val tdData   =
        if td.dataPath.nonEmpty then td.dataPath
        else if td.metastore.contains("dataPath") then td.metastore("dataPath")
        else if rootData.isEmpty then ""
        else PoolSupervisor.replaceLastSegment(rootData, td.name)
      if tdData.nonEmpty then withDb.updated("dataPath", tdData) else withDb

  def restore(): Unit =
    val snap = store.snapshot()

    // Diff-aware rehydration: restore() is also driven by peer NOTIFY handlers,
    // so a row a peer DELETED must disappear from this replica's mirror, not just
    // get overwritten. Compute the snapshot key sets first, propagate deletions,
    // then upsert. Removing before putting avoids a window where a surviving
    // entry is briefly missing.
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
    pools.keys.toList.filterNot(snapPoolKeys).foreach(pools.remove)
    poolIdByKey.keys.toList.filterNot(snapPoolKeys).foreach(poolIdByKey.remove)

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
            dbInitSql = td.initSql,
            initSql = p.initSql,
            cpu = p.cpu,
            memory = p.memory,
            podTemplateYaml = p.podTemplateYaml
          )
        )
      }
    }
    // Hand the RBAC graph to the resolver in one shot. Subsequent
    // mutations are mirrored incrementally by the methods below.
    rbacResolver.replace(snap)
    // Seed operator quarantine flags so a restarted manager or an HA replica woken
    // by a qod_topology NOTIFY keeps refusing to route to quarantined nodes.
    val quarantinedIds = store.listQuarantinedNodeIds()
    pools.values.flatMap(_.nodes).foreach { n =>
      tracker.setQuarantined(n.nodeId, quarantinedIds.contains(n.nodeId))
    }
    // Local-only: restore() is called by peer-notification handlers; broadcasting here
    // would cause infinite echo across replicas.
    invalidateEffectiveCacheLocal()

  /** Initialize the DuckLake catalog for every `kind=ducklake` tenant-db. Runs in a single
    * controlled session per tenant-db so concurrent node ATTACHes do not race on the
    * `ducklake_metadata` CREATE TABLE in Postgres. Idempotent.
    *
    * Intended to run after [[restore]] (so the tenant-dbs cache is populated) and BEFORE
    * [[reconcile]] (so newly spawned nodes find a fully-initialized catalog). The YAML import path
    * persists tenant-dbs directly via `ManifestImporter` without per-row bootstrap, so a fresh boot
    * needs this dedicated init pass.
    */
  def ensureDuckLakeInitialized(): IO[Unit] = IO.blocking {
    tenantDbs.values.toList.foreach { td =>
      if td.kind == TenantDbKind.DuckLake then
        try DuckLakeInitializer.initBlocking(effectiveMetastoreFor(td))
        catch
          case t: Throwable =>
            logger.warn(
              s"ensureDuckLakeInitialized: '${td.name}' pre-init raised ${t.getClass.getSimpleName}: " +
                s"${t.getMessage}. First pool spawn will retry."
            )
    }
  }

  /** Re-check every persisted node; respawn dead ones. */
  def reconcile(): IO[Unit] = IO.defer {
    logger.info(
      s"reconcile: checking ${pools.size} pool(s), ${pools.values.map(_.nodes.size).sum} node(s)"
    )
    pools.toList.foldLeft(IO.unit) { case (acc, (key, state)) =>
      acc *> reconcilePool(key, state).void
    }
  }

  /** Run [[reconcile]] forever, sleeping `interval` between passes. The boot path runs reconcile
    * once; this keeps it running so a node that dies (or a pod evicted) while the manager is up is
    * respawned on the next tick instead of staying dead until the next restart. A tick that throws
    * is logged and swallowed so one bad pass doesn't kill the loop. Started as a cancelable fiber
    * by `Main`; cancellation is the normal shutdown exit. Drained pools (zero distribution) are
    * left alone -- reconcile only respawns when the persisted distribution is non-zero.
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
    // Re-read the pool's persisted node rows INSIDE the advisory lock so a second
    // lock holder acts on the first holder's committed writes, not on the pre-lock
    // PoolState that reconcile()'s fold captured. Deferred via IO.blocking so the
    // read runs when withLock's bracket executes this IO (i.e. after the lock is
    // acquired), not eagerly at IO-construction time. Fall back to the passed state
    // when the pool id or rows are missing (InMemory / no-row cases), preserving
    // today's behavior including the empty-nodes spawn-from-distribution path.
    IO.blocking {
      poolId(key) match
        case Some(pid) =>
          val rows = store.listNodes(pid)
          if rows.nonEmpty then state.copy(nodes = rows) else state
        case None => state
    }.flatMap(fresh => reconcilePoolUnlockedWith(key, fresh))

  private def respawnSpec(key: PoolKey, state: PoolState, n: RunningNode): NodeSpec =
    NodeSpec(
      poolKey = key,
      nodeId = n.nodeId,
      role = n.role,
      metastore = state.metastore,
      s3 = state.s3,
      maxConcurrent = n.maxConcurrent,
      kindWire = state.kindWire,
      extraSetupSql = PoolSupervisor.joinInitAndBlob(state.initSql, state.extraSetupSql),
      dbInitSql = state.dbInitSql,
      placement = placementForNodeId(key, n.nodeId),
      cpu = Option(state.cpu).filter(_.nonEmpty),
      memory = Option(state.memory).filter(_.nonEmpty),
      podTemplateYaml = Option(state.podTemplateYaml).filter(_.nonEmpty)
    )

  /** NodeSpec for an ephemeral Spec 09 maintenance node. Never registered in the Router or
    * NodeLoadTracker; the caller owns the full lifecycle (start -> chain -> stop). Borrows the
    * resolved config (metastore, s3, kindWire, init SQL) of any existing serving pool of the
    * tenant-db so the maintenance node ATTACHes the same catalog the same way; falls back to the
    * effective metastore with empty s3/federation when the tenant-db has no pool yet. The pool
    * key's pool segment is the reserved name `__maint` so node ids can never collide with a serving
    * pool's ids.
    */
  def maintenanceNodeSpec(tenantName: String, tenantDbName: String): Option[NodeSpec] =
    findTenantDb(tenantName, tenantDbName).map { td =>
      val key    = PoolKey(tenantName, td.name, "__maint")
      val nodeId = s"maint-${td.name}-${System.nanoTime()}"
      // Borrow a serving pool's resolved config when one exists (s3 creds, kindWire, initSql).
      val donor = pools.values.find(s => s.key.tenant == key.tenant && s.key.tenantDb == td.name)
      NodeSpec(
        poolKey = key,
        nodeId = nodeId,
        role = Role.Dual,
        metastore = donor.map(_.metastore).getOrElse(effectiveMetastoreFor(tenantName, td.name)),
        s3 = donor.map(_.s3).getOrElse(Map.empty),
        maxConcurrent = 1,
        kindWire = donor.map(_.kindWire).getOrElse("ducklake"),
        extraSetupSql = donor
          .map(s => PoolSupervisor.joinInitAndBlob(s.initSql, s.extraSetupSql))
          .getOrElse(""),
        dbInitSql = donor.map(_.dbInitSql).getOrElse("")
      )
    }

  private def reconcilePoolUnlockedWith(key: PoolKey, state: PoolState): IO[PoolState] =
    if state.nodes.isEmpty && state.distribution.total > 0 then
      // Pool persisted with zero running nodes. Happens after a fresh
      // YAML bootstrap (ManifestImporter writes the pool row but does
      // not spawn nodes the way createPool would). Spawn the full
      // distribution now using the same NodeSpec construction path
      // createPool uses.
      spawnFromDistribution(key, state)
    else
      state.nodes
        .foldLeft(IO.pure(List.empty[RunningNode])) { (acc, n) =>
          acc.flatMap { kept =>
            if isReachable(n) then backend.adopt(n).as(kept :+ n)
            else
              logger.warn(
                s"reconcile: $key/${n.nodeId} (pid=${n.pid.getOrElse("?")} port=${n.port}) " +
                  "is dead; respawning"
              )
              val wasQuarantined = tracker.snapshot(n.nodeId).quarantined
              IO.delay(tracker.remove(n.nodeId)) *>
                backend
                  .start(respawnSpec(key, state, n))
                  .flatMap { fresh =>
                    // Re-apply the pre-remove quarantine flag so an operator quarantine
                    // survives a node crash. restartNode intentionally clears it; only
                    // automatic reconcile respawn must preserve it.
                    val restore: IO[Unit] =
                      if wasQuarantined then IO.delay(tracker.setQuarantined(fresh.nodeId, true))
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
          if newNodes.zip(state.nodes).exists((a, b) => a ne b) then
            val updated = state.copy(nodes = newNodes)
            IO.delay { pools.put(key, updated); publish.topologyChanged() }.as(updated)
          else IO.pure(state)
        }

  /** Spawn the full distribution for a pool whose persisted state has no nodes yet. Mirrors
    * createPool's spawn block but operates on an existing PoolState rather than persisting a fresh
    * Pool entity. Cohort placement is recovered from the pool's authored cohorts (via poolRows)
    * when present; otherwise nodes spawn placement-less.
    */
  private def spawnFromDistribution(key: PoolKey, state: PoolState): IO[PoolState] =
    val poolEntity = poolIdByKey.get(key).flatMap(poolRows.get)
    val plan: List[(ai.starlake.quack.model.Role, NodePlacement)] = poolEntity match
      case Some(p) =>
        p.effectiveCohorts.flatMap(c => c.distribution.asRoleList.map(r => (r, c.placement)))
      case None =>
        state.distribution.asRoleList.map(r => (r, NodePlacement.empty))
    val nodeExtra = PoolSupervisor.joinInitAndBlob(state.initSql, state.extraSetupSql)
    val specs     = plan.zipWithIndex.map { case ((role, placement), i) =>
      NodeSpec(
        key,
        PoolSupervisor.nodeId(key, i + 1),
        role,
        state.metastore,
        state.s3,
        maxConcurrent = state.maxConcurrentPerNode,
        kindWire = state.kindWire,
        extraSetupSql = nodeExtra,
        dbInitSql = state.dbInitSql,
        placement = placement,
        cpu = Option(state.cpu).filter(_.nonEmpty),
        memory = Option(state.memory).filter(_.nonEmpty),
        podTemplateYaml = Option(state.podTemplateYaml).filter(_.nonEmpty)
      )
    }
    specs
      .foldLeft(IO.pure(List.empty[RunningNode])) { (acc, spec) =>
        acc.flatMap(rs => IO.delay(tracker.remove(spec.nodeId)) *> backend.start(spec).map(rs :+ _))
      }
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

  /** Find the cohort placement that owns the node at 1-based `index` in the pool's deterministic
    * spawn order. Returns [[NodePlacement.empty]] when the pool has no explicit cohorts or the
    * index is out of range. Used by reconcile to respawn a dead node onto the same K8s nodeSelector
    * as the original.
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

  /** True when the underlying [[QuackBackend]] can honor node placement hints (K8s nodeSelector /
    * tolerations). Exposed so the `/client-config` endpoint can flag the UI to hide cohort controls
    * in local mode.
    */
  def supportsPlacement: Boolean = backend.supportsPlacement

  def get(key: PoolKey): Option[PoolState] = pools.get(key)

  /** Surface the internal `qodstate_pool.id` for a (tenant, tenantDb, pool) triple so the RBAC
    * pool-grant UI can render a name-keyed select that submits the id the grant endpoint expects.
    * None until the pool has been hydrated by the supervisor.
    */
  def poolId(key: PoolKey): Option[String] = poolIdByKey.get(key)

  /** Surface the persisted [[Pool]] row by id so REST handlers can read fields the runtime
    * [[PoolState]] doesn't carry (today: the cohorts placement plan).
    */
  def poolEntity(id: String): Option[Pool]         = poolRows.get(id)
  def list(): List[PoolState]                      = pools.values.toList
  def snapshot(key: PoolKey): Option[PoolSnapshot] =
    pools.get(key).map(p => PoolSnapshot(p.key, p.nodes, tracker.snapshotAll))

  def listTenants(): List[Tenant]             = tenants.values.toList.sortBy(_.displayName)
  def getTenant(name: String): Option[Tenant] =
    val n = name.toLowerCase
    tenants.values.find(_.id == n)

  /** Lookup by surrogate id (`qodstate_tenant.id`, e.g. `t-02d0e86e`). The internal `tenants` map
    * is already keyed by id, so this is a direct hit.
    */
  def getTenantById(id: String): Option[Tenant] = tenants.get(id)

  def listPoolsOfTenant(name: String): List[String] =
    pools.values.filter(_.key.tenant == name.toLowerCase).map(_.key.pool).toList.sorted

  def listTenantDbsByTenant(tenantName: String): List[TenantDb] =
    getTenant(tenantName)
      .map(t => tenantDbs.values.filter(_.tenantId == t.id).toList.sortBy(_.name))
      .getOrElse(Nil)

  def findTenantDb(tenantName: String, tenantDbName: String): Option[TenantDb] =
    getTenant(tenantName).flatMap { t =>
      val nm = tenantDbName.toLowerCase
      tenantDbs.values.find(td => td.tenantId == t.id && td.name == nm)
    }

  /** Find the (tenant, tenantDb) pair whose DuckDB-side catalog name (the composed Postgres
    * `dbName` = `${tenant}_${tenantDb}`, lowercased) matches `catalog`. Used by the CLS rewriter's
    * column-catalog fetcher to route a DuckDB catalog reference back to its tenant-db.
    *
    * The composed name is what `Names.normalizeTenantDbName` produces and is persisted into
    * `TenantDb.name` (always lowercase via `Names.normalizeOrError`); matching against `td.name`
    * with `equalsIgnoreCase` keeps this in sync with the SAME formula `spawn-quack-node.sh` /
    * `effectiveMetastoreFor` use to spawn the node, with no risk of drift.
    *
    * Returns `None` if no matching tenant-db is registered.
    */
  def findTenantDbByCatalogName(catalog: String): Option[TenantDb] =
    if catalog == null || catalog.isEmpty then None
    else tenantDbs.values.find(_.name.equalsIgnoreCase(catalog))

  /** Resolve `(tenant, poolName) -> PoolKey` so the FlightSQL edge can route a connection that
    * addresses only `tenant` + `pool`. Pool names are unique within a tenant (enforced both by
    * `createPool` and the `qodstate_pool_tenant_name_unique` constraint), so at most one match
    * exists.
    */
  def findPoolKeyByTenantAndPoolName(tenant: String, poolName: String): Option[PoolKey] =
    val t = tenant.toLowerCase
    pools.keys.find(k => k.tenant == t && k.pool == poolName)

  /** Effective metastore for a tenant-db: global defaults overlaid with the tenant-db's own params,
    * then the per-tenant-db naming convention (dbName=td.name, dataPath alongside the root). Used
    * by the catalog browser.
    */
  def effectiveMetastoreFor(tenantName: String, tenantDbName: String): Map[String, String] =
    findTenantDb(tenantName, tenantDbName)
      .map(effectiveMetastoreFor)
      .getOrElse(defaultMetastore)

  // ---------- Tenant-of-resource lookups (RBAC scope check) ----------

  /** Tenant id owning a `qodstate_user` row. Outer `Option` distinguishes "user not found" from
    * "user is a superuser" (`Some(None)`).
    */
  def tenantForUser(userId: String): Option[Option[String]] =
    store.getUserById(userId).map(_.tenant)

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
    // The tenant id is the one key: a normalized lowercase slug. The display
    // name is a free-form label (falls back to the id when blank).
    Names.normalizeOrError(t.id, "tenant id") match
      case Left(err)                                      => Left(SupervisorError.InvalidName(err))
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
          // Every new tenant gets a built-in `admin` role with a
          // wildcard ALL permission, inserted in the same transaction as
          // the tenant row so a partial failure leaves no orphans. The
          // bootstrap admin (qodstate_user superuser) is wired to this
          // role by BootstrapAccessSeeder at boot time.
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
          Right(withId)
  }

  def setTenantDisabled(name: String, disabled: Boolean): IO[Either[SupervisorError, Tenant]] =
    IO.blocking {
      getTenant(name) match
        case None    => Left(SupervisorError.NotFound(s"tenant not found: $name"))
        case Some(t) =>
          val updated = t.copy(disabled = disabled)
          store.upsertTenant(updated)
          tenants.put(updated.id, updated)
          publish.topologyChanged()
          Right(updated)
    }

  /** Mutate the tenant's auth provider + provider-specific config. The existing users / roles /
    * groups are unchanged -- this is a config swap, not a wipe. Validation of the new config shape
    * (issuer URL, required keys per provider) lives in the REST handler so the supervisor stays
    * storage-only.
    */
  def setTenantAuth(
      name: String,
      authProvider: String,
      authConfig: Map[String, String]
  ): IO[Either[SupervisorError, Tenant]] = IO.blocking {
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

  def deleteTenant(name: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    getTenant(name) match
      case None    => Left(SupervisorError.NotFound(s"tenant not found: $name"))
      case Some(t) =>
        val tdbs    = tenantDbs.values.filter(_.tenantId == t.id).toList
        val poolsOf = tdbs.flatMap(td => poolRows.values.filter(_.tenantDbId == td.id))
        if poolsOf.nonEmpty then
          Left(
            SupervisorError.Conflict(
              s"tenant '$name' has ${poolsOf.size} active pool(s); stop them first"
            )
          )
        else
          tdbs.foreach { td =>
            store.deleteTenantDb(td.id); tenantDbs.remove(td.id)
            try onTenantDbDeleted(name.toLowerCase, td.name)
            catch case _: Throwable => ()
            dbAdmin.dropDatabase(td.name) match
              case Right(_)  => ()
              case Left(err) =>
                logger.warn(
                  s"deleteTenant: tenant-db row removed but DROP DATABASE \"${td.name}\" failed: $err"
                )
          }
          store.deleteTenant(t.id)
          tenants.remove(t.id)
          publish.topologyChanged()
          Right(())
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
      initSql: String = ""
  ): IO[Either[SupervisorError, TenantDb]] = IO.blocking {
    Names.normalizeTenantDbName(tenantName, suffix) match
      case Left(err)   => Left(SupervisorError.InvalidName(err))
      case Right(full) =>
        val tn = tenantName.toLowerCase
        getTenant(tn) match
          case None => Left(SupervisorError.NotFound(s"tenant not found: $tn"))
          case Some(t) if tenantDbs.values.exists(td => td.tenantId == t.id && td.name == full) =>
            Left(
              SupervisorError.AlreadyExists(s"tenant-db '$full' already exists in tenant '$tn'")
            )
          case Some(t) =>
            // Per-kind config preparation. DuckLake auto-injects dbName and
            // pre-provisions the Postgres database + DuckLake metadata tables.
            // DuckDbFile and InMemory skip both: a file-backed catalog needs no
            // Postgres tables, and an in-memory catalog has no persistence at
            // all.
            val effectiveMeta = kind match
              case TenantDbKind.DuckLake   => metastore.updated("dbName", full)
              case TenantDbKind.DuckDbFile => metastore
              case TenantDbKind.InMemory   => metastore

            val td = TenantDb(
              id = newId("td"),
              tenantId = t.id,
              name = full,
              kind = kind,
              metastore = effectiveMeta,
              dataPath = dataPath,
              objectStore = objectStore,
              defaultDatabase = defaultDatabase,
              defaultSchema = defaultSchema,
              initSql = initSql
            )

            TenantDb.validate(td) match
              case Some(msg) =>
                Left(SupervisorError.InvalidArgument(s"invalid kind=${kind.wireValue}: $msg"))
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
                        // Pre-init the ducklake_* metadata tables in the fresh
                        // tenant-db Postgres so the first batch of pool nodes
                        // doesn't race on `CREATE TABLE __ducklake_metadata`.
                        try
                          DuckLakeInitializer.initBlocking(
                            effectiveMeta.updated("dataPath", dataPath)
                          )
                        catch
                          case t: Throwable =>
                            logger.warn(
                              s"createTenantDb: DuckLake pre-init for '$full' failed; " +
                                s"first pool spawn will retry the ATTACH. Cause: ${t.getMessage}"
                            )
                        store.upsertTenantDb(td)
                        tenantDbs.put(td.id, td)
                        publish.topologyChanged()
                        Right(td)
                  case TenantDbKind.DuckDbFile | TenantDbKind.InMemory =>
                    store.upsertTenantDb(td)
                    tenantDbs.put(td.id, td)
                    publish.topologyChanged()
                    Right(td)
  }

  def deleteTenantDb(tenantName: String, tenantDbName: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      val tn = tenantName.toLowerCase
      getTenant(tn) match
        case None    => Left(SupervisorError.NotFound(s"tenant not found: $tn"))
        case Some(t) =>
          tenantDbs.values.find(td => td.tenantId == t.id && td.name == tenantDbName) match
            case None =>
              Left(SupervisorError.NotFound(s"tenant-db '$tenantDbName' not found in tenant '$tn'"))
            case Some(td) =>
              val activePools = poolRows.values.filter(_.tenantDbId == td.id).toList
              if activePools.nonEmpty then
                Left(
                  SupervisorError.Conflict(
                    s"tenant-db '$tenantDbName' has ${activePools.size} active pool(s); stop them first"
                  )
                )
              else
                store.deleteTenantDb(td.id)
                tenantDbs.remove(td.id)
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
                Right(())
    }

  /** Merge a patch into an existing tenant-db, persist it, refresh caches, and restart every node
    * of the database's pools when node-affecting fields (metastore, objectStore, initSql) changed.
    * Restart is all-at-once via the same per-node path restartNode uses; per-node failures are
    * collected, not thrown (reconcile heals). Response-redacted keys (pgPassword) are preserved
    * when an incoming map lacks them; an explicit empty value removes the key.
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
            val droppedRequired =
              (td.metastore.keySet & TenantDb.requiredMetastoreKeys(merged.kind)) --
                merged.metastore.keySet
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
              val refresh = IO.blocking {
                store.upsertTenantDb(merged)
                tenantDbs.put(merged.id, merged)
                // Unlocked read-modify-write: same trade-off as documented on the
                // former setTenantDbInitSql; self-healing via restore()/NOTIFY.
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
                          restartNode(key, nodeId).map {
                            case Right(()) => (ok :+ nodeId, failed)
                            case Left(msg) => (ok, failed :+ (nodeId -> msg.message))
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

  /** Replace-the-map semantics with one exception: keys redacted from API responses (pgPassword)
    * are carried over from the stored map when the incoming map lacks them, because no client can
    * round-trip a value it never sees. An incoming redacted key with an EMPTY value removes it.
    */
  private def mergeSecretKeys(
      stored: Map[String, String],
      incoming: Map[String, String]
  ): Map[String, String] =
    val secretKeys = stored.keys.filter(_.equalsIgnoreCase("pgPassword")).toList
    val carried    = secretKeys.collect {
      case k if !incoming.keys.exists(_.equalsIgnoreCase(k)) => k -> stored(k)
    }
    val explicit = incoming.filter { case (k, v) =>
      !(k.equalsIgnoreCase("pgPassword") && v.isEmpty)
    }
    explicit ++ carried

  // ---------- Pool API ----------

  /** Create a pool under an existing tenant-db. The tenant-db's metastore + objectStore are the
    * only source of storage config -- there is no per-pool override anymore. The caller must have
    * created `(key.tenant, key.tenantDb)` first via `createTenantDb`.
    */
  def createPool(
      key: PoolKey,
      dist: RoleDistribution,
      maxConcurrentPerNode: Int = 0,
      cohorts: List[PoolCohort] = Nil,
      // Persist with disabled=true so the edge refuses fresh handshakes
      // until the operator explicitly enables the pool. Nodes are still
      // spawned -- same semantics as calling setPoolDisabled(true) right
      // after a normal create.
      disabled: Boolean = false,
      // Free-form per-pool SQL prepended to the federation blob and shipped
      // to spawn-quack-node.sh via $extraSetupSql. PRAGMAs / SET / INSTALL /
      // LOAD live here; ATTACH aliases live on federation sources. Order
      // matters at node-init time: initSql runs FIRST so PRAGMAs are in
      // effect before any federation source's ATTACH.
      initSql: String = "",
      cpu: String = "",
      memory: String = "",
      podTemplateYaml: String = ""
  ): IO[List[RunningNode]] = locks.withLock(key)(IO.defer {
    val size = dist.total
    require(
      dist.writeonly >= 0 && dist.readonly >= 0 && dist.dual >= 0,
      s"role distribution must be non-negative: $dist"
    )
    require(size > 0, s"role distribution must sum to at least 1: $dist")
    require(dist.isValidFor(size), s"role distribution does not sum to $size")
    // Cohorts are always persisted as authored so a pool defined on
    // local can be exported and replayed on K8s with placement intact.
    // The K8s backend reads `NodeSpec.placement`; local backends ignore
    // it -- the UI already shows a warning before the operator submits.
    if cohorts.nonEmpty && !backend.supportsPlacement then
      logger.info(
        s"backend ${backend.getClass.getSimpleName} does not honor node placement; " +
          s"persisting ${cohorts.size} cohort(s) for pool $key but they will be " +
          "ignored at runtime"
      )
    // When cohorts are provided, the per-cohort distributions must sum
    // back to `dist`. Reject mismatches up-front so the persisted
    // `qodstate_pool` row never disagrees with the spawned nodes.
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
      require(summed == dist, s"cohort distributions sum to $summed but pool distribution is $dist")

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
        // Pool names are unique within a tenant (see qodstate_pool
        // UNIQUE (tenant_id, name)). The FlightSQL edge resolves
        // (tenant, pool) -> PoolKey at handshake time, so allowing the
        // same pool name in two tenant-dbs under one tenant would make
        // that lookup ambiguous.
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
          // initSql runs first so PRAGMAs/INSTALL land before the federation
          // blob's ATTACHes; both get shipped via NodeSpec.extraSetupSql.
          val fedBlob = blobOpt.getOrElse("")
          // What the node actually gets at spawn = initSql + "\n" + federation blob.
          // `state.extraSetupSql` keeps the federation blob ONLY so a manager
          // restart that re-projects PoolState from persisted rows still has the
          // operator-authored initSql available separately (and respawn can
          // re-concatenate without double-prepending).
          val nodeExtra  = PoolSupervisor.joinInitAndBlob(initSql, fedBlob)
          val poolEntity = Pool(
            id = newId("p"),
            tenantId = td.tenantId,
            tenantDbId = td.id,
            name = key.pool,
            size = size,
            distribution = dist,
            maxConcurrentPerNode = maxConcurrentPerNode,
            disabled = disabled,
            cohorts = cohorts,
            initSql = initSql,
            cpu = cpu,
            memory = memory,
            podTemplateYaml = podTemplateYaml
          )
          IO.blocking(store.upsertPool(poolEntity)) *> IO.delay {
            poolRows.put(poolEntity.id, poolEntity)
            poolIdByKey.put(key, poolEntity.id)
          } *> {
            // Walk cohorts in order; each role gets the cohort's placement.
            // Empty `cohorts` falls back to a single placement-less cohort
            // carrying `dist`, matching pre-cohort behavior exactly.
            val plan: List[(ai.starlake.quack.model.Role, NodePlacement)] =
              poolEntity.effectiveCohorts.flatMap { c =>
                c.distribution.asRoleList.map(role => (role, c.placement))
              }
            val specs = plan.zipWithIndex.map { case ((role, placement), i) =>
              NodeSpec(
                key,
                PoolSupervisor.nodeId(key, i + 1),
                role,
                merged,
                td.objectStore,
                maxConcurrent = maxConcurrentPerNode,
                kindWire = kindWire,
                extraSetupSql = nodeExtra,
                dbInitSql = td.initSql,
                placement = placement,
                cpu = Option(cpu).filter(_.nonEmpty),
                memory = Option(memory).filter(_.nonEmpty),
                podTemplateYaml = Option(podTemplateYaml).filter(_.nonEmpty)
              )
            }
            specs
              .foldLeft(IO.pure(List.empty[RunningNode])) { (acc, spec) =>
                acc.flatMap(rs =>
                  IO.delay(tracker.remove(spec.nodeId)) *> backend.start(spec).map(rs :+ _)
                )
              }
              .flatMap { running =>
                val state = PoolState(
                  key,
                  running,
                  dist,
                  merged,
                  td.objectStore,
                  maxConcurrentPerNode,
                  disabled = disabled,
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
                pools.put(key, state)
                running
                  .foldLeft(IO.unit)((acc, n) =>
                    acc *> IO.blocking(store.upsertNode(n, poolEntity.id))
                  )
                  .map(_ => publish.topologyChanged())
                  .as(running)
              }
          }
        }
  })

  def setPoolDisabled(key: PoolKey, disabled: Boolean): IO[Either[SupervisorError, Pool]] =
    IO.blocking {
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

  def setPoolResources(
      key: PoolKey,
      cpu: String,
      memory: String
  ): IO[Either[SupervisorError, Pool]] =
    IO.blocking {
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

  def setPoolTemplate(key: PoolKey, yaml: String): IO[Either[SupervisorError, Pool]] =
    IO.blocking {
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

  def setMaxConcurrent(key: PoolKey, nodeId: String, max: Int): IO[Option[RunningNode]] =
    pools.get(key).flatMap(s => s.nodes.find(_.nodeId == nodeId)) match
      case None    => IO.pure(None)
      case Some(n) =>
        val u        = n.copy(maxConcurrent = max)
        val state    = pools(key)
        val newNodes = state.nodes.map(x => if x.nodeId == nodeId then u else x)
        pools.put(key, state.copy(nodes = newNodes))
        poolIdByKey.get(key) match
          case Some(pid) =>
            IO.blocking(store.upsertNode(u, pid)).map { _ => publish.topologyChanged(); Some(u) }
          case None => IO.delay { publish.topologyChanged(); Some(u) }

  def scale(
      key: PoolKey,
      targetSize: Int,
      newDist: RoleDistribution,
      force: Boolean
  ): IO[List[RunningNode]] =
    require(newDist.isValidFor(targetSize), "role distribution does not sum to targetSize")
    locks.withLock(key) {
      scaleUnlocked(key, targetSize, newDist, force)
    }

  private def scaleUnlocked(
      key: PoolKey,
      targetSize: Int,
      newDist: RoleDistribution,
      force: Boolean
  ): IO[List[RunningNode]] =
    pools.get(key) match
      case None        => IO.raiseError(new NoSuchElementException(s"pool not found: $key"))
      case Some(state) =>
        val poolId = poolIdByKey.getOrElse(key, "")

        // Reconcile per role against the ACTUAL roles of the running nodes,
        // never a positional slice of `newDist.asRoleList`. The old
        // `asRoleList.drop(state.size).take(toAdd)` assumed the existing nodes
        // filled the first `state.size` entries of the new role list; because
        // that list is ordered [WriteOnly, ReadOnly, Dual], adding one ReadOnly
        // to a Dual-only pool dropped past the new ReadOnly and spawned a Dual.
        // Diffing per role also lets one operation both add and remove (e.g.
        // swapping a ReadOnly for a WriteOnly at constant size). Counts are read
        // by name via `newDist.countFor`, never by slicing `asRoleList`.

        // Surplus nodes of each over-provisioned role (newest first), and the
        // deficit roles to spawn for each under-provisioned role.
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
          val nodeExtra = PoolSupervisor.joinInitAndBlob(state.initSql, state.extraSetupSql)
          // Fresh ids start above the current high-water mark so they never
          // collide with survivors during a mixed add/remove.
          val baseIndex = state.size
          val specs     = rolesToAdd.zipWithIndex.map { case (role, i) =>
            NodeSpec(
              key,
              PoolSupervisor.nodeId(key, baseIndex + i + 1),
              role,
              state.metastore,
              state.s3,
              maxConcurrent = state.maxConcurrentPerNode,
              kindWire = state.kindWire,
              extraSetupSql = nodeExtra,
              dbInitSql = state.dbInitSql,
              cpu = Option(state.cpu).filter(_.nonEmpty),
              memory = Option(state.memory).filter(_.nonEmpty),
              podTemplateYaml = Option(state.podTemplateYaml).filter(_.nonEmpty)
            )
          }
          val survivors = state.nodes.filterNot(n => toRemove.exists(_.nodeId == n.nodeId))

          val stopRemoved =
            if force then toRemove.foldLeft(IO.unit)((acc, n) => acc *> backend.stop(n.nodeId))
            else
              toRemove.foldLeft(IO.unit) { (acc, n) =>
                acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *> drainAndStop(n)
              }
          val deleteRemoved =
            // Drop both the store row and the tracker entry now that the node is
            // stopped. setDraining (force=false above) created the entry; without
            // this remove it lingers in snapshotAll with draining=true forever.
            toRemove.foldLeft(IO.unit) { (acc, n) =>
              acc *> IO.blocking(store.deleteNode(n.nodeId)) *> IO.delay(tracker.remove(n.nodeId))
            }

          stopRemoved *> deleteRemoved *>
            specs
              .foldLeft(IO.pure(survivors)) { (acc, spec) =>
                // Reset any stale tracker entry before (re)starting: scaling down
                // with force=false sets draining=true and deletes the node row but
                // leaves the tracker entry behind, and scaling back up reuses the
                // freed node id. Without this remove the fresh node inherits the old
                // draining=true flag (mirrors createPool / reconcile spawn paths).
                acc.flatMap(rs =>
                  IO.delay(tracker.remove(spec.nodeId)) *> backend.start(spec).map(rs :+ _)
                )
              }
              .flatMap { combined =>
                pools.put(key, state.copy(nodes = combined, distribution = newDist))
                updatePoolEntityDist(key, newDist, combined.size)
                val added = combined.drop(survivors.size)
                (if poolId.nonEmpty then
                   added
                     .foldLeft(IO.unit)((acc, n) => acc *> IO.blocking(store.upsertNode(n, poolId)))
                 else IO.unit).map { _ => publish.topologyChanged(); combined }
              }

  /** Stop every node of the pool but KEEP the pool itself registered. The pool row survives in the
    * control plane and the in-memory state stays with an empty node list and a zero distribution,
    * so the pool is effectively scaled to 0 and stays drained across a manager restart (reconcile
    * only respawns when the persisted distribution is non-zero). Use [[deletePool]] to remove the
    * pool entirely. `force=true` kills nodes immediately; `force=false` drains them (stop accepting
    * new queries, then shut down).
    */
  def stopPool(key: PoolKey, force: Boolean): IO[Unit] =
    pools.get(key) match
      case None    => IO.unit
      case Some(_) => scale(key, 0, RoleDistribution(0, 0, 0), force).void

  /** Remove the pool entirely: stop every node, then delete the pool and its node rows from the
    * control plane and forget it in memory. This is the only path that deletes a pool; [[stopPool]]
    * merely scales it to 0.
    */
  def deletePool(key: PoolKey, force: Boolean): IO[Unit] =
    locks.withLock(key) {
      deletePoolUnlocked(key, force)
    }

  private def deletePoolUnlocked(key: PoolKey, force: Boolean): IO[Unit] =
    pools.get(key) match
      case None        => IO.unit
      case Some(state) =>
        val stopAll =
          if force then state.nodes.foldLeft(IO.unit)((acc, n) => acc *> backend.stop(n.nodeId))
          else
            state.nodes.foldLeft(IO.unit) { (acc, n) =>
              acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *> drainAndStop(n)
            }
        stopAll *>
          state.nodes.foldLeft(IO.unit)((acc, n) =>
            // Store row and tracker entry both go now that the node is stopped,
            // so a drained-then-deleted node leaves nothing behind in snapshotAll.
            acc *> IO.blocking(store.deleteNode(n.nodeId)) *> IO.delay(tracker.remove(n.nodeId))
          ) *>
          IO.blocking {
            poolIdByKey.get(key).foreach { pid =>
              store.deletePool(pid)
              poolRows.remove(pid)
            }
            pools.remove(key)
            poolIdByKey.remove(key)
            publish.topologyChanged()
          }

  private def drainAndStop(n: RunningNode): IO[Unit] = backend.stop(n.nodeId)

  /** Operator-initiated restart of a single node: stop it (all its in-flight statements fail to
    * their clients), respawn through the same NodeSpec path reconcile uses, clear any quarantine so
    * the fresh node is routable, and broadcast the topology change. Left(message) when pool or node
    * is unknown.
    */
  def restartNode(key: PoolKey, nodeId: String): IO[Either[SupervisorError, Unit]] =
    locks.withLock(key) {
      IO.delay(pools.get(key)).flatMap {
        case None        => IO.pure(Left(SupervisorError.NotFound(s"pool $key not found")))
        case Some(state) =>
          state.nodes.find(_.nodeId == nodeId) match
            case None =>
              IO.pure(Left(SupervisorError.NotFound(s"node $nodeId not found in $key")))
            case Some(n) =>
              for
                _     <- backend.stop(n.nodeId)
                _     <- IO.delay(tracker.remove(n.nodeId))
                fresh <- backend.start(respawnSpec(key, state, n))
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

  // ---------- RBAC: users ----------

  /** Persist a new (tenant, username) principal, including its bcrypt password hash, and register
    * the row with the resolver so role grants can FK to it. Returns the persisted [[RbacUser]] (id
    * assigned). `tenant = None` creates a superuser.
    */
  def createUser(
      tenant: Option[String],
      username: String,
      password: String,
      role: String = "user",
      userStore: ai.starlake.quack.ondemand.state.UserStore
  ): IO[Either[SupervisorError, RbacUser]] = IO.blocking {
    if username.isEmpty || password.isEmpty then
      Left(SupervisorError.InvalidArgument("username and password are required"))
    else
      tenant match
        case Some(t) if t.isEmpty =>
          Left(SupervisorError.InvalidArgument("tenant must be non-empty (use None for superuser)"))
        case Some(t) if !tenants.values.exists(x => x.id == t || x.displayName == t.toLowerCase) =>
          Left(SupervisorError.NotFound(s"tenant not found: $t"))
        case _ =>
          val resolvedTenantId = tenant.flatMap { t =>
            tenants.values.find(x => x.id == t || x.displayName == t.toLowerCase).map(_.id)
          }
          val out = userStore.upsertUser(resolvedTenantId, username, password, role)
          val u   = RbacUser(out.id, resolvedTenantId, username, role)
          store.upsertUserIdentity(u)
          Right(u)
  }

  def updateUserPassword(
      userId: String,
      password: Option[String],
      role: Option[String],
      userStore: ai.starlake.quack.ondemand.state.UserStore
  ): IO[Either[SupervisorError, RbacUser]] = IO.blocking {
    store.getUserById(userId) match
      case None    => Left(SupervisorError.NotFound(s"user not found: $userId"))
      case Some(u) =>
        val newRole = role.getOrElse(u.role)
        password.foreach(pw => userStore.upsertUser(u.tenant, u.username, pw, newRole))
        val updated = u.copy(role = newRole)
        store.upsertUserIdentity(updated)
        invalidateEffectiveCache()
        Right(updated)
  }

  def deleteUser(userId: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    store.getUserById(userId) match
      case None    => Left(SupervisorError.NotFound(s"user not found: $userId"))
      case Some(_) =>
        // ON DELETE CASCADE in qodstate_user_role / user_group /
        // pool_permission cleans up the user's edges automatically.
        store.deleteUser(userId)
        invalidateEffectiveCache()
        Right(())
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

  def deleteRole(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    rbacResolver.role(id) match
      case None    => Left(SupervisorError.NotFound(s"role not found: $id"))
      case Some(_) =>
        store.deleteRole(id)
        rbacResolver.removeRole(id)
        invalidateEffectiveCache()
        Right(())
  }

  def listRoles(tenantId: String): List[RbacRole] = store.listRoles(tenantId)

  def grantRolePermission(
      roleId: String,
      catalog: String,
      schema: String,
      table: String,
      verb: String
  ): IO[Either[SupervisorError, RolePermission]] = IO.blocking {
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

  def revokeRolePermission(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    if store.deleteRolePermission(id) then
      rbacResolver.removeRolePermission(id)
      invalidateEffectiveCache()
      Right(())
    else Left(SupervisorError.NotFound(s"role permission not found: $id"))
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
            .toRight(SupervisorError.InvalidArgument("transformSql is required when action='mask'"))
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

  def updateColumnPolicy(
      id: String,
      action: String,
      transformSql: Option[String]
  ): IO[Either[SupervisorError, Unit]] = IO.blocking {
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
            .toRight(SupervisorError.InvalidArgument("transformSql is required when action='mask'"))
            .flatMap { raw =>
              // updateColumnPolicy does not know the columnName; fetch it from the store to validate.
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

  def deleteColumnPolicy(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    if store.deleteColumnPolicy(id) then { invalidateEffectiveCache(); Right(()) }
    else Left(SupervisorError.NotFound(s"column policy $id not found"))
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

  def updateRowPolicy(id: String, predicateSql: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      ai.starlake.quack.edge.rls.RowPredicateValidator.validate(predicateSql) match
        case ai.starlake.quack.edge.rls.RowPredicateValidator.Invalid(reason) =>
          Left(SupervisorError.InvalidArgument(s"invalid predicateSql: $reason"))
        case ai.starlake.quack.edge.rls.RowPredicateValidator.Valid(canon) =>
          val ok = store.updateRowPolicy(id, canon)
          if ok then { invalidateEffectiveCache(); Right(()) }
          else Left(SupervisorError.NotFound(s"row policy $id not found"))
    }

  def deleteRowPolicy(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    if store.deleteRowPolicy(id) then { invalidateEffectiveCache(); Right(()) }
    else Left(SupervisorError.NotFound(s"row policy $id not found"))
  }

  def listRowPoliciesByRole(roleId: String): IO[List[state.RoleRowPolicy]] =
    IO.blocking(store.listRowPolicies(roleId))

  // ---------- RBAC: groups ----------

  def createGroup(
      tenantId: String,
      name: String,
      description: Option[String] = None
  ): IO[Either[SupervisorError, RbacGroup]] = IO.blocking {
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

  def deleteGroup(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    rbacResolver.group(id) match
      case None    => Left(SupervisorError.NotFound(s"group not found: $id"))
      case Some(_) =>
        store.deleteGroup(id)
        rbacResolver.removeGroup(id)
        invalidateEffectiveCache()
        Right(())
  }

  def listGroups(tenantId: String): List[RbacGroup] = store.listGroups(tenantId)

  def listRolesForGroup(groupId: String): List[RbacRole] =
    rbacResolver.rolesForGroup(groupId).toList.flatMap(rbacResolver.role).sortBy(_.name)

  // ---------- RBAC: memberships ----------

  def addUserRole(userId: String, roleId: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    membershipCheck(userId, roleId, rbacResolver.role(_), _.tenantId, "role") match
      case Some(err) => Left(err)
      case None      =>
        store.addUserRole(userId, roleId)
        invalidateEffectiveCache()
        Right(())
  }

  def removeUserRole(userId: String, roleId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      store.removeUserRole(userId, roleId)
      invalidateEffectiveCache()
      Right(())
    }

  def addUserGroup(userId: String, groupId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      membershipCheck(userId, groupId, rbacResolver.group(_), _.tenantId, "group") match
        case Some(err) => Left(err)
        case None      =>
          store.addUserGroup(userId, groupId)
          invalidateEffectiveCache()
          Right(())
    }

  def removeUserGroup(userId: String, groupId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      store.removeUserGroup(userId, groupId)
      invalidateEffectiveCache()
      Right(())
    }

  def addGroupRole(groupId: String, roleId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
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

  def removeGroupRole(groupId: String, roleId: String): IO[Either[SupervisorError, Unit]] =
    IO.blocking {
      store.removeGroupRole(groupId, roleId)
      rbacResolver.removeGroupRoleEdge(groupId, roleId)
      invalidateEffectiveCache()
      Right(())
    }

  /** Validate a user->role or user->group membership edge before it is written.
    *
    * Beyond the existence checks, this enforces TENANT ALIGNMENT: the edge is only allowed when the
    * user and the referenced role/group belong to the same tenant. A role/group always carries a
    * non-null `tenantId`; a user carries `tenant: Option[String]` where `None` marks a superuser.
    * We require exact equality of `user.tenant` and `Some(otherTenant)`, so:
    *   - same tenant -> allowed;
    *   - tenant-A user + tenant-B role/group -> rejected (the escalation this closes);
    *   - superuser (tenant=None) + any tenant-scoped role/group -> rejected. A superuser already
    *     bypasses the pool/ACL gates via the resolver, so it must not additionally accrue a
    *     tenant-scoped role, mirroring the same stance taken in `grantPoolPermission`.
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
    // Walk through the predicates in order and short-circuit on the
    // first failure. Tenant-scoping invariant (principal belongs to
    // the target tenant) is checked at the end since it requires the
    // principal lookup to have succeeded.
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
            // Principal must belong to the same tenant the grant covers.
            // A superuser (tenant=None) cannot receive a tenant-scoped
            // pool grant; the resolver's bypass rule already gives them
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

  def revokePoolPermission(id: String): IO[Either[SupervisorError, Unit]] = IO.blocking {
    if store.deletePoolPermission(id) then
      rbacResolver.removePoolPermission(id)
      invalidateEffectiveCache()
      Right(())
    else Left(SupervisorError.NotFound(s"pool permission not found: $id"))
  }

  def listPoolPermissions(
      tenantId: Option[String] = None,
      userId: Option[String] = None,
      groupId: Option[String] = None
  ): List[PoolPermission] = store.listPoolPermissions(tenantId, userId, groupId)

  // ---------- RBAC: handshake authorization ----------

  /** End-to-end FlightSQL handshake gate. Runs (in order):
    *   1. resolve `(tenant, pool) -> PoolKey` + tenant/pool kill switches
    *   2. lookup the user via [[ControlPlaneStore.findUserForLogin]], then reject when the row
    *      carries `enabled = false` (operator kill switch on the single user, mirroring the tenant
    *      and pool switches of gate 1) -- this query also enforces the tenant scope: it returns
    *      rows where `tenant IS NULL` (superuser) OR `tenant = <tenantId>`. Any user returned here
    *      is therefore already scoped correctly, so we do NOT re-check
    *      `user.tenant == tenantRow.id` at the application layer. If
    *      [[ControlPlaneStore.findUserForLogin]] is ever refactored to drop the tenant filter (e.g.
    *      moving to a global-username model), reinstate the scope check between gates 2 and 3.
    *   3. compute the effective set (groups, roles, permissions, pool grants)
    *   4. pool-access check (skipped for superusers): the effective pool grants must cover the
    *      addressed pool (pool_id NULL = "every pool in this tenant")
    *
    * Returns a populated [[ai.starlake.quack.ondemand.rbac.AuthorizedHandshake]] on success; a
    * left-string explains which gate failed, with enough context for the FlightSQL caller to
    * diagnose the rejection.
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
                // 2. User lookup. The query is tenant-scoped at the SQL layer
                //    (see scaladoc above) -- any user returned is admissible.
                store.findUserForLogin(tenantRow.id, username) match
                  case None =>
                    Left(s"user '$username' is not registered in tenant '${key.tenant}'")
                  case Some(user) if !user.enabled =>
                    Left(s"user '$username' is disabled")
                  case Some(user) =>
                    // 3. Effective set. Superusers get an empty set --
                    //    the per-statement validator bypasses them.
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

  /** Compute the closure of `(roles, groups, permissions, pool grants)` for one user. Combines the
    * user's direct edges (fetched from Postgres) with the schema-bounded cache in [[rbacResolver]]
    * AND with any JWT-claimed role / group names -- those are resolved against `qodstate_role.name`
    * / `qodstate_group.name` in the user's tenant and union-merged before the closure is computed.
    *
    * `jwtRoles` / `jwtGroups` are pass-through name sets: empty means "no JWT claims" (Basic auth
    * path or no `roles`/`groups` in the token). Names unknown to the manager are silently dropped,
    * so a JWT claiming `[admin, foo, bar]` against a tenant with only an `admin` role grants
    * exactly the admin role and nothing else.
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
      // JWT-claim resolution. Only tenant-scoped users carry a tenant
      // id; superusers (u.tenant.isEmpty) bypass per-statement
      // validation entirely upstream, so the union here is a no-op.
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

  /** Bulk version: one Postgres round-trip per dependency (direct roles, groups, pool grants)
    * instead of N+1. Returns a map keyed by user id; users with no edges are still present in the
    * map with empty lists. Used by the `/user/list` REST surface.
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
      // Scale changes the role distribution out from under any explicit
      // cohort plan, so clear cohorts; the recreate-with-cohorts path
      // is the supported way to change placement after the fact.
      val updated = p.copy(size = size, distribution = dist, cohorts = Nil)
      store.upsertPool(updated)
      poolRows.put(updated.id, updated)
    }

object PoolSupervisor:
  val AdminRoleName: String = "admin"

  /** Concatenate the per-pool [[ai.starlake.quack.ondemand.PoolState.initSql]] with the resolved
    * federation blob for shipment as a single `extraSetupSql` to spawn-quack-node.sh.
    *
    * Order: `initSql` FIRST (so PRAGMAs / SET / INSTALL land before any federation ATTACH that
    * depends on them), federation blob SECOND. A trailing newline is forced between the two
    * non-empty fragments because spawn-quack-node.sh echoes the value verbatim into a `duckdb`
    * pipe, and DuckDB needs the statement terminator to parse them as separate statements. Empty
    * fragments are dropped so the resulting string contains no leading / trailing blank lines
    * (cleaner UI display + simpler tests).
    *
    * The tenant-db initSql is NOT part of this join: it must run BEFORE the quack extension is
    * installed/loaded (and after the proxy settings), so it rides
    * [[ai.starlake.quack.model.NodeSpec.dbInitSql]] and its own `dbInitSql` env var instead.
    */
  def joinInitAndBlob(initSql: String, federationBlob: String): String =
    val a = Option(initSql).getOrElse("").trim
    val b = Option(federationBlob).getOrElse("").trim
    (a, b) match
      case ("", "") => ""
      case (i, "")  => i
      case ("", f)  => f
      case (i, f)   => s"$i\n$f"

  /** Compose a node id that is safe as a Kubernetes pod + service name.
    *
    * `key.tenantDb` is the normalized composed Postgres database name `${tenant}_${tenantDb}` and
    * carries an underscore as separator, which is valid in Postgres identifiers but illegal in pod
    * names (RFC 1123 subdomain: lowercase alphanumeric + `-` + `.` only). The Postgres name stays
    * unchanged; this helper only sanitizes for the node-id / pod-name surface.
    */
  def nodeId(key: ai.starlake.quack.model.PoolKey, index: Int): String =
    // RFC-1123 resource names forbid '_'; slugs may contain it. Map '_' -> '-'
    // on every component (tenant / db / pool). Collision-free since a valid slug
    // never contains '-'. Labels keep the raw slug (they permit '_').
    val safeTenant = key.tenant.replace('_', '-')
    val safeDb     = key.tenantDb.replace('_', '-')
    val safePool   = key.pool.replace('_', '-')
    s"quack-$safeTenant-$safeDb-$safePool-$index"

  /** Replace the last segment of a dataPath with `newSegment`, used to derive a per-tenant-db
    * dataPath alongside the configured root. URI-style paths (`<scheme>://...`) are handled with
    * string operations so we don't let `java.nio.file.Paths.get` collapse the scheme's `//` to `/`
    * (which DuckLake's `__ducklake_metadata.data_path` check then rejects on re-ATTACH). Filesystem
    * paths fall through to NIO so portability behavior is unchanged.
    *
    * Examples (root + newSegment -> result): ./ducklake/tpch + acme_tpch -> ./ducklake/acme_tpch
    * /var/data/tpch + acme_tpch -> /var/data/acme_tpch s3://qod-ducklake/tpch + acme_tpch ->
    * s3://qod-ducklake/acme_tpch gs://bucket/tpch + acme_tpch -> gs://bucket/acme_tpch
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
