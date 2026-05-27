package ai.starlake.acl.api

import ai.starlake.acl.AclError
import ai.starlake.acl.model.{AclPolicy, TenantId}
import ai.starlake.acl.policy.TenantLoader
import ai.starlake.acl.store.AclStore

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Thread-safe cache for tenant grants with optional LRU eviction.
  *
  * Uses ConcurrentHashMap for lock-free reads. Cache misses trigger loading via
  * TenantLoader with per-tenant locking to prevent thundering herd on cold
  * cache.
  *
  * @param store
  *   Storage backend for reading ACL files
  * @param maxTenants
  *   Maximum number of tenants to cache (None = unlimited)
  */
private[api] class TenantCache(store: AclStore, maxTenants: Option[Int]):

  private case class CacheEntry(
      policy: Either[AclError, AclPolicy],
      loadedAt: Instant,
      staleReason: Option[(Instant, String)] = None
  )

  private val cache = new ConcurrentHashMap[TenantId, CacheEntry]()
  private val accessOrder = new java.util.concurrent.ConcurrentLinkedDeque[TenantId]()
  private val evictionLock = new ReentrantLock()
  private val loadLocks = new ConcurrentHashMap[TenantId, ReentrantLock]()

  /** Get cached grants or load from store.
    *
    * @param tenant
    *   Tenant identifier
    * @return
    *   (Either[AclError, AclPolicy], usedStale: Boolean)
    */
  def getOrLoad(tenant: TenantId): (Either[AclError, AclPolicy], Boolean) =
    Option(cache.get(tenant)) match
      case Some(entry) =>
        updateAccessOrder(tenant)
        (entry.policy, entry.staleReason.isDefined)
      case None =>
        loadWithLock(tenant)

  /** Invalidate a tenant's cache entry. */
  def invalidate(tenant: TenantId): Unit =
    val _ = cache.remove(tenant)
    val _ = accessOrder.remove(tenant)
    val _ = loadLocks.remove(tenant)

  /** Invalidate all cached entries. */
  def invalidateAll(): Unit =
    cache.clear()
    accessOrder.clear()
    loadLocks.clear()

  /** Get status of a tenant's cache. */
  def status(tenant: TenantId): TenantStatus =
    Option(cache.get(tenant)) match
      case None => TenantStatus.NotLoaded
      case Some(entry) =>
        entry.staleReason match
          case None                  => TenantStatus.Fresh(entry.loadedAt)
          case Some((failedAt, msg)) => TenantStatus.Stale(entry.loadedAt, failedAt, msg)

  /** Attempt to reload a tenant's grants, keeping stale on failure. */
  def reload(tenant: TenantId): Unit =
    val lock = loadLocks.computeIfAbsent(tenant, _ => new ReentrantLock())
    lock.lock()
    try
      Option(cache.get(tenant)) match
        case Some(existing) =>
          // Try reload, mark stale on failure
          TenantLoader.load(store, tenant).toEither match
            case Right(policy) =>
              val _ = cache.put(tenant, CacheEntry(Right(policy), Instant.now()))
            case Left(errors) =>
              val msg = errors.toList.map(_.message).mkString("; ")
              val _ = cache.put(
                tenant,
                existing.copy(
                  staleReason = Some((Instant.now(), msg))
                )
              )
        case None =>
          // Not cached, do fresh load
          val _ = loadWithLock(tenant)
    finally lock.unlock()

  private def loadWithLock(tenant: TenantId): (Either[AclError, AclPolicy], Boolean) =
    val lock = loadLocks.computeIfAbsent(tenant, _ => new ReentrantLock())
    lock.lock()
    try
      // Double-check after acquiring lock
      Option(cache.get(tenant)) match
        case Some(entry) =>
          (entry.policy, entry.staleReason.isDefined)
        case None =>
          val result = TenantLoader.load(store, tenant).toEither.left.map(_.head)
          val entry = CacheEntry(result, Instant.now())
          cache.put(tenant, entry)
          updateAccessOrder(tenant)
          maybeEvict()
          (result, false)
    finally lock.unlock()

  private def updateAccessOrder(tenant: TenantId): Unit =
    accessOrder.remove(tenant)
    accessOrder.addLast(tenant)

  private def maybeEvict(): Unit =
    maxTenants.foreach { max =>
      if cache.size() > max then
        evictionLock.lock()
        try
          while cache.size() > max do
            Option(accessOrder.pollFirst()).foreach { oldest =>
              cache.remove(oldest)
              loadLocks.remove(oldest)
            }
        finally evictionLock.unlock()
    }
