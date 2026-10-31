package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{PoolKey, TenantDb}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/** A set per pool, computed on demand and kept for `ttlMs`, that a write drops at once
  * ([[invalidate]], [[invalidateAll]]: Task 11 addendum 7). A computation that started before a
  * drop is never served after it: the drop moves a generation first, a value is kept under the
  * generation its computation started in, and only an entry of the current generation is served, so
  * the next lookup reads the state the write left rather than the one before it. A computation that
  * throws keeps nothing.
  */
final class PoolSetCache(clock: () => Long, ttlMs: Long):
  /** A kept set, with the generation read before it was computed. */
  private final case class Entry(at: Long, generation: Long, set: Set[String])
  private val generation = new AtomicLong(0L)
  private val cache      = new ConcurrentHashMap[PoolKey, Entry]()

  /** The set kept for `key` while fresh, else `compute`'s, then kept. An entry of an older
    * generation is a miss: a computation that straddled a drop is kept under the generation it
    * started in, so no lookup that starts after the drop is ever served it (final review A567-m3:
    * putting it and then taking it back left a window where one was).
    */
  def get(key: PoolKey)(compute: => Set[String]): Set[String] =
    val now     = clock()
    val current = generation.get()
    Option(cache.get(key))
      .collect { case e if e.generation == current && now - e.at < ttlMs => e.set }
      .getOrElse {
        val set = compute
        cache.put(key, Entry(now, current, set))
        set
      }

  /** Drops the set of every pool `keys` accepts. The others current until now are kept under the
    * new generation (one a lookup reads in between is a miss, computed again). Only those: an entry
    * of an older generation may be a computation that straddled an earlier drop, which no later
    * drop may revive.
    */
  def invalidate(keys: PoolKey => Boolean): Unit =
    val next = generation.incrementAndGet()
    cache.asScala.toList.foreach { (key, entry) =>
      if keys(key) then cache.remove(key)
      else if entry.generation == next - 1 then
        cache.replace(key, entry, entry.copy(generation = next))
    }

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
