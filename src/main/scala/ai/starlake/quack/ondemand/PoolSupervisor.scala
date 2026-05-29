package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, PoolKey, RoleDistribution, RunningNode, Tenant}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{StateStore, StoredPool, StoredState, StoredTenant}
import ai.starlake.quack.route.PoolSnapshot
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import scala.collection.concurrent.TrieMap

final class PoolSupervisor(
    backend: QuackBackend,
    tracker: NodeLoadTracker,
    store:   StateStore,
    defaultMetastore: Map[String, String] = Map.empty
) extends LazyLogging:

  private val pools   = TrieMap.empty[PoolKey, PoolState]
  private val tenants = TrieMap.empty[String, Tenant]

  /** Replay state from disk on construction (local mode). Direct sync -
    * called once at boot before serving. */
  def restore(): Unit =
    val s = store.load()
    s.tenants.values.foreach { st =>
      tenants.put(st.name, StoredTenant.toDomain(st))
    }
    s.pools.values.foreach { sp =>
      pools.put(sp.key,
        PoolState(sp.key, sp.nodes, sp.distribution,
                  sp.metastore, sp.s3, sp.maxConcurrentPerNode))
    }

  /** After `restore()`, walk every persisted node and check whether its
    * child process + listening port are still alive. Dead records are
    * respawned through the backend (the new process gets a fresh port +
    * token; everything else - nodeId, role, metastore - is preserved).
    *
    * Closes the bite documented as followup #20: before this, a manager
    * restart after `kill -9` left phantom node records that the router
    * happily routed to. The HealthProbe would catch the failure later but
    * a stop+recreate cycle was the only practical recovery path. */
  def reconcile(): IO[Unit] = IO.defer {
    // IO.defer so `pools.toList` is read at runtime, after restore() has
    // populated the map. Without it, the foldLeft snapshots an empty list
    // at build time and the reconcile is a no-op.
    logger.info(s"reconcile: checking ${pools.size} pool(s), ${pools.values.map(_.nodes.size).sum} node(s)")
    pools.toList.foldLeft(IO.unit) { case (acc, (key, state)) =>
      acc *> reconcilePool(key, state).void
    }
  }

  private def reconcilePool(key: PoolKey, state: PoolState): IO[PoolState] =
    state.nodes.foldLeft(IO.pure(List.empty[RunningNode])) { (acc, n) =>
      acc.flatMap { kept =>
        if isReachable(n) then
          // Keep - but register with the backend so subsequent stop()
          // and port-allocation operations see this node. Without this,
          // a stop after reconcile silently no-ops (Local mode) and the
          // port allocator hands out the already-in-use port on the
          // next createPool.
          backend.adopt(n).as(kept :+ n)
        else
          logger.warn(
            s"reconcile: $key/${n.nodeId} (pid=${n.pid.getOrElse("?")} port=${n.port}) " +
            "is dead; respawning")
          // Reset tracker - same logic as createPool - so any leftover
          // draining/unhealthy state from the prior incarnation doesn't
          // carry over into the new process.
          IO.delay(tracker.remove(n.nodeId)) *>
            backend.start(NodeSpec(
              poolKey       = key,
              nodeId        = n.nodeId,
              role          = n.role,
              metastore     = state.metastore,
              s3            = state.s3,
              maxConcurrent = n.maxConcurrent
            )).map(fresh => kept :+ fresh)
      }
    }.flatMap { newNodes =>
      if newNodes.zip(state.nodes).exists((a, b) => a ne b) then
        val updated = state.copy(nodes = newNodes)
        IO.delay(pools.put(key, updated)) *> persist().as(updated)
      else IO.pure(state)
    }

  /** Does the persisted node still have a live OS process + accepting
    * port? Local mode uses `ProcessHandle.of(pid)` + a one-shot TCP
    * connect.
    *
    * K8s mode stores `pid = None` (the backend tracks pod names, not
    * Linux PIDs). Reconciliation for K8s is a separate concern - pod
    * liveness is the K8s control plane's job, and re-spawning a pod
    * that's already alive would 409 the apiserver. So when `pid.isEmpty`
    * we conservatively trust the persisted record and let the
    * HealthProbe catch any drift later.
    *
    * Side effect: in local mode, a node persisted with no PID (shouldn't
    * happen, but defensively) is treated as reachable rather than
    * triggering an unbounded respawn loop. The cost of that false
    * positive is a 5 s HealthProbe lag; the cost of false negatives in
    * K8s mode is duplicate pods. */
  private def isReachable(n: RunningNode): Boolean =
    n.pid match
      case None =>
        true // K8s - defer to control-plane liveness + HealthProbe.
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

  def get(key: PoolKey): Option[PoolState] = pools.get(key)

  def list(): List[PoolState] = pools.values.toList

  def snapshot(key: PoolKey): Option[PoolSnapshot] =
    pools.get(key).map(p => PoolSnapshot(p.key, p.nodes, tracker.snapshotAll))

  // ---------- Tenant API ----------

  def listTenants(): List[Tenant] = tenants.values.toList.sortBy(_.name)

  /** Effective metastore for a tenant: global defaults overlaid with the
    * tenant's own overrides. Same merge order used at pool create time
    * (without the per-pool layer). */
  def effectiveMetastoreFor(tenantName: String): Map[String, String] =
    val tenantOverrides = tenants.get(tenantName).map(_.metastore).getOrElse(Map.empty)
    defaultMetastore ++ tenantOverrides

  def getTenant(name: String): Option[Tenant] = tenants.get(name)

  def listPoolsOfTenant(name: String): List[String] =
    pools.values.filter(_.key.tenant == name).map(_.key.pool).toList.sorted

  def createTenant(t: Tenant): IO[Either[String, Tenant]] =
    if tenants.putIfAbsent(t.name, t).isDefined then
      IO.pure(Left(s"tenant already exists: ${t.name}"))
    else
      persist().as(Right(t))

  def setTenantMetastore(name: String, metastore: Map[String, String]): IO[Option[Tenant]] =
    tenants.get(name) match
      case None => IO.pure(None)
      case Some(existing) =>
        val updated = existing.copy(metastore = metastore)
        tenants.put(name, updated)
        persist().as(Some(updated))

  def deleteTenant(name: String): IO[Either[String, Unit]] =
    val poolsOf = listPoolsOfTenant(name)
    if poolsOf.nonEmpty then
      IO.pure(Left(s"tenant '$name' has ${poolsOf.size} active pool(s); stop them first"))
    else if tenants.remove(name).isEmpty then
      IO.pure(Left(s"tenant not found: $name"))
    else
      persist().as(Right(()))

  // ---------- Pool API ----------

  def createPool(
      key: PoolKey,
      dist: RoleDistribution,
      metastore: Map[String, String],
      s3: Map[String, String],
      maxConcurrentPerNode: Int = 0
  ): IO[List[RunningNode]] =
    val size = dist.total
    require(dist.writeonly >= 0 && dist.readonly >= 0 && dist.dual >= 0,
            s"role distribution must be non-negative: $dist")
    require(size > 0, s"role distribution must sum to at least 1: $dist")
    require(dist.isValidFor(size), s"role distribution does not sum to $size")
    // Merge order: global defaults -> tenant overrides -> per-pool. Right side
    // wins on key collisions. The merged map is what we store on PoolState so
    // a later scale() reuses exactly the metastore that was in effect at create
    // time, and the API can expose the effective values to the UI.
    val tenantOverrides = tenants.get(key.tenant).map(_.metastore).getOrElse(Map.empty)
    val merged          = defaultMetastore ++ tenantOverrides ++ metastore
    val specs = dist.asRoleList.zipWithIndex.map { case (role, i) =>
      NodeSpec(key, s"quack-${key.tenant}-${key.pool}-${i + 1}", role,
               merged, s3, maxConcurrent = maxConcurrentPerNode)
    }
    specs.foldLeft(IO.pure(List.empty[RunningNode])) { (acc, spec) =>
      // Reset any stale tracker entry left over from a previous incarnation
      // of this nodeId (e.g. a non-force stopPool that set draining=true on
      // the prior node before this nodeId gets reused).
      acc.flatMap(rs => IO.delay(tracker.remove(spec.nodeId)) *> backend.start(spec).map(rs :+ _))
    }.flatMap { running =>
      val state = PoolState(key, running, dist, merged, s3, maxConcurrentPerNode)
      pools.put(key, state)
      persist().as(running)
    }

  /** Mutate one node's maxConcurrent. Direct TrieMap update; only persist() is IO. */
  def setMaxConcurrent(key: PoolKey, nodeId: String, max: Int): IO[Option[RunningNode]] =
    val updated = pools.get(key).flatMap { state =>
      state.nodes.find(_.nodeId == nodeId).map { n =>
        val u = n.copy(maxConcurrent = max)
        val newNodes = state.nodes.map(x => if x.nodeId == nodeId then u else x)
        pools.put(key, state.copy(nodes = newNodes))
        u
      }
    }
    updated match
      case Some(n) => persist().as(Some(n))
      case None    => IO.pure(None)

  def scale(
      key: PoolKey,
      targetSize: Int,
      newDist: RoleDistribution,
      force: Boolean
  ): IO[List[RunningNode]] =
    require(newDist.isValidFor(targetSize), "role distribution does not sum to targetSize")
    pools.get(key) match
      case None => IO.raiseError(new NoSuchElementException(s"pool not found: $key"))
      case Some(state) =>
        if targetSize > state.size then
          val toAdd  = targetSize - state.size
          val roles  = newDist.asRoleList.drop(state.size).take(toAdd)
          // state.metastore is already the merged map from createPool - reuse
          // it verbatim so scale-up doesn't drift if tenant metastore changed.
          val specs = roles.zipWithIndex.map { case (role, i) =>
            NodeSpec(key, s"quack-${key.tenant}-${key.pool}-${state.size + i + 1}",
                     role, state.metastore, state.s3,
                     maxConcurrent = state.maxConcurrentPerNode)
          }
          specs.foldLeft(IO.pure(state.nodes)) { (acc, spec) =>
            acc.flatMap(rs => backend.start(spec).map(rs :+ _))
          }.flatMap { combined =>
            pools.put(key, state.copy(nodes = combined, distribution = newDist))
            persist().as(combined)
          }
        else if targetSize < state.size then
          val toRemove = state.nodes.takeRight(state.size - targetSize)
          val remaining = state.nodes.dropRight(state.size - targetSize)
          val stopAll =
            if force then toRemove.foldLeft(IO.unit)((acc, n) => acc *> backend.stop(n.nodeId))
            else
              toRemove.foldLeft(IO.unit) { (acc, n) =>
                acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *> drainAndStop(n)
              }
          stopAll *> IO.delay {
            pools.put(key, state.copy(nodes = remaining, distribution = newDist))
            ()
          } *> persist().as(remaining)
        else
          IO.pure(state.nodes)

  def stopPool(key: PoolKey, force: Boolean): IO[Unit] =
    pools.get(key) match
      case None => IO.unit
      case Some(state) =>
        val stopAll =
          if force then state.nodes.foldLeft(IO.unit)((acc, n) => acc *> backend.stop(n.nodeId))
          else
            state.nodes.foldLeft(IO.unit) { (acc, n) =>
              acc *> IO.delay(tracker.setDraining(n.nodeId, true)) *> drainAndStop(n)
            }
        stopAll *> IO.delay { pools.remove(key); () } *> persist()

  private def drainAndStop(n: RunningNode): IO[Unit] =
    // Simplified: just stop. Full drain w/ timeout is a follow-up; routing
    // already skips draining nodes via NodeLoadTracker.setDraining(true).
    backend.stop(n.nodeId)

  private def persist(): IO[Unit] = IO.blocking {
    val storedPools = pools.iterator.map { case (k, s) =>
      k.toString -> StoredPool(k, s.nodes.size, s.distribution,
                                s.metastore, s.s3, s.nodes, s.maxConcurrentPerNode)
    }.toMap
    val storedTenants = tenants.iterator.map { case (n, t) =>
      n -> StoredTenant.fromDomain(t)
    }.toMap
    store.save(StoredState(storedPools, storedTenants))
  }
