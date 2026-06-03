package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  Names, NodeSpec, Pool, PoolKey, RoleDistribution, RunningNode, Tenant, TenantDb
}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.route.PoolSnapshot
import cats.effect.IO
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.collection.concurrent.TrieMap

/** Owns the in-memory topology and mediates every mutation through
  * [[ControlPlaneStore]] (per-entity upserts).
  *
  * Topology:
  *   - `Tenant`        ownership umbrella (`id`, `displayName`, `disabled`).
  *   - `TenantDb`      one DuckLake / Postgres database under a tenant.
  *                     Owns `(metastore, dataPath, objectStore)`.
  *   - `Pool`          desired compute config under one tenant-db.
  *   - `RunningNode`   runtime instance of a pool's compute.
  *
  * HEAD callers pass `metastore` + `s3` per `createPool` rather than
  * creating an explicit tenant-db first. This supervisor keeps that
  * external shape: a `createPool` for a tenant with no tenant-db
  * auto-creates the tenant's `default` tenant-db, threading the passed
  * metastore + object-store into it. New callers can call
  * [[createTenantDb]] up-front for an explicit name. */
final class PoolSupervisor(
    backend:          QuackBackend,
    tracker:          NodeLoadTracker,
    store:            ControlPlaneStore,
    defaultMetastore: Map[String, String] = Map.empty
):

  private val logger = LoggerFactory.getLogger(getClass)

  /** Suffix used by the auto-created tenant-db when a caller drives the
    * old-shape `createTenant -> createPool` flow. The composed name is
    * `${tenant}_default` via [[Names.normalizeTenantDbName]]. */
  val DefaultTenantDbSuffix: String = "default"

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
        val key = PoolKey(t.displayName, p.name)
        poolIdByKey.put(key, p.id)
        val nodesHere = snap.nodes.filter(_.poolKey == key)
        val merged    = defaultMetastore ++ td.metastore
        pools.put(key, PoolState(
          key                  = key,
          nodes                = nodesHere,
          distribution         = p.distribution,
          metastore            = merged,
          s3                   = td.objectStore,
          maxConcurrentPerNode = p.maxConcurrentPerNode
        ))
      }
    }

  /** Re-check every persisted node; respawn dead ones. Identical contract
    * to HEAD - just persists per-node now instead of via a bulk save. */
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

  def defaultTenantDbOf(tenantName: String): Option[TenantDb] =
    getTenant(tenantName).flatMap { t =>
      val expected = Names.normalizeTenantDbName(t.displayName, DefaultTenantDbSuffix).toOption
      expected.flatMap(full => tenantDbs.values.find(td => td.tenantId == t.id && td.name == full))
    }

  /** Effective metastore for a tenant: global defaults overlaid with the
    * tenant's default tenant-db's params. Falls back to global defaults
    * when the tenant has no tenant-db yet. */
  def effectiveMetastoreFor(tenantName: String): Map[String, String] =
    defaultTenantDbOf(tenantName).map(td => defaultMetastore ++ td.metastore).getOrElse(defaultMetastore)

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
          // Back-compat: when the caller still drives the HEAD shape
          // and supplies a `Tenant.metastore`, materialise the default
          // tenant-db right away so later `createPool` / `effective-
          // MetastoreFor` see the params without an extra round-trip.
          if t.metastore.nonEmpty then
            ensureDefaultTenantDb(withId, t.metastore, dataPath = "", objectStore = Map.empty)
          Right(withId)
  }

  /** Back-compat shim: HEAD's `setTenantMetastore` mutated the tenant
    * row's metastore. The new model stores metastore on the tenant-db,
    * so we route the call to the tenant's default tenant-db (auto-create
    * if absent). */
  def setTenantMetastore(name: String, metastore: Map[String, String]): IO[Option[Tenant]] =
    IO.blocking {
      getTenant(name) match
        case None => None
        case Some(t) =>
          val td = ensureDefaultTenantDb(t, metastore, dataPath = "", objectStore = Map.empty)
          val updated = td.copy(metastore = metastore)
          store.upsertTenantDb(updated)
          tenantDbs.put(updated.id, updated)
          Some(t)
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
            val td = TenantDb(
              id          = newId("td"),
              tenantId    = t.id,
              name        = full,
              metastore   = metastore,
              dataPath    = dataPath,
              objectStore = objectStore
            )
            store.upsertTenantDb(td)
            tenantDbs.put(td.id, td)
            Right(td)
  }

  private def ensureDefaultTenantDb(
      t:           Tenant,
      metastore:   Map[String, String],
      dataPath:    String,
      objectStore: Map[String, String]
  ): TenantDb =
    val expected = Names
      .normalizeTenantDbName(t.displayName, DefaultTenantDbSuffix)
      .getOrElse(throw new IllegalStateException(s"invalid tenant name for default: ${t.displayName}"))
    tenantDbs.values.find(td => td.tenantId == t.id && td.name == expected) match
      case Some(td) => td
      case None =>
        val td = TenantDb(
          id          = newId("td"),
          tenantId    = t.id,
          name        = expected,
          metastore   = metastore,
          dataPath    = dataPath,
          objectStore = objectStore
        )
        store.upsertTenantDb(td)
        tenantDbs.put(td.id, td)
        td

  // ---------- Pool API ----------

  /** Create a pool under the tenant's default tenant-db. The first call
    * for a tenant auto-creates `${tenant}_default` with the passed
    * metastore + s3; subsequent calls reuse it. */
  def createPool(
      key:                  PoolKey,
      dist:                 RoleDistribution,
      metastore:            Map[String, String],
      s3:                   Map[String, String],
      maxConcurrentPerNode: Int = 0
  ): IO[List[RunningNode]] = IO.defer {
    val size = dist.total
    require(dist.writeonly >= 0 && dist.readonly >= 0 && dist.dual >= 0,
      s"role distribution must be non-negative: $dist")
    require(size > 0, s"role distribution must sum to at least 1: $dist")
    require(dist.isValidFor(size), s"role distribution does not sum to $size")

    getTenant(key.tenant) match
      case None =>
        IO.raiseError(new IllegalStateException(s"tenant '${key.tenant}' not registered"))
      case Some(t) =>
        val td = ensureDefaultTenantDb(t, metastore, dataPath = "", objectStore = s3)
        val merged = defaultMetastore ++ td.metastore ++ metastore
        val poolEntity = Pool(
          id                   = newId("p"),
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
            NodeSpec(key, s"quack-${key.tenant}-${key.pool}-${i + 1}", role,
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
            NodeSpec(key, s"quack-${key.tenant}-${key.pool}-${state.size + i + 1}",
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

