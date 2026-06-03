package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  Names, NodeSpec, Pool, PoolKey, RoleDistribution, RunningNode, Tenant, TenantDb
}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, DbAdmin, NoopDbAdmin}
import ai.starlake.quack.route.PoolSnapshot
import cats.effect.IO
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.collection.concurrent.TrieMap

/** Owns the in-memory topology and mediates every mutation through
  * [[ControlPlaneStore]]:
  *
  *   - `Tenant`        ownership umbrella (`id`, `displayName`, `disabled`).
  *   - `TenantDb`      one DuckLake / Postgres database under a tenant.
  *                     Owns the `(metastore, dataPath, objectStore)`
  *                     tuple. Pools and metastore live HERE.
  *   - `Pool`          desired compute config under one tenant-db.
  *                     Inherits the tenant-db's metastore + objectStore.
  *   - `RunningNode`   runtime instance of a pool's compute. */
final class PoolSupervisor(
    backend:          QuackBackend,
    tracker:          NodeLoadTracker,
    store:            ControlPlaneStore,
    defaultMetastore: Map[String, String] = Map.empty,
    dbAdmin:          DbAdmin             = NoopDbAdmin
):

  private val logger = LoggerFactory.getLogger(getClass)

  // Surrogate-id-indexed caches of the persisted state.
  private val tenants   = TrieMap.empty[String, Tenant]    // id -> Tenant
  private val tenantDbs = TrieMap.empty[String, TenantDb]  // id -> TenantDb
  private val poolRows  = TrieMap.empty[String, Pool]      // id -> Pool

  // Runtime cache keyed by the natural PoolKey for fast routing.
  private val pools = TrieMap.empty[PoolKey, PoolState]
  // PoolKey -> pool.id, so per-node mutations know the FK to qodstate_pool.
  private val poolIdByKey = TrieMap.empty[PoolKey, String]

  // ---------- Bootstrap / replay ----------

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
        val merged    = defaultMetastore ++ td.metastore
        pools.put(key, PoolState(
          key                  = key,
          nodes                = nodesHere,
          distribution         = p.distribution,
          metastore            = merged,
          s3                   = td.objectStore,
          maxConcurrentPerNode = p.maxConcurrentPerNode,
          disabled             = p.disabled
        ))
      }
    }

  /** Re-check every persisted node; respawn dead ones. */
  def reconcile(): IO[Unit] = IO.defer {
    logger.info(s"reconcile: checking ${pools.size} pool(s), ${pools.values.map(_.nodes.size).sum} node(s)")
    pools.toList.foldLeft(IO.unit) { case (acc, (key, state)) =>
      acc *> reconcilePool(key, state).void
    }
  }

  private def reconcilePool(key: PoolKey, state: PoolState): IO[PoolState] =
    state.nodes.foldLeft(IO.pure(List.empty[RunningNode])) { (acc, n) =>
      acc.flatMap { kept =>
        if isReachable(n) then backend.adopt(n).as(kept :+ n)
        else
          logger.warn(
            s"reconcile: $key/${n.nodeId} (pid=${n.pid.getOrElse("?")} port=${n.port}) " +
              "is dead; respawning"
          )
          IO.delay(tracker.remove(n.nodeId)) *>
            backend.start(NodeSpec(
              poolKey       = key,
              nodeId        = n.nodeId,
              role          = n.role,
              metastore     = state.metastore,
              s3            = state.s3,
              maxConcurrent = n.maxConcurrent
            )).flatMap { fresh =>
              poolIdByKey.get(key) match
                case Some(pid) => IO.blocking(store.upsertNode(fresh, pid)).as(kept :+ fresh)
                case None      => IO.pure(kept :+ fresh)
            }
      }
    }.flatMap { newNodes =>
      if newNodes.zip(state.nodes).exists((a, b) => a ne b) then
        val updated = state.copy(nodes = newNodes)
        IO.delay(pools.put(key, updated)).as(updated)
      else IO.pure(state)
    }

  private def isReachable(n: RunningNode): Boolean =
    n.pid match
      case None => true // K8s: defer to control-plane liveness + HealthProbe.
      case Some(p) =>
        val pidAlive = Option(java.lang.ProcessHandle.of(p))
          .flatMap(o => if o.isPresent then Some(o.get()) else None)
          .exists(_.isAlive)
        if !pidAlive then false
        else
          val sock = new java.net.Socket()
          try { sock.connect(new java.net.InetSocketAddress(n.host, n.port), 250); true }
          catch case _: Throwable => false
          finally try sock.close() catch case _: Throwable => ()

  // ---------- Read API ----------

  def get(key: PoolKey): Option[PoolState] = pools.get(key)
  def list(): List[PoolState]              = pools.values.toList
  def snapshot(key: PoolKey): Option[PoolSnapshot] =
    pools.get(key).map(p => PoolSnapshot(p.key, p.nodes, tracker.snapshotAll))

  def listTenants(): List[Tenant] = tenants.values.toList.sortBy(_.displayName)
  def getTenant(name: String): Option[Tenant] =
    val n = name.toLowerCase
    tenants.values.find(_.displayName == n)

  def listPoolsOfTenant(name: String): List[String] =
    pools.values.filter(_.key.tenant == name.toLowerCase).map(_.key.pool).toList.sorted

  def listTenantDbsByTenant(tenantName: String): List[TenantDb] =
    getTenant(tenantName).map(t => tenantDbs.values.filter(_.tenantId == t.id).toList.sortBy(_.name))
      .getOrElse(Nil)

  def findTenantDb(tenantName: String, tenantDbName: String): Option[TenantDb] =
    getTenant(tenantName).flatMap { t =>
      val nm = tenantDbName.toLowerCase
      tenantDbs.values.find(td => td.tenantId == t.id && td.name == nm)
    }

  /** Resolve `(tenant, poolName) -> PoolKey` so the FlightSQL edge can
    * route a connection that addresses only `tenant` + `pool`. Pool
    * names are unique within a tenant (enforced both by
    * `createPool` and the `qodstate_pool_tenant_name_unique`
    * constraint), so at most one match exists. */
  def findPoolKeyByTenantAndPoolName(tenant: String, poolName: String): Option[PoolKey] =
    val t = tenant.toLowerCase
    pools.keys.find(k => k.tenant == t && k.pool == poolName)

  /** Effective metastore for a tenant-db: global defaults overlaid
    * with the tenant-db's own params. Used by the catalog browser. */
  def effectiveMetastoreFor(tenantName: String, tenantDbName: String): Map[String, String] =
    findTenantDb(tenantName, tenantDbName)
      .map(td => defaultMetastore ++ td.metastore)
      .getOrElse(defaultMetastore)

  // ---------- Tenant API ----------

  def createTenant(t: Tenant): IO[Either[String, Tenant]] = IO.blocking {
    Names.normalizeOrError(t.name, "tenant name") match
      case Left(err) => Left(err)
      case Right(name) =>
        if tenants.values.exists(_.displayName == name) then
          Left(s"tenant already exists: $name")
        else
          val withId = t.copy(
            name        = name,
            id          = if t.id.nonEmpty then t.id else newId("t"),
            displayName = name
          )
          store.upsertTenant(withId)
          tenants.put(withId.id, withId)
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

  def deleteTenant(name: String): IO[Either[String, Unit]] = IO.blocking {
    getTenant(name) match
      case None => Left(s"tenant not found: $name")
      case Some(t) =>
        val tdbs = tenantDbs.values.filter(_.tenantId == t.id).toList
        val poolsOf = tdbs.flatMap(td => poolRows.values.filter(_.tenantDbId == td.id))
        if poolsOf.nonEmpty then
          Left(s"tenant '$name' has ${poolsOf.size} active pool(s); stop them first")
        else
          tdbs.foreach { td =>
            store.deleteTenantDb(td.id); tenantDbs.remove(td.id)
            dbAdmin.dropDatabase(td.name) match
              case Right(_) => ()
              case Left(err) => logger.warn(
                s"deleteTenant: tenant-db row removed but DROP DATABASE \"${td.name}\" failed: $err"
              )
          }
          store.deleteTenant(t.id)
          tenants.remove(t.id)
          Right(())
  }

  // ---------- TenantDb API ----------

  def createTenantDb(
      tenantName:  String,
      suffix:      String,
      metastore:   Map[String, String],
      dataPath:    String,
      objectStore: Map[String, String] = Map.empty
  ): IO[Either[String, TenantDb]] = IO.blocking {
    Names.normalizeTenantDbName(tenantName, suffix) match
      case Left(err) => Left(err)
      case Right(full) =>
        val tn = tenantName.toLowerCase
        getTenant(tn) match
          case None => Left(s"tenant not found: $tn")
          case Some(t) if tenantDbs.values.exists(td => td.tenantId == t.id && td.name == full) =>
            Left(s"tenant-db '$full' already exists in tenant '$tn'")
          case Some(t) =>
            val metaWithDb = metastore.updated("dbName", full)
            dbAdmin.createDatabase(full) match
              case Left(err) => Left(s"failed to provision Postgres database '$full': $err")
              case Right(_) =>
                // Pre-init the ducklake_* metadata tables in the fresh
                // tenant-db Postgres so the first batch of pool nodes
                // doesn't race on `CREATE TABLE __ducklake_metadata`.
                // Best-effort: failures here only forfeit race protection,
                // the per-node ATTACH still creates the tables. Skips
                // silently when the metastore lacks pgHost (e.g. tests
                // with NoopDbAdmin).
                try
                  DuckLakeInitializer.initBlocking(metaWithDb.updated("dataPath", dataPath))
                catch case t: Throwable =>
                  logger.warn(
                    s"createTenantDb: DuckLake pre-init for '$full' failed; " +
                    s"first pool spawn will retry the ATTACH. Cause: ${t.getMessage}"
                  )
                val td = TenantDb(
                  id          = newId("td"),
                  tenantId    = t.id,
                  name        = full,
                  metastore   = metaWithDb,
                  dataPath    = dataPath,
                  objectStore = objectStore
                )
                store.upsertTenantDb(td)
                tenantDbs.put(td.id, td)
                Right(td)
  }

  def deleteTenantDb(tenantName: String, tenantDbName: String): IO[Either[String, Unit]] =
    IO.blocking {
      val tn = tenantName.toLowerCase
      getTenant(tn) match
        case None => Left(s"tenant not found: $tn")
        case Some(t) =>
          tenantDbs.values.find(td => td.tenantId == t.id && td.name == tenantDbName) match
            case None => Left(s"tenant-db '$tenantDbName' not found in tenant '$tn'")
            case Some(td) =>
              val activePools = poolRows.values.filter(_.tenantDbId == td.id).toList
              if activePools.nonEmpty then
                Left(s"tenant-db '$tenantDbName' has ${activePools.size} active pool(s); stop them first")
              else
                store.deleteTenantDb(td.id)
                tenantDbs.remove(td.id)
                dbAdmin.dropDatabase(td.name) match
                  case Right(_) => ()
                  case Left(err) => logger.warn(
                    s"deleteTenantDb: control-plane row removed but " +
                    s"DROP DATABASE \"${td.name}\" failed: $err"
                  )
                Right(())
    }

  // ---------- Pool API ----------

  /** Create a pool under an existing tenant-db. The tenant-db's
    * metastore + objectStore are the only source of storage config --
    * there is no per-pool override anymore. The caller must have
    * created `(key.tenant, key.tenantDb)` first via `createTenantDb`. */
  def createPool(
      key:                  PoolKey,
      dist:                 RoleDistribution,
      maxConcurrentPerNode: Int = 0
  ): IO[List[RunningNode]] = IO.defer {
    val size = dist.total
    require(dist.writeonly >= 0 && dist.readonly >= 0 && dist.dual >= 0,
      s"role distribution must be non-negative: $dist")
    require(size > 0, s"role distribution must sum to at least 1: $dist")
    require(dist.isValidFor(size), s"role distribution does not sum to $size")

    findTenantDb(key.tenant, key.tenantDb) match
      case None =>
        IO.raiseError(new IllegalStateException(
          s"tenant-db '${key.tenant}/${key.tenantDb}' not found; create it first"
        ))
      case Some(td) if pools.keys.exists(k => k.tenant == key.tenant && k.pool == key.pool && k.tenantDb != key.tenantDb) =>
        // Pool names are unique within a tenant (see qodstate_pool
        // UNIQUE (tenant_id, name)). The FlightSQL edge resolves
        // (tenant, pool) -> PoolKey at handshake time, so allowing the
        // same pool name in two tenant-dbs under one tenant would make
        // that lookup ambiguous.
        IO.raiseError(new IllegalStateException(
          s"pool '${key.pool}' already exists under tenant '${key.tenant}' " +
          "in a different tenant-db; pool names must be unique per tenant"
        ))
      case Some(td) =>
        val merged = defaultMetastore ++ td.metastore
        val poolEntity = Pool(
          id                   = newId("p"),
          tenantId             = td.tenantId,
          tenantDbId           = td.id,
          name                 = key.pool,
          size                 = size,
          distribution         = dist,
          maxConcurrentPerNode = maxConcurrentPerNode
        )
        IO.blocking(store.upsertPool(poolEntity)) *> IO.delay {
          poolRows.put(poolEntity.id, poolEntity)
          poolIdByKey.put(key, poolEntity.id)
        } *> {
          val specs = dist.asRoleList.zipWithIndex.map { case (role, i) =>
            NodeSpec(key, s"quack-${key.tenant}-${key.tenantDb}-${key.pool}-${i + 1}", role,
              merged, td.objectStore, maxConcurrent = maxConcurrentPerNode)
          }
          specs.foldLeft(IO.pure(List.empty[RunningNode])) { (acc, spec) =>
            acc.flatMap(rs => IO.delay(tracker.remove(spec.nodeId)) *> backend.start(spec).map(rs :+ _))
          }.flatMap { running =>
            val state = PoolState(key, running, dist, merged, td.objectStore, maxConcurrentPerNode)
            pools.put(key, state)
            running.foldLeft(IO.unit)((acc, n) => acc *> IO.blocking(store.upsertNode(n, poolEntity.id))).as(running)
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
      case None => IO.pure(None)
      case Some(n) =>
        val u        = n.copy(maxConcurrent = max)
        val state    = pools(key)
        val newNodes = state.nodes.map(x => if x.nodeId == nodeId then u else x)
        pools.put(key, state.copy(nodes = newNodes))
        poolIdByKey.get(key) match
          case Some(pid) => IO.blocking(store.upsertNode(u, pid)).as(Some(u))
          case None      => IO.pure(Some(u))

  def scale(
      key:        PoolKey,
      targetSize: Int,
      newDist:    RoleDistribution,
      force:      Boolean
  ): IO[List[RunningNode]] =
    require(newDist.isValidFor(targetSize), "role distribution does not sum to targetSize")
    pools.get(key) match
      case None => IO.raiseError(new NoSuchElementException(s"pool not found: $key"))
      case Some(state) =>
        val poolId = poolIdByKey.getOrElse(key, "")
        if targetSize > state.size then
          val toAdd = targetSize - state.size
          val roles = newDist.asRoleList.drop(state.size).take(toAdd)
          val specs = roles.zipWithIndex.map { case (role, i) =>
            NodeSpec(key,
              s"quack-${key.tenant}-${key.tenantDb}-${key.pool}-${state.size + i + 1}",
              role, state.metastore, state.s3, maxConcurrent = state.maxConcurrentPerNode)
          }
          specs.foldLeft(IO.pure(state.nodes)) { (acc, spec) =>
            acc.flatMap(rs => backend.start(spec).map(rs :+ _))
          }.flatMap { combined =>
            pools.put(key, state.copy(nodes = combined, distribution = newDist))
            updatePoolEntityDist(key, newDist, combined.size)
            val added = combined.drop(state.nodes.size)
            (if poolId.nonEmpty then added.foldLeft(IO.unit)((acc, n) => acc *> IO.blocking(store.upsertNode(n, poolId)))
             else IO.unit).as(combined)
          }
        else if targetSize < state.size then
          val toRemove  = state.nodes.takeRight(state.size - targetSize)
          val remaining = state.nodes.dropRight(state.size - targetSize)
          val stopAll =
            if force then toRemove.foldLeft(IO.unit)((acc, n) => acc *> backend.stop(n.nodeId))
            else toRemove.foldLeft(IO.unit) { (acc, n) =>
              acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *> drainAndStop(n)
            }
          stopAll *>
            toRemove.foldLeft(IO.unit)((acc, n) => acc *> IO.blocking(store.deleteNode(n.nodeId))) *>
            IO.delay {
              pools.put(key, state.copy(nodes = remaining, distribution = newDist))
              updatePoolEntityDist(key, newDist, remaining.size)
              ()
            }.as(remaining)
        else IO.pure(state.nodes)

  def stopPool(key: PoolKey, force: Boolean): IO[Unit] =
    pools.get(key) match
      case None => IO.unit
      case Some(state) =>
        val stopAll =
          if force then state.nodes.foldLeft(IO.unit)((acc, n) => acc *> backend.stop(n.nodeId))
          else state.nodes.foldLeft(IO.unit) { (acc, n) =>
            acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *> drainAndStop(n)
          }
        stopAll *>
          state.nodes.foldLeft(IO.unit)((acc, n) => acc *> IO.blocking(store.deleteNode(n.nodeId))) *>
          IO.blocking {
            poolIdByKey.get(key).foreach { pid =>
              store.deletePool(pid)
              poolRows.remove(pid)
            }
            pools.remove(key)
            poolIdByKey.remove(key)
          }

  private def drainAndStop(n: RunningNode): IO[Unit] = backend.stop(n.nodeId)

  // ---------- helpers ----------

  private def newId(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  private def updatePoolEntityDist(key: PoolKey, dist: RoleDistribution, size: Int): Unit =
    poolIdByKey.get(key).flatMap(poolRows.get).foreach { p =>
      val updated = p.copy(size = size, distribution = dist)
      store.upsertPool(updated)
      poolRows.put(updated.id, updated)
    }