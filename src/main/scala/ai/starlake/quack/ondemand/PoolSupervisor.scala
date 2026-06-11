package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  Names,
  NodePlacement,
  NodeSpec,
  Pool,
  PoolCohort,
  PoolKey,
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

import java.util.UUID
import scala.collection.concurrent.TrieMap

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
    federationBlobOf: String => IO[Option[String]] = _ => IO.pure(None)
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

  /** In-memory mirror of the RBAC slice of the snapshot. Phase B's REST handlers and (Phase C) the
    * FlightSQL handshake gates read effective sets from here without re-joining qodstate_role /
    * qodstate_group on every call. Replaced from the store snapshot at `restore()` and updated
    * incrementally after each supervisor RBAC mutation.
    */
  val rbacResolver: RbacResolver = new RbacResolver()

  // ---------- Bootstrap / replay ----------

  /** Per-tenant-db naming convention, applied ONLY to `kind=ducklake` because that's the kind
    * where each tenant-db IS its own Postgres database (named after `td.name`) with parquet stored
    * alongside it at `parent(defaultDataPath)/td.name`. Operators can override either by setting
    * `dbName`/`dataPath` in `td.metastore` or `td.dataPath` explicitly. Mirrors what the deleted
    * programmatic bootstrap did at create time and what the LOAD_TPC loader scripts write to.
    *
    * For `duckdb-file` and `memory` kinds the convention does not apply: those persistence layouts
    * are operator-defined and we fall through to the plain `defaultMetastore ++ td.metastore` merge.
    */
  private def effectiveMetastoreFor(td: TenantDb): Map[String, String] =
    val merged = defaultMetastore ++ td.metastore
    if td.kind != TenantDbKind.DuckLake then merged
    else
      val withDb   = merged.updated("dbName", td.metastore.getOrElse("dbName", td.name))
      val rootData = defaultMetastore.getOrElse("dataPath", "")
      val tdData =
        if td.dataPath.nonEmpty then td.dataPath
        else if td.metastore.contains("dataPath") then td.metastore("dataPath")
        else if rootData.isEmpty then ""
        else
          val p      = java.nio.file.Paths.get(rootData)
          val parent = p.getParent
          if parent == null then td.name else parent.resolve(td.name).toString
      if tdData.nonEmpty then withDb.updated("dataPath", tdData) else withDb

  def restore(): Unit =
    val snap = store.snapshot()
    snap.tenants.foreach(t => tenants.put(t.id, t))
    snap.tenantDbs.foreach(td => tenantDbs.put(td.id, td))
    snap.pools.foreach(p => poolRows.put(p.id, p))
    snap.pools.foreach { p =>
      val opt = for
        td <- tenantDbs.get(p.tenantDbId)
        t  <- tenants.get(td.tenantId)
      yield (td, t)
      opt.foreach { case (td, t) =>
        val key = PoolKey(t.displayName, td.name, p.name)
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
            disabled = p.disabled
          )
        )
      }
    }
    // Hand the RBAC graph to the resolver in one shot. Subsequent
    // mutations are mirrored incrementally by the methods below.
    rbacResolver.replace(snap)

  /** Initialize the DuckLake catalog for every `kind=ducklake` tenant-db. Runs in a single
    * controlled session per tenant-db so concurrent node ATTACHes do not race on the
    * `ducklake_metadata` CREATE TABLE in Postgres. Idempotent.
    *
    * Intended to run after [[restore]] (so the tenant-dbs cache is populated) and BEFORE
    * [[reconcile]] (so newly spawned nodes find a fully-initialized catalog). The deleted
    * programmatic bootstrap used to call this from `createTenantDb`; the YAML import path
    * persists tenant-dbs directly via `ManifestImporter` and skips that hook, so a fresh
    * boot needs a dedicated init pass.
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

  private def reconcilePool(key: PoolKey, state: PoolState): IO[PoolState] =
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
            IO.delay(tracker.remove(n.nodeId)) *>
              backend
                .start(
                  NodeSpec(
                    poolKey = key,
                    nodeId = n.nodeId,
                    role = n.role,
                    metastore = state.metastore,
                    s3 = state.s3,
                    maxConcurrent = n.maxConcurrent,
                    kindWire = state.kindWire,
                    extraSetupSql = state.extraSetupSql,
                    // Recover the original cohort placement from the node's
                    // 1-based suffix index. Falls back to no placement when
                    // the pool was created without explicit cohorts or when
                    // the id is in an unexpected shape.
                    placement = placementForNodeId(key, n.nodeId)
                  )
                )
                .flatMap { fresh =>
                  poolIdByKey.get(key) match
                    case Some(pid) => IO.blocking(store.upsertNode(fresh, pid)).as(kept :+ fresh)
                    case None      => IO.pure(kept :+ fresh)
                }
        }
      }
      .flatMap { newNodes =>
        if newNodes.zip(state.nodes).exists((a, b) => a ne b) then
          val updated = state.copy(nodes = newNodes)
          IO.delay(pools.put(key, updated)).as(updated)
        else IO.pure(state)
      }

  /** Spawn the full distribution for a pool whose persisted state has no nodes yet.
    * Mirrors createPool's spawn block but operates on an existing PoolState rather than
    * persisting a fresh Pool entity. Cohort placement is recovered from the pool's
    * authored cohorts (via poolRows) when present; otherwise nodes spawn placement-less.
    */
  private def spawnFromDistribution(key: PoolKey, state: PoolState): IO[PoolState] =
    val poolEntity = poolIdByKey.get(key).flatMap(poolRows.get)
    val plan: List[(ai.starlake.quack.model.Role, NodePlacement)] = poolEntity match
      case Some(p) =>
        p.effectiveCohorts.flatMap(c => c.distribution.asRoleList.map(r => (r, c.placement)))
      case None =>
        state.distribution.asRoleList.map(r => (r, NodePlacement.empty))
    val specs = plan.zipWithIndex.map { case ((role, placement), i) =>
      NodeSpec(
        key,
        PoolSupervisor.nodeId(key, i + 1),
        role,
        state.metastore,
        state.s3,
        maxConcurrent = state.maxConcurrentPerNode,
        kindWire = state.kindWire,
        extraSetupSql = state.extraSetupSql,
        placement = placement
      )
    }
    specs
      .foldLeft(IO.pure(List.empty[RunningNode])) { (acc, spec) =>
        acc.flatMap(rs =>
          IO.delay(tracker.remove(spec.nodeId)) *> backend.start(spec).map(rs :+ _)
        )
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
              .as(updated)
          case None =>
            IO.pure(updated)
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
    tenants.values.find(_.displayName == n)

  /** Lookup by surrogate id (`qodstate_tenant.id`, e.g. `t-02d0e86e`). The
    * internal `tenants` map is already keyed by id, so this is a direct hit. */
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

  /** Resolve `(tenant, poolName) -> PoolKey` so the FlightSQL edge can route a connection that
    * addresses only `tenant` + `pool`. Pool names are unique within a tenant (enforced both by
    * `createPool` and the `qodstate_pool_tenant_name_unique` constraint), so at most one match
    * exists.
    */
  def findPoolKeyByTenantAndPoolName(tenant: String, poolName: String): Option[PoolKey] =
    val t = tenant.toLowerCase
    pools.keys.find(k => k.tenant == t && k.pool == poolName)

  /** Effective metastore for a tenant-db: global defaults overlaid with the tenant-db's own params,
    * then the per-tenant-db naming convention (dbName=td.name, dataPath alongside the root).
    * Used by the catalog browser.
    */
  def effectiveMetastoreFor(tenantName: String, tenantDbName: String): Map[String, String] =
    findTenantDb(tenantName, tenantDbName)
      .map(effectiveMetastoreFor)
      .getOrElse(defaultMetastore)

  // ---------- Tenant API ----------

  def createTenant(t: Tenant): IO[Either[String, Tenant]] = IO.blocking {
    Names.normalizeOrError(t.name, "tenant name") match
      case Left(err)   => Left(err)
      case Right(name) =>
        if tenants.values.exists(_.displayName == name) then Left(s"tenant already exists: $name")
        else
          val withId = t.copy(
            name = name,
            id = if t.id.nonEmpty then t.id else newId("t"),
            displayName = name
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
          Right(withId)
  }

  def setTenantDisabled(name: String, disabled: Boolean): IO[Either[String, Tenant]] = IO.blocking {
    getTenant(name) match
      case None    => Left(s"tenant not found: $name")
      case Some(t) =>
        val updated = t.copy(disabled = disabled)
        store.upsertTenant(updated)
        tenants.put(updated.id, updated)
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
  ): IO[Either[String, Tenant]] = IO.blocking {
    if !Tenant.ValidAuthProviders.contains(authProvider) then
      Left(s"authProvider must be one of ${Tenant.ValidAuthProviders.toList.sorted.mkString(", ")}")
    else
      getTenant(name) match
        case None    => Left(s"tenant not found: $name")
        case Some(t) =>
          val updated = t.copy(authProvider = authProvider, authConfig = authConfig)
          store.upsertTenant(updated)
          tenants.put(updated.id, updated)
          Right(updated)
  }

  def deleteTenant(name: String): IO[Either[String, Unit]] = IO.blocking {
    getTenant(name) match
      case None    => Left(s"tenant not found: $name")
      case Some(t) =>
        val tdbs    = tenantDbs.values.filter(_.tenantId == t.id).toList
        val poolsOf = tdbs.flatMap(td => poolRows.values.filter(_.tenantDbId == td.id))
        if poolsOf.nonEmpty then
          Left(s"tenant '$name' has ${poolsOf.size} active pool(s); stop them first")
        else
          tdbs.foreach { td =>
            store.deleteTenantDb(td.id); tenantDbs.remove(td.id)
            dbAdmin.dropDatabase(td.name) match
              case Right(_)  => ()
              case Left(err) =>
                logger.warn(
                  s"deleteTenant: tenant-db row removed but DROP DATABASE \"${td.name}\" failed: $err"
                )
          }
          store.deleteTenant(t.id)
          tenants.remove(t.id)
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
      defaultSchema: Option[String] = None
  ): IO[Either[String, TenantDb]] = IO.blocking {
    Names.normalizeTenantDbName(tenantName, suffix) match
      case Left(err)   => Left(err)
      case Right(full) =>
        val tn = tenantName.toLowerCase
        getTenant(tn) match
          case None => Left(s"tenant not found: $tn")
          case Some(t) if tenantDbs.values.exists(td => td.tenantId == t.id && td.name == full) =>
            Left(s"tenant-db '$full' already exists in tenant '$tn'")
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
              defaultSchema = defaultSchema
            )

            TenantDb.validate(td) match
              case Some(msg) => Left(s"invalid kind=${kind.wireValue}: $msg")
              case None      =>
                kind match
                  case TenantDbKind.DuckLake =>
                    dbAdmin.createDatabase(full) match
                      case Left(err) => Left(s"failed to provision Postgres database '$full': $err")
                      case Right(_)  =>
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
                        Right(td)
                  case TenantDbKind.DuckDbFile | TenantDbKind.InMemory =>
                    store.upsertTenantDb(td)
                    tenantDbs.put(td.id, td)
                    Right(td)
  }

  def deleteTenantDb(tenantName: String, tenantDbName: String): IO[Either[String, Unit]] =
    IO.blocking {
      val tn = tenantName.toLowerCase
      getTenant(tn) match
        case None    => Left(s"tenant not found: $tn")
        case Some(t) =>
          tenantDbs.values.find(td => td.tenantId == t.id && td.name == tenantDbName) match
            case None     => Left(s"tenant-db '$tenantDbName' not found in tenant '$tn'")
            case Some(td) =>
              val activePools = poolRows.values.filter(_.tenantDbId == td.id).toList
              if activePools.nonEmpty then
                Left(
                  s"tenant-db '$tenantDbName' has ${activePools.size} active pool(s); stop them first"
                )
              else
                store.deleteTenantDb(td.id)
                tenantDbs.remove(td.id)
                dbAdmin.dropDatabase(td.name) match
                  case Right(_)  => ()
                  case Left(err) =>
                    logger.warn(
                      s"deleteTenantDb: control-plane row removed but " +
                        s"DROP DATABASE \"${td.name}\" failed: $err"
                    )
                Right(())
    }

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
      disabled: Boolean = false
  ): IO[List[RunningNode]] = IO.defer {
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
        val merged     = effectiveMetastoreFor(td)
        val kindWire   = td.kind.wireValue
        val extraBlob  = federationBlobOf(td.id).unsafeRunSync().getOrElse("")
        val poolEntity = Pool(
          id = newId("p"),
          tenantId = td.tenantId,
          tenantDbId = td.id,
          name = key.pool,
          size = size,
          distribution = dist,
          maxConcurrentPerNode = maxConcurrentPerNode,
          disabled = disabled,
          cohorts = cohorts
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
              extraSetupSql = extraBlob,
              placement = placement
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
                extraSetupSql = extraBlob,
                defaultDatabase = td.defaultDatabase,
                defaultSchema = td.defaultSchema
              )
              pools.put(key, state)
              running
                .foldLeft(IO.unit)((acc, n) =>
                  acc *> IO.blocking(store.upsertNode(n, poolEntity.id))
                )
                .as(running)
            }
        }
  }

  def setPoolDisabled(key: PoolKey, disabled: Boolean): IO[Either[String, Pool]] = IO.blocking {
    pools.get(key) match
      case None        => Left(s"pool not found: $key")
      case Some(state) =>
        poolIdByKey.get(key).flatMap(poolRows.get) match
          case None    => Left(s"pool entity missing for $key (control-plane out of sync)")
          case Some(p) =>
            val updated = p.copy(disabled = disabled)
            store.upsertPool(updated)
            poolRows.put(updated.id, updated)
            pools.put(key, state.copy(disabled = disabled))
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
          case Some(pid) => IO.blocking(store.upsertNode(u, pid)).as(Some(u))
          case None      => IO.pure(Some(u))

  def scale(
      key: PoolKey,
      targetSize: Int,
      newDist: RoleDistribution,
      force: Boolean
  ): IO[List[RunningNode]] =
    require(newDist.isValidFor(targetSize), "role distribution does not sum to targetSize")
    pools.get(key) match
      case None        => IO.raiseError(new NoSuchElementException(s"pool not found: $key"))
      case Some(state) =>
        val poolId = poolIdByKey.getOrElse(key, "")
        if targetSize > state.size then
          val toAdd = targetSize - state.size
          val roles = newDist.asRoleList.drop(state.size).take(toAdd)
          val specs = roles.zipWithIndex.map { case (role, i) =>
            NodeSpec(
              key,
              PoolSupervisor.nodeId(key, state.size + i + 1),
              role,
              state.metastore,
              state.s3,
              maxConcurrent = state.maxConcurrentPerNode,
              kindWire = state.kindWire,
              extraSetupSql = state.extraSetupSql
            )
          }
          specs
            .foldLeft(IO.pure(state.nodes)) { (acc, spec) =>
              acc.flatMap(rs => backend.start(spec).map(rs :+ _))
            }
            .flatMap { combined =>
              pools.put(key, state.copy(nodes = combined, distribution = newDist))
              updatePoolEntityDist(key, newDist, combined.size)
              val added = combined.drop(state.nodes.size)
              (if poolId.nonEmpty then
                 added
                   .foldLeft(IO.unit)((acc, n) => acc *> IO.blocking(store.upsertNode(n, poolId)))
               else IO.unit).as(combined)
            }
        else if targetSize < state.size then
          val toRemove  = state.nodes.takeRight(state.size - targetSize)
          val remaining = state.nodes.dropRight(state.size - targetSize)
          val stopAll   =
            if force then toRemove.foldLeft(IO.unit)((acc, n) => acc *> backend.stop(n.nodeId))
            else
              toRemove.foldLeft(IO.unit) { (acc, n) =>
                acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *> drainAndStop(n)
              }
          stopAll *>
            toRemove.foldLeft(IO.unit)((acc, n) =>
              acc *> IO.blocking(store.deleteNode(n.nodeId))
            ) *>
            IO.delay {
              pools.put(key, state.copy(nodes = remaining, distribution = newDist))
              updatePoolEntityDist(key, newDist, remaining.size)
              ()
            }.as(remaining)
        else IO.pure(state.nodes)

  def stopPool(key: PoolKey, force: Boolean): IO[Unit] =
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
            acc *> IO.blocking(store.deleteNode(n.nodeId))
          ) *>
          IO.blocking {
            poolIdByKey.get(key).foreach { pid =>
              store.deletePool(pid)
              poolRows.remove(pid)
            }
            pools.remove(key)
            poolIdByKey.remove(key)
          }

  private def drainAndStop(n: RunningNode): IO[Unit] = backend.stop(n.nodeId)

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
  ): IO[Either[String, RbacUser]] = IO.blocking {
    if username.isEmpty || password.isEmpty then Left("username and password are required")
    else
      tenant match
        case Some(t) if t.isEmpty =>
          Left("tenant must be non-empty (use None for superuser)")
        case Some(t) if !tenants.values.exists(x => x.id == t || x.displayName == t.toLowerCase) =>
          Left(s"tenant not found: $t")
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
  ): IO[Either[String, RbacUser]] = IO.blocking {
    store.getUserById(userId) match
      case None    => Left(s"user not found: $userId")
      case Some(u) =>
        val newRole = role.getOrElse(u.role)
        password.foreach(pw => userStore.upsertUser(u.tenant, u.username, pw, newRole))
        val updated = u.copy(role = newRole)
        store.upsertUserIdentity(updated)
        Right(updated)
  }

  def deleteUser(userId: String): IO[Either[String, Unit]] = IO.blocking {
    store.getUserById(userId) match
      case None    => Left(s"user not found: $userId")
      case Some(_) =>
        // ON DELETE CASCADE in qodstate_user_role / user_group /
        // pool_permission cleans up the user's edges automatically.
        store.deleteUser(userId)
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
  ): IO[Either[String, RbacRole]] = IO.blocking {
    if name.isEmpty then Left("role name must be non-empty")
    else if !tenants.contains(tenantId) then Left(s"tenant not found: $tenantId")
    else if store.findRole(tenantId, name).isDefined then
      Left(s"role '$name' already exists in tenant '$tenantId'")
    else
      val r = RbacRole(newId("r"), tenantId, name, description)
      store.upsertRole(r)
      rbacResolver.putRole(r)
      Right(r)
  }

  def deleteRole(id: String): IO[Either[String, Unit]] = IO.blocking {
    rbacResolver.role(id) match
      case None    => Left(s"role not found: $id")
      case Some(_) =>
        store.deleteRole(id)
        rbacResolver.removeRole(id)
        Right(())
  }

  def listRoles(tenantId: String): List[RbacRole] = store.listRoles(tenantId)

  def grantRolePermission(
      roleId: String,
      catalog: String,
      schema: String,
      table: String,
      verb: String
  ): IO[Either[String, RolePermission]] = IO.blocking {
    val upper = verb.toUpperCase
    if !RolePermission.ValidVerbs.contains(upper) then
      Left(s"verb must be one of ${RolePermission.ValidVerbs.mkString(", ")}")
    else if rbacResolver.role(roleId).isEmpty then Left(s"role not found: $roleId")
    else
      val p         = RolePermission(newId("rp"), roleId, catalog, schema, table, upper)
      val persisted = store.insertRolePermission(p)
      rbacResolver.putRolePermission(persisted)
      Right(persisted)
  }

  def revokeRolePermission(id: String): IO[Either[String, Unit]] = IO.blocking {
    if store.deleteRolePermission(id) then
      rbacResolver.removeRolePermission(id)
      Right(())
    else Left(s"role permission not found: $id")
  }

  def listRolePermissions(roleId: String): List[RolePermission] =
    store.listRolePermissions(roleId)

  // ---------- RBAC: groups ----------

  def createGroup(
      tenantId: String,
      name: String,
      description: Option[String] = None
  ): IO[Either[String, RbacGroup]] = IO.blocking {
    if name.isEmpty then Left("group name must be non-empty")
    else if !tenants.contains(tenantId) then Left(s"tenant not found: $tenantId")
    else if store.findGroup(tenantId, name).isDefined then
      Left(s"group '$name' already exists in tenant '$tenantId'")
    else
      val g = RbacGroup(newId("g"), tenantId, name, description)
      store.upsertGroup(g)
      rbacResolver.putGroup(g)
      Right(g)
  }

  def deleteGroup(id: String): IO[Either[String, Unit]] = IO.blocking {
    rbacResolver.group(id) match
      case None    => Left(s"group not found: $id")
      case Some(_) =>
        store.deleteGroup(id)
        rbacResolver.removeGroup(id)
        Right(())
  }

  def listGroups(tenantId: String): List[RbacGroup] = store.listGroups(tenantId)

  def listRolesForGroup(groupId: String): List[RbacRole] =
    rbacResolver.rolesForGroup(groupId).toList.flatMap(rbacResolver.role).sortBy(_.name)

  // ---------- RBAC: memberships ----------

  def addUserRole(userId: String, roleId: String): IO[Either[String, Unit]] = IO.blocking {
    membershipCheck(userId, roleId, rbacResolver.role(_), "role") match
      case Some(err) => Left(err)
      case None      =>
        store.addUserRole(userId, roleId)
        Right(())
  }

  def removeUserRole(userId: String, roleId: String): IO[Either[String, Unit]] = IO.blocking {
    store.removeUserRole(userId, roleId)
    Right(())
  }

  def addUserGroup(userId: String, groupId: String): IO[Either[String, Unit]] = IO.blocking {
    membershipCheck(userId, groupId, rbacResolver.group(_), "group") match
      case Some(err) => Left(err)
      case None      =>
        store.addUserGroup(userId, groupId)
        Right(())
  }

  def removeUserGroup(userId: String, groupId: String): IO[Either[String, Unit]] = IO.blocking {
    store.removeUserGroup(userId, groupId)
    Right(())
  }

  def addGroupRole(groupId: String, roleId: String): IO[Either[String, Unit]] = IO.blocking {
    if rbacResolver.group(groupId).isEmpty then Left(s"group not found: $groupId")
    else if rbacResolver.role(roleId).isEmpty then Left(s"role not found: $roleId")
    else
      store.addGroupRole(groupId, roleId)
      rbacResolver.addGroupRoleEdge(groupId, roleId)
      Right(())
  }

  def removeGroupRole(groupId: String, roleId: String): IO[Either[String, Unit]] = IO.blocking {
    store.removeGroupRole(groupId, roleId)
    rbacResolver.removeGroupRoleEdge(groupId, roleId)
    Right(())
  }

  private def membershipCheck[A](
      userId: String,
      otherId: String,
      lookup: String => Option[A],
      otherLabel: String
  ): Option[String] =
    if store.getUserById(userId).isEmpty then Some(s"user not found: $userId")
    else if lookup(otherId).isEmpty then Some(s"$otherLabel not found: $otherId")
    else None

  // ---------- RBAC: pool permissions ----------

  def grantPoolPermission(
      tenantId: String,
      poolId: Option[String],
      userId: Option[String],
      groupId: Option[String]
  ): IO[Either[String, PoolPermission]] = IO.blocking {
    // Walk through the predicates in order and short-circuit on the
    // first failure. Tenant-scoping invariant (principal belongs to
    // the target tenant) is checked at the end since it requires the
    // principal lookup to have succeeded.
    val problem: Option[String] =
      if !tenants.contains(tenantId) then Some(s"tenant not found: $tenantId")
      else if userId.isDefined == groupId.isDefined then
        Some("exactly one of userId / groupId must be set")
      else
        poolId
          .flatMap(p => if poolRows.contains(p) then None else Some(s"pool not found: $p"))
          .orElse(
            userId.flatMap(u =>
              if store.getUserById(u).isDefined then None else Some(s"user not found: $u")
            )
          )
          .orElse(
            groupId.flatMap(g =>
              if rbacResolver.group(g).isDefined then None else Some(s"group not found: $g")
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
            if ok then None else Some("principal does not belong to the target tenant")
          }

    problem match
      case Some(err) => Left(err)
      case None      =>
        val pp        = PoolPermission(newId("pp"), tenantId, poolId, userId, groupId)
        val persisted = store.insertPoolPermission(pp)
        rbacResolver.putPoolPermission(persisted)
        Right(persisted)
  }

  def revokePoolPermission(id: String): IO[Either[String, Unit]] = IO.blocking {
    if store.deletePoolPermission(id) then
      rbacResolver.removePoolPermission(id)
      Right(())
    else Left(s"pool permission not found: $id")
  }

  def listPoolPermissions(
      tenantId: Option[String] = None,
      userId: Option[String] = None,
      groupId: Option[String] = None
  ): List[PoolPermission] = store.listPoolPermissions(tenantId, userId, groupId)

  // ---------- RBAC: handshake authorization ----------

  /** End-to-end FlightSQL handshake gate. Runs (in order):
    *   1. resolve `(tenant, pool) -> PoolKey` + tenant/pool kill switches
    *   2. lookup the user via [[ControlPlaneStore.findUserForLogin]] -- this query also enforces
    *      the tenant scope: it returns rows where `tenant IS NULL` (superuser) OR
    *      `tenant = <tenantId>`. Any user returned here is therefore already scoped correctly,
    *      so we do NOT re-check `user.tenant == tenantRow.id` at the application layer.
    *      If [[ControlPlaneStore.findUserForLogin]] is ever refactored to drop the tenant
    *      filter (e.g. moving to a global-username model), reinstate the scope check between
    *      gates 2 and 3.
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
    store.getUserById(userId).map { u =>
      val directRoleIdsLocal = store.listDirectRolesForUser(u.id).toSet
      val groupIdsLocal      = store.listGroupsForUser(u.id).toSet
      // JWT-claim resolution. Only tenant-scoped users carry a tenant
      // id; superusers (u.tenant.isEmpty) bypass per-statement
      // validation entirely upstream, so the union here is a no-op.
      val jwtRoleIds  = u.tenant.toSet.flatMap(t => rbacResolver.rolesByNamesInTenant(t, jwtRoles))
      val jwtGroupIds =
        u.tenant.toSet.flatMap(t => rbacResolver.groupsByNamesInTenant(t, jwtGroups))
      val directRoleIds = directRoleIdsLocal ++ jwtRoleIds
      val groupIds      = groupIdsLocal ++ jwtGroupIds
      val viaGroups     = groupIds.flatMap(rbacResolver.rolesForGroup)
      val allRoleIds    = directRoleIds ++ viaGroups
      val effRoles      = allRoleIds.flatMap(rbacResolver.role).toList.sortBy(_.name)
      val effGroups     = groupIds.flatMap(rbacResolver.group).toList.sortBy(_.name)
      val effPerms      = rbacResolver.permissionsForRoles(allRoleIds)
      val directPools   = store.listPoolPermissionsForUser(u.id)
      val viaGroupPools = groupIds.toList.flatMap(rbacResolver.poolPermissionsForGroup)
      ai.starlake.quack.ondemand.rbac.EffectiveSet(
        u,
        effRoles,
        effGroups,
        effPerms,
        directPools ++ viaGroupPools
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

  private def newId(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

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

  /** Compose a node id that is safe as a Kubernetes pod + service name.
    *
    * `key.tenantDb` is the normalized composed Postgres database name `${tenant}_${tenantDb}` and
    * carries an underscore as separator, which is valid in Postgres identifiers but illegal in pod
    * names (RFC 1123 subdomain: lowercase alphanumeric + `-` + `.` only). The Postgres name stays
    * unchanged; this helper only sanitizes for the node-id / pod-name surface.
    */
  def nodeId(key: ai.starlake.quack.model.PoolKey, index: Int): String =
    val safeDb = key.tenantDb.replace('_', '-')
    s"quack-${key.tenant}-$safeDb-${key.pool}-$index"
