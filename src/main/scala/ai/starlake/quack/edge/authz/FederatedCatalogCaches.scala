package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{PoolKey, TenantDb}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/** A set per pool, computed on demand and kept for `ttlMs`, that a write drops at once
  * ([[invalidate]], [[invalidateAll]]: Task 11 addendum 7). A computation that started before a
  * drop is never kept after it: the drop moves a generation first, and a value put under an older
  * one is removed again, so the next lookup reads the state the write left rather than the one
  * before it. A computation that throws keeps nothing.
  */
final class PoolSetCache(clock: () => Long, ttlMs: Long):
  private final case class Entry(at: Long, set: Set[String])
  private val generation = new AtomicLong(0L)
  private val cache      = new ConcurrentHashMap[PoolKey, Entry]()

  /** The set kept for `key` while fresh, else `compute`'s, then kept. */
  def get(key: PoolKey)(compute: => Set[String]): Set[String] =
    val now = clock()
    Option(cache.get(key)).collect { case e if now - e.at < ttlMs => e.set }.getOrElse {
      val before = generation.get()
      val set    = compute
      val entry  = Entry(now, set)
      cache.put(key, entry)
      // A drop since `before` may have run before the put above: take the entry back out.
      if generation.get() != before then cache.remove(key, entry)
      set
    }

  /** Drops the set of every pool `keys` accepts. */
  def invalidate(keys: PoolKey => Boolean): Unit =
    generation.incrementAndGet()
    cache.keySet.asScala.toList.filter(keys).foreach(cache.remove)

  /** Drops every pool's set. */
  def invalidateAll(): Unit =
    generation.incrementAndGet()
    cache.clear()

/** Each pool's attached catalogs, as the router resolves names against them: the DuckDB builtins,
  * the tenant-db's own alias and every federated source alias of the tenant-db (`compute`).
  * Disabled sources are included deliberately: their alias stays ATTACHed on running nodes until
  * the pool recycles, and excluding it would re-open the two-part-name bypass. Kept `ttlMs` per
  * pool, and dropped at once by a federated-source write ([[FederatedSourceCaches]]), so a new
  * alias is known on the next statement.
  */
final class AttachedCatalogs(
    compute: PoolKey => Set[String],
    clock: () => Long = () => System.currentTimeMillis(),
    ttlMs: Long = 60_000L
):
  private val sets = new PoolSetCache(clock, ttlMs)

  def of(key: PoolKey): Set[String] = sets.get(key)(compute(key))

  def invalidate(keys: PoolKey => Boolean): Unit = sets.invalidate(keys)

  def invalidateAll(): Unit = sets.invalidateAll()

/** The per-pool caches a federated source feeds (Task 11 addendum 7): each pool's read-only
  * catalogs, minted into every user handle, and its attached catalogs, which the router resolves
  * names against. A stored source create, update or delete drops both for every pool of the
  * source's tenant-db on the serving replica ([[sourcesChanged]]) before the handler answers, so
  * the next statement carries the new read-only flag and knows the new alias; peers drop all of
  * theirs on the `qod_topology` NOTIFY the handler then sends, through their supervisor's restore
  * ([[clear]]), which the periodic snapshot refresh also runs, so a missed NOTIFY is covered too.
  * The policy version is not bumped: the node's read-only refusal runs before any cached verdict is
  * reused.
  */
final class FederatedSourceCaches(
    readOnly: FederatedReadOnlyCatalogs,
    attached: AttachedCatalogs,
    tenantDbById: String => Option[TenantDb]
):

  /** Drops both caches for every pool of the tenant-db `tenantDbId`; for every pool when that
    * tenant-db is unknown here (a cache race, a concurrent delete), so no pool keeps a stale set.
    */
  def sourcesChanged(tenantDbId: String): Unit =
    tenantDbById(tenantDbId) match
      case Some(td) =>
        val ofTenantDb: PoolKey => Boolean = k => k.tenant == td.tenantId && k.tenantDb == td.name
        readOnly.invalidate(ofTenantDb)
        attached.invalidate(ofTenantDb)
      case None => clear()

  /** Drops both caches for every pool. */
  def clear(): Unit =
    readOnly.invalidateAll()
    attached.invalidateAll()
